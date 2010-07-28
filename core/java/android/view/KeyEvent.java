/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.util.Log;
import android.util.SparseIntArray;
import android.view.KeyCharacterMap;
import android.view.KeyCharacterMap.KeyData;

/**
 * Contains constants for key events.
 */
public class KeyEvent extends InputEvent implements Parcelable {
    // key codes
    public static final int KEYCODE_UNKNOWN         = 0;
    public static final int KEYCODE_SOFT_LEFT       = 1;
    public static final int KEYCODE_SOFT_RIGHT      = 2;
    public static final int KEYCODE_HOME            = 3;
    public static final int KEYCODE_BACK            = 4;
    public static final int KEYCODE_CALL            = 5;
    public static final int KEYCODE_ENDCALL         = 6;
    public static final int KEYCODE_0               = 7;
    public static final int KEYCODE_1               = 8;
    public static final int KEYCODE_2               = 9;
    public static final int KEYCODE_3               = 10;
    public static final int KEYCODE_4               = 11;
    public static final int KEYCODE_5               = 12;
    public static final int KEYCODE_6               = 13;
    public static final int KEYCODE_7               = 14;
    public static final int KEYCODE_8               = 15;
    public static final int KEYCODE_9               = 16;
    public static final int KEYCODE_STAR            = 17;
    public static final int KEYCODE_POUND           = 18;
    public static final int KEYCODE_DPAD_UP         = 19;
    public static final int KEYCODE_DPAD_DOWN       = 20;
    public static final int KEYCODE_DPAD_LEFT       = 21;
    public static final int KEYCODE_DPAD_RIGHT      = 22;
    public static final int KEYCODE_DPAD_CENTER     = 23;
    public static final int KEYCODE_VOLUME_UP       = 24;
    public static final int KEYCODE_VOLUME_DOWN     = 25;
    public static final int KEYCODE_POWER           = 26;
    public static final int KEYCODE_CAMERA          = 27;
    public static final int KEYCODE_CLEAR           = 28;
    public static final int KEYCODE_A               = 29;
    public static final int KEYCODE_B               = 30;
    public static final int KEYCODE_C               = 31;
    public static final int KEYCODE_D               = 32;
    public static final int KEYCODE_E               = 33;
    public static final int KEYCODE_F               = 34;
    public static final int KEYCODE_G               = 35;
    public static final int KEYCODE_H               = 36;
    public static final int KEYCODE_I               = 37;
    public static final int KEYCODE_J               = 38;
    public static final int KEYCODE_K               = 39;
    public static final int KEYCODE_L               = 40;
    public static final int KEYCODE_M               = 41;
    public static final int KEYCODE_N               = 42;
    public static final int KEYCODE_O               = 43;
    public static final int KEYCODE_P               = 44;
    public static final int KEYCODE_Q               = 45;
    public static final int KEYCODE_R               = 46;
    public static final int KEYCODE_S               = 47;
    public static final int KEYCODE_T               = 48;
    public static final int KEYCODE_U               = 49;
    public static final int KEYCODE_V               = 50;
    public static final int KEYCODE_W               = 51;
    public static final int KEYCODE_X               = 52;
    public static final int KEYCODE_Y               = 53;
    public static final int KEYCODE_Z               = 54;
    public static final int KEYCODE_COMMA           = 55;
    public static final int KEYCODE_PERIOD          = 56;
    public static final int KEYCODE_ALT_LEFT        = 57;
    public static final int KEYCODE_ALT_RIGHT       = 58;
    public static final int KEYCODE_SHIFT_LEFT      = 59;
    public static final int KEYCODE_SHIFT_RIGHT     = 60;
    public static final int KEYCODE_TAB             = 61;
    public static final int KEYCODE_SPACE           = 62;
    public static final int KEYCODE_SYM             = 63;
    public static final int KEYCODE_EXPLORER        = 64;
    public static final int KEYCODE_ENVELOPE        = 65;
    public static final int KEYCODE_ENTER           = 66;
    public static final int KEYCODE_DEL             = 67;
    public static final int KEYCODE_GRAVE           = 68;
    public static final int KEYCODE_MINUS           = 69;
    public static final int KEYCODE_EQUALS          = 70;
    public static final int KEYCODE_LEFT_BRACKET    = 71;
    public static final int KEYCODE_RIGHT_BRACKET   = 72;
    public static final int KEYCODE_BACKSLASH       = 73;
    public static final int KEYCODE_SEMICOLON       = 74;
    public static final int KEYCODE_APOSTROPHE      = 75;
    public static final int KEYCODE_SLASH           = 76;
    public static final int KEYCODE_AT              = 77;
    public static final int KEYCODE_NUM             = 78;
    public static final int KEYCODE_HEADSETHOOK     = 79;
    public static final int KEYCODE_FOCUS           = 80;   // *Camera* focus
    public static final int KEYCODE_PLUS            = 81;
    public static final int KEYCODE_MENU            = 82;
    public static final int KEYCODE_NOTIFICATION    = 83;
    public static final int KEYCODE_SEARCH          = 84;
    public static final int KEYCODE_MEDIA_PLAY_PAUSE= 85;
    public static final int KEYCODE_MEDIA_STOP      = 86;
    public static final int KEYCODE_MEDIA_NEXT      = 87;
    public static final int KEYCODE_MEDIA_PREVIOUS  = 88;
    public static final int KEYCODE_MEDIA_REWIND    = 89;
    public static final int KEYCODE_MEDIA_FAST_FORWARD = 90;
    public static final int KEYCODE_MUTE            = 91;
    public static final int KEYCODE_PAGE_UP         = 92;
    public static final int KEYCODE_PAGE_DOWN       = 93;
    public static final int KEYCODE_PICTSYMBOLS     = 94;   // switch symbol-sets (Emoji,Kao-moji)
    public static final int KEYCODE_SWITCH_CHARSET  = 95;   // switch char-sets (Kanji,Katakana)
    public static final int KEYCODE_BUTTON_A        = 96;
    public static final int KEYCODE_BUTTON_B        = 97;
    public static final int KEYCODE_BUTTON_C        = 98;
    public static final int KEYCODE_BUTTON_X        = 99;
    public static final int KEYCODE_BUTTON_Y        = 100;
    public static final int KEYCODE_BUTTON_Z        = 101;
    public static final int KEYCODE_BUTTON_L1       = 102;
    public static final int KEYCODE_BUTTON_R1       = 103;
    public static final int KEYCODE_BUTTON_L2       = 104;
    public static final int KEYCODE_BUTTON_R2       = 105;
    public static final int KEYCODE_BUTTON_THUMBL   = 106;
    public static final int KEYCODE_BUTTON_THUMBR   = 107;
    public static final int KEYCODE_BUTTON_START    = 108;
    public static final int KEYCODE_BUTTON_SELECT   = 109;
    public static final int KEYCODE_BUTTON_MODE     = 110;

