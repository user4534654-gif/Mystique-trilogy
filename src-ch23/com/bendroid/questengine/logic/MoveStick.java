package com.bendroid.questengine.logic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import com.bendroid.questengine.QuestEngine;
import com.bendroid.questengine.logic.graph.Node;

/**
 * Virtual analog stick for walking.
 *
 * Dragging the knob away from the centre walks into that direction, so all
 * eight directions including the diagonals are reachable with a single finger.
 * The further the knob is pushed, the faster the player walks.
 *
 * The direction is split into the forward axis (handled by the game itself)
 * and the sideways axis (handled by the InputProcessor), both normalised
 * together, so walking diagonally is exactly as fast as walking straight.
 */
public class MoveStick extends View {

    /** Walking strength when the stick is pushed fully to one side. */
    private static final float STEP = 0.3f;

    /** Fraction of the radius that is ignored, so the player does not drift. */
    private static final float DEADZONE = 0.12f;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable upArrow;
    private final Drawable downArrow;

    /** Knob offset from the centre of the stick, in pixels. */
    private float knobX;
    private float knobY;
    private float radius;
    private float arrowSize;
    private boolean active;

    public MoveStick(Context context) {
        super(context);
        this.upArrow = load(context, "btn_up");
        this.downArrow = load(context, "btn_down");
        this.ringPaint.setStyle(Paint.Style.STROKE);
        this.ringPaint.setColor(0x66FFFFFF);
        this.ringPaint.setStrokeWidth(dp(2.0f));
        this.knobPaint.setStyle(Paint.Style.FILL);
        this.knobPaint.setColor(0x99FFFFFF);
        this.barPaint.setStyle(Paint.Style.STROKE);
        this.barPaint.setColor(0x88FFFFFF);
        this.barPaint.setStrokeWidth(dp(4.0f));
        this.barPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * The arrow graphics are looked up by name, because every chapter has its
     * own resource ids.
     */
    private static Drawable load(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        if (id == 0) {
            return null;
        }
        return context.getResources().getDrawable(id);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        this.radius = Math.min(w, h) * 0.33f;
        this.arrowSize = Math.min(w, h) * 0.23f;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) {
            reset();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }

    private void reset() {
        this.knobX = 0.0f;
        this.knobY = 0.0f;
        this.active = false;
        applyDirection();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (!this.active) {
                this.active = true;
                moveKnobTo(event.getX(), event.getY());
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (this.active) {
                moveKnobTo(event.getX(), event.getY());
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            reset();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void moveKnobTo(float x, float y) {
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float dx = x - cx;
        float dy = y - cy;
        float dist = (float) Math.sqrt((dx * dx) + (dy * dy));
        if (dist > this.radius && dist > 0.0f) {
            dx = (dx / dist) * this.radius;
            dy = (dy / dist) * this.radius;
        }
        this.knobX = dx;
        this.knobY = dy;
        applyDirection();
        invalidate();
    }

    /**
     * Turns the knob position into the forward and sideways walking values.
     * Screen up is forward, screen right is to the right, both relative to the
     * direction the camera is looking at.
     */
    private void applyDirection() {
        float forward = 0.0f;
        float strafe = 0.0f;
        float dist = (float) Math.sqrt((this.knobX * this.knobX) + (this.knobY * this.knobY));
        if (dist > 0.0001f && this.radius > 0.0f) {
            float ratio = Math.min(1.0f, dist / this.radius);
            if (ratio > DEADZONE) {
                float strength = (ratio - DEADZONE) / (1.0f - DEADZONE);
                float nx = this.knobX / dist;
                float ny = this.knobY / dist;
                forward = -ny * strength * STEP;
                strafe = nx * strength * STEP;
            }
        }
        QuestLogic logic = getLogic();
        if (logic == null) {
            return;
        }
        if (!canWalk(logic)) {
            forward = 0.0f;
            strafe = 0.0f;
        }
        logic.setDirection(forward);
        InputProcessor input = logic.getInputProcessor();
        if (input != null) {
            input.setStrafe(strafe);
        }
    }

    private static boolean canWalk(QuestLogic logic) {
        if (!logic.hasInited()) {
            return false;
        }
        try {
            Node node = logic.getCurrentNode();
            return node != null && node.isProcessInput();
        } catch (Exception e) {
            // the game is still loading and the location is not set up yet
            return false;
        }
    }

    private QuestLogic getLogic() {
        Context context = getContext();
        if (!(context instanceof QuestEngine)) {
            return null;
        }
        return ((QuestEngine) context).getLogic();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        if (this.radius <= 0.0f) {
            return;
        }
        canvas.drawCircle(cx, cy, this.radius, this.ringPaint);
        // the four arrows sit on the ring and show which way the player walks
        drawArrow(canvas, this.upArrow, cx, cy, 0.0f, -this.radius, 0.0f);
        drawArrow(canvas, this.downArrow, cx, cy, 0.0f, this.radius, 0.0f);
        drawArrow(canvas, this.upArrow, cx, cy, 0.0f, -this.radius, -90.0f);
        drawArrow(canvas, this.upArrow, cx, cy, 0.0f, -this.radius, 90.0f);
        if (this.knobX != 0.0f || this.knobY != 0.0f) {
            canvas.drawLine(cx, cy, cx + this.knobX, cy + this.knobY, this.barPaint);
            canvas.drawCircle(cx + this.knobX, cy + this.knobY, this.arrowSize * 0.3f, this.knobPaint);
        }
    }

    /**
     * Draws one arrow at the given offset from the centre of the stick. A non
     * zero angle turns the whole stick around its centre, which moves the arrow
     * to the side and turns the graphic into the matching direction.
     */
    private void drawArrow(Canvas canvas, Drawable arrow, float cx, float cy, float dx, float dy, float angle) {
        if (arrow == null) {
            return;
        }
        float size = this.arrowSize;
        canvas.save();
        if (angle != 0.0f) {
            canvas.rotate(angle, cx, cy);
        }
        arrow.setBounds((int) (cx + dx - (size * 0.5f)), (int) (cy + dy - (size * 0.5f)),
                (int) (cx + dx + (size * 0.5f)), (int) (cy + dy + (size * 0.5f)));
        arrow.draw(canvas);
        canvas.restore();
    }
}
