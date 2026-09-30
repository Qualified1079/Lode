import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

// Caching design inputs. Three questions that change the answer:
//   1. Of the 262.7 us/chunk materialise cost, how much is INFLATE vs STRUCTURE-BUILD?
//      (decides whether a compressed-in-memory tier is worth having)
//   2. What is a materialised chunk's MEMORY footprint? (decides cache sizing)
//   3. Does LAZY per-section materialisation beat eager? 72% of sections are whole-air, and a
//      mesher only touches surface sections — so on-first-touch may be much cheaper.

public class CacheBench {
    static HashMap<String,Integer> SID=new HashMap<>();
    static ArrayList<String> SNAME=new ArrayList<>();
    static int sid(CompoundTag st){
        String k;
        if(st==null) k="minecraft:air";
        else{ String n=st.getString("Name");
            CompoundTag p=st.getCompoundTag("Properties");
            if(p==null||p.size()==0) k=n;
            else{ StringBuilder sb=new StringBuilder(n).append('[');
                ArrayList<String> ks=new ArrayList<>(p.keySet()); Collections.sort(ks);
                for(int i=0;i<ks.size();i++){ if(i>0)sb.append(','); sb.append(ks.get(i)).append('=').append(p.get(ks.get(i)).valueToString()); }
                k=sb.append(']').toString(); } }
        Integer v=SID.get(k);
        if(v==null){ v=SNAME.size(); SID.put(k,v); SNAME.add(k); }
        return v;
    }
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
    static void wVar(ByteArrayOutputStream bo,int n){
        while((n&~0x7F)!=0){bo.write((n&0x7F)|0x80);n>>>=7;} bo.write(n);
    }
    static int rdVar(byte[] b,int[] p){
        int n=0,sh=0,x; do{ x=b[p[0]++]&0xFF; n|=(x&0x7F)<<sh; sh+=7; }while((x&0x80)!=0); return n;
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

    static class Sec { int[] pal; long[] data; int bits; int single=-1; int off=-1; }
    static class Chunk {
        Sec[] secs; byte[] raw;              // raw kept for lazy mode
        long bytesResident(){
            long b=32;
            for(Sec s:secs){ if(s==null) continue; b+=24;
                if(s.pal!=null) b+=16+4L*s.pal.length;
                if(s.data!=null) b+=16+8L*s.data.length; }
            return b;
        }
        int get(int lx,int ly,int lz,int sy){
            Sec s=secs[sy];
            if(s.single>=0) return s.single;
            int i=(ly*16+lz)*16+lx, per=64/s.bits;
            return s.pal[(int)((s.data[i/per]>>>((i%per)*s.bits))&((1L<<s.bits)-1))];
        }
    }

    // EAGER: unpack every section up front
    static Chunk eager(byte[] def) throws Exception {
        byte[] raw=inflate(def);
        return buildFrom(raw,true);
    }
    // LAZY: record section offsets, unpack a section only on first touch
    static Chunk lazy(byte[] def) throws Exception {
        byte[] raw=inflate(def);
        return buildFrom(raw,false);
    }
    static Chunk buildFrom(byte[] raw,boolean eager){
        int[] p={0};
        int nSec=rdVar(raw,p);
        Chunk c=new Chunk(); c.secs=new Sec[nSec]; c.raw=raw;
        for(int s=0;s<nSec;s++){
            Sec sc=new Sec();
            int tag=raw[p[0]++]&0xFF;
            if(tag==1){ sc.single=rdVar(raw,p); c.secs[s]=sc; continue; }
            int np=rdVar(raw,p);
            int bits=Math.max(4,log2ceil(np));
            int per=64/bits, nl=(4096+per-1)/per;
            if(eager){
                sc.pal=new int[np];
                for(int i=0;i<np;i++) sc.pal[i]=rdVar(raw,p);
                sc.bits=bits; sc.data=new long[nl];
                for(int i=0;i<nl;i++){ long v=0;
                    for(int b=0;b<8;b++) v|=((long)(raw[p[0]++]&0xFF))<<(b*8);
                    sc.data[i]=v; }
            } else {
                sc.off=p[0]; sc.bits=bits;
                for(int i=0;i<np;i++) rdVar(raw,p);       // skip palette
                p[0]+=nl*8;                                // skip packed data
            }
            c.secs[s]=sc;
        }
        return c;
    }
    static void force(Chunk c,int s){
        Sec sc=c.secs[s];
        if(sc.single>=0||sc.data!=null) return;
        int[] p={sc.off};
        int np=rdVar(c.raw,p);
        // off pointed at np? recompute: we stored off AFTER reading np, so re-read carefully
        sc.pal=new int[np];
        for(int i=0;i<np;i++) sc.pal[i]=rdVar(c.raw,p);
        int per=64/sc.bits, nl=(4096+per-1)/per;
        sc.data=new long[nl];
        for(int i=0;i<nl;i++){ long v=0;
            for(int b=0;b<8;b++) v|=((long)(c.raw[p[0]++]&0xFF))<<(b*8);
            sc.data[i]=v; }
    }

    interface Op{ void run() throws Exception; }
    static double us(Op o,int it) throws Exception {
        for(int i=0;i<Math.min(it,50);i++) o.run();
        long t=System.nanoTime(); for(int i=0;i<it;i++) o.run();
        return (System.nanoTime()-t)/1e3/it;
    }

    public static void main(String[] a) throws Exception {
        File dir=new File("/tmp/pregen/world/region");
        File[] fs=dir.listFiles((d,n)->n.endsWith(".mca")); Arrays.sort(fs);
        McaRegionFile mca=McaFileHelpers.read(fs[fs.length/2]);
        TerrainChunk tc=null;
        outer: for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
            TerrainChunk c=mca.getChunk(cx,cz); if(c!=null){ tc=c; break outer; } }
        int minS=tc.getMinSectionY(), maxS=tc.getMaxSectionY(), nSec=maxS-minS+1;

        ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<16);
        wVar(bo,nSec);
        int nonAir=0;
        for(int sy=minS;sy<=maxS;sy++){
            TerrainSection s=tc.getSection(sy);
            var pc=(s==null)?null:s.getBlockStates();
            HashMap<Integer,Integer> loc=new HashMap<>(); ArrayList<Integer> list=new ArrayList<>();
            int[] idx=new int[4096];
            for(int i=0;i<4096;i++){
                int g=(pc==null)?sid(null):sid(pc.getByRef(i));
                Integer li=loc.get(g); if(li==null){ li=list.size(); loc.put(g,li); list.add(g);} idx[i]=li; }
            int np=list.size();
            if(np==1){ bo.write(1); wVar(bo,list.get(0)); continue; }
            nonAir++;
            bo.write(2); wVar(bo,np);
            for(int g:list) wVar(bo,g);
            int bits=Math.max(4,log2ceil(np)), per=64/bits, li2=0, sh=0;
            long[] pk=new long[(4096+per-1)/per];
            for(int i=0;i<4096;i++){ pk[li2]|=((long)idx[i])<<(sh*bits); if(++sh==per){sh=0;li2++;} }
            for(long L:pk) for(int b=0;b<8;b++) bo.write((int)((L>>>(b*8))&0xFF));
        }
        final byte[] rawBlob=bo.toByteArray();
        final byte[] def=deflate(rawBlob);
        System.out.printf("=== CACHE DESIGN INPUTS ===%n");
        System.out.printf("  chunk: %d sections (%d non-trivial), compressed %,d B, raw %,d B%n%n",
            nSec,nonAir,def.length,rawBlob.length);

