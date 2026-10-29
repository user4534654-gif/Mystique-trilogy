package com.bendroid.global.graphics.objects;

public class Point3D {
    public float x;
    public float y;
    public float z;

    public Point3D() {
    }

    public Point3D(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void setPoints(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Point3D add(Point3D to_add) {
        this.x += to_add.x;
        this.y += to_add.y;
        this.z += to_add.z;
        return this;
    }

    public Point3D normalize() {
        return this;
    }
}
