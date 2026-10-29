package com.bendroid.questengine.logic;

import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.bendroid.questengine.QuestEngine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime probe that writes the state of the running game to logcat.
 *
 * The game keeps everything that describes "where we are" in private fields of
 * QuestLogic and its base class: the current app state, the animation that is
 * playing, the location, the node stack, the camera, the triggers. This class
 * reads them with reflection instead of touching the game code, and prints one
 * line per change (plus a heartbeat once a second) under the tag MYSTIQUE.
 *
 * Chapters two and three declare these members in different classes and with
 * slightly different names, so nothing is bound at compile time: every lookup
 * walks the class hierarchy and is reported as MISSING in the first line if it
 * does not exist. A lookup that fails is skipped, never fatal.
 */
public final class StateProbe {

    private static final String TAG = "MYSTIQUE";

    private static final long POLL_MS = 200L;
    private static final long HEARTBEAT_MS = 1000L;

    private static final String[] LOGIC_FIELDS = {
            "APP_STATE", "NEXT_STATE_STATE", "currentAnimation", "animations",
            "currentLocation", "triggers", "inv", "direction", "delta", "camera",
            "locations", "inited", "canInit", "loadUponInit"
    };
    private static final String[] LOGIC_METHODS = {
            "getState", "getCurrentLocation", "getCurrentNode", "getAnimationByIndex",
            "getTriggers", "getInventory", "getNodesStack", "getCamera", "getContext",
            "hasInited"
    };
    private static final String[] ENGINE_FIELDS = {"logic", "inMenu", "started"};
    private static final String[] ENGINE_METHODS = {"getLogic", "isInMenu", "getInventory"};

    // only one of the chapters has these, so they must not be reported as broken
    private static final String[] OPTIONAL_LOGIC_FIELDS = {"totalTime"};
    private static final String[] OPTIONAL_LOGIC_METHODS = {"getSoundPool"};
    private static final String[] OPTIONAL_ENGINE_FIELDS = {"fightLogic"};
    private static final String[] OPTIONAL_ENGINE_METHODS = {"getFightLogic"};

    // the views that tell "menu or game" without touching the game logic
    private static final String[] VIEWS = {
            "glview", "MessagesCenter", "MessagesText", "menuView", "introView",
            "outtroView", "busy_icon", "back_icon", "forward_btn", "reverse_btn"
    };

    private static volatile QuestEngine engine;
    private static volatile boolean running;

    private static final Map<String, Field> FIELDS = new HashMap<String, Field>();
    private static final Map<String, Method> METHODS = new HashMap<String, Method>();
    private static final Map<String, Integer> IDS = new HashMap<String, Integer>();

    private static long startTime;

    private StateProbe() {
    }

