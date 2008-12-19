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
import android.text.Spanned;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

/**
 * The InputConnection interface is the communication channel from an
 * {@link InputMethod} back to the application that is receiving its input. It
 * is used to perform such things as reading text around the cursor,
 * committing text to the text box, and sending raw key events to the application.
 */
public interface InputConnection {
    /**
     * Get <var>n</var> characters of text before the current cursor position.
     * 
     * <p>This method may fail either if the input connection has become invalid
     * (such as its process crashing) or the client is taking too long to
     * respond with the text (it is given a couple seconds to return).
     * In either case, a null is returned.
     * 
     * @param n The expected length of the text.
     * 
     * @return Returns the text before the cursor position; the length of the
     * returned text might be less than <var>n</var>.
     */
    public CharSequence getTextBeforeCursor(int n);

    /**
     * Get <var>n</var> characters of text after the current cursor position.
     * 
     * <p>This method may fail either if the input connection has become invalid
     * (such as its process crashing) or the client is taking too long to
     * respond with the text (it is given a couple seconds to return).
     * In either case, a null is returned.
     * 
     * @param n The expected length of the text.
     * 
     * @return Returns the text after the cursor position; the length of the
     * returned text might be less than <var>n</var>.
     */
    public CharSequence getTextAfterCursor(int n);

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
    
    public static final int EXTRACTED_TEXT_MONITOR = 0x0001;
    
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
     * {@link #EXTRACTED_TEXT_MONITOR}.
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
    boolean deleteSurroundingText(int leftLength, int rightLength);

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
     * @param newCursorPosition The new cursor position within the
     *        <var>text</var>.
     * 
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     */
    public boolean setComposingText(CharSequence text, int newCursorPosition);

    /**
     * Commit text to the text box and set the new cursor position.
     * Any composing text set previously will be removed
     * automatically.
     * 
     * @param text The committed text.
     * @param newCursorPosition The new cursor position within the
     *        <var>text</var>.
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
    
    /**
     * Show an icon in the status bar.
     * 
     * @param packageName The package holding the icon resource to be shown.
     * @param resId The resource id of the icon to show.
     *        
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     */
    public boolean showStatusIcon(String packageName, int resId);
    
    /**
     * Hide the icon shown in the status bar.
     *        
     * @return Returns true on success, false if the input connection is no longer
     * valid.
     */
    public boolean hideStatusIcon();
}
