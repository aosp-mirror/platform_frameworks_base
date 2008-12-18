package android.view.inputmethod;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.InputType;
import android.text.TextUtils;

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
    public int inputType = TYPE_CLASS_TEXT;

    /**
     * A string supplying additional information about the content type that
     * is private to a particular IME implementation.  The string must be
     * scoped to a package owned by the implementation, to ensure there are
     * no conflicts between implementations, but other than that you can put
     * whatever you want in it to communicate with the IME.  For example,
     * you could have a string that supplies an argument like
     * <code>"com.example.myapp.SpecialMode=3"</code>.  This field is can be
     * filled in from the {@link android.R.attr#editorPrivateContentType}
     * attribute of a TextView.
     */
    public String privateContentType = null;
    
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
     * Any extra data to supply to the input method.  This is for extended
     * communication with specific input methods; the name fields in the
     * bundle should be scoped (such as "com.mydomain.im.SOME_FIELD") so
     * that they don't conflict with others.  This field is can be
     * filled in from the {@link android.R.attr#editorExtras}
     * attribute of a TextView.
     */
    public Bundle extras;
    
    /**
     * Used to package this object into a {@link Parcel}.
     * 
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(inputType);
        dest.writeString(privateContentType);
        dest.writeInt(initialSelStart);
        dest.writeInt(initialSelEnd);
        dest.writeInt(initialCapsMode);
        TextUtils.writeToParcel(hintText, dest, flags);
        TextUtils.writeToParcel(label, dest, flags);
        dest.writeBundle(extras);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<EditorInfo> CREATOR = new Parcelable.Creator<EditorInfo>() {
        public EditorInfo createFromParcel(Parcel source) {
            EditorInfo res = new EditorInfo();
            res.inputType = source.readInt();
            res.privateContentType = source.readString();
            res.initialSelStart = source.readInt();
            res.initialSelEnd = source.readInt();
            res.initialCapsMode = source.readInt();
            res.hintText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            res.label = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
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