    // NOTE: If you add a new keycode here you must also add it to:
    //  isSystem()
    //  native/include/android/keycodes.h
    //  frameworks/base/include/ui/KeycodeLabels.h
    //  external/webkit/WebKit/android/plugins/ANPKeyCodes.h
    //  tools/puppet_master/PuppetMaster/nav_keys.py
    //  frameworks/base/core/res/res/values/attrs.xml
    //  commands/monkey/Monkey.java
    //  emulator?
    //
    //  Also Android currently does not reserve code ranges for vendor-
    //  specific key codes.  If you have new key codes to have, you
    //  MUST contribute a patch to the open source project to define
    //  those new codes.  This is intended to maintain a consistent
    //  set of key code definitions across all Android devices.
   
    private static final int LAST_KEYCODE           = KEYCODE_SWITCH_CHARSET;
    
    /**
     * @deprecated There are now more than MAX_KEYCODE keycodes.
     * Use {@link #getMaxKeyCode()} instead.
     */
    @Deprecated
    public static final int MAX_KEYCODE             = 84;

    /**
     * {@link #getAction} value: the key has been pressed down.
     */
    public static final int ACTION_DOWN             = 0;
    /**
     * {@link #getAction} value: the key has been released.
     */
    public static final int ACTION_UP               = 1;
    /**
     * {@link #getAction} value: multiple duplicate key events have
     * occurred in a row, or a complex string is being delivered.  If the
     * key code is not {#link {@link #KEYCODE_UNKNOWN} then the
     * {#link {@link #getRepeatCount()} method returns the number of times
     * the given key code should be executed.
     * Otherwise, if the key code is {@link #KEYCODE_UNKNOWN}, then
     * this is a sequence of characters as returned by {@link #getCharacters}.
     */
    public static final int ACTION_MULTIPLE         = 2;

    /**
     * <p>This mask is used to check whether one of the ALT meta keys is pressed.</p>
     *
     * @see #isAltPressed()
     * @see #getMetaState()
     * @see #KEYCODE_ALT_LEFT
     * @see #KEYCODE_ALT_RIGHT
     */
    public static final int META_ALT_ON = 0x02;

    /**
     * <p>This mask is used to check whether the left ALT meta key is pressed.</p>
     *
     * @see #isAltPressed()
     * @see #getMetaState()
     * @see #KEYCODE_ALT_LEFT
     */
    public static final int META_ALT_LEFT_ON = 0x10;

    /**
     * <p>This mask is used to check whether the right the ALT meta key is pressed.</p>
     *
     * @see #isAltPressed()
     * @see #getMetaState()
     * @see #KEYCODE_ALT_RIGHT
     */
    public static final int META_ALT_RIGHT_ON = 0x20;

    /**
     * <p>This mask is used to check whether one of the SHIFT meta keys is pressed.</p>
     *
     * @see #isShiftPressed()
     * @see #getMetaState()
     * @see #KEYCODE_SHIFT_LEFT
     * @see #KEYCODE_SHIFT_RIGHT
     */
    public static final int META_SHIFT_ON = 0x1;

    /**
     * <p>This mask is used to check whether the left SHIFT meta key is pressed.</p>
     *
     * @see #isShiftPressed()
     * @see #getMetaState()
     * @see #KEYCODE_SHIFT_LEFT
     */
    public static final int META_SHIFT_LEFT_ON = 0x40;

    /**
     * <p>This mask is used to check whether the right SHIFT meta key is pressed.</p>
     *
     * @see #isShiftPressed()
     * @see #getMetaState()
     * @see #KEYCODE_SHIFT_RIGHT
     */
    public static final int META_SHIFT_RIGHT_ON = 0x80;

    /**
     * <p>This mask is used to check whether the SYM meta key is pressed.</p>
     *
     * @see #isSymPressed()
     * @see #getMetaState()
     */
    public static final int META_SYM_ON = 0x4;

    /**
     * This mask is set if the device woke because of this key event.
     */
    public static final int FLAG_WOKE_HERE = 0x1;
    
    /**
     * This mask is set if the key event was generated by a software keyboard.
     */
    public static final int FLAG_SOFT_KEYBOARD = 0x2;
    
