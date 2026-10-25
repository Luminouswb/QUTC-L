package com.qutc.launcher.core.skin;

import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 皮肤工具：解析、旧版 64x32 兼容、Slim 判断、默认皮肤。
 *
 * <p>皮肤是 64x64 的「UV 图集」，头/身/手/脚各块在固定像素区域。
 * 这里只提供数据层，3D 建模在 UI 层。
 */
public final class Skins {

    public static final int SIZE = 64;

    private static volatile Image defaultImage;

    private Skins() {
    }

    public static BufferedImage decode(byte[] data) throws IOException {
        if (data == null || data.length < 8) {
            throw new IOException("皮肤文件为空");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("不是有效的图片");
        }
        return image;
    }

    /** 把旧版 64x32 皮肤转成 64x64（复制四肢到新布局），已经是 64x64 的原样返回。 */
    public static BufferedImage normalize(BufferedImage source) {
        if (source == null) {
            return null;
        }
        if (source.getWidth() == SIZE && source.getHeight() == SIZE) {
            return source;
        }
        if (source.getWidth() != SIZE || source.getHeight() != 32) {
            return null;
        }
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(source, 0, 0, null);
        copy(source, out, 0, 16, 4, 4, 16, 48);
        copy(source, out, 4, 16, 4, 4, 20, 48);
        copy(source, out, 8, 16, 4, 4, 24, 48);
        copy(source, out, 12, 16, 4, 4, 28, 48);
        copy(source, out, 0, 20, 4, 12, 16, 52);
        copy(source, out, 4, 20, 4, 12, 20, 52);
        copy(source, out, 8, 20, 4, 12, 24, 52);
        copy(source, out, 12, 20, 4, 12, 28, 52);
        g.dispose();
        return out;
    }

