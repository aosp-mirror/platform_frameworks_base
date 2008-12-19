package android.view.inputmethod;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Information about text that has been extracted for use by an input method.
 */
public class ExtractedText implements Parcelable {
    /**
     * The text that has been extracted.
     */
    public CharSequence text;

    /**
     * The offset in the overall text at which the extracted text starts.
     */
    public int startOffset;
    
    /**
     * The offset where the selection currently starts within the extracted
     * text.  The real selection start position is at
     * <var>startOffset</var>+<var>selectionStart</var>.
     */
    public int selectionStart;
    
    /**
     * The offset where the selection currently ends within the extracted
     * text.  The real selection end position is at
     * <var>startOffset</var>+<var>selectionEnd</var>.
     */
    public int selectionEnd;
    
    /**
     * Bit for {@link #flags}: set if the text being edited can only be on
     * a single line.
     */
    public static final int FLAG_SINGLE_LINE = 0x0001;
    
    /**
     * Additional bit flags of information about the edited text.
     */
    public int flags;
    
    /**
     * Used to package this object into a {@link Parcel}.
     * 
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    public void writeToParcel(Parcel dest, int flags) {
        TextUtils.writeToParcel(text, dest, flags);
        dest.writeInt(startOffset);
        dest.writeInt(selectionStart);
        dest.writeInt(selectionEnd);
        dest.writeInt(flags);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<ExtractedText> CREATOR = new Parcelable.Creator<ExtractedText>() {
        public ExtractedText createFromParcel(Parcel source) {
            ExtractedText res = new ExtractedText();
            res.text = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            res.startOffset = source.readInt();
            res.selectionStart = source.readInt();
            res.selectionEnd = source.readInt();
            res.flags = source.readInt();
            return res;
        }

        public ExtractedText[] newArray(int size) {
            return new ExtractedText[size];
        }
    };

    public int describeContents() {
        return 0;
    }
}
