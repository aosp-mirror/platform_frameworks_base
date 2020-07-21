/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Printer;
import android.view.View;
import android.view.autofill.AutofillId;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * An EditorInfo describes several attributes of a text editing object
 * that an input method is communicating with (typically an EditText), most
 * importantly the type of text content it contains and the current cursor position.
 */
public class EditorInfo implements InputType, Parcelable {
    /**
     * Masks for {@link inputType}
     *
     * <pre>
     * |-------|-------|-------|-------|
     *                              1111 TYPE_MASK_CLASS
     *                      11111111     TYPE_MASK_VARIATION
     *          111111111111             TYPE_MASK_FLAGS
     * |-------|-------|-------|-------|
     *                                   TYPE_NULL
     * |-------|-------|-------|-------|
     *                                 1 TYPE_CLASS_TEXT
     *                             1     TYPE_TEXT_VARIATION_URI
     *                            1      TYPE_TEXT_VARIATION_EMAIL_ADDRESS
     *                            11     TYPE_TEXT_VARIATION_EMAIL_SUBJECT
     *                           1       TYPE_TEXT_VARIATION_SHORT_MESSAGE
     *                           1 1     TYPE_TEXT_VARIATION_LONG_MESSAGE
     *                           11      TYPE_TEXT_VARIATION_PERSON_NAME
     *                           111     TYPE_TEXT_VARIATION_POSTAL_ADDRESS
     *                          1        TYPE_TEXT_VARIATION_PASSWORD
     *                          1  1     TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
     *                          1 1      TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
     *                          1 11     TYPE_TEXT_VARIATION_FILTER
     *                          11       TYPE_TEXT_VARIATION_PHONETIC
     *                          11 1     TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
     *                          111      TYPE_TEXT_VARIATION_WEB_PASSWORD
     *                     1             TYPE_TEXT_FLAG_CAP_CHARACTERS
     *                    1              TYPE_TEXT_FLAG_CAP_WORDS
     *                   1               TYPE_TEXT_FLAG_CAP_SENTENCES
     *                  1                TYPE_TEXT_FLAG_AUTO_CORRECT
     *                 1                 TYPE_TEXT_FLAG_AUTO_COMPLETE
     *                1                  TYPE_TEXT_FLAG_MULTI_LINE
     *               1                   TYPE_TEXT_FLAG_IME_MULTI_LINE
     *              1                    TYPE_TEXT_FLAG_NO_SUGGESTIONS
     * |-------|-------|-------|-------|
     *                                1  TYPE_CLASS_NUMBER
     *                             1     TYPE_NUMBER_VARIATION_PASSWORD
     *                     1             TYPE_NUMBER_FLAG_SIGNED
     *                    1              TYPE_NUMBER_FLAG_DECIMAL
     * |-------|-------|-------|-------|
     *                                11 TYPE_CLASS_PHONE
     * |-------|-------|-------|-------|
     *                               1   TYPE_CLASS_DATETIME
     *                             1     TYPE_DATETIME_VARIATION_DATE
     *                            1      TYPE_DATETIME_VARIATION_TIME
     * |-------|-------|-------|-------|</pre>
     */

    /**
     * The content type of the text box, whose bits are defined by
     * {@link InputType}.
     *
     * @see InputType
     * @see #TYPE_MASK_CLASS
     * @see #TYPE_MASK_VARIATION
     * @see #TYPE_MASK_FLAGS
     */
    public int inputType = TYPE_NULL;

    /**
     * Set of bits in {@link #imeOptions} that provide alternative actions
     * associated with the "enter" key.  This both helps the IME provide
     * better feedback about what the enter key will do, and also allows it
     * to provide alternative mechanisms for providing that command.
     */
    public static final int IME_MASK_ACTION = 0x000000ff;

    /**
     * Bits of {@link #IME_MASK_ACTION}: no specific action has been
     * associated with this editor, let the editor come up with its own if
     * it can.
     */
    public static final int IME_ACTION_UNSPECIFIED = 0x00000000;

    /**
     * Bits of {@link #IME_MASK_ACTION}: there is no available action.
     */
    public static final int IME_ACTION_NONE = 0x00000001;

    /**
     * Bits of {@link #IME_MASK_ACTION}: the action key performs a "go"
     * operation to take the user to the target of the text they typed.
     * Typically used, for example, when entering a URL.
     */
    public static final int IME_ACTION_GO = 0x00000002;

    /**
     * Bits of {@link #IME_MASK_ACTION}: the action key performs a "search"
     * operation, taking the user to the results of searching for the text
     * they have typed (in whatever context is appropriate).
     */
    public static final int IME_ACTION_SEARCH = 0x00000003;

    /**
     * Bits of {@link #IME_MASK_ACTION}: the action key performs a "send"
     * operation, delivering the text to its target.  This is typically used
     * when composing a message in IM or SMS where sending is immediate.
     */
    public static final int IME_ACTION_SEND = 0x00000004;