    /**
     * This mask is set if we don't want the key event to cause us to leave
     * touch mode.
     */
    public static final int FLAG_KEEP_TOUCH_MODE = 0x4;
    
    /**
     * This mask is set if an event was known to come from a trusted part
     * of the system.  That is, the event is known to come from the user,
     * and could not have been spoofed by a third party component.
     */
    public static final int FLAG_FROM_SYSTEM = 0x8;
    
    /**
     * This mask is used for compatibility, to identify enter keys that are
     * coming from an IME whose enter key has been auto-labelled "next" or
     * "done".  This allows TextView to dispatch these as normal enter keys
     * for old applications, but still do the appropriate action when
     * receiving them.
     */
    public static final int FLAG_EDITOR_ACTION = 0x10;
    
    /**
     * When associated with up key events, this indicates that the key press
     * has been canceled.  Typically this is used with virtual touch screen
     * keys, where the user can slide from the virtual key area on to the
     * display: in that case, the application will receive a canceled up
     * event and should not perform the action normally associated with the
     * key.  Note that for this to work, the application can not perform an
     * action for a key until it receives an up or the long press timeout has
     * expired. 
     */
    public static final int FLAG_CANCELED = 0x20;
    
    /**
     * This key event was generated by a virtual (on-screen) hard key area.
     * Typically this is an area of the touchscreen, outside of the regular
     * display, dedicated to "hardware" buttons.
     */
    public static final int FLAG_VIRTUAL_HARD_KEY = 0x40;
    
    /**
     * This flag is set for the first key repeat that occurs after the
     * long press timeout.
     */
    public static final int FLAG_LONG_PRESS = 0x80;
    
    /**
     * Set when a key event has {@link #FLAG_CANCELED} set because a long
     * press action was executed while it was down. 
     */
    public static final int FLAG_CANCELED_LONG_PRESS = 0x100;
    
    /**
     * Set for {@link #ACTION_UP} when this event's key code is still being
     * tracked from its initial down.  That is, somebody requested that tracking
     * started on the key down and a long press has not caused
     * the tracking to be canceled.
     */
    public static final int FLAG_TRACKING = 0x200;
    
    /**
     * Private control to determine when an app is tracking a key sequence.
     * @hide
     */
    public static final int FLAG_START_TRACKING = 0x40000000;
    
    /**
     * Returns the maximum keycode.
     */
    public static int getMaxKeyCode() {
        return LAST_KEYCODE;
    }

    /**
     * Get the character that is produced by putting accent on the character
     * c.
     * For example, getDeadChar('`', 'e') returns &egrave;.
     */
    public static int getDeadChar(int accent, int c) {
        return KeyCharacterMap.getDeadChar(accent, c);
    }
    
    static final boolean DEBUG = false;
    static final String TAG = "KeyEvent";
    
    private int mMetaState;
    private int mAction;
    private int mKeyCode;
    private int mScanCode;
    private int mRepeatCount;
    private int mFlags;
    private long mDownTime;
    private long mEventTime;
    private String mCharacters;

    public interface Callback {
        /**
         * Called when a key down event has occurred.  If you return true,
         * you can first call {@link KeyEvent#startTracking()
         * KeyEvent.startTracking()} to have the framework track the event
         * through its {@link #onKeyUp(int, KeyEvent)} and also call your
         * {@link #onKeyLongPress(int, KeyEvent)} if it occurs.
         * 
         * @param keyCode The value in event.getKeyCode().
         * @param event Description of the key event.
         * 
         * @return If you handled the event, return true.  If you want to allow
         *         the event to be handled by the next receiver, return false.
         */
        boolean onKeyDown(int keyCode, KeyEvent event);

        /**
         * Called when a long press has occurred.  If you return true,
         * the final key up will have {@link KeyEvent#FLAG_CANCELED} and
         * {@link KeyEvent#FLAG_CANCELED_LONG_PRESS} set.  Note that in
         * order to receive this callback, someone in the event change
         * <em>must</em> return true from {@link #onKeyDown} <em>and</em>
         * call {@link KeyEvent#startTracking()} on the event.
         * 
         * @param keyCode The value in event.getKeyCode().
         * @param event Description of the key event.
         * 
         * @return If you handled the event, return true.  If you want to allow
         *         the event to be handled by the next receiver, return false.
         */
        boolean onKeyLongPress(int keyCode, KeyEvent event);

        /**
         * Called when a key up event has occurred.
         * 
         * @param keyCode The value in event.getKeyCode().
         * @param event Description of the key event.
         * 
         * @return If you handled the event, return true.  If you want to allow
         *         the event to be handled by the next receiver, return false.
         */
        boolean onKeyUp(int keyCode, KeyEvent event);

        /**
         * Called when multiple down/up pairs of the same key have occurred
         * in a row.
         * 
         * @param keyCode The value in event.getKeyCode().
         * @param count Number of pairs as returned by event.getRepeatCount().
         * @param event Description of the key event.
         * 
         * @return If you handled the event, return true.  If you want to allow
         *         the event to be handled by the next receiver, return false.
         */
        boolean onKeyMultiple(int keyCode, int count, KeyEvent event);
    }

    /**
     * Create a new key event.
     * 
     * @param action Action code: either {@link #ACTION_DOWN},
     * {@link #ACTION_UP}, or {@link #ACTION_MULTIPLE}.
     * @param code The key code.
     */
    public KeyEvent(int action, int code) {
        mAction = action;
        mKeyCode = code;
        mRepeatCount = 0;
    }

