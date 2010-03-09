/*
 * Copyright (C) 2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.Editable;
import android.text.NoCopySpan;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.MetaKeyKeyListener;
import android.util.Log;
import android.util.LogPrinter;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewRoot;

class ComposingText implements NoCopySpan {
}

/**
 * Base class for implementors of the InputConnection interface, taking care
 * of most of the common behavior for providing a connection to an Editable.
 * Implementors of this class will want to be sure to implement
 * {@link #getEditable} to provide access to their own editable object.
 */
public class BaseInputConnection implements InputConnection {
    private static final boolean DEBUG = false;
    private static final String TAG = "BaseInputConnection";
    static final Object COMPOSING = new ComposingText();
    
    final InputMethodManager mIMM;
    final View mTargetView;
    final boolean mDummyMode;
    
    private Object[] mDefaultComposingSpans;
    
    Editable mEditable;
    KeyCharacterMap mKeyCharacterMap;
    
    BaseInputConnection(InputMethodManager mgr, boolean fullEditor) {
        mIMM = mgr;
        mTargetView = null;
        mDummyMode = !fullEditor;
    }
    
    public BaseInputConnection(View targetView, boolean fullEditor) {
        mIMM = (InputMethodManager)targetView.getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        mTargetView = targetView;
        mDummyMode = !fullEditor;
    }
    
    public static final void removeComposingSpans(Spannable text) {
        text.removeSpan(COMPOSING);
        Object[] sps = text.getSpans(0, text.length(), Object.class);
        if (sps != null) {
            for (int i=sps.length-1; i>=0; i--) {
                Object o = sps[i];
                if ((text.getSpanFlags(o)&Spanned.SPAN_COMPOSING) != 0) {
                    text.removeSpan(o);
                }
            }
        }
    }
    
