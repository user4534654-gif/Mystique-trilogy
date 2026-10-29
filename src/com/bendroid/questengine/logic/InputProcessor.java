package com.bendroid.questengine.logic;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.bendroid.global.graphics.objects.Camera;
import com.bendroid.global.graphics.objects.Point2D;
import com.bendroid.global.graphics.objects.Point3D;
import com.bendroid.mystique1.R;
import com.bendroid.questengine.logic.graph.Connector;
import com.bendroid.questengine.logic.graph.Node;

/**
 * Touch handling for the 3D view.
 *
 * Walking is now two dimensional: one axis drives the forward/backward axis of
 * the camera, the other one the sideways axis. Both are fed from the on screen
 * D-pad, so holding two arrows at the same time produces a diagonal.
 */
public class InputProcessor {
    public static final float TURN_VELOCITY = ((float) Math.toRadians(90.0d)) / 480.0f;
    public static final float WALK_VELOCITY = 400.0f;

    /** Walkable room limits on the X/Z plane, same values the original game used. */
    private static final float MIN_X = -140.0f;
    private static final float MAX_X = 40.0f;
    private static final float MIN_Z = -180.0f;
    private static final float MAX_Z = 120.0f;

    /** Speed of one movement axis. Matches the 0.3 of the original up/down buttons. */
    private static final float MOVE_SPEED = 0.3f;

    /** Length of the direction vector below which the input counts as "no input". */
    private static final float EPSILON = 0.0001f;

    private QuestLogic logic;
    private Point2D lastPos = new Point2D();
    private Point2D downPos = new Point2D();
    private long downTime = 0;
    Point3D plus = new Point3D();
    private Camera touchCamera = new Camera();
    private Point3D camRot = new Point3D();

    /** Sideways component: -1 left, 0 none, +1 right. Driven by the D-pad. */
    private float strafeDir = 0.0f;

    public InputProcessor(QuestLogic logic) {
        this.logic = logic;
        this.touchCamera.setParameters(1.0f, 1.0f, 1.0f, 400.0f, 0, 0);
    }

    public void processDown(MotionEvent e) {
        Point2D point2D = this.lastPos;
        Point2D point2D2 = this.downPos;
        float x = e.getX();
        point2D2.x = x;
        point2D.x = x;
        Point2D point2D3 = this.lastPos;
        Point2D point2D4 = this.downPos;
        float y = e.getY();
        point2D4.y = y;
        point2D3.y = y;
        this.downTime = SystemClock.uptimeMillis();
    }

    public void processUp(MotionEvent e) {
        if (this.logic.getCurrentNode() != null && Math.abs(this.downPos.x - e.getX()) < 25.0f && Math.abs(this.downPos.y - e.getY()) < 25.0f && SystemClock.uptimeMillis() - this.downTime < 300) {
            float angleX = (float) Math.toDegrees((e.getX() - (this.logic.getCamera().getWidth() / 2)) / this.logic.getCamera().getDist());
            float angleY = (float) Math.toDegrees(((this.logic.getCamera().getHeight() / 2) - e.getY()) / this.logic.getCamera().getDist());
            this.camRot.setPoints(this.logic.getCamera().getRotation().x, this.logic.getCamera().getRotation().y, this.logic.getCamera().getRotation().z);
            this.camRot.x += angleX;
            this.camRot.y += angleY;
            Point3D pos = new Point3D(this.logic.getCamera().getPosition().x, this.logic.getCamera().getPosition().y, this.logic.getCamera().getPosition().z);
            this.touchCamera.setPosition(pos);
            this.touchCamera.setRotation(this.camRot);
            float minDist = 10000.0f;
            Connector conn = null;
            for (Connector cur_con : this.logic.getCurrentNode().getConnectors()) {
                if (this.touchCamera.getFrustrum().boxInFrustrum(cur_con.getClickableZone().getCoords()) && cur_con.isActive()) {
                    float dist = cur_con.getClickableZone().getDistance(this.logic.getCamera().getPosition());
                    if (dist < minDist) {
                        conn = cur_con;
                        minDist = dist;
                    }
                }
            }
            if (conn != null) {
                if (minDist <= 100.0f) {
                    this.logic.processResult(conn.exec(), true);
                } else {
                    this.logic.getTextHandler().setText(this.logic.getContext().getResources().getString(R.string.too_far), 1500);
                }
            }
        }
    }