    /**
     * Create a new key event.
     * 
     * @param downTime The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this key code originally went down.
     * @param eventTime The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this event happened.
     * @param action Action code: either {@link #ACTION_DOWN},
     * {@link #ACTION_UP}, or {@link #ACTION_MULTIPLE}.
     * @param code The key code.
     * @param repeat A repeat count for down events (> 0 if this is after the
     * initial down) or event count for multiple events.
     */
    public KeyEvent(long downTime, long eventTime, int action,
                    int code, int repeat) {
        mDownTime = downTime;
        mEventTime = eventTime;
        mAction = action;
        mKeyCode = code;
        mRepeatCount = repeat;
    }

    /**
     * Create a new key event.
     * 
     * @param downTime The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this key code originally went down.
     * @param eventTime The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this event happened.
     * @param action Action code: either {@link #ACTION_DOWN},
     * {@link #ACTION_UP}, or {@link #ACTION_MULTIPLE}.
     * @param code The key code.
     * @param repeat A repeat count for down events (> 0 if this is after the
     * initial down) or event count for multiple events.
     * @param metaState Flags indicating which meta keys are currently pressed.
     */
    public KeyEvent(long downTime, long eventTime, int action,
                    int code, int repeat, int metaState) {
        mDownTime = downTime;
        mEventTime = eventTime;
        mAction = action;
        mKeyCode = code;
        mRepeatCount = repeat;
        mMetaState = metaState;
    }

    /**
     * Create a new key event.
     * 
     * @param downTime The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this key code originally went down.
     * @param eventTime The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this event happened.
     * @param action Action code: either {@link #ACTION_DOWN},
     * {@link #ACTION_UP}, or {@link #ACTION_MULTIPLE}.
     * @param code The key code.
     * @param repeat A repeat count for down events (> 0 if this is after the
     * initial down) or event count for multiple events.
     * @param metaState Flags indicating which meta keys are currently pressed.
     * @param deviceId The device ID that generated the key event.
     * @param scancode Raw device scan code of the event.
     */
    public KeyEvent(long downTime, long eventTime, int action,
                    int code, int repeat, int metaState,
                    int deviceId, int scancode) {
        mDownTime = downTime;
        mEventTime = eventTime;
        mAction = action;
        mKeyCode = code;
        mRepeatCount = repeat;
        mMetaState = metaState;
        mDeviceId = deviceId;
        mScanCode = scancode;
    }

    /**
     * Create a new key event.
     * 
     * @param downTime The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this key code originally went down.
     * @param eventTime The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this event happened.
     * @param action Action code: either {@link #ACTION_DOWN},
     * {@link #ACTION_UP}, or {@link #ACTION_MULTIPLE}.
     * @param code The key code.
     * @param repeat A repeat count for down events (> 0 if this is after the
     * initial down) or event count for multiple events.
     * @param metaState Flags indicating which meta keys are currently pressed.
     * @param deviceId The device ID that generated the key event.
     * @param scancode Raw device scan code of the event.
     * @param flags The flags for this key event
     */
    public KeyEvent(long downTime, long eventTime, int action,
                    int code, int repeat, int metaState,
                    int deviceId, int scancode, int flags) {
        mDownTime = downTime;
        mEventTime = eventTime;
        mAction = action;
        mKeyCode = code;
        mRepeatCount = repeat;
        mMetaState = metaState;
        mDeviceId = deviceId;
        mScanCode = scancode;
        mFlags = flags;
    }

    /**
     * Create a new key event.
     * 
     * @param downTime The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this key code originally went down.
     * @param eventTime The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this event happened.
     * @param action Action code: either {@link #ACTION_DOWN},
     * {@link #ACTION_UP}, or {@link #ACTION_MULTIPLE}.
     * @param code The key code.
     * @param repeat A repeat count for down events (> 0 if this is after the
     * initial down) or event count for multiple events.
     * @param metaState Flags indicating which meta keys are currently pressed.
     * @param deviceId The device ID that generated the key event.
     * @param scancode Raw device scan code of the event.
     * @param flags The flags for this key event
     * @param source The input source such as {@link InputDevice#SOURCE_KEYBOARD}.
     */
    public KeyEvent(long downTime, long eventTime, int action,
                    int code, int repeat, int metaState,
                    int deviceId, int scancode, int flags, int source) {
        mDownTime = downTime;
        mEventTime = eventTime;
        mAction = action;
        mKeyCode = code;
        mRepeatCount = repeat;
        mMetaState = metaState;
        mDeviceId = deviceId;
        mScanCode = scancode;
        mFlags = flags;
        mSource = source;
    }

    /**
     * Create a new key event for a string of characters.  The key code,
     * action, repeat count and source will automatically be set to
     * {@link #KEYCODE_UNKNOWN}, {@link #ACTION_MULTIPLE}, 0, and
     * {@link InputDevice#SOURCE_KEYBOARD} for you.
     * 
     * @param time The time (in {@link android.os.SystemClock#uptimeMillis})
     * at which this event occured.
     * @param characters The string of characters.
     * @param deviceId The device ID that generated the key event.
     * @param flags The flags for this key event
     */
    public KeyEvent(long time, String characters, int deviceId, int flags) {
        mDownTime = time;
        mEventTime = time;
        mCharacters = characters;
        mAction = ACTION_MULTIPLE;
        mKeyCode = KEYCODE_UNKNOWN;
        mRepeatCount = 0;
        mDeviceId = deviceId;
        mFlags = flags;
        mSource = InputDevice.SOURCE_KEYBOARD;
    }

