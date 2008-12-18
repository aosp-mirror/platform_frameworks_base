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

package android.text.method;

import android.view.KeyEvent;
import android.view.View;
import android.text.*;

/**
 * This base class encapsulates the behavior for handling the meta keys
 * (caps, fn, sym).  Key listener that care about meta state should
 * inherit from it; you should not instantiate this class directly in a client.
 */

public abstract class MetaKeyKeyListener {
    public static final int META_SHIFT_ON = KeyEvent.META_SHIFT_ON;
    public static final int META_ALT_ON = KeyEvent.META_ALT_ON;
    public static final int META_SYM_ON = KeyEvent.META_SYM_ON;

    public static final int META_CAP_LOCKED = KeyEvent.META_SHIFT_ON << 8;
    public static final int META_ALT_LOCKED = KeyEvent.META_ALT_ON << 8;
    public static final int META_SYM_LOCKED = KeyEvent.META_SYM_ON << 8;

    private static final Object CAP = new Object();
    private static final Object ALT = new Object();
    private static final Object SYM = new Object();

    /**
     * Resets all meta state to inactive.
     */
    public static void resetMetaState(Spannable text) {
        text.removeSpan(CAP);
        text.removeSpan(ALT);
        text.removeSpan(SYM);
    }

    /**
     * Gets the state of the meta keys.
     * 
     * @param text the buffer in which the meta key would have been pressed.
     *
     * @return an integer in which each bit set to one represents a pressed
     *         or locked meta key.
     */
    public static final int getMetaState(CharSequence text) {
        return getActive(text, CAP, META_SHIFT_ON, META_CAP_LOCKED) |
               getActive(text, ALT, META_ALT_ON, META_ALT_LOCKED) |
               getActive(text, SYM, META_SYM_ON, META_SYM_LOCKED);
    }

    /**
     * Gets the state of a particular meta key.
     *
     * @param meta META_SHIFT_ON, META_ALT_ON, or META_SYM_ON
     * @param text the buffer in which the meta key would have been pressed.
     *
     * @return 0 if inactive, 1 if active, 2 if locked.
     */
    public static final int getMetaState(CharSequence text, int meta) {
        switch (meta) {
            case META_SHIFT_ON:
                return getActive(text, CAP, 1, 2);

            case META_ALT_ON:
                return getActive(text, ALT, 1, 2);

            case META_SYM_ON:
                return getActive(text, SYM, 1, 2);

            default:
                return 0;
        }
    }

    private static int getActive(CharSequence text, Object meta,
                                 int on, int lock) {
        if (!(text instanceof Spanned)) {
            return 0;
        }

        Spanned sp = (Spanned) text;
        int flag = sp.getSpanFlags(meta);

        if (flag == LOCKED) {
            return lock;
        } else if (flag != 0) {
            return on;
        } else {
            return 0;
        }
    }

    /**
     * Call this method after you handle a keypress so that the meta
     * state will be reset to unshifted (if it is not still down)
     * or primed to be reset to unshifted (once it is released).
     */
    public static void adjustMetaAfterKeypress(Spannable content) {
        adjust(content, CAP);
        adjust(content, ALT);
        adjust(content, SYM);
    }

    /**
     * Returns true if this object is one that this class would use to
     * keep track of meta state in the specified text.
     */
    public static boolean isMetaTracker(CharSequence text, Object what) {
        return what == CAP || what == ALT || what == SYM;
    }

    private static void adjust(Spannable content, Object what) {
        int current = content.getSpanFlags(what);

        if (current == PRESSED)
            content.setSpan(what, 0, 0, USED);
        else if (current == RELEASED)
            content.removeSpan(what);
    }

    /**
     * Call this if you are a method that ignores the locked meta state
     * (arrow keys, for example) and you handle a key.
     */
    protected static void resetLockedMeta(Spannable content) {
        resetLock(content, CAP);
        resetLock(content, ALT);
        resetLock(content, SYM);
    }

    private static void resetLock(Spannable content, Object what) {
        int current = content.getSpanFlags(what);

        if (current == LOCKED)
            content.removeSpan(what);
    }

    /**
     * Handles presses of the meta keys.
     */
    public boolean onKeyDown(View view, Editable content,
                             int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            press(content, CAP);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
                || keyCode == KeyEvent.KEYCODE_NUM) {
            press(content, ALT);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_SYM) {
            press(content, SYM);
            return true;
        }

        return false; // no super to call through to
    }

    private void press(Editable content, Object what) {
        int state = content.getSpanFlags(what);

        if (state == PRESSED)
            ; // repeat before use
        else if (state == RELEASED)
            content.setSpan(what, 0, 0, LOCKED);
        else if (state == USED)
            ; // repeat after use
        else if (state == LOCKED)
            content.removeSpan(what);
        else
            content.setSpan(what, 0, 0, PRESSED);
    }

    /**
     * Handles release of the meta keys.
     */
    public boolean onKeyUp(View view, Editable content, int keyCode,
                                    KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            release(content, CAP);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
                || keyCode == KeyEvent.KEYCODE_NUM) {
            release(content, ALT);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_SYM) {
            release(content, SYM);
            return true;
        }

        return false; // no super to call through to
    }

    private void release(Editable content, Object what) {
        int current = content.getSpanFlags(what);

        if (current == USED)
            content.removeSpan(what);
        else if (current == PRESSED)
            content.setSpan(what, 0, 0, RELEASED);
    }

    public void clearMetaKeyState(View view, Editable content, int states) {
        if ((states&META_SHIFT_ON) != 0) resetLock(content, CAP);
        if ((states&META_ALT_ON) != 0) resetLock(content, ALT);
        if ((states&META_SYM_ON) != 0) resetLock(content, SYM);
    }
    
    /**
     * The meta key has been pressed but has not yet been used.
     */
    private static final int PRESSED = 
        Spannable.SPAN_MARK_MARK | (1 << Spannable.SPAN_USER_SHIFT);

    /**
     * The meta key has been pressed and released but has still
     * not yet been used.
     */
    private static final int RELEASED = 
        Spannable.SPAN_MARK_MARK | (2 << Spannable.SPAN_USER_SHIFT);

    /**
     * The meta key has been pressed and used but has not yet been released.
     */
    private static final int USED = 
        Spannable.SPAN_MARK_MARK | (3 << Spannable.SPAN_USER_SHIFT);

    /**
     * The meta key has been pressed and released without use, and then
     * pressed again; it may also have been released again.
     */
    private static final int LOCKED = 
        Spannable.SPAN_MARK_MARK | (4 << Spannable.SPAN_USER_SHIFT);
}

