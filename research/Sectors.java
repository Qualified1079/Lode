import java.io.*;
public class Sectors {
  public static void main(String[] a) throws Exception {
    for(String n:new String[]{"r.0.0.mca","r.0.2.mca"}){
      RandomAccessFile f=new RandomAccessFile("mcaout/"+n,"r");
      byte[] loc=new byte[4096]; f.readFully(loc);
      long payload=0, sectors=0, nchunk=0, maxLen=0, minLen=Long.MAX_VALUE;
      int[] histo=new int[10];
      for(int i=0;i<1024;i++){
        int off=((loc[i*4]&0xFF)<<16)|((loc[i*4+1]&0xFF)<<8)|(loc[i*4+2]&0xFF);
        int cnt=loc[i*4+3]&0xFF;
        if(off==0||cnt==0) continue;
        nchunk++; sectors+=cnt;
        histo[Math.min(9,cnt)]++;
        f.seek((long)off*4096);
        int len=f.readInt();           // length of (compressionByte + data)
        payload+=len+4;
        maxLen=Math.max(maxLen,len); minLen=Math.min(minLen,len);
      }
      f.close();
      long alloc=sectors*4096;
      System.out.printf("%s%n  chunks=%d  real payload=%,d B  allocated=%,d B  padding waste=%,d B (%.1f%%)%n",
        n,nchunk,payload,alloc,alloc-payload,100.0*(alloc-payload)/alloc);
      System.out.printf("  payload/chunk avg=%,d B  min=%,d  max=%,d%n",payload/nchunk,minLen,maxLen);
      System.out.print("  sectors/chunk histogram: ");
      for(int s=1;s<10;s++) if(histo[s]>0) System.out.printf("%d->%d  ",s,histo[s]);
      System.out.println();
    }
  }
}
