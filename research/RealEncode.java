import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.io.BigEndianNbtOutputStream;
import io.github.ensgijs.nbt.io.NamedTag;
import io.github.ensgijs.nbt.tag.*;
import java.io.*;
import java.util.*;
import java.util.zip.Deflater;

// Runs OUR encoder against REAL Minecraft region files and compares to the actual file on disk.
// This is the "floor" measurement: no feature extraction, pure overhead avoidance.
//   we store   : per-section palette (global state ids) + fixed-min-4-bit packed indices,
//                DEFLATE per chunk, plus block-entity NBT verbatim
//   we DERIVE  : light (skyLight/blockLight), heightmaps  -> not stored at all
//   we skip    : sector padding, per-section NBT wrappers, palette strings
//
// Lossless w.r.t. block states: the key is the FULL state (Name + Properties), so waterlogged
// stairs and plain stairs are distinct entries.

public class RealEncode {
    static HashMap<String,Integer> STATE=new HashMap<>();
    static ArrayList<String> STATE_LIST=new ArrayList<>();

    static String key(CompoundTag st){
        if(st==null) return "minecraft:air";
        String n=st.getString("Name");
        CompoundTag p=st.getCompoundTag("Properties");
        if(p==null||p.size()==0) return n;
        StringBuilder sb=new StringBuilder(n).append('[');
        ArrayList<String> ks=new ArrayList<>(p.keySet());
        Collections.sort(ks);
        for(int i=0;i<ks.size();i++){
            if(i>0) sb.append(',');
            sb.append(ks.get(i)).append('=').append(p.get(ks.get(i)).valueToString());
        }
        return sb.append(']').toString();
    }
    static int stateId(CompoundTag st){
        String k=key(st);
        Integer id=STATE.get(k);
        if(id==null){ id=STATE_LIST.size(); STATE.put(k,id); STATE_LIST.add(k); }
        return id;
    }
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
    static void wVar(ByteArrayOutputStream bo,int n){
        while((n&~0x7F)!=0){bo.write((n&0x7F)|0x80);n>>>=7;} bo.write(n);
    }
    static long dlen(byte[] in,int lvl){
        Deflater d=new Deflater(lvl); d.setInput(in); d.finish();
        byte[] o=new byte[1<<16]; long t=0;
        while(!d.finished()){int n=d.deflate(o); if(n>0)t+=n; else break;}
        d.end(); return t;
    }

