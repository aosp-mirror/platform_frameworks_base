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

import android.text.Editable;
import android.text.NoCopySpan;
import android.text.Spannable;
import android.text.Spanned;
import android.view.KeyEvent;
import android.view.View;

/**
 * This base class encapsulates the behavior for handling the meta keys
 * (shift and alt) and the pseudo-meta state of selecting text.
 * Key listeners that care about meta state should
 * inherit from it; you should not instantiate this class directly in a client.
 */

public abstract class MetaKeyKeyListener {
    /**
     * Flag that indicates that the SHIFT key is on.
     * Value equals {@link KeyEvent#META_SHIFT_ON}.
     */
    public static final int META_SHIFT_ON = KeyEvent.META_SHIFT_ON;
    /**
     * Flag that indicates that the ALT key is on.
     * Value equals {@link KeyEvent#META_ALT_ON}.
     */
    public static final int META_ALT_ON = KeyEvent.META_ALT_ON;
    /**
     * Flag that indicates that the SYM key is on.
     * Value equals {@link KeyEvent#META_SYM_ON}.
     */
    public static final int META_SYM_ON = KeyEvent.META_SYM_ON;
    
    /**
     * Flag that indicates that the SHIFT key is locked in CAPS mode.
     */
    public static final int META_CAP_LOCKED = KeyEvent.META_CAP_LOCKED;
    /**
     * Flag that indicates that the ALT key is locked.
     */
    public static final int META_ALT_LOCKED = KeyEvent.META_ALT_LOCKED;
    /**
     * Flag that indicates that the SYM key is locked.
     */
    public static final int META_SYM_LOCKED = KeyEvent.META_SYM_LOCKED;

    /**
     * @hide pending API review
     */
    public static final int META_SELECTING = KeyEvent.META_SELECTING;

    private static final int META_SHIFT_ON_AND_LOCKED = META_SHIFT_ON | META_CAP_LOCKED;
    private static final int META_ALT_ON_AND_LOCKED = META_ALT_ON | META_ALT_LOCKED;
    private static final int META_SYM_ON_AND_LOCKED = META_SYM_ON | META_SYM_LOCKED;

    // These bits are privately used by the meta key key listener.
    // They are deliberately assigned values outside of the representable range of an 'int'
    // so as not to conflict with any meta key states publicly defined by KeyEvent.
    private static final long META_CAP_USED = 1L << 32;
    private static final long META_ALT_USED = 1L << 33;
    private static final long META_SYM_USED = 1L << 34;
    
    private static final long META_CAP_PRESSED = 1L << 40;
    private static final long META_ALT_PRESSED = 1L << 41;
    private static final long META_SYM_PRESSED = 1L << 42;
    
    private static final long META_CAP_RELEASED = 1L << 48;
    private static final long META_ALT_RELEASED = 1L << 49;
    private static final long META_SYM_RELEASED = 1L << 50;

    private static final long META_SHIFT_MASK = META_SHIFT_ON
            | META_CAP_LOCKED | META_CAP_USED
            | META_CAP_PRESSED | META_CAP_RELEASED;
    private static final long META_ALT_MASK = META_ALT_ON
            | META_ALT_LOCKED | META_ALT_USED
            | META_ALT_PRESSED | META_ALT_RELEASED;
    private static final long META_SYM_MASK = META_SYM_ON
            | META_SYM_LOCKED | META_SYM_USED
            | META_SYM_PRESSED | META_SYM_RELEASED;
    
    private static final Object CAP = new NoCopySpan.Concrete();
    private static final Object ALT = new NoCopySpan.Concrete();
    private static final Object SYM = new NoCopySpan.Concrete();
    private static final Object SELECTING = new NoCopySpan.Concrete();

    /**
     * Resets all meta state to inactive.
     */
    public static void resetMetaState(Spannable text) {
        text.removeSpan(CAP);
        text.removeSpan(ALT);
        text.removeSpan(SYM);
        text.removeSpan(SELECTING);
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
               getActive(text, SYM, META_SYM_ON, META_SYM_LOCKED) |
               getActive(text, SELECTING, META_SELECTING, META_SELECTING);
    }

