import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.io.BigEndianNbtOutputStream;
import io.github.ensgijs.nbt.io.NamedTag;
import io.github.ensgijs.nbt.tag.*;
import com.github.luben.zstd.Zstd;
import java.io.*; import java.util.*;
// What does a LIVE-SAFE version cost? Carry every non-derivable chunk NBT field verbatim
// (structures, status, InhabitedTime, tick lists, block entities...), re-encode only the
// block states, and optionally drop light+heightmaps (derivable).
public class LiveAddon {
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
  static byte[] nbt(Tag<?> t) throws IOException {
    ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<14);
    try(BigEndianNbtOutputStream os=new BigEndianNbtOutputStream(bo,false)){ os.writeTag(new NamedTag("",t),512); }
    return bo.toByteArray(); }
  public static void main(String[] a) throws Exception {
    File dir=new File("/tmp/pregen/world/region");
    File[] fs=dir.listFiles((d,n)->n.endsWith(".mca"));
    Arrays.sort(fs,(x,y)->Long.compare(y.length(),x.length()));
    int nR=6;
    long van=0, chunks=0;
    ByteArrayOutputStream blocksAll=new ByteArrayOutputStream(1<<24);
    ByteArrayOutputStream residNoLight=new ByteArrayOutputStream(1<<24);
    ByteArrayOutputStream residWithLight=new ByteArrayOutputStream(1<<24);
    for(int fi=0;fi<nR;fi++){
      File f=fs[fi]; van+=f.length();
      McaRegionFile mca; try{mca=McaFileHelpers.read(f);}catch(Throwable e){continue;}
      for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
        TerrainChunk c; try{c=mca.getChunk(cx,cz);}catch(Throwable e){continue;}
        if(c==null) continue; chunks++;
        // 1. re-encoded block states
        try{ for(int sy=c.getMinSectionY(); sy<=c.getMaxSectionY(); sy++){
            TerrainSection s=c.getSection(sy);
            if(s==null){blocksAll.write(0);continue;}
            var pc=s.getBlockStates(); if(pc==null){blocksAll.write(0);continue;}
            HashMap<Integer,Integer> loc=new HashMap<>(); ArrayList<Integer> L=new ArrayList<>();
            int[] idx=new int[4096];
            for(int i=0;i<4096;i++){int g=sid(pc.getByRef(i));Integer li=loc.get(g);
              if(li==null){li=L.size();loc.put(g,li);L.add(g);} idx[i]=li;}
            int np=L.size();
            if(np==1){blocksAll.write(1);wV(blocksAll,L.get(0));continue;}
            blocksAll.write(2);wV(blocksAll,np); for(int g:L) wV(blocksAll,g);
            int bits=Math.max(4,l2c(np)),per=64/bits,li2=0,sh=0;
            long[] pk=new long[(4096+per-1)/per];
            for(int i=0;i<4096;i++){pk[li2]|=((long)idx[i])<<(sh*bits);if(++sh==per){sh=0;li2++;}}
            for(long v:pk) for(int b=0;b<8;b++) blocksAll.write((int)((v>>>(b*8))&0xFF)); }
        }catch(Throwable e){}
        // 2. residual chunk NBT: everything EXCEPT what we re-encode
        try{
          CompoundTag h=c.updateHandle(cx,cz);
          CompoundTag copy=h.clone();
          CompoundTag withLight=h.clone();
          // strip block_states + biomes from every section in BOTH; strip light only from `copy`
          for(CompoundTag t : new CompoundTag[]{copy,withLight}){
            ListTag<CompoundTag> secs=null;
            try{ secs=t.getListTag("sections").asCompoundTagList(); }catch(Throwable e){}
            if(secs!=null) for(CompoundTag s:secs){
              s.remove("block_states"); s.remove("biomes");
              if(t==copy){ s.remove("BlockLight"); s.remove("SkyLight"); }
            }
            if(t==copy) t.remove("Heightmaps");
          }
          residNoLight.write(nbt(copy));
          residWithLight.write(nbt(withLight));
        }catch(Throwable e){}
      }
    }
    byte[] B=blocksAll.toByteArray(), RN=residNoLight.toByteArray(), RW=residWithLight.toByteArray();
    long zB=Zstd.compress(B,19).length, zRN=Zstd.compress(RN,19).length, zRW=Zstd.compress(RW,19).length;
    System.out.printf("=== LIVE-SAFE COSTING (6 regions, %,d chunks) ===%n%n",chunks);
    System.out.printf("vanilla                              : %,13d B   1.00x%n",van);
    System.out.printf("Linear lvl19 (measured earlier)      : %,13d B   %.2fx%n",32618365L,van/32618365.0);
    System.out.printf("%n  re-encoded block states (zstd19)   : %,13d B%n",zB);
    System.out.printf("  residual NBT, light DROPPED        : %,13d B%n",zRN);
    System.out.printf("  residual NBT, light KEPT           : %,13d B%n",zRW);
    System.out.printf("%n--- TOTALS ---%n");
    System.out.printf("  blocks only, nothing else (my old claim) : %,13d B   %.2fx%n",zB,van/(double)zB);
    System.out.printf("  LIVE-SAFE, light dropped + relight       : %,13d B   %.2fx%n",zB+zRN,van/(double)(zB+zRN));
    System.out.printf("  LIVE-SAFE, light kept (no relight)       : %,13d B   %.2fx%n",zB+zRW,van/(double)(zB+zRW));
    System.out.printf("%n  vs Linear 1.88x:  light-dropped %.2fx better | light-kept %.2fx better%n",
      32618365.0/(zB+zRN), 32618365.0/(zB+zRW));
  }
}