    public static void setComposingSpans(Spannable text) {
        final Object[] sps = text.getSpans(0, text.length(), Object.class);
        if (sps != null) {
            for (int i=sps.length-1; i>=0; i--) {
                final Object o = sps[i];
                if (o == COMPOSING) {
                    text.removeSpan(o);
                    continue;
                }
                final int fl = text.getSpanFlags(o);
                if ((fl&(Spanned.SPAN_COMPOSING|Spanned.SPAN_POINT_MARK_MASK)) 
                        != (Spanned.SPAN_COMPOSING|Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)) {
                    text.setSpan(o, text.getSpanStart(o), text.getSpanEnd(o),
                            (fl&Spanned.SPAN_POINT_MARK_MASK)
                                    | Spanned.SPAN_COMPOSING
                                    | Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        
        text.setSpan(COMPOSING, 0, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
    }
    
    public static int getComposingSpanStart(Spannable text) {
        return text.getSpanStart(COMPOSING);
    }
    
    public static int getComposingSpanEnd(Spannable text) {
        return text.getSpanEnd(COMPOSING);
    }
    
    /**
     * Return the target of edit operations.  The default implementation
     * returns its own fake editable that is just used for composing text;
     * subclasses that are real text editors should override this and
     * supply their own.
     */
    public Editable getEditable() {
        if (mEditable == null) {
            mEditable = Editable.Factory.getInstance().newEditable("");
            Selection.setSelection(mEditable, 0);
        }
        return mEditable;
    }
    
    /**
     * Default implementation does nothing.
     */
    public boolean beginBatchEdit() {
        return false;
    }

    /**
     * Default implementation does nothing.
     */
    public boolean endBatchEdit() {
        return false;
    }

    /**
     * Default implementation uses
     * {@link MetaKeyKeyListener#clearMetaKeyState(long, int)
     * MetaKeyKeyListener.clearMetaKeyState(long, int)} to clear the state.
     */
    public boolean clearMetaKeyStates(int states) {
        final Editable content = getEditable();
        if (content == null) return false;
        MetaKeyKeyListener.clearMetaKeyState(content, states);
        return true;
    }

    /**
     * Default implementation does nothing.
     */
    public boolean commitCompletion(CompletionInfo text) {
        return false;
    }

    /**
     * Default implementation replaces any existing composing text with
     * the given text.  In addition, only if dummy mode, a key event is
     * sent for the new text and the current editable buffer cleared.
     */
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (DEBUG) Log.v(TAG, "commitText " + text);
        replaceText(text, newCursorPosition, false);
        sendCurrentText();
        return true;
    }

    /**
     * The default implementation performs the deletion around the current
     * selection position of the editable text.
     */
    public boolean deleteSurroundingText(int leftLength, int rightLength) {
        if (DEBUG) Log.v(TAG, "deleteSurroundingText " + leftLength
                + " / " + rightLength);
        final Editable content = getEditable();
        if (content == null) return false;

        beginBatchEdit();
        
        int a = Selection.getSelectionStart(content);
        int b = Selection.getSelectionEnd(content);

        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        // ignore the composing text.
        int ca = getComposingSpanStart(content);
        int cb = getComposingSpanEnd(content);
        if (cb < ca) {
            int tmp = ca;
            ca = cb;
            cb = tmp;
        }
        if (ca != -1 && cb != -1) {
            if (ca < a) a = ca;
            if (cb > b) b = cb;
        }

        int deleted = 0;

        if (leftLength > 0) {
            int start = a - leftLength;
            if (start < 0) start = 0;
            content.delete(start, a);
            deleted = a - start;
        }

        if (rightLength > 0) {
            b = b - deleted;

            int end = b + rightLength;
            if (end > content.length()) end = content.length();

            content.delete(b, end);
        }
        
        endBatchEdit();
        
        return true;
    }

    /**
     * The default implementation removes the composing state from the
     * current editable text.  In addition, only if dummy mode, a key event is
     * sent for the new text and the current editable buffer cleared.
     */
    public boolean finishComposingText() {
        if (DEBUG) Log.v(TAG, "finishComposingText");
        final Editable content = getEditable();
        if (content != null) {
            beginBatchEdit();
            removeComposingSpans(content);
            endBatchEdit();
            sendCurrentText();
        }
        return true;
    }

    /**
     * The default implementation uses TextUtils.getCapsMode to get the
     * cursor caps mode for the current selection position in the editable
     * text, unless in dummy mode in which case 0 is always returned.
     */
    public int getCursorCapsMode(int reqModes) {
        if (mDummyMode) return 0;
        
        final Editable content = getEditable();
        if (content == null) return 0;
        
        int a = Selection.getSelectionStart(content);
        int b = Selection.getSelectionEnd(content);

        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        return TextUtils.getCapsMode(content, a, reqModes);
    }

    /**
     * The default implementation always returns null.
     */
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        return null;
    }

    /**
     * The default implementation returns the given amount of text from the
     * current cursor position in the buffer.
     */
    public CharSequence getTextBeforeCursor(int length, int flags) {
        final Editable content = getEditable();
        if (content == null) return null;

        int a = Selection.getSelectionStart(content);
        int b = Selection.getSelectionEnd(content);

        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        if (a <= 0) {
            return "";
        }
        
        if (length > a) {
            length = a;
        }

        if ((flags&GET_TEXT_WITH_STYLES) != 0) {
            return content.subSequence(a - length, a);
        }
        return TextUtils.substring(content, a - length, a);
    }

    /**
     * The default implementation returns the given amount of text from the
     * current cursor position in the buffer.
     */
    public CharSequence getTextAfterCursor(int length, int flags) {
        final Editable content = getEditable();
        if (content == null) return null;

        int a = Selection.getSelectionStart(content);
        int b = Selection.getSelectionEnd(content);

        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        // Guard against the case where the cursor has not been positioned yet.
        if (b < 0) {
            b = 0;
        }

        if (b + length > content.length()) {
            length = content.length() - b;
        }


        if ((flags&GET_TEXT_WITH_STYLES) != 0) {
            return content.subSequence(b, b + length);
        }
        return TextUtils.substring(content, b, b + length);
    }

    /**
     * The default implementation turns this into the enter key.
     */
    public boolean performEditorAction(int actionCode) {
        long eventTime = SystemClock.uptimeMillis();
        sendKeyEvent(new KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0, 0, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE
                | KeyEvent.FLAG_EDITOR_ACTION));
        sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0, 0, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE
                | KeyEvent.FLAG_EDITOR_ACTION));
        return true;
    }

    /**
     * The default implementation does nothing.
     */
    public boolean performContextMenuAction(int id) {
        return false;
    }

    /**
     * The default implementation does nothing.
     */
    public boolean performPrivateCommand(String action, Bundle data) {
        return false;
    }

