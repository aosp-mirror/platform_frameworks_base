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

import static android.view.ContentInfo.SOURCE_INPUT_METHOD;

import android.annotation.CallSuper;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ClipData;
import android.content.ClipDescription;
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
import android.view.ContentInfo;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;

import com.android.internal.util.Preconditions;

class ComposingText implements NoCopySpan {
}

/**
 * Base class for implementors of the InputConnection interface, taking care
 * of most of the common behavior for providing a connection to an Editable.
 * Implementors of this class will want to be sure to implement
 * {@link #getEditable} to provide access to their own editable object, and
 * to refer to the documentation in {@link InputConnection}.
 */
public class BaseInputConnection implements InputConnection {
    private static final boolean DEBUG = false;
    private static final String TAG = "BaseInputConnection";
    static final Object COMPOSING = new ComposingText();

    /** @hide */
    @NonNull protected final InputMethodManager mIMM;

    /**
     * Target view for the input connection.
     *
     * <p>This could be null for a fallback input connection.
     */
    @Nullable final View mTargetView;

    final boolean mFallbackMode;

    private Object[] mDefaultComposingSpans;

    Editable mEditable;
    KeyCharacterMap mKeyCharacterMap;

    BaseInputConnection(@NonNull InputMethodManager mgr, boolean fullEditor) {
        mIMM = mgr;
        mTargetView = null;
        mFallbackMode = !fullEditor;
    }

    public BaseInputConnection(@NonNull View targetView, boolean fullEditor) {
        mIMM = (InputMethodManager)targetView.getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        mTargetView = targetView;
        mFallbackMode = !fullEditor;
    }

