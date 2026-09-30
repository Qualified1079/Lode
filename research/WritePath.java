import java.io.*;
import java.util.*;
import java.util.zip.*;

// WRITE PATH — never measured before. Decode was fine; encode is the harder direction because
// DEFLATE is asymmetric (compress is far slower than decompress).
//
// A live format must survive Minecraft's actual write load: autosave of every loaded chunk every
// ~5 min, plus a write on every chunk unload. A busy server has hundreds of loaded chunks.
//
// Measures, per chunk:
//   W1 full re-encode      : palette + bitpack + DEFLATE (what a save costs from scratch)
//   W2 overlay append      : record a block edit into the diff overlay (the live hot path)
//   W3 overlay flush       : serialize + DEFLATE just the accumulated edits
//   W4 vanilla-equivalent  : DEFLATE a vanilla-sized payload, for apples-to-apples
// across DEFLATE levels 1 / 6 / 9, since that is the obvious knob.

public class WritePath {
    static final int HEIGHT=VoxSim9.HEIGHT, Y_MIN=VoxSim9.Y_MIN;
    static final byte AIR=VoxSim9.AIR;

    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}

    static byte[] deflateAt(byte[] in,int level){
        Deflater d=new Deflater(level); d.setInput(in); d.finish();
        ByteArrayOutputStream bo=new ByteArrayOutputStream(in.length/3+64);
        byte[] o=new byte[1<<16];
        while(!d.finished()){int n=d.deflate(o); if(n>0)bo.write(o,0,n); else break;}
        d.end(); return bo.toByteArray();
    }
    static byte[] sectionBytes(byte[][] v,int side,int cx,int cz,int s){
        int[] pi=new int[256]; Arrays.fill(pi,-1);
        int np=0; short[] idx=new short[4096]; byte[] pal=new byte[64];
        for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
            byte b=VoxSim9.get(v,side,cx*16+lx,cz*16+lz,s*16+ly);
            if(pi[b&0xFF]<0){ pal[np]=b; pi[b&0xFF]=np++; }
            idx[(ly*16+lz)*16+lx]=(short)pi[b&0xFF];
        }
        ByteArrayOutputStream bo=new ByteArrayOutputStream(2600);
        bo.write(np);
        for(int i=0;i<np;i++) bo.write(pal[i]);
        if(np>1){
            int bits=Math.max(4,log2ceil(np));
            int per=64/bits, li=0, sh=0;
            long[] pk=new long[(4096+per-1)/per];
            for(int i=0;i<4096;i++){ pk[li]|=((long)idx[i])<<(sh*bits); if(++sh==per){sh=0;li++;} }
            for(long L:pk) for(int b=0;b<8;b++) bo.write((int)((L>>>(b*8))&0xFF));
        }
        return bo.toByteArray();
    }
    static byte[] chunkRaw(byte[][] v,int side,int cx,int cz) throws IOException {
        ByteArrayOutputStream bo=new ByteArrayOutputStream(1<<16);
        for(int s=0;s<HEIGHT/16;s++) bo.write(sectionBytes(v,side,cx,cz,s));
        return bo.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        int side=512, chunks=side/16;
        List<VoxSim9.Feat> log=new ArrayList<>();
        byte[][][] both=VoxSim9.generate(side,0.15,log);
        byte[][] base=both[0];
        System.out.printf("=== WRITE PATH  side=%d, %d chunks ===%n%n",side,chunks*chunks);

        // representative payloads
        byte[] ourChunk=chunkRaw(base,side,10,10);                  // de-featured (what we store)
        byte[] fullChunk=chunkRaw(both[1],side,10,10);               // with features baked in
        // vanilla-equivalent payload: block data + light + heightmaps + NBT, ~3975 B compressed,
        // which measured ~22.5 KB raw. Approximate with the full chunk plus light-sized arrays.
        ByteArrayOutputStream vanBo=new ByteArrayOutputStream(1<<16);
        vanBo.write(fullChunk);
        for(int s=0;s<11;s++){ for(int i=0;i<4096;i++) vanBo.write((i*7+s)&0xFF); } // sky+block light
        for(int m=0;m<4;m++) for(int i=0;i<296;i++) vanBo.write((i*13+m)&0xFF);     // heightmaps
        byte[] vanChunk=vanBo.toByteArray();

        System.out.printf("  raw payload sizes: ours(de-featured) %,d B | ours(full) %,d B | vanilla-equiv %,d B%n%n",
            ourChunk.length, fullChunk.length, vanChunk.length);

        System.out.printf("  %-6s %-24s %10s %10s %12s%n","level","operation","us/chunk","chunks/s","out bytes");
        for(int level : new int[]{1,6,9}){
            // W1: full re-encode of OUR payload (palette+bitpack already in chunkRaw; time deflate)
            double t1=timeUs(()->{ deflateAt(ourChunk,level); },2000);
            long o1=deflateAt(ourChunk,level).length;
            System.out.printf("  %-6d %-24s %10.1f %10.0f %,12d%n",
                level,"W1 deflate ours",t1,1e6/t1,o1);

            // W4: same at vanilla payload size (apples-to-apples: vanilla must also deflate on save)
            double t4=timeUs(()->{ deflateAt(vanChunk,level); },2000);
            long o4=deflateAt(vanChunk,level).length;
            System.out.printf("  %-6d %-24s %10.1f %10.0f %,12d%n",
                level,"W4 deflate vanilla-equiv",t4,1e6/t4,o4);
            System.out.printf("  %-6s %-24s %10.2fx%n","","  ours faster by",t4/t1);
        }

        // full re-encode INCLUDING palette+bitpack work (not just deflate)
        System.out.println();
        double tFull=timeUs(()->{
            try{ byte[] r=chunkRaw(base,side,10,10); deflateAt(r,6); }catch(IOException e){}
        },500);
        System.out.printf("  W1b full re-encode (palette+bitpack+deflate, lvl6): %8.1f us/chunk  -> %,.0f chunks/s%n",
            tFull, 1e6/tFull);

        // ---- W2: overlay append (the live hot path: one block edit) ----
        // overlay = open-addressed map from packed position -> block id, per chunk
        // overlay = open-addressed map, position -> block id. Sized 16384 with a reset at 50%
        // load, which models the real lifecycle (a chunk is promoted once its overlay grows large).
        final int CAP=16384, MASK=CAP-1;
        final int[] keys=new int[CAP]; final byte[] vals=new byte[CAP];
        Arrays.fill(keys,-1);
        final int[] count={0};
        final java.util.Random rr=new java.util.Random(99);
        double t2=timeUs(()->{
            if(count[0] > CAP/2){ Arrays.fill(keys,-1); count[0]=0; }   // promote + reset
            int pos=rr.nextInt(98304);
            int h=(pos*0x9E3779B1)>>>18 & MASK;
            for(int probe=0;probe<CAP;probe++){
                int p2=(h+probe)&MASK;
                if(keys[p2]==-1){ keys[p2]=pos; vals[p2]=3; count[0]++; break; }
                if(keys[p2]==pos){ vals[p2]=3; break; }
            }
        },200000);
        System.out.printf("  W2 overlay append (1 block edit)                  : %8.3f us   -> %,.0f edits/s%n",
            t2, 1e6/t2);

        // ---- W3: overlay flush (serialize + deflate N accumulated edits) ----
        for(int n : new int[]{16,128,1024}){
            ByteArrayOutputStream ov=new ByteArrayOutputStream(n*5);
            Random r=new Random(7);
            for(int i=0;i<n;i++){
                int pos=r.nextInt(98304);
                ov.write(pos&0xFF); ov.write((pos>>>8)&0xFF); ov.write((pos>>>16)&0xFF);
                ov.write(r.nextInt(26));
            }
            byte[] ovb=ov.toByteArray();
            double t3=timeUs(()->{ deflateAt(ovb,6); },20000);
            System.out.printf("  W3 overlay flush, %4d edits                      : %8.2f us   %,d -> %,d B%n",
                n, t3, ovb.length, deflateAt(ovb,6).length);
        }

        // ---- server load model ----
        System.out.println("\n  === SERVER LOAD MODEL ===");
        double usFullLvl6=timeUs(()->{ deflateAt(ourChunk,6); },2000);
        double usFullLvl1=timeUs(()->{ deflateAt(ourChunk,1); },2000);
        for(int loaded : new int[]{200,800,2000}){
            System.out.printf("  %,5d loaded chunks, full re-encode each:  lvl6 %7.1f ms | lvl1 %7.1f ms | overlay-only %6.2f ms%n",
                loaded, loaded*usFullLvl6/1000.0, loaded*usFullLvl1/1000.0,
                loaded*timeUs(()->{ deflateAt(new byte[128],6); },20000)/1000.0);
        }
        System.out.println("  (Minecraft autosaves all loaded chunks every ~5 min = 300,000 ms budget)");
    }

    interface Op { void run(); }
    static double timeUs(Op op,int iters){
        for(int i=0;i<Math.min(iters,200);i++) op.run();   // warm
        long t=System.nanoTime();
        for(int i=0;i<iters;i++) op.run();
        return (System.nanoTime()-t)/1e3/iters;
    }
}