    /** Called once when the game installs the mod controls. */
    public static void start(QuestEngine e) {
        if (e == null) {
            return;
        }
        engine = e;
        if (running) {
            return;
        }
        running = true;
        startTime = System.currentTimeMillis();
        log("=== probe started, pid=" + android.os.Process.myPid() + " ===");
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                logBuild();
                loop();
            }
        }, "mystique-probe");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Prints which build is running: the version the package reports, the
     * sha256 of the installed apk and the device it runs on.
     *
     * This is the line that tells a wrong (older) apk apart from the current
     * one, without having to play until something looks off. The version is
     * stamped into the apk at build time and the hash is the one printed by
     * the release's SHA256SUMS.txt, so both can be compared directly.
     */
    private static void logBuild() {
        String versionName = "?";
        String versionCode = "?";
        String apk = "?";
        try {
            QuestEngine e = engine;
            android.content.pm.PackageManager manager = e.getPackageManager();
            android.content.pm.PackageInfo info = manager.getPackageInfo(e.getPackageName(), 0);
            versionName = String.valueOf(info.versionName);
            versionCode = String.valueOf(info.versionCode);
            apk = new java.io.File(e.getPackageCodePath()).getName();
        } catch (Throwable t) {
            // without the package manager there is nothing to report, the rest
            // of the probe keeps working
        }
        log("BUILD version=" + versionName + " versionCode=" + versionCode
                + " apk=" + apk + " sha256=" + sha256());
        log("BUILD device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " android=" + android.os.Build.VERSION.RELEASE
                + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " abi=" + abi());
    }

    private static String abi() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        return abis != null && abis.length > 0 ? abis[0] : "?";
    }

    /** sha256 of the installed apk, the same value the release lists. */
    private static String sha256() {
        String path;
        try {
            path = engine.getPackageCodePath();
        } catch (Throwable t) {
            return "?";
        }
        java.io.InputStream in = null;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(path);
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] sum = digest.digest();
            StringBuilder hex = new StringBuilder(sum.length * 2);
            for (byte b : sum) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "unavailable(" + t + ")";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // there is nothing to do about a failing close
                }
            }
        }
    }

    private static void loop() {
        boolean checked = false;
        String lastCore = "";
        long lastPrint = 0L;
        Map<Integer, Boolean> triggerState = new HashMap<Integer, Boolean>();
        long lastDelta = -1L;
        while (true) {
            try {
                QuestEngine e = engine;
                Object logic = e == null ? null : e.getLogic();
                if (logic == null) {
                    long now = System.currentTimeMillis();
                    if (now - lastPrint >= HEARTBEAT_MS) {
                        log("t=" + (now - startTime) + "ms [hb] waiting, logic not available yet");
                        lastPrint = now;
                    }
                } else {
                    if (!checked) {
                        checked = true;
                        selfCheck(logic, e);
                    }
                    StringBuilder core = new StringBuilder(160);
                    String line = sample(logic, e, triggerState, core);
                    long now = System.currentTimeMillis();
                    String key = core.toString();
                    boolean changed = !key.equals(lastCore);
                    if (changed || now - lastPrint >= HEARTBEAT_MS) {
                        log("t=" + (now - startTime) + "ms " + (changed ? "[change]" : "[hb]    ") + " " + line);
                        lastCore = key;
                        lastPrint = now;
                    }
                    long delta = num(get(logic, "delta"));
                    if (delta > 100 && delta != lastDelta) {
                        log("t=" + (now - startTime) + "ms [SPIKE] delta=" + delta + "ms");
                        lastDelta = delta;
                    }
                }
            } catch (Throwable t) {
                log("probe error: " + t);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ sample

    private static String sample(Object logic, QuestEngine e, Map<Integer, Boolean> triggerState, StringBuilder core) {
        StringBuilder b = new StringBuilder(220);

        int state = (int) num(call(logic, "getState"));
        core.append(state).append('|');
        b.append("st=").append(stateName(state)).append('(').append(state).append(')');
        Object raw = get(logic, "APP_STATE");
        if (raw != null) {
            b.append(" APP_STATE=").append(raw);
        }
        b.append(" NEXT=").append(get(logic, "NEXT_STATE_STATE"));
        b.append(" | menu=").append(callBool(e, "isInMenu"));
        b.append(" started=").append(val(get(e, "started")));
        b.append(" inMenu=").append(val(get(e, "inMenu")));
        b.append(" | inited=").append(val(fieldOr(call(logic, "hasInited"), logic, "inited")));
        b.append(" canInit=").append(val(get(logic, "canInit")));
        b.append(" loadUpon=").append(val(get(logic, "loadUponInit")));
        b.append(" totalTime=").append(ms(get(logic, "totalTime")));

        b.append(" | ").append(animLine(logic, core));
        b.append(" | ").append(placeLine(logic, core));
        b.append(" | ").append(cameraLine(logic));
        b.append(" | delta=").append(get(logic, "delta"));
        b.append(" dir=").append(get(logic, "direction"));
        b.append(" | ").append(inventoryLine(logic));

        String views = viewsLine(e);
        if (views != null) {
            b.append(" | ").append(views);
            core.append(views);
        }

        String trig = triggersLine(logic, triggerState);
        if (trig != null && trig.length() > 0) {
            b.append(" | trig ").append(trig);
            core.append("trig").append(trig);
        }

        Object fight = call(e, "getFightLogic");
        if (fight != null) {
            Object fightState = call(fight, "getState");
            b.append(" | fight st=").append(fight.getClass().getSimpleName()).append(':').append(fightState);
            core.append("fight").append(fightState);
        }
        return b.toString();
    }

    private static String animLine(Object logic, StringBuilder core) {
        Object anim = get(logic, "currentAnimation");
        if (anim == null) {
            core.append("anim=null|");
            return "anim=null";
        }
        StringBuilder b = new StringBuilder();
        b.append("anim=").append(anim.getClass().getSimpleName());
        int index = indexOf(get(logic, "animations"), anim);
        b.append('#').append(index);
        b.append('/').append(size(get(logic, "animations")));
        core.append(anim.getClass().getName()).append('#').append(index).append('|');

        Object current = call(anim, "getCurrentTime");
        Object total = call(anim, "getTotalTime");
        if (current == null) {
            current = get(anim, "currentTime");
        }
        if (total == null) {
            total = get(anim, "totalTime");
        }
        if (current != null && total != null) {
            float c = numF(current);
            float t = numF(total);
            b.append(" t=").append((long) c).append('/').append((long) t).append("ms");
            // the progress of an animation is the interesting part, a 200 ms
            // step keeps the line readable but shows where it stalls
            core.append("t").append((long) (c / 200f)).append('|');
            if (t > 0) {
                b.append(" (").append((long) (100f * c / t)).append("%)");
            }
            Object frameRate = get(anim, "frameRate");
            if (frameRate != null && num(frameRate) > 0) {
                b.append(" f=").append((long) (c * num(frameRate) / 1000f));
            }
        }
        // the fields of the max animation of chapter two, kept in the line
        // because they carry the end tick and the frame timing
        appendIf(b, " endTick=", get(anim, "endTick"));
        appendIf(b, " ticksPerFrame=", get(anim, "ticksPerFrame"));
        appendIf(b, " maxLoops=", get(anim, "maxLoops"));
        appendIf(b, " loops=", get(anim, "currentLoops"));
        appendIf(b, " objects=", call(anim, "getAnimationsCount"));
        appendIf(b, " simpleAnim=", get(anim, "currentAnimation"));
        return b.toString();
    }

    private static String placeLine(Object logic, StringBuilder core) {
        StringBuilder b = new StringBuilder();
        Object location = call(logic, "getCurrentLocation");
        if (location == null) {
            location = get(logic, "currentLocation");
        }
        if (location != null) {
            Object locationId = call(location, "getId");
            Object pack = call(location, "getCurrentPointPack");
            core.append("loc").append(locationId).append('/').append(pack).append('|');
            b.append("loc=").append(locationId);
            b.append(" pack=").append(pack);
            Object node = call(location, "getCurrentNode");
            if (node != null) {
                b.append(" locNode=").append(call(node, "getId"));
            }
        } else {
            core.append("loc=null|");
            b.append("loc=null");
        }
        Object node = call(logic, "getCurrentNode");
        Object nodeId = node == null ? null : call(node, "getId");
        core.append("node").append(nodeId).append('|');
        b.append(" node=").append(nodeId == null ? "null" : nodeId);
        if (node != null) {
            Object type = call(node, "getType");
            Object input = call(node, "isProcessInput");
            b.append("(t").append(type).append(input != null ? ",in" + input : "").append(')');
        }
        String stack = stackLine(call(logic, "getNodesStack"));
        core.append(stack).append('|');
        b.append(" stack=").append(stack);
        return b.toString();
    }

    private static String stackLine(Object stack) {
        if (stack == null) {
            return "null";
        }
        Object nodes = get(stack, "nodes");
        Object pos = get(stack, "currentPos");
        StringBuilder b = new StringBuilder();
        b.append('[').append(pos == null ? "?" : pos).append(':');
        if (nodes instanceof List) {
            List<?> list = (List<?>) nodes;
            for (int i = 0; i < list.size(); i++) {
                Object n = list.get(i);
                b.append(' ').append(n == null ? "null" : call(n, "getId"));
            }
        }
        return b.append(']').toString();
    }

    private static String cameraLine(Object logic) {
        Object camera = call(logic, "getCamera");
        if (camera == null) {
            camera = get(logic, "camera");
        }
        if (camera == null) {
            return "cam=null";
        }
        return "cam" + point(call(camera, "getPosition"))
                + " look" + point(call(camera, "getLookDirection"))
                + " rot" + point(call(camera, "getRotation"));
    }

    private static String inventoryLine(Object logic) {
        Object inv = call(logic, "getInventory");
        if (inv == null) {
            inv = get(logic, "inv");
        }
        if (inv == null) {
            return "inv=null";
        }
        Object items = call(inv, "getItems");
        return "inv sel=" + call(inv, "getSelectedItemId")
                + " items=" + size(items)
                + " open=" + get(inv, "visible");
    }

    private static String viewsLine(QuestEngine e) {
        StringBuilder b = new StringBuilder();
        try {
            for (String name : VIEWS) {
                int id = viewId(e, name);
                if (id == 0) {
                    continue;
                }
                View v = e.findViewById(id);
                if (v == null) {
                    continue;
                }
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(shortVis(v.getVisibility())).append(' ').append(name);
                if (v instanceof TextView) {
                    CharSequence text = ((TextView) v).getText();
                    b.append("=\"").append(trim(text)).append('"');
                }
            }
        } catch (Throwable t) {
            return "views error " + t;
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static int viewId(QuestEngine e, String name) {
        if (IDS.containsKey(name)) {
            return IDS.get(name).intValue();
        }
        int id = e.getResources().getIdentifier(name, "id", e.getPackageName());
        IDS.put(name, Integer.valueOf(id));
        return id;
    }

    private static String triggersLine(Object logic, Map<Integer, Boolean> known) {
        Object triggers = call(logic, "getTriggers");
        if (!(triggers instanceof List)) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        List<?> list = (List<?>) triggers;
        for (Object trigger : list) {
            if (trigger == null) {
                continue;
            }
            int id = (int) num(call(trigger, "getId"));
            boolean on = Boolean.TRUE.equals(call(trigger, "get"));
            Boolean old = known.get(Integer.valueOf(id));
            if (old == null || old.booleanValue() != on) {
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(id).append('=').append(on ? "ON" : "off");
                known.put(Integer.valueOf(id), Boolean.valueOf(on));
            }
        }
        if (b.length() == 0) {
            return null;
        }
        return b.toString();
    }

    // -------------------------------------------------------------- self check

    private static void selfCheck(Object logic, QuestEngine e) {
        StringBuilder missing = new StringBuilder();
        StringBuilder optional = new StringBuilder();
        checkFields(logic, LOGIC_FIELDS, missing);
        checkMethods(logic, LOGIC_METHODS, missing);
        checkFields(e, ENGINE_FIELDS, missing);
        checkMethods(e, ENGINE_METHODS, missing);
        checkFields(logic, OPTIONAL_LOGIC_FIELDS, optional);
        checkMethods(logic, OPTIONAL_LOGIC_METHODS, optional);
        checkFields(e, OPTIONAL_ENGINE_FIELDS, optional);
        checkMethods(e, OPTIONAL_ENGINE_METHODS, optional);
        log("probe map: logic=" + logic.getClass().getName() + " engine=" + e.getClass().getName()
                + (missing.length() == 0 ? ", every lookup resolved" : ", MISSING:" + missing));
        if (optional.length() > 0) {
            log("probe map: not in this chapter (skipped):" + optional);
        }

        Object anims = get(logic, "animations");
        if (anims instanceof List) {
            List<?> list = (List<?>) anims;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                Object a = list.get(i);
                b.append(i).append('=').append(a == null ? "null" : a.getClass().getSimpleName()).append(' ');
            }
            log("animations(" + list.size() + "): " + b);
        }
        Object locations = get(logic, "locations");
        if (locations instanceof Object[]) {
            log("locations: " + ((Object[]) locations).length);
        }
        Object inv = call(logic, "getInventory");
        Object items = inv == null ? null : call(inv, "getItems");
        if (items instanceof Map) {
            log("inventory items: " + ((Map<?, ?>) items).keySet());
        }
        Object triggers = call(logic, "getTriggers");
        if (triggers instanceof List) {
            StringBuilder b = new StringBuilder();
            for (Object trigger : (List<?>) triggers) {
                if (trigger != null) {
                    b.append(call(trigger, "getId")).append(' ');
                }
            }
            log("triggers(" + ((List<?>) triggers).size() + "): " + b);
        }
    }

    private static void checkFields(Object target, String[] names, StringBuilder missing) {
        for (String name : names) {
            if (field(target, name) == null) {
                missing.append(' ').append(target.getClass().getSimpleName()).append('.').append(name);
            }
        }
    }

    private static void checkMethods(Object target, String[] names, StringBuilder missing) {
        for (String name : names) {
            if (method(target, name) == null) {
                missing.append(' ').append(target.getClass().getSimpleName()).append('.').append(name).append("()");
            }
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case 0: return "PREINIT";
            case 1: return "IDLE";
            case 2: return "BUSY/ANIM";
            case 3: return "INV_ITEM";
            case 4: return "INTRO";
            case 5: return "OUTRO";
            default: return "?";
        }
    }

    // --------------------------------------------------------------- reflect

    private static Field field(Object target, String name) {
        if (target == null) {
            return null;
        }
        String key = target.getClass().getName() + "#" + name;
        if (FIELDS.containsKey(key)) {
            return FIELDS.get(key);
        }
        Field found = null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                found = c.getDeclaredField(name);
                found.setAccessible(true);
                break;
            } catch (NoSuchFieldException ignored) {
                // the member lives in a super class, keep walking
            }
        }
        FIELDS.put(key, found);
        return found;
    }

    private static Method method(Object target, String name) {
        if (target == null) {
            return null;
        }
        String key = target.getClass().getName() + "#" + name;
        if (METHODS.containsKey(key)) {
            return METHODS.get(key);
        }
        Method found = null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                found = c.getDeclaredMethod(name);
                found.setAccessible(true);
                break;
            } catch (NoSuchMethodException ignored) {
                // the member lives in a super class, keep walking
            }
        }
        METHODS.put(key, found);
        return found;
    }

    private static Object get(Object target, String name) {
        Field f = field(target, name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object call(Object target, String name) {
        Method m = method(target, name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean callBool(Object target, String name) {
        return Boolean.TRUE.equals(call(target, name));
    }

    private static Object fieldOr(Object value, Object target, String name) {
        return value == null ? get(target, name) : value;
    }

    private static Object val(Object value) {
        return value == null ? "?" : value;
    }

    // ---------------------------------------------------------------- helpers

    private static long num(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    private static float numF(Object value) {
        return value instanceof Number ? ((Number) value).floatValue() : 0f;
    }

    private static long ms(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : -1L;
    }

    private static String point(Object value) {
        if (value == null) {
            return "(null)";
        }
        Object x = get(value, "x");
        Object y = get(value, "y");
        Object z = get(value, "z");
        if (x == null && y == null && z == null) {
            return "(" + value + ")";
        }
        return "(" + numF(x) + "," + numF(y) + "," + numF(z) + ")";
    }

    private static int indexOf(Object list, Object item) {
        if (list instanceof List) {
            List<?> l = (List<?>) list;
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i) == item) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int size(Object list) {
        return list instanceof List ? ((List<?>) list).size() : (list instanceof Map ? ((Map<?, ?>) list).size() : -1);
    }

    private static String shortVis(int visibility) {
        switch (visibility) {
            case View.VISIBLE: return "V";
            case View.INVISIBLE: return "I";
            case View.GONE: return "G";
            default: return String.valueOf(visibility);
        }
    }

    private static String trim(CharSequence text) {
        if (text == null) {
            return "";
        }
        String s = text.toString();
        if (s.length() > 70) {
            s = s.substring(0, 70) + "...";
        }
        return s;
    }

    private static void appendIf(StringBuilder b, String label, Object value) {
        if (value != null) {
            b.append(label).append(value);
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
    }
}
