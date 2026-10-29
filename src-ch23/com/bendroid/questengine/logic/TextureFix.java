package com.bendroid.questengine.logic;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

/**
 * Loads the textures of chapters two and three.
 *
 * The original code decodes the texture without any options and uploads it
 * with GLUtils.texImage2D(target, level, internalFormat, bitmap, border).
 * That overload only accepts a bitmap in ARGB_8888 or RGB_565, and it also
 * requires the bitmap format to match the requested internal format. On
 * current android versions the decoded bitmap does not match, and the game
 * dies with "invalid Bitmap format" while it loads the textures.
 *
 * The bitmap is therefore decoded with a fixed format and uploaded with the
 * overload that takes the format from the bitmap itself.
 */
public class TextureFix {

    private static final String TAG = "MystiqueMod";

    public static Bitmap load(Resources resources, int id) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeResource(resources, id, options);
        if (bitmap == null) {
            Log.e(TAG, "texture " + id + " could not be decoded");
            return null;
        }
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            Log.e(TAG, "texture " + id + " has the config " + bitmap.getConfig());
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) {
                copy = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            }
            if (copy != null) {
                bitmap.recycle();
                return copy;
            }
        }
        return bitmap;
    }

    /**
     * Chapter three decodes the textures with options of its own. They are
     * ignored, the format the game asks for there cannot be uploaded.
     */
    public static Bitmap load(Resources resources, int id, BitmapFactory.Options options) {
        return load(resources, id);
    }
}
