/*
 * Copyright 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.SparseIntArray;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

/**
 * A complimentary class to {@link KeyboardLayoutPreviewDrawable} describing the physical key layout
 * of a Physical keyboard and provides information regarding the scan codes produced by the physical
 * keys.
 */
final class PhysicalKeyLayout {

    private static final String TAG = "KeyboardLayoutPreview";
    private static final int SCANCODE_1 = 2;
    private static final int SCANCODE_2 = 3;
    private static final int SCANCODE_3 = 4;
    private static final int SCANCODE_4 = 5;
    private static final int SCANCODE_5 = 6;
    private static final int SCANCODE_6 = 7;
    private static final int SCANCODE_7 = 8;
    private static final int SCANCODE_8 = 9;
    private static final int SCANCODE_9 = 10;
    private static final int SCANCODE_0 = 11;
    private static final int SCANCODE_MINUS = 12;
    private static final int SCANCODE_EQUALS = 13;
    private static final int SCANCODE_Q = 16;
    private static final int SCANCODE_W = 17;
    private static final int SCANCODE_E = 18;
    private static final int SCANCODE_R = 19;
    private static final int SCANCODE_T = 20;
    private static final int SCANCODE_Y = 21;
    private static final int SCANCODE_U = 22;
    private static final int SCANCODE_I = 23;
    private static final int SCANCODE_O = 24;
    private static final int SCANCODE_P = 25;
    private static final int SCANCODE_LEFT_BRACKET = 26;
    private static final int SCANCODE_RIGHT_BRACKET = 27;
    private static final int SCANCODE_A = 30;
    private static final int SCANCODE_S = 31;
    private static final int SCANCODE_D = 32;
    private static final int SCANCODE_F = 33;
    private static final int SCANCODE_G = 34;
    private static final int SCANCODE_H = 35;
    private static final int SCANCODE_J = 36;
    private static final int SCANCODE_K = 37;
    private static final int SCANCODE_L = 38;
    private static final int SCANCODE_SEMICOLON = 39;
    private static final int SCANCODE_APOSTROPHE = 40;
    private static final int SCANCODE_GRAVE = 41;
    private static final int SCANCODE_BACKSLASH1 = 43;
    private static final int SCANCODE_Z = 44;
    private static final int SCANCODE_X = 45;
    private static final int SCANCODE_C = 46;
    private static final int SCANCODE_V = 47;
    private static final int SCANCODE_B = 48;
    private static final int SCANCODE_N = 49;
    private static final int SCANCODE_M = 50;
    private static final int SCANCODE_COMMA = 51;
    private static final int SCANCODE_PERIOD = 52;
    private static final int SCANCODE_SLASH = 53;
    private static final int SCANCODE_BACKSLASH2 = 86;
    private static final int SCANCODE_YEN = 124;

    private static final SparseIntArray DEFAULT_KEYCODE_FOR_SCANCODE = new SparseIntArray();

