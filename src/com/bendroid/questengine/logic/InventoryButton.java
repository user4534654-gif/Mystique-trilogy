package com.bendroid.questengine.logic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.bendroid.questengine.QuestEngine;
import com.bendroid.questengine.logic.inventory.Inventory;

/**
 * Button that opens and closes the inventory.
 *
 * The original game had a code path for it (message 3 in the message handler
 * toggles the inventory) but nothing ever sent that message and there was no
 * button in the layout either, so the collected items could not be picked up
 * and the game could not be finished.
 *
 * As soon as the player picks an item the panel closes itself again, otherwise
 * it would cover the right hand side of the room and block the objects that
 * have to be touched with the item.
 */
public class InventoryButton extends View {

    /** QuestLogic state in which the player may interact with the world. */
    private static final int STATE_IDLE = 1;

    /** Delay before the panel closes itself after an item was picked. */
    private static final long CLOSE_DELAY = 650L;

    /** How often the panel is checked for a newly picked item. */
    private static final long POLL_INTERVAL = 100L;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF handle = new RectF();
    private final RectF flap = new RectF();
    private final Handler handler = new Handler();

    /** Item that was selected when the panel was opened. */
    private int selectedWhenOpened = 0;

    /** Point in time when the panel has to close itself. */
    private long pendingClose = 0L;

    private final Runnable watcher = new Runnable() {
        @Override
        public void run() {
            closeIfItemPicked();
            handler.postDelayed(this, POLL_INTERVAL);
        }
    };

    public InventoryButton(Context context) {
        super(context);
        init();
    }

    public InventoryButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public InventoryButton(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        this.backgroundPaint.setStyle(Paint.Style.FILL);
        this.backgroundPaint.setColor(0x99000000);
        this.glyphPaint.setStyle(Paint.Style.STROKE);
        this.glyphPaint.setStrokeWidth(dp(2.2f));
        this.glyphPaint.setColor(0xFFFFFFFF);
        this.glyphPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        this.handler.postDelayed(this.watcher, 120L);
    }

    @Override
    protected void onDetachedFromWindow() {
        this.handler.removeCallbacks(this.watcher);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0.0f || h <= 0.0f) {
            return;
        }
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float s = Math.min(w, h);
        canvas.drawCircle(cx, cy, s * 0.46f, this.backgroundPaint);

        // a small bag: rounded body, handle on top and a flap line
        float bw = s * 0.30f;
        float bh = s * 0.30f;
        this.body.set(cx - bw, cy - bh * 0.25f, cx + bw, cy + bh * 0.95f);
        this.handle.set(cx - bw * 0.55f, cy - bh * 0.95f, cx + bw * 0.55f, cy - bh * 0.05f);
        this.flap.set(cx - bw, cy + bh * 0.10f, cx + bw, cy + bh * 0.30f);
        canvas.drawRoundRect(this.body, s * 0.06f, s * 0.06f, this.glyphPaint);
        canvas.drawArc(this.handle, 180.0f, 180.0f, false, this.glyphPaint);
        canvas.drawLine(this.flap.left, this.flap.centerY(), this.flap.right, this.flap.centerY(), this.glyphPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            toggle();
        }
        return true;
    }

    private void toggle() {
        Inventory inventory = getInventory();
        if (inventory == null) {
            return;
        }
        QuestLogic logic = getLogic();
        if (logic == null || logic.getState() != STATE_IDLE) {
            return;
        }
        if (inventory.getVisibility() == VISIBLE) {
            inventory.hide();
        } else {
            this.selectedWhenOpened = inventory.getSelectedItemId();
            inventory.setVisibility(VISIBLE);
        }
        invalidate();
    }

    /**
     * Closes the panel a moment after the player picked an item inside it, so
     * the right hand side of the room stays reachable. The delay is longer
     * than the double tap window of the inventory items, so the description of
     * an item can still be opened with a double tap.
     */
    private void closeIfItemPicked() {
        Inventory inventory = getInventory();
        if (inventory == null || inventory.getVisibility() != VISIBLE) {
            this.pendingClose = 0L;
            return;
        }
        int selected = inventory.getSelectedItemId();
        if (selected == 0 || selected == this.selectedWhenOpened) {
            this.pendingClose = 0L;
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (this.pendingClose == 0L) {
            this.pendingClose = now + CLOSE_DELAY;
            return;
        }
        if (now >= this.pendingClose) {
            this.pendingClose = 0L;
            inventory.hide();
            invalidate();
        }
    }

    private Inventory getInventory() {
        QuestLogic logic = getLogic();
        return logic == null ? null : logic.getInventory();
    }

    private QuestLogic getLogic() {
        Context context = getContext();
        if (!(context instanceof QuestEngine)) {
            return null;
        }
        return ((QuestEngine) context).getLogic();
    }
}
