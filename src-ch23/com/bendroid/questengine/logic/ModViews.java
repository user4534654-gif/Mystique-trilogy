package com.bendroid.questengine.logic;

import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.bendroid.questengine.QuestEngine;

/**
 * Adds the mod controls to the game screen.
 *
 * The layouts of chapters two and three are stored as compiled binary xml,
 * which cannot be edited, so the analog stick and the inventory button are
 * added to the frame layout from code instead. Everything is looked up by
 * resource name, because every chapter has its own resource ids.
 */
public class ModViews {

    private static QuestEngine currentEngine;

    private static MoveStick stick;
    private static InventoryButton inventoryButton;

    private ModViews() {
    }

    /**
     * Adds the controls to the game screen. Has to be called right after
     * setContentView, so the intro and outro screens end up on top of them.
     */
    public static void install(QuestEngine engine) {
        StateProbe.start(engine);
        View root = engine.findViewById(identifier(engine, "main_layout", "id"));
        if (!(root instanceof FrameLayout)) {
            return;
        }
        FrameLayout frame = (FrameLayout) root;
        currentEngine = engine;

        forwardTouches(engine);

        stick = new MoveStick(engine);
        FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(engine, 170), dp(engine, 170));
        stickParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        stickParams.leftMargin = dp(engine, 6);
        stickParams.bottomMargin = dp(engine, 6);
        frame.addView(stick, stickParams);
        stick.setVisibility(View.GONE);

        inventoryButton = new InventoryButton(engine);
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(dp(engine, 54), dp(engine, 54));
        buttonParams.gravity = Gravity.TOP | Gravity.RIGHT;
        buttonParams.rightMargin = dp(engine, 10);
        buttonParams.topMargin = dp(engine, 10);
        frame.addView(inventoryButton, buttonParams);
    }

    /**
     * The touches of the 3d view never reach the activity in these two
     * chapters, the gl view is a surface and swallows them. They are taken
     * there and handed over to the logic directly. The event is consumed, so
     * nothing is handled twice.
     */
    private static void forwardTouches(final QuestEngine engine) {
        View gl = engine.findViewById(identifier(engine, "glview", "id"));
        if (gl == null) {
            return;
        }
        gl.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                QuestLogic logic = getLogic();
                if (logic == null) {
                    return false;
                }
                logic.onTouchEvent(event);
                return true;
            }
        });
    }

    /**
     * Shows or hides the walking controls. The up and down buttons of the
     * original game are hidden for good, the analog stick replaces them.
     */
    public static void setWalkControlsVisible(QuestEngine engine, boolean visible) {
        setVisibility(engine, "forward_btn", View.GONE);
        setVisibility(engine, "reverse_btn", View.GONE);
        if (stick != null) {
            stick.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private static QuestLogic getLogic() {
        QuestEngine engine = currentEngine;
        return engine == null ? null : engine.getLogic();
    }

    private static void setVisibility(QuestEngine engine, String name, int visibility) {
        int id = identifier(engine, name, "id");
        if (id == 0) {
            return;
        }
        View view = engine.findViewById(id);
        if (view != null) {
            view.setVisibility(visibility);
        }
    }

    private static int identifier(QuestEngine engine, String name, String type) {
        return engine.getResources().getIdentifier(name, type, engine.getPackageName());
    }

    private static int dp(QuestEngine engine, int value) {
        return (int) (value * engine.getResources().getDisplayMetrics().density);
    }
}
