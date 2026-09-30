import java.util.*;
import java.util.zip.Deflater;
import java.util.concurrent.*;

// v7: realistic worldgen features (the previous sims were biased AGAINST dedup), plus the
// hybrid schemes that the dedup analysis points at.
//
// WORLDGEN FIXES (sourced from minecraft.wiki ore/tree/jigsaw pages):
//   * Ores as ELLIPSOIDAL BLOBS from a small shape family, real per-chunk attempt counts and
//     size caps (coal 20 attempts/size<=17, iron 10/<=4, copper 16/<=10, diamond 7/<=4, ...).
//     Previously: 1-in-1024 scattered single voxels = maximally dedup-hostile.
//   * TREES from a small template family (birch trunk 5-7 + ~55 leaves no branches; oak small/
//     large; spruce). Real MC has thousands of near-identical trees. Previously: none at all.
//   * VILLAGES as jigsaw-style repeated templates from a fixed pool of building shapes.
//   * Stone variants (andesite/granite/diorite/tuff) as BLOB features, not continuous noise.
//
// NEW SCHEMES:
//   * ColRLE-SurfRel  - column runs encoded RELATIVE TO SURFACE HEIGHT, heightmap sent separately.
//                       Absolute runs make a y=70 column and a y=71 column different byte strings
//                       that never dedupe; surface-aligning makes them identical.
//   * ColDict-SurfRel - dictionary over surface-relative column profiles.
//   * YBandDict       - dictionary over fixed Y-bands (shorter units -> saturate better, per the
//                       "coarse enough to repeat, fine enough to saturate" analysis).

public class VoxSim7 {
    static final byte AIR=0,BEDROCK=1,DEEPSLATE=2,STONE=3,DIRT=4,GRASS=5,WATER=6,
        COAL=7,IRON=8,COPPER=9,DIAMOND=10,GOLD=11,REDSTONE=12,
        SAND=13,GRAVEL=14,ANDESITE=15,GRANITE=16,DIORITE=17,TUFF=18,
        SNOW=19,CLAY=20,LOG=21,LEAVES=22,PLANKS=23,COBBLE=24,GLASS=25;
    static final int Y_MIN=-64,Y_MAX=320,HEIGHT=Y_MAX-Y_MIN,SEA=62;

