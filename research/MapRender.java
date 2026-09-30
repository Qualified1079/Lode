import io.github.ensgijs.nbt.mca.*;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

// Renders a REAL .mca region file to a top-down PNG by reading it back through the MC library.
// No game client, no account needed. Proves the written file is genuinely parseable as Minecraft
// world data and shows whether the terrain looks like a plausible world.
//
// Two outputs: a surface-colour map (what a map renderer shows) and a heightmap.

public class MapRender {
    // approximate Minecraft map colours per block
    static final Map<String,Integer> COL=new HashMap<>();
    static {
        COL.put("minecraft:grass_block",0x7FB238);
        COL.put("minecraft:stone",0x707070);
        COL.put("minecraft:dirt",0x976D4D);
        COL.put("minecraft:sand",0xF7E9A3);
        COL.put("minecraft:water",0x4040FF);
        COL.put("minecraft:snow_block",0xFFFFFF);
        COL.put("minecraft:clay",0xA4A8B8);
        COL.put("minecraft:gravel",0x84868B);
        COL.put("minecraft:oak_log",0x684E31);
        COL.put("minecraft:oak_leaves",0x4A7A2C);
        COL.put("minecraft:oak_planks",0xB8945F);
        COL.put("minecraft:cobblestone",0x7A7A7A);
        COL.put("minecraft:glass",0xD0E8F0);
        COL.put("minecraft:bedrock",0x333333);
        COL.put("minecraft:deepslate",0x4C4C4F);
        COL.put("minecraft:andesite",0x8A8A8A);
        COL.put("minecraft:granite",0x9A6B5B);
        COL.put("minecraft:diorite",0xC7C7C7);
        COL.put("minecraft:tuff",0x6B6E63);
        COL.put("minecraft:coal_ore",0x4A4A4A);
        COL.put("minecraft:iron_ore",0xC9B09A);
        COL.put("minecraft:copper_ore",0xC07A50);
        COL.put("minecraft:gold_ore",0xE0C253);
        COL.put("minecraft:diamond_ore",0x5DECF5);
        COL.put("minecraft:redstone_ore",0xC03030);
        COL.put("minecraft:chest",0xA07540);
        COL.put("minecraft:air",0x000000);
    }

    public static void main(String[] a) throws Exception {
        String path = a.length>0 ? a[0] : "mcaout/r.0.0.mca";
        File f=new File(path);
        System.out.printf("reading %s (%,d bytes)%n",path,f.length());
        McaRegionFile mca=McaFileHelpers.read(f);

        int S=512;
        BufferedImage surf=new BufferedImage(S,S,BufferedImage.TYPE_INT_RGB);
        BufferedImage hmap=new BufferedImage(S,S,BufferedImage.TYPE_INT_RGB);
        int[] H=new int[S*S]; java.util.Arrays.fill(H,Integer.MIN_VALUE);
        int chunksRead=0, colsHit=0, minH=999, maxH=-999;
        Map<String,Integer> census=new TreeMap<>();

        for(int cx=0;cx<32;cx++)for(int cz=0;cz<32;cz++){
            TerrainChunk c=mca.getChunk(cx,cz);
            if(c==null) continue;
            chunksRead++;
            int minSec=c.getMinSectionY(), maxSec=c.getMaxSectionY();
            for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){
                // walk down from the top to find the first non-air block
                String top=null; int topY=Integer.MIN_VALUE;
                for(int sy=maxSec; sy>=minSec && top==null; sy--){
                    TerrainSection s=c.getSection(sy);
                    if(s==null) continue;
                    var pc=s.getBlockStates();
                    if(pc==null) continue;
                    for(int ly=15; ly>=0; ly--){
                        CompoundTag st=pc.getByRef(lx,ly,lz);
                        if(st==null) continue;
                        String nm=st.getString("Name");
                        if(nm!=null && !nm.equals("minecraft:air")){
                            top=nm; topY=sy*16+ly; break;
                        }
                    }
                }
                int px=cx*16+lx, pz=cz*16+lz;
                if(top==null){ surf.setRGB(px,pz,0x000000); continue; }
                colsHit++;
                census.merge(top,1,Integer::sum);
                int base=COL.getOrDefault(top,0xFF00FF);   // magenta = unmapped block, a visible bug flag
                // cheap shading from local slope so terrain reads as 3D
                int shade=0;
                if(lx>0){ }
                surf.setRGB(px,pz,base);
                minH=Math.min(minH,topY); maxH=Math.max(maxH,topY);
                H[px*S+pz]=topY;
            }
        }
        // second pass: normalise the heightmap to greyscale now that the range is known
        int[] G=new int[S*S];
        for(int x=0;x<S;x++)for(int z=0;z<S;z++){
            int y=H[x*S+z];
            if(y==Integer.MIN_VALUE){ G[x*S+z]=0; hmap.setRGB(x,z,0x000020); continue; }
            int v=(int)(255.0*(y-minH)/Math.max(1,(maxH-minH)));
            v=Math.max(0,Math.min(255,v));
            G[x*S+z]=v;
            hmap.setRGB(x,z,(v<<16)|(v<<8)|v);
        }
        // relief shading on the surface map using the height field, so it looks like a map render
        BufferedImage shaded=new BufferedImage(S,S,BufferedImage.TYPE_INT_RGB);
        for(int x=0;x<S;x++)for(int z=0;z<S;z++){
            int rgb=surf.getRGB(x,z);
            int h=G[x*S+z];
            int hn = (z>0? G[x*S+(z-1)] : h);
            int hw = (x>0? G[(x-1)*S+z] : h);
            double d=((h-hn)+(h-hw))/2.0;
            double k=1.0+Math.max(-0.5,Math.min(0.5,d*0.16));
            int r=(int)Math.min(255,((rgb>>>16)&0xFF)*k);
            int g=(int)Math.min(255,((rgb>>>8)&0xFF)*k);
            int b=(int)Math.min(255,(rgb&0xFF)*k);
            shaded.setRGB(x,z,(r<<16)|(g<<8)|b);
        }

        ImageIO.write(shaded,"png",new File("render_surface.png"));
        ImageIO.write(hmap,"png",new File("render_height.png"));
        System.out.printf("chunks read: %d / 1024   columns with terrain: %,d / %,d%n",
            chunksRead,colsHit,S*S);
        System.out.printf("surface Y range: %d .. %d (world y %d .. %d)%n",
            minH,maxH,minH-64,maxH-64);
        final int hits=colsHit;
        System.out.println("surface block census (top block per column):");
        census.entrySet().stream()
            .sorted((p,q)->q.getValue()-p.getValue())
            .forEach(e->System.out.printf("   %-28s %,7d  (%.1f%%)%n",
                e.getKey(),e.getValue(),100.0*e.getValue()/hits));
        System.out.println("wrote render_surface.png, render_height.png");
    }
}
