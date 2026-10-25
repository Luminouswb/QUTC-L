package com.qutc.launcher.ui.components;

import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

import java.util.ArrayList;
import java.util.List;

/** 把皮肤的一个长方体部件（带 6 面 UV）构建成 JavaFX 3D 网格。 */
public final class SkinMesh {

    /** 皮肤图集里的像素矩形。 */
    public record Uv(int x, int y, int w, int h) {
    }

    private SkinMesh() {
    }

    public static MeshView part(Image skin, int texSize, float w, float h, float d,
                                Uv front, Uv back, Uv left, Uv right, Uv top, Uv bottom) {
        TriangleMesh mesh = new TriangleMesh();
        List<Float> points = new ArrayList<>();
        List<Float> texCoords = new ArrayList<>();
        List<Integer> faces = new ArrayList<>();

        float hw = w / 2f;
        float hh = h / 2f;
        float hd = d / 2f;

        face(points, texCoords, faces, texSize, front, new float[][]{
                {-hw, -hh, hd}, {hw, -hh, hd}, {hw, hh, hd}, {-hw, hh, hd}});
        face(points, texCoords, faces, texSize, back, new float[][]{
                {hw, -hh, -hd}, {-hw, -hh, -hd}, {-hw, hh, -hd}, {hw, hh, -hd}});
        face(points, texCoords, faces, texSize, right, new float[][]{
                {hw, -hh, hd}, {hw, -hh, -hd}, {hw, hh, -hd}, {hw, hh, hd}});
        face(points, texCoords, faces, texSize, left, new float[][]{
                {-hw, -hh, -hd}, {-hw, -hh, hd}, {-hw, hh, hd}, {-hw, hh, -hd}});
        face(points, texCoords, faces, texSize, top, new float[][]{
                {-hw, hh, hd}, {hw, hh, hd}, {hw, hh, -hd}, {-hw, hh, -hd}});
        face(points, texCoords, faces, texSize, bottom, new float[][]{
                {-hw, -hh, -hd}, {hw, -hh, -hd}, {hw, -hh, hd}, {-hw, -hh, hd}});

        mesh.getPoints().addAll(toArray(points));
        mesh.getTexCoords().addAll(toArray(texCoords));
        mesh.getFaces().addAll(toIntArray(faces));

        PhongMaterial material = new PhongMaterial(Color.WHITE);
        material.setDiffuseMap(skin);
        material.setSpecularColor(Color.TRANSPARENT);
        MeshView view = new MeshView(mesh);
        view.setMaterial(material);
        view.setCullFace(CullFace.NONE);
        return view;
    }

    private static void face(List<Float> points, List<Float> texCoords, List<Integer> faces,
                             int texSize, Uv uv, float[][] corners) {
        int start = points.size() / 3;
        for (float[] corner : corners) {
            points.add(corner[0]);
            points.add(corner[1]);
            points.add(corner[2]);
        }
        float u0 = uv.x() / (float) texSize;
        float v0 = uv.y() / (float) texSize;
        float u1 = (uv.x() + uv.w()) / (float) texSize;
        float v1 = (uv.y() + uv.h()) / (float) texSize;
        // 顶点顺序：左下、右下、右上、左上；每个三角形是 (点, 纹理, 点, 纹理, 点, 纹理)
        texCoords.addAll(List.of(u0, v1, u1, v1, u1, v0, u0, v0));
        faces.addAll(List.of(
                start, start, start + 1, start + 1, start + 2, start + 2,
                start, start, start + 2, start + 2, start + 3, start + 3));
    }

    private static float[] toArray(List<Float> values) {
        float[] array = new float[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }
}
