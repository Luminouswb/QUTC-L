package com.qutc.launcher.ui.components;

import com.qutc.launcher.core.skin.Skins;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

/**
 * 当前账号皮肤的 3D 全身预览（左侧导航栏底部）。
 *
 * <p>用 Minecraft 皮肤的 UV 图集构建头/帽/身/双臂/双腿六个长方体，
 * 像素尺寸直接乘上缩放系数（避免 JavaFX 节点缩放的 pivot 问题），
 * 整体绕 Y 轴缓慢摆动；鼠标拖动可手动旋转。
 */
public final class SkinPreview3D extends Region {

    private static final int TEX = 64;
    private static final float SCALE = 4.6f;
    private static final float CENTER_Y = 73.5f;

    private final Group model = new Group();
    private final Rotate yaw = new Rotate(0, 0, 0, 0, Rotate.Y_AXIS);
    private final SubScene subScene;
    private final javafx.scene.shape.Ellipse shadow = new javafx.scene.shape.Ellipse(46, 7);
    private Timeline timeline;
    private double lastMouseX;

    public SkinPreview3D(double width, double height) {
        setStyle("-fx-background-color: rgba(0, 0, 0, 0.16); -fx-background-radius: 12;");
        setMinSize(width, height);
        setPrefSize(width, height);
        setMaxSize(width, height);

        Group content = new Group();
        model.getTransforms().add(yaw);
        content.getChildren().add(model);

        AmbientLight ambient = new AmbientLight(Color.color(0.95, 0.96, 1.0));
        PointLight key = new PointLight(Color.color(1, 1, 1, 1));
        key.setTranslateX(-80);
        key.setTranslateY(30);
        key.setTranslateZ(-120);
        PointLight fill = new PointLight(Color.color(0.75, 0.8, 0.95, 1));
        fill.setTranslateX(80);
        fill.setTranslateY(-30);
        fill.setTranslateZ(-90);
        content.getChildren().addAll(ambient, key, fill);

        subScene = new SubScene(content, width, height, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.TRANSPARENT);
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(2000);
        camera.setFieldOfView(32);
        camera.setTranslateZ(-262);
        subScene.setCamera(camera);
        setStyle("-fx-background-color: rgba(255, 255, 255, 0.035); -fx-background-radius: 14;");
        shadow.setFill(javafx.scene.paint.Color.rgb(0, 0, 0, 0.55));
        shadow.setEffect(new javafx.scene.effect.GaussianBlur(7));
        getChildren().addAll(shadow, subScene);

        subScene.setOnMousePressed(event -> lastMouseX = event.getSceneX());
        subScene.setOnMouseDragged(event -> {
            double delta = event.getSceneX() - lastMouseX;
            lastMouseX = event.getSceneX();
            stopSwing();
            yaw.setAngle(yaw.getAngle() + delta);
        });

        setSkin(Skins.defaultSkin(), false);
        startSwing();
    }

    @Override
    protected void layoutChildren() {
        if (getWidth() > 0 && getHeight() > 0) {
            subScene.setWidth(getWidth());
            subScene.setHeight(getHeight());
        }
        subScene.relocate(0, 0);
        shadow.setCenterX(getWidth() / 2);
        shadow.setCenterY(getHeight() - 20);
    }

    public void setSkin(Image image, boolean slimModel) {
        apply(image == null ? Skins.defaultSkin() : image, slimModel);
    }

