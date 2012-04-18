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

import android.os.Parcel;
import android.os.Parcelable;
import android.text.method.MetaKeyKeyListener;
import android.util.AndroidRuntimeException;
import android.util.SparseIntArray;
import android.hardware.input.InputManager;

import java.lang.Character;

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
     * @see {#link #getModifierBehavior()} for more details.
     */
    public static final int MODIFIER_BEHAVIOR_CHORDED = 0;

    /**
     * Modifier keys may be chorded with character keys or they may toggle
     * into latched or locked states when pressed independently.
     *
     * @see {#link #getModifierBehavior()} for more details.
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
    private static final int ACCENT_GRAVE = '\u02CB';
    private static final int ACCENT_CIRCUMFLEX = '\u02C6';
    private static final int ACCENT_TILDE = '\u02DC';
    private static final int ACCENT_UMLAUT = '\u00A8';

    /* Legacy dead key display characters used in previous versions of the API.
     * We still support these characters by mapping them to their non-legacy version. */
    private static final int ACCENT_GRAVE_LEGACY = '`';
    private static final int ACCENT_CIRCUMFLEX_LEGACY = '^';
    private static final int ACCENT_TILDE_LEGACY = '~';

    /**
     * Maps Unicode combining diacritical to display-form dead key
     * (display character shifted left 16 bits).
     */
    private static final SparseIntArray COMBINING = new SparseIntArray();
    static {
        COMBINING.put('\u0300', ACCENT_GRAVE);
        COMBINING.put('\u0301', ACCENT_ACUTE);
        COMBINING.put('\u0302', ACCENT_CIRCUMFLEX);
        COMBINING.put('\u0303', ACCENT_TILDE);
        COMBINING.put('\u0308', ACCENT_UMLAUT);
    }

    /**
     * Maps combinations of (display-form) dead key and second character
     * to combined output character.
     */
    private static final SparseIntArray DEAD = new SparseIntArray();
    static {
        addDeadChar(ACCENT_ACUTE, 'A', '\u00C1');
        addDeadChar(ACCENT_ACUTE, 'C', '\u0106');
        addDeadChar(ACCENT_ACUTE, 'E', '\u00C9');
        addDeadChar(ACCENT_ACUTE, 'G', '\u01F4');
        addDeadChar(ACCENT_ACUTE, 'I', '\u00CD');
        addDeadChar(ACCENT_ACUTE, 'K', '\u1E30');
        addDeadChar(ACCENT_ACUTE, 'L', '\u0139');
        addDeadChar(ACCENT_ACUTE, 'M', '\u1E3E');
        addDeadChar(ACCENT_ACUTE, 'N', '\u0143');
        addDeadChar(ACCENT_ACUTE, 'O', '\u00D3');
        addDeadChar(ACCENT_ACUTE, 'P', '\u1E54');
        addDeadChar(ACCENT_ACUTE, 'R', '\u0154');
        addDeadChar(ACCENT_ACUTE, 'S', '\u015A');
        addDeadChar(ACCENT_ACUTE, 'U', '\u00DA');
        addDeadChar(ACCENT_ACUTE, 'W', '\u1E82');
        addDeadChar(ACCENT_ACUTE, 'Y', '\u00DD');
        addDeadChar(ACCENT_ACUTE, 'Z', '\u0179');
        addDeadChar(ACCENT_ACUTE, 'a', '\u00E1');
        addDeadChar(ACCENT_ACUTE, 'c', '\u0107');
        addDeadChar(ACCENT_ACUTE, 'e', '\u00E9');
        addDeadChar(ACCENT_ACUTE, 'g', '\u01F5');
        addDeadChar(ACCENT_ACUTE, 'i', '\u00ED');
        addDeadChar(ACCENT_ACUTE, 'k', '\u1E31');
        addDeadChar(ACCENT_ACUTE, 'l', '\u013A');
        addDeadChar(ACCENT_ACUTE, 'm', '\u1E3F');
        addDeadChar(ACCENT_ACUTE, 'n', '\u0144');
        addDeadChar(ACCENT_ACUTE, 'o', '\u00F3');
        addDeadChar(ACCENT_ACUTE, 'p', '\u1E55');
        addDeadChar(ACCENT_ACUTE, 'r', '\u0155');
        addDeadChar(ACCENT_ACUTE, 's', '\u015B');
        addDeadChar(ACCENT_ACUTE, 'u', '\u00FA');
        addDeadChar(ACCENT_ACUTE, 'w', '\u1E83');
        addDeadChar(ACCENT_ACUTE, 'y', '\u00FD');
        addDeadChar(ACCENT_ACUTE, 'z', '\u017A');
        addDeadChar(ACCENT_CIRCUMFLEX, 'A', '\u00C2');
        addDeadChar(ACCENT_CIRCUMFLEX, 'C', '\u0108');
        addDeadChar(ACCENT_CIRCUMFLEX, 'E', '\u00CA');
        addDeadChar(ACCENT_CIRCUMFLEX, 'G', '\u011C');
        addDeadChar(ACCENT_CIRCUMFLEX, 'H', '\u0124');
        addDeadChar(ACCENT_CIRCUMFLEX, 'I', '\u00CE');
        addDeadChar(ACCENT_CIRCUMFLEX, 'J', '\u0134');
        addDeadChar(ACCENT_CIRCUMFLEX, 'O', '\u00D4');
        addDeadChar(ACCENT_CIRCUMFLEX, 'S', '\u015C');
        addDeadChar(ACCENT_CIRCUMFLEX, 'U', '\u00DB');
        addDeadChar(ACCENT_CIRCUMFLEX, 'W', '\u0174');
        addDeadChar(ACCENT_CIRCUMFLEX, 'Y', '\u0176');
        addDeadChar(ACCENT_CIRCUMFLEX, 'Z', '\u1E90');
        addDeadChar(ACCENT_CIRCUMFLEX, 'a', '\u00E2');
        addDeadChar(ACCENT_CIRCUMFLEX, 'c', '\u0109');
        addDeadChar(ACCENT_CIRCUMFLEX, 'e', '\u00EA');
        addDeadChar(ACCENT_CIRCUMFLEX, 'g', '\u011D');
        addDeadChar(ACCENT_CIRCUMFLEX, 'h', '\u0125');
        addDeadChar(ACCENT_CIRCUMFLEX, 'i', '\u00EE');
        addDeadChar(ACCENT_CIRCUMFLEX, 'j', '\u0135');
        addDeadChar(ACCENT_CIRCUMFLEX, 'o', '\u00F4');
        addDeadChar(ACCENT_CIRCUMFLEX, 's', '\u015D');
        addDeadChar(ACCENT_CIRCUMFLEX, 'u', '\u00FB');
        addDeadChar(ACCENT_CIRCUMFLEX, 'w', '\u0175');
        addDeadChar(ACCENT_CIRCUMFLEX, 'y', '\u0177');
        addDeadChar(ACCENT_CIRCUMFLEX, 'z', '\u1E91');
        addDeadChar(ACCENT_GRAVE, 'A', '\u00C0');
        addDeadChar(ACCENT_GRAVE, 'E', '\u00C8');
        addDeadChar(ACCENT_GRAVE, 'I', '\u00CC');
        addDeadChar(ACCENT_GRAVE, 'N', '\u01F8');
        addDeadChar(ACCENT_GRAVE, 'O', '\u00D2');
        addDeadChar(ACCENT_GRAVE, 'U', '\u00D9');
        addDeadChar(ACCENT_GRAVE, 'W', '\u1E80');
        addDeadChar(ACCENT_GRAVE, 'Y', '\u1EF2');
        addDeadChar(ACCENT_GRAVE, 'a', '\u00E0');
        addDeadChar(ACCENT_GRAVE, 'e', '\u00E8');
        addDeadChar(ACCENT_GRAVE, 'i', '\u00EC');
        addDeadChar(ACCENT_GRAVE, 'n', '\u01F9');
        addDeadChar(ACCENT_GRAVE, 'o', '\u00F2');
        addDeadChar(ACCENT_GRAVE, 'u', '\u00F9');
        addDeadChar(ACCENT_GRAVE, 'w', '\u1E81');
        addDeadChar(ACCENT_GRAVE, 'y', '\u1EF3');
        addDeadChar(ACCENT_TILDE, 'A', '\u00C3');
        addDeadChar(ACCENT_TILDE, 'E', '\u1EBC');
        addDeadChar(ACCENT_TILDE, 'I', '\u0128');
        addDeadChar(ACCENT_TILDE, 'N', '\u00D1');
        addDeadChar(ACCENT_TILDE, 'O', '\u00D5');
        addDeadChar(ACCENT_TILDE, 'U', '\u0168');
        addDeadChar(ACCENT_TILDE, 'V', '\u1E7C');
        addDeadChar(ACCENT_TILDE, 'Y', '\u1EF8');
        addDeadChar(ACCENT_TILDE, 'a', '\u00E3');
        addDeadChar(ACCENT_TILDE, 'e', '\u1EBD');
        addDeadChar(ACCENT_TILDE, 'i', '\u0129');
        addDeadChar(ACCENT_TILDE, 'n', '\u00F1');
        addDeadChar(ACCENT_TILDE, 'o', '\u00F5');
        addDeadChar(ACCENT_TILDE, 'u', '\u0169');
        addDeadChar(ACCENT_TILDE, 'v', '\u1E7D');
        addDeadChar(ACCENT_TILDE, 'y', '\u1EF9');
        addDeadChar(ACCENT_UMLAUT, 'A', '\u00C4');
        addDeadChar(ACCENT_UMLAUT, 'E', '\u00CB');
        addDeadChar(ACCENT_UMLAUT, 'H', '\u1E26');
        addDeadChar(ACCENT_UMLAUT, 'I', '\u00CF');
        addDeadChar(ACCENT_UMLAUT, 'O', '\u00D6');
        addDeadChar(ACCENT_UMLAUT, 'U', '\u00DC');
        addDeadChar(ACCENT_UMLAUT, 'W', '\u1E84');
        addDeadChar(ACCENT_UMLAUT, 'X', '\u1E8C');
        addDeadChar(ACCENT_UMLAUT, 'Y', '\u0178');
        addDeadChar(ACCENT_UMLAUT, 'a', '\u00E4');
        addDeadChar(ACCENT_UMLAUT, 'e', '\u00EB');
        addDeadChar(ACCENT_UMLAUT, 'h', '\u1E27');
        addDeadChar(ACCENT_UMLAUT, 'i', '\u00EF');
        addDeadChar(ACCENT_UMLAUT, 'o', '\u00F6');
        addDeadChar(ACCENT_UMLAUT, 't', '\u1E97');
        addDeadChar(ACCENT_UMLAUT, 'u', '\u00FC');
        addDeadChar(ACCENT_UMLAUT, 'w', '\u1E85');
        addDeadChar(ACCENT_UMLAUT, 'x', '\u1E8D');
        addDeadChar(ACCENT_UMLAUT, 'y', '\u00FF');
    }

    public static final Parcelable.Creator<KeyCharacterMap> CREATOR =
            new Parcelable.Creator<KeyCharacterMap>() {
        public KeyCharacterMap createFromParcel(Parcel in) {
            return new KeyCharacterMap(in);
        }
        public KeyCharacterMap[] newArray(int size) {
            return new KeyCharacterMap[size];
        }
    };

    private int mPtr;

    private static native int nativeReadFromParcel(Parcel in);
    private static native void nativeWriteToParcel(int ptr, Parcel out);
    private static native void nativeDispose(int ptr);

    private static native char nativeGetCharacter(int ptr, int keyCode, int metaState);
    private static native boolean nativeGetFallbackAction(int ptr, int keyCode, int metaState,
            FallbackAction outFallbackAction);
    private static native char nativeGetNumber(int ptr, int keyCode);
    private static native char nativeGetMatch(int ptr, int keyCode, char[] chars, int metaState);
    private static native char nativeGetDisplayLabel(int ptr, int keyCode);
    private static native int nativeGetKeyboardType(int ptr);
    private static native KeyEvent[] nativeGetEvents(int ptr, char[] chars);

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
    private KeyCharacterMap(int ptr) {
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
     * Loads the key character maps for the keyboard with the specified device id.
     *
     * @param deviceId The device id of the keyboard.
     * @return The associated key character map.
     * @throws {@link UnavailableException} if the key character map
     * could not be loaded because it was malformed or the default key character map
     * is missing from the system.
     */
    public static KeyCharacterMap load(int deviceId) {
        final InputManager im = InputManager.getInstance();
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

        int map = COMBINING.get(ch);
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
     * @param outFallbackAction The fallback action object to populate.
     * @return True if a fallback action was found, false otherwise.
     *
     * @hide
     */
    public boolean getFallbackAction(int keyCode, int metaState,
            FallbackAction outFallbackAction) {
        if (outFallbackAction == null) {
            throw new IllegalArgumentException("fallbackAction must not be null");
        }

        metaState = KeyEvent.normalizeMetaState(metaState);
        return nativeGetFallbackAction(mPtr, keyCode, metaState, outFallbackAction);
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
     * Get the character that is produced by putting accent on the character c.
     * For example, getDeadChar('`', 'e') returns &egrave;.
     *
     * @param accent The accent character.  eg. '`'
     * @param c The basic character.
     * @return The combined character, or 0 if the characters cannot be combined.
     */
    public static int getDeadChar(int accent, int c) {
        if (accent == ACCENT_CIRCUMFLEX_LEGACY) {
            accent = ACCENT_CIRCUMFLEX;
        } else if (accent == ACCENT_GRAVE_LEGACY) {
            accent = ACCENT_GRAVE;
        } else if (accent == ACCENT_TILDE_LEGACY) {
            accent = ACCENT_TILDE;
        }
        return DEAD.get((accent << 16) | c);
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
     * @see {@link #MODIFIER_BEHAVIOR_CHORDED}
     * @see {@link #MODIFIER_BEHAVIOR_CHORDED_OR_TOGGLED}
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
     * Queries the framework about whether any physical keys exist on the
     * any keyboard attached to the device that are capable of producing the given key code.
     *
     * @param keyCode The key code to query.
     * @return True if at least one attached keyboard supports the specified key code.
     */
    public static boolean deviceHasKey(int keyCode) {
        return InputManager.getInstance().deviceHasKeys(new int[] { keyCode })[0];
    }

    /**
     * Queries the framework about whether any physical keys exist on the
     * any keyboard attached to the device that are capable of producing the given
     * array of key codes.
     *
     * @param keyCodes The array of key codes to query.
     * @return A new array of the same size as the key codes array whose elements
     * are set to true if at least one attached keyboard supports the corresponding key code
     * at the same index in the key codes array.
     */
    public static boolean[] deviceHasKeys(int[] keyCodes) {
        return InputManager.getInstance().deviceHasKeys(keyCodes);
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

    private static void addDeadChar(int accent, int c, char combinedResult) {
        DEAD.put((accent << 16) | c, combinedResult);
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
        public int keyCode;
        public int metaState;
    }
}
