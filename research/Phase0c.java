import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import java.io.*;
import java.util.*;

// PHASE 0c — options B, C, D.
//   B  resident side table : giant components stored WHOLE (best of surface/global-bbox-bitmask),
//                            kept in a small always-resident region header. No chunk splitting.
//   C  chunk-local col-RLE : (re-measured properly: runs along Y per column, not per-cell)
//   D  sparse coord anchors: user's idea. Instead of a positional header per (comp,chunk) piece,
//                            sort pieces by chunk and store an absolute anchor every X pieces;
//                            locate by binary search + walk <= X. Swept over X.
//                            NOTE: applied to BITMASK, where the per-piece headers are 29% of cost.

public class Phase0c {
    static final Set<String> TARGET=new HashSet<>(Arrays.asList(
        "minecraft:coal_ore","minecraft:deepslate_coal_ore","minecraft:iron_ore",
        "minecraft:deepslate_iron_ore","minecraft:copper_ore","minecraft:deepslate_copper_ore",
        "minecraft:gold_ore","minecraft:deepslate_gold_ore","minecraft:redstone_ore",
        "minecraft:deepslate_redstone_ore","minecraft:diamond_ore","minecraft:deepslate_diamond_ore",
        "minecraft:lapis_ore","minecraft:deepslate_lapis_ore","minecraft:emerald_ore",
        "minecraft:deepslate_emerald_ore","minecraft:andesite","minecraft:granite",
        "minecraft:diorite","minecraft:tuff","minecraft:gravel","minecraft:dirt","minecraft:clay"
    ));

    public static void main(String[] args) throws Exception {
        File dir=new File(args.length>0?args[0]:"/tmp/pregen/world/region");
        int maxR=args.length>1?Integer.parseInt(args[1]):4;
        File[] files=dir.listFiles((d,n)->n.endsWith(".mca"));
        Arrays.sort(files);
        int nR=Math.min(maxR,files.length);
        System.out.printf("=== PHASE 0c: options B / C / D on REAL .mca (%d regions) ===%n%n",nR);

        long nComp=0, nVox=0;
        // whole-component (option B) accounting, giants only
        long giantCount=0, giantVol=0, giantWholeSurf=0, giantGlobalBBoxBytes=0;
        // chunk-split bitmask accounting (the incumbent winner)
        long pieces=0, bitmaskBits=0, bitmaskTypeBBoxBytes=0;
        // option C: true column-RLE per (comp,chunk)
        long colrleBytes=0;
        // per-size totals
        long totVoxSmall=0;

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
            int[] stack=new int[1<<16], cells=new int[1<<16];
            for(int i0=0;i0<vol.length;i0++){
                if(vol[i0]==0||seen[i0]) continue;
                short t=vol[i0];
                int sp=0,nc=0; stack[sp++]=i0; seen[i0]=true;
                long surf=0;
                int gminx=999999,gmaxx=-1,gminy=999999,gmaxy=-1,gminz=999999,gmaxz=-1;
                while(sp>0){
                    int cur=stack[--sp];
                    if(nc==cells.length) cells=Arrays.copyOf(cells,cells.length*2);
                    cells[nc++]=cur;
                    int gy=cur%H, r=cur/H, gz=r%W, gx=r/W;
                    if(gx<gminx)gminx=gx; if(gx>gmaxx)gmaxx=gx;
                    if(gy<gminy)gminy=gy; if(gy>gmaxy)gmaxy=gy;
                    if(gz<gminz)gminz=gz; if(gz>gmaxz)gmaxz=gz;
                    for(int d=0;d<6;d++){
                        int nx=gx,ny=gy,nz=gz;
                        if(d==0)nx++; else if(d==1)nx--; else if(d==2)ny++;
                        else if(d==3)ny--; else if(d==4)nz++; else nz--;
                        if(nx<0||nz<0||nx>=W||nz>=W||ny<0||ny>=H){ surf++; continue; }
                        int ni=(nx*W+nz)*H+ny;
                        if(vol[ni]!=t){ surf++; continue; }
                        if(!seen[ni]){ seen[ni]=true;
                            if(sp==stack.length) stack=Arrays.copyOf(stack,stack.length*2);
                            stack[sp++]=ni; }
                    }
                }
                long v=nc; nComp++; nVox+=v;

                if(v>10240){
                    giantCount++; giantVol+=v; giantWholeSurf+=surf;
                    long gb=(long)(gmaxx-gminx+1)*(gmaxy-gminy+1)*(gmaxz-gminz+1);
                    giantGlobalBBoxBytes += 12 + (gb+7)/8;
                } else totVoxSmall+=v;

                // group cells by chunk for the split encodings
                HashMap<Integer,ArrayList<Integer>> byChunk=new HashMap<>();
                for(int k=0;k<nc;k++){
                    int cc=cells[k];
                    int r=cc/H, gz=r%W, gx=r/W;
                    byChunk.computeIfAbsent(((gx>>4)<<5)|(gz>>4),x->new ArrayList<>()).add(cc);
                }
                for(ArrayList<Integer> lst:byChunk.entrySet().stream().map(Map.Entry::getValue).toList()){
                    pieces++;
                    int mnx=99,mxx=-1,mny=999999,mxy=-1,mnz=99,mxz=-1;
                    for(int cc:lst){
                        int gy=cc%H, r=cc/H, gz=r%W, gx=r/W;
                        int lx=gx&15, lz=gz&15;
                        if(lx<mnx)mnx=lx; if(lx>mxx)mxx=lx;
                        if(gy<mny)mny=gy; if(gy>mxy)mxy=gy;
                        if(lz<mnz)mnz=lz; if(lz>mxz)mxz=lz;
                    }
                    long bb=(long)(mxx-mnx+1)*(mxy-mny+1)*(mxz-mnz+1);
                    bitmaskBits+=bb;
                    bitmaskTypeBBoxBytes+=4;   // type + packed bbox, WITHOUT the positional chunk id
                    // option C: real column-RLE — count runs along Y per (lx,lz) column
                    HashSet<Integer> cellSet=new HashSet<>(lst);
                    HashMap<Integer,ArrayList<Integer>> cols=new HashMap<>();
                    for(int cc:lst){
                        int gy=cc%H, r=cc/H, gz=r%W, gx=r/W;
                        cols.computeIfAbsent(((gx&15)<<4)|(gz&15),x->new ArrayList<>()).add(gy);
                    }
                    long runs=0;
                    for(ArrayList<Integer> ys:cols.values()){
                        Collections.sort(ys);
                        runs++;
                        for(int k=1;k<ys.size();k++) if(ys.get(k)!=ys.get(k-1)+1) runs++;
                    }
                    colrleBytes += 4 + cols.size()*1 + runs*3;   // hdr + col ids + (startY,len) runs
                }
            }
            System.out.printf("  region %d/%d done, comps %,d%n",fi+1,nR,nComp);
        }