    public static void main(String[] args) throws Exception {
        File dir=new File(args.length>0?args[0]:"/tmp/pregen/world/region");
        int maxR=args.length>1?Integer.parseInt(args[1]):999;
        File[] files=dir.listFiles((d,n)->n.endsWith(".mca"));
        Arrays.sort(files);
        int nR=Math.min(maxR,files.length);
        System.out.printf("=== OUR ENCODER vs REAL .mca : %s (%d regions) ===%n%n",dir,nR);

        long vanillaBytes=0, ourBytes=0, ourBlocksOnly=0, beBytes=0;
        long nChunks=0, nSections=0, emptySections=0;
        long rawBeforeDeflate=0;
        long t0=System.currentTimeMillis();

        for(int fi=0;fi<nR;fi++){
            File f=files[fi];
            vanillaBytes+=f.length();
            McaRegionFile mca;
            try{ mca=McaFileHelpers.read(f); }catch(Exception e){ System.out.println("  skip "+f.getName()); continue; }

            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
                TerrainChunk c;
                try{ c=mca.getChunk(cx,cz); }catch(Exception e){ continue; }
                if(c==null) continue;
                nChunks++;

                ByteArrayOutputStream chunkBlob=new ByteArrayOutputStream(1<<16);
                int minS=c.getMinSectionY(), maxS=c.getMaxSectionY();
                wVar(chunkBlob, minS+128); wVar(chunkBlob, maxS+128);

                for(int sy=minS; sy<=maxS; sy++){
                    TerrainSection s=c.getSection(sy);
                    if(s==null){ chunkBlob.write(0); continue; }   // 0 = absent section
                    var pc=s.getBlockStates();
                    if(pc==null){ chunkBlob.write(0); continue; }
                    nSections++;

                    // local palette of global state ids
                    HashMap<Integer,Integer> loc=new HashMap<>();
                    ArrayList<Integer> locList=new ArrayList<>();
                    int[] idx=new int[4096];
                    for(int i=0;i<4096;i++){
                        int gid=stateId(pc.getByRef(i));
                        Integer li=loc.get(gid);
                        if(li==null){ li=locList.size(); loc.put(gid,li); locList.add(gid); }
                        idx[i]=li;
                    }
                    int np=locList.size();
                    if(np==1){
                        // whole-section fill: 1 marker + the single state id, no index data
                        chunkBlob.write(1);
                        wVar(chunkBlob, locList.get(0));
                        if(STATE_LIST.get(locList.get(0)).equals("minecraft:air")) emptySections++;
                        continue;
                    }
                    chunkBlob.write(2);
                    wVar(chunkBlob, np);
                    for(int gid:locList) wVar(chunkBlob, gid);
                    int bits=Math.max(4,log2ceil(np));
                    int per=64/bits, li2=0, sh=0;
                    long[] pk=new long[(4096+per-1)/per];
                    for(int i=0;i<4096;i++){ pk[li2]|=((long)idx[i])<<(sh*bits); if(++sh==per){sh=0;li2++;} }
                    for(long L:pk) for(int b=0;b<8;b++) chunkBlob.write((int)((L>>>(b*8))&0xFF));
                }

                byte[] blocks=chunkBlob.toByteArray();
                rawBeforeDeflate+=blocks.length;
                long dBlocks=dlen(blocks,6);
                ourBlocksOnly+=dBlocks;

                // block entities carried verbatim (the irreducible floor)
                long dBe=0;
                try{
                    ListTag<CompoundTag> te=c.getTileEntities();
                    if(te!=null && !te.isEmpty()){
                        ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<13);
                        try(BigEndianNbtOutputStream os=new BigEndianNbtOutputStream(bo,false)){
                            os.writeTag(new NamedTag("",te),512);
                        }
                        dBe=dlen(bo.toByteArray(),6);
                    }
                }catch(Exception e){}
                beBytes+=dBe;
                ourBytes+=dBlocks+dBe;
            }
            if((fi+1)%6==0) System.out.printf("  ... %d/%d regions, %,d chunks, %ds%n",
                fi+1,nR,nChunks,(System.currentTimeMillis()-t0)/1000);
        }

        System.out.printf("%n  global distinct block states: %,d%n",STATE_LIST.size());
        System.out.printf("  chunks %,d | sections stored %,d | whole-air sections %,d%n",
            nChunks,nSections,emptySections);
        System.out.printf("%n  vanilla on disk    : %,15d B   (%,d B/chunk)%n",
            vanillaBytes, vanillaBytes/Math.max(1,nChunks));
        System.out.printf("  ours: blocks only  : %,15d B   (%,d B/chunk)%n",
            ourBlocksOnly, ourBlocksOnly/Math.max(1,nChunks));
        System.out.printf("  ours: + block ents : %,15d B   (%,d B/chunk)   [BE = %,d B total]%n",
            ourBytes, ourBytes/Math.max(1,nChunks), beBytes);
        System.out.printf("  our raw pre-deflate: %,15d B%n",rawBeforeDeflate);
        System.out.printf("%n  *** RATIO vs real .mca : %.2fx ***%n", vanillaBytes/(double)ourBytes);
        System.out.printf("      (blocks-only ratio   : %.2fx)%n", vanillaBytes/(double)ourBlocksOnly);
        System.out.printf("%n  we do NOT store: skyLight, blockLight, heightmaps, sector padding,%n");
        System.out.printf("                   per-section NBT wrappers, palette strings%n");
        System.out.printf("  elapsed %ds%n",(System.currentTimeMillis()-t0)/1000);
    }
}