    /**
     * Gets the state of a particular meta key.
     *
     * @param meta META_SHIFT_ON, META_ALT_ON, META_SYM_ON, or META_SELECTING
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

            case META_SELECTING:
                return getActive(text, SELECTING, 1, 2);

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
     * keep track of any meta state in the specified text.
     */
    public static boolean isMetaTracker(CharSequence text, Object what) {
        return what == CAP || what == ALT || what == SYM ||
               what == SELECTING;
    }

    /**
     * Returns true if this object is one that this class would use to
     * keep track of the selecting meta state in the specified text.
     */
    public static boolean isSelectingMetaTracker(CharSequence text, Object what) {
        return what == SELECTING;
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
        resetLock(content, SELECTING);
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
     * Start selecting text.
     * @hide pending API review
     */
    public static void startSelecting(View view, Spannable content) {
        content.setSpan(SELECTING, 0, 0, PRESSED);
    }

    /**
     * Stop selecting text.  This does not actually collapse the selection;
     * call {@link android.text.Selection#setSelection} too.
     * @hide pending API review
     */
    public static void stopSelecting(View view, Spannable content) {
        content.removeSpan(SELECTING);
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
        clearMetaKeyState(content, states);
    }

    public static void clearMetaKeyState(Editable content, int states) {
        if ((states&META_SHIFT_ON) != 0) content.removeSpan(CAP);
        if ((states&META_ALT_ON) != 0) content.removeSpan(ALT);
        if ((states&META_SYM_ON) != 0) content.removeSpan(SYM);
        if ((states&META_SELECTING) != 0) content.removeSpan(SELECTING);
    }

    /**
     * Call this if you are a method that ignores the locked meta state
     * (arrow keys, for example) and you handle a key.
     */
    public static long resetLockedMeta(long state) {
        if ((state & META_CAP_LOCKED) != 0) {
            state &= ~META_SHIFT_MASK;
        }
        if ((state & META_ALT_LOCKED) != 0) {
            state &= ~META_ALT_MASK;
        }
        if ((state & META_SYM_LOCKED) != 0) {
            state &= ~META_SYM_MASK;
        }
        return state;
    }

    // ---------------------------------------------------------------------
    // Version of API that operates on a state bit mask
    // ---------------------------------------------------------------------

    /**
     * Gets the state of the meta keys.
     * 
     * @param state the current meta state bits.
     *
     * @return an integer in which each bit set to one represents a pressed
     *         or locked meta key.
     */
    public static final int getMetaState(long state) {
        int result = 0;

        if ((state & META_CAP_LOCKED) != 0) {
            result |= META_CAP_LOCKED;
        } else if ((state & META_SHIFT_ON) != 0) {
            result |= META_SHIFT_ON;
        }

        if ((state & META_ALT_LOCKED) != 0) {
            result |= META_ALT_LOCKED;
        } else if ((state & META_ALT_ON) != 0) {
            result |= META_ALT_ON;
        }

        if ((state & META_SYM_LOCKED) != 0) {
            result |= META_SYM_LOCKED;
        } else if ((state & META_SYM_ON) != 0) {
            result |= META_SYM_ON;
        }

        return result;
    }

    /**
     * Gets the state of a particular meta key.
     *
     * @param state the current state bits.
     * @param meta META_SHIFT_ON, META_ALT_ON, or META_SYM_ON
     *
     * @return 0 if inactive, 1 if active, 2 if locked.
     */
    public static final int getMetaState(long state, int meta) {
        switch (meta) {
            case META_SHIFT_ON:
                if ((state & META_CAP_LOCKED) != 0) return 2;
                if ((state & META_SHIFT_ON) != 0) return 1;
                return 0;

            case META_ALT_ON:
                if ((state & META_ALT_LOCKED) != 0) return 2;
                if ((state & META_ALT_ON) != 0) return 1;
                return 0;

            case META_SYM_ON:
                if ((state & META_SYM_LOCKED) != 0) return 2;
                if ((state & META_SYM_ON) != 0) return 1;
                return 0;

            default:
                return 0;
        }
    }

    /**
     * Call this method after you handle a keypress so that the meta
     * state will be reset to unshifted (if it is not still down)
     * or primed to be reset to unshifted (once it is released).  Takes
     * the current state, returns the new state.
     */
    public static long adjustMetaAfterKeypress(long state) {
        if ((state & META_CAP_PRESSED) != 0) {
            state = (state & ~META_SHIFT_MASK) | META_SHIFT_ON | META_CAP_USED;
        } else if ((state & META_CAP_RELEASED) != 0) {
            state &= ~META_SHIFT_MASK;
        }

        if ((state & META_ALT_PRESSED) != 0) {
            state = (state & ~META_ALT_MASK) | META_ALT_ON | META_ALT_USED;
        } else if ((state & META_ALT_RELEASED) != 0) {
            state &= ~META_ALT_MASK;
        }

        if ((state & META_SYM_PRESSED) != 0) {
            state = (state & ~META_SYM_MASK) | META_SYM_ON | META_SYM_USED;
        } else if ((state & META_SYM_RELEASED) != 0) {
            state &= ~META_SYM_MASK;
        }
        return state;
    }

    /**
     * Handles presses of the meta keys.
     */
    public static long handleKeyDown(long state, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            return press(state, META_SHIFT_ON, META_SHIFT_MASK,
                    META_CAP_LOCKED, META_CAP_PRESSED, META_CAP_RELEASED, META_CAP_USED);
        }

        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
                || keyCode == KeyEvent.KEYCODE_NUM) {
            return press(state, META_ALT_ON, META_ALT_MASK,
                    META_ALT_LOCKED, META_ALT_PRESSED, META_ALT_RELEASED, META_ALT_USED);
        }