        // 1. decomposition
        double tInf=us(()->{ inflate(def); },3000);
        double tEager=us(()->{ eager(def); },1000);
        double tBuild=tEager-tInf;
        System.out.println("--- 1. WHERE THE MATERIALISE COST GOES ---");
        System.out.printf("  inflate only            : %8.1f us  (%.0f%%)%n",tInf,100*tInf/tEager);
        System.out.printf("  structure build         : %8.1f us  (%.0f%%)%n",tBuild,100*tBuild/tEager);
        System.out.printf("  TOTAL eager materialise : %8.1f us%n",tEager);

        // 2. memory
        Chunk ec=eager(def);
        long res=ec.bytesResident();
        System.out.println("\n--- 2. MEMORY FOOTPRINT ---");
        System.out.printf("  compressed on disk      : %,9d B%n",def.length);
        System.out.printf("  materialised resident   : %,9d B   (%.1fx expansion)%n",
            res,res/(double)def.length);
        System.out.printf("  1,000 chunks cached     : %,9.1f MB%n",res*1000/1e6);
        System.out.printf("  4,000 chunks (r=32 view): %,9.1f MB%n",res*4000/1e6);

        // 3. lazy vs eager
        double tLazy=us(()->{ lazy(def); },2000);
        System.out.println("\n--- 3. LAZY vs EAGER MATERIALISATION ---");
        System.out.printf("  eager (all sections)    : %8.1f us%n",tEager);
        System.out.printf("  lazy  (index only)      : %8.1f us   %.1fx cheaper up front%n",
            tLazy,tEager/tLazy);
        // cost of touching k sections lazily
        for(int k : new int[]{1,3,8}){
            final int kk=k;
            double t=us(()->{
                Chunk c=lazy(def);
                int done=0;
                for(int s=0;s<c.secs.length && done<kk;s++)
                    if(c.secs[s].single<0){ force(c,s); done++; }
            },1000);
            System.out.printf("  lazy + force %d sections : %8.1f us   %s%n",
                k,t, t<tEager? String.format("%.2fx cheaper than eager",tEager/t)
                              : String.format("%.2fx MORE than eager",t/tEager));
        }
        System.out.printf("%n  (a mesher touches ~1-3 surface sections; full-column physics touches all)%n");
    }
}
