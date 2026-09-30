import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import com.github.luben.zstd.Zstd;
import java.io.*; import java.util.*; import java.util.zip.Deflater;
// DEFLATE vs ZSTD on our encoding, real data. Also per-chunk vs per-region granularity.
public class ZstdCmp {
  static HashMap<String,Integer> SID=new HashMap<>(); static int nS=0;
  static int sid(CompoundTag st){ String k;
    if(st==null)k="minecraft:air"; else{ String n=st.getString("Name");
      CompoundTag p=st.getCompoundTag("Properties");
      if(p==null||p.size()==0)k=n; else{ StringBuilder sb=new StringBuilder(n).append('[');
        ArrayList<String> ks=new ArrayList<>(p.keySet()); Collections.sort(ks);
        for(int i=0;i<ks.size();i++){if(i>0)sb.append(',');sb.append(ks.get(i)).append('=').append(p.get(ks.get(i)).valueToString());}
        k=sb.append(']').toString(); } }
    Integer v=SID.get(k); if(v==null){v=nS++;SID.put(k,v);} return v; }
  static int l2c(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
  static void wV(ByteArrayOutputStream b,int n){while((n&~0x7F)!=0){b.write((n&0x7F)|0x80);n>>>=7;}b.write(n);}
  static long defl(byte[] in,int lvl){ Deflater d=new Deflater(lvl);d.setInput(in);d.finish();
    byte[] o=new byte[1<<16]; long t=0; while(!d.finished()){int n=d.deflate(o); if(n>0)t+=n; else break;} d.end(); return t; }
  public static void main(String[] a) throws Exception {
    File dir=new File(a.length>0?a[0]:"/tmp/pregen/world/region");
    int nRw=a.length>1?Integer.parseInt(a[1]):6;
    File[] fs=dir.listFiles((d,n)->n.endsWith(".mca"));
    Arrays.sort(fs,(x,y)->Long.compare(y.length(),x.length()));
    int nR=Math.min(nRw,fs.length);
    long van=0,chunks=0;
    long dfl6=0,dfl9=0,z3=0,z9=0,z19=0;
    ByteArrayOutputStream regionAll=new ByteArrayOutputStream(1<<24);
    long regionDfl=0, regionZ19=0;
    long tD=0,tZ=0;
    for(int fi=0;fi<nR;fi++){
      File f=fs[fi]; van+=f.length();
      McaRegionFile mca; try{mca=McaFileHelpers.read(f);}catch(Throwable e){continue;}
      ByteArrayOutputStream regionBlob=new ByteArrayOutputStream(1<<23);
      for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
        TerrainChunk c; try{c=mca.getChunk(cx,cz);}catch(Throwable e){continue;}
        if(c==null) continue; chunks++;
        ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<16);
        try{ for(int sy=c.getMinSectionY(); sy<=c.getMaxSectionY(); sy++){
            TerrainSection s=c.getSection(sy);
            if(s==null){bo.write(0);continue;}
            var pc=s.getBlockStates(); if(pc==null){bo.write(0);continue;}
            HashMap<Integer,Integer> loc=new HashMap<>(); ArrayList<Integer> L=new ArrayList<>();
            int[] idx=new int[4096];
            for(int i=0;i<4096;i++){int g=sid(pc.getByRef(i));Integer li=loc.get(g);
              if(li==null){li=L.size();loc.put(g,li);L.add(g);} idx[i]=li;}
            int np=L.size();
            if(np==1){bo.write(1);wV(bo,L.get(0));continue;}
            bo.write(2);wV(bo,np); for(int g:L) wV(bo,g);
            int bits=Math.max(4,l2c(np)),per=64/bits,li2=0,sh=0;
            long[] pk=new long[(4096+per-1)/per];
            for(int i=0;i<4096;i++){pk[li2]|=((long)idx[i])<<(sh*bits);if(++sh==per){sh=0;li2++;}}
            for(long v:pk) for(int b=0;b<8;b++) bo.write((int)((v>>>(b*8))&0xFF)); }
        }catch(Throwable e){}
        byte[] raw=bo.toByteArray();
        long t0=System.nanoTime(); dfl6+=defl(raw,6); tD+=System.nanoTime()-t0;
        dfl9+=defl(raw,9);
        t0=System.nanoTime(); z3+=Zstd.compress(raw,3).length; tZ+=System.nanoTime()-t0;
        z9+=Zstd.compress(raw,9).length;
        z19+=Zstd.compress(raw,19).length;
        regionBlob.write(raw);
      }
      byte[] rb=regionBlob.toByteArray();
      regionDfl+=defl(rb,6);
      regionZ19+=Zstd.compress(rb,19).length;
    }
    System.out.printf("=== %s : %d regions, %,d chunks ===%n%n",dir,nR,chunks);
    System.out.printf("vanilla on disk           : %,13d B   (%,d B/chunk)%n",van,van/chunks);
    System.out.printf("%n--- PER-CHUNK (chunk-granular access) ---%n");
    System.out.printf("  DEFLATE lvl6            : %,13d B   %.2fx   [%.0f us/chunk]%n",dfl6,van/(double)dfl6,tD/1e3/chunks);
    System.out.printf("  DEFLATE lvl9            : %,13d B   %.2fx%n",dfl9,van/(double)dfl9);
    System.out.printf("  ZSTD lvl3               : %,13d B   %.2fx   [%.0f us/chunk]%n",z3,van/(double)z3,tZ/1e3/chunks);
    System.out.printf("  ZSTD lvl9               : %,13d B   %.2fx%n",z9,van/(double)z9);
    System.out.printf("  ZSTD lvl19              : %,13d B   %.2fx%n",z19,van/(double)z19);
    System.out.printf("%n--- PER-REGION (Linear-style; 1 stream per region) ---%n");
    System.out.printf("  DEFLATE lvl6            : %,13d B   %.2fx%n",regionDfl,van/(double)regionDfl);
    System.out.printf("  ZSTD lvl19              : %,13d B   %.2fx%n",regionZ19,van/(double)regionZ19);
    System.out.printf("%n  zstd19 vs deflate6, per-chunk : %.1f%% smaller%n",100.0*(dfl6-z19)/dfl6);
    System.out.printf("  per-region vs per-chunk (z19) : %.1f%% smaller%n",100.0*(z19-regionZ19)/z19);
  }
}