        long bitmaskBytes = bitmaskTypeBBoxBytes + (bitmaskBits+7)/8;
        System.out.printf("%n%,d components, %,d voxels, %,d chunk-pieces%n",nComp,nVox,pieces);

        System.out.println("\n--- OPTION B: giants WHOLE in a resident side table (no splitting) ---");
        System.out.printf("  giant components (>10240): %,d, volume %,d%n",giantCount,giantVol);
        long bSurf = giantCount*12 + giantWholeSurf*2;
        System.out.printf("  B via whole-surface        : %,12d B%n",bSurf);
        System.out.printf("  B via global-bbox bitmask  : %,12d B%n",giantGlobalBBoxBytes);
        System.out.printf("  same giants, CHUNK-SPLIT bitmask (from 0b) : ~393,542 B%n");
        System.out.printf("  -> B is %.1fx WORSE than splitting them%n",
            Math.min(bSurf,giantGlobalBBoxBytes)/393542.0);

        System.out.println("\n--- OPTION C: chunk-local column-RLE (properly run-length, not per-cell) ---");
        System.out.printf("  C total: %,12d B%n",colrleBytes);

        System.out.println("\n--- OPTION D: sparse coordinate anchors (your idea), applied to BITMASK ---");
        System.out.printf("  bitmask payload (bbox bits)        : %,12d B%n",(bitmaskBits+7)/8);
        System.out.printf("  per-piece type+bbox (unavoidable)  : %,12d B%n",bitmaskTypeBBoxBytes);
        long fullIdx = pieces*2;   // explicit chunk id per piece
        System.out.printf("  + explicit chunk id per piece      : %,12d B  => TOTAL %,d%n",
            fullIdx, bitmaskBytes+fullIdx);
        System.out.printf("%n  %-8s %14s %14s %12s%n","X","anchor bytes","TOTAL","vs explicit");
        for(int X : new int[]{16,64,256,1024,4096,16384}){
            long anchors=(pieces+X-1)/X;
            long anchorBytes=anchors*6;
            long tot=bitmaskBytes+anchorBytes;
            System.out.printf("  %-8d %,14d %,14d %11.3fx   (walk <= %d pieces)%n",
                X,anchorBytes,tot,(bitmaskBytes+fullIdx)/(double)tot,X);
        }
        System.out.printf("%n  best-case saving from sparse anchors: %,d B (%.1f%% of bitmask total)%n",
            fullIdx - 6L*((pieces+16383)/16384),
            100.0*(fullIdx - 6L*((pieces+16383)/16384))/(double)(bitmaskBytes+fullIdx));
    }
}
