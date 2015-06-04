package android.app.assist;

import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * New home for AssistContent.
 */
public final class AssistContent extends android.app.AssistContent implements Parcelable {

    /** @hide */
    public AssistContent() {
    }

    public AssistContent(Parcel in) {
        super(in);
    }

    public Intent getIntent() {
        return super.getIntent();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    public static final Parcelable.Creator<AssistContent> CREATOR
            = new Parcelable.Creator<AssistContent>() {
        public AssistContent createFromParcel(Parcel in) {
            return new AssistContent(in);
        }

        public AssistContent[] newArray(int size) {
            return new AssistContent[size];
        }
    };
}
