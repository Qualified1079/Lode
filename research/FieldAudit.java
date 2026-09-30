import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.io.BigEndianNbtOutputStream;
import io.github.ensgijs.nbt.io.NamedTag;
import io.github.ensgijs.nbt.tag.*;
import com.github.luben.zstd.Zstd;
import java.io.*; import java.util.*;
// Enumerate EVERY chunk NBT field present in real data, how often, and what it costs.
// Then flag which ones the encoder actually stores.
public class FieldAudit {
  static byte[] nbt(Tag<?> t) throws IOException {
    ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<14);
    try(BigEndianNbtOutputStream os=new BigEndianNbtOutputStream(bo,false)){ os.writeTag(new NamedTag("",t),512); }
    return bo.toByteArray(); }
  public static void main(String[] a) throws Exception {
    String[] worlds={"/tmp/pregen/world/region","/tmp/pregen2/world/region"};
    TreeMap<String,long[]> top=new TreeMap<>();   // key -> {chunksPresent, rawBytes}
    TreeMap<String,long[]> sec=new TreeMap<>();
    long chunks=0;
    for(String w:worlds){
      File[] fs=new File(w).listFiles((d,n)->n.endsWith(".mca"));
      if(fs==null) continue;
      Arrays.sort(fs,(x,y)->Long.compare(y.length(),x.length()));
      for(int fi=0;fi<Math.min(3,fs.length);fi++){
        McaRegionFile mca; try{mca=McaFileHelpers.read(fs[fi]);}catch(Throwable e){continue;}
        for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
          TerrainChunk c; try{c=mca.getChunk(cx,cz);}catch(Throwable e){continue;}
          if(c==null) continue; chunks++;
          CompoundTag h;
          try{ h=c.updateHandle(cx,cz); }catch(Throwable e){ continue; }
          for(String k:h.keySet()){
            long sz=0; try{ sz=nbt(h.get(k)).length; }catch(Throwable e){}
            long[] e2=top.computeIfAbsent(k,x->new long[2]); e2[0]++; e2[1]+=sz;
          }
          try{
            ListTag<CompoundTag> ss=h.getListTag("sections").asCompoundTagList();
            if(ss!=null) for(CompoundTag s:ss) for(String k:s.keySet()){
              long sz=0; try{ sz=nbt(s.get(k)).length; }catch(Throwable e){}
              long[] e2=sec.computeIfAbsent(k,x->new long[2]); e2[0]++; e2[1]+=sz;
            }
          }catch(Throwable e){}
        }
      }
    }
    Set<String> STORED_TOP=new HashSet<>(Arrays.asList("sections","block_entities"));
    Set<String> STORED_SEC=new HashSet<>(Arrays.asList("block_states","biomes","Y"));
    Set<String> DERIVABLE=new HashSet<>(Arrays.asList("Heightmaps","BlockLight","SkyLight","isLightOn"));
    System.out.printf("=== CHUNK FIELD AUDIT (%,d real chunks) ===%n%n",chunks);
    System.out.printf("%-26s %9s %14s %12s  %s%n","TOP-LEVEL KEY","present","raw bytes","B/chunk","status");
    for(Map.Entry<String,long[]> e:top.entrySet()){
      String st = STORED_TOP.contains(e.getKey()) ? "STORED"
                : DERIVABLE.contains(e.getKey()) ? "derivable (dropped)"
                : "** DROPPED **";
      System.out.printf("%-26s %9d %,14d %,12d  %s%n",
        e.getKey(),e.getValue()[0],e.getValue()[1],e.getValue()[1]/Math.max(1,chunks),st);
    }
    System.out.printf("%n%-26s %9s %14s %12s  %s%n","PER-SECTION KEY","present","raw bytes","B/chunk","status");
    for(Map.Entry<String,long[]> e:sec.entrySet()){
      String st = STORED_SEC.contains(e.getKey()) ? "STORED"
                : DERIVABLE.contains(e.getKey()) ? "derivable (dropped)"
                : "** DROPPED **";
      System.out.printf("%-26s %9d %,14d %,12d  %s%n",
        e.getKey(),e.getValue()[0],e.getValue()[1],e.getValue()[1]/Math.max(1,chunks),st);
    }
    // cost of the genuinely-dropped, non-derivable top-level fields
    long droppedRaw=0;
    for(Map.Entry<String,long[]> e:top.entrySet())
      if(!STORED_TOP.contains(e.getKey()) && !DERIVABLE.contains(e.getKey())) droppedRaw+=e.getValue()[1];
    System.out.printf("%n  non-derivable top-level data currently DROPPED: %,d B raw (%,d B/chunk)%n",
      droppedRaw, droppedRaw/Math.max(1,chunks));
  }
}
