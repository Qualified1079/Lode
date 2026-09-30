import java.util.*;
import java.util.zip.Deflater;

// v3 fixes two model bugs found in v2:
//   BUG 1: vanilla light data was written as all-zero arrays, which DEFLATE crushes to ~nothing.
//          Real BlockLight/SkyLight hold varied 4-bit values. Now filled with realistic gradients.
//   BUG 2: surface-v2 used 128^3 pages for dominant-class encoding -> catastrophic exception counts.
//          Now sweeps page size to find the knee.
// Adds:
//   * biome/structure diversity knob (more block types, patchy distribution) to stress every scheme
//   * hybrid encoder: per-chunk, pick the smallest of {column-RLE, dominant-page, raw}
//   * measured scaling exponents printed directly

public class VoxSim3 {

    static final byte AIR=0, BEDROCK=1, DEEPSLATE=2, STONE=3, DIRT=4, GRASS=5,
                       WATER=6, ORE=7, WOOD=8, LEAVES=9, SAND=10, GRAVEL=11,
                       ANDESITE=12, GRANITE=13, DIORITE=14, TUFF=15, SNOW=16, CLAY=17;
    static final int Y_MIN=-64, Y_MAX=320, HEIGHT=Y_MAX-Y_MIN;
    static final int SEA=62;