    /**
     * Make an exact copy of an existing key event.
     */
    public KeyEvent(KeyEvent origEvent) {
        mDownTime = origEvent.mDownTime;
        mEventTime = origEvent.mEventTime;
        mAction = origEvent.mAction;
        mKeyCode = origEvent.mKeyCode;
        mRepeatCount = origEvent.mRepeatCount;
        mMetaState = origEvent.mMetaState;
        mDeviceId = origEvent.mDeviceId;
        mSource = origEvent.mSource;
        mScanCode = origEvent.mScanCode;
        mFlags = origEvent.mFlags;
        mCharacters = origEvent.mCharacters;
    }

    /**
     * Copy an existing key event, modifying its time and repeat count.
     * 
     * @deprecated Use {@link #changeTimeRepeat(KeyEvent, long, int)}
     * instead.
     * 
     * @param origEvent The existing event to be copied.
     * @param eventTime The new event time
     * (in {@link android.os.SystemClock#uptimeMillis}) of the event.
     * @param newRepeat The new repeat count of the event.
     */
    @Deprecated
    public KeyEvent(KeyEvent origEvent, long eventTime, int newRepeat) {
        mDownTime = origEvent.mDownTime;
        mEventTime = eventTime;
        mAction = origEvent.mAction;
        mKeyCode = origEvent.mKeyCode;
        mRepeatCount = newRepeat;
        mMetaState = origEvent.mMetaState;
        mDeviceId = origEvent.mDeviceId;
        mSource = origEvent.mSource;
        mScanCode = origEvent.mScanCode;
        mFlags = origEvent.mFlags;
        mCharacters = origEvent.mCharacters;
    }

    /**
     * Create a new key event that is the same as the given one, but whose
     * event time and repeat count are replaced with the given value.
     * 
     * @param event The existing event to be copied.  This is not modified.
     * @param eventTime The new event time
     * (in {@link android.os.SystemClock#uptimeMillis}) of the event.
     * @param newRepeat The new repeat count of the event.
     */
    public static KeyEvent changeTimeRepeat(KeyEvent event, long eventTime,
            int newRepeat) {
        return new KeyEvent(event, eventTime, newRepeat);
    }
    
    /**
     * Create a new key event that is the same as the given one, but whose
     * event time and repeat count are replaced with the given value.
     * 
     * @param event The existing event to be copied.  This is not modified.
     * @param eventTime The new event time
     * (in {@link android.os.SystemClock#uptimeMillis}) of the event.
     * @param newRepeat The new repeat count of the event.
     * @param newFlags New flags for the event, replacing the entire value
     * in the original event.
     */
    public static KeyEvent changeTimeRepeat(KeyEvent event, long eventTime,
            int newRepeat, int newFlags) {
        KeyEvent ret = new KeyEvent(event);
        ret.mEventTime = eventTime;
        ret.mRepeatCount = newRepeat;
        ret.mFlags = newFlags;
        return ret;
    }
    
    /**
     * Copy an existing key event, modifying its action.
     * 
     * @param origEvent The existing event to be copied.
     * @param action The new action code of the event.
     */
    private KeyEvent(KeyEvent origEvent, int action) {
        mDownTime = origEvent.mDownTime;
        mEventTime = origEvent.mEventTime;
        mAction = action;
        mKeyCode = origEvent.mKeyCode;
        mRepeatCount = origEvent.mRepeatCount;
        mMetaState = origEvent.mMetaState;
        mDeviceId = origEvent.mDeviceId;
        mSource = origEvent.mSource;
        mScanCode = origEvent.mScanCode;
        mFlags = origEvent.mFlags;
        // Don't copy mCharacters, since one way or the other we'll lose it
        // when changing the action.
    }

    /**
     * Create a new key event that is the same as the given one, but whose
     * action is replaced with the given value.
     * 
     * @param event The existing event to be copied.  This is not modified.
     * @param action The new action code of the event.
     */
    public static KeyEvent changeAction(KeyEvent event, int action) {
        return new KeyEvent(event, action);
    }
    
    /**
     * Create a new key event that is the same as the given one, but whose
     * flags are replaced with the given value.
     * 
     * @param event The existing event to be copied.  This is not modified.
     * @param flags The new flags constant.
     */
    public static KeyEvent changeFlags(KeyEvent event, int flags) {
        event = new KeyEvent(event);
        event.mFlags = flags;
        return event;
    }
    
    /**
     * Don't use in new code, instead explicitly check
     * {@link #getAction()}.
     * 
     * @return If the action is ACTION_DOWN, returns true; else false.
     *
     * @deprecated
     * @hide
     */
    @Deprecated public final boolean isDown() {
        return mAction == ACTION_DOWN;
    }

    /**
     * Is this a system key?  System keys can not be used for menu shortcuts.
     * 
     * TODO: this information should come from a table somewhere.
     * TODO: should the dpad keys be here?  arguably, because they also shouldn't be menu shortcuts
     */
    public final boolean isSystem() {
        return native_isSystemKey(mKeyCode);
    }

    /** @hide */
    public final boolean hasDefaultAction() {
        return native_hasDefaultAction(mKeyCode);
    }


    /**
     * <p>Returns the state of the meta keys.</p>
     *
     * @return an integer in which each bit set to 1 represents a pressed
     *         meta key
     *
     * @see #isAltPressed()
     * @see #isShiftPressed()
     * @see #isSymPressed()
     * @see #META_ALT_ON
     * @see #META_SHIFT_ON
     * @see #META_SYM_ON
     */
    public final int getMetaState() {
        return mMetaState;
    }

