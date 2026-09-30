import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.*;
import java.io.*; import java.util.*;
// How expensive is it to REGENERATE the light we chose not to store?
// Simplified skylight: direct top-down seeding, then BFS horizontal/downward propagation
// decrementing by 1 per step. Same shape as the real engine (which also does block light and
// cross-chunk propagation, so treat this as a LOWER BOUND).
public class RelightCost {
  public static void main(String[] a) throws Exception {
    File dir=new File("/tmp/pregen/world/region");
    File[] fs=dir.listFiles((d,n)->n.endsWith(".mca")); Arrays.sort(fs);
    McaRegionFile mca=McaFileHelpers.read(fs[fs.length/2]);
    // pull one chunk's opacity into a flat array
    TerrainChunk tc=null;
    outer: for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
      TerrainChunk c=mca.getChunk(cx,cz); if(c!=null){ tc=c; break outer; } }
    int minS=tc.getMinSectionY(), maxS=tc.getMaxSectionY(), H=(maxS-minS+1)*16;
    final boolean[] opaque=new boolean[16*16*H];
    int solid=0;
    for(int sy=minS;sy<=maxS;sy++){
      TerrainSection s=tc.getSection(sy); if(s==null) continue;
      var pc=s.getBlockStates(); if(pc==null) continue;
      for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
        CompoundTag st=pc.getByRef(lx,ly,lz);
        String nm=(st==null)?"minecraft:air":st.getString("Name");
        boolean op = nm!=null && !nm.equals("minecraft:air") && !nm.equals("minecraft:water")
                     && !nm.contains("leaves") && !nm.contains("glass");
        if(op) solid++;
        opaque[(lx*16+lz)*H+((sy-minS)*16+ly)]=op;
      }
    }
    final int HH=H;
    System.out.printf("chunk: %d sections, height %d, %,d opaque of %,d cells (%.1f%%)%n%n",
      maxS-minS+1,H,solid,16*16*H,100.0*solid/(16*16*H));

    Runnable relight=()->{
      byte[] sky=new byte[16*16*HH];
      int[] q=new int[16*16*HH]; int head=0,tail=0;
      // direct skylight from the top
      for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){
        int lv=15;
        for(int y=HH-1;y>=0;y--){
          int i=(lx*16+lz)*HH+y;
          if(opaque[i]){ lv=0; }
          sky[i]=(byte)lv;
          if(lv==15) q[tail++]=i;
        }
      }
      // BFS propagation, decrement 1 per step
      while(head<tail){
        int cur=q[head++];
        int y=cur%HH, r=cur/HH, lz=r%16, lx=r/16;
        int lv=sky[cur];
        if(lv<=1) continue;
        for(int d=0;d<6;d++){
          int nx=lx,ny=y,nz=lz;
          if(d==0)nx++; else if(d==1)nx--; else if(d==2)ny++;
          else if(d==3)ny--; else if(d==4)nz++; else nz--;
          if(nx<0||nz<0||ny<0||nx>15||nz>15||ny>=HH) continue;
          int ni=(nx*16+nz)*HH+ny;
          if(opaque[ni]) continue;
          if(sky[ni]>=lv-1) continue;
          sky[ni]=(byte)(lv-1);
          if(tail<q.length) q[tail++]=ni;
        }
      }
    };
    for(int i=0;i<30;i++) relight.run();
    long t=System.nanoTime(); int N=200;
    for(int i=0;i<N;i++) relight.run();
    double us=(System.nanoTime()-t)/1e3/N;
    System.out.printf("RELIGHT (skylight only, single chunk, no cross-chunk): %8.1f us%n",us);
    System.out.printf("  vs materialise cost (~136-270 us)               : %.1fx - %.1fx%n",us/270.0,us/136.0);
    System.out.printf("  4,000 chunks would cost                          : %8.1f ms%n",us*4000/1000.0);
    System.out.println("\n  NOTE: real engine also does BLOCK light + cross-chunk propagation +");
    System.out.println("  re-propagation on every block change. This is a LOWER BOUND.");
  }
}
