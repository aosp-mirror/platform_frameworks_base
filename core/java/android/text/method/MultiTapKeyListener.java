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
import android.os.Handler;
import android.os.SystemClock;
import android.text.*;
import android.text.method.TextKeyListener.Capitalize;
import android.util.SparseArray;

/**
 * This is the standard key listener for alphabetic input on 12-key
 * keyboards.  You should generally not need to instantiate this yourself;
 * TextKeyListener will do it for you.
 */
public class MultiTapKeyListener extends BaseKeyListener
        implements SpanWatcher {
    private static MultiTapKeyListener[] sInstance =
        new MultiTapKeyListener[Capitalize.values().length * 2];

    private static final SparseArray<String> sRecs = new SparseArray<String>();

    private Capitalize mCapitalize;
    private boolean mAutoText;

    static {
        sRecs.put(KeyEvent.KEYCODE_1,     ".,1!@#$%^&*:/?'=()");
        sRecs.put(KeyEvent.KEYCODE_2,     "abc2ABC");
        sRecs.put(KeyEvent.KEYCODE_3,     "def3DEF");
        sRecs.put(KeyEvent.KEYCODE_4,     "ghi4GHI");
        sRecs.put(KeyEvent.KEYCODE_5,     "jkl5JKL");
        sRecs.put(KeyEvent.KEYCODE_6,     "mno6MNO");
        sRecs.put(KeyEvent.KEYCODE_7,     "pqrs7PQRS");
        sRecs.put(KeyEvent.KEYCODE_8,     "tuv8TUV");
        sRecs.put(KeyEvent.KEYCODE_9,     "wxyz9WXYZ");
        sRecs.put(KeyEvent.KEYCODE_0,     "0+");
        sRecs.put(KeyEvent.KEYCODE_POUND, " ");
    };

    public MultiTapKeyListener(Capitalize cap,
                               boolean autotext) {
        mCapitalize = cap;
        mAutoText = autotext;
    }

    /**
     * Returns a new or existing instance with the specified capitalization
     * and correction properties.
     */
    public static MultiTapKeyListener getInstance(boolean autotext,
                                                  Capitalize cap) {
        int off = cap.ordinal() * 2 + (autotext ? 1 : 0);

        if (sInstance[off] == null) {
            sInstance[off] = new MultiTapKeyListener(cap, autotext);
        }

        return sInstance[off];
    }

    public int getInputType() {
        return makeTextContentType(mCapitalize, mAutoText);
    }
    
    public boolean onKeyDown(View view, Editable content,
                             int keyCode, KeyEvent event) {
        int selStart, selEnd;
        int pref = 0;

        if (view != null) {
            pref = TextKeyListener.getInstance().getPrefs(view.getContext());
        }

        {
            int a = Selection.getSelectionStart(content);
            int b = Selection.getSelectionEnd(content);

            selStart = Math.min(a, b);
            selEnd = Math.max(a, b);
        }

        int activeStart = content.getSpanStart(TextKeyListener.ACTIVE);
        int activeEnd = content.getSpanEnd(TextKeyListener.ACTIVE);

        // now for the multitap cases...

        // Try to increment the character we were working on before
        // if we have one and it's still the same key.

        int rec = (content.getSpanFlags(TextKeyListener.ACTIVE)
                    & Spannable.SPAN_USER) >>> Spannable.SPAN_USER_SHIFT;

        if (activeStart == selStart && activeEnd == selEnd &&
            selEnd - selStart == 1 &&
            rec >= 0 && rec < sRecs.size()) {
            if (keyCode == KeyEvent.KEYCODE_STAR) {
                char current = content.charAt(selStart);

                if (Character.isLowerCase(current)) {
                    content.replace(selStart, selEnd,
                                    String.valueOf(current).toUpperCase());
                    removeTimeouts(content);
                    new Timeout(content); // for its side effects

                    return true;
                }
                if (Character.isUpperCase(current)) {
                    content.replace(selStart, selEnd,
                                    String.valueOf(current).toLowerCase());
                    removeTimeouts(content);
                    new Timeout(content); // for its side effects

                    return true;
                }
            }

            if (sRecs.indexOfKey(keyCode) == rec) {
                String val = sRecs.valueAt(rec);
                char ch = content.charAt(selStart);
                int ix = val.indexOf(ch);

                if (ix >= 0) {
                    ix = (ix + 1) % (val.length());

                    content.replace(selStart, selEnd, val, ix, ix + 1);
                    removeTimeouts(content);
                    new Timeout(content); // for its side effects

                    return true;
                }
            }

            // Is this key one we know about at all?  If so, acknowledge
            // that the selection is our fault but the key has changed
            // or the text no longer matches, so move the selection over
            // so that it inserts instead of replaces.

            rec = sRecs.indexOfKey(keyCode);

            if (rec >= 0) {
                Selection.setSelection(content, selEnd, selEnd);
                selStart = selEnd;
            }
        } else {
            rec = sRecs.indexOfKey(keyCode);
        }

        if (rec >= 0) {
            // We have a valid key.  Replace the selection or insertion point
            // with the first character for that key, and remember what
            // record it came from for next time.

            String val = sRecs.valueAt(rec);

            int off = 0;
            if ((pref & TextKeyListener.AUTO_CAP) != 0 &&
                TextKeyListener.shouldCap(mCapitalize, content, selStart)) {
                for (int i = 0; i < val.length(); i++) {
                    if (Character.isUpperCase(val.charAt(i))) {
                        off = i;
                        break;
                    }
                }
            }

            if (selStart != selEnd) {
                Selection.setSelection(content, selEnd);
            }

            content.setSpan(OLD_SEL_START, selStart, selStart,
                            Spannable.SPAN_MARK_MARK);

            content.replace(selStart, selEnd, val, off, off + 1);

            int oldStart = content.getSpanStart(OLD_SEL_START);
            selEnd = Selection.getSelectionEnd(content);

            if (selEnd != oldStart) {
                Selection.setSelection(content, oldStart, selEnd);

                content.setSpan(TextKeyListener.LAST_TYPED, 
                                oldStart, selEnd,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                content.setSpan(TextKeyListener.ACTIVE,
                            oldStart, selEnd,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE |
                            (rec << Spannable.SPAN_USER_SHIFT));

            }

            removeTimeouts(content);
            new Timeout(content); // for its side effects

            // Set up the callback so we can remove the timeout if the
            // cursor moves.

            if (content.getSpanStart(this) < 0) {
                KeyListener[] methods = content.getSpans(0, content.length(),
                                                    KeyListener.class);
                for (Object method : methods) {
                    content.removeSpan(method);
                }
                content.setSpan(this, 0, content.length(),
                                Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            }

            return true;
        }

        return super.onKeyDown(view, content, keyCode, event);
    }

    public void onSpanChanged(Spannable buf,
                              Object what, int s, int e, int start, int stop) {
        if (what == Selection.SELECTION_END) {
            buf.removeSpan(TextKeyListener.ACTIVE);
            removeTimeouts(buf);
        }
    }

    private static void removeTimeouts(Spannable buf) {
        Timeout[] timeout = buf.getSpans(0, buf.length(), Timeout.class);

        for (int i = 0; i < timeout.length; i++) {
            Timeout t = timeout[i];

            t.removeCallbacks(t);
            t.mBuffer = null;
            buf.removeSpan(t);
        }
    }

    private class Timeout
    extends Handler
    implements Runnable
    {
        public Timeout(Editable buffer) {
            mBuffer = buffer;
            mBuffer.setSpan(Timeout.this, 0, mBuffer.length(),
                            Spannable.SPAN_INCLUSIVE_INCLUSIVE);

            postAtTime(this, SystemClock.uptimeMillis() + 2000);
        }

        public void run() {
            Spannable buf = mBuffer;

            if (buf != null) {
                int st = Selection.getSelectionStart(buf);
                int en = Selection.getSelectionEnd(buf);

                int start = buf.getSpanStart(TextKeyListener.ACTIVE);
                int end = buf.getSpanEnd(TextKeyListener.ACTIVE);

                if (st == start && en == end) {
                    Selection.setSelection(buf, Selection.getSelectionEnd(buf));
                }

                buf.removeSpan(Timeout.this);
            }
        }

        private Editable mBuffer;
    }

    public void onSpanAdded(Spannable s, Object what, int start, int end) { }
    public void onSpanRemoved(Spannable s, Object what, int start, int end) { }
}