    private void apply(Image image, boolean slimModel) {
        model.getChildren().clear();
        Image tex = pixelate(image, 4);
        int armWidth = slimModel ? 3 : 4;
        int armBack = slimModel ? 51 : 52;

        model.getChildren().addAll(
                // 帽子层（外圈）
                part(tex, 8.6f, 8.6f, 8.6f, 0, -28, 0,
                        new SkinMesh.Uv(40, 8, 8, 8), new SkinMesh.Uv(56, 8, 8, 8),
                        new SkinMesh.Uv(32, 8, 8, 8), new SkinMesh.Uv(48, 8, 8, 8),
                        new SkinMesh.Uv(40, 0, 8, 8), new SkinMesh.Uv(48, 0, 8, 8)),
                // 头
                part(tex, 8, 8, 8, 0, -28, 0,
                        new SkinMesh.Uv(8, 8, 8, 8), new SkinMesh.Uv(24, 8, 8, 8),
                        new SkinMesh.Uv(0, 8, 8, 8), new SkinMesh.Uv(16, 8, 8, 8),
                        new SkinMesh.Uv(8, 0, 8, 8), new SkinMesh.Uv(16, 0, 8, 8)),
                // 身体
                part(tex, 8, 12, 4, 0, -18, 0,
                        new SkinMesh.Uv(20, 20, 8, 12), new SkinMesh.Uv(32, 20, 8, 12),
                        new SkinMesh.Uv(28, 20, 4, 12), new SkinMesh.Uv(16, 20, 4, 12),
                        new SkinMesh.Uv(20, 16, 8, 4), new SkinMesh.Uv(28, 16, 8, 4)),
                // 右臂（画面左侧）
                part(tex, armWidth, 12, 4, -6, -18, 0,
                        new SkinMesh.Uv(44, 20, armWidth, 12),
                        new SkinMesh.Uv(armBack, 20, armWidth, 12),
                        new SkinMesh.Uv(40, 20, 4, 12),
                        new SkinMesh.Uv(48, 20, 4, 12),
                        new SkinMesh.Uv(44, 16, armWidth, 4),
                        new SkinMesh.Uv(48, 16, 4, 4)),
                // 左臂
                part(tex, armWidth, 12, 4, 6, -18, 0,
                        new SkinMesh.Uv(36, 20, armWidth, 12),
                        new SkinMesh.Uv(44, 20, armWidth, 12),
                        new SkinMesh.Uv(40, 20, 4, 12),
                        new SkinMesh.Uv(32, 20, 4, 12),
                        new SkinMesh.Uv(36, 16, armWidth, 4),
                        new SkinMesh.Uv(40, 16, 4, 4)),
                // 右腿
                part(tex, 4, 12, 4, -2, -6, 0,
                        new SkinMesh.Uv(4, 20, 4, 12), new SkinMesh.Uv(12, 20, 4, 12),
                        new SkinMesh.Uv(0, 20, 4, 12), new SkinMesh.Uv(8, 20, 4, 12),
                        new SkinMesh.Uv(4, 16, 4, 4), new SkinMesh.Uv(8, 16, 4, 4)),
                // 左腿
                part(tex, 4, 12, 4, 2, -6, 0,
                        new SkinMesh.Uv(20, 20, 4, 12), new SkinMesh.Uv(28, 20, 4, 12),
                        new SkinMesh.Uv(24, 20, 4, 12), new SkinMesh.Uv(16, 20, 4, 12),
                        new SkinMesh.Uv(20, 16, 4, 4), new SkinMesh.Uv(24, 16, 4, 4)));
    }

    /** 像素单位直接乘缩放，避免节点缩放以包围盒中心为 pivot 造成错位。 */
    private static javafx.scene.Group part(Image image, float w, float h, float d,
                                          float x, float y, float z,
                                          SkinMesh.Uv... uvs) {
        javafx.scene.Group view = SkinMesh.part(image, TEX, w * SCALE, h * SCALE, d * SCALE,
                uvs[0], uvs[1], uvs[2], uvs[3], uvs[4], uvs[5]);
        view.setTranslateX(x * SCALE);
        view.setTranslateY(y * SCALE + CENTER_Y);
        view.setTranslateZ(z * SCALE);
        return view;
    }

    /** 最近邻放大：让 3D 贴图保持像素风，不被线性过滤糊掉。 */
    private static Image pixelate(Image src, int factor) {
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        if (w <= 0 || h <= 0) {
            return src;
        }
        int[] row = new int[w];
        javafx.scene.image.WritableImage out =
                new javafx.scene.image.WritableImage(w * factor, h * factor);
        javafx.scene.image.PixelReader reader = src.getPixelReader();
        javafx.scene.image.PixelWriter writer = out.getPixelWriter();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                row[x] = reader.getArgb(x, y);
            }
            for (int dy = 0; dy < factor; dy++) {
                for (int x = 0; x < w; x++) {
                    int argb = row[x];
                    for (int dx = 0; dx < factor; dx++) {
                        writer.setArgb(x * factor + dx, y * factor + dy, argb);
                    }
                }
            }
        }
        return out;
    }

    private void startSwing() {
        stopSwing();
        timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(yaw.angleProperty(), -18, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.seconds(4), new KeyValue(yaw.angleProperty(), 18, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.seconds(8), new KeyValue(yaw.angleProperty(), -18, Interpolator.EASE_BOTH)));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    private void stopSwing() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
    }
}
