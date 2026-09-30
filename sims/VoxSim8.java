import java.util.*;
import java.util.zip.Deflater;
import java.util.concurrent.*;

// v8: FEATURE EXTRACTION.
//
// Diagnosis from v7: realistic ore/variant blobs cost ~4x in every scheme, because each blob is a
// small island that shatters long runs (Column-RLE) and creates unique subtrees (SVDAG). Meanwhile
// the features that DO dedupe well (trees, village buildings -- template-based) are volumetrically
// negligible (logs 0.025%, leaves 0.25%, planks ~0%).
//
// Key realization: the volumetrically significant features repeat in SHAPE but not in POSITION,
// and octree/column dedup is position-sensitive. So dedup the SHAPE and store the POSITION
// separately:
//     encoded = column-RLE(terrain WITHOUT features) + compact list of (template, position)
// Reconstruction replays the placements. This is the robust, version-independent cousin of
// "regenerate from the worldgen seed" -- it stores placement parameters, not resulting voxels.

public class VoxSim8 {
    static final byte AIR=0,BEDROCK=1,DEEPSLATE=2,STONE=3,DIRT=4,GRASS=5,WATER=6,
        COAL=7,IRON=8,COPPER=9,DIAMOND=10,GOLD=11,REDSTONE=12,
        SAND=13,GRAVEL=14,ANDESITE=15,GRANITE=16,DIORITE=17,TUFF=18,
        SNOW=19,CLAY=20,LOG=21,LEAVES=22,PLANKS=23,COBBLE=24,GLASS=25;
    static final int Y_MIN=-64,Y_MAX=320,HEIGHT=Y_MAX-Y_MIN,SEA=62;

