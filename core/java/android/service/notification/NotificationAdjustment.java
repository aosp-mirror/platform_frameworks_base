package android.service.notification;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

public class NotificationAdjustment implements Parcelable {
    int mImportance;
    CharSequence mExplanation;
    Uri mReference;

    /**
     * Create a notification importance adjustment.
     *
     * @param importance The final importance of the notification.
     * @param explanation A human-readable justification for the adjustment.
     * @param reference A reference to an external object that augments the
     *                  explanation, such as a
     *                  {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI},
     *                  or null.
     */
    public NotificationAdjustment(int importance, CharSequence explanation, Uri reference) {
        mImportance = importance;
        mExplanation = explanation;
        mReference = reference;
    }

    private NotificationAdjustment(Parcel source) {
        this(source.readInt(), source.readCharSequence(),
                (Uri) source.readParcelable(NotificationAdjustment.class.getClassLoader()));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mImportance);
        dest.writeCharSequence(mExplanation);
        dest.writeParcelable(mReference, 0);
    }

    public static final Parcelable.Creator<NotificationAdjustment> CREATOR
            = new Parcelable.Creator<NotificationAdjustment>() {
        @Override
        public NotificationAdjustment createFromParcel(Parcel source) {
            return new NotificationAdjustment(source);
        }

        @Override
        public NotificationAdjustment[] newArray(int size) {
            return new NotificationAdjustment[size];
        }
    };
}
