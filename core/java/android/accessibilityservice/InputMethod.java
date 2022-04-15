/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.accessibilityservice;

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import android.annotation.CallbackExecutor;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.inputmethodservice.IInputMethodSessionWrapper;
import android.inputmethodservice.RemoteInputConnection;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSession;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;

import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputSessionWithIdCallback;

import java.util.concurrent.Executor;

/**
 * This class provides input method APIs. Some public methods such as
 * @link #onUpdateSelection(int, int, int, int, int, int)} do nothing by default and service
 * developers should override them as needed. Developers should also override
 * {@link AccessibilityService#onCreateInputMethod()} to return
 * their custom InputMethod implementation. Accessibility services also need to set the
 * {@link AccessibilityServiceInfo#FLAG_INPUT_METHOD_EDITOR} flag to use input method APIs.
 */
public class InputMethod {
    private static final String LOG_TAG = "A11yInputMethod";

    private final AccessibilityService mService;
    private InputBinding mInputBinding;
    private boolean mInputStarted;
    private InputConnection mStartedInputConnection;
    private EditorInfo mInputEditorInfo;

    /**
     * Creates a new InputMethod instance for the given <code>service</code>, so that the
     * accessibility service can control editing.
     */
    public InputMethod(@NonNull AccessibilityService service) {
        mService = service;
    }

    /**
     * Retrieve the currently active InputConnection that is bound to
     * the input method, or null if there is none.
     */
    @Nullable
    public final AccessibilityInputConnection getCurrentInputConnection() {
        if (mStartedInputConnection != null) {
            return new AccessibilityInputConnection(mStartedInputConnection);
        }
        return null;
    }

    /**
     * Whether the input has started.
     */
    public final boolean getCurrentInputStarted() {
        return mInputStarted;
    }

    /**
     * Get the EditorInfo which describes several attributes of a text editing object
     * that an accessibility service is communicating with (typically an EditText).
     */
    @Nullable
    public final EditorInfo getCurrentInputEditorInfo() {
        return mInputEditorInfo;
    }

    /**
     * Called to inform the accessibility service that text input has started in an
     * editor.  You should use this callback to initialize the state of your
     * input to match the state of the editor given to it.
     *
     * @param attribute  The attributes of the editor that input is starting
     *                   in.
     * @param restarting Set to true if input is restarting in the same
     *                   editor such as because the application has changed the text in
     *                   the editor.  Otherwise will be false, indicating this is a new
     *                   session with the editor.
     */
    public void onStartInput(@NonNull EditorInfo attribute, boolean restarting) {
        // Intentionally empty
    }

    /**
     * Called to inform the accessibility service that text input has finished in
     * the last editor. At this point there may be a call to
     * {@link #onStartInput(EditorInfo, boolean)} to perform input in a
     * new editor, or the accessibility service may be left idle. This method is
     * <em>not</em> called when input restarts in the same editor.
     *
     * <p>The default
     * implementation uses the InputConnection to clear any active composing
     * text; you can override this (not calling the base class implementation)
     * to perform whatever behavior you would like.
     */
    public void onFinishInput() {
        if (mStartedInputConnection != null) {
            mStartedInputConnection.finishComposingText();
        }
    }

