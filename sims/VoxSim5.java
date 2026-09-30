import java.util.*;
import java.util.zip.Deflater;
import java.util.concurrent.*;

// v5: find where SVDAG's sublinear scaling actually overtakes Column-RLE's linear scaling,
// and how fast it diverges past that point. Drops the losers (ColDictQuad, dominant-page,
// surface, plain octree). Runs SVDAG at every size including 2048 and 4096.

public class VoxSim5 {
    static final byte AIR=0,BEDROCK=1,DEEPSLATE=2,STONE=3,DIRT=4,GRASS=5,WATER=6,ORE=7,
                      SAND=10,GRAVEL=11,ANDESITE=12,GRANITE=13,DIORITE=14,TUFF=15,SNOW=16,CLAY=17;
    static final int Y_MIN=-64,Y_MAX=320,HEIGHT=Y_MAX-Y_MIN,SEA=62;

    static int hash(int x,int z,int s){int h=x*374761393+z*668265263+s*1274126177;h=(h^(h>>>13))*1274126177;return h^(h>>>16);}
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
    static byte blockAt(int x,int y,int z,double cave){
        if(y<Y_MIN+5)return BEDROCK;
        int sy=surfaceY(x,z);
        if(y>sy)return (y<=SEA)?WATER:AIR;
        if(isCave(x,y,z,cave)&&y<sy-2&&y>Y_MIN+5)return AIR;
        double biome=noise(x,z,9,77);
        if(y==sy){ if(sy<=SEA+1)return biome<0.3?SAND:DIRT;
                   if(biome<0.20)return SAND; if(biome>0.85)return SNOW; return GRASS; }
        if(y>sy-4){ if(biome<0.20)return SAND; if(biome<0.28)return CLAY; return DIRT; }
        double vn=noise(x,z,4,101+((y>>4)*13));
        if(vn<0.10)return ANDESITE;
        if(vn>0.93)return GRANITE;
        if(vn>0.88)return DIORITE;
        if(y<0&&vn>0.78)return TUFF;
        if((hash(x,z,y*17)&0x1FF)==0)return GRAVEL;
        if(y<0)return DEEPSLATE;
        if((hash(x,z,y*31)&0x3FF)==0)return ORE;
        return STONE;
    }
    static byte[] generate(int side,double cave) throws Exception {
        final byte[] v=new byte[side*side*HEIGHT];
        int nt=Runtime.getRuntime().availableProcessors();
        ExecutorService ex=Executors.newFixedThreadPool(nt);
        List<Future<?>> fs=new ArrayList<>();
        int ch=(side+nt-1)/nt;
        for(int t=0;t<nt;t++){
            final int x0=t*ch,x1=Math.min(x0+ch,side);
            fs.add(ex.submit(()->{
                for(int x=x0;x<x1;x++)for(int z=0;z<side;z++)for(int yi=0;yi<HEIGHT;yi++)
                    v[x*side*HEIGHT+z*HEIGHT+yi]=blockAt(x,yi+Y_MIN,z,cave);
            }));
        }
        for(Future<?> f:fs)f.get(); ex.shutdown(); return v;
    }
    static byte get(byte[] v,int side,int x,int z,int yi){return v[x*side*HEIGHT+z*HEIGHT+yi];}
    static int nextPow2(int n){int p=1;while(p<n)p<<=1;return p;}
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
    static long deflatedLen(byte[] in){
        Deflater d=new Deflater(6);d.setInput(in);d.finish();
        byte[] o=new byte[1<<16]; long total=0;
        while(!d.finished()){int n=d.deflate(o); if(n>0) total+=n; else break;}
        d.end(); return total;
    }

