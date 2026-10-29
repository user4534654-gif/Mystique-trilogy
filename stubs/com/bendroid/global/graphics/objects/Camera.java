package com.bendroid.global.graphics.objects;

public class Camera {
    public Point3D getPosition() {
        return null;
    }

    public Point3D getLookDirection() {
        return null;
    }

    public Point3D getRotation() {
        return null;
    }

    public Frustrum getFrustrum() {
        return null;
    }

    public void setPosition(Point3D newPos) {
    }

    public void setRotation(Point3D rotation) {
    }

    public void rotate(float angleXY, float angleXZ) {
    }

    public void reinitFrustrum() {
    }

    public void setParameters(float FOV, float ratio, float nearP, float farP, int width, int height) {
    }

    public int getWidth() {
        return 0;
    }

    public int getHeight() {
        return 0;
    }

    public float getDist() {
        return 0.0f;
    }
}
