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

import android.os.Bundle;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

/**
 * The InputConnection interface is the communication channel from an
 * {@link InputMethod} back to the application that is receiving its input. It
 * is used to perform such things as reading text around the cursor,
 * committing text to the text box, and sending raw key events to the application.
 * 
 * <p>Implementations of this interface should generally be done by
 * subclassing {@link BaseInputConnection}.
 */
public interface InputConnection {
    /**
     * Flag for use with {@link #getTextAfterCursor} and
     * {@link #getTextBeforeCursor} to have style information returned along
     * with the text.  If not set, you will receive only the raw text.  If
     * set, you may receive a complex CharSequence of both text and style
     * spans.
     */
    static final int GET_TEXT_WITH_STYLES = 0x0001;
    
    /**
     * Flag for use with {@link #getExtractedText} to indicate you would
     * like to receive updates when the extracted text changes.
     */
    public static final int GET_EXTRACTED_TEXT_MONITOR = 0x0001;
    
    /**
     * Get <var>n</var> characters of text before the current cursor position.
     * 
     * <p>This method may fail either if the input connection has become invalid
     * (such as its process crashing) or the client is taking too long to
     * respond with the text (it is given a couple seconds to return).
     * In either case, a null is returned.
     * 
     * @param n The expected length of the text.
     * @param flags Supplies additional options controlling how the text is
     * returned.  May be either 0 or {@link #GET_TEXT_WITH_STYLES}.
     * 
     * @return Returns the text before the cursor position; the length of the
     * returned text might be less than <var>n</var>.
     */
    public CharSequence getTextBeforeCursor(int n, int flags);

    /**
     * Get <var>n</var> characters of text after the current cursor position.
     * 
     * <p>This method may fail either if the input connection has become invalid
     * (such as its process crashing) or the client is taking too long to
     * respond with the text (it is given a couple seconds to return).
     * In either case, a null is returned.
     * 
     * @param n The expected length of the text.
     * @param flags Supplies additional options controlling how the text is
     * returned.  May be either 0 or {@link #GET_TEXT_WITH_STYLES}.
     * 
     * @return Returns the text after the cursor position; the length of the
     * returned text might be less than <var>n</var>.
     */
    public CharSequence getTextAfterCursor(int n, int flags);

    /**
     * Retrieve the current capitalization mode in effect at the current
     * cursor position in the text.  See
     * {@link android.text.TextUtils#getCapsMode TextUtils.getCapsMode} for
     * more information.
     * 
     * <p>This method may fail either if the input connection has become invalid
     * (such as its process crashing) or the client is taking too long to
     * respond with the text (it is given a couple seconds to return).
     * In either case, a 0 is returned.
     * 
     * @param reqModes The desired modes to retrieve, as defined by
     * {@link android.text.TextUtils#getCapsMode TextUtils.getCapsMode}.  These
     * constants are defined so that you can simply pass the current
     * {@link EditorInfo#inputType TextBoxAttribute.contentType} value
     * directly in to here.
     * 
     * @return Returns the caps mode flags that are in effect.
     */
    public int getCursorCapsMode(int reqModes);
    
    /**
     * Retrieve the current text in the input connection's editor, and monitor
     * for any changes to it.  This function returns with the current text,
     * and optionally the input connection can send updates to the
     * input method when its text changes.
     * 
     * <p>This method may fail either if the input connection has become invalid
     * (such as its process crashing) or the client is taking too long to
     * respond with the text (it is given a couple seconds to return).
     * In either case, a null is returned.
     * 
     * @param request Description of how the text should be returned.
     * @param flags Additional options to control the client, either 0 or
     * {@link #GET_EXTRACTED_TEXT_MONITOR}.
     * 
     * @return Returns an ExtractedText object describing the state of the
     * text view and containing the extracted text itself.
     */
    public ExtractedText getExtractedText(ExtractedTextRequest request,
            int flags);

    /**
     * Delete <var>leftLength</var> characters of text before the current cursor
     * position, and delete <var>rightLength</var> characters of text after the
     * current cursor position, excluding composing text.
     * 
     * @param leftLength The number of characters to be deleted before the
     *        current cursor position.
     * @param rightLength The number of characters to be deleted after the
     *        current cursor position.
     *        
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     */
    public boolean deleteSurroundingText(int leftLength, int rightLength);

    /**
     * Set composing text around the current cursor position with the given text,
     * and set the new cursor position.  Any composing text set previously will
     * be removed automatically.
     * 
     * @param text The composing text with styles if necessary. If no style
     *        object attached to the text, the default style for composing text
     *        is used. See {#link android.text.Spanned} for how to attach style
     *        object to the text. {#link android.text.SpannableString} and
     *        {#link android.text.SpannableStringBuilder} are two
     *        implementations of the interface {#link android.text.Spanned}.
     * @param newCursorPosition The new cursor position around the text.  If
     *        > 0, this is relative to the end of the text - 1; if <= 0, this
     *        is relative to the start of the text.  So a value of 1 will
     *        always advance you to the position after the full text being
     *        inserted.  Note that this means you can't position the cursor
     *        within the text, because the editor can make modifications to
     *        the text you are providing so it is not possible to correctly
     *        specify locations there.
     * 
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     */
    public boolean setComposingText(CharSequence text, int newCursorPosition);

