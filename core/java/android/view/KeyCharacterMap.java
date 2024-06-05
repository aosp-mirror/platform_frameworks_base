/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.hardware.input.InputManagerGlobal;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.method.MetaKeyKeyListener;
import android.util.AndroidRuntimeException;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;

import java.text.Normalizer;

/**
 * Describes the keys provided by a keyboard device and their associated labels.
 */
public class KeyCharacterMap implements Parcelable {
    /**
     * The id of the device's primary built in keyboard is always 0.
     *
     * @deprecated This constant should no longer be used because there is no
     * guarantee that a device has a built-in keyboard that can be used for
     * typing text.  There might not be a built-in keyboard, the built-in keyboard
     * might be a {@link #NUMERIC} or {@link #SPECIAL_FUNCTION} keyboard, or there
     * might be multiple keyboards installed including external keyboards.
     * When interpreting key presses received from the framework, applications should
     * use the device id specified in the {@link KeyEvent} received.
     * When synthesizing key presses for delivery elsewhere or when translating key presses
     * from unknown keyboards, applications should use the special {@link #VIRTUAL_KEYBOARD}
     * device id.
     */
    @Deprecated
    public static final int BUILT_IN_KEYBOARD = 0;

    /**
     * The id of a generic virtual keyboard with a full layout that can be used to
     * synthesize key events.  Typically used with {@link #getEvents}.
     */
    public static final int VIRTUAL_KEYBOARD = -1;

    /**
     * A numeric (12-key) keyboard.
     * <p>
     * A numeric keyboard supports text entry using a multi-tap approach.
     * It may be necessary to tap a key multiple times to generate the desired letter
     * or symbol.
     * </p><p>
     * This type of keyboard is generally designed for thumb typing.
     * </p>
     */
    public static final int NUMERIC = 1;

    /**
     * A keyboard with all the letters, but with more than one letter per key.
     * <p>
     * This type of keyboard is generally designed for thumb typing.
     * </p>
     */
    public static final int PREDICTIVE = 2;

    /**
     * A keyboard with all the letters, and maybe some numbers.
     * <p>
     * An alphabetic keyboard supports text entry directly but may have a condensed
     * layout with a small form factor.  In contrast to a {@link #FULL full keyboard}, some
     * symbols may only be accessible using special on-screen character pickers.
     * In addition, to improve typing speed and accuracy, the framework provides
     * special affordances for alphabetic keyboards such as auto-capitalization
     * and toggled / locked shift and alt keys.
     * </p><p>
     * This type of keyboard is generally designed for thumb typing.
     * </p>
     */
    public static final int ALPHA = 3;

    /**
     * A full PC-style keyboard.
     * <p>
     * A full keyboard behaves like a PC keyboard.  All symbols are accessed directly
     * by pressing keys on the keyboard without on-screen support or affordances such
     * as auto-capitalization.
     * </p><p>
     * This type of keyboard is generally designed for full two hand typing.
     * </p>
     */
    public static final int FULL = 4;

    /**
     * A keyboard that is only used to control special functions rather than for typing.
     * <p>
     * A special function keyboard consists only of non-printing keys such as
     * HOME and POWER that are not actually used for typing.
     * </p>
     */
    public static final int SPECIAL_FUNCTION = 5;

    /**
     * This private-use character is used to trigger Unicode character
     * input by hex digits.
     */
    public static final char HEX_INPUT = '\uEF00';

    /**
     * This private-use character is used to bring up a character picker for
     * miscellaneous symbols.
     */
    public static final char PICKER_DIALOG_INPUT = '\uEF01';

    /**
     * Modifier keys may be chorded with character keys.
     *
     * @see #getModifierBehavior()
     */
    public static final int MODIFIER_BEHAVIOR_CHORDED = 0;

    /**
     * Modifier keys may be chorded with character keys or they may toggle
     * into latched or locked states when pressed independently.
     *
     * @see #getModifierBehavior()
     */
    public static final int MODIFIER_BEHAVIOR_CHORDED_OR_TOGGLED = 1;

