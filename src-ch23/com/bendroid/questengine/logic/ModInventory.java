package com.bendroid.questengine.logic;

import com.bendroid.questengine.logic.inventory.Inventory;
import com.bendroid.questengine.logic.inventory.InventoryItem;

/**
 * Adds the collected items to the inventory panel.
 *
 * The game loads its logic on the gl thread and adds the item views to the
 * inventory while it does so. Touching views from a thread other than the one
 * that created them is not allowed on current android versions, the game dies
 * with a CalledFromWrongThreadException. The view is therefore added on the ui
 * thread, the item itself goes into the list right away so the game can use it.
 */
public class ModInventory {

    private ModInventory() {
    }

    public static void addItem(final Inventory inventory, final InventoryItem item) {
        if (inventory == null || item == null) {
            return;
        }
        inventory.getItems().put(Integer.valueOf(item.getId()), item);
        inventory.post(new Runnable() {
            @Override
            public void run() {
                inventory.addInventoryItem(item);
            }
        });
    }
}