    /**
     * Bits of {@link #IME_MASK_ACTION}: the action key performs a "next"
     * operation, taking the user to the next field that will accept text.
     */
    public static final int IME_ACTION_NEXT = 0x00000005;

    /**
     * Bits of {@link #IME_MASK_ACTION}: the action key performs a "done"
     * operation, typically meaning there is nothing more to input and the
     * IME will be closed.
     */
    public static final int IME_ACTION_DONE = 0x00000006;

    /**
     * Bits of {@link #IME_MASK_ACTION}: like {@link #IME_ACTION_NEXT}, but
     * for moving to the previous field.  This will normally not be used to
     * specify an action (since it precludes {@link #IME_ACTION_NEXT}), but
     * can be returned to the app if it sets {@link #IME_FLAG_NAVIGATE_PREVIOUS}.
     */
    public static final int IME_ACTION_PREVIOUS = 0x00000007;

    /**
     * Flag of {@link #imeOptions}: used to request that the IME should not update any personalized
     * data such as typing history and personalized language model based on what the user typed on
     * this text editing object.  Typical use cases are:
     * <ul>
     *     <li>When the application is in a special mode, where user's activities are expected to be
     *     not recorded in the application's history.  Some web browsers and chat applications may
     *     have this kind of modes.</li>
     *     <li>When storing typing history does not make much sense.  Specifying this flag in typing
     *     games may help to avoid typing history from being filled up with words that the user is
     *     less likely to type in their daily life.  Another example is that when the application
     *     already knows that the expected input is not a valid word (e.g. a promotion code that is
     *     not a valid word in any natural language).</li>
     * </ul>
     *
     * <p>Applications need to be aware that the flag is not a guarantee, and some IMEs may not
     * respect it.</p>
     */
    public static final int IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000;

    /**
     * Flag of {@link #imeOptions}: used to request that the IME never go
     * into fullscreen mode.
     * By default, IMEs may go into full screen mode when they think
     * it's appropriate, for example on small screens in landscape
     * orientation where displaying a software keyboard may occlude
     * such a large portion of the screen that the remaining part is
     * too small to meaningfully display the application UI.
     * If this flag is set, compliant IMEs will never go into full screen mode,
     * and always leave some space to display the application UI.
     * Applications need to be aware that the flag is not a guarantee, and
     * some IMEs may ignore it.
     */
    public static final int IME_FLAG_NO_FULLSCREEN = 0x2000000;

    /**
     * Flag of {@link #imeOptions}: like {@link #IME_FLAG_NAVIGATE_NEXT}, but
     * specifies there is something interesting that a backward navigation
     * can focus on.  If the user selects the IME's facility to backward
     * navigate, this will show up in the application as an {@link #IME_ACTION_PREVIOUS}
     * at {@link InputConnection#performEditorAction(int)
     * InputConnection.performEditorAction(int)}.
     */
    public static final int IME_FLAG_NAVIGATE_PREVIOUS = 0x4000000;

    /**
     * Flag of {@link #imeOptions}: used to specify that there is something
     * interesting that a forward navigation can focus on. This is like using
     * {@link #IME_ACTION_NEXT}, except allows the IME to be multiline (with
     * an enter key) as well as provide forward navigation.  Note that some
     * IMEs may not be able to do this, especially when running on a small
     * screen where there is little space.  In that case it does not need to
     * present a UI for this option.  Like {@link #IME_ACTION_NEXT}, if the
     * user selects the IME's facility to forward navigate, this will show up
     * in the application at {@link InputConnection#performEditorAction(int)
     * InputConnection.performEditorAction(int)}.
     */
    public static final int IME_FLAG_NAVIGATE_NEXT = 0x8000000;

    /**
     * Flag of {@link #imeOptions}: used to specify that the IME does not need
     * to show its extracted text UI.  For input methods that may be fullscreen,
     * often when in landscape mode, this allows them to be smaller and let part
     * of the application be shown behind, through transparent UI parts in the
     * fullscreen IME. The part of the UI visible to the user may not be responsive
     * to touch because the IME will receive touch events, which may confuse the
     * user; use {@link #IME_FLAG_NO_FULLSCREEN} instead for a better experience.
     * Using this flag is discouraged and it may become deprecated in the future.
     * Its meaning is unclear in some situations and it may not work appropriately
     * on older versions of the platform.
     */
    public static final int IME_FLAG_NO_EXTRACT_UI = 0x10000000;

    /**
     * Flag of {@link #imeOptions}: used in conjunction with one of the actions
     * masked by {@link #IME_MASK_ACTION}, this indicates that the action
     * should not be available as an accessory button on the right of the extracted
     * text when the input method is full-screen. Note that by setting this flag,
     * there can be cases where the action is simply never available to the
     * user. Setting this generally means that you think that in fullscreen mode,
     * where there is little space to show the text, it's not worth taking some
     * screen real estate to display the action and it should be used instead
     * to show more text.
     */
    public static final int IME_FLAG_NO_ACCESSORY_ACTION = 0x20000000;

