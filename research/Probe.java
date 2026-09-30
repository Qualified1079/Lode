import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import java.io.*;
import java.util.*;
import java.util.zip.*;

// Two experiments:
//
// PART 1 - LAYOUT ISOLATION. "DEFLATE already finds those runs, why is this a gain?"
//   Decomposes the Column-RLE advantage into its actual causes by DEFLATE-ing the SAME voxel data
//   in different layouts. If DEFLATE were layout-agnostic these would all be equal.
//     L1 vanilla order  : per-16^3 section, YZX order (y outermost) -- what MC actually stores
//     L2 section Y-inner: per-16^3 section, but Y innermost (vertical runs adjacent)
//     L3 full column    : whole 384-tall column contiguous, no section split
//     L4 bit-packed     : L1 but paletted + bit-packed like real MC (adds non-byte-aligned cost)
//     L5 column-RLE     : explicit (block,runlen) tokens
//
// PART 2 - DECODE TIME. Never measured before; it is the number that decides live viability.
//   Times full reconstruction of the volume from each format, plus real MCA via the library.

public class Probe {
    static final int HEIGHT=VoxSim9.HEIGHT, Y_MIN=VoxSim9.Y_MIN;
    static final byte AIR=VoxSim9.AIR;

    static byte[] deflate(byte[] in){
        Deflater d=new Deflater(6); d.setInput(in); d.finish();
        ByteArrayOutputStream bo=new ByteArrayOutputStream(in.length/4+1024);
        byte[] o=new byte[1<<16];
        while(!d.finished()){ int n=d.deflate(o); if(n>0) bo.write(o,0,n); else break; }
        d.end(); return bo.toByteArray();
    }
    static long dlen(byte[] in){ return deflate(in).length; }
    static void wVar(ByteArrayOutputStream bo,int n){
        while((n&~0x7F)!=0){bo.write((n&0x7F)|0x80);n>>>=7;} bo.write(n);
    }
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}

    public static void main(String[] a) throws Exception {
        int side=512;
        List<VoxSim9.Feat> log=new ArrayList<>();
        byte[][][] both=VoxSim9.generate(side,0.15,log);
        byte[][] base=both[0], v=both[1];
        int chunks=side/16, sections=HEIGHT/16;
        System.out.printf("=== side=%d, %d chunks, %d features ===%n%n",side,chunks*chunks,log.size());

        // ---------------- PART 1: LAYOUT ISOLATION ----------------
        System.out.println("### PART 1 - LAYOUT ISOLATION (same voxels, same DEFLATE, different layout) ###");

        // L1: vanilla order -- per section, index=(y*16+z)*16+x  (y outermost)
        ByteArrayOutputStream l1=new ByteArrayOutputStream(1<<24);
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++)for(int s=0;s<sections;s++)
            for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++)
                l1.write(VoxSim9.get(v,side,cx*16+lx,cz*16+lz,s*16+ly));
        long L1=dlen(l1.toByteArray()); l1=null;

        // L2: per section, but Y innermost -> vertical runs adjacent in the stream
        ByteArrayOutputStream l2=new ByteArrayOutputStream(1<<24);
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++)for(int s=0;s<sections;s++)
            for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)for(int ly=0;ly<16;ly++)
                l2.write(VoxSim9.get(v,side,cx*16+lx,cz*16+lz,s*16+ly));
        long L2=dlen(l2.toByteArray()); l2=null;

        // L3: full 384-tall columns contiguous, NO section split
        ByteArrayOutputStream l3=new ByteArrayOutputStream(1<<24);
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++)
            for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)
                for(int yi=0;yi<HEIGHT;yi++)
                    l3.write(VoxSim9.get(v,side,cx*16+lx,cz*16+lz,yi));
        long L3=dlen(l3.toByteArray()); l3=null;

        // L4: vanilla order + per-section palette + bit-packed indices (what MC really writes)
        ByteArrayOutputStream l4=new ByteArrayOutputStream(1<<24);
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++)for(int s=0;s<sections;s++){
            int[] pi=new int[256]; Arrays.fill(pi,-1);
            int np=0; short[] idx=new short[4096];
            for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
                byte b=VoxSim9.get(v,side,cx*16+lx,cz*16+lz,s*16+ly);
                if(pi[b&0xFF]<0) pi[b&0xFF]=np++;
                idx[(ly*16+lz)*16+lx]=(short)pi[b&0xFF];
            }
            int bits=Math.max(4,log2ceil(np));
            int per=64/bits, li=0, sh=0;
            long[] pk=new long[(4096+per-1)/per];
            for(int i=0;i<4096;i++){ pk[li]|=((long)idx[i])<<(sh*bits); if(++sh==per){sh=0;li++;} }
            for(long L:pk) for(int b=0;b<8;b++) l4.write((int)((L>>>(b*8))&0xFF));
        }
        long L4=dlen(l4.toByteArray()); l4=null;

        // L5: column-RLE
        ByteArrayOutputStream l5=new ByteArrayOutputStream(1<<22);
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++)
            for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){
                int gx=cx*16+lx, gz=cz*16+lz;
                byte prev=VoxSim9.get(v,side,gx,gz,0); int run=1;
                for(int yi=1;yi<HEIGHT;yi++){
                    byte cur=VoxSim9.get(v,side,gx,gz,yi);
                    if(cur==prev){run++;continue;}
                    l5.write(prev); wVar(l5,run); prev=cur; run=1;
                }
                l5.write(prev); wVar(l5,run);
            }
        byte[] l5raw=l5.toByteArray(); long L5=dlen(l5raw); l5=null;

        System.out.printf("  L1 vanilla order (YZX, y outer)   : %,12d B%n",L1);
        System.out.printf("  L2 section, Y innermost           : %,12d B   %.2fx vs L1%n",L2,L1/(double)L2);
        System.out.printf("  L3 full column, no section split  : %,12d B   %.2fx vs L1%n",L3,L1/(double)L3);
        System.out.printf("  L4 vanilla + palette + bit-packed : %,12d B   %.2fx vs L1%n",L4,L1/(double)L4);
        System.out.printf("  L5 column-RLE                     : %,12d B   %.2fx vs L1%n",L5,L1/(double)L5);
        System.out.printf("%n  attribution:%n");
        System.out.printf("    axis order alone (L1->L2)       : %.2fx%n",L1/(double)L2);
        System.out.printf("    + no section split (L2->L3)     : %.2fx%n",L2/(double)L3);
        System.out.printf("    + explicit RLE (L3->L5)         : %.2fx%n",L3/(double)L5);
        System.out.printf("    bit-packing cost (L4 vs L1)     : %.2fx%n",L1/(double)L4);

        // ---------------- PART 2: DECODE TIME ----------------
        System.out.println("\n### PART 2 - DECODE TIME (full volume reconstruction) ###");
        long voxels=(long)side*side*HEIGHT;

        // -- real MCA decode via the library --
        File mcaF=new File("mcaout/r.0.0.mca");
        if(mcaF.exists()){
            for(int warm=0;warm<2;warm++){
                long t=System.nanoTime();
                McaRegionFile m=McaFileHelpers.read(mcaF);
                long touched=0;
                for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
                    TerrainChunk c=m.getChunk(cx,cz);
                    if(c==null) continue;
                    for(TerrainSection s:c){
                        var pc=s.getBlockStates();
                        if(pc==null) continue;
                        for(int i=0;i<pc.size();i++){ CompoundTag ct=pc.getByRef(i); if(ct!=null) touched++; }
                    }
                }
                long ms=(System.nanoTime()-t)/1_000_000;
                if(warm==1) System.out.printf("  real MCA (library read + all block states) : %,6d ms   %,d states%n",ms,touched);
            }
        }

        // -- column-RLE decode --
        byte[] crleDef=deflate(l5raw);
        for(int warm=0;warm<2;warm++){
            long t=System.nanoTime();
            byte[] raw=inflate(crleDef, l5raw.length);
            byte[][] out=new byte[side][];
            for(int i=0;i<side;i++) out[i]=new byte[side*HEIGHT];
            int p=0;
            for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++)
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){
                    int gx=cx*16+lx, gz=cz*16+lz, yi=0;
                    while(yi<HEIGHT){
                        byte blk=raw[p++];
                        int run=0,shift=0,b;
                        do{ b=raw[p++]&0xFF; run|=(b&0x7F)<<shift; shift+=7; }while((b&0x80)!=0);
                        int base2=gz*HEIGHT;
                        java.util.Arrays.fill(out[gx],base2+yi,base2+yi+run,blk);
                        yi+=run;
                    }
                }
            long ms=(System.nanoTime()-t)/1_000_000;
            if(warm==1) System.out.printf("  column-RLE (inflate + fill volume)         : %,6d ms   %.0f Mvox/s%n",
                ms, voxels/(ms/1000.0)/1e6);
        }

        // -- feature-extract decode: base column-RLE + replay features --
        ByteArrayOutputStream bs=new ByteArrayOutputStream(1<<22);
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++)
            for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){
                int gx=cx*16+lx, gz=cz*16+lz;
                byte prev=VoxSim9.get(base,side,gx,gz,0); int run=1;
                for(int yi=1;yi<HEIGHT;yi++){
                    byte cur=VoxSim9.get(base,side,gx,gz,yi);
                    if(cur==prev){run++;continue;}
                    bs.write(prev); wVar(bs,run); prev=cur; run=1;
                }
                bs.write(prev); wVar(bs,run);
            }
        byte[] baseRaw=bs.toByteArray();
        byte[] baseDef=deflate(baseRaw);
        for(int warm=0;warm<2;warm++){
            long t=System.nanoTime();
            byte[] raw=inflate(baseDef, baseRaw.length);
            byte[][] out=new byte[side][];
            for(int i=0;i<side;i++) out[i]=new byte[side*HEIGHT];
            int p=0;
            for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++)
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){
                    int gx=cx*16+lx, gz=cz*16+lz, yi=0;
                    while(yi<HEIGHT){
                        byte blk=raw[p++];
                        int run=0,shift=0,b;
                        do{ b=raw[p++]&0xFF; run|=(b&0x7F)<<shift; shift+=7; }while((b&0x80)!=0);
                        int bb=gz*HEIGHT;
                        java.util.Arrays.fill(out[gx],bb+yi,bb+yi+run,blk);
                        yi+=run;
                    }
                }
            long tRle=(System.nanoTime()-t)/1_000_000;
            // replay features
            long t2=System.nanoTime();
            for(VoxSim9.Feat f:log){
                if(f.kind==0){
                    int si=f.tpl/(VoxSim9.SHAPE_FAMILY*34), rem=f.tpl%(VoxSim9.SHAPE_FAMILY*34);
                    VoxSim9.applyBlob(out,side,f.x,f.y,f.z,rem/34,rem%34,(byte)VoxSim9.BLOBS[si][0]);
                } else if(f.kind==1) VoxSim9.applyTree(out,side,f.x,f.y,f.z,f.tpl);
                else VoxSim9.applyBuilding(out,side,f.x,f.y,f.z,f.tpl);
            }
            long tFeat=(System.nanoTime()-t2)/1_000_000;
            if(warm==1){
                System.out.printf("  feature-extract total                      : %,6d ms  (base %,d + replay %,d)%n",
                    tRle+tFeat,tRle,tFeat);
                System.out.printf("       -> %.0f Mvox/s%n", voxels/((tRle+tFeat)/1000.0)/1e6);
            }
        }
        System.out.printf("%n  (volume = %,d voxels, %d chunks)%n",voxels,chunks*chunks);
    }

    static byte[] inflate(byte[] in,int expect) throws Exception {
        Inflater f=new Inflater(); f.setInput(in);
        byte[] out=new byte[expect+64];
        int off=0;
        while(!f.finished() && off<out.length){
            int n=f.inflate(out,off,out.length-off);
            if(n==0) break;
            off+=n;
        }
        f.end(); return out;
    }
}
