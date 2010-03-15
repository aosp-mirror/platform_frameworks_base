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

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Printer;

/**
 * An EditorInfo describes several attributes of a text editing object
 * that an input method is communicating with (typically an EditText), most
 * importantly the type of text content it contains.
 */
public class EditorInfo implements InputType, Parcelable {
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
     * the have typed (in whatever context is appropriate).
     */
    public static final int IME_ACTION_SEARCH = 0x00000003;
    
    /**
     * Bits of {@link #IME_MASK_ACTION}: the action key performs a "send"
     * operation, delivering the text to its target.  This is typically used
     * when composing a message.
     */
    public static final int IME_ACTION_SEND = 0x00000004;
    
    /**
     * Bits of {@link #IME_MASK_ACTION}: the action key performs a "next"
     * operation, taking the user to the next field that will accept text.
     */
    public static final int IME_ACTION_NEXT = 0x00000005;
    
    /**
     * Bits of {@link #IME_MASK_ACTION}: the action key performs a "done"
     * operation, typically meaning the IME will be closed.
     */
    public static final int IME_ACTION_DONE = 0x00000006;
    
    /**
     * Flag of {@link #imeOptions}: used to specify that the IME does not need
     * to show its extracted text UI.  For input methods that may be fullscreen,
     * often when in landscape mode, this allows them to be smaller and let part
     * of the application be shown behind.  Though there will likely be limited
     * access to the application available from the user, it can make the
     * experience of a (mostly) fullscreen IME less jarring.  Note that when
     * this flag is specified the IME may <em>not</em> be set up to be able
     * to display text, so it should only be used in situations where this is
     * not needed.
     */
    public static final int IME_FLAG_NO_EXTRACT_UI = 0x10000000;
    
    /**
     * Flag of {@link #imeOptions}: used in conjunction with
     * {@link #IME_MASK_ACTION}, this indicates that the action should not
     * be available as an accessory button when the input method is full-screen.
     * Note that by setting this flag, there can be cases where the action
     * is simply never available to the user.  Setting this generally means
     * that you think showing text being edited is more important than the
     * action you have supplied. 
     */
    public static final int IME_FLAG_NO_ACCESSORY_ACTION = 0x20000000;
    
    /**
     * Flag of {@link #imeOptions}: used in conjunction with
     * {@link #IME_MASK_ACTION}, this indicates that the action should not
     * be available in-line as a replacement for "enter" key.  Typically this is
     * because the action has such a significant impact or is not recoverable
     * enough that accidentally hitting it should be avoided, such as sending
     * a message.  Note that {@link android.widget.TextView} will automatically set this
     * flag for you on multi-line text views.
     */
    public static final int IME_FLAG_NO_ENTER_ACTION = 0x40000000;

    /**
     * Flag of {@link #imeOptions}: used to request that the IME never go
     * into fullscreen mode.  Applications need to be aware that the flag is not
     * a guarantee, and not all IMEs will respect it.
     * @hide
     */
    public static final int IME_FLAG_NO_FULLSCREEN = 0x80000000;

    /**
     * Generic unspecified type for {@link #imeOptions}.
     */
    public static final int IME_NULL = 0x00000000;
    
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
     * a command the user can perform, which you can specify here.  You can
     * not count on this being used.
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
     * began; -1 if not known.
     */
    public int initialSelStart = -1;
    
    /**
     * The text offset of the end of the selection at the time editing
     * began; -1 if not known.
     */
    public int initialSelEnd = -1;
    
    /**
     * The capitalization mode of the first character being edited in the
     * text.  Values may be any combination of
     * {@link TextUtils#CAP_MODE_CHARACTERS TextUtils.CAP_MODE_CHARACTERS},
     * {@link TextUtils#CAP_MODE_WORDS TextUtils.CAP_MODE_WORDS}, and
     * {@link TextUtils#CAP_MODE_SENTENCES TextUtils.CAP_MODE_SENTENCES}, though
     * you should generally just take a non-zero value to mean start out in
     * caps mode.
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
     */
    public String packageName;
    
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
     * that they don't conflict with others.  This field is can be
     * filled in from the {@link android.R.attr#editorExtras}
     * attribute of a TextView.
     */
    public Bundle extras;
    
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
                + " fieldId=" + fieldId
                + " fieldName=" + fieldName);
        pw.println(prefix + "extras=" + extras);
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
        dest.writeInt(fieldId);
        dest.writeString(fieldName);
        dest.writeBundle(extras);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<EditorInfo> CREATOR = new Parcelable.Creator<EditorInfo>() {
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
            res.fieldId = source.readInt();
            res.fieldName = source.readString();
            res.extras = source.readBundle();
            return res;
        }

        public EditorInfo[] newArray(int size) {
            return new EditorInfo[size];
        }
    };

    public int describeContents() {
        return 0;
    }

}
