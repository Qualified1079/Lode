import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

// Two things not yet checked:
//   1. Does 2.77x hold PER REGION, or is the average hiding a bad tail?
//   2. What does a real game access pattern cost? The game does not do isolated point queries —
//      it does spatially coherent bursts (lighting propagation, meshing, physics, redstone).
//
// Access patterns benchmarked against a materialised chunk (palette + packed longs, i.e. exactly
// vanilla's runtime shape):
//   MESH   : sweep a 16^3 section, 6-neighbour lookup per block  (24,576 queries/section)
//   LIGHT  : BFS flood from a seed over a few thousand blocks in a small volume
//   RANDOM : uniform random points (the pattern I wrongly benchmarked before)
// Plus the load step: compressed bytes -> queryable structure.
//
// Also measures the DISASTER case: querying by walking the compressed stream instead of
// materialising, to quantify why you must materialise.

public class AccessBench {
    static HashMap<String,Integer> SID=new HashMap<>();
    static ArrayList<String> SNAME=new ArrayList<>();
    static int sid(CompoundTag st){
        String k;
        if(st==null) k="minecraft:air";
        else{
            String n=st.getString("Name");
            CompoundTag p=st.getCompoundTag("Properties");
            if(p==null||p.size()==0) k=n;
            else{ StringBuilder sb=new StringBuilder(n).append('[');
                ArrayList<String> ks=new ArrayList<>(p.keySet()); Collections.sort(ks);
                for(int i=0;i<ks.size();i++){ if(i>0)sb.append(','); sb.append(ks.get(i)).append('=').append(p.get(ks.get(i)).valueToString()); }
                k=sb.append(']').toString(); }
        }
        Integer v=SID.get(k);
        if(v==null){ v=SNAME.size(); SID.put(k,v); SNAME.add(k); }
        return v;
    }
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
    static void wVar(ByteArrayOutputStream bo,int n){
        while((n&~0x7F)!=0){bo.write((n&0x7F)|0x80);n>>>=7;} bo.write(n);
    }
    static byte[] deflate(byte[] in){
        Deflater d=new Deflater(6); d.setInput(in); d.finish();
        ByteArrayOutputStream bo=new ByteArrayOutputStream(in.length/4+64);
        byte[] o=new byte[1<<16];
        while(!d.finished()){int n=d.deflate(o); if(n>0)bo.write(o,0,n); else break;}
        d.end(); return bo.toByteArray();
    }
    static byte[] inflate(byte[] in) throws Exception {
        Inflater f=new Inflater(); f.setInput(in);
        ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<16);
        byte[] o=new byte[1<<16];
        while(!f.finished()){ int n=f.inflate(o); if(n<=0) break; bo.write(o,0,n); }
        f.end(); return bo.toByteArray();
    }

    // ---- our encoded chunk ----
    static byte[] encodeChunk(int[] vol,int W,int H,int cx,int cz,int nSec){
        ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<16);
        wVar(bo,nSec);
        for(int s=0;s<nSec;s++){
            HashMap<Integer,Integer> loc=new HashMap<>();
            ArrayList<Integer> list=new ArrayList<>();
            int[] idx=new int[4096]; int p=0;
            for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++)
            { int gid=vol[((cx*16+lx)*W+(cz*16+lz))*H+(s*16+ly)];
              Integer li=loc.get(gid);
              if(li==null){ li=list.size(); loc.put(gid,li); list.add(gid);} idx[p++]=li; }
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
        return deflate(bo.toByteArray());
    }

    // materialised runtime form: exactly vanilla's shape (palette + packed longs, O(1) index)
    static class Sec { int[] pal; long[] data; int bits; int single=-1; }
    static class Chunk {
        Sec[] secs;
        int get(int lx,int ly,int lz,int sy){
            Sec s=secs[sy];
            if(s.single>=0) return s.single;
            int i=(ly*16+lz)*16+lx;
            int per=64/s.bits;
            long word=s.data[i/per];
            int shift=(i%per)*s.bits;
            int v=(int)((word>>>shift)&((1L<<s.bits)-1));
            return s.pal[v];
        }
    }
    static int rdVar(byte[] b,int[] p){
        int n=0,sh=0,x;
        do{ x=b[p[0]++]&0xFF; n|=(x&0x7F)<<sh; sh+=7; }while((x&0x80)!=0);
        return n;
    }
    static Chunk materialise(byte[] def) throws Exception {
        byte[] raw=inflate(def);
        int[] p={0};
        int nSec=rdVar(raw,p);
        Chunk c=new Chunk(); c.secs=new Sec[nSec];
        for(int s=0;s<nSec;s++){
            Sec sc=new Sec();
            int tag=raw[p[0]++]&0xFF;
            if(tag==1){ sc.single=rdVar(raw,p); c.secs[s]=sc; continue; }
            int np=rdVar(raw,p);
            sc.pal=new int[np];
            for(int i=0;i<np;i++) sc.pal[i]=rdVar(raw,p);
            sc.bits=Math.max(4,log2ceil(np));
            int per=64/sc.bits;
            int nl=(4096+per-1)/per;
            sc.data=new long[nl];
            for(int i=0;i<nl;i++){
                long v=0;
                for(int b=0;b<8;b++) v|=((long)(raw[p[0]++]&0xFF))<<(b*8);
                sc.data[i]=v;
            }
            c.secs[s]=sc;
        }
        return c;
    }

    interface Op{ void run() throws Exception; }
    static double us(Op o,int it) throws Exception {
        for(int i=0;i<Math.min(it,50);i++) o.run();
        long t=System.nanoTime();
        for(int i=0;i<it;i++) o.run();
        return (System.nanoTime()-t)/1e3/it;
    }

    public static void main(String[] args) throws Exception {
        File dir=new File("/tmp/pregen/world/region");
        File[] files=dir.listFiles((d,n)->n.endsWith(".mca"));
        Arrays.sort(files);

        // ---------- 1. PER-REGION RATIO SPREAD ----------
        System.out.println("=== 1. PER-REGION RATIO (is 2.77x hiding a bad tail?) ===");
        double minR=99,maxR=0; String minN="",maxN="";
        long sumV=0,sumO=0;
        ArrayList<Double> ratios=new ArrayList<>();
        for(File f:files){
            McaRegionFile mca;
            try{ mca=McaFileHelpers.read(f); }catch(Exception e){ continue; }
            long ours=0;
            for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
                TerrainChunk c=mca.getChunk(cx,cz); if(c==null) continue;
                ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<16);
                for(int sy=c.getMinSectionY(); sy<=c.getMaxSectionY(); sy++){
                    TerrainSection s=c.getSection(sy);
                    if(s==null){ bo.write(0); continue; }
                    var pc=s.getBlockStates(); if(pc==null){ bo.write(0); continue; }
                    HashMap<Integer,Integer> loc=new HashMap<>();
                    ArrayList<Integer> list=new ArrayList<>();
                    int[] idx=new int[4096];
                    for(int i=0;i<4096;i++){
                        int g=sid(pc.getByRef(i));
                        Integer li=loc.get(g);
                        if(li==null){ li=list.size(); loc.put(g,li); list.add(g);} idx[i]=li;
                    }
                    int np=list.size();
                    if(np==1){ bo.write(1); wVar(bo,list.get(0)); continue; }
                    bo.write(2); wVar(bo,np);
                    for(int g:list) wVar(bo,g);
                    int bits=Math.max(4,log2ceil(np));
                    int per=64/bits,li2=0,sh=0;
                    long[] pk=new long[(4096+per-1)/per];
                    for(int i=0;i<4096;i++){ pk[li2]|=((long)idx[i])<<(sh*bits); if(++sh==per){sh=0;li2++;} }
                    for(long L:pk) for(int b=0;b<8;b++) bo.write((int)((L>>>(b*8))&0xFF));
                }
                ours+=deflate(bo.toByteArray()).length;
            }
            double r=f.length()/(double)ours;
            ratios.add(r); sumV+=f.length(); sumO+=ours;
            if(r<minR){minR=r;minN=f.getName();}
            if(r>maxR){maxR=r;maxN=f.getName();}
        }
        Collections.sort(ratios);
        System.out.printf("  regions %d | aggregate %.2fx%n",ratios.size(),sumV/(double)sumO);
        System.out.printf("  WORST  %.2fx (%s)%n  best   %.2fx (%s)%n",minR,minN,maxR,maxN);
        System.out.printf("  p10 %.2fx | median %.2fx | p90 %.2fx%n",
            ratios.get(ratios.size()/10), ratios.get(ratios.size()/2),
            ratios.get(ratios.size()*9/10));

        // ---------- 2. ACCESS PATTERNS ----------
        System.out.println("\n=== 2. ACCESS PATTERNS (spatially coherent bursts, not random points) ===");
        McaRegionFile mca=McaFileHelpers.read(files[files.length/2]);
        // build one chunk's volume
        TerrainChunk tc=null; int fcx=0,fcz=0;
        outer:
        for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
            TerrainChunk c=mca.getChunk(cx,cz);
            if(c!=null){ tc=c; fcx=cx; fcz=cz; break outer; }
        }
        int minS=tc.getMinSectionY(), maxS=tc.getMaxSectionY(), nSec=maxS-minS+1;
        int H=nSec*16, W=16;
        int[] vol=new int[W*W*H];
        for(int sy=minS;sy<=maxS;sy++){
            TerrainSection s=tc.getSection(sy); if(s==null) continue;
            var pc=s.getBlockStates(); if(pc==null) continue;
            for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++)
                vol[(lx*W+lz)*H+((sy-minS)*16+ly)]=sid(pc.getByRef(lx,ly,lz));
        }
        final byte[] enc=encodeChunk(vol,W,H,0,0,nSec);
        System.out.printf("  test chunk: %d sections, encoded %,d B%n",nSec,enc.length);

        double tLoad=us(()->{ materialise(enc); },300);
        System.out.printf("  LOAD  compressed -> queryable   : %8.1f us/chunk%n",tLoad);

        final Chunk ch=materialise(enc);
        final long[] sink={0};

        // MESH: sweep one section with 6-neighbour lookups
        final int midSec=nSec/2;
        double tMesh=us(()->{
            long acc=0;
            for(int ly=1;ly<15;ly++)for(int lz=1;lz<15;lz++)for(int lx=1;lx<15;lx++){
                acc+=ch.get(lx,ly,lz,midSec);
                acc+=ch.get(lx+1,ly,lz,midSec); acc+=ch.get(lx-1,ly,lz,midSec);
                acc+=ch.get(lx,ly+1,lz,midSec); acc+=ch.get(lx,ly-1,lz,midSec);
                acc+=ch.get(lx,ly,lz+1,midSec); acc+=ch.get(lx,ly,lz-1,midSec);
            }
            sink[0]+=acc;
        },2000);
        long meshQ=14L*14*14*7;
        System.out.printf("  MESH  section sweep, 6-neighbour: %8.1f us  (%,d queries) -> %.2f ns/query%n",
            tMesh,meshQ,tMesh*1000/meshQ);

        // LIGHT: BFS flood over a small volume
        double tLight=us(()->{
            boolean[] vis=new boolean[16*16*16];
            int[] st=new int[4096]; int sp=0;
            st[sp++]=(8*16+8)*16+8; vis[(8*16+8)*16+8]=true;
            long acc=0; int n=0;
            while(sp>0 && n<3000){
                int cur=st[--sp]; n++;
                int lx=cur%16, r=cur/16, lz=r%16, ly=r/16;
                acc+=ch.get(lx,ly,lz,midSec);
                for(int d=0;d<6;d++){
                    int nx=lx,ny=ly,nz=lz;
                    if(d==0)nx++; else if(d==1)nx--; else if(d==2)ny++;
                    else if(d==3)ny--; else if(d==4)nz++; else nz--;
                    if(nx<0||ny<0||nz<0||nx>15||ny>15||nz>15) continue;
                    int ni=(ny*16+nz)*16+nx;
                    if(vis[ni]) continue;
                    vis[ni]=true;
                    if(sp<st.length) st[sp++]=ni;
                }
            }
            sink[0]+=acc;
        },3000);
        System.out.printf("  LIGHT BFS flood (<=3000 blocks) : %8.1f us  -> %.2f ns/query%n",
            tLight, tLight*1000/3000);

        // RANDOM: uniform points (the pattern I benchmarked before)
        final java.util.Random rnd=new java.util.Random(7);
        double tRand=us(()->{
            long acc=0;
            for(int i=0;i<4096;i++)
                acc+=ch.get(rnd.nextInt(16),rnd.nextInt(16),rnd.nextInt(16),rnd.nextInt(nSec));
            sink[0]+=acc;
        },2000);
        System.out.printf("  RAND  uniform points            : %8.1f us  (4096 q) -> %.2f ns/query%n",
            tRand, tRand*1000/4096);

        // DISASTER CASE: re-inflate per query burst instead of materialising once
        double tNaive=us(()->{
            Chunk c2=materialise(enc);
            long acc=0;
            for(int ly=1;ly<15;ly++)for(int lz=1;lz<15;lz++)for(int lx=1;lx<15;lx++)
                acc+=c2.get(lx,ly,lz,midSec);
            sink[0]+=acc;
        },200);
        System.out.printf("  NAIVE re-inflate per burst      : %8.1f us  -> %.1fx worse than cached%n",
            tNaive, tNaive/tMesh);
        System.out.printf("%n  (sink %d)%n",sink[0]);
    }
}