    /**
     * Removes the composing spans from the given text if any.
     *
     * @param text the spannable text to remove composing spans
     */
    public static final void removeComposingSpans(@NonNull Spannable text) {
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

    /**
     * Removes the composing spans from the given text if any.
     *
     * @param text the spannable text to remove composing spans
     */
    public static void setComposingSpans(@NonNull Spannable text) {
        setComposingSpans(text, 0, text.length());
    }

    /** @hide */
    public static void setComposingSpans(@NonNull Spannable text, int start, int end) {
        final Object[] sps = text.getSpans(start, end, Object.class);
        if (sps != null) {
            for (int i=sps.length-1; i>=0; i--) {
                final Object o = sps[i];
                if (o == COMPOSING) {
                    text.removeSpan(o);
                    continue;
                }

                final int fl = text.getSpanFlags(o);
                if ((fl & (Spanned.SPAN_COMPOSING | Spanned.SPAN_POINT_MARK_MASK))
                        != (Spanned.SPAN_COMPOSING | Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)) {
                    text.setSpan(
                            o,
                            text.getSpanStart(o),
                            text.getSpanEnd(o),
                            (fl & ~Spanned.SPAN_POINT_MARK_MASK)
                                    | Spanned.SPAN_COMPOSING
                                    | Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        text.setSpan(COMPOSING, start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
    }

    /** Return the beginning of the range of composing text, or -1 if there's no composing text. */
    public static int getComposingSpanStart(@NonNull Spannable text) {
        return text.getSpanStart(COMPOSING);
    }

    /** Return the end of the range of composing text, or -1 if there's no composing text. */
    public static int getComposingSpanEnd(@NonNull Spannable text) {
        return text.getSpanEnd(COMPOSING);
    }

    /**
     * Return the target of edit operations. The default implementation returns its own fake
     * editable that is just used for composing text; subclasses that are real text editors should
     * override this and supply their own.
     *
     * <p>Subclasses could override this method to turn null.
     */
    @Nullable
    public Editable getEditable() {
        if (mEditable == null) {
            mEditable = Editable.Factory.getInstance().newEditable("");
            Selection.setSelection(mEditable, 0);
        }
        return mEditable;
    }

    /** Default implementation does nothing. */
    @Override
    public boolean beginBatchEdit() {
        return false;
    }

    /** Default implementation does nothing. */
    @Override
    public boolean endBatchEdit() {
        return false;
    }

    /**
     * Called after only the composing region is modified (so it isn't called if the text also
     * changes).
     *
     * <p>Default implementation does nothing.
     *
     * @hide
     */
    public void endComposingRegionEditInternal() {}

    /**
     * Default implementation calls {@link #finishComposingText()} and {@code
     * setImeConsumesInput(false)}.
     */
    @CallSuper
    @Override
    public void closeConnection() {
        finishComposingText();
        setImeConsumesInput(false);
    }

    /**
     * Default implementation uses {@link MetaKeyKeyListener#clearMetaKeyState(long, int)
     * MetaKeyKeyListener.clearMetaKeyState(long, int)} to clear the state.
     */
    @Override
    public boolean clearMetaKeyStates(int states) {
        final Editable content = getEditable();
        if (content == null) return false;
        MetaKeyKeyListener.clearMetaKeyState(content, states);
        return true;
    }

    /** Default implementation does nothing and returns false. */
    @Override
    public boolean commitCompletion(CompletionInfo text) {
        return false;
    }

    /** Default implementation does nothing and returns false. */
    @Override
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        return false;
    }

    /**
     * Default implementation replaces any existing composing text with the given text. In addition,
     * only if fallback mode, a key event is sent for the new text and the current editable buffer
     * cleared.
     */
    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (DEBUG) Log.v(TAG, "commitText(" + text + ", " + newCursorPosition + ")");
        replaceText(text, newCursorPosition, false);
        sendCurrentText();
        return true;
    }

    /**
     * The default implementation performs the deletion around the current selection position of the
     * editable text.
     *
     * @param beforeLength The number of characters before the cursor to be deleted, in code unit.
     *     If this is greater than the number of existing characters between the beginning of the
     *     text and the cursor, then this method does not fail but deletes all the characters in
     *     that range.
     * @param afterLength The number of characters after the cursor to be deleted, in code unit. If
     *     this is greater than the number of existing characters between the cursor and the end of
     *     the text, then this method does not fail but deletes all the characters in that range.
     * @return {@code true} when selected text is deleted, {@code false} when either the selection
     *     is invalid or not yet attached (i.e. selection start or end is -1), or the editable text
     *     is {@code null}.
     */
    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (DEBUG) Log.v(TAG, "deleteSurroundingText(" + beforeLength + ", " + afterLength + ")");
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

        // Skip when the selection is not yet attached.
        if (a == -1 || b == -1) {
            endBatchEdit();
            return false;
        }

        // Ignore the composing text.
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

        if (beforeLength > 0) {
            int start = a - beforeLength;
            if (start < 0) start = 0;

            final int numDeleteBefore = a - start;
            if (a >= 0 && numDeleteBefore > 0) {
                content.delete(start, a);
                deleted = numDeleteBefore;
            }
        }

        if (afterLength > 0) {
            b = b - deleted;

            int end = b + afterLength;
            if (end > content.length()) end = content.length();

            final int numDeleteAfter = end - b;
            if (b >= 0 && numDeleteAfter > 0) {
                content.delete(b, end);
            }
        }

        endBatchEdit();

        return true;
    }

    private static int INVALID_INDEX = -1;
    private static int findIndexBackward(final CharSequence cs, final int from,
            final int numCodePoints) {
        int currentIndex = from;
        boolean waitingHighSurrogate = false;
        final int N = cs.length();
        if (currentIndex < 0 || N < currentIndex) {
            return INVALID_INDEX;  // The starting point is out of range.
        }
        if (numCodePoints < 0) {
            return INVALID_INDEX;  // Basically this should not happen.
        }
        int remainingCodePoints = numCodePoints;
        while (true) {
            if (remainingCodePoints == 0) {
                return currentIndex;  // Reached to the requested length in code points.
            }

            --currentIndex;
            if (currentIndex < 0) {
                if (waitingHighSurrogate) {
                    return INVALID_INDEX;  // An invalid surrogate pair is found.
                }
                return 0;  // Reached to the beginning of the text w/o any invalid surrogate pair.
            }
            final char c = cs.charAt(currentIndex);
            if (waitingHighSurrogate) {
                if (!java.lang.Character.isHighSurrogate(c)) {
                    return INVALID_INDEX;  // An invalid surrogate pair is found.
                }
                waitingHighSurrogate = false;
                --remainingCodePoints;
                continue;
            }
            if (!java.lang.Character.isSurrogate(c)) {
                --remainingCodePoints;
                continue;
            }
            if (java.lang.Character.isHighSurrogate(c)) {
                return INVALID_INDEX;  // A invalid surrogate pair is found.
            }
            waitingHighSurrogate = true;
        }
    }

    private static int findIndexForward(final CharSequence cs, final int from,
            final int numCodePoints) {
        int currentIndex = from;
        boolean waitingLowSurrogate = false;
        final int N = cs.length();
        if (currentIndex < 0 || N < currentIndex) {
            return INVALID_INDEX;  // The starting point is out of range.
        }
        if (numCodePoints < 0) {
            return INVALID_INDEX;  // Basically this should not happen.
        }
        int remainingCodePoints = numCodePoints;

        while (true) {
            if (remainingCodePoints == 0) {
                return currentIndex;  // Reached to the requested length in code points.
            }

            if (currentIndex >= N) {
                if (waitingLowSurrogate) {
                    return INVALID_INDEX;  // An invalid surrogate pair is found.
                }
                return N;  // Reached to the end of the text w/o any invalid surrogate pair.
            }
            final char c = cs.charAt(currentIndex);
            if (waitingLowSurrogate) {
                if (!java.lang.Character.isLowSurrogate(c)) {
                    return INVALID_INDEX;  // An invalid surrogate pair is found.
                }
                --remainingCodePoints;
                waitingLowSurrogate = false;
                ++currentIndex;
                continue;
            }
            if (!java.lang.Character.isSurrogate(c)) {
                --remainingCodePoints;
                ++currentIndex;
                continue;
            }
            if (java.lang.Character.isLowSurrogate(c)) {
                return INVALID_INDEX; // A invalid surrogate pair is found.
            }
            waitingLowSurrogate = true;
            ++currentIndex;
        }
    }

    /**
     * The default implementation performs the deletion around the current selection position of the
     * editable text.
     *
     * @param beforeLength The number of characters before the cursor to be deleted, in code points.
     *     If this is greater than the number of existing characters between the beginning of the
     *     text and the cursor, then this method does not fail but deletes all the characters in
     *     that range.
     * @param afterLength The number of characters after the cursor to be deleted, in code points.
     *     If this is greater than the number of existing characters between the cursor and the end
     *     of the text, then this method does not fail but deletes all the characters in that range.
     */
    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        if (DEBUG) Log.v(TAG, "deleteSurroundingText " + beforeLength + " / " + afterLength);
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

        // Ignore the composing text.
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

        if (a >= 0 && b >= 0) {
            final int start = findIndexBackward(content, a, Math.max(beforeLength, 0));
            if (start != INVALID_INDEX) {
                final int end = findIndexForward(content, b, Math.max(afterLength, 0));
                if (end != INVALID_INDEX) {
                    final int numDeleteBefore = a - start;
                    if (numDeleteBefore > 0) {
                        content.delete(start, a);
                    }
                    final int numDeleteAfter = end - b;
                    if (numDeleteAfter > 0) {
                        content.delete(b - numDeleteBefore, end - numDeleteBefore);
                    }
                }
            }
            // NOTE: You may think we should return false here if start and/or end is INVALID_INDEX,
            // but the truth is that IInputConnectionWrapper running in the middle of IPC calls
            // always returns true to the IME without waiting for the completion of this method as
            // IInputConnectionWrapper#isAtive() returns true.  This is actually why some methods
            // including this method look like asynchronous calls from the IME.
        }

        endBatchEdit();

        return true;
    }

    /**
     * The default implementation removes the composing state from the current editable text. In
     * addition, only if fallback mode, a key event is sent for the new text and the current
     * editable buffer cleared.
     */
    @Override
    public boolean finishComposingText() {
        if (DEBUG) Log.v(TAG, "finishComposingText");
        final Editable content = getEditable();
        if (content != null) {
            beginBatchEdit();
            removeComposingSpans(content);
            // Note: sendCurrentText does nothing unless mFallbackMode is set
            sendCurrentText();
            endBatchEdit();
            endComposingRegionEditInternal();
        }
        return true;
    }

    /**
     * The default implementation uses TextUtils.getCapsMode to get the cursor caps mode for the
     * current selection position in the editable text, unless in fallback mode in which case 0 is
     * always returned.
     */
    @Override
    public int getCursorCapsMode(int reqModes) {
        if (mFallbackMode) return 0;

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

    /** The default implementation always returns null. */
    @Override
    @Nullable
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        return null;
    }

    /**
     * The default implementation returns the given amount of text from the current cursor position
     * in the buffer.
     */
    @Override
    @Nullable
    public CharSequence getTextBeforeCursor(@IntRange(from = 0) int length, int flags) {
        Preconditions.checkArgumentNonnegative(length);

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
     * The default implementation returns the text currently selected, or null if none is selected.
     */
    @Override
    @Nullable
    public CharSequence getSelectedText(int flags) {
        final Editable content = getEditable();
        if (content == null) return null;

        int a = Selection.getSelectionStart(content);
        int b = Selection.getSelectionEnd(content);

        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }

        if (a == b || a < 0) return null;

        if ((flags&GET_TEXT_WITH_STYLES) != 0) {
            return content.subSequence(a, b);
        }
        return TextUtils.substring(content, a, b);
    }

    /**
     * The default implementation returns the given amount of text from the current cursor position
     * in the buffer.
     */
    @Override
    @Nullable
    public CharSequence getTextAfterCursor(@IntRange(from = 0) int length, int flags) {
        Preconditions.checkArgumentNonnegative(length);

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
        int end = (int) Math.min((long) b + length, content.length());
        if ((flags&GET_TEXT_WITH_STYLES) != 0) {
            return content.subSequence(b, end);
        }
        return TextUtils.substring(content, b, end);
    }

    /**
     * The default implementation returns the given amount of text around the current cursor
     * position in the buffer.
     */
    @Override
    @Nullable
    public SurroundingText getSurroundingText(
            @IntRange(from = 0) int beforeLength, @IntRange(from = 0) int afterLength, int flags) {
        Preconditions.checkArgumentNonnegative(beforeLength);
        Preconditions.checkArgumentNonnegative(afterLength);

        final Editable content = getEditable();
        // If {@link #getEditable()} is null or {@code mEditable} is equal to {@link #getEditable()}
        // (a.k.a, a fake editable), it means we cannot get valid content from the editable, so
        // fallback to retrieve surrounding text from other APIs.
        if (content == null || mEditable == content) {
            return InputConnection.super.getSurroundingText(beforeLength, afterLength, flags);
        }

        int selStart = Selection.getSelectionStart(content);
        int selEnd = Selection.getSelectionEnd(content);

        // Guard against the case where the cursor has not been positioned yet.
        if (selStart < 0 || selEnd < 0) {
            return null;
        }

        if (selStart > selEnd) {
            int tmp = selStart;
            selStart = selEnd;
            selEnd = tmp;
        }

        // Guards the start and end pos within range [0, contentLength].
        int startPos = Math.max(0, selStart - beforeLength);
        int endPos = (int) Math.min((long) selEnd + afterLength, content.length());

        CharSequence surroundingText;
        if ((flags & GET_TEXT_WITH_STYLES) != 0) {
            surroundingText = content.subSequence(startPos, endPos);
        } else {
            surroundingText = TextUtils.substring(content, startPos, endPos);
        }
        return new SurroundingText(
                surroundingText, selStart - startPos, selEnd - startPos, startPos);
    }

    /** The default implementation turns this into the enter key. */
    @Override
    public boolean performEditorAction(int actionCode) {
        long eventTime = SystemClock.uptimeMillis();
        sendKeyEvent(
                new KeyEvent(
                        eventTime,
                        eventTime,
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_ENTER,
                        0,
                        0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        KeyEvent.FLAG_SOFT_KEYBOARD
                                | KeyEvent.FLAG_KEEP_TOUCH_MODE
                                | KeyEvent.FLAG_EDITOR_ACTION));
        sendKeyEvent(
                new KeyEvent(
                        SystemClock.uptimeMillis(),
                        eventTime,
                        KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_ENTER,
                        0,
                        0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        KeyEvent.FLAG_SOFT_KEYBOARD
                                | KeyEvent.FLAG_KEEP_TOUCH_MODE
                                | KeyEvent.FLAG_EDITOR_ACTION));
        return true;
    }

    /** The default implementation does nothing. */
    @Override
    public boolean performContextMenuAction(int id) {
        return false;
    }

    /** The default implementation does nothing. */
    @Override
    public boolean performPrivateCommand(String action, Bundle data) {
        return false;
    }

    /** The default implementation does nothing. */
    @Override
    public boolean requestCursorUpdates(int cursorUpdateMode) {
        return false;
    }

    @Override
    @Nullable
    public Handler getHandler() {
        return null;
    }

    /**
     * The default implementation places the given text into the editable, replacing any existing
     * composing text. The new text is marked as in a composing state with the composing style.
     */
    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        if (DEBUG) Log.v(TAG, "setComposingText(" + text + ", " + newCursorPosition + ")");
        replaceText(text, newCursorPosition, true);
        return true;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        if (DEBUG) Log.v(TAG, "setComposingRegion(" + start + ", " + end + ")");
        final Editable content = getEditable();
        if (content != null) {
            beginBatchEdit();
            removeComposingSpans(content);
            int a = start;
            int b = end;
            if (a > b) {
                int tmp = a;
                a = b;
                b = tmp;
            }
            // Clip the end points to be within the content bounds.
            final int length = content.length();
            if (a < 0) a = 0;
            if (b < 0) b = 0;
            if (a > length) a = length;
            if (b > length) b = length;

            ensureDefaultComposingSpans();
            if (mDefaultComposingSpans != null) {
                for (int i = 0; i < mDefaultComposingSpans.length; ++i) {
                    content.setSpan(
                            mDefaultComposingSpans[i],
                            a,
                            b,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
                }
            }

            content.setSpan(COMPOSING, a, b,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);

            // Note: sendCurrentText does nothing unless mFallbackMode is set
            sendCurrentText();
            endBatchEdit();
            endComposingRegionEditInternal();
        }
        return true;
    }

    /** The default implementation changes the selection position in the current editable text. */
    @Override
    public boolean setSelection(int start, int end) {
        if (DEBUG) Log.v(TAG, "setSelection(" + start + ", " + end + ")");
        final Editable content = getEditable();
        if (content == null) return false;
        int len = content.length();
        if (start > len || end > len || start < 0 || end < 0) {
            // If the given selection is out of bounds, just ignore it.
            // Most likely the text was changed out from under the IME,
            // and the IME is going to have to update all of its state
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
     * Provides standard implementation for sending a key event to the window attached to the input
     * connection's view.
     */
    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        mIMM.dispatchKeyEventFromInputMethod(mTargetView, event);
        return false;
    }

    /** Updates InputMethodManager with the current fullscreen mode. */
    @Override
    public boolean reportFullscreenMode(boolean enabled) {
        return true;
    }

    private void sendCurrentText() {
        if (!mFallbackMode) {
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
                    mKeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
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
                    content.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
            sendKeyEvent(event);
            content.clear();
        }
    }

    private void ensureDefaultComposingSpans() {
        if (mDefaultComposingSpans == null) {
            Context context;
            if (mTargetView != null) {
                context = mTargetView.getContext();
            } else {
                context = mIMM.getFallbackContextFromServedView();
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
    }

    @Override
    public boolean replaceText(
            @IntRange(from = 0) int start,
            @IntRange(from = 0) int end,
            @NonNull CharSequence text,
            int newCursorPosition,
            @Nullable TextAttribute textAttribute) {
        Preconditions.checkArgumentNonnegative(start);
        Preconditions.checkArgumentNonnegative(end);

        if (DEBUG) {
            Log.v(
                    TAG,
                    "replaceText " + start + ", " + end + ", " + text + ", " + newCursorPosition);
        }

        final Editable content = getEditable();
        if (content == null) {
            return false;
        }
        beginBatchEdit();
        removeComposingSpans(content);

        int len = content.length();
        start = Math.min(start, len);
        end = Math.min(end, len);
        if (end < start) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        replaceTextInternal(start, end, text, newCursorPosition, /*composing=*/ false);
        endBatchEdit();
        return true;
    }

    private void replaceText(CharSequence text, int newCursorPosition, boolean composing) {
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
        replaceTextInternal(a, b, text, newCursorPosition, composing);
        endBatchEdit();
    }

    private void replaceTextInternal(
            int a, int b, CharSequence text, int newCursorPosition, boolean composing) {
        final Editable content = getEditable();
        if (content == null) {
            return;
        }

        if (composing) {
            Spannable sp = null;
            if (!(text instanceof Spannable)) {
                sp = new SpannableStringBuilder(text);
                text = sp;
                ensureDefaultComposingSpans();
                if (mDefaultComposingSpans != null) {
                    for (int i = 0; i < mDefaultComposingSpans.length; ++i) {
                        sp.setSpan(mDefaultComposingSpans[i], 0, sp.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
                    }
                }
            } else {
                sp = (Spannable)text;
            }
            setComposingSpans(sp);
        }

        if (DEBUG) {
            Log.v(
                    TAG,
                    "Replacing from "
                            + a
                            + " to "
                            + b
                            + " with \""
                            + text
                            + "\", composing="
                            + composing
                            + ", newCursorPosition="
                            + newCursorPosition
                            + ", type="
                            + text.getClass().getCanonicalName());

            LogPrinter lp = new LogPrinter(Log.VERBOSE, TAG);
            lp.println("Current text:");
            TextUtils.dumpSpans(content, lp, "  ");
            lp.println("Composing text:");
            TextUtils.dumpSpans(text, lp, "  ");
        }

        // Position the cursor appropriately, so that after replacing the desired range of text it
        // will be located in the correct spot.
        // This allows us to deal with filters performing edits on the text we are providing here.
        int requestedNewCursorPosition = newCursorPosition;
        if (newCursorPosition > 0) {
            newCursorPosition += b - 1;
        } else {
            newCursorPosition += a;
        }
        if (newCursorPosition < 0) newCursorPosition = 0;
        if (newCursorPosition > content.length()) newCursorPosition = content.length();
        Selection.setSelection(content, newCursorPosition);
        content.replace(a, b, text);

        // Replace (or insert) to the cursor (a==b==newCursorPosition) will position the cursor to
        // the end of the new replaced/inserted text, we need to re-position the cursor to the start
        // according the API definition: "if <= 0, this is relative to the start of the text".
        if (requestedNewCursorPosition == 0 && a == b) {
            Selection.setSelection(content, newCursorPosition);
        }

        if (DEBUG) {
            LogPrinter lp = new LogPrinter(Log.VERBOSE, TAG);
            lp.println("Final text:");
            TextUtils.dumpSpans(content, lp, "  ");
        }
    }

    /**
     * Default implementation which invokes {@link View#performReceiveContent} on the target view if
     * the view {@link View#getReceiveContentMimeTypes allows} content insertion; otherwise returns
     * false without any side effects.
     */
    @Override
    public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
        if (mTargetView == null) {
            return false;
        }

        ClipDescription description = inputContentInfo.getDescription();
        if (mTargetView.getReceiveContentMimeTypes() == null) {
            if (DEBUG) {
                Log.d(TAG, "Can't insert content from IME: content=" + description);
            }
            return false;
        }
        if ((flags & InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                inputContentInfo.requestPermission();
            } catch (Exception e) {
                Log.w(TAG, "Can't insert content from IME; requestPermission() failed", e);
                return false;
            }
        }
        final ClipData clip = new ClipData(inputContentInfo.getDescription(),
                new ClipData.Item(inputContentInfo.getContentUri()));
        final ContentInfo payload = new ContentInfo.Builder(clip, SOURCE_INPUT_METHOD)
                .setLinkUri(inputContentInfo.getLinkUri())
                .setExtras(opts)
                .setInputContentInfo(inputContentInfo)
                .build();
        return mTargetView.performReceiveContent(payload) == null;
    }

    /**
     * Default implementation that constructs {@link TextSnapshot} with information extracted from
     * {@link BaseInputConnection}.
     *
     * @return {@code null} when {@link TextSnapshot} cannot be fully taken.
     */
    @Nullable
    @Override
    public TextSnapshot takeSnapshot() {
        final Editable content = getEditable();
        if (content == null) {
            return null;
        }
        int composingStart = getComposingSpanStart(content);
        int composingEnd = getComposingSpanEnd(content);
        if (composingEnd < composingStart) {
            final int tmp = composingStart;
            composingStart = composingEnd;
            composingEnd = tmp;
        }

        final SurroundingText surroundingText = getSurroundingText(
                EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH / 2,
                EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH / 2, GET_TEXT_WITH_STYLES);
        if (surroundingText == null) {
            return null;
        }

        final int cursorCapsMode = getCursorCapsMode(TextUtils.CAP_MODE_CHARACTERS
                | TextUtils.CAP_MODE_WORDS | TextUtils.CAP_MODE_SENTENCES);

        return new TextSnapshot(surroundingText, composingStart, composingEnd, cursorCapsMode);
    }
}
