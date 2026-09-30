import java.io.*; import java.util.*; import java.util.zip.GZIPInputStream;
// Compare two LightDump outputs chunk-by-chunk. Reports sky/block nibble mismatches, delta histograms,
// and how mismatches distribute by distance from the edge of the dumped square.
public class LightCmp {
  public static void main(String[] a) throws Exception {
    int per = Integer.parseInt(a[2]) * 256, mn = Integer.parseInt(a[3]), mx = Integer.parseInt(a[4]);
    DataInputStream A = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(a[0]), 1 << 16), 1 << 20));
    DataInputStream B = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(a[1]), 1 << 16), 1 << 20));
    byte[] ba = new byte[per], bb = new byte[per];
    long chunks = 0, total = 0, skyD = 0, blkD = 0, chSky = 0, chBlk = 0;
    TreeMap<Integer, Long> skyH = new TreeMap<>(), blkH = new TreeMap<>(), ring = new TreeMap<>(), ringCh = new TreeMap<>();
    while (true) {
      int ax, az, bx, bz;
      try { ax = A.readInt(); az = A.readInt(); } catch (EOFException e) { break; }
      bx = B.readInt(); bz = B.readInt();
      if (ax != bx || az != bz) throw new IllegalStateException("desync " + ax + "," + az + " vs " + bx + "," + bz);
      A.readFully(ba); B.readFully(bb);
      chunks++; total += per;
      int d = Math.min(Math.min(ax - mn, mx - ax), Math.min(az - mn, mx - az));
      int r = Math.min(d, 4);
      ringCh.merge(r, 1L, Long::sum);
      boolean cs = false, cb = false;
      for (int i = 0; i < per; i++) {
        if (ba[i] == bb[i]) continue;
        int sa = (ba[i] >> 4) & 15, sb = (bb[i] >> 4) & 15, la = ba[i] & 15, lb = bb[i] & 15;
        if (sa != sb) { skyD++; cs = true; skyH.merge(sb - sa, 1L, Long::sum); ring.merge(r, 1L, Long::sum); }
        if (la != lb) { blkD++; cb = true; blkH.merge(lb - la, 1L, Long::sum); }
      }
      if (cs) chSky++; if (cb) chBlk++;
    }
    System.out.printf("chunks compared: %,d   blocks each: %,d%n", chunks, total);
    System.out.printf("sky light  : %,d differ (%.5f%%) in %,d chunks; delta(B-A) %s%n", skyD, 100.0 * skyD / total, chSky, skyH);
    System.out.printf("block light: %,d differ (%.5f%%) in %,d chunks; delta(B-A) %s%n", blkD, 100.0 * blkD / total, chBlk, blkH);
    System.out.printf("sky mismatches by distance from dump edge (0=edge chunk, 4=4+): %s   chunks per ring: %s%n", ring, ringCh);
  }
}
