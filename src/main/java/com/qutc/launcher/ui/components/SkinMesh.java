package com.qutc.launcher.ui.components;

import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

/**
 * 皮肤 3D 网格构建：
 * <ul>
 *   <li>{@link #part}：基础层长方体，6 个面各自一张贴图 + 固定明暗；</li>
 *   <li>{@link #pixelPart}：叠加层（帽子/外套/袖子）逐像素面片，只画不透明像素，
 *       避开 JavaFX 3D 不支持贴图 alpha 混合的问题。</li>
 * </ul>
 */
public final class SkinMesh {

    /** 皮肤图集里的像素矩形。 */
    public record Uv(int x, int y, int w, int h) {
    }

    private SkinMesh() {
    }

    /** 基础层：6 个面各自一个 MeshView（不同明暗），返回 Group。 */
    public static Group part(Image skin, int texSize, float w, float h, float d,
                             Uv front, Uv back, Uv left, Uv right, Uv top, Uv bottom) {
        float hw = w / 2f;
        float hh = h / 2f;
        float hd = d / 2f;
        Group group = new Group();
        addFace(group, skin, texSize, front, 1.00f, new float[][]{
                {-hw, -hh, hd}, {hw, -hh, hd}, {hw, hh, hd}, {-hw, hh, hd}});
        addFace(group, skin, texSize, back, 0.78f, new float[][]{
                {hw, -hh, -hd}, {-hw, -hh, -hd}, {-hw, hh, -hd}, {hw, hh, -hd}});
        addFace(group, skin, texSize, right, 0.68f, new float[][]{
                {hw, -hh, hd}, {hw, -hh, -hd}, {hw, hh, -hd}, {hw, hh, hd}});
        addFace(group, skin, texSize, left, 0.62f, new float[][]{
                {-hw, -hh, -hd}, {-hw, -hh, hd}, {-hw, hh, hd}, {-hw, hh, -hd}});
        addFace(group, skin, texSize, top, 0.86f, new float[][]{
                {-hw, hh, hd}, {hw, hh, hd}, {hw, hh, -hd}, {-hw, hh, -hd}});
        addFace(group, skin, texSize, bottom, 0.55f, new float[][]{
                {-hw, -hh, -hd}, {hw, -hh, -hd}, {hw, -hh, hd}, {-hw, -hh, hd}});
        return group;
    }

    /** 叠加层：只给贴图里不透明（alpha>=24）的像素建小方片，往外扩 grow。 */
    public static Group pixelPart(Image skin, int texSize, float w, float h, float d, float grow,
                                  Uv front, Uv back, Uv left, Uv right, Uv top, Uv bottom) {
        float hw = w / 2f + grow;
        float hh = h / 2f + grow;
        float hd = d / 2f + grow;
        Group group = new Group();
        pixelFace(group, skin, texSize, front, new float[][]{
                {-hw, -hh, hd}, {hw, -hh, hd}, {hw, hh, hd}, {-hw, hh, hd}});
        pixelFace(group, skin, texSize, back, new float[][]{
                {hw, -hh, -hd}, {-hw, -hh, -hd}, {-hw, hh, -hd}, {hw, hh, -hd}});
        pixelFace(group, skin, texSize, right, new float[][]{
                {hw, -hh, hd}, {hw, -hh, -hd}, {hw, hh, -hd}, {hw, hh, hd}});
        pixelFace(group, skin, texSize, left, new float[][]{
                {-hw, -hh, -hd}, {-hw, -hh, hd}, {-hw, hh, hd}, {-hw, hh, -hd}});
        pixelFace(group, skin, texSize, top, new float[][]{
                {-hw, hh, hd}, {hw, hh, hd}, {hw, hh, -hd}, {-hw, hh, -hd}});
        pixelFace(group, skin, texSize, bottom, new float[][]{
                {-hw, -hh, -hd}, {hw, -hh, -hd}, {hw, -hh, hd}, {-hw, -hh, hd}});
        return group;
    }

    private static void addFace(Group group, Image skin, int texSize, Uv uv, float tint,
                                float[][] corners) {
        TriangleMesh mesh = new TriangleMesh();
        for (float[] corner : corners) {
            mesh.getPoints().addAll(corner[0], corner[1], corner[2]);
        }
        float u0 = uv.x() / (float) texSize;
        float v0 = uv.y() / (float) texSize;
        float u1 = (uv.x() + uv.w()) / (float) texSize;
        float v1 = (uv.y() + uv.h()) / (float) texSize;
        mesh.getTexCoords().addAll(u0, v1, u1, v1, u1, v0, u0, v0);
        mesh.getFaces().addAll(0, 0, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3);
        MeshView view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        view.setMaterial(material(skin, tint));
        group.getChildren().add(view);
    }

    private static void pixelFace(Group group, Image skin, int texSize, Uv uv, float[][] corners) {
        var reader = skin.getPixelReader();
        if (reader == null) {
            return;
        }
        TriangleMesh mesh = new TriangleMesh();
        float uStep = 1f / texSize;
        for (int py = 0; py < uv.h(); py++) {
            for (int px = 0; px < uv.w(); px++) {
                if ((reader.getArgb(uv.x() + px, uv.y() + py) >>> 24) < 24) {
                    continue;
                }
                float tu0 = px / (float) uv.w();
                float tu1 = (px + 1) / (float) uv.w();
                float tv0 = py / (float) uv.h();
                float tv1 = (py + 1) / (float) uv.h();
                float[] p00 = bilinear(corners, tu0, tv0);
                float[] p10 = bilinear(corners, tu1, tv0);
                float[] p11 = bilinear(corners, tu1, tv1);
                float[] p01 = bilinear(corners, tu0, tv1);
                int base = mesh.getPoints().size() / 3;
                mesh.getPoints().addAll(
                        p00[0], p00[1], p00[2],
                        p10[0], p10[1], p10[2],
                        p11[0], p11[1], p11[2],
                        p01[0], p01[1], p01[2]);
                float u0 = (uv.x() + px) * uStep;
                float u1 = (uv.x() + px + 1) * uStep;
                float v0 = (uv.y() + py) * uStep;
                float v1 = (uv.y() + py + 1) * uStep;
                mesh.getTexCoords().addAll(u0, v1, u1, v1, u1, v0, u0, v0);
                mesh.getFaces().addAll(base, 0, base + 1, 1, base + 2, 2, base, 0, base + 2, 2, base + 3, 3);
            }
        }
        if (mesh.getPoints().size() == 0) {
            return;
        }
        MeshView view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        view.setMaterial(material(skin, 1.0f));
        group.getChildren().add(view);
    }

    private static PhongMaterial material(Image skin, float tint) {
        PhongMaterial material = new PhongMaterial(Color.color(tint, tint, tint));
        material.setDiffuseMap(skin);
        material.setSpecularColor(Color.TRANSPARENT);
        if (System.getProperty("qutc.skin.debug") != null) {
            material.setDiffuseMap(null);
            material.setDiffuseColor(Color.hsb((tint * 360) % 360, 0.6, 0.9));
        }
        return material;
    }

    /** corners 顺序：左下、右下、右上、左上；做双线性插值。 */
    private static float[] bilinear(float[][] corners, float u, float v) {
        float[] result = new float[3];
        for (int i = 0; i < 3; i++) {
            float bottom = corners[0][i] + (corners[1][i] - corners[0][i]) * u;
            float top = corners[3][i] + (corners[2][i] - corners[3][i]) * u;
            result[i] = bottom + (top - bottom) * v;
        }
        return result;
    }
}