    // ---- vanilla (harsh model, used only as a consistent yardstick) ----
    static long encVanilla(byte[] v,int side){
        long total=0;int chunks=side/16;
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++)
            total+=((5+deflatedLen(chunkPayload(v,side,cx,cz))+4095)/4096)*4096;
        int r=(chunks+31)/32; return total+(long)r*r*8192L;
    }
    static byte[] chunkPayload(byte[] v,int side,int cx,int cz){
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

    // ---- Column-RLE + DEFLATE ----
    static long encColumnRLE(byte[] v,int side){
        int chunks=side/16,regions=(chunks+31)/32;
        long total=(long)regions*regions*512L;
        for(int rx=0;rx<regions;rx++)for(int rz=0;rz<regions;rz++){
            java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<21);
            for(int c0=0;c0<32;c0++)for(int c1=0;c1<32;c1++){
                int cx=rx*32+c0,cz=rz*32+c1;
                if(cx>=chunks||cz>=chunks)continue;
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)colRuns(bo,v,side,cx*16+lx,cz*16+lz);
            }
            total+=deflatedLen(bo.toByteArray());
        }
        return total;
    }
    static void colRuns(java.io.ByteArrayOutputStream bo,byte[] v,int side,int gx,int gz){
        byte prev=get(v,side,gx,gz,0);int run=1;
        for(int yi=1;yi<HEIGHT;yi++){
            byte cur=get(v,side,gx,gz,yi);
            if(cur==prev){run++;continue;}
            bo.write(prev);wVar(bo,run);prev=cur;run=1;
        }
        bo.write(prev);wVar(bo,run);
    }
    static void wVar(java.io.ByteArrayOutputStream bo,int n){
        while((n&~0x7F)!=0){bo.write((n&0x7F)|0x80);n>>>=7;}bo.write(n);
    }

    // ---- ColDict: global column-profile dictionary + bit-packed index array ----
    static long[] encColDict(byte[] v,int side){
        HashMap<ByteKey,Integer> dict=new HashMap<>(1<<20);
        java.io.ByteArrayOutputStream db=new java.io.ByteArrayOutputStream(1<<22);
        int[] index=new int[side*side];
        int nProf=0;
        java.io.ByteArrayOutputStream cb=new java.io.ByteArrayOutputStream(64);
        for(int x=0;x<side;x++)for(int z=0;z<side;z++){
            cb.reset(); colRuns(cb,v,side,x,z);
            byte[] p=cb.toByteArray();
            ByteKey k=new ByteKey(p);
            Integer id=dict.get(k);
            if(id==null){ id=nProf++; dict.put(k,id); wVar(db,p.length); db.write(p,0,p.length); }
            index[x*side+z]=id;
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

    // ---- SVDAG 3D ----
    static long[] encSVDAG(byte[] v,int side){
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
    static int dagBuild(byte[] v,int side,int x0,int y0,int z0,int dim,
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
    static int samp(byte[] v,int side,int x,int y,int z){
        if(x>=side||z>=side||y>=HEIGHT)return AIR&0xFF;
        return get(v,side,x,z,y)&0xFF;
    }
    static class BitStream{
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<22);
        long buf=0;int nb=0;
        void w(int v,int n){ if(n<=0)return;
            buf|=((long)(v&(int)((1L<<n)-1)))<<nb;nb+=n;
            while(nb>=8){bo.write((int)(buf&0xFF));buf>>>=8;nb-=8;} }
        byte[] toByteArray(){if(nb>0)bo.write((int)(buf&0xFF));return bo.toByteArray();}
    }

    static LinkedHashMap<String,TreeMap<Integer,Long>> res=new LinkedHashMap<>();
    static void rec(String k,int side,long b){
        res.computeIfAbsent(k,x->new TreeMap<>()).put(side,b);
    }

    static void run(int side) throws Exception {
        System.out.printf("%n=== side=%d (%.2fB voxels, %.1f GB raw) ===%n",
            side,side*(long)side*HEIGHT/1e9,side*(long)side*HEIGHT/1e9);
        long t=System.currentTimeMillis();
        byte[] v=generate(side,0.15);
        System.out.printf("  [gen %ds]%n",(System.currentTimeMillis()-t)/1000);

        t=System.currentTimeMillis(); long van=encVanilla(v,side); rec("vanilla",side,van);
        System.out.printf("  %-22s %14d           [%ds]%n","vanilla",van,(System.currentTimeMillis()-t)/1000);

        t=System.currentTimeMillis(); long cr=encColumnRLE(v,side); rec("colrle",side,cr);
        System.out.printf("  %-22s %14d  %7.1fx  [%ds]%n","Column-RLE",cr,van/(double)cr,(System.currentTimeMillis()-t)/1000);

        t=System.currentTimeMillis(); long[] cd=encColDict(v,side); rec("coldict",side,cd[0]);
        System.out.printf("  %-22s %14d  %7.1fx  [%ds]  %d profiles, dict=%dB idx=%dB%n",
            "ColDict",cd[0],van/(double)cd[0],(System.currentTimeMillis()-t)/1000,cd[1],cd[2],cd[3]);

        t=System.currentTimeMillis(); long[] sv=encSVDAG(v,side); rec("svdag",side,sv[0]);
        System.out.printf("  %-22s %14d  %7.1fx  [%ds]  %d nodes%n",
            "SVDAG",sv[0],van/(double)sv[0],(System.currentTimeMillis()-t)/1000,sv[1]);
    }

    public static void main(String[] a) throws Exception {
        int[] sides = a.length>0 ? new int[]{Integer.parseInt(a[0])} : new int[]{512,1024,2048};
        for(int s:sides) run(s);
        System.out.println("\n##### exponents (size ~ area^b), per side-doubling #####");
        for(Map.Entry<String,TreeMap<Integer,Long>> e:res.entrySet()){
            System.out.printf("%-10s",e.getKey());
            Integer[] ks=e.getValue().keySet().toArray(new Integer[0]);
            for(int i=0;i+1<ks.length;i++){
                double g=e.getValue().get(ks[i+1])/(double)e.getValue().get(ks[i]);
                System.out.printf("  %d->%d b=%.3f",ks[i],ks[i+1],Math.log(g)/Math.log(4.0));
            }
            System.out.println();
        }
    }
}
