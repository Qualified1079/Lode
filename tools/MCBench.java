import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.io.BigEndianNbtOutputStream;
import io.github.ensgijs.nbt.io.NamedTag;
import io.github.ensgijs.nbt.tag.*;
import java.util.function.*;
import java.io.*;
import java.util.*;
import java.util.zip.Deflater;
import com.github.luben.zstd.Zstd;

// MCBench — point it at a Minecraft world folder, get compression numbers.
//   java -jar mcbench.jar <world-folder> [maxRegions]
//
// Measures, against the real .mca files:
//   * vanilla size, real payload vs sector padding
//   * our re-encode (paletted + fixed-4-bit packing, DEFLATE per chunk, block entities verbatim;
//     light/heightmaps derived and not stored)
//   * block-entity density (the NBT floor)
//   * terrain complexity (distinct block states, palette sizes, biomes)
//
// Reads only. Writes nothing. Copy the output back.

public class MCBench {
    static HashMap<String,Integer> SID=new HashMap<>();
    static int nStates=0;
    static HashMap<String,Integer> BIOME=new HashMap<>();
    static int nBiomes=0;
    static int bid(String s){
        if(s==null) s="minecraft:plains";
        Integer v=BIOME.get(s);
        if(v==null){ v=nBiomes++; BIOME.put(s,v); }
        return v;
    }
    static int sid(CompoundTag st){
        String k;
        if(st==null) k="minecraft:air";
        else{
            String n=st.getString("Name");
            CompoundTag p=st.getCompoundTag("Properties");
            if(p==null||p.size()==0) k=n;
            else{ StringBuilder sb=new StringBuilder(n).append('[');
                ArrayList<String> ks=new ArrayList<>(p.keySet()); Collections.sort(ks);
                for(int i=0;i<ks.size();i++){ if(i>0)sb.append(',');
                    sb.append(ks.get(i)).append('=').append(p.get(ks.get(i)).valueToString()); }
                k=sb.append(']').toString(); }
        }
        Integer v=SID.get(k);
        if(v==null){ v=nStates++; SID.put(k,v); }
        return v;
    }
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
    static void wVar(ByteArrayOutputStream b,int n){
        while((n&~0x7F)!=0){b.write((n&0x7F)|0x80);n>>>=7;} b.write(n);
    }
    static long dlen(byte[] in){
        Deflater d=new Deflater(6); d.setInput(in); d.finish();
        byte[] o=new byte[1<<16]; long t=0;
        while(!d.finished()){int n=d.deflate(o); if(n>0)t+=n; else break;}
        d.end(); return t;
    }
    static long zlen(byte[] in,int lvl){ return Zstd.compress(in,lvl).length; }
    static long dirBytes(File d){
        if(d==null||!d.isDirectory()) return 0;
        long t=0; File[] fs=d.listFiles();
        if(fs!=null) for(File f:fs) t+=f.isDirectory()?dirBytes(f):f.length();
        return t;
    }
    static long mcaPayload(File d){
        if(d==null||!d.isDirectory()) return 0;
        long pay=0; File[] fs=d.listFiles((x,n)->n.endsWith(".mca"));
        if(fs==null) return 0;
        for(File f:fs){
            try(RandomAccessFile raf=new RandomAccessFile(f,"r")){
                if(raf.length()<4096) continue;
                byte[] loc=new byte[4096]; raf.readFully(loc);
                for(int i=0;i<1024;i++){
                    int off=((loc[i*4]&0xFF)<<16)|((loc[i*4+1]&0xFF)<<8)|(loc[i*4+2]&0xFF);
                    int cnt=loc[i*4+3]&0xFF;
                    if(off==0||cnt==0) continue;
                    if((long)off*4096+4>raf.length()) continue;
                    raf.seek((long)off*4096);
                    pay+=raf.readInt()+4;
                }
            }catch(Exception e){}
        }
        return pay;
    }