    private static void copy(BufferedImage src, BufferedImage dst, int sx, int sy, int w, int h, int dx, int dy) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(sx + x, sy + y);
                if ((argb >>> 24) != 0) {
                    dst.setRGB(dx + x, dy + y, argb);
                }
            }
        }
    }

    /** 判断是否是 Slim（Alex）模型：宽手臂区域右侧应全透明。 */
    public static boolean isSlim(BufferedImage skin) {
        if (skin == null || skin.getWidth() < SIZE || skin.getHeight() < SIZE) {
            return false;
        }
        int transparent = 0;
        int total = 0;
        for (int y = 20; y < 32; y++) {
            for (int x = 47; x < 52; x++) {
                total++;
                if ((skin.getRGB(x, y) >>> 24) == 0) {
                    transparent++;
                }
            }
        }
        return total > 0 && transparent * 100 / total >= 80;
    }

    public static Image toFxImage(BufferedImage image) {
        if (image == null) {
            return null;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        WritableImage fx = new WritableImage(w, h);
        PixelWriter writer = fx.getPixelWriter();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                writer.setArgb(x, y, row[x]);
            }
        }
        return fx;
    }

    /** 没有可用皮肤时的默认皮肤（程序生成的 Steve 风格，不依赖外部资源）。 */
    /**
     * 把 1.8+ 的叠加层（帽子/外套/袖子/裤腿）预先合成到基础层。
     *
     * <p>JavaFX 3D 不做贴图 alpha 混合，半透明 overlay 会遮住底层，
     * 所以这里在 2D 阶段把 overlay 的像素直接画进基础层，3D 用一个不透明贴图。
     */
    public static BufferedImage bakeOverlay(BufferedImage skin) {
        if (skin == null || skin.getWidth() < SIZE || skin.getHeight() < SIZE) {
            return skin;
        }
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(skin, 0, 0, null);
        g.dispose();
        // 帽子层 ×1
        overlay(out, 0, 0, 32, 16, 32, 0);
        // 外套 / 右袖
        overlay(out, 16, 16, 24, 16, 16, 32);
        overlay(out, 40, 16, 16, 16, 40, 32);
        // 左臂 / 左袖在 64x64 皮肤里位于下半区 (y 48..64)
        overlay(out, 32, 48, 16, 16, 48, 48);
        return out;
    }

    private static void overlay(BufferedImage img, int dx, int dy, int w, int h, int sx, int sy) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int src = img.getRGB(sx + x, sy + y);
                int alpha = src >>> 24;
                if (alpha == 0) {
                    continue;
                }
                if (alpha == 255) {
                    img.setRGB(dx + x, dy + y, src);
                } else {
                    int dst = img.getRGB(dx + x, dy + y);
                    img.setRGB(dx + x, dy + y, blend(dst, src, alpha));
                }
            }
        }
    }

    private static int blend(int dst, int src, int srcAlpha) {
        int sr = (src >> 16) & 0xFF;
        int sg = (src >> 8) & 0xFF;
        int sb = src & 0xFF;
        int dr = (dst >> 16) & 0xFF;
        int dg = (dst >> 8) & 0xFF;
        int db = dst & 0xFF;
        int a = srcAlpha;
        int r = (sr * a + dr * (255 - a)) / 255;
        int g = (sg * a + dg * (255 - a)) / 255;
        int b = (sb * a + db * (255 - a)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 没有可用皮肤时的默认皮肤（程序生成的 Steve，64x64 标准布局）。 */
    public static Image defaultSkin() {
        Image cached = defaultImage;
        if (cached != null) {
            return cached;
        }
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Color skinTone = new Color(0xC6, 0x9A, 0x6D);
        Color skinShade = new Color(0xB2, 0x88, 0x5E);
        Color hair = new Color(0x3B, 0x28, 0x1A);
        Color shirt = new Color(0x2E, 0x8B, 0x87);
        Color shirtDark = new Color(0x25, 0x74, 0x70);
        Color pants = new Color(0x3A, 0x3F, 0x7A);
        Color pantsDark = new Color(0x2C, 0x30, 0x60);
        Color shoes = new Color(0x4A, 0x4E, 0x58);

        // 头：脸（正）+ 头发（顶/侧/后）
        fill(img, 8, 8, 8, 8, skinTone);
        fill(img, 8, 8, 8, 2, hair);
        fill(img, 8, 0, 8, 8, hair);
        fill(img, 0, 8, 8, 8, hair);
        fill(img, 16, 8, 8, 8, hair);
        fill(img, 24, 8, 8, 8, hair);
        fill(img, 16, 0, 8, 8, skinShade);
        // 眼睛 / 嘴
        fill(img, 10, 12, 1, 1, new Color(0xEA, 0xF2, 0xFF));
        fill(img, 13, 12, 1, 1, new Color(0xEA, 0xF2, 0xFF));
        fill(img, 10, 13, 1, 1, new Color(0x2E, 0x4C, 0x9A));
        fill(img, 13, 13, 1, 1, new Color(0x2E, 0x4C, 0x9A));
        fill(img, 11, 14, 2, 1, new Color(0x8A, 0x5F, 0x3C));
        // 身体：正面衬衫 + 脖子
        fill(img, 20, 20, 8, 12, shirt);
        fill(img, 20, 20, 8, 2, skinTone);
        fill(img, 32, 20, 8, 12, shirtDark);
        fill(img, 16, 20, 4, 12, shirtDark);
        fill(img, 28, 20, 4, 12, shirtDark);
        fill(img, 20, 16, 8, 4, shirtDark);
        fill(img, 28, 16, 8, 4, shirtDark);
        // 右臂（x44..52）
        arm(img, 44, skinTone, shirt, shirtDark);
        // 左臂（下半区 x36..44）
        arm(img, 36, skinTone, shirt, shirtDark);
        // 右腿（x4..12）
        leg(img, 4, pants, pantsDark, shoes);
        // 左腿（下半区 x20..28）
        leg(img, 20, pants, pantsDark, shoes);
        Image fx = toFxImage(img);
        defaultImage = fx;
        return fx;
    }

    /** 画一条手臂：正面/背面/两侧/顶底，前 8 行袖子、后 3 行皮肤。 */
    private static void arm(BufferedImage img, int x, Color skinTone, Color shirt, Color shirtDark) {
        fill(img, x, 20, 4, 9, shirt);
        fill(img, x, 29, 4, 3, skinTone);
        fill(img, x + 8, 20, 4, 12, shirtDark);
        fill(img, x - 4, 20, 4, 12, shirtDark);
        fill(img, x + 4, 20, 4, 12, shirtDark);
        fill(img, x, 16, 4, 4, shirt);
        fill(img, x + 4, 16, 4, 4, skinTone);
    }

    /** 画一条腿：正面/背面/两侧/顶底，鞋在底部 2 行。 */
    private static void leg(BufferedImage img, int x, Color pants, Color pantsDark, Color shoes) {
        fill(img, x, 20, 4, 10, pants);
        fill(img, x, 30, 4, 2, shoes);
        fill(img, x + 8, 20, 4, 12, pantsDark);
        fill(img, x - 4, 20, 4, 12, pantsDark);
        fill(img, x + 4, 20, 4, 12, pantsDark);
        fill(img, x, 16, 4, 4, pants);
        fill(img, x + 4, 16, 4, 4, shoes);
    }
    private static void fill(BufferedImage img, int x, int y, int w, int h, Color color) {
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(x, y, w, h);
        g.dispose();
    }
}