    /**
     * The default implementation places the given text into the editable,
     * replacing any existing composing text.  The new text is marked as
     * in a composing state with the composing style.
     */
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        if (DEBUG) Log.v(TAG, "setComposingText " + text);
        replaceText(text, newCursorPosition, true);
        return true;
    }

    /**
     * The default implementation changes the selection position in the
     * current editable text.
     */
    public boolean setSelection(int start, int end) {
        if (DEBUG) Log.v(TAG, "setSelection " + start + ", " + end);
        final Editable content = getEditable();
        if (content == null) return false;
        int len = content.length();
        if (start > len || end > len) {
            // If the given selection is out of bounds, just ignore it.
            // Most likely the text was changed out from under the IME,
            // the the IME is going to have to update all of its state
            // anyway.
            return true;
        }
        if (start == end && MetaKeyKeyListener.getMetaState(content,
                MetaKeyKeyListener.META_SELECTING) != 0) {
            // If we are in selection mode, then we want to extend the
            // selection instead of replacing it.
            Selection.extendSelection(content, start);
        } else {
            Selection.setSelection(content, start, end);
        }
        return true;
    }

    /**
     * Provides standard implementation for sending a key event to the window
     * attached to the input connection's view.
     */
    public boolean sendKeyEvent(KeyEvent event) {
        synchronized (mIMM.mH) {
            Handler h = mTargetView != null ? mTargetView.getHandler() : null;
            if (h == null) {
                if (mIMM.mServedView != null) {
                    h = mIMM.mServedView.getHandler();
                }
            }
            if (h != null) {
                h.sendMessage(h.obtainMessage(ViewRoot.DISPATCH_KEY_FROM_IME,
                        event));
            }
        }
        return false;
    }
    
    /**
     * Updates InputMethodManager with the current fullscreen mode.
     */
    public boolean reportFullscreenMode(boolean enabled) {
        mIMM.setFullscreenMode(enabled);
        return true;
    }
    
    private void sendCurrentText() {
        if (!mDummyMode) {
            return;
        }
        
        Editable content = getEditable();
        if (content != null) {
            final int N = content.length();
            if (N == 0) {
                return;
            }
            if (N == 1) {
                // If it's 1 character, we have a chance of being
                // able to generate normal key events...
                if (mKeyCharacterMap == null) {
                    mKeyCharacterMap = KeyCharacterMap.load(
                            KeyCharacterMap.BUILT_IN_KEYBOARD);
                }
                char[] chars = new char[1];
                content.getChars(0, 1, chars, 0);
                KeyEvent[] events = mKeyCharacterMap.getEvents(chars);
                if (events != null) {
                    for (int i=0; i<events.length; i++) {
                        if (DEBUG) Log.v(TAG, "Sending: " + events[i]);
                        sendKeyEvent(events[i]);
                    }
                    content.clear();
                    return;
                }
            }
            
            // Otherwise, revert to the special key event containing
            // the actual characters.
            KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                    content.toString(), KeyCharacterMap.BUILT_IN_KEYBOARD, 0);
            sendKeyEvent(event);
            content.clear();
        }
    }
    
    private void replaceText(CharSequence text, int newCursorPosition,
            boolean composing) {
        final Editable content = getEditable();
        if (content == null) {
            return;
        }
        
        beginBatchEdit();
        
        // delete composing text set previously.
        int a = getComposingSpanStart(content);
        int b = getComposingSpanEnd(content);

        if (DEBUG) Log.v(TAG, "Composing span: " + a + " to " + b);
        
        if (b < a) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        if (a != -1 && b != -1) {
            removeComposingSpans(content);
        } else {
            a = Selection.getSelectionStart(content);
            b = Selection.getSelectionEnd(content);
            if (a < 0) a = 0;
            if (b < 0) b = 0;
            if (b < a) {
                int tmp = a;
                a = b;
                b = tmp;
            }
        }

        if (composing) {
            Spannable sp = null;
            if (!(text instanceof Spannable)) {
                sp = new SpannableStringBuilder(text);
                text = sp;
                if (mDefaultComposingSpans == null) {
                    Context context;
                    if (mTargetView != null) {
                        context = mTargetView.getContext();
                    } else if (mIMM.mServedView != null) {
                        context = mIMM.mServedView.getContext();
                    } else {
                        context = null;
                    }
                    if (context != null) {
                        TypedArray ta = context.getTheme()
                                .obtainStyledAttributes(new int[] {
                                        com.android.internal.R.attr.candidatesTextStyleSpans
                                });
                        CharSequence style = ta.getText(0);
                        ta.recycle();
                        if (style != null && style instanceof Spanned) {
                            mDefaultComposingSpans = ((Spanned)style).getSpans(
                                    0, style.length(), Object.class);
                        }
                    }
                }
                if (mDefaultComposingSpans != null) {
                    for (int i = 0; i < mDefaultComposingSpans.length; ++i) {
                        sp.setSpan(mDefaultComposingSpans[i], 0, sp.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            } else {
                sp = (Spannable)text;
            }
            setComposingSpans(sp);
        }
        
        if (DEBUG) Log.v(TAG, "Replacing from " + a + " to " + b + " with \""
                + text + "\", composing=" + composing
                + ", type=" + text.getClass().getCanonicalName());
        
        if (DEBUG) {
            LogPrinter lp = new LogPrinter(Log.VERBOSE, TAG);
            lp.println("Current text:");
            TextUtils.dumpSpans(content, lp, "  ");
            lp.println("Composing text:");
            TextUtils.dumpSpans(text, lp, "  ");
        }
        
        // Position the cursor appropriately, so that after replacing the
        // desired range of text it will be located in the correct spot.
        // This allows us to deal with filters performing edits on the text
        // we are providing here.
        if (newCursorPosition > 0) {
            newCursorPosition += b - 1;
        } else {
            newCursorPosition += a;
        }
        if (newCursorPosition < 0) newCursorPosition = 0;
        if (newCursorPosition > content.length())
            newCursorPosition = content.length();
        Selection.setSelection(content, newCursorPosition);

        content.replace(a, b, text);
        
        if (DEBUG) {
            LogPrinter lp = new LogPrinter(Log.VERBOSE, TAG);
            lp.println("Final text:");
            TextUtils.dumpSpans(content, lp, "  ");
        }
        
        endBatchEdit();
    }
}