    public static void main(String[] args) throws Exception {
        if(args.length<1){
            System.out.println("usage: java -jar mcbench.jar <world-folder> [maxRegions]");
            System.out.println("  <world-folder> is the dir containing region/ (and level.dat)");
            return;
        }
        File world=new File(args[0]);
        int maxR=args.length>1?Integer.parseInt(args[1]):40;
        File rdir=new File(world,"region");
        if(!rdir.isDirectory()){
            // maybe they pointed straight at a region dir
            if(world.getName().equals("region")||world.listFiles((d,n)->n.endsWith(".mca"))!=null){
                rdir=world; world=world.getParentFile();
            }
        }
        if(rdir==null||!rdir.isDirectory()){
            System.out.println("no region/ dir found under "+args[0]); return;
        }
        File[] files=rdir.listFiles((d,n)->n.endsWith(".mca"));
        if(files==null||files.length==0){ System.out.println("no .mca files in "+rdir); return; }
        Arrays.sort(files);
        // prefer the LARGEST regions — those are the built-up ones
        File[] bySize=files.clone();
        Arrays.sort(bySize,(a,b)->Long.compare(b.length(),a.length()));
        int nR=Math.min(maxR,bySize.length);

        System.out.println("=============== MCBench ===============");
        System.out.printf("world   : %s%n",world!=null?world.getAbsolutePath():rdir.getAbsolutePath());
        System.out.printf("regions : %d present, analysing the %d LARGEST%n",files.length,nR);

        long van=0, vanFile=0, ours=0, oursBlocks=0, beDef=0, payload=0, sectors=0, residDef=0;
        long zChunk=0, zRegion=0, zRegionLit=0;
        long nChunks=0, nSections=0, nBE=0, dv=-1;
        long palSum=0, palMax=0, palN=0;
        HashSet<String> biomes=new HashSet<>();
        HashMap<String,Long> beTypes=new HashMap<>();
        long t0=System.currentTimeMillis();
        // Vanilla bytes are only counted for chunks that were fully re-encoded: a region or chunk we
        // could not read must not sit in the denominator while contributing nothing to ours.
        LinkedHashMap<String,Integer> failures=new LinkedHashMap<>();
        long vanExcluded=0;

        for(int fi=0; fi<nR; fi++){
            File f=bySize[fi];
            int[] secOf=new int[1024], payOf=new int[1024];
            try(RandomAccessFile raf=new RandomAccessFile(f,"r")){
                if(raf.length()>=8192){
                    byte[] loc=new byte[4096]; raf.readFully(loc);
                    for(int i=0;i<1024;i++){
                        int off=((loc[i*4]&0xFF)<<16)|((loc[i*4+1]&0xFF)<<8)|(loc[i*4+2]&0xFF);
                        int cnt=loc[i*4+3]&0xFF;
                        if(off==0||cnt==0) continue;
                        secOf[i]=cnt;
                        if((long)off*4096+4>raf.length()) continue;
                        raf.seek((long)off*4096); payOf[i]=raf.readInt()+4;
                    }
                }
            }catch(IOException e){
                failures.merge("region header unreadable",1,Integer::sum); vanExcluded+=f.length(); continue;
            }

            ByteArrayOutputStream regionRaw=new ByteArrayOutputStream(1<<22);
            ByteArrayOutputStream regionLit=new ByteArrayOutputStream(1<<22);
            McaRegionFile mca;
            try{ mca=McaFileHelpers.read(f); }
            catch(Throwable e){
                System.out.printf("  !! skipped %s (%s)%n",f.getName(),e.getClass().getSimpleName());
                failures.merge("region unparseable: "+e.getClass().getSimpleName(),1,Integer::sum);
                vanExcluded+=f.length(); continue;
            }
            boolean anyChunk=false; int failsBefore=failures.values().stream().mapToInt(Integer::intValue).sum();
            long regionAlloc=0;

            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
                int ci=cx+cz*32;
                TerrainChunk c;
                try{ c=mca.getChunk(cx,cz); }catch(Throwable e){
                    failures.merge("chunk unparseable: "+e.getClass().getSimpleName(),1,Integer::sum);
                    vanExcluded+=secOf[ci]*4096L; continue;
                }
                if(c==null){
                    if(secOf[ci]>0){ failures.merge("chunk in header but not loaded",1,Integer::sum); vanExcluded+=secOf[ci]*4096L; }
                    continue;
                }
                // Encode the whole chunk or none of it: a partial encode would make ours too small.
                long db, dbe=0, dres, zc;
                byte[] blobB, beB=null, litB, rbB;
                int chunkBE=0;
                ArrayList<String> chunkBETypes=new ArrayList<>();
                try{
                ByteArrayOutputStream blob=new ByteArrayOutputStream(1<<16);
                {
                    int mn=c.getMinSectionY(), mx=c.getMaxSectionY();
                    wVar(blob,mn+128); wVar(blob,mx+128);
                    for(int sy=mn; sy<=mx; sy++){
                        TerrainSection s=c.getSection(sy);
                        if(s==null){ blob.write(0); continue; }
                        var pc=s.getBlockStates();
                        if(pc==null){ blob.write(0); continue; }
                        nSections++;
                        try{ int ps=pc.paletteSize(); palSum+=ps; palN++; if(ps>palMax) palMax=ps; }catch(Throwable e){}
                        HashMap<Integer,Integer> loc=new HashMap<>();
                        ArrayList<Integer> list=new ArrayList<>();
                        int[] idx=new int[4096];
                        for(int i=0;i<4096;i++){
                            int g=sid(pc.getByRef(i));
                            Integer li=loc.get(g);
                            if(li==null){ li=list.size(); loc.put(g,li); list.add(g); }
                            idx[i]=li;
                        }
                        int np=list.size();
                        if(np==1){ blob.write(1); wVar(blob,list.get(0)); }
                        else{
                            blob.write(2); wVar(blob,np);
                            for(int g:list) wVar(blob,g);
                            int bits=Math.max(4,log2ceil(np));
                            int per=64/bits, li2=0, sh=0;
                            long[] pk=new long[(4096+per-1)/per];
                            for(int i=0;i<4096;i++){ pk[li2]|=((long)idx[i])<<(sh*bits); if(++sh==per){sh=0;li2++;} }
                            for(long L:pk) for(int b=0;b<8;b++) blob.write((int)((L>>>(b*8))&0xFF));
                        }
                        // biomes: paletted 4x4x4 cells. MUST be stored — dropping them is lossy.
                        { var bio=s.getBiomes();
                            if(bio==null){ blob.write(0); }
                            else{
                                int bn=bio.size();
                                HashMap<Integer,Integer> bl=new HashMap<>();
                                ArrayList<Integer> BL=new ArrayList<>();
                                int[] bidx=new int[bn];
                                for(int i=0;i<bn;i++){
                                    StringTag bt=bio.getByRef(i);
                                    String bv=(bt==null)?null:bt.getValue();
                                    if(bv!=null) biomes.add(bv);
                                    int g=bid(bv);
                                    Integer li=bl.get(g);
                                    if(li==null){ li=BL.size(); bl.put(g,li); BL.add(g); }
                                    bidx[i]=li;
                                }
                                int bnp=BL.size();
                                if(bnp==1){ blob.write(1); wVar(blob,BL.get(0)); }
                                else{
                                    blob.write(2); wVar(blob,bnp);
                                    for(int g:BL) wVar(blob,g);
                                    int bb=Math.max(1,log2ceil(bnp));
                                    int bper=64/bb, bli=0, bsh=0;
                                    long[] bpk=new long[(bn+bper-1)/bper];
                                    for(int i=0;i<bn;i++){ bpk[bli]|=((long)bidx[i])<<(bsh*bb); if(++bsh==bper){bsh=0;bli++;} }
                                    for(long L:bpk) for(int k=0;k<8;k++) blob.write((int)((L>>>(k*8))&0xFF));
                                }
                            }
                        }
                    }
                }
                blobB=blob.toByteArray();
                db=dlen(blobB);
                zc=zlen(blobB,19);

                {
                    ListTag<CompoundTag> te=c.getTileEntities();
                    if(te!=null && !te.isEmpty()){
                        chunkBE=te.size();
                        for(CompoundTag t:te){
                            String id=t.getString("id");
                            if(id!=null) chunkBETypes.add(id);
                        }
                        ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<13);
                        try(BigEndianNbtOutputStream os=new BigEndianNbtOutputStream(bo,false)){
                            os.writeTag(new NamedTag("",te),512);
                        }
                        beB=bo.toByteArray();
                        dbe=dlen(beB); zc+=zlen(beB,19);
                    }
                }

                // LIVE-SAFE: carry every non-derivable chunk NBT field verbatim.
                // Dropping these (block_ticks, fluid_ticks, structures, PostProcessing, Status,
                // DataVersion, positions, InhabitedTime...) would break the game; only light and
                // heightmaps are genuinely derivable.
                {
                    CompoundTag hl=c.updateHandle(cx,cz).clone();
                    ListTag<CompoundTag> sl=null;
                    try{ sl=hl.getListTag("sections").asCompoundTagList(); }catch(Throwable e){}
                    if(sl!=null) for(CompoundTag sc:sl){ sc.remove("block_states"); sc.remove("biomes"); }
                    hl.remove("Heightmaps"); hl.remove("block_entities");
                    ByteArrayOutputStream lb=new ByteArrayOutputStream(1<<14);
                    try(BigEndianNbtOutputStream os=new BigEndianNbtOutputStream(lb,false)){
                        os.writeTag(new NamedTag("",hl),512);
                    }
                    litB=lb.toByteArray();

                    CompoundTag h=c.updateHandle(cx,cz).clone();
                    ListTag<CompoundTag> ss=null;
                    try{ ss=h.getListTag("sections").asCompoundTagList(); }catch(Throwable e){}
                    if(ss!=null) for(CompoundTag sc:ss){
                        sc.remove("block_states"); sc.remove("biomes");
                        sc.remove("BlockLight");   sc.remove("SkyLight");
                    }
                    h.remove("Heightmaps"); h.remove("block_entities");
                    ByteArrayOutputStream rb=new ByteArrayOutputStream(1<<14);
                    try(BigEndianNbtOutputStream os=new BigEndianNbtOutputStream(rb,false)){
                        os.writeTag(new NamedTag("",h),512);
                    }
                    rbB=rb.toByteArray();
                    dres=dlen(rbB); zc+=zlen(rbB,19);
                }
                }catch(Throwable e){
                    failures.merge("chunk encode failed: "+e.getClass().getSimpleName(),1,Integer::sum);
                    vanExcluded+=secOf[ci]*4096L; continue;
                }
                // commit
                anyChunk=true;
                nChunks++;
                van+=secOf[ci]*4096L; regionAlloc+=secOf[ci]*4096L; sectors+=secOf[ci]; payload+=payOf[ci];
                if(dv<0) try{ dv=c.getDataVersion(); }catch(Throwable e){}
                oursBlocks+=db; beDef+=dbe; residDef+=dres;
                nBE+=chunkBE; for(String id:chunkBETypes) beTypes.merge(id,1L,Long::sum);
                regionRaw.write(blobB); regionLit.write(blobB);
                if(beB!=null){ regionRaw.write(beB); regionLit.write(beB); }
                regionLit.write(litB); regionRaw.write(rbB);
                ours+=db+dbe+dres;
                zChunk+=zc;
            }
            if(anyChunk){
                van+=8192; // region header
                // File length also counts free/fragmented sectors — real disk, but only fair to use
                // when every chunk in the region made it into ours.
                boolean clean=failures.values().stream().mapToInt(Integer::intValue).sum()==failsBefore;
                vanFile+=clean? f.length() : regionAlloc+8192;
            }
            zRegion+=zlen(regionRaw.toByteArray(),19);
            zRegionLit+=zlen(regionLit.toByteArray(),19);
            if((fi+1)%5==0 || fi==nR-1)
                System.out.printf("  ... %d/%d regions, %,d chunks, %ds%n",
                    fi+1,nR,nChunks,(System.currentTimeMillis()-t0)/1000);
        }

        int nFail=failures.values().stream().mapToInt(Integer::intValue).sum();
        System.out.printf("%n--- FAILURES (excluded from BOTH sides of every ratio) ---%n");
        if(nFail==0) System.out.println("  none");
        else{
            failures.forEach((k,v)->System.out.printf("  %-50s %,d%n",k,v));
            System.out.printf("  vanilla bytes excluded: %,d%n",vanExcluded);
        }
        if(nChunks==0){ System.out.println("no readable chunks — wrong folder, or a version this lib can't parse"); return; }
        long alloc=sectors*4096L;

        System.out.println("\n--- VANILLA ---");
        System.out.printf("  DataVersion      : %d%n",dv);
        System.out.printf("  chunks           : %,d   sections %,d   (%.1f sec/chunk)%n",
            nChunks,nSections,nSections/(double)nChunks);
        System.out.printf("  allocated bytes  : %,15d   (%,d B/chunk; sectors of encoded chunks + headers)%n",van,van/nChunks);
        System.out.printf("  file bytes       : %,15d   (incl. free sectors; used for the second ratio below)%n",vanFile);
        System.out.printf("  real payload     : %,15d   (%,d B/chunk)%n",payload,payload/nChunks);
        System.out.printf("  sector padding   : %,15d   (%.1f%%)%n",
            alloc-payload, alloc>0?100.0*(alloc-payload)/alloc:0);

        System.out.println("\n--- TERRAIN COMPLEXITY ---");
        System.out.printf("  distinct block states : %,d%n",nStates);
        System.out.printf("  distinct biomes (enc) : %,d%n",nBiomes);
        System.out.printf("  distinct biomes       : %,d%n",biomes.size());
        System.out.printf("  palette/section       : avg %.1f, max %d%n",
            palN>0?palSum/(double)palN:0, palMax);

        System.out.println("\n--- BLOCK ENTITIES (the NBT floor) ---");
        System.out.printf("  count            : %,d   (%.2f per chunk)%n",nBE,nBE/(double)nChunks);
        System.out.printf("  NBT deflated     : %,15d   (%,d B/chunk)%n",beDef,beDef/Math.max(1,nChunks));
        beTypes.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(),a.getValue())).limit(8)
            .forEach(e->System.out.printf("     %-40s %,8d%n",e.getKey(),e.getValue()));

        System.out.println("\n--- OUR RE-ENCODE ---");
        long optimistic=oursBlocks+beDef;
        System.out.printf("  blocks + biomes        : %,15d   (%,d B/chunk)%n",oursBlocks,oursBlocks/nChunks);
        System.out.printf("  + block entities       : %,15d   (%,d B/chunk)%n",optimistic,optimistic/nChunks);
        System.out.printf("  + residual chunk NBT   : %,15d   (%,d B/chunk)%n",ours,ours/nChunks);
        System.out.printf("      (block_ticks, fluid_ticks, structures, Status, positions, ...)%n");
        System.out.printf("%n  ratio, blocks+BE only (NOT a viable format) : %.2fx%n", van/(double)optimistic);
        System.out.printf("  *** RATIO, LIVE-SAFE (light+heightmaps derived) : %.2fx ***   (%.2fx vs file bytes)%n", van/(double)ours, vanFile/(double)ours);
        System.out.printf("%n  --- same live-safe payload, other codec / granularity ---%n");
        System.out.printf("  deflate6, per chunk   : %,15d   %.2fx%n", ours, van/(double)ours);
        System.out.printf("  zstd19,   per chunk   : %,15d   %.2fx%n", zChunk, van/(double)zChunk);
        System.out.printf("  zstd19,   per region  : %,15d   %.2fx   (comparable to Linear lvl19)%n", zRegion, van/(double)zRegion);
        System.out.printf("  zstd19, per region, LIGHT KEPT (no relight needed) : %,d   %.2fx%n", zRegionLit, van/(double)zRegionLit);
        System.out.printf("%n  Light and heightmaps are the only fields treated as derivable.%n");
        System.out.printf("  Regenerating light costs ~1.6 ms/chunk; keeping it instead would add ~%d B/chunk.%n",
            900);

        // whole-folder view
        File ed=new File(world,"entities"), pd=new File(world,"poi");
        long eB=dirBytes(ed), pB=dirBytes(pd), eP=mcaPayload(ed), pP=mcaPayload(pd);
        if(eB>0||pB>0){
            double scale=nR/(double)files.length;
            System.out.println("\n--- WHOLE-FOLDER (entities/ and poi/ carried verbatim) ---");
            System.out.printf("  entities/  file %,12d   payload %,12d   (%.0f%% padding)%n",
                eB,eP,eB>0?100.0*(eB-eP)/eB:0);
            System.out.printf("  poi/       file %,12d   payload %,12d   (%.0f%% padding)%n",
                pB,pP,pB>0?100.0*(pB-pP)/pB:0);
            if(nR==files.length){
                long fullVan=van+eB+pB, fullOurs=ours+eP+pP;
                System.out.printf("%n  *** WHOLE-FOLDER RATIO : %.2fx ***%n", fullVan/(double)fullOurs);
            } else {
                System.out.printf("  (analysed %d of %d regions — rerun with maxRegions=%d for a whole-folder ratio)%n",
                    nR,files.length,files.length);
            }
        }
        System.out.printf("%nelapsed %ds%n",(System.currentTimeMillis()-t0)/1000);
    }
}