    /**
     * Returns the flags for this key event.
     *
     * @see #FLAG_WOKE_HERE
     */
    public final int getFlags() {
        return mFlags;
    }

    /**
     * Returns true if this key code is a modifier key.
     *
     * @return whether the provided keyCode is one of
     * {@link #KEYCODE_SHIFT_LEFT} {@link #KEYCODE_SHIFT_RIGHT},
     * {@link #KEYCODE_ALT_LEFT}, {@link #KEYCODE_ALT_RIGHT}
     * or {@link #KEYCODE_SYM}.
     */
    public static boolean isModifierKey(int keyCode) {
        return keyCode == KEYCODE_SHIFT_LEFT || keyCode == KEYCODE_SHIFT_RIGHT
                || keyCode == KEYCODE_ALT_LEFT || keyCode == KEYCODE_ALT_RIGHT
                || keyCode == KEYCODE_SYM;
    }

    /**
     * <p>Returns the pressed state of the ALT meta key.</p>
     *
     * @return true if the ALT key is pressed, false otherwise
     *
     * @see #KEYCODE_ALT_LEFT
     * @see #KEYCODE_ALT_RIGHT
     * @see #META_ALT_ON
     */
    public final boolean isAltPressed() {
        return (mMetaState & META_ALT_ON) != 0;
    }

    /**
     * <p>Returns the pressed state of the SHIFT meta key.</p>
     *
     * @return true if the SHIFT key is pressed, false otherwise
     *
     * @see #KEYCODE_SHIFT_LEFT
     * @see #KEYCODE_SHIFT_RIGHT
     * @see #META_SHIFT_ON
     */
    public final boolean isShiftPressed() {
        return (mMetaState & META_SHIFT_ON) != 0;
    }

    /**
     * <p>Returns the pressed state of the SYM meta key.</p>
     *
     * @return true if the SYM key is pressed, false otherwise
     *
     * @see #KEYCODE_SYM
     * @see #META_SYM_ON
     */
    public final boolean isSymPressed() {
        return (mMetaState & META_SYM_ON) != 0;
    }

    /**
     * Retrieve the action of this key event.  May be either
     * {@link #ACTION_DOWN}, {@link #ACTION_UP}, or {@link #ACTION_MULTIPLE}.
     * 
     * @return The event action: ACTION_DOWN, ACTION_UP, or ACTION_MULTIPLE.
     */
    public final int getAction() {
        return mAction;
    }

    /**
     * For {@link #ACTION_UP} events, indicates that the event has been
     * canceled as per {@link #FLAG_CANCELED}.
     */
    public final boolean isCanceled() {
        return (mFlags&FLAG_CANCELED) != 0;
    }
    
    /**
     * Call this during {@link Callback#onKeyDown} to have the system track
     * the key through its final up (possibly including a long press).  Note
     * that only one key can be tracked at a time -- if another key down
     * event is received while a previous one is being tracked, tracking is
     * stopped on the previous event.
     */
    public final void startTracking() {
        mFlags |= FLAG_START_TRACKING;
    }
    
    /**
     * For {@link #ACTION_UP} events, indicates that the event is still being
     * tracked from its initial down event as per
     * {@link #FLAG_TRACKING}.
     */
    public final boolean isTracking() {
        return (mFlags&FLAG_TRACKING) != 0;
    }
    
    /**
     * For {@link #ACTION_DOWN} events, indicates that the event has been
     * canceled as per {@link #FLAG_LONG_PRESS}.
     */
    public final boolean isLongPress() {
        return (mFlags&FLAG_LONG_PRESS) != 0;
    }
    
    /**
     * Retrieve the key code of the key event.  This is the physical key that
     * was pressed, <em>not</em> the Unicode character.
     * 
     * @return The key code of the event.
     */
    public final int getKeyCode() {
        return mKeyCode;
    }

    /**
     * For the special case of a {@link #ACTION_MULTIPLE} event with key
     * code of {@link #KEYCODE_UNKNOWN}, this is a raw string of characters
     * associated with the event.  In all other cases it is null.
     * 
     * @return Returns a String of 1 or more characters associated with
     * the event.
     */
    public final String getCharacters() {
        return mCharacters;
    }
    
    /**
     * Retrieve the hardware key id of this key event.  These values are not
     * reliable and vary from device to device.
     *
     * {@more}
     * Mostly this is here for debugging purposes.
     */
    public final int getScanCode() {
        return mScanCode;
    }

    /**
     * Retrieve the repeat count of the event.  For both key up and key down
     * events, this is the number of times the key has repeated with the first
     * down starting at 0 and counting up from there.  For multiple key
     * events, this is the number of down/up pairs that have occurred.
     * 
     * @return The number of times the key has repeated.
     */
    public final int getRepeatCount() {
        return mRepeatCount;
    }

    /**
     * Retrieve the time of the most recent key down event,
     * in the {@link android.os.SystemClock#uptimeMillis} time base.  If this
     * is a down event, this will be the same as {@link #getEventTime()}.
     * Note that when chording keys, this value is the down time of the
     * most recently pressed key, which may <em>not</em> be the same physical
     * key of this event.
     * 
     * @return Returns the most recent key down time, in the
     * {@link android.os.SystemClock#uptimeMillis} time base
     */
    public final long getDownTime() {
        return mDownTime;
    }

