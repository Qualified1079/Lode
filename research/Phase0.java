import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import java.io.*;
import java.util.*;

// PHASE 0 — measurement that decides the detector design. No detector written yet.
//
// Tests TWO hypotheses against real Minecraft data:
//   H1 (small blobs):  connected components repeat in shape, so a template dictionary works.
//                      My synthetic world said yes, but it generated blobs from an 8-shape family.
//                      Real OreFeature uses a random angle + per-block RNG, so reuse may not exist.
//   H2 (large masses): big connected single-type masses have surface S ~ V^(2/3), so boundary
//                      encoding beats a bitmask on them. This is the ORIGINAL surface-encoding
//                      idea, applied to the one target where interior detail is absent by
//                      definition (a connected component of one block type has no interior detail).
//
// Per component measures: volume, surface area, bbox, normalized shape hash.
// Then compares encoding costs per size bucket.
//
// CCL runs per-region; components clipped at region edges bias large sizes slightly DOWN.

public class Phase0 {
    static final int SEC=16;
    static final Set<String> TARGET=new HashSet<>(Arrays.asList(
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
        File dir=new File(args.length>0?args[0]:"/tmp/pregen/world/region");
        int maxR=args.length>1?Integer.parseInt(args[1]):4;
        File[] files=dir.listFiles((d,n)->n.endsWith(".mca"));
        Arrays.sort(files);
        int nR=Math.min(maxR,files.length);
        System.out.printf("=== PHASE 0: connected-component census on REAL data (%d regions) ===%n%n",nR);

        // accumulators
        long nComp=0, nVox=0;
        Map<String,long[]> perType=new TreeMap<>();     // type -> {components, volume, surface}
        Map<Long,Integer> shapeHash=new HashMap<>();     // normalized shape -> count
        long shapeable=0;                                // components small enough to hash
        // size buckets: 1-2,3-4,5-8,9-16,17-32,33-64,65-256,257-1k,1k-10k,10k+
        int[] BK={2,4,8,16,32,64,256,1024,10240,Integer.MAX_VALUE};
        long[] bkCount=new long[BK.length], bkVol=new long[BK.length],
              bkSurf=new long[BK.length], bkBBoxCells=new long[BK.length];
        long maxComp=0; String maxCompType="";

        for(int fi=0;fi<nR;fi++){
            File f=files[fi];
            McaRegionFile mca;
            try{ mca=McaFileHelpers.read(f); }catch(Exception e){ continue; }

            // materialise this region as a short[] of "target type id" (0 = not a target)
            // region = 512x512 blocks, Y from minSec*16 .. (maxSec+1)*16
            int minSecY=99, maxSecY=-99;
            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
                TerrainChunk c=mca.getChunk(cx,cz); if(c==null) continue;
                minSecY=Math.min(minSecY,c.getMinSectionY());
                maxSecY=Math.max(maxSecY,c.getMaxSectionY());
            }
            if(minSecY>maxSecY) continue;
            int H=(maxSecY-minSecY+1)*16;
            int W=512;
            short[] vol=new short[W*W*H];
            ArrayList<String> tnames=new ArrayList<>(); tnames.add("");
            HashMap<String,Short> tid=new HashMap<>();

            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
                TerrainChunk c=mca.getChunk(cx,cz); if(c==null) continue;
                for(int sy=c.getMinSectionY(); sy<=c.getMaxSectionY(); sy++){
                    TerrainSection s=c.getSection(sy); if(s==null) continue;
                    var pc=s.getBlockStates(); if(pc==null) continue;
                    for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
                        CompoundTag st=pc.getByRef(lx,ly,lz);
                        if(st==null) continue;
                        String nm=st.getString("Name");
                        if(nm==null||!TARGET.contains(nm)) continue;
                        Short t=tid.get(nm);
                        if(t==null){ t=(short)tnames.size(); tnames.add(nm); tid.put(nm,t); }
                        int gx=cx*16+lx, gz=cz*16+lz, gy=(sy-minSecY)*16+ly;
                        vol[(gx*W+gz)*H+gy]=t;
                    }
                }
            }

