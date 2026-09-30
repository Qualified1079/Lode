import java.util.*;
import java.util.zip.Deflater;

// Voxel-encoding sizing sim for Minecraft-shaped terrain.
// Generates synthetic layered terrain with caves, encodes with each candidate
// scheme, reports bytes. Runs at 256/512/1024 block sides to fit scaling curves.

public class VoxSim {

    static final byte AIR = 0, BEDROCK = 1, DEEPSLATE = 2, STONE = 3,
                       DIRT = 4, GRASS = 5, WATER = 6, ORE = 7,
                       WOOD = 8, LEAVES = 9;
    static final int Y_MIN = -64, Y_MAX = 320, HEIGHT = Y_MAX - Y_MIN; // 384
    static final int SEA = 62;

    // ------------ terrain generator (deterministic hash-based noise) ------------

    static int hash(int x, int z, int salt) {
        int h = x * 374761393 + z * 668265263 + salt * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    static double noise(int x, int z, int octave, int salt) {
        int p = 1 << octave;
        int x0 = Math.floorDiv(x, p), z0 = Math.floorDiv(z, p);
        double fx = (x - x0 * p) / (double) p, fz = (z - z0 * p) / (double) p;
        double a = (hash(x0,   z0,   salt) & 0xFFFF) / 65535.0;
        double b = (hash(x0+1, z0,   salt) & 0xFFFF) / 65535.0;
        double c = (hash(x0,   z0+1, salt) & 0xFFFF) / 65535.0;
        double d = (hash(x0+1, z0+1, salt) & 0xFFFF) / 65535.0;
        double sx = fx*fx*(3-2*fx), sz = fz*fz*(3-2*fz);
        return (a*(1-sx) + b*sx) * (1-sz) + (c*(1-sx) + d*sx) * sz;
    }

    static int surfaceY(int x, int z) {
        double h = 0;
        h += noise(x, z, 7, 1) * 40;
        h += noise(x, z, 5, 2) * 15;
        h += noise(x, z, 3, 3) * 5;
        return 60 + (int) h; // roughly 40..120
    }

    // spherical cave carve: check whether (x,y,z) falls inside any nearby cave sphere
    static boolean isCave(int x, int y, int z, double density) {
        // grid of potential cave centers every 24 blocks; each has ~density chance to exist,
        // and radius drawn from hash. Cheap and terrain-shaped.
        int GS = 24;
        int gx = Math.floorDiv(x, GS), gy = Math.floorDiv(y, GS), gz = Math.floorDiv(z, GS);
        for (int dx = -1; dx <= 1; dx++)
        for (int dy = -1; dy <= 1; dy++)
        for (int dz = -1; dz <= 1; dz++) {
            int cx = (gx+dx)*GS, cy = (gy+dy)*GS, cz = (gz+dz)*GS;
            int h1 = hash(cx, cz, cy*7919 + 11);
            double p = (h1 & 0xFFFF) / 65535.0;
            if (p > density) continue;
            int h2 = hash(cx, cz, cy*7919 + 23);
            double jx = ((h2       & 0xFF) - 128) / 20.0;
            double jy = (((h2>>>8) & 0xFF) - 128) / 20.0;
            double jz = (((h2>>>16)& 0xFF) - 128) / 20.0;
            double ox = cx + jx*GS, oy = cy + jy*GS, oz = cz + jz*GS;
            int h3 = hash(cx, cz, cy*7919 + 41);
            double r = 4 + ((h3 & 0xFF) / 255.0) * 8; // radius 4..12
            double d2 = (x-ox)*(x-ox) + (y-oy)*(y-oy)*3 + (z-oz)*(z-oz); // squish vertically
            if (d2 < r*r) return true;
        }
        return false;
    }

    static byte blockAt(int x, int y, int z, double caveDensity) {
        if (y < Y_MIN + 5) return BEDROCK;
        int sy = surfaceY(x, z);
        if (y > sy) return (y <= SEA) ? WATER : AIR;
        if (isCave(x, y, z, caveDensity) && y < sy - 2 && y > Y_MIN + 5) return AIR;
        if (y == sy) return (sy <= SEA + 1) ? DIRT : GRASS;
        if (y > sy - 4) return DIRT;
        if (y < 0) return DEEPSLATE;
        // sparse ore
        if ((hash(x, z, y*31) & 0x3FF) == 0) return ORE;
        return STONE;
    }

    // Generate full (side x HEIGHT x side) volume as a flat byte[] indexed [x*side*H + z*H + y_off].
    // y_off = y - Y_MIN.
    static byte[] generate(int side, double caveDensity) {
        byte[] v = new byte[side * side * HEIGHT];
        for (int x = 0; x < side; x++)
        for (int z = 0; z < side; z++) {
            for (int yi = 0; yi < HEIGHT; yi++) {
                int y = yi + Y_MIN;
                v[x * side * HEIGHT + z * HEIGHT + yi] = blockAt(x, y, z, caveDensity);
            }
        }
        return v;
    }
    static byte get(byte[] v, int side, int x, int z, int yi) {
        return v[x * side * HEIGHT + z * HEIGHT + yi];
    }

    // ------------ encoders ------------

    // Vanilla-mca approximation: per 16-block section, palette + packed longs, DEFLATE.
    // Plus 4KB sector padding per non-empty chunk (16x16 xz footprint).
    static long encVanilla(byte[] v, int side) {
        long total = 0;
        int chunks = side / 16;
        Deflater d = new Deflater(6);
        for (int cx = 0; cx < chunks; cx++)
        for (int cz = 0; cz < chunks; cz++) {
            byte[] chunkNbtLike = chunkPayload(v, side, cx, cz);
            byte[] out = new byte[chunkNbtLike.length + 64];
            d.reset();
            d.setInput(chunkNbtLike);
            d.finish();
            int len = d.deflate(out);
            // 5-byte header + payload, padded to 4096
            int stored = 5 + len;
            stored = ((stored + 4095) / 4096) * 4096;
            total += stored;
        }
        d.end();
        // 8 KB header per region (32x32 chunks)
        int regions = (chunks + 31) / 32;
        total += (long) regions * regions * 8192L;
        return total;
    }

    static byte[] chunkPayload(byte[] v, int side, int cx, int cz) {
        // one section = 16x16x16 = 4096 voxels
        int sections = HEIGHT / 16;
        // approximate NBT overhead: 32 bytes per section header + palette + data
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(65536);
        for (int sy = 0; sy < sections; sy++) {
            byte[] pal = new byte[16];
            int npal = 0;
            int[] palIdx = new int[256];
            Arrays.fill(palIdx, -1);
            short[] indices = new short[4096];
            boolean anyNonAir = false;
            for (int lx = 0; lx < 16; lx++)
            for (int lz = 0; lz < 16; lz++)
            for (int ly = 0; ly < 16; ly++) {
                int gx = cx*16 + lx, gz = cz*16 + lz, gyi = sy*16 + ly;
                byte b = get(v, side, gx, gz, gyi);
                if (b != AIR) anyNonAir = true;
                int pi = palIdx[b & 0xFF];
                if (pi < 0) {
                    if (npal == pal.length) pal = Arrays.copyOf(pal, pal.length*2);
                    pal[npal] = b;
                    pi = npal++;
                    palIdx[b & 0xFF] = pi;
                }
                indices[((lx*16)+lz)*16 + ly] = (short) pi;
            }
            if (!anyNonAir) continue; // skip empty sections
            // section header + palette
            bo.write(32); // fake NBT overhead
            for (int i = 0; i < 31; i++) bo.write(0);
            bo.write(npal);
            for (int i = 0; i < npal; i++) bo.write(pal[i]);
            // pack indices at ceil(log2(npal)) bits, min 4
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, npal-1)));
            if (npal <= 1) continue; // whole-section fill, no data needed
            int longs = (4096 * bits + 63) / 64;
            bo.write(new byte[longs * 8], 0, longs * 8);
        }
        return bo.toByteArray();
    }

    // Column-RLE: per (x,z) column, (blockId, runLen) pairs. Varint encoded.
    // Plus a small per-chunk header. No further compression.
    static long encColumnRLE(byte[] v, int side) {
        long total = 0;
        int chunks = side / 16;
        for (int cx = 0; cx < chunks; cx++)
        for (int cz = 0; cz < chunks; cz++) {
            long chunkBytes = 4; // chunk header
            for (int lx = 0; lx < 16; lx++)
            for (int lz = 0; lz < 16; lz++) {
                int gx = cx*16 + lx, gz = cz*16 + lz;
                byte prev = get(v, side, gx, gz, 0);
                int run = 1;
                int runs = 0;
                for (int yi = 1; yi < HEIGHT; yi++) {
                    byte cur = get(v, side, gx, gz, yi);
                    if (cur == prev) { run++; continue; }
                    chunkBytes += 1 + varintLen(run);
                    runs++;
                    prev = cur; run = 1;
                }
                chunkBytes += 1 + varintLen(run);
                runs++;
            }
            total += chunkBytes;
        }
        int regions = (chunks + 31) / 32;
        total += (long) regions * regions * 512L; // small region header
        return total;
    }

    static int varintLen(int n) {
        int len = 1;
        while (n >= 0x80) { n >>>= 7; len++; }
        return len;
    }

    // Plain octree over each 512^3 super-region. For side < 512, use one octree per chunk region.
    // Node cost: leaf = 1 byte (block id), internal = 1 byte child mask + 8 * 4-byte offsets = 33 bytes
    // (uniform-cube leaves collapse; internal splits when children differ).
    static long encOctree(byte[] v, int side) {
        // Octree the whole side x HEIGHT x side volume as one tree.
        // Round side up to next power of 2, same for height dimension.
        int dim = nextPow2(Math.max(side, HEIGHT));
        long[] stats = new long[2]; // {internal, leaf}
        // Use iterative subdivision via bounds; represent absence with default AIR.
        octreeCount(v, side, 0, 0, 0, dim, stats);
        return stats[0] * 33L + stats[1] * 1L + 32L; // 32B root header
    }

    static int nextPow2(int n) { int p = 1; while (p < n) p <<= 1; return p; }

    // Returns dominant block id if the (x0..x0+dim) cube is uniform, else -1.
    // Increments stats: [internal, leaf].
    static int octreeCount(byte[] v, int side, int x0, int y0, int z0, int dim, long[] stats) {
        if (dim == 1) {
            stats[1]++;
            return octreeSample(v, side, x0, y0, z0);
        }
        int half = dim >>> 1;
        int uniform = -1;
        boolean first = true;
        int[] children = new int[8];
        long saveInternal = stats[0], saveLeaf = stats[1];
        for (int oi = 0; oi < 8; oi++) {
            int ox = (oi & 1) * half;
            int oy = ((oi >>> 1) & 1) * half;
            int oz = ((oi >>> 2) & 1) * half;
            int cid = octreeCount(v, side, x0+ox, y0+oy, z0+oz, half, stats);
            children[oi] = cid;
            if (first) { uniform = cid; first = false; }
            else if (cid != uniform) uniform = -2;
        }
        if (uniform >= 0) {
            // collapse: undo the child accounting, count as one leaf
            stats[0] = saveInternal;
            stats[1] = saveLeaf + 1;
            return uniform;
        }
        stats[0]++;
        return -1;
    }

    // Sample the majority block in the [x0..x0+1)³ region — trivially just one voxel.
    // Bounds outside the world default to AIR.
    static int octreeSample(byte[] v, int side, int x, int y, int z) {
        if (x >= side || z >= side || y >= HEIGHT) return AIR & 0xFF;
        return get(v, side, x, z, y) & 0xFF;
    }

    // SVDAG: hash-cons octree subtrees. Cost model: each unique internal node = 8 * 4B refs + 1B mask,
    // each unique leaf = 1B (block id). Plus a small root header.
    static long encSVDAG(byte[] v, int side) {
        int dim = nextPow2(Math.max(side, HEIGHT));
        java.util.HashMap<Long, Integer> nodePool = new java.util.HashMap<>();
        long[] stats = new long[1]; // reserved
        int rootId = svdagBuild(v, side, 0, 0, 0, dim, nodePool);
        // nodePool holds all unique subtrees (leaves as small IDs 0..255, internal as pool entries)
        long internal = nodePool.size();
        // leaf ids 0..255 fixed cost — 10 possible blocks used, so ~10B
        return internal * 33L + 10L + 32L;
    }

    // Returns a subtree ID: 0..255 for leaves (block id + 1 to keep positive), 256+ for internal (256 + pool index).
    static int svdagBuild(byte[] v, int side, int x0, int y0, int z0, int dim,
                          java.util.HashMap<Long, Integer> pool) {
        if (dim == 1) {
            return (octreeSample(v, side, x0, y0, z0) & 0xFF) + 1;
        }
        int half = dim >>> 1;
        int[] c = new int[8];
        int uniform = -1;
        boolean first = true;
        for (int oi = 0; oi < 8; oi++) {
            int ox = (oi & 1) * half;
            int oy = ((oi >>> 1) & 1) * half;
            int oz = ((oi >>> 2) & 1) * half;
            c[oi] = svdagBuild(v, side, x0+ox, y0+oy, z0+oz, half, pool);
            if (first) { uniform = c[oi]; first = false; }
            else if (c[oi] != uniform) uniform = -2;
        }
        if (uniform >= 0) return uniform; // subtree is uniform, collapse to that leaf
        // hash-cons: key on the 8 child ids (each fits in 20 bits comfortably for these tree sizes)
        long key = 0;
        for (int i = 0; i < 8; i++) key = key * 1099511628211L + c[i] + 1;
        Integer existing = pool.get(key);
        if (existing != null) return existing + 256;
        int id = pool.size();
        pool.put(key, id);
        return id + 256;
    }

    // Roaring-per-type: for each block type, gather sorted voxel index list; for each 65536-index
    // page choose min(array 2B/elem, bitmap 8KB, RLE ~ 4B/run).
    // NB: full Roaring uses 16-bit values inside a 65536-page; that's fine here.
    static long encRoaring(byte[] v, int side) {
        int voxels = side * side * HEIGHT;
        // count per type
        int[] typeCount = new int[256];
        for (int i = 0; i < voxels; i++) typeCount[v[i] & 0xFF]++;
        // for each type present, page through the index space
        long total = 32; // header
        int pages = (voxels + 65535) / 65536;
        for (int t = 0; t < 256; t++) {
            if (typeCount[t] == 0) continue;
            long typeBytes = 16; // type header + page index skeleton
            int[] pagePop = new int[pages];
            int[] pageRuns = new int[pages];
            byte cur = 99;
            for (int i = 0; i < voxels; i++) {
                int b = v[i] & 0xFF;
                if (b == t) {
                    pagePop[i >>> 16]++;
                    if ((v[i] & 0xFF) != cur) pageRuns[i >>> 16]++;
                }
                cur = (byte) b;
            }
            for (int p = 0; p < pages; p++) {
                int pop = pagePop[p];
                if (pop == 0) continue;
                long arr = pop * 2L;
                long bmp = 8192L;
                long rle = pageRuns[p] * 4L;
                typeBytes += 4 + Math.min(arr, Math.min(bmp, rle));
            }
            total += typeBytes;
        }
        return total;
    }

    // Surface encoding: for each connected component of a monoblock type with volume > 4096,
    // cost = face count * 2 bytes (each boundary quad) + 32 bytes component metadata.
    // Everything else falls back to column-RLE for that region (approximated).
    // We approximate connected-component analysis by treating each block type as one blob and
    // counting all interface faces (overestimates when a type is fragmented, but bounds surface cost).
    static long encSurface(byte[] v, int side) {
        long total = 32;
        int voxels = side * side * HEIGHT;
        long[] typeVol = new long[256];
        for (int i = 0; i < voxels; i++) typeVol[v[i] & 0xFF]++;
        long faceCount = 0;
        for (int x = 0; x < side; x++)
        for (int z = 0; z < side; z++)
        for (int yi = 0; yi < HEIGHT; yi++) {
            byte b = get(v, side, x, z, yi);
            if (b == AIR) continue;
            // check 6 neighbors
            if (yi+1 < HEIGHT && get(v,side,x,z,yi+1) != b) faceCount++;
            if (yi   > 0     && get(v,side,x,z,yi-1) != b) faceCount++;
            if (x+1 < side   && get(v,side,x+1,z,yi) != b) faceCount++;
            if (x   > 0      && get(v,side,x-1,z,yi) != b) faceCount++;
            if (z+1 < side   && get(v,side,x,z+1,yi) != b) faceCount++;
            if (z   > 0      && get(v,side,x,z-1,yi) != b) faceCount++;
        }
        total += faceCount * 2L; // 2 bytes per face record (position hash + block id)
        for (int t = 0; t < 256; t++) if (typeVol[t] > 0) total += 32;
        return total;
    }

    // ------------ runner ------------

    static void run(int side, double caveDensity) {
        System.out.printf("%n=== side=%d, cave_density=%.3f (%.1fM voxels) ===%n",
            side, caveDensity, side * (long) side * HEIGHT / 1e6);
        long t0 = System.currentTimeMillis();
        byte[] v = generate(side, caveDensity);
        long tGen = System.currentTimeMillis() - t0;
        System.out.printf("generated in %.1fs%n", tGen / 1000.0);

        long[] sizes = new long[6];
        String[] names = {"Vanilla-mca~", "Column-RLE", "Octree", "SVDAG", "Roaring-per-type", "Surface"};
        long ts;

        ts = System.currentTimeMillis(); sizes[0] = encVanilla(v, side);
        System.out.printf("  Vanilla-mca~      : %12d bytes  (%.1fs)%n", sizes[0], (System.currentTimeMillis()-ts)/1000.0);

        ts = System.currentTimeMillis(); sizes[1] = encColumnRLE(v, side);
        System.out.printf("  Column-RLE        : %12d bytes  (%.1fs)  ratio-vs-vanilla %.1fx%n",
            sizes[1], (System.currentTimeMillis()-ts)/1000.0, sizes[0] / (double) sizes[1]);

        ts = System.currentTimeMillis(); sizes[2] = encOctree(v, side);
        System.out.printf("  Octree            : %12d bytes  (%.1fs)  ratio-vs-vanilla %.1fx%n",
            sizes[2], (System.currentTimeMillis()-ts)/1000.0, sizes[0] / (double) sizes[2]);

        ts = System.currentTimeMillis(); sizes[3] = encSVDAG(v, side);
        System.out.printf("  SVDAG             : %12d bytes  (%.1fs)  ratio-vs-vanilla %.1fx%n",
            sizes[3], (System.currentTimeMillis()-ts)/1000.0, sizes[0] / (double) sizes[3]);

        ts = System.currentTimeMillis(); sizes[4] = encRoaring(v, side);
        System.out.printf("  Roaring-per-type  : %12d bytes  (%.1fs)  ratio-vs-vanilla %.1fx%n",
            sizes[4], (System.currentTimeMillis()-ts)/1000.0, sizes[0] / (double) sizes[4]);

        ts = System.currentTimeMillis(); sizes[5] = encSurface(v, side);
        System.out.printf("  Surface           : %12d bytes  (%.1fs)  ratio-vs-vanilla %.1fx%n",
            sizes[5], (System.currentTimeMillis()-ts)/1000.0, sizes[0] / (double) sizes[5]);
    }

    public static void main(String[] args) {
        // sweep sizes at moderate cave density (roughly Minecraft-realistic)
        double cave = 0.15;
        int[] sides = {256, 512, 1024};
        for (int s : sides) run(s, cave);

        // sensitivity: high-cave world at 512
        System.out.println("\n--- high cave density check ---");
        run(512, 0.35);
    }
}