    /**
     * Flag of {@link #imeOptions}: used in conjunction with one of the actions
     * masked by {@link #IME_MASK_ACTION}. If this flag is not set, IMEs will
     * normally replace the "enter" key with the action supplied. This flag
     * indicates that the action should not be available in-line as a replacement
     * for the "enter" key. Typically this is because the action has such a
     * significant impact or is not recoverable enough that accidentally hitting
     * it should be avoided, such as sending a message. Note that
     * {@link android.widget.TextView} will automatically set this flag for you
     * on multi-line text views.
     */
    public static final int IME_FLAG_NO_ENTER_ACTION = 0x40000000;

    /**
     * Flag of {@link #imeOptions}: used to request an IME that is capable of
     * inputting ASCII characters.  The intention of this flag is to ensure that
     * the user can type Roman alphabet characters in a {@link android.widget.TextView}.
     * It is typically used for an account ID or password input. A lot of the time,
     * IMEs are already able to input ASCII even without being told so (such IMEs
     * already respect this flag in a sense), but there are cases when this is not
     * the default. For instance, users of languages using a different script like
     * Arabic, Greek, Hebrew or Russian typically have a keyboard that can't
     * input ASCII characters by default. Applications need to be
     * aware that the flag is not a guarantee, and some IMEs may not respect it.
     * However, it is strongly recommended for IME authors to respect this flag
     * especially when their IME could end up with a state where only languages
     * using non-ASCII are enabled.
     */
    public static final int IME_FLAG_FORCE_ASCII = 0x80000000;

    /**
     * Generic unspecified type for {@link #imeOptions}.
     */
    public static final int IME_NULL = 0x00000000;

    /**
     * Masks for {@link imeOptions}
     *
     * <pre>
     * |-------|-------|-------|-------|
     *                              1111 IME_MASK_ACTION
     * |-------|-------|-------|-------|
     *                                   IME_ACTION_UNSPECIFIED
     *                                 1 IME_ACTION_NONE
     *                                1  IME_ACTION_GO
     *                                11 IME_ACTION_SEARCH
     *                               1   IME_ACTION_SEND
     *                               1 1 IME_ACTION_NEXT
     *                               11  IME_ACTION_DONE
     *                               111 IME_ACTION_PREVIOUS
     *         1                         IME_FLAG_NO_PERSONALIZED_LEARNING
     *        1                          IME_FLAG_NO_FULLSCREEN
     *       1                           IME_FLAG_NAVIGATE_PREVIOUS
     *      1                            IME_FLAG_NAVIGATE_NEXT
     *     1                             IME_FLAG_NO_EXTRACT_UI
     *    1                              IME_FLAG_NO_ACCESSORY_ACTION
     *   1                               IME_FLAG_NO_ENTER_ACTION
     *  1                                IME_FLAG_FORCE_ASCII
     * |-------|-------|-------|-------|</pre>
     */

    /**
     * Extended type information for the editor, to help the IME better
     * integrate with it.
     */
    public int imeOptions = IME_NULL;

    /**
     * A string supplying additional information options that are
     * private to a particular IME implementation.  The string must be
     * scoped to a package owned by the implementation, to ensure there are
     * no conflicts between implementations, but other than that you can put
     * whatever you want in it to communicate with the IME.  For example,
     * you could have a string that supplies an argument like
     * <code>"com.example.myapp.SpecialMode=3"</code>.  This field is can be
     * filled in from the {@link android.R.attr#privateImeOptions}
     * attribute of a TextView.
     */
    public String privateImeOptions = null;

    /**
     * In some cases an IME may be able to display an arbitrary label for
     * a command the user can perform, which you can specify here. This is
     * typically used as the label for the action to use in-line as a replacement
     * for the "enter" key (see {@link #actionId}). Remember the key where
     * this will be displayed is typically very small, and there are significant
     * localization challenges to make this fit in all supported languages. Also
     * you can not count absolutely on this being used, as some IMEs may
     * ignore this.
     */
    public CharSequence actionLabel = null;

    /**
     * If {@link #actionLabel} has been given, this is the id for that command
     * when the user presses its button that is delivered back with
     * {@link InputConnection#performEditorAction(int)
     * InputConnection.performEditorAction()}.
     */
    public int actionId = 0;

    /**
     * The text offset of the start of the selection at the time editing
     * begins; -1 if not known. Keep in mind that, without knowing the cursor
     * position, many IMEs will not be able to offer their full feature set and
     * may even behave in unpredictable ways: pass the actual cursor position
     * here if possible at all.
     *
     * <p>Also, this needs to be the cursor position <strong>right now</strong>,
     * not at some point in the past, even if input is starting in the same text field
     * as before. When the app is filling this object, input is about to start by
     * definition, and this value will override any value the app may have passed to
     * {@link InputMethodManager#updateSelection(android.view.View, int, int, int, int)}
     * before.</p>
     */
    public int initialSelStart = -1;