    /**
     * Dragging over the 3D view turns the camera. The signs of both angles are
     * preserved, so the camera can be turned in both directions.
     */
    public void processMove(MotionEvent e) {
        if (this.logic.getCurrentNode() != null && this.logic.getCurrentNode().isProcessInput()) {
            float diffX = this.lastPos.x - e.getX();
            float diffY = this.lastPos.y - e.getY();
            this.lastPos.x = e.getX();
            this.lastPos.y = e.getY();
            float angleXY = (float) Math.atan((-diffX) * TURN_VELOCITY);
            float angleXZ = (float) Math.atan(TURN_VELOCITY * diffY);
            this.logic.getCamera().rotate(angleXY, angleXZ);
        }
    }

    /**
     * Frame driven movement. Uses the sideways component that the D-pad keeps
     * up to date, so a single call already handles diagonals.
     */
    public boolean move(float direction, int delta) {
        return move2D(direction, this.strafeDir, delta);
    }

    /**
     * Moves the camera along a combination of the forward and the sideways
     * axis. The combined direction is normalised, so walking diagonally is not
     * faster than walking straight, the strength of the input controls the
     * speed, and the resulting position is clamped hard to the room limits so
     * the camera can never leave the room.
     */
    public boolean move2D(float forward, float strafe, int delta) {
        if (delta <= 0) {
            return false;
        }
        Node node = this.logic.getCurrentNode();
        if (node == null || !node.isProcessInput()) {
            return false;
        }
        float mag = (float) Math.sqrt((forward * forward) + (strafe * strafe));
        if (mag < EPSILON) {
            return false;
        }
        Camera cam = this.logic.getCamera();
        if (cam == null) {
            return false;
        }
        Point3D look = cam.getLookDirection();
        float fx = look.x;
        float fz = look.z;
        float flen = (float) Math.sqrt((fx * fx) + (fz * fz));
        if (flen < EPSILON) {
            // looking straight up or down, there is no horizontal direction left
            return false;
        }
        fx /= flen;
        fz /= flen;
        // sideways vector: forward rotated by -90 degrees on the XZ plane
        float rx = -fz;
        float rz = fx;

        float f = forward / mag;
        float s = strafe / mag;
        // how hard the stick is pushed, 1.0 is a full walk
        float strength = Math.min(1.0f, mag / MOVE_SPEED);

        float vx = ((fx * f) + (rx * s)) * strength;
        float vz = ((fz * f) + (rz * s)) * strength;

        float scale = (((float) delta) / 1000.0f) * WALK_VELOCITY * MOVE_SPEED;
        vx *= scale;
        vz *= scale;

        Point3D pos = cam.getPosition();
        float nx = pos.x + vx;
        float nz = pos.z + vz;
        if (nx > MAX_X) {
            nx = MAX_X;
        } else if (nx < MIN_X) {
            nx = MIN_X;
        }
        if (nz > MAX_Z) {
            nz = MAX_Z;
        } else if (nz < MIN_Z) {
            nz = MIN_Z;
        }
        pos.x = nx;
        pos.z = nz;
        cam.reinitFrustrum();
        return true;
    }

    public void setStrafe(float value) {
        this.strafeDir = value;
    }

    public float getStrafe() {
        return this.strafeDir;
    }

    /** Pure sideways movement, kept for compatibility with the original class. */
    public void strafe(float direction, int delta) {
        move2D(0.0f, direction, delta);
    }

    public boolean processKeyDown(KeyEvent e, int delta) {
        if (e.getAction() == 0) {
            if (this.logic.hasInited()) {
                if (this.logic.getCurrentNode() != null && this.logic.getCurrentNode().isProcessInput()) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.KEYCODE_DPAD_UP:
                        case KeyEvent.KEYCODE_W:
                            return move2D(1.0f, 0.0f, delta);
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                        case KeyEvent.KEYCODE_S:
                            return move2D(-1.0f, 0.0f, delta);
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                        case KeyEvent.KEYCODE_A:
                            return move2D(0.0f, -1.0f, delta);
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                        case KeyEvent.KEYCODE_D:
                            return move2D(0.0f, 1.0f, delta);
                        default:
                            break;
                    }
                }
            }
            if (e.getKeyCode() == 4) {
                if (this.logic.getInventory().getVisibility() == 0) {
                    this.logic.getInventory().hide();
                } else {
                    this.logic.getContext().confirmExit();
                }
                return true;
            }
        }
        return false;
    }
}
