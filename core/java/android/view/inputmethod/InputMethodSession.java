/*
 * Copyright (C) 2007-2008 The Android Open Source Project
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

import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.android.internal.inputmethod.IRemoteInputConnection;

/**
 * The InputMethodSession interface provides the per-client functionality
 * of {@link InputMethod} that is safe to expose to applications.
 *
 * <p>Applications will not normally use this interface themselves, instead
 * relying on the standard interaction provided by
 * {@link android.widget.TextView} and {@link android.widget.EditText}.
 */
public interface InputMethodSession {

    public interface EventCallback {
        void finishedEvent(int seq, boolean handled);
    }

    /**
     * This method is called when the application would like to stop
     * receiving text input.
     */
    public void finishInput();

    /**
     * This method is called when the selection or cursor in the current
     * target input field has changed.
     *
     * @param oldSelStart The previous text offset of the cursor selection
     * start position.
     * @param oldSelEnd The previous text offset of the cursor selection
     * end position.
     * @param newSelStart The new text offset of the cursor selection
     * start position.
     * @param newSelEnd The new text offset of the cursor selection
     * end position.
     * @param candidatesStart The text offset of the current candidate
     * text start position.
     * @param candidatesEnd The text offset of the current candidate
     * text end position.
     */
    public void updateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd);

    /**
     * This method is called when the user tapped a text view.
     * IMEs can't rely on this method being called because this was not part of the original IME
     * protocol, so applications with custom text editing written before this method appeared will
     * not call to inform the IME of this interaction.
     * @param focusChanged true if the user changed the focused view by this click.
     */
    public void viewClicked(boolean focusChanged);

    /**
     * This method is called when cursor location of the target input field
     * has changed within its window.  This is not normally called, but will
     * only be reported if requested by the input method.
     *
     * @param newCursor The rectangle of the cursor currently being shown in
     * the input field's window coordinates.
     */
    public void updateCursor(Rect newCursor);

    /**
     * Called by a text editor that performs auto completion, to tell the
     * input method about the completions it has available.  This can be used
     * by the input method to display them to the user to select the text to
     * be inserted.
     *
     * @param completions Array of text completions that are available, starting with
     * the best.  If this array is null, any existing completions will be
     * removed.
     */
    public void displayCompletions(CompletionInfo[] completions);

    /**
     * Called by a text editor to report its new extracted text when its
     * contents change.  This will only be called if the input method
     * calls {@link InputConnection#getExtractedText(ExtractedTextRequest, int)
     * InputConnection.getExtractedText()} with the option to report updates.
     *
     * @param token The input method supplied token for identifying its request.
     * @param text The new extracted text.
     */
    public void updateExtractedText(int token, ExtractedText text);

    /**
     * This method is called when a key is pressed.  When done with the event,
     * the implementation must call back on <var>callback</var> with its
     * result.
     *
     * <p>
     * If the input method wants to handle this event, return true, otherwise
     * return false and the caller (i.e. the application) will handle the event.
     *
     * @param event The key event.
     *
     * @return Whether the input method wants to handle this event.
     *
     * @see android.view.KeyEvent
     */
    public void dispatchKeyEvent(int seq, KeyEvent event, EventCallback callback);

    /**
     * This method is called when there is a track ball event.
     *
     * <p>
     * If the input method wants to handle this event, return true, otherwise
     * return false and the caller (i.e. the application) will handle the event.
     *
     * @param event The motion event.
     *
     * @return Whether the input method wants to handle this event.
     *
     * @see android.view.MotionEvent
     */
    public void dispatchTrackballEvent(int seq, MotionEvent event, EventCallback callback);

    /**
     * This method is called when there is a generic motion event.
     *
     * <p>
     * If the input method wants to handle this event, return true, otherwise
     * return false and the caller (i.e. the application) will handle the event.
     *
     * @param event The motion event.
     *
     * @return Whether the input method wants to handle this event.
     *
     * @see android.view.MotionEvent
     */
    public void dispatchGenericMotionEvent(int seq, MotionEvent event, EventCallback callback);

    /**
     * Process a private command sent from the application to the input method.
     * This can be used to provide domain-specific features that are
     * only known between certain input methods and their clients.
     *
     * @param action Name of the command to be performed.  This <em>must</em>
     * be a scoped name, i.e. prefixed with a package name you own, so that
     * different developers will not create conflicting commands.
     * @param data Any data to include with the command.
     */
    public void appPrivateCommand(String action, Bundle data);

    /**
     * Toggle the soft input window.
     * Applications can toggle the state of the soft input window.
     *
     * @deprecated Starting in {@link android.os.Build.VERSION_CODES#S} the system no longer invokes
     * this method, instead it explicitly shows or hides the IME. An {@code InputMethodService}
     * wishing to toggle its own visibility should instead invoke {@link
     * InputMethodService#requestShowSelf} or {@link InputMethodService#requestHideSelf}
     */
    @Deprecated
    public void toggleSoftInput(@InputMethodManager.ShowFlags int showFlags,
            @InputMethodManager.HideFlags int hideFlags);

    /**
     * This method is called when the cursor and/or the character position relevant to text input
     * is changed on the screen.  This is not called by default.  It will only be reported if
     * requested by the input method.
     *
     * @param cursorAnchorInfo Positional information relevant to text input, such as text
     * insertion point and composition string.
     */
    public void updateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo);

    /**
     * Notify IME directly to remove surface as it is no longer visible.
     * @hide
     */
    public void removeImeSurface();

    /**
     * Called when {@code inputContext} is about to be reset with {@code sessionId}.
     *
     * <p>The actual implementation should ignore if {@code inputContext} is no longer the current
     * {@link InputConnection} due to a stale callback.</p>
     *
     * @param editorInfo {@link EditorInfo} to be used
     * @param inputConnection specifies which {@link InputConnection} is being updated.
     * @param sessionId the ID to be specified to
     *                       {@link com.android.internal.inputmethod.InputConnectionCommandHeader}.
     * @hide
     */
    default void invalidateInputInternal(EditorInfo editorInfo,
            IRemoteInputConnection inputConnection, int sessionId) {
    }
}