    /**
     * <p>The text offset of the end of the selection at the time editing
     * begins; -1 if not known. Keep in mind that, without knowing the cursor
     * position, many IMEs will not be able to offer their full feature set and
     * may behave in unpredictable ways: pass the actual cursor position
     * here if possible at all.</p>
     *
     * <p>Also, this needs to be the cursor position <strong>right now</strong>,
     * not at some point in the past, even if input is starting in the same text field
     * as before. When the app is filling this object, input is about to start by
     * definition, and this value will override any value the app may have passed to
     * {@link InputMethodManager#updateSelection(android.view.View, int, int, int, int)}
     * before.</p>
     */
    public int initialSelEnd = -1;

    /**
     * The capitalization mode of the first character being edited in the
     * text.  Values may be any combination of
     * {@link TextUtils#CAP_MODE_CHARACTERS TextUtils.CAP_MODE_CHARACTERS},
     * {@link TextUtils#CAP_MODE_WORDS TextUtils.CAP_MODE_WORDS}, and
     * {@link TextUtils#CAP_MODE_SENTENCES TextUtils.CAP_MODE_SENTENCES}, though
     * you should generally just take a non-zero value to mean "start out in
     * caps mode".
     */
    public int initialCapsMode = 0;

    /**
     * The "hint" text of the text view, typically shown in-line when the
     * text is empty to tell the user what to enter.
     */
    public CharSequence hintText;

    /**
     * A label to show to the user describing the text they are writing.
     */
    public CharSequence label;

    /**
     * Name of the package that owns this editor.
     *
     * <p><strong>IME authors:</strong> In API level 22
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1} and prior, do not trust this package
     * name. The system had not verified the consistency between the package name here and
     * application's uid. Consider to use {@link InputBinding#getUid()}, which is trustworthy.
     * Starting from {@link android.os.Build.VERSION_CODES#M}, the system verifies the consistency
     * between this package name and application uid before {@link EditorInfo} is passed to the
     * input method.</p>
     *
     * <p><strong>Editor authors:</strong> Starting from {@link android.os.Build.VERSION_CODES#M},
     * the application is no longer
     * able to establish input connections if the package name provided here is inconsistent with
     * application's uid.</p>
     */
    public String packageName;

    /**
     * Autofill Id for the field that's currently on focus.
     *
     * <p> Marked as hide since it's only used by framework.</p>
     * @hide
     */
    public AutofillId autofillId;

    /**
     * Identifier for the editor's field.  This is optional, and may be
     * 0.  By default it is filled in with the result of
     * {@link android.view.View#getId() View.getId()} on the View that
     * is being edited.
     */
    public int fieldId;

    /**
     * Additional name for the editor's field.  This can supply additional
     * name information for the field.  By default it is null.  The actual
     * contents have no meaning.
     */
    public String fieldName;

    /**
     * Any extra data to supply to the input method.  This is for extended
     * communication with specific input methods; the name fields in the
     * bundle should be scoped (such as "com.mydomain.im.SOME_FIELD") so
     * that they don't conflict with others.  This field can be
     * filled in from the {@link android.R.attr#editorExtras}
     * attribute of a TextView.
     */
    public Bundle extras;

    /**
     * List of the languages that the user is supposed to switch to no matter what input method
     * subtype is currently used.  This special "hint" can be used mainly for, but not limited to,
     * multilingual users who want IMEs to switch language context automatically.
     *
     * <p>{@code null} means that no special language "hint" is needed.</p>
     *
     * <p><strong>Editor authors:</strong> Specify this only when you are confident that the user
     * will switch to certain languages in this context no matter what input method subtype is
     * currently selected.  Otherwise, keep this {@code null}.  Explicit user actions and/or
     * preferences would be good signals to specify this special "hint",  For example, a chat
     * application may be able to put the last used language at the top of {@link #hintLocales}
     * based on whom the user is going to talk, by remembering what language is used in the last
     * conversation.  Do not specify {@link android.widget.TextView#getTextLocales()} only because
     * it is used for text rendering.</p>
     *
     * @see android.widget.TextView#setImeHintLocales(LocaleList)
     * @see android.widget.TextView#getImeHintLocales()
     */
    @Nullable
    public LocaleList hintLocales = null;


    /**
     * List of acceptable MIME types for
     * {@link InputConnection#commitContent(InputContentInfo, int, Bundle)}.
     *
     * <p>{@code null} or an empty array means that
     * {@link InputConnection#commitContent(InputContentInfo, int, Bundle)} is not supported in this
     * editor.</p>
     */
    @Nullable
    public String[] contentMimeTypes = null;

    /**
     * If not {@code null}, this editor needs to talk to IMEs that run for the specified user, no
     * matter what user ID the calling process has.
     *
     * <p>Note: This field will be silently ignored when
     * {@link com.android.server.inputmethod.InputMethodSystemProperty#MULTI_CLIENT_IME_ENABLED} is
     * {@code true}.</p>
     *
     * <p>Note also that pseudo handles such as {@link UserHandle#ALL} are not supported.</p>
     *
     * @hide
     */
    @RequiresPermission(INTERACT_ACROSS_USERS_FULL)
    @Nullable
    public UserHandle targetInputMethodUser = null;

