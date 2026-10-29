package com.bendroid.questengine.logic;

import com.bendroid.global.graphics.objects.Camera;
import com.bendroid.questengine.QuestEngine;
import com.bendroid.questengine.logic.graph.Node;
import com.bendroid.questengine.logic.graph.Result;
import com.bendroid.questengine.logic.inventory.Inventory;

public class QuestLogic {
    public Camera getCamera() {
        return null;
    }

    public Node getCurrentNode() {
        return null;
    }

    public TextHandler getTextHandler() {
        return null;
    }

    public QuestEngine getContext() {
        return null;
    }

    public boolean hasInited() {
        return false;
    }

    public int getState() {
        return 0;
    }

    public Inventory getInventory() {
        return null;
    }

    public InputProcessor getInputProcessor() {
        return null;
    }

    public void setDirection(float direction) {
    }

    public void processResult(Result res, boolean processAnim) {
    }
}
