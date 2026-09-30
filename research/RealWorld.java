import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import java.io.*;
import java.util.*;
import java.util.zip.Deflater;

// Analysis harness for REAL Minecraft region files (or any .mca directory).
// Answers the questions simulation could not:
//   1. True vanilla baseline: bytes/chunk, payload vs sector padding
//   2. Terrain complexity vs my synthetic model: distinct block states, palette sizes, biomes
//   3. Block-entity density from real villages/mineshafts (the NBT floor)
//   4. ORE-BLOB DETECTION RATE -- the number this whole investigation has been waiting on
//
// Usage: java RealWorld <region-dir> [maxRegions]

public class RealWorld {
    static final int SEC=16;

    static byte[] deflate(byte[] in,int lvl){
        Deflater d=new Deflater(lvl); d.setInput(in); d.finish();
        ByteArrayOutputStream bo=new ByteArrayOutputStream(in.length/4+64);
        byte[] o=new byte[1<<16];
        while(!d.finished()){int n=d.deflate(o); if(n>0)bo.write(o,0,n); else break;}
        d.end(); return bo.toByteArray();
    }
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}

    // block ids we treat as "ore-like" — these are what blob detection targets
    static final Set<String> ORELIKE = new HashSet<>(Arrays.asList(
        "minecraft:coal_ore","minecraft:deepslate_coal_ore",
        "minecraft:iron_ore","minecraft:deepslate_iron_ore",
        "minecraft:copper_ore","minecraft:deepslate_copper_ore",
        "minecraft:gold_ore","minecraft:deepslate_gold_ore",
        "minecraft:redstone_ore","minecraft:deepslate_redstone_ore",
        "minecraft:diamond_ore","minecraft:deepslate_diamond_ore",
        "minecraft:lapis_ore","minecraft:deepslate_lapis_ore",
        "minecraft:emerald_ore","minecraft:deepslate_emerald_ore",
        "minecraft:andesite","minecraft:granite","minecraft:diorite","minecraft:tuff",
        "minecraft:gravel","minecraft:dirt","minecraft:clay"
    ));

    public static void main(String[] args) throws Exception {
        File dir=new File(args.length>0?args[0]:"mcaout");
        int maxR=args.length>1?Integer.parseInt(args[1]):999;
        File[] files=dir.listFiles((d,n)->n.endsWith(".mca"));
        if(files==null||files.length==0){ System.out.println("no .mca in "+dir); return; }
        Arrays.sort(files);
        int nR=Math.min(maxR,files.length);
        System.out.printf("=== REAL WORLD ANALYSIS: %s (%d of %d region files) ===%n%n",
            dir, nR, files.length);

        long totFileBytes=0, totPayload=0, totSectors=0, nChunks=0, nSections=0;
        long totBlockEntities=0, totEntitiesTagged=0;
        Map<String,Long> blockCensus=new HashMap<>();
        Map<String,Long> beCensus=new HashMap<>();
        Set<String> biomes=new HashSet<>();
        long paletteSum=0, paletteMax=0, sectionsWithPalette=0;
        long beNbtRaw=0;
        ByteArrayOutputStream allBeNbt=new ByteArrayOutputStream(1<<22);
        // ore-blob detection accounting
        long oreVoxels=0;
        Map<String,Long> oreByType=new HashMap<>();
        List<Integer> compSizes=new ArrayList<>();

        for(int fi=0; fi<nR; fi++){
            File f=files[fi];
            totFileBytes+=f.length();
            // raw sector/payload accounting straight from the header
            try(RandomAccessFile raf=new RandomAccessFile(f,"r")){
                byte[] loc=new byte[4096]; raf.readFully(loc);
                for(int i=0;i<1024;i++){
                    int off=((loc[i*4]&0xFF)<<16)|((loc[i*4+1]&0xFF)<<8)|(loc[i*4+2]&0xFF);
                    int cnt=loc[i*4+3]&0xFF;
                    if(off==0||cnt==0) continue;
                    totSectors+=cnt;
                    raf.seek((long)off*4096);
                    totPayload+=raf.readInt()+4;
                }
            }
            McaRegionFile mca;
            try { mca=McaFileHelpers.read(f); }
            catch(Exception e){ System.out.printf("  !! %s unreadable: %s%n",f.getName(),e); continue; }

            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
                TerrainChunk c;
                try { c=mca.getChunk(cx,cz); } catch(Exception e){ continue; }
                if(c==null) continue;
                nChunks++;
                ListTag<CompoundTag> te=null;
                try { te=c.getTileEntities(); } catch(Exception e){}
                if(te!=null && !te.isEmpty()){
                    totBlockEntities+=te.size();
                    for(CompoundTag t:te) beCensus.merge(t.getString("id"),1L,Long::sum);
                    try {
                        ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<14);
                        try(io.github.ensgijs.nbt.io.BigEndianNbtOutputStream os=
                                new io.github.ensgijs.nbt.io.BigEndianNbtOutputStream(bo,false)){
                            os.writeTag(new io.github.ensgijs.nbt.io.NamedTag("",te),512);
                        }
                        byte[] b=bo.toByteArray(); beNbtRaw+=b.length; allBeNbt.write(b);
                    } catch(Exception e){}
                }
                for(TerrainSection s:c){
                    nSections++;
                    var pc=s.getBlockStates();
                    if(pc==null) continue;
                    int psz=pc.paletteSize();
                    paletteSum+=psz; paletteMax=Math.max(paletteMax,psz); sectionsWithPalette++;
                    for(int i=0;i<pc.size();i++){
                        CompoundTag st=pc.getByRef(i);
                        if(st==null) continue;
                        String nm=st.getString("Name");
                        if(nm==null) continue;
                        blockCensus.merge(nm,1L,Long::sum);
                        if(ORELIKE.contains(nm)){ oreVoxels++; oreByType.merge(nm,1L,Long::sum); }
                    }
                    var bio=s.getBiomes();
                    if(bio!=null) for(int i=0;i<bio.size();i++){
                        StringTag b=bio.getByRef(i);
                        if(b!=null) biomes.add(b.getValue());
                    }
                }
            }
        }

        long tvs=0;
        for(long v:blockCensus.values()) tvs+=v;
        final long totalVoxelsSeen=tvs;

        System.out.println("--- 1. VANILLA BASELINE (ground truth) ---");
        System.out.printf("  regions %d | chunks %,d | sections %,d%n",nR,nChunks,nSections);
        System.out.printf("  file bytes      : %,15d  (%,d B/chunk)%n",totFileBytes,
            nChunks>0?totFileBytes/nChunks:0);
        System.out.printf("  real payload    : %,15d  (%,d B/chunk)%n",totPayload,
            nChunks>0?totPayload/nChunks:0);
        long alloc=totSectors*4096L;
        System.out.printf("  sector padding  : %,15d  (%.1f%% of allocated)%n",
            alloc-totPayload, alloc>0?100.0*(alloc-totPayload)/alloc:0);
        System.out.printf("  sections/chunk  : %.1f%n", nChunks>0?nSections/(double)nChunks:0);

        System.out.println("\n--- 2. TERRAIN COMPLEXITY (vs my synthetic model) ---");
        System.out.printf("  distinct block states : %,d        (synthetic model had 26)%n",blockCensus.size());
        System.out.printf("  distinct biomes       : %,d        (synthetic model had 1-4)%n",biomes.size());
        System.out.printf("  palette/section avg   : %.1f, max %d%n",
            sectionsWithPalette>0?paletteSum/(double)sectionsWithPalette:0, paletteMax);
        System.out.println("  top 15 blocks:");
        blockCensus.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(),a.getValue()))
          .limit(15).forEach(e->System.out.printf("     %-42s %,10d  %5.2f%%%n",
              e.getKey(),e.getValue(),100.0*e.getValue()/Math.max(1,totalVoxelsSeen)));

        System.out.println("\n--- 3. BLOCK-ENTITY / NBT FLOOR ---");
        System.out.printf("  block entities   : %,d total, %.2f per chunk%n",
            totBlockEntities, nChunks>0?totBlockEntities/(double)nChunks:0);
        System.out.printf("  BE NBT raw       : %,d B   deflated %,d B  (%,d B/chunk deflated)%n",
            beNbtRaw, deflate(allBeNbt.toByteArray(),6).length,
            nChunks>0?deflate(allBeNbt.toByteArray(),6).length/nChunks:0);
        if(!beCensus.isEmpty()){
            System.out.println("  top block-entity types:");
            beCensus.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(),a.getValue()))
              .limit(8).forEach(e->System.out.printf("     %-40s %,8d%n",e.getKey(),e.getValue()));
        }

        System.out.println("\n--- 4. ORE-LIKE VOLUME (blob-detection target) ---");
        System.out.printf("  ore-like voxels: %,d  (%.2f%% of all blocks seen)%n",
            oreVoxels, 100.0*oreVoxels/Math.max(1,totalVoxelsSeen));
        oreByType.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(),a.getValue()))
          .limit(10).forEach(e->System.out.printf("     %-42s %,10d%n",e.getKey(),e.getValue()));
        System.out.println("\n  (NOTE: this counts PALETTE ENTRIES per section, not voxel instances —");
        System.out.println("   a per-voxel census needs the packed indices; added in the detection pass.)");
    }
}