    @IntDef({TrimPolicy.HEAD, TrimPolicy.TAIL})
    @Retention(RetentionPolicy.SOURCE)
    @interface TrimPolicy {
        int HEAD = 0;
        int TAIL = 1;
    }

    /**
     * The maximum length of initialSurroundingText. When the input text from
     * {@code setInitialSurroundingText(CharSequence)} is longer than this, trimming shall be
     * performed to keep memory efficiency.
     */
    @VisibleForTesting
    static final int MEMORY_EFFICIENT_TEXT_LENGTH = 2048;
    /**
     * When the input text is longer than {@code #MEMORY_EFFICIENT_TEXT_LENGTH}, we start trimming
     * the input text into three parts: BeforeCursor, Selection, and AfterCursor. We don't want to
     * trim the Selection but we also don't want it consumes all available space. Therefore, the
     * maximum acceptable Selection length is half of {@code #MEMORY_EFFICIENT_TEXT_LENGTH}.
     */
    @VisibleForTesting
    static final int MAX_INITIAL_SELECTION_LENGTH =  MEMORY_EFFICIENT_TEXT_LENGTH / 2;

    @NonNull
    private InitialSurroundingText mInitialSurroundingText = new InitialSurroundingText();

    /**
     * Editors may use this method to provide initial input text to IMEs. As the surrounding text
     * could be used to provide various input assistance, we recommend editors to provide the
     * complete initial input text in its {@link View#onCreateInputConnection(EditorInfo)} callback.
     * The supplied text will then be processed to serve {@code #getInitialTextBeforeCursor},
     * {@code #getInitialSelectedText}, and {@code #getInitialTextBeforeCursor}. System is allowed
     * to trim {@code sourceText} for various reasons while keeping the most valuable data to IMEs.
     *
     * <p><strong>Editor authors: </strong>Providing the initial input text helps reducing IPC calls
     * for IMEs to provide many modern features right after the connection setup. We recommend
     * calling this method in your implementation.
     *
     * @param sourceText The complete input text.
     */
    public void setInitialSurroundingText(@NonNull CharSequence sourceText) {
        setInitialSurroundingSubText(sourceText, /* subTextStart = */ 0);
    }

    /**
     * Editors may use this method to provide initial input text to IMEs. As the surrounding text
     * could be used to provide various input assistance, we recommend editors to provide the
     * complete initial input text in its {@link View#onCreateInputConnection(EditorInfo)} callback.
     * When trimming the input text is needed, call this method instead of
     * {@code setInitialSurroundingText(CharSequence)} and provide the trimmed position info. Always
     * try to include the selected text within {@code subText} to give the system best flexibility
     * to choose where and how to trim {@code subText} when necessary.
     *
     * @param subText The input text. When it was trimmed, {@code subTextStart} must be provided
     *                correctly.
     * @param subTextStart  The position that the input text got trimmed. For example, when the
     *                      editor wants to trim out the first 10 chars, subTextStart should be 10.
     */
    public void setInitialSurroundingSubText(@NonNull CharSequence subText, int subTextStart) {
        CharSequence newSubText = Editable.Factory.getInstance().newEditable(subText);
        Objects.requireNonNull(newSubText);

        // Swap selection start and end if necessary.
        final int subTextSelStart = initialSelStart > initialSelEnd
                ? initialSelEnd - subTextStart : initialSelStart - subTextStart;
        final int subTextSelEnd = initialSelStart > initialSelEnd
                ? initialSelStart - subTextStart : initialSelEnd - subTextStart;

        final int subTextLength = newSubText.length();
        // Unknown or invalid selection.
        if (subTextStart < 0 || subTextSelStart < 0 || subTextSelEnd > subTextLength) {
            mInitialSurroundingText = new InitialSurroundingText();
            return;
        }

        // For privacy protection reason, we don't carry password inputs to IMEs.
        if (isPasswordInputType(inputType)) {
            mInitialSurroundingText = new InitialSurroundingText();
            return;
        }

        if (subTextLength <= MEMORY_EFFICIENT_TEXT_LENGTH) {
            mInitialSurroundingText = new InitialSurroundingText(newSubText, subTextSelStart,
                    subTextSelEnd);
            return;
        }

        trimLongSurroundingText(newSubText, subTextSelStart, subTextSelEnd);
    }

