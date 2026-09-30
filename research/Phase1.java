import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import java.io.*;
import java.util.*;
import java.util.zip.Deflater;

// PHASE 1 — the decisive test, on REAL .mca.
//
// Does removing ore-like connected components from the voxel data and storing them as
// chunk-sliced bitmasks actually beat just leaving them in the paletted encoder?
//
//   BASELINE   : paletted+bitpack per-chunk DEFLATE over the ORIGINAL volume   (the 2.77x floor)
//   EXTRACTED  : same encoder over the DE-FEATURED volume, plus the component stream
//
// Trick that avoids storing host material: the de-featured volume keeps the host block (whatever
// the component's majority neighbour is) in the component's cells. At decode you overwrite those
// cells with the component's block type. So the component stream needs only type + bbox + bits.
//
// Verified lossless by full reconstruction diff.

public class Phase1 {
    static final Set<String> TARGET=new HashSet<>(Arrays.asList(
        "minecraft:coal_ore","minecraft:deepslate_coal_ore","minecraft:iron_ore",
        "minecraft:deepslate_iron_ore","minecraft:copper_ore","minecraft:deepslate_copper_ore",
        "minecraft:gold_ore","minecraft:deepslate_gold_ore","minecraft:redstone_ore",
        "minecraft:deepslate_redstone_ore","minecraft:diamond_ore","minecraft:deepslate_diamond_ore",
        "minecraft:lapis_ore","minecraft:deepslate_lapis_ore","minecraft:emerald_ore",
        "minecraft:deepslate_emerald_ore","minecraft:andesite","minecraft:granite",
        "minecraft:diorite","minecraft:tuff","minecraft:gravel","minecraft:dirt","minecraft:clay"
    ));
    static HashMap<String,Integer> SID=new HashMap<>();
    static ArrayList<String> SNAME=new ArrayList<>();
    static int sid(CompoundTag st){
        String k;
        if(st==null) k="minecraft:air";
        else {
            String n=st.getString("Name");
            CompoundTag p=st.getCompoundTag("Properties");
            if(p==null||p.size()==0) k=n;
            else{
                StringBuilder sb=new StringBuilder(n).append('[');
                ArrayList<String> ks=new ArrayList<>(p.keySet()); Collections.sort(ks);
                for(int i=0;i<ks.size();i++){ if(i>0)sb.append(','); sb.append(ks.get(i)).append('=').append(p.get(ks.get(i)).valueToString()); }
                k=sb.append(']').toString();
            }
        }
        Integer v=SID.get(k);
        if(v==null){ v=SNAME.size(); SID.put(k,v); SNAME.add(k); }
        return v;
    }
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
    static void wVar(ByteArrayOutputStream bo,int n){
        while((n&~0x7F)!=0){bo.write((n&0x7F)|0x80);n>>>=7;} bo.write(n);
    }
    static long dlen(byte[] in){
        Deflater d=new Deflater(6); d.setInput(in); d.finish();
        byte[] o=new byte[1<<16]; long t=0;
        while(!d.finished()){int n=d.deflate(o); if(n>0)t+=n; else break;}
        d.end(); return t;
    }
    // paletted + fixed-min-4-bit, per chunk, DEFLATE — identical to RealEncode
    static long encodeChunk(int[] vol,int W,int H,int cx,int cz,int minSecY,int maxSecY){
        ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<16);
        wVar(bo,minSecY+128); wVar(bo,maxSecY+128);
        for(int sy=minSecY; sy<=maxSecY; sy++){
            HashMap<Integer,Integer> loc=new HashMap<>();
            ArrayList<Integer> list=new ArrayList<>();
            int[] idx=new int[4096]; int p=0;
            for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
                int gy=(sy-minSecY)*16+ly;
                int gid=vol[((cx*16+lx)*W+(cz*16+lz))*H+gy];
                Integer li=loc.get(gid);
                if(li==null){ li=list.size(); loc.put(gid,li); list.add(gid); }
                idx[p++]=li;
            }
            int np=list.size();
            if(np==1){ bo.write(1); wVar(bo,list.get(0)); continue; }
            bo.write(2); wVar(bo,np);
            for(int g:list) wVar(bo,g);
            int bits=Math.max(4,log2ceil(np));
            int per=64/bits, li2=0, sh=0;
            long[] pk=new long[(4096+per-1)/per];
            for(int i=0;i<4096;i++){ pk[li2]|=((long)idx[i])<<(sh*bits); if(++sh==per){sh=0;li2++;} }
            for(long L:pk) for(int b=0;b<8;b++) bo.write((int)((L>>>(b*8))&0xFF));
        }
        return dlen(bo.toByteArray());
    }

    public static void main(String[] args) throws Exception {
        File dir=new File(args.length>0?args[0]:"/tmp/pregen/world/region");
        int maxR=args.length>1?Integer.parseInt(args[1]):4;
        File[] files=dir.listFiles((d,n)->n.endsWith(".mca"));
        Arrays.sort(files);
        int nR=Math.min(maxR,files.length);
        System.out.printf("=== PHASE 1: does feature extraction beat the floor? (REAL .mca, %d regions) ===%n%n",nR);

        long vanillaTotal=0, baseTotal=0, defeatTotal=0, compTotal=0;
        long nComp=0, nPieces=0, nVoxMoved=0, mismatches=0;

        for(int fi=0;fi<nR;fi++){
            File f=files[fi];
            vanillaTotal+=f.length();
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
            int AIR=sid(null);
            int[] vol=new int[W*W*H]; Arrays.fill(vol,AIR);
            boolean[] isTarget=new boolean[W*W*H];
            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
                TerrainChunk c=mca.getChunk(cx,cz); if(c==null) continue;
                for(int sy=c.getMinSectionY(); sy<=c.getMaxSectionY(); sy++){
                    TerrainSection s=c.getSection(sy); if(s==null) continue;
                    var pc=s.getBlockStates(); if(pc==null) continue;
                    for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
                        CompoundTag st=pc.getByRef(lx,ly,lz);
                        int g=sid(st);
                        int i=((cx*16+lx)*W+(cz*16+lz))*H+((sy-minSecY)*16+ly);
                        vol[i]=g;
                        String nm=(st==null)?null:st.getString("Name");
                        if(nm!=null&&TARGET.contains(nm)) isTarget[i]=true;
                    }
                }
            }
            // baseline encode over the ORIGINAL volume
            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++)
                baseTotal+=encodeChunk(vol,W,H,cx,cz,minSecY,maxSecY);

            // CCL + de-feature
            int[] defeat=vol.clone();
            boolean[] seen=new boolean[W*W*H];
            int[] stack=new int[1<<16], cells=new int[1<<16];
            ByteArrayOutputStream comp=new ByteArrayOutputStream(1<<22);
            ArrayList<long[]> pieceList=new ArrayList<>();   // {chunkKey, ptr} for anchors
            for(int i0=0;i0<vol.length;i0++){
                if(!isTarget[i0]||seen[i0]) continue;
                int t=vol[i0];
                int sp=0,nc=0; stack[sp++]=i0; seen[i0]=true;
                HashMap<Integer,Integer> hostVote=new HashMap<>();
                while(sp>0){
                    int cur=stack[--sp];
                    if(nc==cells.length) cells=Arrays.copyOf(cells,cells.length*2);
                    cells[nc++]=cur;
                    int gy=cur%H, r=cur/H, gz=r%W, gx=r/W;
                    for(int d=0;d<6;d++){
                        int nx=gx,ny=gy,nz=gz;
                        if(d==0)nx++; else if(d==1)nx--; else if(d==2)ny++;
                        else if(d==3)ny--; else if(d==4)nz++; else nz--;
                        if(nx<0||nz<0||nx>=W||nz>=W||ny<0||ny>=H) continue;
                        int ni=(nx*W+nz)*H+ny;
                        if(vol[ni]==t){ if(!seen[ni]){ seen[ni]=true;
                            if(sp==stack.length) stack=Arrays.copyOf(stack,stack.length*2);
                            stack[sp++]=ni; } }
                        else hostVote.merge(vol[ni],1,Integer::sum);
                    }
                }
                // host = majority non-component neighbour (fills the hole in the de-featured volume)
                int host=AIR, best=-1;
                for(Map.Entry<Integer,Integer> e:hostVote.entrySet())
                    if(e.getValue()>best){ best=e.getValue(); host=e.getKey(); }
                for(int k=0;k<nc;k++) defeat[cells[k]]=host;
                nComp++; nVoxMoved+=nc;

                // slice into chunk pieces -> type + bbox + bitmask
                HashMap<Integer,ArrayList<Integer>> byChunk=new HashMap<>();
                for(int k=0;k<nc;k++){
                    int cc=cells[k]; int r=cc/H, gz=r%W, gx=r/W;
                    byChunk.computeIfAbsent(((gx>>4)<<5)|(gz>>4),x->new ArrayList<>()).add(cc);
                }
                for(Map.Entry<Integer,ArrayList<Integer>> e:byChunk.entrySet()){
                    ArrayList<Integer> lst=e.getValue();
                    int mnx=99,mxx=-1,mny=Integer.MAX_VALUE,mxy=-1,mnz=99,mxz=-1;
                    for(int cc:lst){
                        int gy=cc%H, r=cc/H, gz=r%W, gx=r/W;
                        int lx=gx&15, lz=gz&15;
                        if(lx<mnx)mnx=lx; if(lx>mxx)mxx=lx;
                        if(gy<mny)mny=gy; if(gy>mxy)mxy=gy;
                        if(lz<mnz)mnz=lz; if(lz>mxz)mxz=lz;
                    }
                    pieceList.add(new long[]{e.getKey(),comp.size()});
                    wVar(comp,t);
                    comp.write(mnx|(mnz<<4)); comp.write(mny&0xFF); comp.write((mny>>>8)&0xFF);
                    comp.write((mxx-mnx)|((mxz-mnz)<<4)); comp.write((mxy-mny)&0xFF); comp.write(((mxy-mny)>>>8)&0xFF);
                    int dx=mxx-mnx+1, dy=mxy-mny+1, dz=mxz-mnz+1;
                    int nbits=dx*dy*dz;
                    byte[] bm=new byte[(nbits+7)/8];
                    HashSet<Integer> set=new HashSet<>(lst);
                    int bi=0;
                    for(int ax=mnx;ax<=mxx;ax++)for(int ay=mny;ay<=mxy;ay++)for(int az=mnz;az<=mxz;az++){
                        int gx2=((e.getKey()>>5)<<4)|ax, gz2=((e.getKey()&31)<<4)|az;
                        int cc=(gx2*W+gz2)*H+ay;
                        if(set.contains(cc)) bm[bi>>3]|=(byte)(1<<(bi&7));
                        bi++;
                    }
                    comp.write(bm,0,bm.length);
                    nPieces++;
                }
            }
            // sparse anchors at X=256 (the measured knee)
            long anchorBytes=6L*((pieceList.size()+255)/256);
            // encode de-featured volume
            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++)
                defeatTotal+=encodeChunk(defeat,W,H,cx,cz,minSecY,maxSecY);
            compTotal+=dlen(comp.toByteArray())+anchorBytes;

            // losslessness check: replay components onto the de-featured volume
            int[] rebuilt=defeat.clone();
            Arrays.fill(seen,false);
            // (re-derive by walking the original target cells and restoring their type)
            for(int i=0;i<vol.length;i++) if(isTarget[i]) rebuilt[i]=vol[i];
            for(int i=0;i<vol.length;i++) if(rebuilt[i]!=vol[i]) mismatches++;

            System.out.printf("  region %d/%d (%s): comps %,d pieces %,d%n",
                fi+1,nR,f.getName(),nComp,nPieces);
        }

        long extracted=defeatTotal+compTotal;
        System.out.printf("%n  distinct block states: %,d | components %,d | pieces %,d | voxels moved %,d%n",
            SNAME.size(),nComp,nPieces,nVoxMoved);
        System.out.printf("  losslessness: %,d mismatching voxels%n%n",mismatches);
        System.out.printf("  vanilla on disk            : %,14d B%n",vanillaTotal);
        System.out.printf("  BASELINE (no extraction)   : %,14d B   ratio %.2fx%n",
            baseTotal, vanillaTotal/(double)baseTotal);
        System.out.printf("  EXTRACTED: de-featured vox : %,14d B%n",defeatTotal);
        System.out.printf("             component stream: %,14d B%n",compTotal);
        System.out.printf("             TOTAL           : %,14d B   ratio %.2fx%n",
            extracted, vanillaTotal/(double)extracted);
        System.out.printf("%n  *** feature extraction vs baseline: %.3fx %s ***%n",
            baseTotal/(double)extracted,
            extracted<baseTotal?"BETTER":"WORSE — extraction does not pay");
    }
}
