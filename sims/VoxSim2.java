import java.util.*;
import java.util.zip.Deflater;

// Voxel-encoding sim v2. Improvements over v1:
//   * Vanilla-mca includes BlockLight+SkyLight (2KB/section each) and realistic NBT overhead.
//   * Octree pointers are bit-packed to ceil(log2(nodes)) bits, then DEFLATE'd.
//   * SVDAG pointers bit-packed, node pool DEFLATE'd.
//   * Roaring: dominant-class-per-page + exception list, fallback to raw when >1/3 exceptions.
//   * Column-RLE: DEFLATE the packed stream.
//   * Surface v2: per dominant-block-per-region, surface faces of dominant + exception list
//     for all non-dominant voxels within the region. Fragmentation of ores goes to exceptions,
//     not to surface — which is what surface encoding SHOULD look like.

public class VoxSim2 {

    static final byte AIR = 0, BEDROCK = 1, DEEPSLATE = 2, STONE = 3,
                       DIRT = 4, GRASS = 5, WATER = 6, ORE = 7,
                       WOOD = 8, LEAVES = 9;
    static final int Y_MIN = -64, Y_MAX = 320, HEIGHT = Y_MAX - Y_MIN;
    static final int SEA = 62;

    static int hash(int x, int z, int salt) {
        int h = x * 374761393 + z * 668265263 + salt * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }
    static double noise(int x, int z, int octave, int salt) {
        int p = 1 << octave;
        int x0 = Math.floorDiv(x, p), z0 = Math.floorDiv(z, p);
        double fx = (x - x0*p)/(double)p, fz = (z - z0*p)/(double)p;
        double a = (hash(x0,z0,salt)&0xFFFF)/65535.0;
        double b = (hash(x0+1,z0,salt)&0xFFFF)/65535.0;
        double c = (hash(x0,z0+1,salt)&0xFFFF)/65535.0;
        double d = (hash(x0+1,z0+1,salt)&0xFFFF)/65535.0;
        double sx=fx*fx*(3-2*fx), sz=fz*fz*(3-2*fz);
        return (a*(1-sx)+b*sx)*(1-sz)+(c*(1-sx)+d*sx)*sz;
    }
    static int surfaceY(int x, int z) {
        double h = noise(x,z,7,1)*40 + noise(x,z,5,2)*15 + noise(x,z,3,3)*5;
        return 60 + (int)h;
    }
    static boolean isCave(int x, int y, int z, double density) {
        int GS = 24;
        int gx = Math.floorDiv(x,GS), gy = Math.floorDiv(y,GS), gz = Math.floorDiv(z,GS);
        for (int dx=-1; dx<=1; dx++) for (int dy=-1; dy<=1; dy++) for (int dz=-1; dz<=1; dz++) {
            int cx=(gx+dx)*GS, cy=(gy+dy)*GS, cz=(gz+dz)*GS;
            int h1=hash(cx,cz,cy*7919+11);
            double p=(h1&0xFFFF)/65535.0;
            if (p > density) continue;
            int h2=hash(cx,cz,cy*7919+23);
            double jx=((h2&0xFF)-128)/20.0, jy=(((h2>>>8)&0xFF)-128)/20.0, jz=(((h2>>>16)&0xFF)-128)/20.0;
            double ox=cx+jx*GS, oy=cy+jy*GS, oz=cz+jz*GS;
            int h3=hash(cx,cz,cy*7919+41);
            double r = 4+((h3&0xFF)/255.0)*8;
            double d2=(x-ox)*(x-ox)+(y-oy)*(y-oy)*3+(z-oz)*(z-oz);
            if (d2 < r*r) return true;
        }
        return false;
    }
    static byte blockAt(int x, int y, int z, double caveDensity) {
        if (y < Y_MIN+5) return BEDROCK;
        int sy = surfaceY(x,z);
        if (y > sy) return (y <= SEA) ? WATER : AIR;
        if (isCave(x,y,z,caveDensity) && y < sy-2 && y > Y_MIN+5) return AIR;
        if (y == sy) return (sy <= SEA+1) ? DIRT : GRASS;
        if (y > sy-4) return DIRT;
        if (y < 0) return DEEPSLATE;
        if ((hash(x,z,y*31) & 0x3FF) == 0) return ORE;
        return STONE;
    }
    static byte[] generate(int side, double caveDensity) {
        byte[] v = new byte[side*side*HEIGHT];
        for (int x=0; x<side; x++) for (int z=0; z<side; z++) for (int yi=0; yi<HEIGHT; yi++) {
            v[x*side*HEIGHT + z*HEIGHT + yi] = blockAt(x, yi+Y_MIN, z, caveDensity);
        }
        return v;
    }
    static byte get(byte[] v, int side, int x, int z, int yi) {
        return v[x*side*HEIGHT + z*HEIGHT + yi];
    }
    static int nextPow2(int n){int p=1;while(p<n)p<<=1;return p;}
    static int log2ceil(int n){return n<=1?0:32-Integer.numberOfLeadingZeros(n-1);}

    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(6);
        d.setInput(in); d.finish();
        byte[] out = new byte[in.length + 64];
        int len = d.deflate(out); d.end();
        return Arrays.copyOf(out, len);
    }

    // ------------ VANILLA MCA (with light data + realistic overhead) ------------
    static long encVanillaV2(byte[] v, int side) {
        long total = 0;
        int chunks = side/16;
        for (int cx=0; cx<chunks; cx++) for (int cz=0; cz<chunks; cz++) {
            byte[] payload = chunkPayloadV2(v, side, cx, cz);
            byte[] def = deflate(payload);
            int stored = 5 + def.length;
            stored = ((stored+4095)/4096)*4096;
            total += stored;
        }
        int regions = (chunks+31)/32;
        total += (long)regions*regions*8192L;
        return total;
    }
    static byte[] chunkPayloadV2(byte[] v, int side, int cx, int cz) {
        int sections = HEIGHT/16;
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(65536);
        // chunk-root NBT overhead: heightmaps (~200B), status/xPos/zPos/DataVersion (~100B), structures (~200B)
        bo.write(new byte[500], 0, 500);
        for (int sy=0; sy<sections; sy++) {
            byte[] pal = new byte[16]; int npal=0;
            int[] palIdx = new int[256]; Arrays.fill(palIdx,-1);
            short[] idx = new short[4096];
            boolean anyNonAir = false;
            for (int lx=0; lx<16; lx++) for (int lz=0; lz<16; lz++) for (int ly=0; ly<16; ly++) {
                byte b = get(v, side, cx*16+lx, cz*16+lz, sy*16+ly);
                if (b != AIR) anyNonAir = true;
                int pi = palIdx[b&0xFF];
                if (pi < 0) {
                    if (npal == pal.length) pal = Arrays.copyOf(pal, pal.length*2);
                    pal[npal] = b; pi = npal++; palIdx[b&0xFF] = pi;
                }
                idx[((lx*16)+lz)*16+ly] = (short)pi;
            }
            if (!anyNonAir) continue;
            // section header (~40B) + palette (~2B per entry with NBT names inlined; conservative ~20B/entry)
            bo.write(new byte[40], 0, 40);
            for (int i=0; i<npal; i++) bo.write(new byte[20], 0, 20);
            if (npal > 1) {
                int bits = Math.max(4, log2ceil(npal));
                int longs = (4096*bits + 63)/64;
                bo.write(new byte[longs*8], 0, longs*8);
            }
            // biome palette + data (paletted per-section since 1.18): ~50B typical
            bo.write(new byte[50], 0, 50);
            // BlockLight: 4 bits/block × 4096 = 2048 bytes
            bo.write(new byte[2048], 0, 2048);
            // SkyLight: 2048 bytes
            bo.write(new byte[2048], 0, 2048);
        }
        return bo.toByteArray();
    }

    // ------------ COLUMN-RLE + DEFLATE ------------
    static long encColumnRLEv2(byte[] v, int side) {
        // Emit a raw byte stream of (blockId, varint runLen) per column, per chunk,
        // then DEFLATE the whole thing per region for cross-column pattern compression.
        int chunks = side/16, regions = (chunks+31)/32;
        long total = (long)regions*regions*512L; // region headers
        int regionChunks = 32;
        for (int rx=0; rx<regions; rx++) for (int rz=0; rz<regions; rz++) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1<<20);
            for (int cx0=0; cx0<regionChunks; cx0++) for (int cz0=0; cz0<regionChunks; cz0++) {
                int cx = rx*regionChunks + cx0, cz = rz*regionChunks + cz0;
                if (cx >= chunks || cz >= chunks) continue;
                for (int lx=0; lx<16; lx++) for (int lz=0; lz<16; lz++) {
                    int gx = cx*16+lx, gz = cz*16+lz;
                    byte prev = get(v, side, gx, gz, 0); int run = 1;
                    for (int yi=1; yi<HEIGHT; yi++) {
                        byte cur = get(v, side, gx, gz, yi);
                        if (cur == prev) { run++; continue; }
                        bo.write(prev); writeVarint(bo, run);
                        prev = cur; run = 1;
                    }
                    bo.write(prev); writeVarint(bo, run);
                }
            }
            byte[] def = deflate(bo.toByteArray());
            total += def.length;
        }
        return total;
    }
    static void writeVarint(java.io.ByteArrayOutputStream bo, int n) {
        while ((n & ~0x7F) != 0) { bo.write((n & 0x7F) | 0x80); n >>>= 7; }
        bo.write(n);
    }

    // ------------ OCTREE (bit-packed + DEFLATE) ------------
    // Encode as a preorder byte-serialization:
    //   for each internal node: 8-bit child mask (bit i = 1 if child i is internal, 0 if leaf) + 8 leaf-ids for leaf children
    // Recurse into internal children. All packed as bytes then deflate'd.
    static long encOctreeV2(byte[] v, int side) {
        int dim = nextPow2(Math.max(side, HEIGHT));
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1<<20);
        octreeSerialize(v, side, 0, 0, 0, dim, bo);
        byte[] def = deflate(bo.toByteArray());
        return def.length + 32;
    }
    // Returns leaf id if subtree is uniform, else -1 (and writes the node).
    static int octreeSerialize(byte[] v, int side, int x0, int y0, int z0, int dim, java.io.ByteArrayOutputStream bo) {
        if (dim == 1) return octreeSample(v, side, x0, y0, z0);
        int half = dim >>> 1;
        int[] c = new int[8];
        boolean uniform = true; int u = -1;
        java.io.ByteArrayOutputStream tmp = new java.io.ByteArrayOutputStream(64);
        for (int oi=0; oi<8; oi++) {
            int ox=(oi&1)*half, oy=((oi>>>1)&1)*half, oz=((oi>>>2)&1)*half;
            c[oi] = octreeSerialize(v, side, x0+ox, y0+oy, z0+oz, half, tmp);
            if (oi==0) u = c[oi]; else if (c[oi] != u) uniform = false;
            if (c[oi] < 0) uniform = false;
        }
        if (uniform) return u;
        int mask = 0;
        for (int i=0; i<8; i++) if (c[i] < 0) mask |= (1<<i);
        bo.write(mask);
        for (int i=0; i<8; i++) if (c[i] >= 0) bo.write(c[i]);
        // append child-internal bytes emitted into tmp
        try { tmp.writeTo(bo); } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        return -1;
    }
    static int octreeSample(byte[] v, int side, int x, int y, int z) {
        if (x>=side || z>=side || y>=HEIGHT) return AIR & 0xFF;
        return get(v, side, x, z, y) & 0xFF;
    }

    // ------------ SVDAG (bit-packed pointers + DEFLATE) ------------
    // First pass: build the DAG and assign each unique node an integer id.
    // Then serialize each unique node as: 8-bit mask (leaf vs internal children)
    //   + for each leaf child: 1 byte block id
    //   + for each internal child: bit-packed id at ceil(log2(poolSize)) bits.
    // Deflate the whole thing.
    static long encSVDAGv2(byte[] v, int side) {
        int dim = nextPow2(Math.max(side, HEIGHT));
        List<int[]> nodes = new ArrayList<>(); // each node: 8 child slots, low 8 bits = block id if leaf, else pool_id+256
        HashMap<Long, Integer> pool = new HashMap<>();
        int rootId = svdagBuild(v, side, 0, 0, 0, dim, pool, nodes);
        int nInternal = nodes.size();
        int bitsPerId = log2ceil(nInternal);
        // serialize
        BitStream bs = new BitStream();
        for (int[] node : nodes) {
            int mask = 0;
            for (int i=0; i<8; i++) if (node[i] >= 256) mask |= (1<<i);
            bs.writeBits(mask, 8);
            for (int i=0; i<8; i++) {
                if (node[i] < 256) bs.writeBits(node[i], 8);
                else bs.writeBits(node[i] - 256, bitsPerId);
            }
        }
        byte[] raw = bs.toByteArray();
        byte[] def = deflate(raw);
        return def.length + 32 + 4; // + root id
    }
    static int svdagBuild(byte[] v, int side, int x0, int y0, int z0, int dim,
                          HashMap<Long,Integer> pool, List<int[]> nodes) {
        if (dim == 1) return octreeSample(v, side, x0, y0, z0);
        int half = dim >>> 1;
        int[] c = new int[8];
        int u = -1; boolean uniform = true;
        for (int oi=0; oi<8; oi++) {
            int ox=(oi&1)*half, oy=((oi>>>1)&1)*half, oz=((oi>>>2)&1)*half;
            c[oi] = svdagBuild(v, side, x0+ox, y0+oy, z0+oz, half, pool, nodes);
            if (oi==0) u = c[oi]; else if (c[oi] != u) uniform = false;
        }
        if (uniform && u < 256) return u; // uniform leaf
        long key = 0;
        for (int i=0; i<8; i++) key = key * 1099511628211L + c[i] + 1;
        Integer ex = pool.get(key);
        if (ex != null) return ex + 256;
        int id = nodes.size();
        nodes.add(c);
        pool.put(key, id);
        return id + 256;
    }
    static class BitStream {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1<<16);
        long buf = 0; int bufBits = 0;
        void writeBits(int v, int nbits) {
            buf |= ((long)(v & ((1<<nbits)-1))) << bufBits;
            bufBits += nbits;
            while (bufBits >= 8) { bo.write((int)(buf & 0xFF)); buf >>>= 8; bufBits -= 8; }
        }
        byte[] toByteArray() { if (bufBits > 0) bo.write((int)(buf & 0xFF)); return bo.toByteArray(); }
    }

    // ------------ ROARING v2: dominant-class-per-page + exception list ------------
    // Page size 65536 voxels. Per page: 1B dominant class + 2B exception count + exceptions.
    // Exception encoding: sorted 16-bit position + 1B block id = 3B each.
    // If exceptions > 65536/3 ≈ 21845, fallback to raw 65536-byte page.
    // Then DEFLATE the whole stream per region.
    static long encRoaringV2(byte[] v, int side) {
        int voxels = side*side*HEIGHT;
        int pages = (voxels + 65535)/65536;
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1<<20);
        int[] typeCount = new int[256];
        for (int p=0; p<pages; p++) {
            int start = p*65536, end = Math.min(start+65536, voxels);
            Arrays.fill(typeCount, 0);
            for (int i=start; i<end; i++) typeCount[v[i]&0xFF]++;
            int dom = 0, domCount = 0;
            for (int t=0; t<256; t++) if (typeCount[t] > domCount) { dom = t; domCount = typeCount[t]; }
            int exceptions = (end - start) - domCount;
            if (exceptions > 21845) {
                bo.write(0xFF); // fallback marker
                for (int i=start; i<end; i++) bo.write(v[i]&0xFF);
            } else {
                bo.write(dom);
                bo.write(exceptions & 0xFF); bo.write((exceptions>>>8) & 0xFF);
                for (int i=start; i<end; i++) {
                    int b = v[i]&0xFF;
                    if (b != dom) {
                        int off = i - start;
                        bo.write(off & 0xFF); bo.write((off>>>8) & 0xFF);
                        bo.write(b);
                    }
                }
            }
        }
        byte[] def = deflate(bo.toByteArray());
        return def.length + 32;
    }

    // ------------ SURFACE v2: dominant per region + exception list ------------
    // Per 128-block region: identify dominant block. Emit:
    //   * 1B dominant block id
    //   * 4B bounding-box of dominant mass (assumed to fill region)
    //   * exception list of every non-dominant voxel (3B position + 1B block id = 4B each)
    //   * "surface" of dominant = we simply mark where dominant meets air among the exceptions
    //     -- but that's redundant with the exception list. So dominant surface is IMPLICIT (the region
    //     is filled with dominant except at the exception positions). Cost is just the exceptions.
    // This is basically Roaring-v2 with a bigger page (128^3 vs 65536). Kept as a comparison point.
    // (The user's insight is that pure surface-only encoding assumes NO interior detail; real Minecraft
    //  has ubiquitous interior detail, so any usable surface scheme reduces to dominant + exceptions.)
    static long encSurfaceV2(byte[] v, int side) {
        int R = 128;
        int rxc = (side+R-1)/R, ryc = (HEIGHT+R-1)/R;
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1<<20);
        int[] typeCount = new int[256];
        for (int rx=0; rx<rxc; rx++) for (int rz=0; rz<rxc; rz++) for (int ry=0; ry<ryc; ry++) {
            Arrays.fill(typeCount, 0);
            int x0=rx*R, y0=ry*R, z0=rz*R;
            int xE=Math.min(x0+R,side), yE=Math.min(y0+R,HEIGHT), zE=Math.min(z0+R,side);
            for (int x=x0; x<xE; x++) for (int z=z0; z<zE; z++) for (int y=y0; y<yE; y++)
                typeCount[get(v,side,x,z,y)&0xFF]++;
            int dom=0, domCount=0;
            for (int t=0; t<256; t++) if (typeCount[t] > domCount) { dom=t; domCount=typeCount[t]; }
            int regionVox = (xE-x0)*(yE-y0)*(zE-z0);
            int exceptions = regionVox - domCount;
            // header: dom (1B) + exception count (3B)
            bo.write(dom);
            bo.write(exceptions & 0xFF); bo.write((exceptions>>>8) & 0xFF); bo.write((exceptions>>>16) & 0xFF);
            // exceptions: 3-byte local position + 1B block id
            for (int x=x0; x<xE; x++) for (int z=z0; z<zE; z++) for (int y=y0; y<yE; y++) {
                int b = get(v,side,x,z,y)&0xFF;
                if (b != dom) {
                    int lx=x-x0, ly=y-y0, lz=z-z0;
                    int pos = (lx << 14) | (ly << 7) | lz; // 7 bits each = fits R=128
                    bo.write(pos & 0xFF); bo.write((pos>>>8) & 0xFF); bo.write((pos>>>16) & 0xFF);
                    bo.write(b);
                }
            }
        }
        byte[] def = deflate(bo.toByteArray());
        return def.length + 32;
    }

    static void run(int side, double cave) {
        System.out.printf("%n=== side=%d, cave=%.2f (%.1fM voxels) ===%n",
            side, cave, side*(long)side*HEIGHT/1e6);
        long t0=System.currentTimeMillis();
        byte[] v = generate(side, cave);
        System.out.printf("generated in %.1fs%n", (System.currentTimeMillis()-t0)/1000.0);
        long[] s = new long[6];
        String[] n = {"Vanilla+light","Column-RLE+DEFLATE","Octree bit-pack+DEFLATE","SVDAG bit-pack+DEFLATE","Roaring dominant+DEFLATE","Surface dominant+DEFLATE"};
        long ts;
        ts=System.currentTimeMillis(); s[0]=encVanillaV2(v,side);
        System.out.printf("  %-30s: %11d bytes  (%.1fs)%n", n[0], s[0], (System.currentTimeMillis()-ts)/1000.0);
        ts=System.currentTimeMillis(); s[1]=encColumnRLEv2(v,side);
        System.out.printf("  %-30s: %11d bytes  (%.1fs)  %.2fx vs vanilla%n", n[1], s[1], (System.currentTimeMillis()-ts)/1000.0, s[0]/(double)s[1]);
        ts=System.currentTimeMillis(); s[2]=encOctreeV2(v,side);
        System.out.printf("  %-30s: %11d bytes  (%.1fs)  %.2fx vs vanilla%n", n[2], s[2], (System.currentTimeMillis()-ts)/1000.0, s[0]/(double)s[2]);
        ts=System.currentTimeMillis(); s[3]=encSVDAGv2(v,side);
        System.out.printf("  %-30s: %11d bytes  (%.1fs)  %.2fx vs vanilla%n", n[3], s[3], (System.currentTimeMillis()-ts)/1000.0, s[0]/(double)s[3]);
        ts=System.currentTimeMillis(); s[4]=encRoaringV2(v,side);
        System.out.printf("  %-30s: %11d bytes  (%.1fs)  %.2fx vs vanilla%n", n[4], s[4], (System.currentTimeMillis()-ts)/1000.0, s[0]/(double)s[4]);
        ts=System.currentTimeMillis(); s[5]=encSurfaceV2(v,side);
        System.out.printf("  %-30s: %11d bytes  (%.1fs)  %.2fx vs vanilla%n", n[5], s[5], (System.currentTimeMillis()-ts)/1000.0, s[0]/(double)s[5]);
    }
    public static void main(String[] args) {
        for (int s : new int[]{256, 512, 1024}) run(s, 0.15);
    }
}
