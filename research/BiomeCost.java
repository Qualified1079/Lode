import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import com.github.luben.zstd.Zstd;
import java.io.*; import java.util.*; import java.util.zip.Deflater;
// What do BIOMES cost? They were dropped entirely from every earlier measurement.
public class BiomeCost {
  static HashMap<String,Integer> SID=new HashMap<>(); static int nS=0;
  static HashMap<String,Integer> BID=new HashMap<>(); static int nB=0;
  static int sid(CompoundTag st){ String k;
    if(st==null)k="minecraft:air"; else{ String n=st.getString("Name");
      CompoundTag p=st.getCompoundTag("Properties");
      if(p==null||p.size()==0)k=n; else{ StringBuilder sb=new StringBuilder(n).append('[');
        ArrayList<String> ks=new ArrayList<>(p.keySet()); Collections.sort(ks);
        for(int i=0;i<ks.size();i++){if(i>0)sb.append(',');sb.append(ks.get(i)).append('=').append(p.get(ks.get(i)).valueToString());}
        k=sb.append(']').toString(); } }
    Integer v=SID.get(k); if(v==null){v=nS++;SID.put(k,v);} return v; }
  static int bid(String s){ if(s==null)s="minecraft:plains";
    Integer v=BID.get(s); if(v==null){v=nB++;BID.put(s,v);} return v; }
  static int l2c(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
  static void wV(ByteArrayOutputStream b,int n){while((n&~0x7F)!=0){b.write((n&0x7F)|0x80);n>>>=7;}b.write(n);}
  public static void main(String[] a) throws Exception {
    File dir=new File("/tmp/pregen/world/region");
    File[] fs=dir.listFiles((d,n)->n.endsWith(".mca"));
    Arrays.sort(fs,(x,y)->Long.compare(y.length(),x.length()));
    int nR=6; long van=0, chunks=0;
    ByteArrayOutputStream blocks=new ByteArrayOutputStream(1<<24);
    ByteArrayOutputStream biomes=new ByteArrayOutputStream(1<<22);
    long secs=0, secsWithBiome=0;
    for(int fi=0;fi<nR;fi++){
      File f=fs[fi]; van+=f.length();
      McaRegionFile mca; try{mca=McaFileHelpers.read(f);}catch(Throwable e){continue;}
      for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
        TerrainChunk c; try{c=mca.getChunk(cx,cz);}catch(Throwable e){continue;}
        if(c==null) continue; chunks++;
        for(int sy=c.getMinSectionY(); sy<=c.getMaxSectionY(); sy++){
          TerrainSection s=c.getSection(sy);
          if(s==null){blocks.write(0);biomes.write(0);continue;}
          secs++;
          var pc=s.getBlockStates();
          if(pc==null){blocks.write(0);} else {
            HashMap<Integer,Integer> loc=new HashMap<>(); ArrayList<Integer> L=new ArrayList<>();
            int[] idx=new int[4096];
            for(int i=0;i<4096;i++){int g=sid(pc.getByRef(i));Integer li=loc.get(g);
              if(li==null){li=L.size();loc.put(g,li);L.add(g);} idx[i]=li;}
            int np=L.size();
            if(np==1){blocks.write(1);wV(blocks,L.get(0));}
            else{ blocks.write(2);wV(blocks,np); for(int g:L) wV(blocks,g);
              int bits=Math.max(4,l2c(np)),per=64/bits,li2=0,sh=0;
              long[] pk=new long[(4096+per-1)/per];
              for(int i=0;i<4096;i++){pk[li2]|=((long)idx[i])<<(sh*bits);if(++sh==per){sh=0;li2++;}}
              for(long v:pk) for(int b=0;b<8;b++) blocks.write((int)((v>>>(b*8))&0xFF)); }
          }
          // biomes: 4x4x4 = 64 cells per section, paletted, packed at min bits
          var bio=s.getBiomes();
          if(bio==null){ biomes.write(0); continue; }
          secsWithBiome++;
          int n=bio.size();  // should be 64
          HashMap<Integer,Integer> bl=new HashMap<>(); ArrayList<Integer> BL=new ArrayList<>();
          int[] bidx=new int[n];
          for(int i=0;i<n;i++){ StringTag t=bio.getByRef(i);
            int g=bid(t==null?null:t.getValue());
            Integer li=bl.get(g); if(li==null){li=BL.size();bl.put(g,li);BL.add(g);} bidx[i]=li; }
          int np=BL.size();
          if(np==1){ biomes.write(1); wV(biomes,BL.get(0)); continue; }
          biomes.write(2); wV(biomes,np);
          for(int g:BL) wV(biomes,g);
          int bits=Math.max(1,l2c(np));
          int per=64/bits, li2=0, sh=0;
          long[] pk=new long[(n+per-1)/per];
          for(int i=0;i<n;i++){pk[li2]|=((long)bidx[i])<<(sh*bits);if(++sh==per){sh=0;li2++;}}
          for(long v:pk) for(int b=0;b<8;b++) biomes.write((int)((v>>>(b*8))&0xFF));
        }
      }
    }
    byte[] B=blocks.toByteArray(), Bi=biomes.toByteArray();
    long zB=Zstd.compress(B,19).length, zBi=Zstd.compress(Bi,19).length;
    System.out.printf("=== BIOME COST (6 regions, %,d chunks, %,d sections) ===%n%n",chunks,secs);
    System.out.printf("  distinct biomes           : %d%n",nB);
    System.out.printf("  sections with biome data  : %,d%n%n",secsWithBiome);
    System.out.printf("  blocks only (zstd19)      : %,13d B   (%,d B/chunk)%n",zB,zB/chunks);
    System.out.printf("  biomes      (zstd19)      : %,13d B   (%,d B/chunk)%n",zBi,zBi/chunks);
    System.out.printf("  blocks + biomes           : %,13d B   (%,d B/chunk)%n",zB+zBi,(zB+zBi)/chunks);
    System.out.printf("%n  biome share of the total  : %.1f%%%n",100.0*zBi/(zB+zBi));
    System.out.printf("%n  vanilla                   : %,13d B%n",van);
    System.out.printf("  ratio WITHOUT biomes (bug): %.2fx%n",van/(double)zB);
    System.out.printf("  ratio WITH biomes (fixed) : %.2fx%n",van/(double)(zB+zBi));
    System.out.printf("%n  correction factor         : %.3fx%n",(double)(zB+zBi)/zB);
  }
}
