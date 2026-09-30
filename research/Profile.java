import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import java.io.*; import java.util.*;
public class Profile {
  static final Set<String> TERRAIN=new HashSet<>(Arrays.asList(
    "minecraft:air","minecraft:stone","minecraft:water","minecraft:sand","minecraft:dirt",
    "minecraft:bedrock","minecraft:gravel","minecraft:grass_block","minecraft:sandstone",
    "minecraft:clay","minecraft:cave_air"));
  public static void main(String[] a) throws Exception {
    File dir=new File(a[0]);
    File[] fs=dir.listFiles((d,n)->n.endsWith(".mca")); Arrays.sort(fs);
    int nR=Math.min(a.length>1?Integer.parseInt(a[1]):6, fs.length);
    long[] solidByY=new long[400], buildByY=new long[400];
    long buildTot=0, buildInterior=0, buildExposed=0;
    int yOff=64; // world y + 64 -> index
    for(int fi=0; fi<nR; fi++){
      McaRegionFile mca; try{ mca=McaFileHelpers.read(fs[fi]); }catch(Exception e){ continue; }
      // build a per-region occupancy grid for the interior test
      int W=512, minS=99, maxS=-99;
      for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
        TerrainChunk c=mca.getChunk(cx,cz); if(c==null) continue;
        minS=Math.min(minS,c.getMinSectionY()); maxS=Math.max(maxS,c.getMaxSectionY()); }
      if(minS>maxS) continue;
      int H=(maxS-minS+1)*16;
      byte[] kind=new byte[W*W*H];   // 0 air, 1 terrain-solid, 2 build
      for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
        TerrainChunk c=mca.getChunk(cx,cz); if(c==null) continue;
        for(int sy=c.getMinSectionY(); sy<=c.getMaxSectionY(); sy++){
          TerrainSection s=c.getSection(sy); if(s==null) continue;
          var pc=s.getBlockStates(); if(pc==null) continue;
          for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
            CompoundTag st=pc.getByRef(lx,ly,lz); if(st==null) continue;
            String nm=st.getString("Name"); if(nm==null||nm.equals("minecraft:air")||nm.equals("minecraft:cave_air")) continue;
            int wy=sy*16+ly;
            int yi=wy+yOff; if(yi>=0&&yi<400) solidByY[yi]++;
            boolean build=!TERRAIN.contains(nm);
            if(build && yi>=0 && yi<400) buildByY[yi]++;
            kind[((cx*16+lx)*W+(cz*16+lz))*H+(wy-minS*16)] = (byte)(build?2:1);
          }
        }
      }
      // interior vs exposed for BUILD blocks
      for(int x=0;x<W;x++)for(int z=0;z<W;z++)for(int y=0;y<H;y++){
        int i=((x)*W+z)*H+y;
        if(kind[i]!=2) continue;
        buildTot++;
        boolean exposed=false;
        for(int d=0;d<6&&!exposed;d++){
          int nx=x,ny=y,nz=z;
          if(d==0)nx++; else if(d==1)nx--; else if(d==2)ny++; else if(d==3)ny--; else if(d==4)nz++; else nz--;
          if(nx<0||nz<0||ny<0||nx>=W||nz>=W||ny>=H){ exposed=true; break; }
          if(kind[((nx)*W+nz)*H+ny]==0) exposed=true;
        }
        if(exposed) buildExposed++; else buildInterior++;
      }
      System.out.printf("  region %d/%d\n",fi+1,nR);
    }
    System.out.printf("%n=== VERTICAL PROFILE (%d regions) ===%n",nR);
    System.out.printf("%-8s %14s %14s%n","world y","solid blocks","build blocks");
    for(int y=399;y>=0;y--){
      if(solidByY[y]==0&&buildByY[y]==0) continue;
      if(solidByY[y]<1000 && buildByY[y]<200) continue;
      System.out.printf("%-8d %,14d %,14d%n",y-64,solidByY[y],buildByY[y]);
    }
    System.out.printf("%n=== VENEER TEST (build blocks only) ===%n");
    System.out.printf("  build blocks    : %,d%n",buildTot);
    System.out.printf("  exposed to air  : %,d  (%.1f%%)%n",buildExposed,100.0*buildExposed/Math.max(1,buildTot));
    System.out.printf("  fully interior  : %,d  (%.1f%%)%n",buildInterior,100.0*buildInterior/Math.max(1,buildTot));
    System.out.println("  (high exposed% = hollow shells / veneers; low = solid masses)");
  }
}
