import java.util.*;
import java.util.zip.Deflater;
import java.util.concurrent.*;

// v4: no world-size ceiling. Tests asymptotic scaling out to side=2048 with 4-point exponent fits.
//
// NEW SCHEMES (the point of this pass):
//   ColDict      - hash-cons unique COLUMN PROFILES into a dictionary; store a per-column index
//                  array. Rationale: terrain is Y-stratified, so the number of DISTINCT column
//                  profiles saturates as the world grows (only so many surface-height x biome x
//                  cave-pattern combos). Dictionary goes ~constant; index array grows.
//   ColDictQuad  - as above, but the index array is itself encoded as a 2D quadtree-DAG with
//                  hash-consed subtrees. This is SVDAG's dedup applied to the anisotropic
//                  representation. Should be the asymptotic winner: both the dictionary AND the
//                  index structure saturate.

public class VoxSim4 {

    static final byte AIR=0,BEDROCK=1,DEEPSLATE=2,STONE=3,DIRT=4,GRASS=5,WATER=6,ORE=7,
                      WOOD=8,LEAVES=9,SAND=10,GRAVEL=11,ANDESITE=12,GRANITE=13,DIORITE=14,
                      TUFF=15,SNOW=16,CLAY=17;
    static final int Y_MIN=-64,Y_MAX=320,HEIGHT=Y_MAX-Y_MIN;
    static final int SEA=62;

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
    static byte blockAt(int x,int y,int z,double cave,double div){
        if(y<Y_MIN+5)return BEDROCK;
        int sy=surfaceY(x,z);
        if(y>sy)return (y<=SEA)?WATER:AIR;
        if(isCave(x,y,z,cave)&&y<sy-2&&y>Y_MIN+5)return AIR;
        double biome=noise(x,z,9,77);
        if(y==sy){
            if(sy<=SEA+1)return (div>0.5&&biome<0.3)?SAND:DIRT;
            if(div>0.5){if(biome<0.20)return SAND; if(biome>0.85)return SNOW;}
            return GRASS;
        }
        if(y>sy-4){
            if(div>0.5&&biome<0.20)return SAND;
            if(div>0.5&&biome<0.28)return CLAY;
            return DIRT;
        }
        if(div>0.5){
            double vn=noise(x,z,4,101+((y>>4)*13));
            if(vn<0.10)return ANDESITE;
            if(vn>0.93)return GRANITE;
            if(vn>0.88)return DIORITE;
            if(y<0&&vn>0.78)return TUFF;
            if((hash(x,z,y*17)&0x1FF)==0)return GRAVEL;
        }
        if(y<0)return DEEPSLATE;
        if((hash(x,z,y*31)&0x3FF)==0)return ORE;
        return STONE;
    }
    // parallel generation - 1.6B voxels at side=2048 is too slow single-threaded
    static byte[] generate(int side,double cave,double div) throws Exception {
        final byte[] v=new byte[side*side*HEIGHT];
        int nt=Runtime.getRuntime().availableProcessors();
        ExecutorService ex=Executors.newFixedThreadPool(nt);
        List<Future<?>> fs=new ArrayList<>();
        int chunk=(side+nt-1)/nt;
        for(int t=0;t<nt;t++){
            final int x0=t*chunk, x1=Math.min(x0+chunk,side);
            fs.add(ex.submit(()->{
                for(int x=x0;x<x1;x++)for(int z=0;z<side;z++)for(int yi=0;yi<HEIGHT;yi++)
                    v[x*side*HEIGHT+z*HEIGHT+yi]=blockAt(x,yi+Y_MIN,z,cave,div);
            }));
        }
        for(Future<?> f:fs) f.get();
        ex.shutdown();
        return v;
    }
    static byte get(byte[] v,int side,int x,int z,int yi){return v[x*side*HEIGHT+z*HEIGHT+yi];}
    static int nextPow2(int n){int p=1;while(p<n)p<<=1;return p;}
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}
    static byte[] deflate(byte[] in){
        Deflater d=new Deflater(6);d.setInput(in);d.finish();
        byte[] o=new byte[in.length/2+4096];
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(in.length/4+1024);
        while(!d.finished()){int n=d.deflate(o); if(n>0) bo.write(o,0,n); else break;}
        d.end(); return bo.toByteArray();
    }
    static long deflatedLen(byte[] in){ return deflate(in).length; }

    // ---------- VANILLA (v3 model: realistic-valued light; known ~4x too harsh, see notes) ----------
    static long encVanilla(byte[] v,int side){
        long total=0;int chunks=side/16;
        for(int cx=0;cx<chunks;cx++)for(int cz=0;cz<chunks;cz++){
            byte[] p=chunkPayload(v,side,cx,cz);
            total+=((5+deflate(p).length+4095)/4096)*4096;
        }
        int regions=(chunks+31)/32;
        return total+(long)regions*regions*8192L;
    }
    static byte[] chunkPayload(byte[] v,int side,int cx,int cz){
        int sections=HEIGHT/16;
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<17);
        for(int m=0;m<4;m++)for(int i=0;i<37*8;i++)bo.write(hash(cx,cz,m*31+i)&0xFF);
        bo.write(new byte[200],0,200);
        for(int sy=0;sy<sections;sy++){
            byte[] pal=new byte[32];int npal=0;int[] pidx=new int[256];Arrays.fill(pidx,-1);
            short[] idx=new short[4096];boolean nonAir=false;
            for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)for(int ly=0;ly<16;ly++){
                byte b=get(v,side,cx*16+lx,cz*16+lz,sy*16+ly);
                if(b!=AIR)nonAir=true;
                int pi=pidx[b&0xFF];
                if(pi<0){if(npal==pal.length)pal=Arrays.copyOf(pal,pal.length*2);pal[npal]=b;pi=npal++;pidx[b&0xFF]=pi;}
                idx[((lx*16)+lz)*16+ly]=(short)pi;
            }
            if(!nonAir)continue;
            bo.write(new byte[40],0,40);
            for(int i=0;i<npal;i++){String nm="minecraft:block_"+(pal[i]&0xFF);for(int k=0;k<nm.length();k++)bo.write(nm.charAt(k));}
            if(npal>1){
                int bits=Math.max(4,log2ceil(npal));int longs=(4096*bits+63)/64;
                long[] pk=new long[longs];int per=64/bits,li=0,sh=0;
                for(int i=0;i<4096;i++){pk[li]|=((long)idx[i])<<(sh*bits);if(++sh==per){sh=0;li++;}}
                for(long L:pk)for(int b=0;b<8;b++)bo.write((int)((L>>>(b*8))&0xFF));
            }
            bo.write(new byte[50],0,50);
            for(int i=0;i<2048;i++)bo.write(hash(cx*16+sy,cz*16,i)&0xFF);
            for(int i=0;i<2048;i++)bo.write(hash(cz*16+sy,cx*16,i+9973)&0xFF);
        }
        return bo.toByteArray();
    }

    // ---------- COLUMN-RLE + DEFLATE (v3 champion) ----------
    static long encColumnRLE(byte[] v,int side){
        int chunks=side/16,regions=(chunks+31)/32;
        long total=(long)regions*regions*512L;
        for(int rx=0;rx<regions;rx++)for(int rz=0;rz<regions;rz++){
            java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(1<<21);
            for(int c0=0;c0<32;c0++)for(int c1=0;c1<32;c1++){
                int cx=rx*32+c0,cz=rz*32+c1;
                if(cx>=chunks||cz>=chunks)continue;
                for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++)
                    writeColRuns(bo,v,side,cx*16+lx,cz*16+lz);
            }
            total+=deflatedLen(bo.toByteArray());
        }
        return total;
    }
    static void writeColRuns(java.io.ByteArrayOutputStream bo,byte[] v,int side,int gx,int gz){
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
    static byte[] colProfile(byte[] v,int side,int gx,int gz){
        java.io.ByteArrayOutputStream bo=new java.io.ByteArrayOutputStream(64);
        writeColRuns(bo,v,side,gx,gz);
        return bo.toByteArray();
    }

    // ---------- NEW: ColDict - hash-cons unique column profiles ----------
    // dictionary of distinct column run-profiles + per-column index array, both DEFLATE'd.
    static long[] encColDict(byte[] v,int side){
        HashMap<ByteKey,Integer> dict=new HashMap<>();
        List<byte[]> profiles=new ArrayList<>();
        int[] index=new int[side*side];
        for(int x=0;x<side;x++)for(int z=0;z<side;z++){
            byte[] p=colProfile(v,side,x,z);
            ByteKey k=new ByteKey(p);
            Integer id=dict.get(k);
            if(id==null){id=profiles.size();dict.put(k,id);profiles.add(p);}
            index[x*side+z]=id;
        }
        // serialize dictionary
        java.io.ByteArrayOutputStream db=new java.io.ByteArrayOutputStream(1<<20);
        for(byte[] p:profiles){wVar(db,p.length);db.write(p,0,p.length);}
        long dictBytes=deflatedLen(db.toByteArray());
        // serialize index array, bit-packed at log2(dictSize)
        int bits=Math.max(1,log2ceil(profiles.size()));
        BitStream bs=new BitStream();
        for(int i=0;i<index.length;i++)bs.w(index[i],bits);
        long idxBytes=deflatedLen(bs.toByteArray());
        return new long[]{dictBytes+idxBytes+64, profiles.size(), dictBytes, idxBytes};
    }

    // ---------- NEW: ColDictQuad - column dict + 2D quadtree-DAG over the index array ----------
    static long[] encColDictQuad(byte[] v,int side){
        HashMap<ByteKey,Integer> dict=new HashMap<>();
        List<byte[]> profiles=new ArrayList<>();
        int[] index=new int[side*side];
        for(int x=0;x<side;x++)for(int z=0;z<side;z++){
            byte[] p=colProfile(v,side,x,z);
            ByteKey k=new ByteKey(p);
            Integer id=dict.get(k);
            if(id==null){id=profiles.size();dict.put(k,id);profiles.add(p);}
            index[x*side+z]=id;
        }
        java.io.ByteArrayOutputStream db=new java.io.ByteArrayOutputStream(1<<20);
        for(byte[] p:profiles){wVar(db,p.length);db.write(p,0,p.length);}
        long dictBytes=deflatedLen(db.toByteArray());
        // quadtree-DAG over index[]: leaves are column ids, internal nodes hash-consed
        int dim=nextPow2(side);
        List<int[]> nodes=new ArrayList<>();
        HashMap<Long,Integer> pool=new HashMap<>();
        int nProfiles=profiles.size();
        quadBuild(index,side,0,0,dim,pool,nodes,nProfiles);
        int idBits=Math.max(1,log2ceil(nodes.size()));
        int leafBits=Math.max(1,log2ceil(nProfiles));
        BitStream bs=new BitStream();
        for(int[] n:nodes){
            int mask=0;
            for(int i=0;i<4;i++) if(n[i]>=nProfiles) mask|=(1<<i);
            bs.w(mask,4);
            for(int i=0;i<4;i++){
                if(n[i]<nProfiles) bs.w(n[i],leafBits);
                else bs.w(n[i]-nProfiles,idBits);
            }
        }
        long treeBytes=deflatedLen(bs.toByteArray());
        return new long[]{dictBytes+treeBytes+64, nProfiles, dictBytes, treeBytes, nodes.size()};
    }
    // returns a column-profile id if the square is uniform, else nProfiles + nodeIndex
    static int quadBuild(int[] index,int side,int x0,int z0,int dim,
                         HashMap<Long,Integer> pool,List<int[]> nodes,int nProfiles){
        if(dim==1){
            if(x0>=side||z0>=side) return 0; // out of bounds -> profile 0
            return index[x0*side+z0];
        }
        int half=dim>>>1;
        int[] c=new int[4];
        int u=-1;boolean uni=true;
        for(int q=0;q<4;q++){
            int ox=(q&1)*half, oz=((q>>>1)&1)*half;
            c[q]=quadBuild(index,side,x0+ox,z0+oz,half,pool,nodes,nProfiles);
            if(q==0)u=c[q]; else if(c[q]!=u)uni=false;
        }
        if(uni && u<nProfiles) return u;
        long key=0;for(int i=0;i<4;i++)key=key*1099511628211L+c[i]+1;
        Integer ex=pool.get(key);
        if(ex!=null) return nProfiles+ex;
        int id=nodes.size();nodes.add(c);pool.put(key,id);
        return nProfiles+id;
    }
    static class ByteKey{
        final byte[] b; final int h;
        ByteKey(byte[] b){this.b=b;this.h=Arrays.hashCode(b);}
        public int hashCode(){return h;}
        public boolean equals(Object o){return o instanceof ByteKey && Arrays.equals(b,((ByteKey)o).b);}
    }

    // ---------- SVDAG (3D, for comparison) ----------
    static long[] encSVDAG(byte[] v,int side){
        int dim=nextPow2(Math.max(side,HEIGHT));
        List<int[]> nodes=new ArrayList<>();HashMap<Long,Integer> pool=new HashMap<>();
        dagBuild(v,side,0,0,0,dim,pool,nodes);
        int bits=log2ceil(nodes.size());
        BitStream bs=new BitStream();
        for(int[] n:nodes){
            int mask=0;for(int i=0;i<8;i++)if(n[i]>=256)mask|=(1<<i);
            bs.w(mask,8);
            for(int i=0;i<8;i++){if(n[i]<256)bs.w(n[i],8);else bs.w(n[i]-256,bits);}
        }
        return new long[]{deflatedLen(bs.toByteArray())+36, nodes.size()};
    }
    static int dagBuild(byte[] v,int side,int x0,int y0,int z0,int dim,
                        HashMap<Long,Integer> pool,List<int[]> nodes){
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
        void w(int v,int n){
            if(n<=0) return;
            buf|=((long)(v&(int)((1L<<n)-1)))<<nb; nb+=n;
            while(nb>=8){bo.write((int)(buf&0xFF));buf>>>=8;nb-=8;}
        }
        byte[] toByteArray(){if(nb>0)bo.write((int)(buf&0xFF));return bo.toByteArray();}
    }

    static Map<String,long[]> results=new LinkedHashMap<>();

    static void run(int side,double cave,double div) throws Exception {
        System.out.printf("%n=== side=%d div=%.1f (%.2fB voxels) ===%n",side,div,side*(long)side*HEIGHT/1e9);
        long t=System.currentTimeMillis();
        byte[] v=generate(side,cave,div);
        System.out.printf("  [gen %.0fs]%n",(System.currentTimeMillis()-t)/1000.0);

        t=System.currentTimeMillis(); long van=encVanilla(v,side);
        System.out.printf("  %-28s %13d          [%.0fs]%n","Vanilla mca (harsh model)",van,(System.currentTimeMillis()-t)/1000.0);
        record("vanilla",side,van);

        t=System.currentTimeMillis(); long cr=encColumnRLE(v,side);
        System.out.printf("  %-28s %13d  %7.1fx [%.0fs]%n","Column-RLE+DEFLATE",cr,van/(double)cr,(System.currentTimeMillis()-t)/1000.0);
        record("colrle",side,cr);

        t=System.currentTimeMillis(); long[] cd=encColDict(v,side);
        System.out.printf("  %-28s %13d  %7.1fx [%.0fs]  dict=%d profiles (%d B) idx=%d B%n",
            "ColDict (profile dedup)",cd[0],van/(double)cd[0],(System.currentTimeMillis()-t)/1000.0,cd[1],cd[2],cd[3]);
        record("coldict",side,cd[0]);

        t=System.currentTimeMillis(); long[] cq=encColDictQuad(v,side);
        System.out.printf("  %-28s %13d  %7.1fx [%.0fs]  dict=%d (%d B) quad=%d B / %d nodes%n",
            "ColDictQuad (2D DAG)",cq[0],van/(double)cq[0],(System.currentTimeMillis()-t)/1000.0,cq[1],cq[2],cq[3],cq[4]);
        record("coldictquad",side,cq[0]);

        if(side<=1024){
            t=System.currentTimeMillis(); long[] sv=encSVDAG(v,side);
            System.out.printf("  %-28s %13d  %7.1fx [%.0fs]  %d nodes%n",
                "SVDAG 3D",sv[0],van/(double)sv[0],(System.currentTimeMillis()-t)/1000.0,sv[1]);
            record("svdag",side,sv[0]);
        }
    }
    static void record(String k,int side,long bytes){
        results.computeIfAbsent(k,x->new long[16]);
        int slot=log2ceil(side)-8; // 256->0, 512->1, 1024->2, 2048->3
        results.get(k)[slot]=bytes;
    }
    static void fits(){
        System.out.println("\n\n########## MEASURED SCALING EXPONENTS (size ~ area^b) ##########");
        int[] sides={256,512,1024,2048};
        for(Map.Entry<String,long[]> e:results.entrySet()){
            long[] r=e.getValue();
            System.out.printf("%-14s ",e.getKey());
            List<Double> bs=new ArrayList<>();
            for(int i=0;i+1<4;i++){
                if(r[i]==0||r[i+1]==0) continue;
                double growth=r[i+1]/(double)r[i];
                double b=Math.log(growth)/Math.log(4.0); // area quadruples per side doubling
                bs.add(b);
                System.out.printf(" %d->%d: b=%.3f ",sides[i],sides[i+1],b);
            }
            if(!bs.isEmpty()) System.out.printf("  | last b=%.3f",bs.get(bs.size()-1));
            System.out.println();
        }
    }

    public static void main(String[] a) throws Exception {
        for(int s:new int[]{256,512,1024,2048}) run(s,0.15,1.0);
        fits();
    }
}