    static int hash(int x,int z,int s){int h=x*374761393+z*668265263+s*1274126177;h=(h^(h>>>13))*1274126177;return h^(h>>>16);}
    static int hash3(int x,int y,int z,int s){
        int h=x*374761393+y*1103515245+z*668265263+s*1274126177;
        h=(h^(h>>>13))*1274126177; return h^(h>>>16);
    }
    static double noise(int x,int z,int o,int s){
        int p=1<<o;int x0=Math.floorDiv(x,p),z0=Math.floorDiv(z,p);
        double fx=(x-x0*p)/(double)p,fz=(z-z0*p)/(double)p;
        double a=(hash(x0,z0,s)&0xFFFF)/65535.0,b=(hash(x0+1,z0,s)&0xFFFF)/65535.0;
        double c=(hash(x0,z0+1,s)&0xFFFF)/65535.0,d=(hash(x0+1,z0+1,s)&0xFFFF)/65535.0;
        double sx=fx*fx*(3-2*fx),sz=fz*fz*(3-2*fz);
        return (a*(1-sx)+b*sx)*(1-sz)+(c*(1-sx)+d*sx)*sz;
    }
    static int surfaceY(int x,int z){return 60+(int)(noise(x,z,7,1)*40+noise(x,z,5,2)*15+noise(x,z,3,3)*5);}
    static boolean isCave(int x,int y,int z,double den){
        int GS=24;int gx=Math.floorDiv(x,GS),gy=Math.floorDiv(y,GS),gz=Math.floorDiv(z,GS);
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
            int cx=(gx+dx)*GS,cy=(gy+dy)*GS,cz=(gz+dz)*GS;
            if(((hash(cx,cz,cy*7919+11)&0xFFFF)/65535.0)>den)continue;
            int h2=hash(cx,cz,cy*7919+23);
            double ox=cx+((h2&0xFF)-128)/20.0*GS,oy=cy+(((h2>>>8)&0xFF)-128)/20.0*GS,oz=cz+(((h2>>>16)&0xFF)-128)/20.0*GS;
            double r=4+((hash(cx,cz,cy*7919+41)&0xFF)/255.0)*8;
            if((x-ox)*(x-ox)+(y-oy)*(y-oy)*3+(z-oz)*(z-oz)<r*r)return true;
        }
        return false;
    }
    // base terrain only; features applied in a second pass
    static byte baseBlock(int x,int y,int z,double cave){
        if(y<Y_MIN+5)return BEDROCK;
        int sy=surfaceY(x,z);
        if(y>sy)return (y<=SEA)?WATER:AIR;
        if(isCave(x,y,z,cave)&&y<sy-2&&y>Y_MIN+5)return AIR;
        double biome=noise(x,z,9,77);
        if(y==sy){ if(sy<=SEA+1)return biome<0.3?SAND:DIRT;
                   if(biome<0.20)return SAND; if(biome>0.85)return SNOW; return GRASS; }
        if(y>sy-4){ if(biome<0.20)return SAND; if(biome<0.28)return CLAY; return DIRT; }
        if(y<0)return DEEPSLATE;
        return STONE;
    }

    // ---- ore/variant blob table: {block, attemptsPerChunk, maxSize, yMin, yMax} ----
    static final int[][] BLOBS = {
        {COAL,     20, 17,   0, 192},
        {IRON,     10,  4, -64,  72},
        {COPPER,   16, 10, -16, 112},
        {DIAMOND,   7,  4, -64,  16},
        {GOLD,      4,  9, -64,  32},
        {REDSTONE,  8,  8, -64,  15},
        {GRAVEL,    8, 33,   0,  80},
        {ANDESITE, 10, 33,   0,  79},
        {GRANITE,  10, 33,   0,  79},
        {DIORITE,  10, 33,   0,  79},
        {TUFF,      6, 33, -64,   0},
    };
    // Ellipsoidal blob from a SMALL shape family (8 canonical shapes) -- this is what makes
    // real ore veins dedupe, and what the old scattered-single-voxel model destroyed.
    static final int SHAPE_FAMILY = 8;
    static void placeBlobs(byte[][] v,int side,int cx,int cz,double cave){
        for(int[] spec:BLOBS){
            byte block=(byte)spec[0]; int attempts=spec[1],maxSize=spec[2],yLo=spec[3],yHi=spec[4];
            for(int a=0;a<attempts;a++){
                int h=hash3(cx,a,cz,spec[0]*7919);
                int ox=cx*16+((h>>>2)&15), oz=cz*16+((h>>>6)&15);
                int oy=yLo+((h>>>10)&0x7FFF)%Math.max(1,(yHi-yLo));
                int shape=(h>>>26)&(SHAPE_FAMILY-1);
                int size=1+((h>>>20)&63)%maxSize;
                // ellipsoid radii drawn from the shape family (small parameter space -> dedupe)
                double rx=1.0+(shape&1)*0.8+size*0.06;
                double ry=1.0+((shape>>>1)&1)*0.6+size*0.04;
                double rz=1.0+((shape>>>2)&1)*0.8+size*0.06;
                int R=(int)Math.ceil(Math.max(rx,Math.max(ry,rz)));
                for(int dx=-R;dx<=R;dx++)for(int dy=-R;dy<=R;dy++)for(int dz=-R;dz<=R;dz++){
                    double e=(dx*dx)/(rx*rx)+(dy*dy)/(ry*ry)+(dz*dz)/(rz*rz);
                    if(e>1.0) continue;
                    int X=ox+dx, Y=oy+dy, Z=oz+dz;
                    if(X<0||Z<0||X>=side||Z>=side) continue;
                    int yi=Y-Y_MIN; if(yi<5||yi>=HEIGHT) continue;
                    byte cur=v[X][Z*HEIGHT+yi];
                    if(cur==STONE||cur==DEEPSLATE) v[X][Z*HEIGHT+yi]=block;
                }
            }
        }
    }

    // ---- TREE TEMPLATES: a small family, exactly like real MC ----
    // birch: trunk 5-7, ~55 leaves, no branches. oak small/large. spruce broad/tall.
    // Each template is a FIXED voxel pattern -> massive dedup opportunity.
    static final int N_TREE_TEMPLATES = 6;
    static List<int[]>[] TREE = buildTreeTemplates();
    @SuppressWarnings("unchecked")
    static List<int[]>[] buildTreeTemplates(){
        List<int[]>[] t=new List[N_TREE_TEMPLATES];
        int[] trunks={5,6,7,5,6,8};      // birch 5/6/7, oak 5/6, spruce 8
        int[] canopyR={2,2,2,3,3,2};
        for(int k=0;k<N_TREE_TEMPLATES;k++){
            List<int[]> cells=new ArrayList<>();
            int th=trunks[k], cr=canopyR[k];
            for(int y=0;y<th;y++) cells.add(new int[]{0,y,0,LOG});
            // canopy: sphere-ish cap, standard shape per template
            for(int dy=-1;dy<=2;dy++)for(int dx=-cr;dx<=cr;dx++)for(int dz=-cr;dz<=cr;dz++){
                if(dx==0&&dz==0&&dy<=0) continue;
                int d2=dx*dx+dz*dz+dy*dy;
                if(d2<=cr*cr+1) cells.add(new int[]{dx,th+dy,dz,LEAVES});
            }
            t[k]=cells;
        }
        return t;
    }
    static void placeTrees(byte[][] v,int side,int cx,int cz){
        double biome=noise(cx*16,cz*16,9,77);
        if(biome<0.30||biome>0.85) return;               // no trees in desert/snow patches
        int n=2+(hash(cx,cz,555)&7);                      // 2-9 trees per forested chunk
        for(int i=0;i<n;i++){
            int h=hash3(cx,i,cz,4242);
            int bx=cx*16+((h>>>3)&15), bz=cz*16+((h>>>7)&15);
            if(bx<2||bz<2||bx>=side-2||bz>=side-2) continue;
            int sy=surfaceY(bx,bz);
            if(sy<=SEA+1) continue;
            int tpl=((h>>>11)&0xFF)%N_TREE_TEMPLATES;
            for(int[] c:TREE[tpl]){
                int X=bx+c[0], Z=bz+c[2], yi=(sy+1+c[1])-Y_MIN;
                if(X<0||Z<0||X>=side||Z>=side||yi<0||yi>=HEIGHT) continue;
                byte cur=v[X][Z*HEIGHT+yi];
                if(cur==AIR||cur==LEAVES) v[X][Z*HEIGHT+yi]=(byte)c[3];
            }
        }
    }

    // ---- VILLAGE: jigsaw-style. Fixed pool of building templates, placed repeatedly. ----
    static final int N_BUILDINGS=6;
    static void placeVillage(byte[][] v,int side,int vcx,int vcz){
        // 8-16 buildings drawn from a pool of 6 fixed templates
        int nb=8+(hash(vcx,vcz,31337)&7);
        for(int b=0;b<nb;b++){
            int h=hash3(vcx,b,vcz,9182);
            int bx=vcx*16+((h&0x3F)-32)*2, bz=vcz*16+(((h>>>6)&0x3F)-32)*2;
            int tpl=((h>>>13)&0xFF)%N_BUILDINGS;
            int w=5+tpl, d=5+((tpl*3)%4), ht=4+(tpl%3);
            if(bx<1||bz<1||bx+w>=side||bz+d>=side) continue;
            int sy=surfaceY(bx,bz);
            if(sy<=SEA+1) continue;
            for(int dx=0;dx<w;dx++)for(int dz=0;dz<d;dz++)for(int dy=0;dy<ht;dy++){
                int X=bx+dx,Z=bz+dz,yi=(sy+1+dy)-Y_MIN;
                if(X>=side||Z>=side||yi<0||yi>=HEIGHT) continue;
                boolean wall = dx==0||dz==0||dx==w-1||dz==d-1;
                boolean roof = dy==ht-1;
                byte blk;
                if(roof) blk=COBBLE;
                else if(wall) blk=(dy==1&&(dx+dz)%3==0)?GLASS:PLANKS;
                else blk=AIR;
                v[X][Z*HEIGHT+yi]=blk;
            }
        }
    }

    static byte[][] generate(int side,double cave) throws Exception {
        final byte[][] v=new byte[side][];
        for(int i=0;i<side;i++) v[i]=new byte[side*HEIGHT];
        int nt=Runtime.getRuntime().availableProcessors();
        ExecutorService ex=Executors.newFixedThreadPool(nt);
        List<Future<?>> fs=new ArrayList<>();
        int ch=(side+nt-1)/nt;
        for(int t=0;t<nt;t++){
            final int x0=t*ch,x1=Math.min(x0+ch,side);
            fs.add(ex.submit(()->{
                for(int x=x0;x<x1;x++)for(int z=0;z<side;z++)for(int yi=0;yi<HEIGHT;yi++)
                    v[x][z*HEIGHT+yi]=baseBlock(x,yi+Y_MIN,z,cave);
            }));
        }
        for(Future<?> f:fs)f.get(); fs.clear();
        // feature pass (per-chunk, parallel over chunk rows)
        int chunks=side/16;
        int crow=(chunks+nt-1)/nt;
        for(int t=0;t<nt;t++){
            final int c0=t*crow,c1=Math.min(c0+crow,chunks);
            fs.add(ex.submit(()->{
                for(int cx=c0;cx<c1;cx++)for(int cz=0;cz<chunks;cz++){
                    placeBlobs(v,side,cx,cz,cave);
                    placeTrees(v,side,cx,cz);
                }
            }));
        }
        for(Future<?> f:fs)f.get(); ex.shutdown();
        // villages: spacing ~34 chunks (real MC default), sequential to avoid overlap races
        for(int vcx=0;vcx<chunks;vcx+=34)for(int vcz=0;vcz<chunks;vcz+=34){
            if((hash(vcx,vcz,777)&3)==0) continue;   // ~75% of grid cells get one
            placeVillage(v,side,vcx,vcz);
        }
        return v;
    }
    static byte get(byte[][] v,int side,int x,int z,int yi){return v[x][z*HEIGHT+yi];}
    static int nextPow2(int n){int p=1;while(p<n)p<<=1;return p;}
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
    static long deflatedLen(byte[] in){
        Deflater d=new Deflater(6);d.setInput(in);d.finish();
        byte[] o=new byte[1<<16];long tot=0;
        while(!d.finished()){int n=d.deflate(o); if(n>0)tot+=n; else break;}
        d.end();return tot;
    }
    static void wVar(java.io.ByteArrayOutputStream bo,int n){
        while((n&~0x7F)!=0){bo.write((n&0x7F)|0x80);n>>>=7;}bo.write(n);
    }

    // ---- vanilla yardstick ----
    static long encVanilla(byte[][] v,int side){
        long total=0;int chunks=side/16;
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++)
            total+=((5+deflatedLen(chunkPayload(v,side,cx,cz))+4095)/4096)*4096;
        int r=(chunks+31)/32;return total+(long)r*r*8192L;
    }
    static byte[] chunkPayload(byte[][] v,int side,int cx,int cz){
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<17);
        for(int m=0;m<4;m++)for(int i=0;i<296;i++)bo.write(hash(cx,cz,m*31+i)&0xFF);
        bo.write(new byte[200],0,200);
        for(int sy=0;sy<HEIGHT/16;sy++){
            byte[] pal=new byte[32];int np=0;int[] pi=new int[256];Arrays.fill(pi,-1);
            short[] idx=new short[4096];boolean na=false;
            for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)for(int ly=0;ly<16;ly++){
                byte b=get(v,side,cx*16+lx,cz*16+lz,sy*16+ly);
                if(b!=AIR)na=true;
                int p=pi[b&0xFF];
                if(p<0){if(np==pal.length)pal=Arrays.copyOf(pal,pal.length*2);pal[np]=b;p=np++;pi[b&0xFF]=p;}
                idx[((lx*16)+lz)*16+ly]=(short)p;
            }
            if(!na)continue;
            bo.write(new byte[40],0,40);
            for(int i=0;i<np;i++){String nm="minecraft:block_"+(pal[i]&0xFF);for(int k=0;k<nm.length();k++)bo.write(nm.charAt(k));}
            if(np>1){
                int bits=Math.max(4,log2ceil(np));int lg=(4096*bits+63)/64;
                long[] pk=new long[lg];int per=64/bits,li=0,sh=0;
                for(int i=0;i<4096;i++){pk[li]|=((long)idx[i])<<(sh*bits);if(++sh==per){sh=0;li++;}}
                for(long L:pk)for(int b=0;b<8;b++)bo.write((int)((L>>>(b*8))&0xFF));
            }
            bo.write(new byte[50],0,50);
            for(int i=0;i<2048;i++)bo.write(hash(cx*16+sy,cz*16,i)&0xFF);
            for(int i=0;i<2048;i++)bo.write(hash(cz*16+sy,cx*16,i+9973)&0xFF);
        }
        return bo.toByteArray();
    }

    // ---- column run helpers ----
    static void colRunsAbs(java.io.ByteArrayOutputStream bo,byte[][] v,int side,int gx,int gz){
        byte prev=get(v,side,gx,gz,0);int run=1;
        for(int yi=1;yi<HEIGHT;yi++){
            byte cur=get(v,side,gx,gz,yi);
            if(cur==prev){run++;continue;}
            bo.write(prev);wVar(bo,run);prev=cur;run=1;
        }
        bo.write(prev);wVar(bo,run);
    }
    // surface-relative: emit runs from the TOP of the column downward starting at the surface,
    // so columns with different absolute surface heights produce identical byte strings.
    static void colRunsSurfRel(java.io.ByteArrayOutputStream bo,byte[][] v,int side,int gx,int gz,int topYi){
        // walk downward from topYi to 0
        byte prev=get(v,side,gx,gz,topYi);int run=1;
        for(int yi=topYi-1;yi>=0;yi--){
            byte cur=get(v,side,gx,gz,yi);
            if(cur==prev){run++;continue;}
            bo.write(prev);wVar(bo,run);prev=cur;run=1;
        }
        bo.write(prev);wVar(bo,run);
    }
    static int topSolidYi(byte[][] v,int side,int gx,int gz){
        for(int yi=HEIGHT-1;yi>=0;yi--) if(get(v,side,gx,gz,yi)!=AIR) return yi;
        return 0;
    }

    static long encColRLEabs(byte[][] v,int side){
        int chunks=side/16,regions=(chunks+31)/32;
        long total=(long)regions*regions*512L;
        for(int rx=0;rx<regions;rx++)for(int rz=0;rz<regions;rz++){
            java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<21);
            for(int c0=0;c0<32;c0++)for(int c1=0;c1<32;c1++){
                int cx=rx*32+c0,cz=rz*32+c1; if(cx>=chunks||cz>=chunks)continue;
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)
                    colRunsAbs(bo,v,side,cx*16+lx,cz*16+lz);
            }
            total+=deflatedLen(bo.toByteArray());
        }
        return total;
    }
    // surface-relative + separate heightmap stream (heightmap is smooth 2D -> compresses hard)
    static long[] encColRLEsurfRel(byte[][] v,int side){
        int chunks=side/16,regions=(chunks+31)/32;
        long total=(long)regions*regions*512L;
        // heightmap as its own delta-coded stream
        java.io.ByteArrayOutputStream hm=new java.io.ByteArrayOutputStream(side*side/2);
        int prevH=0;
        for(int x=0;x<side;x++)for(int z=0;z<side;z++){
            int t=topSolidYi(v,side,x,z);
            int d=t-prevH; prevH=t;
            wVar(hm,(d<<1)^(d>>31));  // zigzag varint
        }
        long hmBytes=deflatedLen(hm.toByteArray());
        for(int rx=0;rx<regions;rx++)for(int rz=0;rz<regions;rz++){
            java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<21);
            for(int c0=0;c0<32;c0++)for(int c1=0;c1<32;c1++){
                int cx=rx*32+c0,cz=rz*32+c1; if(cx>=chunks||cz>=chunks)continue;
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){
                    int gx=cx*16+lx,gz=cz*16+lz;
                    colRunsSurfRel(bo,v,side,gx,gz,topSolidYi(v,side,gx,gz));
                }
            }
            total+=deflatedLen(bo.toByteArray());
        }
        return new long[]{total+hmBytes,hmBytes};
    }

    // ---- dictionaries ----
    static long[] encColDict(byte[][] v,int side,boolean surfRel){
        HashMap<ByteKey,Integer> dict=new HashMap<>(1<<20);
        java.io.ByteArrayOutputStream db=new java.io.ByteArrayOutputStream(1<<22);
        int[] index=new int[side*side]; int nProf=0;
        java.io.ByteArrayOutputStream cb=new java.io.ByteArrayOutputStream(128);
        java.io.ByteArrayOutputStream hm=new java.io.ByteArrayOutputStream(side*side/2);
        int prevH=0;
        for(int x=0;x<side;x++)for(int z=0;z<side;z++){
            cb.reset();
            if(surfRel){
                int t=topSolidYi(v,side,x,z);
                int d=t-prevH; prevH=t; wVar(hm,(d<<1)^(d>>31));
                colRunsSurfRel(cb,v,side,x,z,t);
            } else colRunsAbs(cb,v,side,x,z);
            byte[] p=cb.toByteArray();
            ByteKey k=new ByteKey(p);
            Integer id=dict.get(k);
            if(id==null){id=nProf++;dict.put(k,id);wVar(db,p.length);db.write(p,0,p.length);}
            index[x*side+z]=id;
        }
        long dictBytes=deflatedLen(db.toByteArray());
        long hmBytes=surfRel?deflatedLen(hm.toByteArray()):0;
        int bits=Math.max(1,log2ceil(nProf));
        BitStream bs=new BitStream();
        for(int i=0;i<index.length;i++)bs.w(index[i],bits);
        long idxBytes=deflatedLen(bs.toByteArray());
        return new long[]{dictBytes+idxBytes+hmBytes+64,nProf,dictBytes,idxBytes,hmBytes};
    }

    // Y-band dictionary: split each column into fixed bands; dedupe bands.
    // Shorter units saturate better than whole columns (the ColDict failure diagnosis).
    static long[] encYBandDict(byte[][] v,int side,int band){
        int nBands=(HEIGHT+band-1)/band;
        HashMap<ByteKey,Integer> dict=new HashMap<>(1<<20);
        java.io.ByteArrayOutputStream db=new java.io.ByteArrayOutputStream(1<<22);
        int[] index=new int[side*side*nBands]; int nProf=0;
        java.io.ByteArrayOutputStream cb=new java.io.ByteArrayOutputStream(64);
        for(int x=0;x<side;x++)for(int z=0;z<side;z++)for(int b=0;b<nBands;b++){
            cb.reset();
            int y0=b*band,y1=Math.min(y0+band,HEIGHT);
            byte prev=get(v,side,x,z,y0);int run=1;
            for(int yi=y0+1;yi<y1;yi++){
                byte cur=get(v,side,x,z,yi);
                if(cur==prev){run++;continue;}
                cb.write(prev);wVar(cb,run);prev=cur;run=1;
            }
            cb.write(prev);wVar(cb,run);
            byte[] p=cb.toByteArray();
            ByteKey k=new ByteKey(p);
            Integer id=dict.get(k);
            if(id==null){id=nProf++;dict.put(k,id);wVar(db,p.length);db.write(p,0,p.length);}
            index[(x*side+z)*nBands+b]=id;
        }
        long dictBytes=deflatedLen(db.toByteArray());
        int bits=Math.max(1,log2ceil(nProf));
        BitStream bs=new BitStream();
        for(int i=0;i<index.length;i++)bs.w(index[i],bits);
        long idxBytes=deflatedLen(bs.toByteArray());
        return new long[]{dictBytes+idxBytes+64,nProf,dictBytes,idxBytes};
    }

    static class ByteKey{
        final byte[] b;final int h;
        ByteKey(byte[] b){this.b=b;this.h=Arrays.hashCode(b);}
        public int hashCode(){return h;}
        public boolean equals(Object o){return o instanceof ByteKey&&Arrays.equals(b,((ByteKey)o).b);}
    }

    static long[] encSVDAG(byte[][] v,int side){
        int dim=nextPow2(Math.max(side,HEIGHT));
        ArrayList<int[]> nodes=new ArrayList<>(1<<20);
        HashMap<Long,Integer> pool=new HashMap<>(1<<21);
        dagBuild(v,side,0,0,0,dim,pool,nodes);
        int bits=log2ceil(nodes.size());
        BitStream bs=new BitStream();
        for(int[] n:nodes){
            int mask=0;for(int i=0;i<8;i++)if(n[i]>=256)mask|=(1<<i);
            bs.w(mask,8);
            for(int i=0;i<8;i++){if(n[i]<256)bs.w(n[i],8);else bs.w(n[i]-256,bits);}
        }
        return new long[]{deflatedLen(bs.toByteArray())+36,nodes.size()};
    }
    static int dagBuild(byte[][] v,int side,int x0,int y0,int z0,int dim,
                        HashMap<Long,Integer> pool,ArrayList<int[]> nodes){
        if(dim==1)return samp(v,side,x0,y0,z0);
        int half=dim>>>1;int[] c=new int[8];int u=-1;boolean uni=true;
        for(int oi=0;oi<8;oi++){
            int ox=(oi&1)*half,oy=((oi>>>1)&1)*half,oz=((oi>>>2)&1)*half;
            c[oi]=dagBuild(v,side,x0+ox,y0+oy,z0+oz,half,pool,nodes);
            if(oi==0)u=c[oi];else if(c[oi]!=u)uni=false;
        }
        if(uni&&u<256)return u;
        long key=0;for(int i=0;i<8;i++)key=key*1099511628211L+c[i]+1;
        Integer ex=pool.get(key);if(ex!=null)return ex+256;
        int id=nodes.size();nodes.add(c);pool.put(key,id);return id+256;
    }
    static int samp(byte[][] v,int side,int x,int y,int z){
        if(x>=side||z>=side||y>=HEIGHT)return AIR&0xFF;
        return get(v,side,x,z,y)&0xFF;
    }
    static class BitStream{
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<22);
        long buf=0;int nb=0;
        void w(int v,int n){if(n<=0)return;
            buf|=((long)(v&(int)((1L<<n)-1)))<<nb;nb+=n;
            while(nb>=8){bo.write((int)(buf&0xFF));buf>>>=8;nb-=8;}}
        byte[] toByteArray(){if(nb>0)bo.write((int)(buf&0xFF));return bo.toByteArray();}
    }

    static LinkedHashMap<String,TreeMap<Integer,Long>> res=new LinkedHashMap<>();
    static void rec(String k,int s,long b){res.computeIfAbsent(k,x->new TreeMap<>()).put(s,b);}

    static void run(int side) throws Exception {
        System.out.printf("%n=== side=%d  REALISTIC FEATURES (ore blobs + trees + villages) ===%n",side);
        long t=System.currentTimeMillis();
        byte[][] v=generate(side,0.15);
        System.out.printf("  [gen %ds]%n",(System.currentTimeMillis()-t)/1000);
        // block census
        long[] cnt=new long[256];
        for(int x=0;x<side;x++)for(int i=0;i<side*HEIGHT;i++)cnt[v[x][i]&0xFF]++;
        long tot=(long)side*side*HEIGHT;
        System.out.printf("  census: air %.1f%% stone %.1f%% deepslate %.1f%% log %.3f%% leaves %.3f%% planks %.4f%%%n",
            100.0*cnt[AIR]/tot,100.0*cnt[STONE]/tot,100.0*cnt[DEEPSLATE]/tot,
            100.0*cnt[LOG]/tot,100.0*cnt[LEAVES]/tot,100.0*cnt[PLANKS]/tot);

        long van=encVanilla(v,side); rec("vanilla",side,van);
        System.out.printf("  %-26s %13d%n","vanilla",van);

        long a=encColRLEabs(v,side); rec("colrle_abs",side,a);
        System.out.printf("  %-26s %13d  %6.1fx%n","Column-RLE (absolute)",a,van/(double)a);

        long[] sr=encColRLEsurfRel(v,side); rec("colrle_surfrel",side,sr[0]);
        System.out.printf("  %-26s %13d  %6.1fx   (heightmap %d B)%n","Column-RLE (surf-rel)",sr[0],van/(double)sr[0],sr[1]);

        long[] cda=encColDict(v,side,false); rec("coldict_abs",side,cda[0]);
        System.out.printf("  %-26s %13d  %6.1fx   %d profiles%n","ColDict (absolute)",cda[0],van/(double)cda[0],cda[1]);

        long[] cds=encColDict(v,side,true); rec("coldict_surfrel",side,cds[0]);
        System.out.printf("  %-26s %13d  %6.1fx   %d profiles (dict %d, idx %d, hm %d)%n",
            "ColDict (surf-rel)",cds[0],van/(double)cds[0],cds[1],cds[2],cds[3],cds[4]);

        for(int band:new int[]{32,64}){
            long[] yb=encYBandDict(v,side,band); rec("yband"+band,side,yb[0]);
            System.out.printf("  %-26s %13d  %6.1fx   %d profiles%n",
                "YBandDict band="+band,yb[0],van/(double)yb[0],yb[1]);
        }

        long[] sv=encSVDAG(v,side); rec("svdag",side,sv[0]);
        System.out.printf("  %-26s %13d  %6.1fx   %d nodes%n","SVDAG",sv[0],van/(double)sv[0],sv[1]);
    }

    public static void main(String[] a) throws Exception {
        int[] sides = a.length>0 ? new int[]{Integer.parseInt(a[0])} : new int[]{512,1024,2048};
        for(int s:sides) run(s);
        System.out.println("\n##### exponents (size ~ area^b) #####");
        for(Map.Entry<String,TreeMap<Integer,Long>> e:res.entrySet()){
            System.out.printf("%-18s",e.getKey());
            Integer[] ks=e.getValue().keySet().toArray(new Integer[0]);
            for(int i=0;i+1<ks.length;i++){
                double g=e.getValue().get(ks[i+1])/(double)e.getValue().get(ks[i]);
                System.out.printf("  %d->%d b=%.3f",ks[i],ks[i+1],Math.log(g)/Math.log(4.0));
            }
            System.out.println();
        }
    }
}