    /**
     * Called when the application has reported a new selection region of
     * the text. This is called whether or not the accessibility service has requested
     * extracted text updates, although if so it will not receive this call
     * if the extracted text has changed as well.
     *
     * <p>Be careful about changing the text in reaction to this call with
     * methods such as setComposingText, commitText or
     * deleteSurroundingText. If the cursor moves as a result, this method
     * will be called again, which may result in an infinite loop.
     */
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart,
            int newSelEnd, int candidatesStart, int candidatesEnd) {
        // Intentionally empty
    }

    final void createImeSession(IInputSessionWithIdCallback callback) {
        InputMethodSession session = onCreateInputMethodSessionInterface();
        try {
            IInputMethodSessionWrapper wrap =
                    new IInputMethodSessionWrapper(mService, session, null);
            callback.sessionCreated(wrap, mService.getConnectionId());
        } catch (RemoteException ignored) {
        }
    }

    final void setImeSessionEnabled(@NonNull InputMethodSession session, boolean enabled) {
        ((InputMethodSessionForAccessibility) session).setEnabled(enabled);
    }

    final void bindInput(@NonNull InputBinding binding) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "AccessibilityService.bindInput");
        mInputBinding = binding;
        Log.v(LOG_TAG, "bindInput(): binding=" + binding);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    final void unbindInput() {
        Log.v(LOG_TAG, "unbindInput(): binding=" + mInputBinding);
        // Unbind input is per process per display.
        mInputBinding = null;
    }

    final void startInput(@Nullable InputConnection ic, @NonNull EditorInfo attribute) {
        Log.v(LOG_TAG, "startInput(): editor=" + attribute);
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.startInput");
        doStartInput(ic, attribute, false /* restarting */);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    final void restartInput(@Nullable InputConnection ic, @NonNull EditorInfo attribute) {
        Log.v(LOG_TAG, "restartInput(): editor=" + attribute);
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.restartInput");
        doStartInput(ic, attribute, true /* restarting */);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }


    final void doStartInput(InputConnection ic, EditorInfo attribute, boolean restarting) {
        if ((ic == null || !restarting) && mInputStarted) {
            doFinishInput();
            if (ic == null) {
                // Unlike InputMethodService, A11y IME should not observe fallback InputConnection.
                return;
            }
        }
        mInputStarted = true;
        mStartedInputConnection = ic;
        mInputEditorInfo = attribute;
        Log.v(LOG_TAG, "CALL: onStartInput");
        onStartInput(attribute, restarting);
    }

    final void doFinishInput() {
        Log.v(LOG_TAG, "CALL: doFinishInput");
        if (mInputStarted) {
            Log.v(LOG_TAG, "CALL: onFinishInput");
            onFinishInput();
        }
        mInputStarted = false;
        mStartedInputConnection = null;
        mInputEditorInfo = null;
    }

    private InputMethodSession onCreateInputMethodSessionInterface() {
        return new InputMethodSessionForAccessibility();
    }

    /**
     * This class provides the allowed list of {@link InputConnection} APIs for
     * accessibility services.
     */
    public final class AccessibilityInputConnection {
        private InputConnection mIc;
        AccessibilityInputConnection(InputConnection ic) {
            this.mIc = ic;
        }

        /**
         * Commit text to the text box and set the new cursor position. This method is
         * used to allow the IME to provide extra information while setting up text.
         *
         * <p>This method commits the contents of the currently composing text, and then
         * moves the cursor according to {@code newCursorPosition}. If there
         * is no composing text when this method is called, the new text is
         * inserted at the cursor position, removing text inside the selection
         * if any.
         *
         * <p>Calling this method will cause the editor to call
         * {@link #onUpdateSelection(int, int, int, int,
         * int, int)} on the current accessibility service after the batch input is over.
         * <strong>Editor authors</strong>, for this to happen you need to
         * make the changes known to the accessibility service by calling
         * {@link InputMethodManager#updateSelection(View, int, int, int, int)},
         * but be careful to wait until the batch edit is over if one is
         * in progress.</p>
         *
         * @param text The text to commit. This may include styles.
         * @param newCursorPosition The new cursor position around the text,
         *        in Java characters. If > 0, this is relative to the end
         *        of the text - 1; if <= 0, this is relative to the start
         *        of the text. So a value of 1 will always advance the cursor
         *        to the position after the full text being inserted. Note that
         *        this means you can't position the cursor within the text,
         *        because the editor can make modifications to the text
         *        you are providing so it is not possible to correctly specify
         *        locations there.
         * @param textAttribute The extra information about the text.
         */
        public void commitText(@NonNull CharSequence text, int newCursorPosition,
                @Nullable TextAttribute textAttribute) {
            if (mIc != null) {
                mIc.commitText(text, newCursorPosition, textAttribute);
            }
        }

        /**
         * Set the selection of the text editor. To set the cursor
         * position, start and end should have the same value.
         *
         * <p>Since this moves the cursor, calling this method will cause
         * the editor to call
         * {@link android.inputmethodservice.InputMethodService#onUpdateSelection(int, int, int,
         * int,int, int)} on the current IME after the batch input is over.
         * <strong>Editor authors</strong>, for this to happen you need to
         * make the changes known to the input method by calling
         * {@link InputMethodManager#updateSelection(View, int, int, int, int)},
         * but be careful to wait until the batch edit is over if one is
         * in progress.</p>
         *
         * <p>This has no effect on the composing region which must stay
         * unchanged. The order of start and end is not important. In
         * effect, the region from start to end and the region from end to
         * start is the same. Editor authors, be ready to accept a start
         * that is greater than end.</p>
         *
         * @param start the character index where the selection should start.
         * @param end the character index where the selection should end.
         */
        public void setSelection(int start, int end) {
            if (mIc != null) {
                mIc.setSelection(start, end);
            }
        }

        /**
         * Gets the surrounding text around the current cursor, with <var>beforeLength</var>
         * characters of text before the cursor (start of the selection), <var>afterLength</var>
         * characters of text after the cursor (end of the selection), and all of the selected
         * text. The range are for java characters, not glyphs that can be multiple characters.
         *
         * <p>This method may fail either if the input connection has become invalid (such as its
         * process crashing), or the client is taking too long to respond with the text (it is
         * given a couple seconds to return), or the protocol is not supported. In any of these
         * cases, null is returned.
         *
         * <p>This method does not affect the text in the editor in any way, nor does it affect the
         * selection or composing spans.</p>
         *
         * <p>If {@link InputConnection#GET_TEXT_WITH_STYLES} is supplied as flags, the editor
         * should return a {@link android.text.Spanned} with all the spans set on the text.</p>
         *
         * <p><strong>Accessibility service authors:</strong> please consider this will trigger an
         * IPC round-trip that will take some time. Assume this method consumes a lot of time.
         *
         * @param beforeLength The expected length of the text before the cursor.
         * @param afterLength The expected length of the text after the cursor.
         * @param flags Supplies additional options controlling how the text is returned. May be
         *              either {@code 0} or {@link InputConnection#GET_TEXT_WITH_STYLES}.
         * @return an {@link android.view.inputmethod.SurroundingText} object describing the
         * surrounding text and state of selection, or null if the input connection is no longer
         * valid, or the editor can't comply with the request for some reason, or the application
         * does not implement this method. The length of the returned text might be less than the
         * sum of <var>beforeLength</var> and <var>afterLength</var> .
         * @throws IllegalArgumentException if {@code beforeLength} or {@code afterLength} is
         * negative.
         */
        @Nullable
        public SurroundingText getSurroundingText(
                @IntRange(from = 0) int beforeLength, @IntRange(from = 0) int afterLength,
                @InputConnection.GetTextType int flags) {
            if (mIc != null) {
                return mIc.getSurroundingText(beforeLength, afterLength, flags);
            }
            return null;
        }

        /**
         * Delete <var>beforeLength</var> characters of text before the
         * current cursor position, and delete <var>afterLength</var>
         * characters of text after the current cursor position, excluding
         * the selection. Before and after refer to the order of the
         * characters in the string, not to their visual representation:
         * this means you don't have to figure out the direction of the
         * text and can just use the indices as-is.
         *
         * <p>The lengths are supplied in Java chars, not in code points
         * or in glyphs.</p>
         *
         * <p>Since this method only operates on text before and after the
         * selection, it can't affect the contents of the selection. This
         * may affect the composing span if the span includes characters
         * that are to be deleted, but otherwise will not change it. If
         * some characters in the composing span are deleted, the
         * composing span will persist but get shortened by however many
         * chars inside it have been removed.</p>
         *
         * <p><strong>Accessibility service authors:</strong> please be careful not to
         * delete only half of a surrogate pair. Also take care not to
         * delete more characters than are in the editor, as that may have
         * ill effects on the application. Calling this method will cause
         * the editor to call
         * {@link android.inputmethodservice.InputMethodService#onUpdateSelection(int, int, int, int,
         * int, int)} on your service after the batch input is over.</p>
         *
         * <p><strong>Editor authors:</strong> please be careful of race
         * conditions in implementing this call. An IME can make a change
         * to the text or change the selection position and use this
         * method right away; you need to make sure the effects are
         * consistent with the results of the latest edits. Also, although
         * the IME should not send lengths bigger than the contents of the
         * string, you should check the values for overflows and trim the
         * indices to the size of the contents to avoid crashes. Since
         * this changes the contents of the editor, you need to make the
         * changes known to the input method by calling
         * {@link InputMethodManager#updateSelection(View, int, int, int, int)},
         * but be careful to wait until the batch edit is over if one is
         * in progress.</p>
         *
         * @param beforeLength The number of characters before the cursor to be deleted, in code
         *        unit. If this is greater than the number of existing characters between the
         *        beginning of the text and the cursor, then this method does not fail but deletes
         *        all the characters in that range.
         * @param afterLength The number of characters after the cursor to be deleted, in code unit.
         *        If this is greater than the number of existing characters between the cursor and
         *        the end of the text, then this method does not fail but deletes all the characters
         *        in that range.
         */
        public void deleteSurroundingText(int beforeLength, int afterLength) {
            if (mIc != null) {
                mIc.deleteSurroundingText(beforeLength, afterLength);
            }
        }

        /**
         * Send a key event to the process that is currently attached
         * through this input connection. The event will be dispatched
         * like a normal key event, to the currently focused view; this
         * generally is the view that is providing this InputConnection,
         * but due to the asynchronous nature of this protocol that can
         * not be guaranteed and the focus may have changed by the time
         * the event is received.
         *
         * <p>This method can be used to send key events to the
         * application. For example, an on-screen keyboard may use this
         * method to simulate a hardware keyboard. There are three types
         * of standard keyboards, numeric (12-key), predictive (20-key)
         * and ALPHA (QWERTY). You can specify the keyboard type by
         * specify the device id of the key event.</p>
         *
         * <p>You will usually want to set the flag
         * {@link KeyEvent#FLAG_SOFT_KEYBOARD KeyEvent.FLAG_SOFT_KEYBOARD}
         * on all key event objects you give to this API; the flag will
         * not be set for you.</p>
         *
         * <p>Note that it's discouraged to send such key events in normal
         * operation; this is mainly for use with
         * {@link android.text.InputType#TYPE_NULL} type text fields. Use
         * the {@link #commitText} family of methods to send text to the
         * application instead.</p>
         *
         * @param event The key event.
         *
         * @see KeyEvent
         * @see KeyCharacterMap#NUMERIC
         * @see KeyCharacterMap#PREDICTIVE
         * @see KeyCharacterMap#ALPHA
         */
        public void sendKeyEvent(@NonNull KeyEvent event) {
            if (mIc != null) {
                mIc.sendKeyEvent(event);
            }
        }

        /**
         * Have the editor perform an action it has said it can do.
         *
         * @param editorAction This must be one of the action constants for
         * {@link EditorInfo#imeOptions EditorInfo.imeOptions}, such as
         * {@link EditorInfo#IME_ACTION_GO EditorInfo.EDITOR_ACTION_GO}, or the value of
         * {@link EditorInfo#actionId EditorInfo.actionId} if a custom action is available.
         */
        public void performEditorAction(int editorAction) {
            if (mIc != null) {
                mIc.performEditorAction(editorAction);
            }
        }

        /**
         * Perform a context menu action on the field. The given id may be one of:
         * {@link android.R.id#selectAll},
         * {@link android.R.id#startSelectingText}, {@link android.R.id#stopSelectingText},
         * {@link android.R.id#cut}, {@link android.R.id#copy},
         * {@link android.R.id#paste}, {@link android.R.id#copyUrl},
         * or {@link android.R.id#switchInputMethod}
         */
        public void performContextMenuAction(int id) {
            if (mIc != null) {
                mIc.performContextMenuAction(id);
            }
        }

        /**
         * Retrieve the current capitalization mode in effect at the
         * current cursor position in the text. See
         * {@link android.text.TextUtils#getCapsMode TextUtils.getCapsMode}
         * for more information.
         *
         * <p>This method may fail either if the input connection has
         * become invalid (such as its process crashing) or the client is
         * taking too long to respond with the text (it is given a couple
         * seconds to return). In either case, 0 is returned.</p>
         *
         * <p>This method does not affect the text in the editor in any
         * way, nor does it affect the selection or composing spans.</p>
         *
         * <p><strong>Editor authors:</strong> please be careful of race
         * conditions in implementing this call. An IME can change the
         * cursor position and use this method right away; you need to make
         * sure the returned value is consistent with the results of the
         * latest edits and changes to the cursor position.</p>
         *
         * @param reqModes The desired modes to retrieve, as defined by
         * {@link android.text.TextUtils#getCapsMode TextUtils.getCapsMode}. These
         * constants are defined so that you can simply pass the current
         * {@link EditorInfo#inputType TextBoxAttribute.contentType} value
         * directly in to here.
         * @return the caps mode flags that are in effect at the current
         * cursor position. See TYPE_TEXT_FLAG_CAPS_* in {@link android.text.InputType}.
         */
        public int getCursorCapsMode(int reqModes) {
            if (mIc != null) {
                return mIc.getCursorCapsMode(reqModes);
            }
            return 0;
        }

        /**
         * Clear the given meta key pressed states in the given input
         * connection.
         *
         * <p>This can be used by the accessibility service to clear the meta key states set
         * by a hardware keyboard with latched meta keys, if the editor
         * keeps track of these.</p>
         *
         * @param states The states to be cleared, may be one or more bits as
         * per {@link KeyEvent#getMetaState() KeyEvent.getMetaState()}.
         */
        public void clearMetaKeyStates(int states) {
            if (mIc != null) {
                mIc.clearMetaKeyStates(states);
            }
        }
    }

    /**
     * Concrete implementation of InputMethodSession that provides all of the standard behavior
     * for an input method session.
     */
    private final class InputMethodSessionForAccessibility implements InputMethodSession {
        boolean mEnabled = true;

        public void setEnabled(boolean enabled) {
            mEnabled = enabled;
        }

        @Override
        public void finishInput() {
            if (mEnabled) {
                doFinishInput();
            }
        }

        @Override
        public void updateSelection(int oldSelStart, int oldSelEnd, int newSelStart,
                int newSelEnd, int candidatesStart, int candidatesEnd) {
            if (mEnabled) {
                InputMethod.this.onUpdateSelection(oldSelEnd, oldSelEnd, newSelStart,
                        newSelEnd, candidatesStart, candidatesEnd);
            }
        }

        @Override
        public void viewClicked(boolean focusChanged) {
        }

        @Override
        public void updateCursor(@NonNull Rect newCursor) {
        }

        @Override
        public void displayCompletions(
                @SuppressLint("ArrayReturn") @NonNull CompletionInfo[] completions) {
        }

        @Override
        public void updateExtractedText(int token, @NonNull ExtractedText text) {
        }

        public void dispatchKeyEvent(int seq, @NonNull KeyEvent event,
                @NonNull @CallbackExecutor Executor executor, @NonNull EventCallback callback) {
        }

        @Override
        public void dispatchKeyEvent(int seq, @NonNull KeyEvent event,
                @NonNull EventCallback callback) {
        }

        public void dispatchTrackballEvent(int seq, @NonNull MotionEvent event,
                @NonNull @CallbackExecutor Executor executor, @NonNull EventCallback callback) {
        }

        @Override
        public void dispatchTrackballEvent(int seq, @NonNull MotionEvent event,
                @NonNull EventCallback callback) {
        }

        public void dispatchGenericMotionEvent(int seq, @NonNull MotionEvent event,
                @NonNull @CallbackExecutor Executor executor, @NonNull EventCallback callback) {
        }

        @Override
        public void dispatchGenericMotionEvent(int seq, @NonNull MotionEvent event,
                @NonNull EventCallback callback) {
        }

        @Override
        public void appPrivateCommand(@NonNull String action, @NonNull Bundle data) {
        }

        @Override
        public void toggleSoftInput(int showFlags, int hideFlags) {
        }

        @Override
        public void updateCursorAnchorInfo(@NonNull CursorAnchorInfo cursorAnchorInfo) {
        }

        @Override
        public void notifyImeHidden() {
        }

        @Override
        public void removeImeSurface() {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void invalidateInputInternal(EditorInfo editorInfo, IInputContext inputContext,
                int sessionId) {
            if (mStartedInputConnection instanceof RemoteInputConnection) {
                final RemoteInputConnection ric =
                        (RemoteInputConnection) mStartedInputConnection;
                if (!ric.isSameConnection(inputContext)) {
                    // This is not an error, and can be safely ignored.
                    return;
                }
                editorInfo.makeCompatible(
                        mService.getApplicationInfo().targetSdkVersion);
                restartInput(new RemoteInputConnection(ric, sessionId), editorInfo);
            }
        }
    }
}
