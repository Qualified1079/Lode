import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import java.io.*; import java.util.*; import java.util.zip.Deflater;
// Per-CHUNK ratio distribution. A world made entirely of the worst chunks is the true floor.
public class WorstChunk {
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
  static long dl(byte[] in){ Deflater d=new Deflater(6);d.setInput(in);d.finish();
    byte[] o=new byte[1<<16]; long t=0; while(!d.finished()){int n=d.deflate(o); if(n>0)t+=n; else break;} d.end(); return t; }
  public static void main(String[] a) throws Exception {
    for(String[] w : new String[][]{
        {"wilderness","/tmp/pregen/world/region","8"},
        {"dense-struct","/tmp/pregen2/world/region","8"},
        {"tazader-city","/tmp/upgrade/tazader/region","8"}}){
      File dir=new File(w[1]); File[] fs=dir.listFiles((d,n)->n.endsWith(".mca"));
      if(fs==null){ System.out.println("skip "+w[0]); continue; }
      Arrays.sort(fs,(x,y)->Long.compare(y.length(),x.length()));
      int nR=Math.min(Integer.parseInt(w[2]),fs.length);
      ArrayList<double[]> rows=new ArrayList<>(); // {ratio, vanillaBytes, ourBytes}
      for(int fi=0; fi<nR; fi++){
        File f=fs[fi]; McaRegionFile mca;
        try{ mca=McaFileHelpers.read(f);}catch(Throwable e){continue;}
        // per-chunk vanilla size from the header
        int[] vsz=new int[1024];
        try(RandomAccessFile raf=new RandomAccessFile(f,"r")){
          byte[] loc=new byte[4096]; raf.readFully(loc);
          for(int i=0;i<1024;i++){
            int off=((loc[i*4]&0xFF)<<16)|((loc[i*4+1]&0xFF)<<8)|(loc[i*4+2]&0xFF);
            int cnt=loc[i*4+3]&0xFF; if(off==0||cnt==0) continue;
            raf.seek((long)off*4096); vsz[i]=raf.readInt()+4;   // real payload, padding excluded
          }
        }catch(Exception e){}
        for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
          TerrainChunk c; try{c=mca.getChunk(cx,cz);}catch(Throwable e){continue;}
          if(c==null) continue;
          int vi=vsz[cx+32*cz]; if(vi<=0) continue;
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
          long ours=dl(bo.toByteArray());
          if(ours<=0) continue;
          rows.add(new double[]{vi/(double)ours, vi, ours});
        }
      }
      rows.sort((p,q)->Double.compare(p[0],q[0]));
      int n=rows.size(); if(n==0){System.out.println("no chunks "+w[0]); continue;}
      double sv=0,so=0; for(double[] r:rows){sv+=r[1];so+=r[2];}
      System.out.printf("%n=== %s : %,d chunks (payload-vs-ours, padding EXCLUDED) ===%n",w[0],n);
      System.out.printf("  aggregate %.2fx%n",sv/so);
      System.out.printf("  WORST %.2fx | p1 %.2fx | p10 %.2fx | median %.2fx | p90 %.2fx | best %.2fx%n",
        rows.get(0)[0],rows.get(n/100)[0],rows.get(n/10)[0],rows.get(n/2)[0],
        rows.get(n*9/10)[0],rows.get(n-1)[0]);
      double w1=0,o1=0; int k=Math.max(1,n/100);
      for(int i=0;i<k;i++){w1+=rows.get(i)[1];o1+=rows.get(i)[2];}
      System.out.printf("  a world made ONLY of the worst 1%% of chunks: %.2fx%n",w1/o1);
    }
  }
}