    /*
     * This bit will be set in the return value of {@link #get(int, int)} if the
     * key is a "dead key."
     */
    public static final int COMBINING_ACCENT = 0x80000000;

    /**
     * Mask the return value from {@link #get(int, int)} with this value to get
     * a printable representation of the accent character of a "dead key."
     */
    public static final int COMBINING_ACCENT_MASK = 0x7FFFFFFF;

    /* Characters used to display placeholders for dead keys. */
    private static final int ACCENT_ACUTE = '\u00B4';
    private static final int ACCENT_BREVE = '\u02D8';
    private static final int ACCENT_CARON = '\u02C7';
    private static final int ACCENT_CEDILLA = '\u00B8';
    private static final int ACCENT_CIRCUMFLEX = '\u02C6';
    private static final int ACCENT_COMMA_ABOVE = '\u1FBD';
    private static final int ACCENT_COMMA_ABOVE_RIGHT = '\u02BC';
    private static final int ACCENT_DOT_ABOVE = '\u02D9';
    private static final int ACCENT_DOT_BELOW = '.'; // approximate
    private static final int ACCENT_DOUBLE_ACUTE = '\u02DD';
    private static final int ACCENT_GRAVE = '\u02CB';
    private static final int ACCENT_HOOK_ABOVE = '\u02C0';
    private static final int ACCENT_HORN = '\''; // approximate
    private static final int ACCENT_MACRON = '\u00AF';
    private static final int ACCENT_MACRON_BELOW = '\u02CD';
    private static final int ACCENT_OGONEK = '\u02DB';
    private static final int ACCENT_REVERSED_COMMA_ABOVE = '\u02BD';
    private static final int ACCENT_RING_ABOVE = '\u02DA';
    private static final int ACCENT_STROKE = '-'; // approximate
    private static final int ACCENT_TILDE = '\u02DC';
    private static final int ACCENT_TURNED_COMMA_ABOVE = '\u02BB';
    private static final int ACCENT_UMLAUT = '\u00A8';
    private static final int ACCENT_VERTICAL_LINE_ABOVE = '\u02C8';
    private static final int ACCENT_VERTICAL_LINE_BELOW = '\u02CC';
    private static final int ACCENT_APOSTROPHE = '\'';
    private static final int ACCENT_QUOTATION_MARK = '"';

    /* Legacy dead key display characters used in previous versions of the API.
     * We still support these characters by mapping them to their non-legacy version. */
    private static final int ACCENT_GRAVE_LEGACY = '`';
    private static final int ACCENT_CIRCUMFLEX_LEGACY = '^';
    private static final int ACCENT_TILDE_LEGACY = '~';

    private static final int CHAR_SPACE = ' ';

