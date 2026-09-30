import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import java.io.File;
public class Verify {
  public static void main(String[] a) throws Exception {
    for(String n : new String[]{"r.0.0.mca","r.0.2.mca"}){
      File f=new File("mcaout/"+n);
      McaRegionFile m=McaFileHelpers.read(f);
      int nc=0,ns=0; String sample=null;
      for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
        TerrainChunk c=m.getChunk(cx,cz);
        if(c==null) continue;
        nc++;
        for(TerrainSection s:c){ ns++; }
        if(sample==null){
          sample="chunk(0,0) dataVersion="+c.getDataVersion()
                +" minSecY="+c.getMinSectionY()+" maxSecY="+c.getMaxSectionY();
        }
      }
      System.out.printf("%s: %,d bytes | %d chunks, %d sections read back OK%n  %s%n",
        n, f.length(), nc, ns, sample);
    }
  }
}