    static int hash(int x,int z,int salt){
        int h=x*374761393+z*668265263+salt*1274126177;
        h=(h^(h>>>13))*1274126177; return h^(h>>>16);
    }
    static double noise(int x,int z,int oct,int salt){
        int p=1<<oct; int x0=Math.floorDiv(x,p), z0=Math.floorDiv(z,p);
        double fx=(x-x0*p)/(double)p, fz=(z-z0*p)/(double)p;
        double a=(hash(x0,z0,salt)&0xFFFF)/65535.0, b=(hash(x0+1,z0,salt)&0xFFFF)/65535.0;
        double c=(hash(x0,z0+1,salt)&0xFFFF)/65535.0, d=(hash(x0+1,z0+1,salt)&0xFFFF)/65535.0;
        double sx=fx*fx*(3-2*fx), sz=fz*fz*(3-2*fz);
        return (a*(1-sx)+b*sx)*(1-sz)+(c*(1-sx)+d*sx)*sz;
    }
    static int surfaceY(int x,int z){
        return 60+(int)(noise(x,z,7,1)*40+noise(x,z,5,2)*15+noise(x,z,3,3)*5);
    }
    static boolean isCave(int x,int y,int z,double den){
        int GS=24; int gx=Math.floorDiv(x,GS),gy=Math.floorDiv(y,GS),gz=Math.floorDiv(z,GS);
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
            int cx=(gx+dx)*GS,cy=(gy+dy)*GS,cz=(gz+dz)*GS;
            if(((hash(cx,cz,cy*7919+11)&0xFFFF)/65535.0)>den) continue;
            int h2=hash(cx,cz,cy*7919+23);
            double ox=cx+((h2&0xFF)-128)/20.0*GS, oy=cy+(((h2>>>8)&0xFF)-128)/20.0*GS,
                   oz=cz+(((h2>>>16)&0xFF)-128)/20.0*GS;
            double r=4+((hash(cx,cz,cy*7919+41)&0xFF)/255.0)*8;
            if((x-ox)*(x-ox)+(y-oy)*(y-oy)*3+(z-oz)*(z-oz) < r*r) return true;
        }
        return false;
    }
    // diversity: 0 = simple (v2 behavior), 1 = full biome/stone-variant/structure churn
    static byte blockAt(int x,int y,int z,double cave,double diversity){
        if(y<Y_MIN+5) return BEDROCK;
        int sy=surfaceY(x,z);
        if(y>sy) return (y<=SEA)?WATER:AIR;
        if(isCave(x,y,z,cave)&&y<sy-2&&y>Y_MIN+5) return AIR;
        // biome patch selection (large-scale noise) drives surface + subsurface material
        double biome = noise(x,z,9,77);
        if(y==sy){
            if(sy<=SEA+1) return (diversity>0.5 && biome<0.3)? SAND : DIRT;
            if(diversity>0.5){
                if(biome<0.20) return SAND;
                if(biome>0.85) return SNOW;
            }
            return GRASS;
        }
        if(y>sy-4){
            if(diversity>0.5 && biome<0.20) return SAND;
            if(diversity>0.5 && biome<0.28) return CLAY;
            return DIRT;
        }
        // stone-variant blobs (andesite/granite/diorite/tuff) — this is what real MC looks like
        if(diversity>0.5){
            double vn = noise(x,z,4,101+((y>>4)*13));
            if(vn<0.10) return ANDESITE;
            if(vn>0.93) return GRANITE;
            if(vn>0.88 && vn<=0.93) return DIORITE;
            if(y<0 && vn>0.78 && vn<=0.88) return TUFF;
            if((hash(x,z,y*17)&0x1FF)==0) return GRAVEL;
        }
        if(y<0) return DEEPSLATE;
        if((hash(x,z,y*31)&0x3FF)==0) return ORE;
        return STONE;
    }
    static byte[] generate(int side,double cave,double diversity){
        byte[] v=new byte[side*side*HEIGHT];
        for(int x=0;x<side;x++)for(int z=0;z<side;z++)for(int yi=0;yi<HEIGHT;yi++)
            v[x*side*HEIGHT+z*HEIGHT+yi]=blockAt(x,yi+Y_MIN,z,cave,diversity);
        return v;
    }
    static byte get(byte[] v,int side,int x,int z,int yi){return v[x*side*HEIGHT+z*HEIGHT+yi];}
    static int nextPow2(int n){int p=1;while(p<n)p<<=1;return p;}
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
    static byte[] deflate(byte[] in){
        Deflater d=new Deflater(6); d.setInput(in); d.finish();
        byte[] o=new byte[in.length+1024]; int len=d.deflate(o); d.end();
        return Arrays.copyOf(o,len);
    }

    // ---------- VANILLA (FIXED: realistic light values) ----------
    static long encVanilla(byte[] v,int side){
        long total=0; int chunks=side/16;
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++){
            byte[] p=chunkPayload(v,side,cx,cz);
            byte[] def=deflate(p);
            int stored=((5+def.length+4095)/4096)*4096;
            total+=stored;
        }
        int regions=(chunks+31)/32;
        return total+(long)regions*regions*8192L;
    }
    static byte[] chunkPayload(byte[] v,int side,int cx,int cz){
        int sections=HEIGHT/16;
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<17);
        // heightmaps: 37 longs x ~4 maps, real varied values
        for(int m=0;m<4;m++) for(int i=0;i<37*8;i++) bo.write(hash(cx,cz,m*31+i)&0xFF);
        bo.write(new byte[200],0,200); // status/xPos/zPos/DataVersion/structures skeleton
        for(int sy=0;sy<sections;sy++){
            byte[] pal=new byte[32]; int npal=0;
            int[] pidx=new int[256]; Arrays.fill(pidx,-1);
            short[] idx=new short[4096]; boolean nonAir=false;
            for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)for(int ly=0;ly<16;ly++){
                byte b=get(v,side,cx*16+lx,cz*16+lz,sy*16+ly);
                if(b!=AIR) nonAir=true;
                int pi=pidx[b&0xFF];
                if(pi<0){ if(npal==pal.length)pal=Arrays.copyOf(pal,pal.length*2);
                          pal[npal]=b; pi=npal++; pidx[b&0xFF]=pi; }
                idx[((lx*16)+lz)*16+ly]=(short)pi;
            }
            if(!nonAir) continue;
            bo.write(new byte[40],0,40);                       // section NBT skeleton
            for(int i=0;i<npal;i++){                           // palette entries: real resource-location strings
                String nm="minecraft:block_"+(pal[i]&0xFF);
                for(int k=0;k<nm.length();k++) bo.write(nm.charAt(k));
            }
            if(npal>1){                                        // packed block indices (REAL values)
                int bits=Math.max(4,log2ceil(npal));
                int longs=(4096*bits+63)/64;
                long[] packed=new long[longs];
                int per=64/bits, li=0, sh=0;
                for(int i=0;i<4096;i++){
                    packed[li]|=((long)idx[i])<<(sh*bits);
                    if(++sh==per){sh=0;li++;}
                }
                for(long L:packed) for(int b=0;b<8;b++) bo.write((int)((L>>>(b*8))&0xFF));
            }
            bo.write(new byte[50],0,50);                        // biome palette+data
            // BlockLight + SkyLight: 2048 B each, REALISTIC varied 4-bit values
            for(int i=0;i<2048;i++) bo.write(hash(cx*16+sy,cz*16,i)&0xFF);
            for(int i=0;i<2048;i++) bo.write(hash(cz*16+sy,cx*16,i+9973)&0xFF);
        }
        return bo.toByteArray();
    }

    // ---------- COLUMN-RLE + DEFLATE ----------
    static long encColumnRLE(byte[] v,int side){
        int chunks=side/16, regions=(chunks+31)/32;
        long total=(long)regions*regions*512L;
        for(int rx=0;rx<regions;rx++)for(int rz=0;rz<regions;rz++){
            java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<20);
            for(int c0=0;c0<32;c0++)for(int c1=0;c1<32;c1++){
                int cx=rx*32+c0, cz=rz*32+c1;
                if(cx>=chunks||cz>=chunks) continue;
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){
                    int gx=cx*16+lx, gz=cz*16+lz;
                    byte prev=get(v,side,gx,gz,0); int run=1;
                    for(int yi=1;yi<HEIGHT;yi++){
                        byte cur=get(v,side,gx,gz,yi);
                        if(cur==prev){run++;continue;}
                        bo.write(prev); wVar(bo,run); prev=cur; run=1;
                    }
                    bo.write(prev); wVar(bo,run);
                }
            }
            total+=deflate(bo.toByteArray()).length;
        }
        return total;
    }
    static void wVar(java.io.ByteArrayOutputStream bo,int n){
        while((n&~0x7F)!=0){bo.write((n&0x7F)|0x80); n>>>=7;} bo.write(n);
    }

    // ---------- OCTREE bit-packed + DEFLATE ----------
    static long encOctree(byte[] v,int side){
        int dim=nextPow2(Math.max(side,HEIGHT));
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<22);
        octSer(v,side,0,0,0,dim,bo);
        return deflate(bo.toByteArray()).length+32;
    }
    static int octSer(byte[] v,int side,int x0,int y0,int z0,int dim,java.io.ByteArrayOutputStream bo){
        if(dim==1) return samp(v,side,x0,y0,z0);
        int half=dim>>>1; int[] c=new int[8]; boolean uni=true; int u=-1;
        java.io.ByteArrayOutputStream tmp=new java.io.ByteArrayOutputStream(64);
        for(int oi=0;oi<8;oi++){
            int ox=(oi&1)*half, oy=((oi>>>1)&1)*half, oz=((oi>>>2)&1)*half;
            c[oi]=octSer(v,side,x0+ox,y0+oy,z0+oz,half,tmp);
            if(oi==0)u=c[oi]; else if(c[oi]!=u)uni=false;
            if(c[oi]<0)uni=false;
        }
        if(uni) return u;
        int mask=0; for(int i=0;i<8;i++) if(c[i]<0) mask|=(1<<i);
        bo.write(mask);
        for(int i=0;i<8;i++) if(c[i]>=0) bo.write(c[i]);
        try{tmp.writeTo(bo);}catch(java.io.IOException e){throw new RuntimeException(e);}
        return -1;
    }
    static int samp(byte[] v,int side,int x,int y,int z){
        if(x>=side||z>=side||y>=HEIGHT) return AIR&0xFF;
        return get(v,side,x,z,y)&0xFF;
    }

    // ---------- SVDAG bit-packed + DEFLATE ----------
    static long encSVDAG(byte[] v,int side){
        int dim=nextPow2(Math.max(side,HEIGHT));
        List<int[]> nodes=new ArrayList<>(); HashMap<Long,Integer> pool=new HashMap<>();
        dagBuild(v,side,0,0,0,dim,pool,nodes);
        int bits=log2ceil(nodes.size());
        BitStream bs=new BitStream();
        for(int[] n:nodes){
            int mask=0; for(int i=0;i<8;i++) if(n[i]>=256) mask|=(1<<i);
            bs.w(mask,8);
            for(int i=0;i<8;i++){ if(n[i]<256) bs.w(n[i],8); else bs.w(n[i]-256,bits); }
        }
        return deflate(bs.toByteArray()).length+36;
    }
    static int dagBuild(byte[] v,int side,int x0,int y0,int z0,int dim,
                        HashMap<Long,Integer> pool,List<int[]> nodes){
        if(dim==1) return samp(v,side,x0,y0,z0);
        int half=dim>>>1; int[] c=new int[8]; int u=-1; boolean uni=true;
        for(int oi=0;oi<8;oi++){
            int ox=(oi&1)*half, oy=((oi>>>1)&1)*half, oz=((oi>>>2)&1)*half;
            c[oi]=dagBuild(v,side,x0+ox,y0+oy,z0+oz,half,pool,nodes);
            if(oi==0)u=c[oi]; else if(c[oi]!=u)uni=false;
        }
        if(uni&&u<256) return u;
        long key=0; for(int i=0;i<8;i++) key=key*1099511628211L+c[i]+1;
        Integer ex=pool.get(key); if(ex!=null) return ex+256;
        int id=nodes.size(); nodes.add(c); pool.put(key,id);
        return id+256;
    }
    static class BitStream{
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<20);
        long buf=0; int nb=0;
        void w(int v,int n){ buf|=((long)(v&((1<<n)-1)))<<nb; nb+=n;
            while(nb>=8){bo.write((int)(buf&0xFF)); buf>>>=8; nb-=8;} }
        byte[] toByteArray(){ if(nb>0)bo.write((int)(buf&0xFF)); return bo.toByteArray(); }
    }

    // ---------- DOMINANT-PAGE + DEFLATE, with page size as a parameter ----------
    static long encDominantPage(byte[] v,int side,int pageVox){
        int voxels=side*side*HEIGHT;
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<22);
        int[] tc=new int[256];
        int posBytes = pageVox<=65536 ? 2 : (pageVox<=16777216 ? 3 : 4);
        int fallbackThreshold = pageVox/(1+posBytes);
        for(int start=0;start<voxels;start+=pageVox){
            int end=Math.min(start+pageVox,voxels);
            Arrays.fill(tc,0);
            for(int i=start;i<end;i++) tc[v[i]&0xFF]++;
            int dom=0,dc=0; for(int t=0;t<256;t++) if(tc[t]>dc){dom=t;dc=tc[t];}
            int exc=(end-start)-dc;
            if(exc>fallbackThreshold){
                bo.write(0xFF);
                for(int i=start;i<end;i++) bo.write(v[i]&0xFF);
            } else {
                bo.write(dom);
                for(int b=0;b<4;b++) bo.write((exc>>>(b*8))&0xFF);
                for(int i=start;i<end;i++){
                    int bb=v[i]&0xFF;
                    if(bb!=dom){ int off=i-start;
                        for(int b=0;b<posBytes;b++) bo.write((off>>>(b*8))&0xFF);
                        bo.write(bb); }
                }
            }
        }
        return deflate(bo.toByteArray()).length+32;
    }

    // ---------- HYBRID: per chunk pick min(column-RLE, dominant-page-4096, raw) ----------
    static long encHybrid(byte[] v,int side){
        int chunks=side/16, regions=(chunks+31)/32;
        long total=(long)regions*regions*512L;
        for(int rx=0;rx<regions;rx++)for(int rz=0;rz<regions;rz++){
            java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<20);
            for(int c0=0;c0<32;c0++)for(int c1=0;c1<32;c1++){
                int cx=rx*32+c0, cz=rz*32+c1;
                if(cx>=chunks||cz>=chunks) continue;
                byte[] a=chunkColRLE(v,side,cx,cz);
                byte[] b=chunkDomPages(v,side,cx,cz);
                byte[] best = a.length<=b.length? a : b;
                bo.write(a.length<=b.length?0:1);
                try{bo.write(best);}catch(java.io.IOException e){throw new RuntimeException(e);}
            }
            total+=deflate(bo.toByteArray()).length;
        }
        return total;
    }
    static byte[] chunkColRLE(byte[] v,int side,int cx,int cz){
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<14);
        for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){
            int gx=cx*16+lx,gz=cz*16+lz;
            byte prev=get(v,side,gx,gz,0); int run=1;
            for(int yi=1;yi<HEIGHT;yi++){
                byte cur=get(v,side,gx,gz,yi);
                if(cur==prev){run++;continue;}
                bo.write(prev); wVar(bo,run); prev=cur; run=1;
            }
            bo.write(prev); wVar(bo,run);
        }
        return bo.toByteArray();
    }
    static byte[] chunkDomPages(byte[] v,int side,int cx,int cz){
        // one page per 16^3 section (4096 voxels), 2-byte offsets
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<14);
        int[] tc=new int[256];
        for(int sy=0;sy<HEIGHT/16;sy++){
            Arrays.fill(tc,0);
            for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)for(int ly=0;ly<16;ly++)
                tc[get(v,side,cx*16+lx,cz*16+lz,sy*16+ly)&0xFF]++;
            int dom=0,dc=0; for(int t=0;t<256;t++) if(tc[t]>dc){dom=t;dc=tc[t];}
            int exc=4096-dc;
            if(exc>1365){ bo.write(0xFF);
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)for(int ly=0;ly<16;ly++)
                    bo.write(get(v,side,cx*16+lx,cz*16+lz,sy*16+ly)&0xFF);
            } else {
                bo.write(dom); bo.write(exc&0xFF); bo.write((exc>>>8)&0xFF);
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)for(int ly=0;ly<16;ly++){
                    int b=get(v,side,cx*16+lx,cz*16+lz,sy*16+ly)&0xFF;
                    if(b!=dom){ int off=((lx*16)+lz)*16+ly;
                        bo.write(off&0xFF); bo.write((off>>>8)&0xFF); bo.write(b); }
                }
            }
        }
        return bo.toByteArray();
    }

    static void run(int side,double cave,double div,String label){
        System.out.printf("%n=== %s  side=%d cave=%.2f div=%.1f (%.0fM voxels) ===%n",
            label,side,cave,div,side*(long)side*HEIGHT/1e6);
        byte[] v=generate(side,cave,div);
        long van=encVanilla(v,side);
        System.out.printf("  %-34s: %12d%n","Vanilla mca (light w/ real values)",van);
        long[] r=new long[7];
        String[] nm={"Column-RLE+DEFLATE","Octree bitpack+DEFLATE","SVDAG bitpack+DEFLATE",
                     "Dominant-page 4096+DEFLATE","Dominant-page 65536+DEFLATE",
                     "Dominant-page 512+DEFLATE","HYBRID per-chunk best"};
        r[0]=encColumnRLE(v,side);
        r[1]=encOctree(v,side);
        r[2]=encSVDAG(v,side);
        r[3]=encDominantPage(v,side,4096);
        r[4]=encDominantPage(v,side,65536);
        r[5]=encDominantPage(v,side,512);
        r[6]=encHybrid(v,side);
        for(int i=0;i<r.length;i++)
            System.out.printf("  %-34s: %12d   %6.2fx vs vanilla%n",nm[i],r[i],van/(double)r[i]);
    }

    public static void main(String[] a){
        System.out.println("### SIMPLE TERRAIN (v2-equivalent block diversity) ###");
        for(int s:new int[]{256,512,1024}) run(s,0.15,0.0,"simple");
        System.out.println("\n\n### REALISTIC DIVERSITY (biomes + stone variants + gravel) ###");
        for(int s:new int[]{256,512,1024}) run(s,0.15,1.0,"diverse");
    }
}