    /**
     * Maps Unicode combining diacritical to display-form dead key.
     */
    private static final SparseIntArray sCombiningToAccent = new SparseIntArray();
    private static final SparseIntArray sAccentToCombining = new SparseIntArray();
    static {
        addCombining('\u0300', ACCENT_GRAVE);
        addCombining('\u0301', ACCENT_ACUTE);
        addCombining('\u0302', ACCENT_CIRCUMFLEX);
        addCombining('\u0303', ACCENT_TILDE);
        addCombining('\u0304', ACCENT_MACRON);
        addCombining('\u0306', ACCENT_BREVE);
        addCombining('\u0307', ACCENT_DOT_ABOVE);
        addCombining('\u0308', ACCENT_UMLAUT);
        addCombining('\u0309', ACCENT_HOOK_ABOVE);
        addCombining('\u030A', ACCENT_RING_ABOVE);
        addCombining('\u030B', ACCENT_DOUBLE_ACUTE);
        addCombining('\u030C', ACCENT_CARON);
        //addCombining('\u030F', ACCENT_DOUBLE_GRAVE);
        //addCombining('\u0310', ACCENT_CANDRABINDU);
        //addCombining('\u0311', ACCENT_INVERTED_BREVE);
        addCombining('\u0312', ACCENT_TURNED_COMMA_ABOVE);
        addCombining('\u0313', ACCENT_COMMA_ABOVE);
        addCombining('\u0314', ACCENT_REVERSED_COMMA_ABOVE);
        addCombining('\u0315', ACCENT_COMMA_ABOVE_RIGHT);
        addCombining('\u031B', ACCENT_HORN);
        addCombining('\u0323', ACCENT_DOT_BELOW);
        //addCombining('\u0326', ACCENT_COMMA_BELOW);
        addCombining('\u0327', ACCENT_CEDILLA);
        addCombining('\u0328', ACCENT_OGONEK);
        addCombining('\u0329', ACCENT_VERTICAL_LINE_BELOW);
        addCombining('\u0331', ACCENT_MACRON_BELOW);
        addCombining('\u0335', ACCENT_STROKE);
        //addCombining('\u0342', ACCENT_PERISPOMENI);
        //addCombining('\u0344', ACCENT_DIALYTIKA_TONOS);
        //addCombining('\u0345', ACCENT_YPOGEGRAMMENI);

        // One-way mappings to equivalent preferred accents.
        sCombiningToAccent.append('\u0340', ACCENT_GRAVE);
        sCombiningToAccent.append('\u0341', ACCENT_ACUTE);
        sCombiningToAccent.append('\u0343', ACCENT_COMMA_ABOVE);
        sCombiningToAccent.append('\u030D', ACCENT_APOSTROPHE);
        sCombiningToAccent.append('\u030E', ACCENT_QUOTATION_MARK);

        // One-way legacy mappings to preserve compatibility with older applications.
        sAccentToCombining.append(ACCENT_GRAVE_LEGACY, '\u0300');
        sAccentToCombining.append(ACCENT_CIRCUMFLEX_LEGACY, '\u0302');
        sAccentToCombining.append(ACCENT_TILDE_LEGACY, '\u0303');

        // One-way mappings to use the preferred accent
        sAccentToCombining.append(ACCENT_APOSTROPHE, '\u0301');
        sAccentToCombining.append(ACCENT_QUOTATION_MARK, '\u0308');
    }

    private static void addCombining(int combining, int accent) {
        sCombiningToAccent.append(combining, accent);
        sAccentToCombining.append(accent, combining);
    }

    /**
     * Maps combinations of (display-form) combining key and second character
     * to combined output character.
     * These mappings are derived from the Unicode NFC tables as needed.
     */
    private static final SparseIntArray sDeadKeyCache = new SparseIntArray();
    private static final StringBuilder sDeadKeyBuilder = new StringBuilder();
    static {
        // Non-standard decompositions.
        // Stroke modifier for Finnish multilingual keyboard and others.
        addDeadKey(ACCENT_STROKE, 'D', '\u0110');
        addDeadKey(ACCENT_STROKE, 'G', '\u01e4');
        addDeadKey(ACCENT_STROKE, 'H', '\u0126');
        addDeadKey(ACCENT_STROKE, 'I', '\u0197');
        addDeadKey(ACCENT_STROKE, 'L', '\u0141');
        addDeadKey(ACCENT_STROKE, 'O', '\u00d8');
        addDeadKey(ACCENT_STROKE, 'T', '\u0166');
        addDeadKey(ACCENT_STROKE, 'd', '\u0111');
        addDeadKey(ACCENT_STROKE, 'g', '\u01e5');
        addDeadKey(ACCENT_STROKE, 'h', '\u0127');
        addDeadKey(ACCENT_STROKE, 'i', '\u0268');
        addDeadKey(ACCENT_STROKE, 'l', '\u0142');
        addDeadKey(ACCENT_STROKE, 'o', '\u00f8');
        addDeadKey(ACCENT_STROKE, 't', '\u0167');
    }

