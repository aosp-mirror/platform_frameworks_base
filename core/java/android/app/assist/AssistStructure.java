package android.app.assist;

import android.app.Activity;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * New home for AssistStructure.
 */
public final class AssistStructure extends android.app.AssistStructure implements Parcelable {

    public AssistStructure() {
    }

    /** @hide */
    public AssistStructure(Activity activity) {
        super(activity);
    }

    AssistStructure(Parcel in) {
        super(in);
    }

    public WindowNode getWindowNodeAt(int index) {
        return super.getWindowNodeAt(index);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        if (mHaveData) {
            // This object holds its data.  We want to write a send channel that the
            // other side can use to retrieve that data.
            if (mSendChannel == null) {
                mSendChannel = new SendChannel();
            }
            out.writeStrongBinder(mSendChannel);
        } else {
            // This object doesn't hold its data, so just propagate along its receive channel.
            out.writeStrongBinder(mReceiveChannel);
        }
    }

    public static final Parcelable.Creator<AssistStructure> CREATOR
            = new Parcelable.Creator<AssistStructure>() {
        public AssistStructure createFromParcel(Parcel in) {
            return new AssistStructure(in);
        }

        public AssistStructure[] newArray(int size) {
            return new AssistStructure[size];
        }
    };
}