    /**
     * Trims the initial surrounding text when it is over sized. Fundamental trimming rules are:
     * - The text before the cursor is the most important information to IMEs.
     * - The text after the cursor is the second important information to IMEs.
     * - The selected text is the least important information but it shall NEVER be truncated. When
     *    it is too long, just drop it.
     *<p><pre>
     * For example, the subText can be viewed as
     *     TextBeforeCursor + Selection + TextAfterCursor
     * The result could be
     *     1. (maybeTrimmedAtHead)TextBeforeCursor + Selection + TextAfterCursor(maybeTrimmedAtTail)
     *     2. (maybeTrimmedAtHead)TextBeforeCursor + TextAfterCursor(maybeTrimmedAtTail)</pre>
     *
     * @param subText The long text that needs to be trimmed.
     * @param selStart The text offset of the start of the selection.
     * @param selEnd The text offset of the end of the selection
     */
    private void trimLongSurroundingText(CharSequence subText, int selStart, int selEnd) {
        final int sourceSelLength = selEnd - selStart;
        // When the selected text is too long, drop it.
        final int newSelLength = (sourceSelLength > MAX_INITIAL_SELECTION_LENGTH)
                ? 0 : sourceSelLength;

        // Distribute rest of length quota to TextBeforeCursor and TextAfterCursor in 4:1 ratio.
        final int subTextBeforeCursorLength = selStart;
        final int subTextAfterCursorLength = subText.length() - selEnd;
        final int maxLengthMinusSelection = MEMORY_EFFICIENT_TEXT_LENGTH - newSelLength;
        final int possibleMaxBeforeCursorLength =
                Math.min(subTextBeforeCursorLength, (int) (0.8 * maxLengthMinusSelection));
        int newAfterCursorLength = Math.min(subTextAfterCursorLength,
                maxLengthMinusSelection - possibleMaxBeforeCursorLength);
        int newBeforeCursorLength = Math.min(subTextBeforeCursorLength,
                maxLengthMinusSelection - newAfterCursorLength);

        // As trimming may happen at the head of TextBeforeCursor, calculate new starting position.
        int newBeforeCursorHead = subTextBeforeCursorLength - newBeforeCursorLength;

        // We don't want to cut surrogate pairs in the middle. Exam that at the new head and tail.
        if (isCutOnSurrogate(subText,
                selStart - newBeforeCursorLength, TrimPolicy.HEAD)) {
            newBeforeCursorHead = newBeforeCursorHead + 1;
            newBeforeCursorLength = newBeforeCursorLength - 1;
        }
        if (isCutOnSurrogate(subText,
                selEnd + newAfterCursorLength - 1, TrimPolicy.TAIL)) {
            newAfterCursorLength = newAfterCursorLength - 1;
        }

        // Now we know where to trim, compose the initialSurroundingText.
        final int newTextLength = newBeforeCursorLength + newSelLength + newAfterCursorLength;
        final CharSequence newInitialSurroundingText;
        if (newSelLength != sourceSelLength) {
            final CharSequence beforeCursor = subText.subSequence(newBeforeCursorHead,
                    newBeforeCursorHead + newBeforeCursorLength);
            final CharSequence afterCursor = subText.subSequence(selEnd,
                    selEnd + newAfterCursorLength);

            newInitialSurroundingText = TextUtils.concat(beforeCursor, afterCursor);
        } else {
            newInitialSurroundingText = subText
                    .subSequence(newBeforeCursorHead, newBeforeCursorHead + newTextLength);
        }

        // As trimming may happen at the head, adjust cursor position in the initialSurroundingText
        // obj.
        newBeforeCursorHead = 0;
        final int newSelHead = newBeforeCursorHead + newBeforeCursorLength;
        mInitialSurroundingText = new InitialSurroundingText(
                newInitialSurroundingText, newSelHead, newSelHead + newSelLength);
    }

    /**
     * Get <var>length</var> characters of text before the current cursor position. May be
     * {@code null} when the protocol is not supported.
     *
     * @param length The expected length of the text.
     * @param flags Supplies additional options controlling how the text is returned. May be
     * either 0 or {@link InputConnection#GET_TEXT_WITH_STYLES}.
     * @return the text before the cursor position; the length of the returned text might be less
     * than <var>length</var>. When there is no text before the cursor, an empty string will be
     * returned. It could also be {@code null} when the editor or system could not support this
     * protocol.
     */
    @Nullable
    public CharSequence getInitialTextBeforeCursor(int length, int flags) {
        return mInitialSurroundingText.getInitialTextBeforeCursor(length, flags);
    }

    /**
     * Gets the selected text, if any. May be {@code null} when the protocol is not supported or the
     * selected text is way too long.
     *
     * @param flags Supplies additional options controlling how the text is returned. May be
     * either 0 or {@link InputConnection#GET_TEXT_WITH_STYLES}.
     * @return the text that is currently selected, if any. It could be an empty string when there
     * is no text selected. When {@code null} is returned, the selected text might be too long or
     * this protocol is not supported.
     */
    @Nullable
    public CharSequence getInitialSelectedText(int flags) {
        // Swap selection start and end if necessary.
        final int correctedTextSelStart = initialSelStart > initialSelEnd
                ? initialSelEnd : initialSelStart;
        final int correctedTextSelEnd = initialSelStart > initialSelEnd
                ? initialSelStart : initialSelEnd;

        final int sourceSelLength = correctedTextSelEnd - correctedTextSelStart;
        if (initialSelStart < 0 || initialSelEnd < 0
                || mInitialSurroundingText.getSelectionLength() != sourceSelLength) {
            return null;
        }
        return mInitialSurroundingText.getInitialSelectedText(flags);
    }