    /**
     * Retrieve the time this event occurred, 
     * in the {@link android.os.SystemClock#uptimeMillis} time base.
     * 
     * @return Returns the time this event occurred, 
     * in the {@link android.os.SystemClock#uptimeMillis} time base.
     */
    public final long getEventTime() {
        return mEventTime;
    }

    /**
     * Renamed to {@link #getDeviceId}.
     * 
     * @hide
     * @deprecated
     */
    public final int getKeyboardDevice() {
        return mDeviceId;
    }

    /**
     * Get the primary character for this key.  In other words, the label
     * that is physically printed on it.
     */
    public char getDisplayLabel() {
        return KeyCharacterMap.load(mDeviceId).getDisplayLabel(mKeyCode);
    }
    
    /**
     * <p>
     * Returns the Unicode character that the key would produce.
     * </p><p>
     * Returns 0 if the key is not one that is used to type Unicode
     * characters.
     * </p><p>
     * If the return value has bit 
     * {@link KeyCharacterMap#COMBINING_ACCENT} 
     * set, the key is a "dead key" that should be combined with another to
     * actually produce a character -- see {@link #getDeadChar} --
     * after masking with 
     * {@link KeyCharacterMap#COMBINING_ACCENT_MASK}.
     * </p>
     */
    public int getUnicodeChar() {
        return getUnicodeChar(mMetaState);
    }
    
    /**
     * <p>
     * Returns the Unicode character that the key would produce.
     * </p><p>
     * Returns 0 if the key is not one that is used to type Unicode
     * characters.
     * </p><p>
     * If the return value has bit 
     * {@link KeyCharacterMap#COMBINING_ACCENT} 
     * set, the key is a "dead key" that should be combined with another to
     * actually produce a character -- see {@link #getDeadChar} -- after masking
     * with {@link KeyCharacterMap#COMBINING_ACCENT_MASK}.
     * </p>
     */
    public int getUnicodeChar(int meta) {
        return KeyCharacterMap.load(mDeviceId).get(mKeyCode, meta);
    }
    
    /**
     * Get the characters conversion data for the key event..
     *
     * @param results a {@link KeyData} that will be filled with the results.
     *
     * @return whether the key was mapped or not.  If the key was not mapped,
     *         results is not modified.
     */
    public boolean getKeyData(KeyData results) {
        return KeyCharacterMap.load(mDeviceId).getKeyData(mKeyCode, results);
    }
    
    /**
     * The same as {@link #getMatch(char[],int) getMatch(chars, 0)}.
     */
    public char getMatch(char[] chars) {
        return getMatch(chars, 0);
    }
    
    /**
     * If one of the chars in the array can be generated by the keyCode of this
     * key event, return the char; otherwise return '\0'.
     * @param chars the characters to try to find
     * @param modifiers the modifier bits to prefer.  If any of these bits
     *                  are set, if there are multiple choices, that could
     *                  work, the one for this modifier will be set.
     */
    public char getMatch(char[] chars, int modifiers) {
        return KeyCharacterMap.load(mDeviceId).getMatch(mKeyCode, chars, modifiers);
    }
    
    /**
     * Gets the number or symbol associated with the key.  The character value
     * is returned, not the numeric value.  If the key is not a number, but is
     * a symbol, the symbol is retuned.
     */
    public char getNumber() {
        return KeyCharacterMap.load(mDeviceId).getNumber(mKeyCode);
    }
    
    /**
     * Does the key code of this key produce a glyph?
     */
    public boolean isPrintingKey() {
        return KeyCharacterMap.load(mDeviceId).isPrintingKey(mKeyCode);
    }
    
    /**
     * @deprecated Use {@link #dispatch(Callback, DispatcherState, Object)} instead.
     */
    @Deprecated
    public final boolean dispatch(Callback receiver) {
        return dispatch(receiver, null, null);
    }
    
    /**
     * Deliver this key event to a {@link Callback} interface.  If this is
     * an ACTION_MULTIPLE event and it is not handled, then an attempt will
     * be made to deliver a single normal event.
     * 
     * @param receiver The Callback that will be given the event.
     * @param state State information retained across events.
     * @param target The target of the dispatch, for use in tracking.
     * 
     * @return The return value from the Callback method that was called.
     */
    public final boolean dispatch(Callback receiver, DispatcherState state,
            Object target) {
        switch (mAction) {
            case ACTION_DOWN: {
                mFlags &= ~FLAG_START_TRACKING;
                if (DEBUG) Log.v(TAG, "Key down to " + target + " in " + state
                        + ": " + this);
                boolean res = receiver.onKeyDown(mKeyCode, this);
                if (state != null) {
                    if (res && mRepeatCount == 0 && (mFlags&FLAG_START_TRACKING) != 0) {
                        if (DEBUG) Log.v(TAG, "  Start tracking!");
                        state.startTracking(this, target);
                    } else if (isLongPress() && state.isTracking(this)) {
                        try {
                            if (receiver.onKeyLongPress(mKeyCode, this)) {
                                if (DEBUG) Log.v(TAG, "  Clear from long press!");
                                state.performedLongPress(this);
                                res = true;
                            }
                        } catch (AbstractMethodError e) {
                        }
                    }
                }
                return res;
            }
            case ACTION_UP:
                if (DEBUG) Log.v(TAG, "Key up to " + target + " in " + state
                        + ": " + this);
                if (state != null) {
                    state.handleUpEvent(this);
                }
                return receiver.onKeyUp(mKeyCode, this);
            case ACTION_MULTIPLE:
                final int count = mRepeatCount;
                final int code = mKeyCode;
                if (receiver.onKeyMultiple(code, count, this)) {
                    return true;
                }
                if (code != KeyEvent.KEYCODE_UNKNOWN) {
                    mAction = ACTION_DOWN;
                    mRepeatCount = 0;
                    boolean handled = receiver.onKeyDown(code, this);
                    if (handled) {
                        mAction = ACTION_UP;
                        receiver.onKeyUp(code, this);
                    }
                    mAction = ACTION_MULTIPLE;
                    mRepeatCount = count;
                    return handled;
                }
                return false;
        }
        return false;
    }

