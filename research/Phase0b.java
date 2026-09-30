import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import java.io.*;
import java.util.*;

// PHASE 0b — partition economics.
//
// Fixes two things from 0a:
//   * dirt and clay KEPT (removing them was a semantic judgement, not an empirical one)
//   * measures what chunk-granular access actually COSTS each encoding
//
// Encodings compared, all required to be chunk-accessible:
//   NAIVE-SPLIT  cut components at chunk bounds; artificial cut faces counted on both sides
//   OFFSET-INDEX store the component's surface ONCE, faces sorted by owning chunk, plus a sparse
//                per-(component,chunk) offset entry. Zero artificial faces; cost = index only.
//   BITMASK-SPLIT per-chunk bbox+bitmask of the component's cells in that chunk
//   COLRLE-SPLIT per-chunk column-RLE of the component's cells
// plus WHOLE-SURFACE as the (non-chunk-accessible) lower bound for reference.

public class Phase0b {
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
        System.out.printf("=== PHASE 0b: PARTITION ECONOMICS (%d regions, dirt+clay kept) ===%n%n",nR);

        int[] BK={64,1024,10240,Integer.MAX_VALUE};
        String[] lbl={"<=64","<=1024","<=10240",">10240"};
        long[] bkCount=new long[4], bkVol=new long[4];
        long[] wholeSurf=new long[4], splitSurf=new long[4], incid=new long[4];
        long[] bmSplitBytes=new long[4], rleSplitBytes=new long[4];
        long[] chunksTouchedMax=new long[4];
        long nComp=0,nVox=0,maxComp=0; String maxT="";