    /**
     * Get <var>length</var> characters of text after the current cursor position. May be
     * {@code null} when the protocol is not supported.
     *
     * @param length The expected length of the text.
     * @param flags Supplies additional options controlling how the text is returned. May be
     * either 0 or {@link InputConnection#GET_TEXT_WITH_STYLES}.
     * @return the text after the cursor position; the length of the returned text might be less
     * than <var>length</var>. When there is no text after the cursor, an empty string will be
     * returned. It could also be {@code null} when the editor or system could not support this
     * protocol.
     */
    @Nullable
    public CharSequence getInitialTextAfterCursor(int length, int flags) {
        return mInitialSurroundingText.getInitialTextAfterCursor(length, flags);
    }

    private static boolean isCutOnSurrogate(CharSequence sourceText, int cutPosition,
            @TrimPolicy int policy) {
        switch (policy) {
            case TrimPolicy.HEAD:
                return Character.isLowSurrogate(sourceText.charAt(cutPosition));
            case TrimPolicy.TAIL:
                return Character.isHighSurrogate(sourceText.charAt(cutPosition));
            default:
                return false;
        }
    }

    private static boolean isPasswordInputType(int inputType) {
        final int variation =
                inputType & (TYPE_MASK_CLASS | TYPE_MASK_VARIATION);
        return variation
                == (TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD)
                || variation
                == (TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || variation
                == (TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_PASSWORD);
    }

    /**
     * Ensure that the data in this EditorInfo is compatible with an application
     * that was developed against the given target API version.  This can
     * impact the following input types:
     * {@link InputType#TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS},
     * {@link InputType#TYPE_TEXT_VARIATION_WEB_PASSWORD},
     * {@link InputType#TYPE_NUMBER_VARIATION_NORMAL},
     * {@link InputType#TYPE_NUMBER_VARIATION_PASSWORD}.
     *
     * <p>This is called by the framework for input method implementations;
     * you should not generally need to call it yourself.
     *
     * @param targetSdkVersion The API version number that the compatible
     * application was developed against.
     */
    public final void makeCompatible(int targetSdkVersion) {
        if (targetSdkVersion < android.os.Build.VERSION_CODES.HONEYCOMB) {
            switch (inputType&(TYPE_MASK_CLASS|TYPE_MASK_VARIATION)) {
                case TYPE_CLASS_TEXT|TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
                    inputType = TYPE_CLASS_TEXT|TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                            | (inputType&TYPE_MASK_FLAGS);
                    break;
                case TYPE_CLASS_TEXT|TYPE_TEXT_VARIATION_WEB_PASSWORD:
                    inputType = TYPE_CLASS_TEXT|TYPE_TEXT_VARIATION_PASSWORD
                            | (inputType&TYPE_MASK_FLAGS);
                    break;
                case TYPE_CLASS_NUMBER|TYPE_NUMBER_VARIATION_NORMAL:
                case TYPE_CLASS_NUMBER|TYPE_NUMBER_VARIATION_PASSWORD:
                    inputType = TYPE_CLASS_NUMBER
                            | (inputType&TYPE_MASK_FLAGS);
                    break;
            }
        }
    }

    /**
     * Write debug output of this object.
     */
    public void dump(Printer pw, String prefix) {
        pw.println(prefix + "inputType=0x" + Integer.toHexString(inputType)
                + " imeOptions=0x" + Integer.toHexString(imeOptions)
                + " privateImeOptions=" + privateImeOptions);
        pw.println(prefix + "actionLabel=" + actionLabel
                + " actionId=" + actionId);
        pw.println(prefix + "initialSelStart=" + initialSelStart
                + " initialSelEnd=" + initialSelEnd
                + " initialCapsMode=0x"
                + Integer.toHexString(initialCapsMode));
        pw.println(prefix + "hintText=" + hintText
                + " label=" + label);
        pw.println(prefix + "packageName=" + packageName
                + " autofillId=" + autofillId
                + " fieldId=" + fieldId
                + " fieldName=" + fieldName);
        pw.println(prefix + "extras=" + extras);
        pw.println(prefix + "hintLocales=" + hintLocales);
        pw.println(prefix + "contentMimeTypes=" + Arrays.toString(contentMimeTypes));
        if (targetInputMethodUser != null) {
            pw.println(prefix + "targetInputMethodUserId=" + targetInputMethodUser.getIdentifier());
        }
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(inputType);
        dest.writeInt(imeOptions);
        dest.writeString(privateImeOptions);
        TextUtils.writeToParcel(actionLabel, dest, flags);
        dest.writeInt(actionId);
        dest.writeInt(initialSelStart);
        dest.writeInt(initialSelEnd);
        dest.writeInt(initialCapsMode);
        TextUtils.writeToParcel(hintText, dest, flags);
        TextUtils.writeToParcel(label, dest, flags);
        dest.writeString(packageName);
        dest.writeParcelable(autofillId, flags);
        dest.writeInt(fieldId);
        dest.writeString(fieldName);
        dest.writeBundle(extras);
        mInitialSurroundingText.writeToParcel(dest, flags);
        if (hintLocales != null) {
            hintLocales.writeToParcel(dest, flags);
        } else {
            LocaleList.getEmptyLocaleList().writeToParcel(dest, flags);
        }
        dest.writeStringArray(contentMimeTypes);
        UserHandle.writeToParcel(targetInputMethodUser, dest);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<EditorInfo> CREATOR =
            new Parcelable.Creator<EditorInfo>() {
                public EditorInfo createFromParcel(Parcel source) {
                    EditorInfo res = new EditorInfo();
                    res.inputType = source.readInt();
                    res.imeOptions = source.readInt();
                    res.privateImeOptions = source.readString();
                    res.actionLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
                    res.actionId = source.readInt();
                    res.initialSelStart = source.readInt();
                    res.initialSelEnd = source.readInt();
                    res.initialCapsMode = source.readInt();
                    res.hintText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
                    res.label = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
                    res.packageName = source.readString();
                    res.autofillId = source.readParcelable(AutofillId.class.getClassLoader());
                    res.fieldId = source.readInt();
                    res.fieldName = source.readString();
                    res.extras = source.readBundle();
                    InitialSurroundingText initialSurroundingText =
                            InitialSurroundingText.CREATOR.createFromParcel(source);
                    res.mInitialSurroundingText = initialSurroundingText;
                    LocaleList hintLocales = LocaleList.CREATOR.createFromParcel(source);
                    res.hintLocales = hintLocales.isEmpty() ? null : hintLocales;
                    res.contentMimeTypes = source.readStringArray();
                    res.targetInputMethodUser = UserHandle.readFromParcel(source);
                    return res;
                }

                public EditorInfo[] newArray(int size) {
                    return new EditorInfo[size];
                }
            };

    public int describeContents() {
        return 0;
    }

    static final class InitialSurroundingText implements Parcelable {
        @Nullable final CharSequence mSurroundingText;
        final int mSelectionHead;
        final int mSelectionEnd;

        InitialSurroundingText() {
            mSurroundingText = null;
            mSelectionHead = 0;
            mSelectionEnd = 0;
        }

        InitialSurroundingText(@Nullable CharSequence surroundingText, int selectionHead,
                int selectionEnd) {
            mSurroundingText = surroundingText;
            mSelectionHead = selectionHead;
            mSelectionEnd = selectionEnd;
        }

        @Nullable
        private CharSequence getInitialTextBeforeCursor(int n, int flags) {
            if (mSurroundingText == null) {
                return null;
            }

            final int length = Math.min(n, mSelectionHead);
            return ((flags & InputConnection.GET_TEXT_WITH_STYLES) != 0)
                    ? mSurroundingText.subSequence(mSelectionHead - length, mSelectionHead)
                    : TextUtils.substring(mSurroundingText, mSelectionHead - length,
                            mSelectionHead);
        }

        @Nullable
        private CharSequence getInitialSelectedText(int flags) {
            if (mSurroundingText == null) {
                return null;
            }

            return ((flags & InputConnection.GET_TEXT_WITH_STYLES) != 0)
                    ? mSurroundingText.subSequence(mSelectionHead, mSelectionEnd)
                    : TextUtils.substring(mSurroundingText, mSelectionHead, mSelectionEnd);
        }

        @Nullable
        private CharSequence getInitialTextAfterCursor(int n, int flags) {
            if (mSurroundingText == null) {
                return null;
            }

            final int length = Math.min(n, mSurroundingText.length() - mSelectionEnd);
            return ((flags & InputConnection.GET_TEXT_WITH_STYLES) != 0)
                    ? mSurroundingText.subSequence(mSelectionEnd, mSelectionEnd + length)
                    : TextUtils.substring(mSurroundingText, mSelectionEnd, mSelectionEnd + length);
        }

        private int getSelectionLength() {
            return mSelectionEnd - mSelectionHead;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            TextUtils.writeToParcel(mSurroundingText, dest, flags);
            dest.writeInt(mSelectionHead);
            dest.writeInt(mSelectionEnd);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<InitialSurroundingText>
                CREATOR = new Parcelable.Creator<InitialSurroundingText>() {
                    @Override
                    public InitialSurroundingText createFromParcel(Parcel source) {
                        final CharSequence initialText =
                                TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
                        final int selectionHead = source.readInt();
                        final int selectionEnd = source.readInt();

                        return new InitialSurroundingText(initialText, selectionHead, selectionEnd);
                    }

                    @Override
                    public InitialSurroundingText[] newArray(int size) {
                        return new InitialSurroundingText[size];
                    }
                };
    }
}
