package android.view.inputmethod;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Description of what an input method would like from an application when
 * extract text from its input editor.
 */
public class ExtractedTextRequest implements Parcelable {
    /**
     * Arbitrary integer that can be supplied in the request, which will be
     * delivered back when reporting updates.
     */
    public int token;
    
    /**
     * Hint for the maximum number of lines to return.
     */
    public int hintMaxLines;
    
    /**
     * Hint for the maximum number of characters to return.
     */
    public int hintMaxChars;
    
    /**
     * Used to package this object into a {@link Parcel}.
     * 
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(token);
        dest.writeInt(hintMaxLines);
        dest.writeInt(hintMaxChars);
    }

    /**
     * Used to make this class parcelable.
     */
    public static final Parcelable.Creator<ExtractedTextRequest> CREATOR
            = new Parcelable.Creator<ExtractedTextRequest>() {
        public ExtractedTextRequest createFromParcel(Parcel source) {
            ExtractedTextRequest res = new ExtractedTextRequest();
            res.token = source.readInt();
            res.hintMaxLines = source.readInt();
            res.hintMaxChars = source.readInt();
            return res;
        }

        public ExtractedTextRequest[] newArray(int size) {
            return new ExtractedTextRequest[size];
        }
    };

    public int describeContents() {
        return 0;
    }
}