    private static void addDeadKey(int accent, int c, int result) {
        final int combining = sAccentToCombining.get(accent);
        if (combining == 0) {
            throw new IllegalStateException("Invalid dead key declaration.");
        }
        final int combination = (combining << 16) | c;
        sDeadKeyCache.put(combination, result);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<KeyCharacterMap> CREATOR =
            new Parcelable.Creator<KeyCharacterMap>() {
        public KeyCharacterMap createFromParcel(Parcel in) {
            return new KeyCharacterMap(in);
        }
        public KeyCharacterMap[] newArray(int size) {
            return new KeyCharacterMap[size];
        }
    };

    private long mPtr;

    private static native long nativeReadFromParcel(Parcel in);
    private static native void nativeWriteToParcel(long ptr, Parcel out);
    private static native void nativeDispose(long ptr);

    private static native char nativeGetCharacter(long ptr, int keyCode, int metaState);
    private static native boolean nativeGetFallbackAction(long ptr, int keyCode, int metaState,
            FallbackAction outFallbackAction);
    private static native char nativeGetNumber(long ptr, int keyCode);
    private static native char nativeGetMatch(long ptr, int keyCode, char[] chars, int metaState);
    private static native char nativeGetDisplayLabel(long ptr, int keyCode);
    private static native int nativeGetKeyboardType(long ptr);
    private static native KeyEvent[] nativeGetEvents(long ptr, char[] chars);
    private static native KeyCharacterMap nativeObtainEmptyKeyCharacterMap(int deviceId);
    private static native boolean nativeEquals(long ptr1, long ptr2);

    private static native void nativeApplyOverlay(long ptr, String layoutDescriptor,
            String overlay);
    private static native int nativeGetMappedKey(long ptr, int scanCode);

    private KeyCharacterMap(Parcel in) {
        if (in == null) {
            throw new IllegalArgumentException("parcel must not be null");
        }
        mPtr = nativeReadFromParcel(in);
        if (mPtr == 0) {
            throw new RuntimeException("Could not read KeyCharacterMap from parcel.");
        }
    }

    // Called from native
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private KeyCharacterMap(long ptr) {
        mPtr = ptr;
    }

    @Override
    protected void finalize() throws Throwable {
        if (mPtr != 0) {
            nativeDispose(mPtr);
            mPtr = 0;
        }
    }

    /**
     * Obtain empty key character map
     * @param deviceId The input device ID
     * @return The KeyCharacterMap object
     * @hide
     */
    @VisibleForTesting
    @Nullable
    public static KeyCharacterMap obtainEmptyMap(int deviceId) {
        return nativeObtainEmptyKeyCharacterMap(deviceId);
    }

    /**
     * Loads the key character maps for the keyboard with the specified device id.
     *
     * @param deviceId The device id of the keyboard.
     * @return The associated key character map.
     * @throws {@link UnavailableException} if the key character map
     * could not be loaded because it was malformed or the default key character map
     * is missing from the system.
     */
    public static KeyCharacterMap load(int deviceId) {
        final InputManagerGlobal im = InputManagerGlobal.getInstance();
        InputDevice inputDevice = im.getInputDevice(deviceId);
        if (inputDevice == null) {
            inputDevice = im.getInputDevice(VIRTUAL_KEYBOARD);
            if (inputDevice == null) {
                throw new UnavailableException(
                        "Could not load key character map for device " + deviceId);
            }
        }
        return inputDevice.getKeyCharacterMap();
    }

    /**
     * Loads the key character map with applied KCM overlay.
     *
     * @param layoutDescriptor descriptor of the applied overlay KCM
     * @param overlay          string describing the overlay KCM
     * @return The resultant key character map.
     * @throws {@link UnavailableException} if the key character map
     *                could not be loaded because it was malformed or the default key character map
     *                is missing from the system.
     * @hide
     */
    public static KeyCharacterMap load(@NonNull String layoutDescriptor, @NonNull String overlay) {
        KeyCharacterMap kcm = KeyCharacterMap.load(VIRTUAL_KEYBOARD);
        kcm.applyOverlay(layoutDescriptor, overlay);
        return kcm;
    }

    private void applyOverlay(@NonNull String layoutDescriptor, @NonNull String overlay) {
        nativeApplyOverlay(mPtr, layoutDescriptor, overlay);
    }

    /**
     * Gets the mapped key for the provided scan code. Returns the provided default if no mapping
     * found in the KeyCharacterMap.
     *
     * @hide
     */
    public int getMappedKeyOrDefault(int scanCode, int defaultKeyCode) {
        int keyCode = nativeGetMappedKey(mPtr, scanCode);
        return keyCode == KeyEvent.KEYCODE_UNKNOWN ? defaultKeyCode : keyCode;
    }

    /**
     * Gets the Unicode character generated by the specified key and meta
     * key state combination.
     * <p>
     * Returns the Unicode character that the specified key would produce
     * when the specified meta bits (see {@link MetaKeyKeyListener})
     * were active.
     * </p><p>
     * Returns 0 if the key is not one that is used to type Unicode
     * characters.
     * </p><p>
     * If the return value has bit {@link #COMBINING_ACCENT} set, the
     * key is a "dead key" that should be combined with another to
     * actually produce a character -- see {@link #getDeadChar} --
     * after masking with {@link #COMBINING_ACCENT_MASK}.
     * </p>
     *
     * @param keyCode The key code.
     * @param metaState The meta key modifier state.
     * @return The associated character or combining accent, or 0 if none.
     */
    public int get(int keyCode, int metaState) {
        metaState = KeyEvent.normalizeMetaState(metaState);
        char ch = nativeGetCharacter(mPtr, keyCode, metaState);

        int map = sCombiningToAccent.get(ch);
        if (map != 0) {
            return map | COMBINING_ACCENT;
        } else {
            return ch;
        }
    }

    /**
     * Gets the fallback action to perform if the application does not
     * handle the specified key.
     * <p>
     * When an application does not handle a particular key, the system may
     * translate the key to an alternate fallback key (specified in the
     * fallback action) and dispatch it to the application.
     * The event containing the fallback key is flagged
     * with {@link KeyEvent#FLAG_FALLBACK}.
     * </p>
     *
     * @param keyCode The key code.
     * @param metaState The meta key modifier state.
     * @return The fallback action, or null if none.  Remember to recycle the fallback action.
     *
     * @hide
     */
    public FallbackAction getFallbackAction(int keyCode, int metaState) {
        FallbackAction action = FallbackAction.obtain();
        metaState = KeyEvent.normalizeMetaState(metaState);
        if (nativeGetFallbackAction(mPtr, keyCode, metaState, action)) {
            action.metaState = KeyEvent.normalizeMetaState(action.metaState);
            return action;
        }
        action.recycle();
        return null;
    }

    /**
     * Gets the number or symbol associated with the key.
     * <p>
     * The character value is returned, not the numeric value.
     * If the key is not a number, but is a symbol, the symbol is retuned.
     * </p><p>
     * This method is intended to to support dial pads and other numeric or
     * symbolic entry on keyboards where certain keys serve dual function
     * as alphabetic and symbolic keys.  This method returns the number
     * or symbol associated with the key independent of whether the user
     * has pressed the required modifier.
     * </p><p>
     * For example, on one particular keyboard the keys on the top QWERTY row generate
     * numbers when ALT is pressed such that ALT-Q maps to '1'.  So for that keyboard
     * when {@link #getNumber} is called with {@link KeyEvent#KEYCODE_Q} it returns '1'
     * so that the user can type numbers without pressing ALT when it makes sense.
     * </p>
     *
     * @param keyCode The key code.
     * @return The associated numeric or symbolic character, or 0 if none.
     */
    public char getNumber(int keyCode) {
        return nativeGetNumber(mPtr, keyCode);
    }

    /**
     * Gets the first character in the character array that can be generated
     * by the specified key code.
     * <p>
     * This is a convenience function that returns the same value as
     * {@link #getMatch(int,char[],int) getMatch(keyCode, chars, 0)}.
     * </p>
     *
     * @param keyCode The keycode.
     * @param chars The array of matching characters to consider.
     * @return The matching associated character, or 0 if none.
     * @throws {@link IllegalArgumentException} if the passed array of characters is null.
     */
    public char getMatch(int keyCode, char[] chars) {
        return getMatch(keyCode, chars, 0);
    }

    /**
     * Gets the first character in the character array that can be generated
     * by the specified key code.  If there are multiple choices, prefers
     * the one that would be generated with the specified meta key modifier state.
     *
     * @param keyCode The key code.
     * @param chars The array of matching characters to consider.
     * @param metaState The preferred meta key modifier state.
     * @return The matching associated character, or 0 if none.
     * @throws {@link IllegalArgumentException} if the passed array of characters is null.
     */
    public char getMatch(int keyCode, char[] chars, int metaState) {
        if (chars == null) {
            throw new IllegalArgumentException("chars must not be null.");
        }

        metaState = KeyEvent.normalizeMetaState(metaState);
        return nativeGetMatch(mPtr, keyCode, chars, metaState);
    }

    /**
     * Gets the primary character for this key.
     * In other words, the label that is physically printed on it.
     *
     * @param keyCode The key code.
     * @return The display label character, or 0 if none (eg. for non-printing keys).
     */
    public char getDisplayLabel(int keyCode) {
        return nativeGetDisplayLabel(mPtr, keyCode);
    }

    /**
     * Get the character that is produced by combining the dead key producing accent
     * with the key producing character c.
     * For example, getDeadChar('`', 'e') returns &egrave;.
     * getDeadChar('^', ' ') returns '^' and getDeadChar('^', '^') returns '^'.
     *
     * @param accent The accent character.  eg. '`'
     * @param c The basic character.
     * @return The combined character, or 0 if the characters cannot be combined.
     */
    public static int getDeadChar(int accent, int c) {
        if (c == accent || CHAR_SPACE == c) {
            // The same dead character typed twice or a dead character followed by a
            // space should both produce the non-combining version of the combining char.
            // In this case we don't even need to compute the combining character.
            return accent;
        }

        int combining = sAccentToCombining.get(accent);
        if (combining == 0) {
            return 0;
        }

        final int combination = (combining << 16) | c;
        int combined;
        synchronized (sDeadKeyCache) {
            combined = sDeadKeyCache.get(combination, -1);
            if (combined == -1) {
                sDeadKeyBuilder.setLength(0);
                sDeadKeyBuilder.append((char)c);
                sDeadKeyBuilder.append((char)combining);
                String result = Normalizer.normalize(sDeadKeyBuilder, Normalizer.Form.NFC);
                combined = result.codePointCount(0, result.length()) == 1
                        ? result.codePointAt(0) : 0;
                sDeadKeyCache.put(combination, combined);
            }
        }
        return combined;
    }

    /**
     * Get the combining character that corresponds with the provided accent.
     *
     * @param accent The accent character.  eg. '`'
     * @return The combining character
     * @hide
     */
    public static int getCombiningChar(int accent) {
        return sAccentToCombining.get(accent);
    }

    /**
     * Describes the character mappings associated with a key.
     *
     * @deprecated instead use {@link KeyCharacterMap#getDisplayLabel(int)},
     * {@link KeyCharacterMap#getNumber(int)} and {@link KeyCharacterMap#get(int, int)}.
     */
    @Deprecated
    public static class KeyData {
        public static final int META_LENGTH = 4;

        /**
         * The display label (see {@link #getDisplayLabel}).
         */
        public char displayLabel;
        /**
         * The "number" value (see {@link #getNumber}).
         */
        public char number;
        /**
         * The character that will be generated in various meta states
         * (the same ones used for {@link #get} and defined as
         * {@link KeyEvent#META_SHIFT_ON} and {@link KeyEvent#META_ALT_ON}).
         *      <table>
         *          <tr><th>Index</th><th align="left">Value</th></tr>
         *          <tr><td>0</td><td>no modifiers</td></tr>
         *          <tr><td>1</td><td>caps</td></tr>
         *          <tr><td>2</td><td>alt</td></tr>
         *          <tr><td>3</td><td>caps + alt</td></tr>
         *      </table>
         */
        public char[] meta = new char[META_LENGTH];
    }

    /**
     * Get the character conversion data for a given key code.
     *
     * @param keyCode The keyCode to query.
     * @param results A {@link KeyData} instance that will be filled with the results.
     * @return True if the key was mapped.  If the key was not mapped, results is not modified.
     *
     * @deprecated instead use {@link KeyCharacterMap#getDisplayLabel(int)},
     * {@link KeyCharacterMap#getNumber(int)} or {@link KeyCharacterMap#get(int, int)}.
     */
    @Deprecated
    public boolean getKeyData(int keyCode, KeyData results) {
        if (results.meta.length < KeyData.META_LENGTH) {
            throw new IndexOutOfBoundsException(
                    "results.meta.length must be >= " + KeyData.META_LENGTH);
        }

        char displayLabel = nativeGetDisplayLabel(mPtr, keyCode);
        if (displayLabel == 0) {
            return false;
        }

        results.displayLabel = displayLabel;
        results.number = nativeGetNumber(mPtr, keyCode);
        results.meta[0] = nativeGetCharacter(mPtr, keyCode, 0);
        results.meta[1] = nativeGetCharacter(mPtr, keyCode, KeyEvent.META_SHIFT_ON);
        results.meta[2] = nativeGetCharacter(mPtr, keyCode, KeyEvent.META_ALT_ON);
        results.meta[3] = nativeGetCharacter(mPtr, keyCode,
                KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON);
        return true;
    }

    /**
     * Get an array of KeyEvent objects that if put into the input stream
     * could plausibly generate the provided sequence of characters.  It is
     * not guaranteed that the sequence is the only way to generate these
     * events or that it is optimal.
     * <p>
     * This function is primarily offered for instrumentation and testing purposes.
     * It may fail to map characters to key codes.  In particular, the key character
     * map for the {@link #BUILT_IN_KEYBOARD built-in keyboard} device id may be empty.
     * Consider using the key character map associated with the
     * {@link #VIRTUAL_KEYBOARD virtual keyboard} device id instead.
     * </p><p>
     * For robust text entry, do not use this function.  Instead construct a
     * {@link KeyEvent} with action code {@link KeyEvent#ACTION_MULTIPLE} that contains
     * the desired string using {@link KeyEvent#KeyEvent(long, String, int, int)}.
     * </p>
     *
     * @param chars The sequence of characters to generate.
     * @return An array of {@link KeyEvent} objects, or null if the given char array
     *         can not be generated using the current key character map.
     * @throws {@link IllegalArgumentException} if the passed array of characters is null.
     */
    public KeyEvent[] getEvents(char[] chars) {
        if (chars == null) {
            throw new IllegalArgumentException("chars must not be null.");
        }
        return nativeGetEvents(mPtr, chars);
    }

    /**
     * Returns true if the specified key produces a glyph.
     *
     * @param keyCode The key code.
     * @return True if the key is a printing key.
     */
    public boolean isPrintingKey(int keyCode) {
        int type = Character.getType(nativeGetDisplayLabel(mPtr, keyCode));

        switch (type)
        {
            case Character.SPACE_SEPARATOR:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.CONTROL:
            case Character.FORMAT:
                return false;
            default:
                return true;
        }
    }

    /**
     * Gets the keyboard type.
     * Returns {@link #NUMERIC}, {@link #PREDICTIVE}, {@link #ALPHA}, {@link #FULL}
     * or {@link #SPECIAL_FUNCTION}.
     * <p>
     * Different keyboard types have different semantics.  Refer to the documentation
     * associated with the keyboard type constants for details.
     * </p>
     *
     * @return The keyboard type.
     */
    public int getKeyboardType() {
        return nativeGetKeyboardType(mPtr);
    }

    /**
     * Gets a constant that describes the behavior of this keyboard's modifier keys
     * such as {@link KeyEvent#KEYCODE_SHIFT_LEFT}.
     * <p>
     * Currently there are two behaviors that may be combined:
     * </p>
     * <ul>
     * <li>Chorded behavior: When the modifier key is pressed together with one or more
     * character keys, the keyboard inserts the modified keys and
     * then resets the modifier state when the modifier key is released.</li>
     * <li>Toggled behavior: When the modifier key is pressed and released on its own
     * it first toggles into a latched state.  When latched, the modifier will apply
     * to next character key that is pressed and will then reset itself to the initial state.
     * If the modifier is already latched and the modifier key is pressed and release on
     * its own again, then it toggles into a locked state.  When locked, the modifier will
     * apply to all subsequent character keys that are pressed until unlocked by pressing
     * the modifier key on its own one more time to reset it to the initial state.
     * Toggled behavior is useful for small profile keyboards designed for thumb typing.
     * </ul>
     * <p>
     * This function currently returns {@link #MODIFIER_BEHAVIOR_CHORDED} when the
     * {@link #getKeyboardType() keyboard type} is {@link #FULL} or {@link #SPECIAL_FUNCTION} and
     * {@link #MODIFIER_BEHAVIOR_CHORDED_OR_TOGGLED} otherwise.
     * In the future, the function may also take into account global keyboard
     * accessibility settings, other user preferences, or new device capabilities.
     * </p>
     *
     * @return The modifier behavior for this keyboard.
     *
     * @see #MODIFIER_BEHAVIOR_CHORDED
     * @see #MODIFIER_BEHAVIOR_CHORDED_OR_TOGGLED
     */
    public int getModifierBehavior() {
        switch (getKeyboardType()) {
            case FULL:
            case SPECIAL_FUNCTION:
                return MODIFIER_BEHAVIOR_CHORDED;
            default:
                return MODIFIER_BEHAVIOR_CHORDED_OR_TOGGLED;
        }
    }

    /**
     * Queries the framework about whether any physical keys exist on any currently attached input
     * devices that are capable of producing the given key code.
     *
     * @param keyCode The key code to query.
     * @return True if at least one attached keyboard supports the specified key code.
     */
    public static boolean deviceHasKey(int keyCode) {
        return InputManagerGlobal.getInstance().deviceHasKeys(new int[] { keyCode })[0];
    }

    /**
     * Queries the framework about whether any physical keys exist on any currently attached input
     * devices that are capable of producing the given array of key codes.
     *
     * @param keyCodes The array of key codes to query.
     * @return A new array of the same size as the key codes array whose elements
     * are set to true if at least one attached keyboard supports the corresponding key code
     * at the same index in the key codes array.
     */
    public static boolean[] deviceHasKeys(int[] keyCodes) {
        return InputManagerGlobal.getInstance().deviceHasKeys(keyCodes);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (out == null) {
            throw new IllegalArgumentException("parcel must not be null");
        }
        nativeWriteToParcel(mPtr, out);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof KeyCharacterMap)) {
            return false;
        }
        KeyCharacterMap peer = (KeyCharacterMap) obj;
        if (mPtr == 0 || peer.mPtr == 0) {
            return mPtr == peer.mPtr;
        }
        return nativeEquals(mPtr, peer.mPtr);
    }

    /**
     * Thrown by {@link KeyCharacterMap#load} when a key character map could not be loaded.
     */
    public static class UnavailableException extends AndroidRuntimeException {
        public UnavailableException(String msg) {
            super(msg);
        }
    }

    /**
     * Specifies a substitute key code and meta state as a fallback action
     * for an unhandled key.
     * @hide
     */
    public static final class FallbackAction {
        private static final int MAX_RECYCLED = 10;
        private static final Object sRecycleLock = new Object();
        private static FallbackAction sRecycleBin;
        private static int sRecycledCount;

        private FallbackAction next;

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int keyCode;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int metaState;

        private FallbackAction() {
        }

        public static FallbackAction obtain() {
            final FallbackAction target;
            synchronized (sRecycleLock) {
                if (sRecycleBin == null) {
                    target = new FallbackAction();
                } else {
                    target = sRecycleBin;
                    sRecycleBin = target.next;
                    sRecycledCount--;
                    target.next = null;
                }
            }
            return target;
        }

        public void recycle() {
            synchronized (sRecycleLock) {
                if (sRecycledCount < MAX_RECYCLED) {
                    next = sRecycleBin;
                    sRecycleBin = this;
                    sRecycledCount += 1;
                } else {
                    next = null;
                }
            }
        }
    }
}