    /**
     * Have the text editor finish whatever composing text is currently
     * active.  This simply leaves the text as-is, removing any special
     * composing styling or other state that was around it.  The cursor
     * position remains unchanged.
     */
    public boolean finishComposingText();
    
    /**
     * Commit text to the text box and set the new cursor position.
     * Any composing text set previously will be removed
     * automatically.
     * 
     * @param text The committed text.
     * @param newCursorPosition The new cursor position around the text.  If
     *        > 0, this is relative to the end of the text - 1; if <= 0, this
     *        is relative to the start of the text.  So a value of 1 will
     *        always advance you to the position after the full text being
     *        inserted.  Note that this means you can't position the cursor
     *        within the text, because the editor can make modifications to
     *        the text you are providing so it is not possible to correctly
     *        specify locations there.
     * 
     *        
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     */
    public boolean commitText(CharSequence text, int newCursorPosition);

    /**
     * Commit a completion the user has selected from the possible ones
     * previously reported to {@link InputMethodSession#displayCompletions
     * InputMethodSession.displayCompletions()}.  This will result in the
     * same behavior as if the user had selected the completion from the
     * actual UI.
     * 
     * @param text The committed completion.
     *        
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     */
    public boolean commitCompletion(CompletionInfo text);

    /**
     * Set the selection of the text editor.  To set the cursor position,
     * start and end should have the same value.
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     */
    public boolean setSelection(int start, int end);
    
    /**
     * Have the editor perform an action it has said it can do.
     * 
     * @param editorAction This must be one of the action constants for
     * {@link EditorInfo#imeOptions EditorInfo.editorType}, such as
     * {@link EditorInfo#IME_ACTION_GO EditorInfo.EDITOR_ACTION_GO}.
     * 
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     */
    public boolean performEditorAction(int editorAction);
    
    /**
     * Perform a context menu action on the field.  The given id may be one of:
     * {@link android.R.id#selectAll},
     * {@link android.R.id#startSelectingText}, {@link android.R.id#stopSelectingText},
     * {@link android.R.id#cut}, {@link android.R.id#copy},
     * {@link android.R.id#paste}, {@link android.R.id#copyUrl},
     * or {@link android.R.id#switchInputMethod}
     */
    public boolean performContextMenuAction(int id);
    
    /**
     * Tell the editor that you are starting a batch of editor operations.
     * The editor will try to avoid sending you updates about its state
     * until {@link #endBatchEdit} is called.
     */
    public boolean beginBatchEdit();
    
    /**
     * Tell the editor that you are done with a batch edit previously
     * initiated with {@link #endBatchEdit}.
     */
    public boolean endBatchEdit();
    
    /**
     * Send a key event to the process that is currently attached through
     * this input connection.  The event will be dispatched like a normal
     * key event, to the currently focused; this generally is the view that
     * is providing this InputConnection, but due to the asynchronous nature
     * of this protocol that can not be guaranteed and the focus may have
     * changed by the time the event is received.
     * 
     * <p>
     * This method can be used to send key events to the application. For
     * example, an on-screen keyboard may use this method to simulate a hardware
     * keyboard. There are three types of standard keyboards, numeric (12-key),
     * predictive (20-key) and ALPHA (QWERTY). You can specify the keyboard type
     * by specify the device id of the key event.
     * 
     * <p>
     * You will usually want to set the flag
     * {@link KeyEvent#FLAG_SOFT_KEYBOARD KeyEvent.FLAG_SOFT_KEYBOARD} on all
     * key event objects you give to this API; the flag will not be set
     * for you.
     * 
     * @param event The key event.
     *        
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     * 
     * @see KeyEvent
     * @see KeyCharacterMap#NUMERIC
     * @see KeyCharacterMap#PREDICTIVE
     * @see KeyCharacterMap#ALPHA
     */
    public boolean sendKeyEvent(KeyEvent event);

    /**
     * Clear the given meta key pressed states in the given input connection.
     * 
     * @param states The states to be cleared, may be one or more bits as
     * per {@link KeyEvent#getMetaState() KeyEvent.getMetaState()}.
     * 
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     */
    public boolean clearMetaKeyStates(int states);
    
    /**
     * Called by the IME to tell the client when it switches between fullscreen
     * and normal modes.  This will normally be called for you by the standard
     * implementation of {@link android.inputmethodservice.InputMethodService}.
     */
    public boolean reportFullscreenMode(boolean enabled);
    
    /**
     * API to send private commands from an input method to its connected
     * editor.  This can be used to provide domain-specific features that are
     * only known between certain input methods and their clients.  Note that
     * because the InputConnection protocol is asynchronous, you have no way
     * to get a result back or know if the client understood the command; you
     * can use the information in {@link EditorInfo} to determine if
     * a client supports a particular command.
     * 
     * @param action Name of the command to be performed.  This <em>must</em>
     * be a scoped name, i.e. prefixed with a package name you own, so that
     * different developers will not create conflicting commands.
     * @param data Any data to include with the command.
     * @return Returns true if the command was sent (whether or not the
     * associated editor understood it), false if the input connection is no longer
     * valid.
     */
    public boolean performPrivateCommand(String action, Bundle data);
}