    /**
     * Use with {@link KeyEvent#dispatch(Callback, DispatcherState, Object)}
     * for more advanced key dispatching, such as long presses.
     */
    public static class DispatcherState {
        int mDownKeyCode;
        Object mDownTarget;
        SparseIntArray mActiveLongPresses = new SparseIntArray();
        
        /**
         * Reset back to initial state.
         */
        public void reset() {
            if (DEBUG) Log.v(TAG, "Reset: " + this);
            mDownKeyCode = 0;
            mDownTarget = null;
            mActiveLongPresses.clear();
        }
        
        /**
         * Stop any tracking associated with this target.
         */
        public void reset(Object target) {
            if (mDownTarget == target) {
                if (DEBUG) Log.v(TAG, "Reset in " + target + ": " + this);
                mDownKeyCode = 0;
                mDownTarget = null;
            }
        }
        
        /**
         * Start tracking the key code associated with the given event.  This
         * can only be called on a key down.  It will allow you to see any
         * long press associated with the key, and will result in
         * {@link KeyEvent#isTracking} return true on the long press and up
         * events.
         * 
         * <p>This is only needed if you are directly dispatching events, rather
         * than handling them in {@link Callback#onKeyDown}.
         */
        public void startTracking(KeyEvent event, Object target) {
            if (event.getAction() != ACTION_DOWN) {
                throw new IllegalArgumentException(
                        "Can only start tracking on a down event");
            }
            if (DEBUG) Log.v(TAG, "Start trackingt in " + target + ": " + this);
            mDownKeyCode = event.getKeyCode();
            mDownTarget = target;
        }
        
        /**
         * Return true if the key event is for a key code that is currently
         * being tracked by the dispatcher.
         */
        public boolean isTracking(KeyEvent event) {
            return mDownKeyCode == event.getKeyCode();
        }
        
        /**
         * Keep track of the given event's key code as having performed an
         * action with a long press, so no action should occur on the up.
         * <p>This is only needed if you are directly dispatching events, rather
         * than handling them in {@link Callback#onKeyLongPress}.
         */
        public void performedLongPress(KeyEvent event) {
            mActiveLongPresses.put(event.getKeyCode(), 1);
        }
        
        /**
         * Handle key up event to stop tracking.  This resets the dispatcher state,
         * and updates the key event state based on it.
         * <p>This is only needed if you are directly dispatching events, rather
         * than handling them in {@link Callback#onKeyUp}.
         */
        public void handleUpEvent(KeyEvent event) {
            final int keyCode = event.getKeyCode();
            if (DEBUG) Log.v(TAG, "Handle key up " + event + ": " + this);
            int index = mActiveLongPresses.indexOfKey(keyCode);
            if (index >= 0) {
                if (DEBUG) Log.v(TAG, "  Index: " + index);
                event.mFlags |= FLAG_CANCELED | FLAG_CANCELED_LONG_PRESS;
                mActiveLongPresses.removeAt(index);
            }
            if (mDownKeyCode == keyCode) {
                if (DEBUG) Log.v(TAG, "  Tracking!");
                event.mFlags |= FLAG_TRACKING;
                mDownKeyCode = 0;
                mDownTarget = null;
            }
        }
    }
    
    public String toString() {
        return "KeyEvent{action=" + mAction + " code=" + mKeyCode
            + " repeat=" + mRepeatCount
            + " meta=" + mMetaState + " scancode=" + mScanCode
            + " mFlags=" + mFlags + "}";
    }

    public static final Parcelable.Creator<KeyEvent> CREATOR
            = new Parcelable.Creator<KeyEvent>() {
        public KeyEvent createFromParcel(Parcel in) {
            in.readInt(); // skip token, we already know this is a KeyEvent
            return KeyEvent.createFromParcelBody(in);
        }

        public KeyEvent[] newArray(int size) {
            return new KeyEvent[size];
        }
    };
    
    /** @hide */
    public static KeyEvent createFromParcelBody(Parcel in) {
        return new KeyEvent(in);
    }
    
    private KeyEvent(Parcel in) {
        readBaseFromParcel(in);
        
        mAction = in.readInt();
        mKeyCode = in.readInt();
        mRepeatCount = in.readInt();
        mMetaState = in.readInt();
        mScanCode = in.readInt();
        mFlags = in.readInt();
        mDownTime = in.readLong();
        mEventTime = in.readLong();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(PARCEL_TOKEN_KEY_EVENT);
        
        writeBaseToParcel(out);
        
        out.writeInt(mAction);
        out.writeInt(mKeyCode);
        out.writeInt(mRepeatCount);
        out.writeInt(mMetaState);
        out.writeInt(mScanCode);
        out.writeInt(mFlags);
        out.writeLong(mDownTime);
        out.writeLong(mEventTime);
    }

    private native boolean native_isSystemKey(int keyCode);
    private native boolean native_hasDefaultAction(int keyCode);
}