            // iterative flood fill
            boolean[] seen=new boolean[W*W*H];
            int[] stack=new int[1<<16];
            for(int i=0;i<vol.length;i++){
                if(vol[i]==0||seen[i]) continue;
                short t=vol[i];
                int sp=0; stack[sp++]=i; seen[i]=true;
                long v=0, surf=0;
                int minx=Integer.MAX_VALUE,maxx=-1,miny=Integer.MAX_VALUE,maxy=-1,minz=Integer.MAX_VALUE,maxz=-1;
                ArrayList<Integer> cells=new ArrayList<>();
                while(sp>0){
                    int cur=stack[--sp];
                    v++;
                    int gy=cur%H, rest=cur/H, gz=rest%W, gx=rest/W;
                    if(gx<minx)minx=gx; if(gx>maxx)maxx=gx;
                    if(gy<miny)miny=gy; if(gy>maxy)maxy=gy;
                    if(gz<minz)minz=gz; if(gz>maxz)maxz=gz;
                    if(v<=4096) cells.add(cur);
                    // 6-neighbourhood
                    for(int d=0;d<6;d++){
                        int nx=gx,ny=gy,nz=gz;
                        if(d==0)nx++; else if(d==1)nx--; else if(d==2)ny++;
                        else if(d==3)ny--; else if(d==4)nz++; else nz--;
                        if(nx<0||nz<0||nx>=W||nz>=W||ny<0||ny>=H){ surf++; continue; }
                        int ni=(nx*W+nz)*H+ny;
                        if(vol[ni]!=t){ surf++; continue; }
                        if(!seen[ni]){
                            seen[ni]=true;
                            if(sp==stack.length) stack=Arrays.copyOf(stack,stack.length*2);
                            stack[sp++]=ni;
                        }
                    }
                }
                nComp++; nVox+=v;
                String tn=tnames.get(t);
                long[] pt=perType.computeIfAbsent(tn,k->new long[3]);
                pt[0]++; pt[1]+=v; pt[2]+=surf;
                if(v>maxComp){ maxComp=v; maxCompType=tn; }
                long bboxCells=(long)(maxx-minx+1)*(maxy-miny+1)*(maxz-minz+1);
                int b=0; while(b<BK.length-1 && v>BK[b]) b++;
                bkCount[b]++; bkVol[b]+=v; bkSurf[b]+=surf; bkBBoxCells[b]+=bboxCells;
                // normalized shape hash for small components (translation-invariant)
                if(v<=64 && cells.size()==v){
                    long h=1469598103934665603L;
                    int[] rel=new int[cells.size()];
                    for(int k=0;k<cells.size();k++){
                        int cc=cells.get(k);
                        int cy=cc%H, r2=cc/H, cz2=r2%W, cx2=r2/W;
                        rel[k]=((cx2-minx)<<20)|((cy-miny)<<10)|(cz2-minz);
                    }
                    Arrays.sort(rel);
                    for(int r:rel){ h^=r; h*=1099511628211L; }
                    shapeHash.merge(h,1,Integer::sum);
                    shapeable++;
                }
            }
            System.out.printf("  region %d/%d done (%s), components so far %,d%n",fi+1,nR,f.getName(),nComp);
        }

        System.out.printf("%n--- COMPONENTS: %,d total, %,d voxels ---%n",nComp,nVox);
        System.out.printf("  largest single component: %,d voxels (%s)%n%n",maxComp,maxCompType);

        System.out.printf("%-12s %10s %14s %12s %9s %10s%n",
            "size<=","count","volume","surface","S/V","S/V^(2/3)");
        String[] lbl={"2","4","8","16","32","64","256","1024","10240","inf"};
        for(int b=0;b<BK.length;b++){
            if(bkCount[b]==0) continue;
            double sv=bkVol[b]>0?bkSurf[b]/(double)bkVol[b]:0;
            double meanV=bkVol[b]/(double)bkCount[b];
            double meanS=bkSurf[b]/(double)bkCount[b];
            double svt=meanS/Math.pow(Math.max(1,meanV),2.0/3.0);
            System.out.printf("%-12s %,10d %,14d %,12d %9.2f %10.2f%n",
                lbl[b],bkCount[b],bkVol[b],bkSurf[b],sv,svt);
        }

        System.out.printf("%n--- H1: SHAPE REUSE (components <=64 voxels) ---%n");
        System.out.printf("  hashable components : %,d%n",shapeable);
        System.out.printf("  DISTINCT shapes     : %,d%n",shapeHash.size());
        System.out.printf("  reuse factor        : %.2f  (1.00 = no reuse, dictionary useless)%n",
            shapeHash.size()>0?shapeable/(double)shapeHash.size():0);
        List<Map.Entry<Long,Integer>> top=new ArrayList<>(shapeHash.entrySet());
        top.sort((a,b)->b.getValue()-a.getValue());
        System.out.print("  top shape frequencies: ");
        for(int i=0;i<Math.min(8,top.size());i++) System.out.printf("%d ",top.get(i).getValue());
        System.out.println();

        System.out.printf("%n--- H2: ENCODING COST PER BUCKET (bytes) ---%n");
        System.out.printf("%-12s %14s %14s %14s %12s%n",
            "size<=","explicit","bbox+bitmask","surface","dict-ref");
        for(int b=0;b<BK.length;b++){
            if(bkCount[b]==0) continue;
            long explicit=bkVol[b]*4;
            long bitmask=bkCount[b]*5 + bkBBoxCells[b]/8;
            long surface=bkCount[b]*5 + bkSurf[b]*2;
            long dict=bkCount[b]*5;
            System.out.printf("%-12s %,14d %,14d %,14d %,12d%n",lbl[b],explicit,bitmask,surface,dict);
        }
        System.out.printf("%n  (dict-ref cost assumes the shape IS in the dictionary — see reuse factor above)%n");

        System.out.printf("%n--- PER TYPE ---%n");
        System.out.printf("%-42s %10s %14s %9s%n","type","comps","volume","S/V");
        perType.entrySet().stream()
          .sorted((a,b)->Long.compare(b.getValue()[1],a.getValue()[1]))
          .forEach(e->System.out.printf("%-42s %,10d %,14d %9.2f%n",
              e.getKey(),e.getValue()[0],e.getValue()[1],
              e.getValue()[1]>0?e.getValue()[2]/(double)e.getValue()[1]:0));
    }
}
