package com.qutc.launcher.ui.components;

import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

/**
 * 把皮肤的一个长方体部件构建成 6 个独立面（每面单独材质 + 固定明暗），
 * 这样即使皮肤本身是大面积白色，也能看清头/身/四肢的轮廓。
 */
public final class SkinMesh {

    /** 皮肤图集里的像素矩形。 */
    public record Uv(int x, int y, int w, int h) {
    }

    private SkinMesh() {
    }

    public static Group part(Image skin, int texSize, float w, float h, float d,
                             Uv front, Uv back, Uv left, Uv right, Uv top, Uv bottom) {
        float hw = w / 2f;
        float hh = h / 2f;
        float hd = d / 2f;

        Group group = new Group();
        addFace(group, skin, texSize, front, 100, new float[][]{
                {-hw, -hh, hd}, {hw, -hh, hd}, {hw, hh, hd}, {-hw, hh, hd}});
        addFace(group, skin, texSize, back, 78, new float[][]{
                {hw, -hh, -hd}, {-hw, -hh, -hd}, {-hw, hh, -hd}, {hw, hh, -hd}});
        addFace(group, skin, texSize, right, 68, new float[][]{
                {hw, -hh, hd}, {hw, -hh, -hd}, {hw, hh, -hd}, {hw, hh, hd}});
        addFace(group, skin, texSize, left, 62, new float[][]{
                {-hw, -hh, -hd}, {-hw, -hh, hd}, {-hw, hh, hd}, {-hw, hh, -hd}});
        addFace(group, skin, texSize, top, 86, new float[][]{
                {-hw, hh, hd}, {hw, hh, hd}, {hw, hh, -hd}, {-hw, hh, -hd}});
        addFace(group, skin, texSize, bottom, 55, new float[][]{
                {-hw, -hh, -hd}, {hw, -hh, -hd}, {hw, -hh, hd}, {-hw, -hh, hd}});
        return group;
    }

    private static void addFace(Group group, Image skin, int texSize, Uv uv, int brightness,
                                float[][] corners) {
        TriangleMesh mesh = new TriangleMesh();
        for (float[] corner : corners) {
            mesh.getPoints().addAll(corner[0], corner[1], corner[2]);
        }
        // 顶点顺序：左下、右下、右上、左上 → UV 同步（左下取贴图底边）
        float u0 = uv.x() / (float) texSize;
        float v0 = uv.y() / (float) texSize;
        float u1 = (uv.x() + uv.w()) / (float) texSize;
        float v1 = (uv.y() + uv.h()) / (float) texSize;
        mesh.getTexCoords().addAll(u0, v1, u1, v1, u1, v0, u0, v0);
        mesh.getFaces().addAll(0, 0, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3);

        float tint = brightness / 100f;
        PhongMaterial material = new PhongMaterial(Color.color(tint, tint, tint));
        material.setDiffuseMap(skin);
        material.setSpecularColor(Color.TRANSPARENT);
        if (System.getProperty("qutc.skin.debug") != null) {
            material.setDiffuseMap(null);
            material.setDiffuseColor(Color.hsb((brightness * 37) % 360, 0.65, 0.95));
        }
        MeshView view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        view.setMaterial(material);
        group.getChildren().add(view);
    }
}