    static int hash(int x,int z,int s){int h=x*374761393+z*668265263+s*1274126177;h=(h^(h>>>13))*1274126177;return h^(h>>>16);}
    static int hash3(int x,int y,int z,int s){
        int h=x*374761393+y*1103515245+z*668265263+s*1274126177;
        h=(h^(h>>>13))*1274126177;return h^(h>>>16);
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
    static byte baseBlock(int x,int y,int z,double cave){
        if(y<Y_MIN+5)return BEDROCK;
        int sy=surfaceY(x,z);
        if(y>sy)return (y<=SEA)?WATER:AIR;
        if(isCave(x,y,z,cave)&&y<sy-2&&y>Y_MIN+5)return AIR;
        double biome=noise(x,z,9,77);
        if(y==sy){if(sy<=SEA+1)return biome<0.3?SAND:DIRT;
                  if(biome<0.20)return SAND;if(biome>0.85)return SNOW;return GRASS;}
        if(y>sy-4){if(biome<0.20)return SAND;if(biome<0.28)return CLAY;return DIRT;}
        if(y<0)return DEEPSLATE;
        return STONE;
    }

    static final int[][] BLOBS={
        {COAL,20,17,0,192},{IRON,10,4,-64,72},{COPPER,16,10,-16,112},{DIAMOND,7,4,-64,16},
        {GOLD,4,9,-64,32},{REDSTONE,8,8,-64,15},{GRAVEL,8,33,0,80},
        {ANDESITE,10,33,0,79},{GRANITE,10,33,0,79},{DIORITE,10,33,0,79},{TUFF,6,33,-64,0},
    };
    static final int SHAPE_FAMILY=8;

    // feature log entry: kind(0=blob,1=tree,2=building), template, x, y, z
    static class Feat{ int kind,tpl,x,y,z; Feat(int k,int t,int X,int Y,int Z){kind=k;tpl=t;x=X;y=Y;z=Z;} }

    static void placeBlobs(byte[][] v,int side,int cx,int cz,List<Feat> log){
        for(int si=0;si<BLOBS.length;si++){
            int[] spec=BLOBS[si];
            byte block=(byte)spec[0];int attempts=spec[1],maxSize=spec[2],yLo=spec[3],yHi=spec[4];
            for(int a=0;a<attempts;a++){
                int h=hash3(cx,a,cz,spec[0]*7919);
                int ox=cx*16+((h>>>2)&15),oz=cz*16+((h>>>6)&15);
                int oy=yLo+((h>>>10)&0x7FFF)%Math.max(1,(yHi-yLo));
                int shape=(h>>>26)&(SHAPE_FAMILY-1);
                int size=1+((h>>>20)&63)%maxSize;
                // template id packs (blobType, shape, sizeBucket) -> a small dictionary
                int sizeBucket=Math.min(7,size/5);
                int tpl=(si*SHAPE_FAMILY+shape)*8+sizeBucket;
                if(log!=null) log.add(new Feat(0,tpl,ox,oy,oz));
                applyBlob(v,side,ox,oy,oz,shape,size,block);
            }
        }
    }
    static void applyBlob(byte[][] v,int side,int ox,int oy,int oz,int shape,int size,byte block){
        double rx=1.0+(shape&1)*0.8+size*0.06;
        double ry=1.0+((shape>>>1)&1)*0.6+size*0.04;
        double rz=1.0+((shape>>>2)&1)*0.8+size*0.06;
        int R=(int)Math.ceil(Math.max(rx,Math.max(ry,rz)));
        for(int dx=-R;dx<=R;dx++)for(int dy=-R;dy<=R;dy++)for(int dz=-R;dz<=R;dz++){
            if((dx*dx)/(rx*rx)+(dy*dy)/(ry*ry)+(dz*dz)/(rz*rz)>1.0)continue;
            int X=ox+dx,Y=oy+dy,Z=oz+dz;
            if(X<0||Z<0||X>=side||Z>=side)continue;
            int yi=Y-Y_MIN;if(yi<5||yi>=HEIGHT)continue;
            byte cur=v[X][Z*HEIGHT+yi];
            if(cur==STONE||cur==DEEPSLATE)v[X][Z*HEIGHT+yi]=block;
        }
    }

    static final int N_TREE=6;
    static List<int[]>[] TREE=buildTrees();
    @SuppressWarnings("unchecked")
    static List<int[]>[] buildTrees(){
        List<int[]>[] t=new List[N_TREE];
        int[] tr={5,6,7,5,6,8},cR={2,2,2,3,3,2};
        for(int k=0;k<N_TREE;k++){
            List<int[]> c=new ArrayList<>();
            int th=tr[k],cr=cR[k];
            for(int y=0;y<th;y++)c.add(new int[]{0,y,0,LOG});
            for(int dy=-1;dy<=2;dy++)for(int dx=-cr;dx<=cr;dx++)for(int dz=-cr;dz<=cr;dz++){
                if(dx==0&&dz==0&&dy<=0)continue;
                if(dx*dx+dz*dz+dy*dy<=cr*cr+1)c.add(new int[]{dx,th+dy,dz,LEAVES});
            }
            t[k]=c;
        }
        return t;
    }
    static void placeTrees(byte[][] v,int side,int cx,int cz,List<Feat> log){
        double biome=noise(cx*16,cz*16,9,77);
        if(biome<0.30||biome>0.85)return;
        int n=2+(hash(cx,cz,555)&7);
        for(int i=0;i<n;i++){
            int h=hash3(cx,i,cz,4242);
            int bx=cx*16+((h>>>3)&15),bz=cz*16+((h>>>7)&15);
            if(bx<2||bz<2||bx>=side-2||bz>=side-2)continue;
            int sy=surfaceY(bx,bz);
            if(sy<=SEA+1)continue;
            int tpl=((h>>>11)&0xFF)%N_TREE;
            if(log!=null) log.add(new Feat(1,tpl,bx,sy,bz));
            applyTree(v,side,bx,sy,bz,tpl);
        }
    }
    static void applyTree(byte[][] v,int side,int bx,int sy,int bz,int tpl){
        for(int[] c:TREE[tpl]){
            int X=bx+c[0],Z=bz+c[2],yi=(sy+1+c[1])-Y_MIN;
            if(X<0||Z<0||X>=side||Z>=side||yi<0||yi>=HEIGHT)continue;
            byte cur=v[X][Z*HEIGHT+yi];
            if(cur==AIR||cur==LEAVES)v[X][Z*HEIGHT+yi]=(byte)c[3];
        }
    }

    static final int N_BUILD=6;
    static void placeVillage(byte[][] v,int side,int vcx,int vcz,List<Feat> log){
        int nb=8+(hash(vcx,vcz,31337)&7);
        for(int b=0;b<nb;b++){
            int h=hash3(vcx,b,vcz,9182);
            int bx=vcx*16+((h&0x3F)-32)*2,bz=vcz*16+(((h>>>6)&0x3F)-32)*2;
            int tpl=((h>>>13)&0xFF)%N_BUILD;
            int w=5+tpl,d=5+((tpl*3)%4),ht=4+(tpl%3);
            if(bx<1||bz<1||bx+w>=side||bz+d>=side)continue;
            int sy=surfaceY(bx,bz);
            if(sy<=SEA+1)continue;
            if(log!=null) log.add(new Feat(2,tpl,bx,sy,bz));
            applyBuilding(v,side,bx,sy,bz,tpl);
        }
    }
    static void applyBuilding(byte[][] v,int side,int bx,int sy,int bz,int tpl){
        int w=5+tpl,d=5+((tpl*3)%4),ht=4+(tpl%3);
        for(int dx=0;dx<w;dx++)for(int dz=0;dz<d;dz++)for(int dy=0;dy<ht;dy++){
            int X=bx+dx,Z=bz+dz,yi=(sy+1+dy)-Y_MIN;
            if(X>=side||Z>=side||yi<0||yi>=HEIGHT)continue;
            boolean wall=dx==0||dz==0||dx==w-1||dz==d-1;
            byte blk=(dy==ht-1)?COBBLE:(wall?((dy==1&&(dx+dz)%3==0)?GLASS:PLANKS):AIR);
            v[X][Z*HEIGHT+yi]=blk;
        }
    }

    // returns {baseVolume, finalVolume} and fills the feature log
    static byte[][][] generate(int side,double cave,List<Feat> log) throws Exception {
        final byte[][] base=new byte[side][];
        for(int i=0;i<side;i++)base[i]=new byte[side*HEIGHT];
        int nt=Runtime.getRuntime().availableProcessors();
        ExecutorService ex=Executors.newFixedThreadPool(nt);
        List<Future<?>> fs=new ArrayList<>();
        int ch=(side+nt-1)/nt;
        for(int t=0;t<nt;t++){
            final int x0=t*ch,x1=Math.min(x0+ch,side);
            fs.add(ex.submit(()->{
                for(int x=x0;x<x1;x++)for(int z=0;z<side;z++)for(int yi=0;yi<HEIGHT;yi++)
                    base[x][z*HEIGHT+yi]=baseBlock(x,yi+Y_MIN,z,cave);
            }));
        }
        for(Future<?> f:fs)f.get();ex.shutdown();
        // copy base -> final, then apply features to final (sequential so the log order is exact)
        byte[][] fin=new byte[side][];
        for(int i=0;i<side;i++)fin[i]=base[i].clone();
        int chunks=side/16;
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++){
            placeBlobs(fin,side,cx,cz,log);
            placeTrees(fin,side,cx,cz,log);
        }
        for(int vcx=0;vcx<chunks;vcx+=34)for(int vcz=0;vcz<chunks;vcz+=34){
            if((hash(vcx,vcz,777)&3)==0)continue;
            placeVillage(fin,side,vcx,vcz,log);
        }
        return new byte[][][]{base,fin};
    }

