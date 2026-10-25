package com.qutc.launcher.ui.components;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/** 皮肤头部头像：从 64×64 皮肤裁 8×8 脸 + 帽子层，最近邻放大保证像素清晰。 */
public final class SkinHeadView extends StackPane {

    private final javafx.scene.image.ImageView view = new javafx.scene.image.ImageView();
    private final int size;

    public SkinHeadView(double size, double radius) {
        this.size = (int) Math.round(size);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(false);
        view.setSmooth(false);
        view.setMouseTransparent(true);
        getChildren().add(view);

        Rectangle clip = new Rectangle(size, size);
        clip.setArcWidth(radius * 2);
        clip.setArcHeight(radius * 2);
        setClip(clip);
    }

    public void setSkin(Image image) {
        if (image == null) {
            return;
        }
        int s = size;
        WritableImage output = new WritableImage(s, s);
        PixelWriter writer = output.getPixelWriter();
        PixelReader reader = image.getPixelReader();
        if (reader == null) {
            return;
        }
        for (int y = 0; y < s; y++) {
            int sy = 8 + y * 8 / s;
            for (int x = 0; x < s; x++) {
                int sx = 8 + x * 8 / s;
                int base = reader.getArgb(sx, sy);
                int hat = reader.getArgb(40 + x * 8 / s, 8 + y * 8 / s);
                writer.setArgb(x, y, over(hat, base));
            }
        }
        view.setImage(output);
    }

    private static int over(int top, int bottom) {
        int ta = (top >>> 24) & 0xFF;
        if (ta == 0) {
            return bottom;
        }
        if (ta == 255) {
            return top;
        }
        int ba = (bottom >>> 24) & 0xFF;
        double alpha = ta / 255.0 + ba / 255.0 * (1 - ta / 255.0);
        if (alpha <= 0) {
            return 0;
        }
        int r = blend((top >> 16) & 0xFF, (bottom >> 16) & 0xFF, ta, ba, alpha);
        int g = blend((top >> 8) & 0xFF, (bottom >> 8) & 0xFF, ta, ba, alpha);
        int b = blend(top & 0xFF, bottom & 0xFF, ta, ba, alpha);
        return ((int) Math.round(alpha * 255) << 24) | (r << 16) | (g << 8) | b;
    }

    private static int blend(int top, int bottom, int ta, int ba, double alpha) {
        double value = (top * (ta / 255.0) + bottom * (ba / 255.0) * (1 - ta / 255.0)) / alpha;
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }
}