    static {
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_1, KeyEvent.KEYCODE_1);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_2, KeyEvent.KEYCODE_2);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_3, KeyEvent.KEYCODE_3);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_4, KeyEvent.KEYCODE_4);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_5, KeyEvent.KEYCODE_5);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_6, KeyEvent.KEYCODE_6);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_7, KeyEvent.KEYCODE_7);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_8, KeyEvent.KEYCODE_8);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_9, KeyEvent.KEYCODE_9);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_0, KeyEvent.KEYCODE_0);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_MINUS, KeyEvent.KEYCODE_MINUS);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_EQUALS, KeyEvent.KEYCODE_EQUALS);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_Q, KeyEvent.KEYCODE_Q);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_W, KeyEvent.KEYCODE_W);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_E, KeyEvent.KEYCODE_E);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_R, KeyEvent.KEYCODE_R);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_T, KeyEvent.KEYCODE_T);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_Y, KeyEvent.KEYCODE_Y);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_U, KeyEvent.KEYCODE_U);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_I, KeyEvent.KEYCODE_I);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_O, KeyEvent.KEYCODE_O);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_P, KeyEvent.KEYCODE_P);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_LEFT_BRACKET, KeyEvent.KEYCODE_LEFT_BRACKET);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_A, KeyEvent.KEYCODE_A);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_S, KeyEvent.KEYCODE_S);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_D, KeyEvent.KEYCODE_D);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_F, KeyEvent.KEYCODE_F);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_G, KeyEvent.KEYCODE_G);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_H, KeyEvent.KEYCODE_H);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_J, KeyEvent.KEYCODE_J);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_K, KeyEvent.KEYCODE_K);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_L, KeyEvent.KEYCODE_L);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_SEMICOLON, KeyEvent.KEYCODE_SEMICOLON);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_APOSTROPHE, KeyEvent.KEYCODE_APOSTROPHE);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_GRAVE, KeyEvent.KEYCODE_GRAVE);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_BACKSLASH1, KeyEvent.KEYCODE_BACKSLASH);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_Z, KeyEvent.KEYCODE_Z);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_X, KeyEvent.KEYCODE_X);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_C, KeyEvent.KEYCODE_C);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_V, KeyEvent.KEYCODE_V);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_B, KeyEvent.KEYCODE_B);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_N, KeyEvent.KEYCODE_N);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_M, KeyEvent.KEYCODE_M);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_COMMA, KeyEvent.KEYCODE_COMMA);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_PERIOD, KeyEvent.KEYCODE_PERIOD);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_SLASH, KeyEvent.KEYCODE_SLASH);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_BACKSLASH2, KeyEvent.KEYCODE_BACKSLASH);
        DEFAULT_KEYCODE_FOR_SCANCODE.put(SCANCODE_YEN, KeyEvent.KEYCODE_YEN);
    }

    private LayoutKey[][] mKeys = null;
    private EnterKey mEnterKey = null;

    public PhysicalKeyLayout(@NonNull KeyCharacterMap kcm, @Nullable KeyboardLayout layout) {
        initLayoutKeys(kcm, layout);
    }

    private void initLayoutKeys(KeyCharacterMap kcm, KeyboardLayout layout) {
        if (layout == null) {
            createIsoLayout(kcm);
            return;
        }
        if (layout.isAnsiLayout()) {
            createAnsiLayout(kcm);
        } else if (layout.isJisLayout()) {
            createJisLayout(kcm);
        } else {
            createIsoLayout(kcm);
        }
    }

    public LayoutKey[][] getKeys() {
        return mKeys;
    }

    /**
     * @return Special enter key (if required) that can span multiple rows like ISO enter key.
     */
    @Nullable
    public EnterKey getEnterKey() {
        return mEnterKey;
    }

    private void createAnsiLayout(KeyCharacterMap kcm) {
        mKeys = new LayoutKey[][]{
                {
                        getKey(kcm, SCANCODE_GRAVE), getKey(kcm, SCANCODE_1),
                        getKey(kcm, SCANCODE_2), getKey(kcm, SCANCODE_3), getKey(kcm, SCANCODE_4),
                        getKey(kcm, SCANCODE_5), getKey(kcm, SCANCODE_6), getKey(kcm, SCANCODE_7),
                        getKey(kcm, SCANCODE_8), getKey(kcm, SCANCODE_9), getKey(kcm, SCANCODE_0),
                        getKey(kcm, SCANCODE_MINUS), getKey(kcm, SCANCODE_EQUALS),
                        getKey(KeyEvent.KEYCODE_DEL, 1.5F)
                },
                {
                        getKey(KeyEvent.KEYCODE_TAB, 1.5F), getKey(kcm, SCANCODE_Q),
                        getKey(kcm, SCANCODE_W), getKey(kcm, SCANCODE_E), getKey(kcm, SCANCODE_R),
                        getKey(kcm, SCANCODE_T), getKey(kcm, SCANCODE_Y), getKey(kcm, SCANCODE_U),
                        getKey(kcm, SCANCODE_I), getKey(kcm, SCANCODE_O), getKey(kcm, SCANCODE_P),
                        getKey(kcm, SCANCODE_LEFT_BRACKET), getKey(kcm, SCANCODE_RIGHT_BRACKET),
                        getKey(kcm, SCANCODE_BACKSLASH1)
                },
                {
                        getKey(KeyEvent.KEYCODE_CAPS_LOCK, 1.75F),
                        getKey(kcm, SCANCODE_A), getKey(kcm, SCANCODE_S), getKey(kcm, SCANCODE_D),
                        getKey(kcm, SCANCODE_F), getKey(kcm, SCANCODE_G), getKey(kcm, SCANCODE_H),
                        getKey(kcm, SCANCODE_J), getKey(kcm, SCANCODE_K), getKey(kcm, SCANCODE_L),
                        getKey(kcm, SCANCODE_SEMICOLON), getKey(kcm, SCANCODE_APOSTROPHE),
                        getKey(KeyEvent.KEYCODE_ENTER, 1.75F)
                },
                {
                        getKey(KeyEvent.KEYCODE_SHIFT_LEFT, 2.5F),
                        getKey(kcm, SCANCODE_Z), getKey(kcm, SCANCODE_X), getKey(kcm, SCANCODE_C),
                        getKey(kcm, SCANCODE_V), getKey(kcm, SCANCODE_B), getKey(kcm, SCANCODE_N),
                        getKey(kcm, SCANCODE_M), getKey(kcm, SCANCODE_COMMA),
                        getKey(kcm, SCANCODE_PERIOD), getKey(kcm, SCANCODE_SLASH),
                        getKey(KeyEvent.KEYCODE_SHIFT_RIGHT, 2.5F),
                },
                {
                        getKey(KeyEvent.KEYCODE_CTRL_LEFT, 1.0F),
                        getKey(KeyEvent.KEYCODE_FUNCTION, 1.0F),
                        getKey(KeyEvent.KEYCODE_META_LEFT, 1.0F),
                        getKey(KeyEvent.KEYCODE_ALT_LEFT, 1.0F),
                        getKey(KeyEvent.KEYCODE_SPACE, 6.5F),
                        getKey(KeyEvent.KEYCODE_ALT_RIGHT, 1.0F),
                        getKey(KeyEvent.KEYCODE_META_RIGHT, 1.0F),
                        getKey(KeyEvent.KEYCODE_MENU, 1.0F),
                        getKey(KeyEvent.KEYCODE_CTRL_RIGHT, 1.0F),
                }
        };
    }

    private void createIsoLayout(KeyCharacterMap kcm) {
        mKeys = new LayoutKey[][]{
                {
                        getKey(kcm, SCANCODE_GRAVE), getKey(kcm, SCANCODE_1),
                        getKey(kcm, SCANCODE_2), getKey(kcm, SCANCODE_3), getKey(kcm, SCANCODE_4),
                        getKey(kcm, SCANCODE_5), getKey(kcm, SCANCODE_6), getKey(kcm, SCANCODE_7),
                        getKey(kcm, SCANCODE_8), getKey(kcm, SCANCODE_9), getKey(kcm, SCANCODE_0),
                        getKey(kcm, SCANCODE_MINUS), getKey(kcm, SCANCODE_EQUALS),
                        getKey(KeyEvent.KEYCODE_DEL, 1.5F)
                },
                {
                        getKey(KeyEvent.KEYCODE_TAB, 1.15F), getKey(kcm, SCANCODE_Q),
                        getKey(kcm, SCANCODE_W), getKey(kcm, SCANCODE_E), getKey(kcm, SCANCODE_R),
                        getKey(kcm, SCANCODE_T), getKey(kcm, SCANCODE_Y), getKey(kcm, SCANCODE_U),
                        getKey(kcm, SCANCODE_I), getKey(kcm, SCANCODE_O), getKey(kcm, SCANCODE_P),
                        getKey(kcm, SCANCODE_LEFT_BRACKET), getKey(kcm, SCANCODE_RIGHT_BRACKET),
                        getKey(KeyEvent.KEYCODE_ENTER, 1.35F)
                },
                {
                        getKey(KeyEvent.KEYCODE_TAB, 1.5F), getKey(kcm, SCANCODE_A),
                        getKey(kcm, SCANCODE_S), getKey(kcm, SCANCODE_D), getKey(kcm, SCANCODE_F),
                        getKey(kcm, SCANCODE_G), getKey(kcm, SCANCODE_H), getKey(kcm, SCANCODE_J),
                        getKey(kcm, SCANCODE_K), getKey(kcm, SCANCODE_L),
                        getKey(kcm, SCANCODE_SEMICOLON), getKey(kcm, SCANCODE_APOSTROPHE),
                        getKey(kcm, SCANCODE_BACKSLASH1),
                        getKey(KeyEvent.KEYCODE_ENTER, 1.0F)
                },
                {
                        getKey(KeyEvent.KEYCODE_SHIFT_LEFT, 1.15F),
                        getKey(kcm, SCANCODE_BACKSLASH2), getKey(kcm, SCANCODE_Z),
                        getKey(kcm, SCANCODE_X), getKey(kcm, SCANCODE_C), getKey(kcm, SCANCODE_V),
                        getKey(kcm, SCANCODE_B), getKey(kcm, SCANCODE_N), getKey(kcm, SCANCODE_M),
                        getKey(kcm, SCANCODE_COMMA), getKey(kcm, SCANCODE_PERIOD),
                        getKey(kcm, SCANCODE_SLASH),
                        getKey(KeyEvent.KEYCODE_SHIFT_RIGHT, 2.35F)
                },
                {
                        getKey(KeyEvent.KEYCODE_CTRL_LEFT, 1.0F),
                        getKey(KeyEvent.KEYCODE_FUNCTION, 1.0F),
                        getKey(KeyEvent.KEYCODE_META_LEFT, 1.0F),
                        getKey(KeyEvent.KEYCODE_ALT_LEFT, 1.0F),
                        getKey(KeyEvent.KEYCODE_SPACE, 6.5F),
                        getKey(KeyEvent.KEYCODE_ALT_RIGHT, 1.0F),
                        getKey(KeyEvent.KEYCODE_META_RIGHT, 1.0F),
                        getKey(KeyEvent.KEYCODE_MENU, 1.0F),
                        getKey(KeyEvent.KEYCODE_CTRL_RIGHT, 1.0F),
                }
        };
        mEnterKey = new EnterKey(1, 13, 1.35F, 1.0F);
    }

    private void createJisLayout(KeyCharacterMap kcm) {
        mKeys = new LayoutKey[][]{
                {
                        getKey(kcm, SCANCODE_GRAVE), getKey(kcm, SCANCODE_1),
                        getKey(kcm, SCANCODE_2), getKey(kcm, SCANCODE_3), getKey(kcm, SCANCODE_4),
                        getKey(kcm, SCANCODE_5), getKey(kcm, SCANCODE_6), getKey(kcm, SCANCODE_7),
                        getKey(kcm, SCANCODE_8), getKey(kcm, SCANCODE_9), getKey(kcm, SCANCODE_0),
                        getKey(kcm, SCANCODE_MINUS, 0.8F), getKey(kcm, SCANCODE_EQUALS, 0.8f),
                        getKey(kcm, SCANCODE_YEN, 0.8f), getKey(KeyEvent.KEYCODE_DEL, 1.1F)
                },
                {
                        getKey(KeyEvent.KEYCODE_TAB, 1.15F), getKey(kcm, SCANCODE_Q),
                        getKey(kcm, SCANCODE_W), getKey(kcm, SCANCODE_E), getKey(kcm, SCANCODE_R),
                        getKey(kcm, SCANCODE_T), getKey(kcm, SCANCODE_Y), getKey(kcm, SCANCODE_U),
                        getKey(kcm, SCANCODE_I), getKey(kcm, SCANCODE_O), getKey(kcm, SCANCODE_P),
                        getKey(kcm, SCANCODE_LEFT_BRACKET), getKey(kcm, SCANCODE_RIGHT_BRACKET),
                        getKey(KeyEvent.KEYCODE_ENTER, 1.35F)
                },
                {
                        getKey(KeyEvent.KEYCODE_TAB, 1.5F), getKey(kcm, SCANCODE_A),
                        getKey(kcm, SCANCODE_S), getKey(kcm, SCANCODE_D), getKey(kcm, SCANCODE_F),
                        getKey(kcm, SCANCODE_G), getKey(kcm, SCANCODE_H), getKey(kcm, SCANCODE_J),
                        getKey(kcm, SCANCODE_K), getKey(kcm, SCANCODE_L),
                        getKey(kcm, SCANCODE_SEMICOLON), getKey(kcm, SCANCODE_APOSTROPHE),
                        getKey(kcm, SCANCODE_BACKSLASH2),
                        getKey(KeyEvent.KEYCODE_ENTER, 1.0F)
                },
                {
                        getKey(KeyEvent.KEYCODE_SHIFT_LEFT, 1.15F),
                        getKey(kcm, SCANCODE_Z), getKey(kcm, SCANCODE_X), getKey(kcm, SCANCODE_C),
                        getKey(kcm, SCANCODE_V), getKey(kcm, SCANCODE_B), getKey(kcm, SCANCODE_N),
                        getKey(kcm, SCANCODE_M), getKey(kcm, SCANCODE_COMMA),
                        getKey(kcm, SCANCODE_PERIOD), getKey(kcm, SCANCODE_SLASH),
                        getKey(kcm, SCANCODE_BACKSLASH1),
                        getKey(KeyEvent.KEYCODE_SHIFT_RIGHT, 2.35F)
                },
                {
                        getKey(KeyEvent.KEYCODE_CTRL_LEFT, 1.0F),
                        getKey(KeyEvent.KEYCODE_FUNCTION, 1.0F),
                        getKey(KeyEvent.KEYCODE_META_LEFT, 1.0F),
                        getKey(KeyEvent.KEYCODE_ALT_LEFT, 1.0F),
                        getKey(KeyEvent.KEYCODE_UNKNOWN, 1.0F),
                        getKey(KeyEvent.KEYCODE_SPACE, 3.5F),
                        getKey(KeyEvent.KEYCODE_UNKNOWN, 1.0F),
                        getKey(KeyEvent.KEYCODE_UNKNOWN, 1.0F),
                        getKey(KeyEvent.KEYCODE_ALT_RIGHT, 1.0F),
                        getKey(KeyEvent.KEYCODE_META_RIGHT, 1.0F),
                        getKey(KeyEvent.KEYCODE_MENU, 1.0F),
                        getKey(KeyEvent.KEYCODE_CTRL_RIGHT, 1.0F),
                }
        };
        mEnterKey = new EnterKey(1, 13, 1.35F, 1.0F);
    }

    private static LayoutKey getKey(KeyCharacterMap kcm, int scanCode, float keyWeight) {
        int keyCode = kcm.getMappedKeyOrDefault(scanCode,
                DEFAULT_KEYCODE_FOR_SCANCODE.get(scanCode, KeyEvent.KEYCODE_UNKNOWN));
        return new LayoutKey(keyCode, scanCode, keyWeight, new KeyGlyph(kcm, keyCode));
    }

    private static LayoutKey getKey(KeyCharacterMap kcm, int scanCode) {
        return getKey(kcm, scanCode, 1.0F);
    }

    private static String getKeyText(KeyCharacterMap kcm, int keyCode, int modifierState) {
        if (isSpecialKey(keyCode)) {
            return "";
        }
        int utf8Char = (kcm.get(keyCode, modifierState) & KeyCharacterMap.COMBINING_ACCENT_MASK);
        if (Character.isValidCodePoint(utf8Char)) {
            return String.valueOf(Character.toChars(utf8Char));
        } else {
            return String.valueOf(kcm.getDisplayLabel(keyCode));
        }
    }

    private static LayoutKey getKey(int keyCode, float keyWeight) {
        return new LayoutKey(keyCode, keyCode, keyWeight, null);
    }

    /**
     * Util function that tells if a key corresponds to a special key which are keys on a Physical
     * layout that perform some special action like modifier keys, enter key, space key, character
     * set changing keys, etc.
     */
    private static boolean isSpecialKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_FUNCTION:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_UNKNOWN:
                return true;
        }
        return false;
    }

    public static boolean isSpecialKey(LayoutKey key) {
        return isSpecialKey(key.keyCode);
    }

    public static boolean isKeyPositionUnsure(LayoutKey key) {
        switch (key.scanCode) {
            case SCANCODE_GRAVE:
            case SCANCODE_BACKSLASH1:
            case SCANCODE_BACKSLASH2:
                return true;
        }
        return false;
    }

    public record LayoutKey(int keyCode, int scanCode, float keyWeight, KeyGlyph glyph) {}
    public record EnterKey(int row, int column, float topKeyWeight, float bottomKeyWeight) {}

    public static class KeyGlyph {
        private final String mBaseText;
        private final String mShiftText;
        private final String mAltGrText;
        private final String mAltGrShiftText;

        public KeyGlyph(KeyCharacterMap kcm, int keyCode) {
            mBaseText = getKeyText(kcm, keyCode, KeyEvent.META_CAPS_LOCK_ON);
            mShiftText = getKeyText(kcm, keyCode,
                    KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON);
            mAltGrText = getKeyText(kcm, keyCode,
                    KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON | KeyEvent.META_CAPS_LOCK_ON);
            mAltGrShiftText = getKeyText(kcm, keyCode,
                    KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON | KeyEvent.META_SHIFT_LEFT_ON
                            | KeyEvent.META_SHIFT_ON);
        }

        public String getBaseText() {
            return mBaseText;
        }

        public String getShiftText() {
            return mShiftText;
        }

        public String getAltGrText() {
            return mAltGrText;
        }

        public String getAltGrShiftText() {
            return mAltGrShiftText;
        }

        public boolean hasBaseText() {
            return !TextUtils.isEmpty(mBaseText);
        }

        public boolean hasValidShiftText() {
            return !TextUtils.isEmpty(mShiftText) && !TextUtils.equals(mBaseText, mShiftText);
        }

        public boolean hasValidAltGrText() {
            return !TextUtils.isEmpty(mAltGrText) && !TextUtils.equals(mBaseText, mAltGrText);
        }

        public boolean hasValidAltShiftText() {
            return !TextUtils.isEmpty(mAltGrShiftText)
                    && !TextUtils.equals(mBaseText, mAltGrShiftText)
                    && !TextUtils.equals(mAltGrText, mAltGrShiftText)
                    && !TextUtils.equals(mShiftText, mAltGrShiftText);
        }
    }
}