    static byte get(byte[][] v,int side,int x,int z,int yi){return v[x][z*HEIGHT+yi];}
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
    static int nextPow2(int n){int p=1;while(p<n)p<<=1;return p;}
    static long deflatedLen(byte[] in){
        Deflater d=new Deflater(6);d.setInput(in);d.finish();
        byte[] o=new byte[1<<16];long tot=0;
        while(!d.finished()){int n=d.deflate(o);if(n>0)tot+=n;else break;}
        d.end();return tot;
    }
    static void wVar(java.io.ByteArrayOutputStream bo,int n){
        while((n&~0x7F)!=0){bo.write((n&0x7F)|0x80);n>>>=7;}bo.write(n);
    }

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

    static void colRuns(java.io.ByteArrayOutputStream bo,byte[][] v,int side,int gx,int gz){
        byte prev=get(v,side,gx,gz,0);int run=1;
        for(int yi=1;yi<HEIGHT;yi++){
            byte cur=get(v,side,gx,gz,yi);
            if(cur==prev){run++;continue;}
            bo.write(prev);wVar(bo,run);prev=cur;run=1;
        }
        bo.write(prev);wVar(bo,run);
    }
    static long encColRLE(byte[][] v,int side){
        int chunks=side/16,regions=(chunks+31)/32;
        long total=(long)regions*regions*512L;
        for(int rx=0;rx<regions;rx++)for(int rz=0;rz<regions;rz++){
            java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<21);
            for(int c0=0;c0<32;c0++)for(int c1=0;c1<32;c1++){
                int cx=rx*32+c0,cz=rz*32+c1;if(cx>=chunks||cz>=chunks)continue;
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)colRuns(bo,v,side,cx*16+lx,cz*16+lz);
            }
            total+=deflatedLen(bo.toByteArray());
        }
        return total;
    }

    // feature list: sort by chunk, delta-code positions, varint everything, DEFLATE
    static long encFeatureList(List<Feat> log,int side){
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<22);
        int px=0,py=0,pz=0;
        for(Feat f:log){
            bo.write(f.kind);
            wVar(bo,f.tpl);
            int dx=f.x-px,dy=f.y-py,dz=f.z-pz;
            wVar(bo,(dx<<1)^(dx>>31)); wVar(bo,(dy<<1)^(dy>>31)); wVar(bo,(dz<<1)^(dz>>31));
            px=f.x;py=f.y;pz=f.z;
        }
        return deflatedLen(bo.toByteArray());
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

    // verify feature-extraction is lossless: replay base+log and compare to final
    static boolean verify(byte[][] base,byte[][] fin,int side,List<Feat> log){
        byte[][] rep=new byte[side][];
        for(int i=0;i<side;i++)rep[i]=base[i].clone();
        for(Feat f:log){
            if(f.kind==0){
                int si=f.tpl/(SHAPE_FAMILY*8), rem=f.tpl%(SHAPE_FAMILY*8);
                int shape=rem/8, sizeBucket=rem%8;
                // size is lossy through the bucket -- reconstruct with bucket midpoint
                int size=sizeBucket*5+2;
                applyBlob(rep,side,f.x,f.y,f.z,shape,size,(byte)BLOBS[si][0]);
            } else if(f.kind==1) applyTree(rep,side,f.x,f.y,f.z,f.tpl);
            else applyBuilding(rep,side,f.x,f.y,f.z,f.tpl);
        }
        long diff=0,tot=0;
        for(int x=0;x<side;x++)for(int i=0;i<side*HEIGHT;i++){tot++;if(rep[x][i]!=fin[x][i])diff++;}
        System.out.printf("  [verify] replay mismatch %d / %d voxels (%.4f%%)%n",diff,tot,100.0*diff/tot);
        return diff==0;
    }

    public static void main(String[] args) throws Exception {
        int side=args.length>0?Integer.parseInt(args[0]):512;
        System.out.printf("=== side=%d  FEATURE EXTRACTION TEST ===%n",side);
        List<Feat> log=new ArrayList<>();
        long t=System.currentTimeMillis();
        byte[][][] both=generate(side,0.15,log);
        byte[][] base=both[0],fin=both[1];
        System.out.printf("  [gen %ds]  %d features logged (%.1f per chunk)%n",
            (System.currentTimeMillis()-t)/1000,log.size(),log.size()/(double)((side/16)*(side/16)));

        verify(base,fin,side,log);

        long van=encVanilla(fin,side);
        System.out.printf("  %-34s %13d%n","vanilla (final volume)",van);

        long full=encColRLE(fin,side);
        System.out.printf("  %-34s %13d  %6.2fx%n","Column-RLE on FINAL volume",full,van/(double)full);

        long baseRle=encColRLE(base,side);
        long featBytes=encFeatureList(log,side);
        long combo=baseRle+featBytes;
        System.out.printf("  %-34s %13d  %6.2fx   (terrain %d + features %d)%n",
            "FEATURE-EXTRACT (base + list)",combo,van/(double)combo,baseRle,featBytes);
        System.out.printf("      -> vs Column-RLE-on-final: %.2fx better%n",full/(double)combo);

        long[] svFin=encSVDAG(fin,side);
        System.out.printf("  %-34s %13d  %6.2fx   %d nodes%n","SVDAG on FINAL volume",svFin[0],van/(double)svFin[0],svFin[1]);
        long[] svBase=encSVDAG(base,side);
        long svCombo=svBase[0]+featBytes;
        System.out.printf("  %-34s %13d  %6.2fx   (svdag-base %d + features %d)%n",
            "SVDAG-base + feature list",svCombo,van/(double)svCombo,svBase[0],featBytes);
    }
}
