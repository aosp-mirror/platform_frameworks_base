/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.view.inputmethod;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The InputConnection interface is the communication channel from an
 * {@link InputMethod} back to the application that is receiving its
 * input. It is used to perform such things as reading text around the
 * cursor, committing text to the text box, and sending raw key events
 * to the application.
 *
 * <p>Starting from API Level {@link android.os.Build.VERSION_CODES#N},
 * the system can deal with the situation where the application directly
 * implements this class but one or more of the following methods are
 * not implemented.</p>
 * <ul>
 *     <li>{@link #getSelectedText(int)}, which was introduced in
 *     {@link android.os.Build.VERSION_CODES#GINGERBREAD}.</li>
 *     <li>{@link #setComposingRegion(int, int)}, which was introduced
 *     in {@link android.os.Build.VERSION_CODES#GINGERBREAD}.</li>
 *     <li>{@link #commitCorrection(CorrectionInfo)}, which was introduced
 *     in {@link android.os.Build.VERSION_CODES#HONEYCOMB}.</li>
 *     <li>{@link #requestCursorUpdates(int)}, which was introduced in
 *     {@link android.os.Build.VERSION_CODES#LOLLIPOP}.</li>
 *     <li>{@link #deleteSurroundingTextInCodePoints(int, int)}, which
 *     was introduced in {@link android.os.Build.VERSION_CODES#N}.</li>
 *     <li>{@link #getHandler()}, which was introduced in
 *     {@link android.os.Build.VERSION_CODES#N}.</li>
 *     <li>{@link #closeConnection()}, which was introduced in
 *     {@link android.os.Build.VERSION_CODES#N}.</li>
 *     <li>{@link #commitContent(InputContentInfo, int, Bundle)}, which was
 *     introduced in {@link android.os.Build.VERSION_CODES#N_MR1}.</li>
 * </ul>
 *
 * <h3>Implementing an IME or an editor</h3>
 * <p>Text input is the result of the synergy of two essential components:
 * an Input Method Engine (IME) and an editor. The IME can be a
 * software keyboard, a handwriting interface, an emoji palette, a
 * speech-to-text engine, and so on. There are typically several IMEs
 * installed on any given Android device. In Android, IMEs extend
 * {@link android.inputmethodservice.InputMethodService}.
 * For more information about how to create an IME, see the
 * <a href="{@docRoot}guide/topics/text/creating-input-method.html">
 * Creating an input method</a> guide.
 *
 * The editor is the component that receives text and displays it.
 * Typically, this is an {@link android.widget.EditText} instance, but
 * some applications may choose to implement their own editor for
 * various reasons. This is a large and complicated task, and an
 * application that does this needs to make sure the behavior is
 * consistent with standard EditText behavior in Android. An editor
 * needs to interact with the IME, receiving commands through
 * this InputConnection interface, and sending commands through
 * {@link android.view.inputmethod.InputMethodManager}. An editor
 * should start by implementing
 * {@link android.view.View#onCreateInputConnection(EditorInfo)}
 * to return its own input connection.</p>
 *
 * <p>If you are implementing your own IME, you will need to call the
 * methods in this interface to interact with the application. Be sure
 * to test your IME with a wide range of applications, including
 * browsers and rich text editors, as some may have peculiarities you
 * need to deal with. Remember your IME may not be the only source of
 * changes on the text, and try to be as conservative as possible in
 * the data you send and as liberal as possible in the data you
 * receive.</p>
 *
 * <p>If you are implementing your own editor, you will probably need
 * to provide your own subclass of {@link BaseInputConnection} to
 * answer to the commands from IMEs. Please be sure to test your
 * editor with as many IMEs as you can as their behavior can vary a
 * lot. Also be sure to test with various languages, including CJK
 * languages and right-to-left languages like Arabic, as these may
 * have different input requirements. When in doubt about the
 * behavior you should adopt for a particular call, please mimic the
 * default TextView implementation in the latest Android version, and
 * if you decide to drift from it, please consider carefully that
 * inconsistencies in text editor behavior is almost universally felt
 * as a bad thing by users.</p>
 *
 * <h3>Cursors, selections and compositions</h3>
 * <p>In Android, the cursor and the selection are one and the same
 * thing. A "cursor" is just the special case of a zero-sized
 * selection. As such, this documentation uses them
 * interchangeably. Any method acting "before the cursor" would act
 * before the start of the selection if there is one, and any method
 * acting "after the cursor" would act after the end of the
 * selection.</p>
 *
 * <p>An editor needs to be able to keep track of a currently
 * "composing" region, like the standard edition widgets do. The
 * composition is marked in a specific style: see
 * {@link android.text.Spanned#SPAN_COMPOSING}. IMEs use this to help
 * the user keep track of what part of the text they are currently
 * focusing on, and interact with the editor using
 * {@link InputConnection#setComposingText(CharSequence, int)},
 * {@link InputConnection#setComposingRegion(int, int)} and
 * {@link InputConnection#finishComposingText()}.
 * The composing region and the selection are completely independent
 * of each other, and the IME may use them however they see fit.</p>
 */
public interface InputConnection {
    /** @hide */
    @IntDef(flag = true, prefix = { "GET_TEXT_" }, value = {
            GET_TEXT_WITH_STYLES,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface GetTextType {}

    /**
     * Flag for use with {@link #getTextAfterCursor}, {@link #getTextBeforeCursor} and
     * {@link #getSurroundingText} to have style information returned along with the text. If not
     * set, {@link #getTextAfterCursor} sends only the raw text, without style or other spans. If
     * set, it may return a complex CharSequence of both text and style spans.
     * <strong>Editor authors</strong>: you should strive to send text with styles if possible, but
     * it is not required.
     */
    int GET_TEXT_WITH_STYLES = 0x0001;

    /**
     * Flag for use with {@link #getExtractedText} to indicate you
     * would like to receive updates when the extracted text changes.
     */
    int GET_EXTRACTED_TEXT_MONITOR = 0x0001;

    /**
     * Get <var>n</var> characters of text before the current cursor
     * position.
     *
     * <p>This method may fail either if the input connection has
     * become invalid (such as its process crashing) or the editor is
     * taking too long to respond with the text (it is given a couple
     * seconds to return). In either case, null is returned. This
     * method does not affect the text in the editor in any way, nor
     * does it affect the selection or composing spans.</p>
     *
     * <p>If {@link #GET_TEXT_WITH_STYLES} is supplied as flags, the
     * editor should return a {@link android.text.SpannableString}
     * with all the spans set on the text.</p>
     *
     * <p><strong>IME authors:</strong> please consider this will
     * trigger an IPC round-trip that will take some time. Assume this
     * method consumes a lot of time. Also, please keep in mind the
     * Editor may choose to return less characters than requested even
     * if they are available for performance reasons. If you are using
     * this to get the initial text around the cursor, you may consider
     * using {@link EditorInfo#getInitialTextBeforeCursor(int, int)},
     * {@link EditorInfo#getInitialSelectedText(int)}, and
     * {@link EditorInfo#getInitialTextAfterCursor(int, int)} to prevent IPC costs.</p>
     *
     * <p><strong>Editor authors:</strong> please be careful of race
     * conditions in implementing this call. An IME can make a change
     * to the text and use this method right away; you need to make
     * sure the returned value is consistent with the result of the
     * latest edits. Also, you may return less than n characters if performance
     * dictates so, but keep in mind IMEs are relying on this for many
     * functions: you should not, for example, limit the returned value to
     * the current line, and specifically do not return 0 characters unless
     * the cursor is really at the start of the text.</p>
     *
     * @param n The expected length of the text. This must be non-negative.
     * @param flags Supplies additional options controlling how the text is
     * returned. May be either {@code 0} or {@link #GET_TEXT_WITH_STYLES}.
     * @return the text before the cursor position; the length of the
     * returned text might be less than <var>n</var>.
     * @throws IllegalArgumentException if {@code n} is negative.
     */
    @Nullable
    CharSequence getTextBeforeCursor(@IntRange(from = 0) int n, int flags);

    /**
     * Get <var>n</var> characters of text after the current cursor
     * position.
     *
     * <p>This method may fail either if the input connection has
     * become invalid (such as its process crashing) or the client is
     * taking too long to respond with the text (it is given a couple
     * seconds to return). In either case, null is returned.
     *
     * <p>This method does not affect the text in the editor in any
     * way, nor does it affect the selection or composing spans.</p>
     *
     * <p>If {@link #GET_TEXT_WITH_STYLES} is supplied as flags, the
     * editor should return a {@link android.text.SpannableString}
     * with all the spans set on the text.</p>
     *
     * <p><strong>IME authors:</strong> please consider this will
     * trigger an IPC round-trip that will take some time. Assume this
     * method consumes a lot of time. If you are using this to get the
     * initial text around the cursor, you may consider using
     * {@link EditorInfo#getInitialTextBeforeCursor(int, int)},
     * {@link EditorInfo#getInitialSelectedText(int)}, and
     * {@link EditorInfo#getInitialTextAfterCursor(int, int)} to prevent IPC costs.</p>
     *
     * <p><strong>Editor authors:</strong> please be careful of race
     * conditions in implementing this call. An IME can make a change
     * to the text and use this method right away; you need to make
     * sure the returned value is consistent with the result of the
     * latest edits. Also, you may return less than n characters if performance
     * dictates so, but keep in mind IMEs are relying on this for many
     * functions: you should not, for example, limit the returned value to
     * the current line, and specifically do not return 0 characters unless
     * the cursor is really at the end of the text.</p>
     *
     * @param n The expected length of the text. This must be non-negative.
     * @param flags Supplies additional options controlling how the text is
     * returned. May be either {@code 0} or {@link #GET_TEXT_WITH_STYLES}.
     *
     * @return the text after the cursor position; the length of the
     * returned text might be less than <var>n</var>.
     * @throws IllegalArgumentException if {@code n} is negative.
     */
    @Nullable
    CharSequence getTextAfterCursor(@IntRange(from = 0) int n, int flags);

    /**
     * Gets the selected text, if any.
     *
     * <p>This method may fail if either the input connection has
     * become invalid (such as its process crashing) or the client is
     * taking too long to respond with the text (it is given a couple
     * of seconds to return). In either case, null is returned.</p>
     *
     * <p>This method must not cause any changes in the editor's
     * state.</p>
     *
     * <p>If {@link #GET_TEXT_WITH_STYLES} is supplied as flags, the
     * editor should return a {@link android.text.SpannableString}
     * with all the spans set on the text.</p>
     *
     * <p><strong>IME authors:</strong> please consider this will
     * trigger an IPC round-trip that will take some time. Assume this
     * method consumes a lot of time. If you are using this to get the
     * initial text around the cursor, you may consider using
     * {@link EditorInfo#getInitialTextBeforeCursor(int, int)},
     * {@link EditorInfo#getInitialSelectedText(int)}, and
     * {@link EditorInfo#getInitialTextAfterCursor(int, int)} to prevent IPC costs.</p>
     *
     * <p><strong>Editor authors:</strong> please be careful of race
     * conditions in implementing this call. An IME can make a change
     * to the text or change the selection position and use this
     * method right away; you need to make sure the returned value is
     * consistent with the results of the latest edits.</p>
     *
     * @param flags Supplies additional options controlling how the text is
     * returned. May be either {@code 0} or {@link #GET_TEXT_WITH_STYLES}.
     * @return the text that is currently selected, if any, or {@code null} if no text is selected.
     */
    CharSequence getSelectedText(int flags);

    /**
     * Gets the surrounding text around the current cursor, with <var>beforeLength</var> characters
     * of text before the cursor (start of the selection), <var>afterLength</var> characters of text
     * after the cursor (end of the selection), and all of the selected text. The range are for java
     * characters, not glyphs that can be multiple characters.
     *
     * <p>This method may fail either if the input connection has become invalid (such as its
     * process crashing), or the client is taking too long to respond with the text (it is given a
     * couple seconds to return), or the protocol is not supported. In any of these cases, null is
     * returned.
     *
     * <p>This method does not affect the text in the editor in any way, nor does it affect the
     * selection or composing spans.</p>
     *
     * <p>If {@link #GET_TEXT_WITH_STYLES} is supplied as flags, the editor should return a
     * {@link android.text.Spanned} with all the spans set on the text.</p>
     *
     * <p><strong>IME authors:</strong> please consider this will trigger an IPC round-trip that
     * will take some time. Assume this method consumes a lot of time. If you are using this to get
     * the initial surrounding text around the cursor, you may consider using
     * {@link EditorInfo#getInitialTextBeforeCursor(int, int)},
     * {@link EditorInfo#getInitialSelectedText(int)}, and
     * {@link EditorInfo#getInitialTextAfterCursor(int, int)} to prevent IPC costs.</p>
     *
     * @param beforeLength The expected length of the text before the cursor.
     * @param afterLength The expected length of the text after the cursor.
     * @param flags Supplies additional options controlling how the text is returned. May be either
     *              {@code 0} or {@link #GET_TEXT_WITH_STYLES}.
     * @return an {@link android.view.inputmethod.SurroundingText} object describing the surrounding
     * text and state of selection, or null if the input connection is no longer valid, or the
     * editor can't comply with the request for some reason, or the application does not implement
     * this method. The length of the returned text might be less than the sum of
     * <var>beforeLength</var> and <var>afterLength</var> .
     * @throws IllegalArgumentException if {@code beforeLength} or {@code afterLength} is negative.
     */
    @Nullable
    default SurroundingText getSurroundingText(
            @IntRange(from = 0) int beforeLength, @IntRange(from = 0) int afterLength,
            @GetTextType int flags) {
        Preconditions.checkArgumentNonnegative(beforeLength);
        Preconditions.checkArgumentNonnegative(afterLength);

        CharSequence textBeforeCursor = getTextBeforeCursor(beforeLength, flags);
        if (textBeforeCursor == null) {
            return null;
        }
        CharSequence textAfterCursor = getTextAfterCursor(afterLength, flags);
        if (textAfterCursor == null) {
            return null;
        }
        CharSequence selectedText = getSelectedText(flags);
        if (selectedText == null) {
            selectedText = "";
        }
        CharSequence surroundingText =
                TextUtils.concat(textBeforeCursor, selectedText, textAfterCursor);
        return new SurroundingText(surroundingText, textBeforeCursor.length(),
                textBeforeCursor.length() + selectedText.length(), -1);
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
    int getCursorCapsMode(int reqModes);

    /**
     * Retrieve the current text in the input connection's editor, and
     * monitor for any changes to it. This function returns with the
     * current text, and optionally the input connection can send
     * updates to the input method when its text changes.
     *
     * <p>This method may fail either if the input connection has
     * become invalid (such as its process crashing) or the client is
     * taking too long to respond with the text (it is given a couple
     * seconds to return). In either case, null is returned.</p>
     *
     * <p>Editor authors: as a general rule, try to comply with the
     * fields in <code>request</code> for how many chars to return,
     * but if performance or convenience dictates otherwise, please
     * feel free to do what is most appropriate for your case. Also,
     * if the
     * {@link #GET_EXTRACTED_TEXT_MONITOR} flag is set, you should be
     * calling
     * {@link InputMethodManager#updateExtractedText(View, int, ExtractedText)}
     * whenever you call
     * {@link InputMethodManager#updateSelection(View, int, int, int, int)}.</p>
     *
     * @param request Description of how the text should be returned.
     * {@link android.view.inputmethod.ExtractedTextRequest}
     * @param flags Additional options to control the client, either {@code 0} or
     * {@link #GET_EXTRACTED_TEXT_MONITOR}.

     * @return an {@link android.view.inputmethod.ExtractedText}
     * object describing the state of the text view and containing the
     * extracted text itself, or null if the input connection is no
     * longer valid of the editor can't comply with the request for
     * some reason.
     */
    ExtractedText getExtractedText(ExtractedTextRequest request, int flags);

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
     * <p><strong>IME authors:</strong> please be careful not to
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
     * @param beforeLength The number of characters before the cursor to be deleted, in code unit.
     *        If this is greater than the number of existing characters between the beginning of the
     *        text and the cursor, then this method does not fail but deletes all the characters in
     *        that range.
     * @param afterLength The number of characters after the cursor to be deleted, in code unit.
     *        If this is greater than the number of existing characters between the cursor and
     *        the end of the text, then this method does not fail but deletes all the characters in
     *        that range.
     * @return true on success, false if the input connection is no longer valid.
     */
    boolean deleteSurroundingText(int beforeLength, int afterLength);

    /**
     * A variant of {@link #deleteSurroundingText(int, int)}. Major differences are:
     *
     * <ul>
     *     <li>The lengths are supplied in code points, not in Java chars or in glyphs.</>
     *     <li>This method does nothing if there are one or more invalid surrogate pairs in the
     *     requested range.</li>
     * </ul>
     *
     * <p><strong>Editor authors:</strong> In addition to the requirement in
     * {@link #deleteSurroundingText(int, int)}, make sure to do nothing when one ore more invalid
     * surrogate pairs are found in the requested range.</p>
     *
     * @see #deleteSurroundingText(int, int)
     *
     * @param beforeLength The number of characters before the cursor to be deleted, in code points.
     *        If this is greater than the number of existing characters between the beginning of the
     *        text and the cursor, then this method does not fail but deletes all the characters in
     *        that range.
     * @param afterLength The number of characters after the cursor to be deleted, in code points.
     *        If this is greater than the number of existing characters between the cursor and
     *        the end of the text, then this method does not fail but deletes all the characters in
     *        that range.
     * @return {@code true} on success, {@code false} if the input connection is no longer valid.
     *         Before Android {@link android.os.Build.VERSION_CODES#TIRAMISU}, this API returned
     *         {@code false} when the target application does not implement this method.
     */
    boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength);

    /**
     * Replace the currently composing text with the given text, and
     * set the new cursor position. Any composing text set previously
     * will be removed automatically.
     *
     * <p>If there is any composing span currently active, all
     * characters that it comprises are removed. The passed text is
     * added in its place, and a composing span is added to this
     * text. If there is no composing span active, the passed text is
     * added at the cursor position (removing selected characters
     * first if any), and a composing span is added on the new text.
     * Finally, the cursor is moved to the location specified by
     * <code>newCursorPosition</code>.</p>
     *
     * <p>This is usually called by IMEs to add or remove or change
     * characters in the composing span. Calling this method will
     * cause the editor to call
     * {@link android.inputmethodservice.InputMethodService#onUpdateSelection(int, int, int, int,
     * int, int)} on the current IME after the batch input is over.</p>
     *
     * <p><strong>Editor authors:</strong> please keep in mind the
     * text may be very similar or completely different than what was
     * in the composing span at call time, or there may not be a
     * composing span at all. Please note that although it's not
     * typical use, the string may be empty. Treat this normally,
     * replacing the currently composing text with an empty string.
     * Also, be careful with the cursor position. IMEs rely on this
     * working exactly as described above. Since this changes the
     * contents of the editor, you need to make the changes known to
     * the input method by calling
     * {@link InputMethodManager#updateSelection(View, int, int, int, int)},
     * but be careful to wait until the batch edit is over if one is
     * in progress. Note that this method can set the cursor position
     * on either edge of the composing text or entirely outside it,
     * but the IME may also go on to move the cursor position to
     * within the composing text in a subsequent call so you should
     * make no assumption at all: the composing text and the selection
     * are entirely independent.</p>
     *
     * @param text The composing text with styles if necessary. If no style
     *        object attached to the text, the default style for composing text
     *        is used. See {@link android.text.Spanned} for how to attach style
     *        object to the text. {@link android.text.SpannableString} and
     *        {@link android.text.SpannableStringBuilder} are two
     *        implementations of the interface {@link android.text.Spanned}.
     * @param newCursorPosition The new cursor position around the text. If
     *        > 0, this is relative to the end of the text - 1; if <= 0, this
     *        is relative to the start of the text. So a value of 1 will
     *        always advance you to the position after the full text being
     *        inserted. Note that this means you can't position the cursor
     *        within the text, because the editor can make modifications to
     *        the text you are providing so it is not possible to correctly
     *        specify locations there.
     * @return true on success, false if the input connection is no longer
     * valid.
     */
    boolean setComposingText(CharSequence text, int newCursorPosition);

    /**
     * The variant of {@link #setComposingText(CharSequence, int)}. This method is
     * used to allow the IME to provide extra information while setting up composing text.
     *
     * @param text The composing text with styles if necessary. If no style
     *        object attached to the text, the default style for composing text
     *        is used. See {@link android.text.Spanned} for how to attach style
     *        object to the text. {@link android.text.SpannableString} and
     *        {@link android.text.SpannableStringBuilder} are two
     *        implementations of the interface {@link android.text.Spanned}.
     * @param newCursorPosition The new cursor position around the text. If
     *        > 0, this is relative to the end of the text - 1; if <= 0, this
     *        is relative to the start of the text. So a value of 1 will
     *        always advance you to the position after the full text being
     *        inserted. Note that this means you can't position the cursor
     *        within the text, because the editor can make modifications to
     *        the text you are providing so it is not possible to correctly
     *        specify locations there.
     * @param textAttribute The extra information about the text.
     * @return true on success, false if the input connection is no longer
     *
     */
    default boolean setComposingText(@NonNull CharSequence text, int newCursorPosition,
            @Nullable TextAttribute textAttribute) {
        return setComposingText(text, newCursorPosition);
    }

    /**
     * Mark a certain region of text as composing text. If there was a
     * composing region, the characters are left as they were and the
     * composing span removed, as if {@link #finishComposingText()}
     * has been called. The default style for composing text is used.
     *
     * <p>The passed indices are clipped to the contents bounds. If
     * the resulting region is zero-sized, no region is marked and the
     * effect is the same as that of calling {@link #finishComposingText()}.
     * The order of start and end is not important. In effect, the
     * region from start to end and the region from end to start is
     * the same. Editor authors, be ready to accept a start that is
     * greater than end.</p>
     *
     * <p>Since this does not change the contents of the text, editors should not call
     * {@link InputMethodManager#updateSelection(View, int, int, int, int)} and
     * IMEs should not receive
     * {@link android.inputmethodservice.InputMethodService#onUpdateSelection(int, int, int, int,
     * int, int)}.</p>
     *
     * <p>This has no impact on the cursor/selection position. It may
     * result in the cursor being anywhere inside or outside the
     * composing region, including cases where the selection and the
     * composing region overlap partially or entirely.</p>
     *
     * @param start the position in the text at which the composing region begins
     * @param end the position in the text at which the composing region ends
     * @return {@code true} on success, {@code false} if the input connection is no longer valid.
     *         Since Android {@link android.os.Build.VERSION_CODES#N} until
     *         {@link android.os.Build.VERSION_CODES#TIRAMISU}, this API returned {@code false} when
     *         the target application does not implement this method.
     */
    boolean setComposingRegion(int start, int end);

    /**
     * The variant of {@link InputConnection#setComposingRegion(int, int)}. This method is
     * used to allow the IME to provide extra information while setting up text.
     *
     * @param start the position in the text at which the composing region begins
     * @param end the position in the text at which the composing region ends
     * @param textAttribute The extra information about the text.
     * @return {@code true} on success, {@code false} if the input connection is no longer valid.
     *         Since Android {@link android.os.Build.VERSION_CODES#N} until
     *         {@link android.os.Build.VERSION_CODES#TIRAMISU}, this API returned {@code false} when
     *         the target application does not implement this method.
     */
    default boolean setComposingRegion(int start, int end, @Nullable TextAttribute textAttribute) {
        return setComposingRegion(start, end);
    }

    /**
     * Have the text editor finish whatever composing text is
     * currently active. This simply leaves the text as-is, removing
     * any special composing styling or other state that was around
     * it. The cursor position remains unchanged.
     *
     * <p><strong>IME authors:</strong> be aware that this call may be
     * expensive with some editors.</p>
     *
     * <p><strong>Editor authors:</strong> please note that the cursor
     * may be anywhere in the contents when this is called, including
     * in the middle of the composing span or in a completely
     * unrelated place. It must not move.</p>
     *
     * @return true on success, false if the input connection
     * is no longer valid.
     */
    boolean finishComposingText();

    /**
     * Commit text to the text box and set the new cursor position.
     *
     * <p>This method removes the contents of the currently composing
     * text and replaces it with the passed CharSequence, and then
     * moves the cursor according to {@code newCursorPosition}. If there
     * is no composing text when this method is called, the new text is
     * inserted at the cursor position, removing text inside the selection
     * if any. This behaves like calling
     * {@link #setComposingText(CharSequence, int) setComposingText(text, newCursorPosition)}
     * then {@link #finishComposingText()}.</p>
     *
     * <p>Calling this method will cause the editor to call
     * {@link android.inputmethodservice.InputMethodService#onUpdateSelection(int, int, int, int,
     * int, int)} on the current IME after the batch input is over.
     * <strong>Editor authors</strong>, for this to happen you need to
     * make the changes known to the input method by calling
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
     * @return true on success, false if the input connection is no longer
     * valid.
     */
    boolean commitText(CharSequence text, int newCursorPosition);

    /**
     * The variant of {@link InputConnection#commitText(CharSequence, int)}. This method is
     * used to allow the IME to provide extra information while setting up text.
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
     * @return true on success, false if the input connection is no longer
     */
    default boolean commitText(@NonNull CharSequence text, int newCursorPosition,
            @Nullable TextAttribute textAttribute) {
        return commitText(text, newCursorPosition);
    }

    /**
     * Commit a completion the user has selected from the possible ones
     * previously reported to {@link InputMethodSession#displayCompletions
     * InputMethodSession#displayCompletions(CompletionInfo[])} or
     * {@link InputMethodManager#displayCompletions
     * InputMethodManager#displayCompletions(View, CompletionInfo[])}.
     * This will result in the same behavior as if the user had
     * selected the completion from the actual UI. In all other
     * respects, this behaves like {@link #commitText(CharSequence, int)}.
     *
     * <p><strong>IME authors:</strong> please take care to send the
     * same object that you received through
     * {@link android.inputmethodservice.InputMethodService#onDisplayCompletions(CompletionInfo[])}.
     * </p>
     *
     * <p><strong>Editor authors:</strong> if you never call
     * {@link InputMethodSession#displayCompletions(CompletionInfo[])} or
     * {@link InputMethodManager#displayCompletions(View, CompletionInfo[])} then
     * a well-behaved IME should never call this on your input
     * connection, but be ready to deal with misbehaving IMEs without
     * crashing.</p>
     *
     * <p>Calling this method (with a valid {@link CompletionInfo} object)
     * will cause the editor to call
     * {@link android.inputmethodservice.InputMethodService#onUpdateSelection(int, int, int, int,
     * int, int)} on the current IME after the batch input is over.
     * <strong>Editor authors</strong>, for this to happen you need to
     * make the changes known to the input method by calling
     * {@link InputMethodManager#updateSelection(View, int, int, int, int)},
     * but be careful to wait until the batch edit is over if one is
     * in progress.</p>
     *
     * @param text The committed completion.
     * @return true on success, false if the input connection is no longer
     * valid.
     */
    boolean commitCompletion(CompletionInfo text);

    /**
     * Commit a correction automatically performed on the raw user's input. A
     * typical example would be to correct typos using a dictionary.
     *
     * <p>Calling this method will cause the editor to call
     * {@link android.inputmethodservice.InputMethodService#onUpdateSelection(int, int, int, int,
     * int, int)} on the current IME after the batch input is over.
     * <strong>Editor authors</strong>, for this to happen you need to
     * make the changes known to the input method by calling
     * {@link InputMethodManager#updateSelection(View, int, int, int, int)},
     * but be careful to wait until the batch edit is over if one is
     * in progress.</p>
     *
     * @param correctionInfo Detailed information about the correction.
     * @return {@code true} on success, {@code false} if the input connection is no longer valid.
     *         Since Android {@link android.os.Build.VERSION_CODES#N} until
     *         {@link android.os.Build.VERSION_CODES#TIRAMISU}, this API returned {@code false} when
     *         the target application does not implement this method.
     */
    boolean commitCorrection(CorrectionInfo correctionInfo);

    /**
     * Set the selection of the text editor. To set the cursor
     * position, start and end should have the same value.
     *
     * <p>Since this moves the cursor, calling this method will cause
     * the editor to call
     * {@link android.inputmethodservice.InputMethodService#onUpdateSelection(int, int, int, int,
     * int, int)} on the current IME after the batch input is over.
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
     * @return true on success, false if the input connection is no longer
     * valid.
     */
    boolean setSelection(int start, int end);

    /**
     * Have the editor perform an action it has said it can do.
     *
     * <p>This is typically used by IMEs when the user presses the key
     * associated with the action.</p>
     *
     * @param editorAction This must be one of the action constants for
     * {@link EditorInfo#imeOptions EditorInfo.imeOptions}, such as
     * {@link EditorInfo#IME_ACTION_GO EditorInfo.EDITOR_ACTION_GO}, or the value of
     * {@link EditorInfo#actionId EditorInfo.actionId} if a custom action is available.
     * @return true on success, false if the input connection is no longer
     * valid.
     */
    boolean performEditorAction(int editorAction);

    /**
     * Perform a context menu action on the field. The given id may be one of:
     * {@link android.R.id#selectAll},
     * {@link android.R.id#startSelectingText}, {@link android.R.id#stopSelectingText},
     * {@link android.R.id#cut}, {@link android.R.id#copy},
     * {@link android.R.id#paste}, {@link android.R.id#copyUrl},
     * or {@link android.R.id#switchInputMethod}
     */
    boolean performContextMenuAction(int id);

    /**
     * Tell the editor that you are starting a batch of editor
     * operations. The editor will try to avoid sending you updates
     * about its state until {@link #endBatchEdit} is called. Batch
     * edits nest.
     *
     * <p><strong>IME authors:</strong> use this to avoid getting
     * calls to
     * {@link android.inputmethodservice.InputMethodService#onUpdateSelection(int, int, int, int,
     * int, int)} corresponding to intermediate state. Also, use this to avoid
     * flickers that may arise from displaying intermediate state. Be
     * sure to call {@link #endBatchEdit} for each call to this, or
     * you may block updates in the editor.</p>
     *
     * <p><strong>Editor authors:</strong> while a batch edit is in
     * progress, take care not to send updates to the input method and
     * not to update the display. IMEs use this intensively to this
     * effect. Also please note that batch edits need to nest
     * correctly.</p>
     *
     * @return true if a batch edit is now in progress, false otherwise. Since
     * this method starts a batch edit, that means it will always return true
     * unless the input connection is no longer valid.
     */
    boolean beginBatchEdit();

    /**
     * Tell the editor that you are done with a batch edit previously initiated with
     * {@link #beginBatchEdit()}. This ends the latest batch only.
     *
     * <p><strong>IME authors:</strong> make sure you call this exactly once for each call to
     * {@link #beginBatchEdit()}.</p>
     *
     * <p><strong>Editor authors:</strong> please be careful about batch edit nesting. Updates still
     * to be held back until the end of the last batch edit.  In case you are delegating this API
     * call to the one obtained from
     * {@link android.widget.EditText#onCreateInputConnection(EditorInfo)}, there was an off-by-one
     * that had returned {@code true} when its nested batch edit count becomes {@code 0} as a result
     * of invoking this API.  This bug is fixed in {@link android.os.Build.VERSION_CODES#TIRAMISU}.
     * </p>
     *
     * @return For editor authors, you must return {@code true} if a batch edit is still in progress
     *         after closing the latest one (in other words, if the nesting count is still a
     *         positive number). Return {@code false} otherwise.  For IME authors, you will
     *         always receive {@code true} as long as the request was sent to the editor, and
     *         receive {@code false} only if the input connection is no longer valid.
     */
    boolean endBatchEdit();

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
     * @return true on success, false if the input connection is no longer
     * valid.
     *
     * @see KeyEvent
     * @see KeyCharacterMap#NUMERIC
     * @see KeyCharacterMap#PREDICTIVE
     * @see KeyCharacterMap#ALPHA
     */
    boolean sendKeyEvent(KeyEvent event);

    /**
     * Clear the given meta key pressed states in the given input
     * connection.
     *
     * <p>This can be used by the IME to clear the meta key states set
     * by a hardware keyboard with latched meta keys, if the editor
     * keeps track of these.</p>
     *
     * @param states The states to be cleared, may be one or more bits as
     * per {@link KeyEvent#getMetaState() KeyEvent.getMetaState()}.
     * @return true on success, false if the input connection is no longer
     * valid.
     */
    boolean clearMetaKeyStates(int states);

    /**
     * Called back when the connected IME switches between fullscreen and normal modes.
     *
     * <p><p><strong>Editor authors:</strong> There is a bug on
     * {@link android.os.Build.VERSION_CODES#O} and later devices that this method is called back
     * on the main thread even when {@link #getHandler()} is overridden.  This bug is fixed in
     * {@link android.os.Build.VERSION_CODES#TIRAMISU}.</p>
     *
     * <p><p><strong>IME authors:</strong> On {@link android.os.Build.VERSION_CODES#O} and later
     * devices, input methods are no longer allowed to directly call this method at any time.
     * To signal this event in the target application, input methods should always call
     * {@link InputMethodService#updateFullscreenMode()} instead. This approach should work on API
     * {@link android.os.Build.VERSION_CODES#N_MR1} and prior devices.</p>
     *
     * @return For editor authors, the return value will always be ignored. For IME authors, this
     *         always returns {@code true} on {@link android.os.Build.VERSION_CODES#N_MR1} and prior
     *         devices and {@code false} on {@link android.os.Build.VERSION_CODES#O} and later
     *         devices.
     * @see InputMethodManager#isFullscreenMode()
     */
    boolean reportFullscreenMode(boolean enabled);

    /**
     * Have the editor perform spell checking for the full content.
     *
     * <p>The editor can ignore this method call if it does not support spell checking.
     *
     * @return For editor authors, the return value will always be ignored. For IME authors, this
     *         method returns true if the spell check request was sent (whether or not the
     *         associated editor supports spell checking), false if the input connection is no
     *         longer valid.
     */
    default boolean performSpellCheck() {
        return false;
    }

    /**
     * API to send private commands from an input method to its
     * connected editor. This can be used to provide domain-specific
     * features that are only known between certain input methods and
     * their clients. Note that because the InputConnection protocol
     * is asynchronous, you have no way to get a result back or know
     * if the client understood the command; you can use the
     * information in {@link EditorInfo} to determine if a client
     * supports a particular command.
     *
     * @param action Name of the command to be performed. This <em>must</em>
     * be a scoped name, i.e. prefixed with a package name you own, so that
     * different developers will not create conflicting commands.
     * @param data Any data to include with the command.
     * @return true if the command was sent (whether or not the
     * associated editor understood it), false if the input connection is no longer
     * valid.
     */
    boolean performPrivateCommand(String action, Bundle data);

    /**
     * The editor is requested to call
     * {@link InputMethodManager#updateCursorAnchorInfo(android.view.View, CursorAnchorInfo)} at
     * once, as soon as possible, regardless of cursor/anchor position changes. This flag can be
     * used together with {@link #CURSOR_UPDATE_MONITOR}.
     * <p>
     * Note by default all of {@link #CURSOR_UPDATE_FILTER_EDITOR_BOUNDS},
     * {@link #CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS} and
     * {@link #CURSOR_UPDATE_FILTER_INSERTION_MARKER} are included but specifying them can
     * filter-out others.
     * It can be CPU intensive to include all, filtering specific info is recommended.
     * </p>
     */
    int CURSOR_UPDATE_IMMEDIATE = 1 << 0;

    /**
     * The editor is requested to call
     * {@link InputMethodManager#updateCursorAnchorInfo(android.view.View, CursorAnchorInfo)}
     * whenever cursor/anchor position is changed. To disable monitoring, call
     * {@link InputConnection#requestCursorUpdates(int)} again with this flag off.
     * <p>
     * This flag can be used together with {@link #CURSOR_UPDATE_IMMEDIATE}.
     * </p>
     * <p>
     * Note by default all of {@link #CURSOR_UPDATE_FILTER_EDITOR_BOUNDS},
     * {@link #CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS} and
     * {@link #CURSOR_UPDATE_FILTER_INSERTION_MARKER} are included but specifying them can
     * filter-out others.
     * It can be CPU intensive to include all, filtering specific info is recommended.
     * </p>
     */
    int CURSOR_UPDATE_MONITOR = 1 << 1;

    /**
     * The editor is requested to call
     * {@link InputMethodManager#updateCursorAnchorInfo(android.view.View, CursorAnchorInfo)}
     * with new {@link EditorBoundsInfo} whenever cursor/anchor position is changed. To disable
     * monitoring, call {@link InputConnection#requestCursorUpdates(int)} again with this flag off.
     * <p>
     * This flag can be used together with filters: {@link #CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS},
     * {@link #CURSOR_UPDATE_FILTER_INSERTION_MARKER} and update flags
     * {@link #CURSOR_UPDATE_IMMEDIATE} and {@link #CURSOR_UPDATE_MONITOR}.
     * </p>
     */
    int CURSOR_UPDATE_FILTER_EDITOR_BOUNDS = 1 << 2;

    /**
     * The editor is requested to call
     * {@link InputMethodManager#updateCursorAnchorInfo(android.view.View, CursorAnchorInfo)}
     * with new character bounds {@link CursorAnchorInfo#getCharacterBounds(int)} whenever
     * cursor/anchor position is changed. To disable
     * monitoring, call {@link InputConnection#requestCursorUpdates(int)} again with this flag off.
     * <p>
     * This flag can be combined with other filters: {@link #CURSOR_UPDATE_FILTER_EDITOR_BOUNDS},
     * {@link #CURSOR_UPDATE_FILTER_INSERTION_MARKER} and update flags
     * {@link #CURSOR_UPDATE_IMMEDIATE} and {@link #CURSOR_UPDATE_MONITOR}.
     * </p>
     */
    int CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS = 1 << 3;

    /**
     * The editor is requested to call
     * {@link InputMethodManager#updateCursorAnchorInfo(android.view.View, CursorAnchorInfo)}
     * with new Insertion marker info {@link CursorAnchorInfo#getInsertionMarkerFlags()},
     * {@link CursorAnchorInfo#getInsertionMarkerBaseline()}, etc whenever cursor/anchor position is
     * changed. To disable monitoring, call {@link InputConnection#requestCursorUpdates(int)} again
     * with this flag off.
     * <p>
     * This flag can be combined with other filters: {@link #CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS},
     * {@link #CURSOR_UPDATE_FILTER_EDITOR_BOUNDS} and update flags {@link #CURSOR_UPDATE_IMMEDIATE}
     * and {@link #CURSOR_UPDATE_MONITOR}.
     * </p>
     */
    int CURSOR_UPDATE_FILTER_INSERTION_MARKER = 1 << 4;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {CURSOR_UPDATE_IMMEDIATE, CURSOR_UPDATE_MONITOR}, flag = true,
            prefix = { "CURSOR_UPDATE_" })
    @interface CursorUpdateMode{}

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {CURSOR_UPDATE_FILTER_EDITOR_BOUNDS, CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS,
            CURSOR_UPDATE_FILTER_INSERTION_MARKER}, flag = true,
            prefix = { "CURSOR_UPDATE_FILTER_" })
    @interface CursorUpdateFilter{}

    /**
     * Called by the input method to ask the editor for calling back
     * {@link InputMethodManager#updateCursorAnchorInfo(android.view.View, CursorAnchorInfo)} to
     * notify cursor/anchor locations.
     *
     * @param cursorUpdateMode any combination of update modes and filters:
     * {@link #CURSOR_UPDATE_IMMEDIATE}, {@link #CURSOR_UPDATE_MONITOR}, and date filters:
     * {@link #CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS}, {@link #CURSOR_UPDATE_FILTER_EDITOR_BOUNDS},
     * {@link #CURSOR_UPDATE_FILTER_INSERTION_MARKER}.
     * Pass {@code 0} to disable them. However, if an unknown flag is provided, request will be
     * rejected and method will return {@code false}.
     * @return {@code true} if the request is scheduled. {@code false} to indicate that when the
     *         application will not call {@link InputMethodManager#updateCursorAnchorInfo(
     *         android.view.View, CursorAnchorInfo)}.
     *         Since Android {@link android.os.Build.VERSION_CODES#N} until
     *         {@link android.os.Build.VERSION_CODES#TIRAMISU}, this API returned {@code false} when
     *         the target application does not implement this method.
     */
    boolean requestCursorUpdates(int cursorUpdateMode);

    /**
     * Called by the input method to ask the editor for calling back
     * {@link InputMethodManager#updateCursorAnchorInfo(android.view.View, CursorAnchorInfo)} to
     * notify cursor/anchor locations.
     *
     * @param cursorUpdateMode combination of update modes:
     * {@link #CURSOR_UPDATE_IMMEDIATE}, {@link #CURSOR_UPDATE_MONITOR}
     * @param cursorUpdateFilter any combination of data filters:
     * {@link #CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS}, {@link #CURSOR_UPDATE_FILTER_EDITOR_BOUNDS},
     * {@link #CURSOR_UPDATE_FILTER_INSERTION_MARKER}.
     *
     * <p>Pass {@code 0} to disable them. However, if an unknown flag is provided, request will be
     * rejected and method will return {@code false}.</p>
     * @return {@code true} if the request is scheduled. {@code false} to indicate that when the
     *         application will not call {@link InputMethodManager#updateCursorAnchorInfo(
     *         android.view.View, CursorAnchorInfo)}.
     *         Since Android {@link android.os.Build.VERSION_CODES#N} until
     *         {@link android.os.Build.VERSION_CODES#TIRAMISU}, this API returned {@code false} when
     *         the target application does not implement this method.
     */
    default boolean requestCursorUpdates(@CursorUpdateMode int cursorUpdateMode,
            @CursorUpdateFilter int cursorUpdateFilter) {
        if (cursorUpdateFilter == 0) {
            return requestCursorUpdates(cursorUpdateMode);
        }
        return false;
    }

    /**
     * Called by the system to enable application developers to specify a dedicated thread on which
     * {@link InputConnection} methods are called back.
     *
     * <p><strong>Editor authors</strong>: although you can return your custom subclasses of
     * {@link Handler}, the system only uses {@link android.os.Looper} returned from
     * {@link Handler#getLooper()}.  You cannot intercept or cancel {@link InputConnection}
     * callbacks by implementing this method.</p>
     *
     * <p><strong>IME authors</strong>: This method is not intended to be called from the IME.  You
     * will always receive {@code null}.</p>
     *
     * @return {@code null} to use the default {@link Handler}.
     */
    @Nullable
    Handler getHandler();

    /**
     * Called by the system up to only once to notify that the system is about to invalidate
     * connection between the input method and the application.
     *
     * <p><strong>Editor authors</strong>: You can clear all the nested batch edit right now and
     * you no longer need to handle subsequent callbacks on this connection, including
     * {@link #beginBatchEdit()}}.  Note that although the system tries to call this method whenever
     * possible, there may be a chance that this method is not called in some exceptional
     * situations.</p>
     *
     * <p>Note: This does nothing when called from input methods.</p>
     */
    void closeConnection();

    /**
     * When this flag is used, the editor will be able to request read access to the content URI
     * contained in the {@link InputContentInfo} object.
     *
     * <p>Make sure that the content provider owning the Uri sets the
     * {@link android.R.styleable#AndroidManifestProvider_grantUriPermissions
     * grantUriPermissions} attribute in its manifest or included the
     * {@link android.R.styleable#AndroidManifestGrantUriPermission
     * &lt;grant-uri-permissions&gt;} tag. Otherwise {@link InputContentInfo#requestPermission()}
     * can fail.</p>
     *
     * <p>Although calling this API is allowed only for the IME that is currently selected, the
     * client is able to request a temporary read-only access even after the current IME is switched
     * to any other IME as long as the client keeps {@link InputContentInfo} object.</p>
     **/
    int INPUT_CONTENT_GRANT_READ_URI_PERMISSION =
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;  // 0x00000001

    /**
     * Called by the input method to commit content such as a PNG image to the editor.
     *
     * <p>In order to avoid a variety of compatibility issues, this focuses on a simple use case,
     * where editors and IMEs are expected to work cooperatively as follows:</p>
     * <ul>
     *     <li>Editor must keep {@link EditorInfo#contentMimeTypes} equal to {@code null} if it does
     *     not support this method at all.</li>
     *     <li>Editor can ignore this request when the MIME type specified in
     *     {@code inputContentInfo} does not match any of {@link EditorInfo#contentMimeTypes}.
     *     </li>
     *     <li>Editor can ignore the cursor position when inserting the provided content.</li>
     *     <li>Editor can return {@code true} asynchronously, even before it starts loading the
     *     content.</li>
     *     <li>Editor should provide a way to delete the content inserted by this method or to
     *     revert the effect caused by this method.</li>
     *     <li>IME should not call this method when there is any composing text, in case calling
     *     this method causes a focus change.</li>
     *     <li>IME should grant a permission for the editor to read the content. See
     *     {@link EditorInfo#packageName} about how to obtain the package name of the editor.</li>
     * </ul>
     *
     * @param inputContentInfo Content to be inserted.
     * @param flags {@link #INPUT_CONTENT_GRANT_READ_URI_PERMISSION} if the content provider
     * allows {@link android.R.styleable#AndroidManifestProvider_grantUriPermissions
     * grantUriPermissions} or {@code 0} if the application does not need to call
     * {@link InputContentInfo#requestPermission()}.
     * @param opts optional bundle data. This can be {@code null}.
     * @return {@code true} if this request is accepted by the application, whether the request
     * is already handled or still being handled in background, {@code false} otherwise.
     */
    boolean commitContent(@NonNull InputContentInfo inputContentInfo, int flags,
            @Nullable Bundle opts);

    /**
     * Called by the input method to indicate that it consumes all input for itself, or no longer
     * does so.
     *
     * <p>Editors should reflect that they are not receiving input by hiding the cursor if
     * {@code imeConsumesInput} is {@code true}, and resume showing the cursor if it is
     * {@code false}.
     *
     * @param imeConsumesInput {@code true} when the IME is consuming input and the cursor should be
     * hidden, {@code false} when input to the editor resumes and the cursor should be shown again.
     * @return For editor authors, the return value will always be ignored. For IME authors, this
     *         method returns {@code true} if the request was sent (whether or not the associated
     *         editor does something based on this request), {@code false} if the input connection
     *         is no longer valid.
     */
    default boolean setImeConsumesInput(boolean imeConsumesInput) {
        return false;
    }

    /**
     * Called by the system when it needs to take a snapshot of multiple text-related data in an
     * atomic manner.
     *
     * <p><strong>Editor authors</strong>: Supporting this method is strongly encouraged. Atomically
     * taken {@link TextSnapshot} is going to be really helpful for the system when optimizing IPCs
     * in a safe and deterministic manner.  Return {@code null} if an atomically taken
     * {@link TextSnapshot} is unavailable.  The system continues supporting such a scenario
     * gracefully.</p>
     *
     * <p><strong>IME authors</strong>: Currently IMEs cannot call this method directly and always
     * receive {@code null} as the result.</p>
     *
     * @return {@code null} if {@link TextSnapshot} is unavailable and/or this API is called from
     *         IMEs.
     */
    @Nullable
    default TextSnapshot takeSnapshot() {
        // Returning null by default because the composing text range cannot be retrieved from
        // existing APIs.
        return null;
    }
}
