package com.qutc.launcher.ui.components;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;

/**
 * 2D 全身皮肤展示（正视图）：头、身体、双臂、双腿按皮肤贴图拼成正视图，
 * 并把帽子/外套/袖子/裤腿的叠加层按 alpha 合成进去。
 */
public final class SkinFlat2D extends StackPane {

    private static final int W = 16;
    private static final int H = 32;

    private final ImageView view = new ImageView();

    public SkinFlat2D(double width, double height) {
        getStyleClass().add("skin-stage");
        setMinSize(width, height);
        setPrefSize(width, height);
        setMaxSize(width, height);
        view.setSmooth(false);
        view.setPreserveRatio(true);
        view.setImage(render(null, false));
        view.fitHeightProperty().bind(heightProperty().subtract(18));
        view.fitWidthProperty().bind(widthProperty().subtract(18));
        getChildren().add(view);
    }

    public void setSkin(Image skin, boolean slim) {
        view.setImage(render(skin, slim));
    }

    /** 生成 16×32 的正视图（1 像素 = 1 皮肤像素），再交给 ImageView 最近邻放大。 */
    private static Image render(Image skin, boolean slim) {
        int[] buf = new int[W * H];
        if (skin != null && skin.getPixelReader() != null) {
            PixelReader reader = skin.getPixelReader();
            int armW = slim ? 3 : 4;
            int rightArmX = 4 - armW;
            int leftArmX = 12;
            int leftLegX = 8;

            copy(reader, buf, 8, 8, 8, 8, 4, 0);
            overlay(reader, buf, 40, 8, 8, 8, 4, 0);
            copy(reader, buf, 20, 20, 8, 12, 4, 8);
            overlay(reader, buf, 20, 36, 8, 12, 4, 8);
            copy(reader, buf, 44, 20, armW, 12, rightArmX, 8);
            overlay(reader, buf, 44, 36, armW, 12, rightArmX, 8);
            copy(reader, buf, 36, 52, armW, 12, leftArmX, 8);
            overlay(reader, buf, 52, 52, armW, 12, leftArmX, 8);
            copy(reader, buf, 4, 20, 4, 12, 4, 20);
            overlay(reader, buf, 0, 36, 4, 12, 4, 20);
            copy(reader, buf, 20, 52, 4, 12, leftLegX, 20);
            overlay(reader, buf, 4, 52, 4, 12, leftLegX, 20);
        }
        // 放大 4 倍（最近邻），保证在任何尺寸下都是清晰像素风
        int scale = 4;
        WritableImage out = new WritableImage(W * scale, H * scale);
        PixelWriter writer = out.getPixelWriter();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int argb = buf[y * W + x];
                if ((argb >>> 24) == 0) {
                    continue;
                }
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        writer.setArgb(x * scale + dx, y * scale + dy, argb);
                    }
                }
            }
        }
        return out;
    }

    private static void copy(PixelReader reader, int[] buf,
                             int sx, int sy, int w, int h, int dx, int dy) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = reader.getArgb(sx + x, sy + y);
                if ((argb >>> 24) > 8 && dx + x < W && dy + y < H) {
                    buf[(dy + y) * W + (dx + x)] = argb;
                }
            }
        }
    }

    private static void overlay(PixelReader reader, int[] buf,
                                int sx, int sy, int w, int h, int dx, int dy) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int src = reader.getArgb(sx + x, sy + y);
                int alpha = src >>> 24;
                if (alpha < 8 || dx + x >= W || dy + y >= H) {
                    continue;
                }
                int index = (dy + y) * W + (dx + x);
                if (alpha == 255) {
                    buf[index] = src;
                } else {
                    buf[index] = blend(buf[index], src, alpha);
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
}