/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.input;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.KeyEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * This class provides access to device specific key glyphs, modifier glyphs and device specific
 * shortcuts and keys
 *
 * @hide
 */
public final class KeyGlyphMap implements Parcelable {
    private static final String TAG = "KeyGlyphMap";

    @NonNull
    private final ComponentName mComponentName;
    @NonNull
    private final SparseIntArray mKeyGlyphs;
    @NonNull
    private final SparseIntArray mModifierGlyphs;
    @NonNull
    private final int[] mFunctionRowKeys;
    @NonNull
    private final Map<KeyCombination, Integer> mHardwareShortcuts;

    public static final @NonNull Parcelable.Creator<KeyGlyphMap> CREATOR =
            new Parcelable.Creator<>() {
                public KeyGlyphMap createFromParcel(Parcel in) {
                    return new KeyGlyphMap(in);
                }

                public KeyGlyphMap[] newArray(int size) {
                    return new KeyGlyphMap[size];
                }
            };

    public KeyGlyphMap(@NonNull ComponentName componentName,
            @NonNull SparseIntArray keyGlyphs, @NonNull SparseIntArray modifierGlyphs,
            @NonNull int[] functionRowKeys,
            @NonNull Map<KeyCombination, Integer> hardwareShortcuts) {
        mComponentName = componentName;
        mKeyGlyphs = keyGlyphs;
        mModifierGlyphs = modifierGlyphs;
        mFunctionRowKeys = functionRowKeys;
        mHardwareShortcuts = hardwareShortcuts;
    }

    public KeyGlyphMap(Parcel in) {
        mComponentName = in.readParcelable(getClass().getClassLoader(), ComponentName.class);
        mKeyGlyphs = in.readSparseIntArray();
        mModifierGlyphs = in.readSparseIntArray();
        mFunctionRowKeys = new int[in.readInt()];
        in.readIntArray(mFunctionRowKeys);
        mHardwareShortcuts = new HashMap<>(in.readInt());
        in.readMap(mHardwareShortcuts, getClass().getClassLoader(), KeyCombination.class,
                Integer.class);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mComponentName, 0);
        dest.writeSparseIntArray(mKeyGlyphs);
        dest.writeSparseIntArray(mModifierGlyphs);
        dest.writeInt(mFunctionRowKeys.length);
        dest.writeIntArray(mFunctionRowKeys);
        dest.writeInt(mHardwareShortcuts.size());
        dest.writeMap(mHardwareShortcuts);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Defines a key combination that includes a keycode and modifier state.
     */
    public record KeyCombination(int modifierState, int keycode) {}

    /**
     * Returns keycodes generated from the functional row defined for the keyboard.
     */
    public int[] getFunctionRowKeys() {
        return mFunctionRowKeys;
    }

    /**
     * Returns hardware defined shortcuts that are handled in the firmware of a particular
     * keyboard (e.g. Fn+Backspace = Back, etc.)
     *
     * @return a map of (modifier + key) combinations to keycode mappings that are handled by the
     * device hardware/firmware.
     */
    public Map<KeyCombination, Integer> getHardwareShortcuts() {
        return mHardwareShortcuts;
    }

    /**
     * Provides the drawable resource for the glyph for a keycode.
     * Returns null if not available.
     */
    @Nullable
    public Drawable getDrawableForKeycode(Context context, int keycode) {
        return getDrawable(context, mKeyGlyphs.get(keycode, 0));
    }

    /**
     * Provides the drawable resource for the glyph for a modifier key.
     * Returns null if not available.
     */
    @Nullable
    public Drawable getDrawableForModifier(Context context, int modifierKeycode) {
        int modifier = switch (modifierKeycode) {
            case KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON;
            case KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON;
            case KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON;
            case KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                    KeyEvent.META_SHIFT_ON;
            case KeyEvent.KEYCODE_FUNCTION -> KeyEvent.META_FUNCTION_ON;
            case KeyEvent.KEYCODE_SYM -> KeyEvent.META_SYM_ON;
            case KeyEvent.KEYCODE_CAPS_LOCK -> KeyEvent.META_CAPS_LOCK_ON;
            case KeyEvent.KEYCODE_NUM_LOCK -> KeyEvent.META_NUM_LOCK_ON;
            case KeyEvent.KEYCODE_SCROLL_LOCK -> KeyEvent.META_SCROLL_LOCK_ON;
            default -> 0;
        };
        return getDrawable(context, mModifierGlyphs.get(modifier, 0));
    }

    @Nullable
    private Drawable getDrawable(Context context, @DrawableRes int drawableRes) {
        PackageManager pm = context.getPackageManager();
        try {
            ActivityInfo receiver = pm.getReceiverInfo(mComponentName,
                    PackageManager.GET_META_DATA
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
            Resources resources = pm.getResourcesForApplication(receiver.applicationInfo);
            return resources.getDrawable(drawableRes, null);
        } catch (PackageManager.NameNotFoundException ignored) {
            Log.e(TAG, "Package name not found for " + mComponentName);
        }
        return null;
    }

    @Override
    public String toString() {
        return "KeyGlyphMap{"
                + "mComponentName=" + mComponentName
                + ", mKeyGlyphs=" + mKeyGlyphs
                + ", mModifierGlyphs=" + mModifierGlyphs
                + ", mFunctionRowKeys=" + Arrays.toString(mFunctionRowKeys)
                + ", mHardwareShortcuts=" + mHardwareShortcuts
                + '}';
    }
}