        if (keyCode == KeyEvent.KEYCODE_SYM) {
            return press(state, META_SYM_ON, META_SYM_MASK,
                    META_SYM_LOCKED, META_SYM_PRESSED, META_SYM_RELEASED, META_SYM_USED);
        }
        return state;
    }

    private static long press(long state, int what, long mask,
            long locked, long pressed, long released, long used) {
        if ((state & pressed) != 0) {
            // repeat before use
        } else if ((state & released) != 0) {
            state = (state &~ mask) | what | locked;
        } else if ((state & used) != 0) {
            // repeat after use
        } else if ((state & locked) != 0) {
            state &= ~mask;
        } else {
            state |= what | pressed;
        }
        return state;
    }

    /**
     * Handles release of the meta keys.
     */
    public static long handleKeyUp(long state, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            return release(state, META_SHIFT_ON, META_SHIFT_MASK,
                    META_CAP_PRESSED, META_CAP_RELEASED, META_CAP_USED);
        }

        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
                || keyCode == KeyEvent.KEYCODE_NUM) {
            return release(state, META_ALT_ON, META_ALT_MASK,
                    META_ALT_PRESSED, META_ALT_RELEASED, META_ALT_USED);
        }

        if (keyCode == KeyEvent.KEYCODE_SYM) {
            return release(state, META_SYM_ON, META_SYM_MASK,
                    META_SYM_PRESSED, META_SYM_RELEASED, META_SYM_USED);
        }
        return state;
    }

    private static long release(long state, int what, long mask,
            long pressed, long released, long used) {
        if ((state & used) != 0) {
            state &= ~mask;
        } else if ((state & pressed) != 0) {
            state |= what | released;
        }
        return state;
    }

    /**
     * Clears the state of the specified meta key if it is locked.
     * @param state the meta key state
     * @param which meta keys to clear, may be a combination of {@link #META_SHIFT_ON},
     * {@link #META_ALT_ON} or {@link #META_SYM_ON}.
     */
    public long clearMetaKeyState(long state, int which) {
        if ((which & META_SHIFT_ON_AND_LOCKED) == META_SHIFT_ON_AND_LOCKED) {
            state &= ~META_SHIFT_MASK;
        }
        if ((which & META_ALT_ON_AND_LOCKED) == META_ALT_ON_AND_LOCKED) {
            state &= ~META_ALT_MASK;
        }
        if ((which & META_SYM_ON_AND_LOCKED) == META_SYM_ON_AND_LOCKED) {
            state &= ~META_SYM_MASK;
        }
        return state;
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