        for(int fi=0;fi<nR;fi++){
            File f=files[fi];
            McaRegionFile mca;
            try{ mca=McaFileHelpers.read(f); }catch(Exception e){ continue; }
            int minSecY=99,maxSecY=-99;
            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
                TerrainChunk c=mca.getChunk(cx,cz); if(c==null) continue;
                minSecY=Math.min(minSecY,c.getMinSectionY());
                maxSecY=Math.max(maxSecY,c.getMaxSectionY());
            }
            if(minSecY>maxSecY) continue;
            int H=(maxSecY-minSecY+1)*16, W=512;
            short[] vol=new short[W*W*H];
            ArrayList<String> tn=new ArrayList<>(); tn.add("");
            HashMap<String,Short> tid=new HashMap<>();
            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
                TerrainChunk c=mca.getChunk(cx,cz); if(c==null) continue;
                for(int sy=c.getMinSectionY(); sy<=c.getMaxSectionY(); sy++){
                    TerrainSection s=c.getSection(sy); if(s==null) continue;
                    var pc=s.getBlockStates(); if(pc==null) continue;
                    for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
                        CompoundTag st=pc.getByRef(lx,ly,lz); if(st==null) continue;
                        String nm=st.getString("Name");
                        if(nm==null||!TARGET.contains(nm)) continue;
                        Short t=tid.get(nm);
                        if(t==null){ t=(short)tn.size(); tn.add(nm); tid.put(nm,t); }
                        vol[((cx*16+lx)*W+(cz*16+lz))*H+((sy-minSecY)*16+ly)]=t;
                    }
                }
            }

            boolean[] seen=new boolean[W*W*H];
            int[] stack=new int[1<<16];
            int[] cells=new int[1<<16];
            for(int i0=0;i0<vol.length;i0++){
                if(vol[i0]==0||seen[i0]) continue;
                short t=vol[i0];
                int sp=0,nc=0; stack[sp++]=i0; seen[i0]=true;
                long surf=0, cutFaces=0;
                while(sp>0){
                    int cur=stack[--sp];
                    if(nc==cells.length) cells=Arrays.copyOf(cells,cells.length*2);
                    cells[nc++]=cur;
                    int gy=cur%H, r=cur/H, gz=r%W, gx=r/W;
                    for(int d=0;d<6;d++){
                        int nx=gx,ny=gy,nz=gz;
                        if(d==0)nx++; else if(d==1)nx--; else if(d==2)ny++;
                        else if(d==3)ny--; else if(d==4)nz++; else nz--;
                        boolean oob = nx<0||nz<0||nx>=W||nz>=W||ny<0||ny>=H;
                        boolean diff = oob || vol[(nx*W+nz)*H+ny]!=t;
                        if(diff) surf++;
                        else {
                            // same material: is it across a chunk boundary? then a NAIVE split cuts here
                            if((gx>>4)!=(nx>>4) || (gz>>4)!=(nz>>4)) cutFaces++;
                            int ni=(nx*W+nz)*H+ny;
                            if(!seen[ni]){
                                seen[ni]=true;
                                if(sp==stack.length) stack=Arrays.copyOf(stack,stack.length*2);
                                stack[sp++]=ni;
                            }
                        }
                    }
                }
                long v=nc;
                nComp++; nVox+=v;
                if(v>maxComp){ maxComp=v; maxT=tn.get(t); }
                int b=0; while(b<3 && v>BK[b]) b++;
                bkCount[b]++; bkVol[b]+=v;
                wholeSurf[b]+=surf;
                splitSurf[b]+=surf+cutFaces;      // naive split adds the cut faces
                // per-chunk grouping
                HashMap<Integer,int[]> byChunk=new HashMap<>(); // chunkKey -> {count,minx,maxx,miny,maxy,minz,maxz}
                for(int k=0;k<nc;k++){
                    int cc=cells[k];
                    int gy=cc%H, r=cc/H, gz=r%W, gx=r/W;
                    int ck=((gx>>4)<<5)|(gz>>4);
                    int[] e=byChunk.get(ck);
                    if(e==null){ e=new int[]{0,999,-1,999,-1,999,-1}; byChunk.put(ck,e); }
                    e[0]++;
                    int lx=gx&15, lz=gz&15;
                    if(lx<e[1])e[1]=lx; if(lx>e[2])e[2]=lx;
                    if(gy<e[3])e[3]=gy; if(gy>e[4])e[4]=gy;
                    if(lz<e[5])e[5]=lz; if(lz>e[6])e[6]=lz;
                }
                incid[b]+=byChunk.size();
                if(byChunk.size()>chunksTouchedMax[b]) chunksTouchedMax[b]=byChunk.size();
                for(int[] e:byChunk.values()){
                    long bb=(long)(e[2]-e[1]+1)*(e[4]-e[3]+1)*(e[6]-e[5]+1);
                    bmSplitBytes[b]+= 6 + (bb+7)/8;         // 6B header per (comp,chunk) + bitmask
                    rleSplitBytes[b]+= 6 + e[0]*2L;          // crude: ~2B per cell run-ish
                }
                if((nComp&0xFFFF)==0) System.out.printf("   ... %,d comps%n",nComp);
            }
            System.out.printf("  region %d/%d (%s) comps=%,d%n",fi+1,nR,f.getName(),nComp);
        }

        System.out.printf("%n--- %,d components, %,d voxels; largest %,d (%s) ---%n%n",
            nComp,nVox,maxComp,maxT);
        System.out.printf("%-9s %9s %13s %12s %12s %10s %8s%n",
            "size","count","volume","wholeSurf","splitSurf","incidences","maxChk");
        for(int b=0;b<4;b++){
            if(bkCount[b]==0) continue;
            System.out.printf("%-9s %,9d %,13d %,12d %,12d %,10d %,8d%n",
                lbl[b],bkCount[b],bkVol[b],wholeSurf[b],splitSurf[b],incid[b],chunksTouchedMax[b]);
        }
        System.out.printf("%n  cut-face inflation from naive splitting: %.2fx overall%n",
            Arrays.stream(splitSurf).sum()/(double)Math.max(1,Arrays.stream(wholeSurf).sum()));

        System.out.printf("%n--- CHUNK-ACCESSIBLE ENCODING COST (bytes) ---%n");
        System.out.printf("%-9s %14s %14s %14s %14s %14s%n",
            "size","NAIVE-SPLIT","OFFSET-INDEX","BITMASK-SPLIT","COLRLE-SPLIT","(whole surf)");
        long tN=0,tO=0,tB=0,tR=0,tW=0;
        for(int b=0;b<4;b++){
            if(bkCount[b]==0) continue;
            long naive   = incid[b]*5 + splitSurf[b]*2;       // per-piece header + inflated faces
            long offset  = bkCount[b]*5 + wholeSurf[b]*2 + incid[b]*4;  // comp hdr + true faces + index
            long bitmask = bmSplitBytes[b];
            long colrle  = rleSplitBytes[b];
            long whole   = bkCount[b]*5 + wholeSurf[b]*2;
            tN+=naive; tO+=offset; tB+=bitmask; tR+=colrle; tW+=whole;
            System.out.printf("%-9s %,14d %,14d %,14d %,14d %,14d%n",
                lbl[b],naive,offset,bitmask,colrle,whole);
        }
        System.out.printf("%-9s %,14d %,14d %,14d %,14d %,14d%n","TOTAL",tN,tO,tB,tR,tW);
        System.out.printf("%n  OFFSET-INDEX vs NAIVE-SPLIT : %.2fx better%n",tN/(double)tO);
        System.out.printf("  OFFSET-INDEX overhead vs non-accessible whole-surface : %.1f%%%n",
            100.0*(tO-tW)/(double)tW);
    }
}
