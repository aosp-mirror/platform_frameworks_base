package android.telecom;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public final class PhoneAccountSuggestion implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {REASON_NONE, REASON_INTRA_CARRIER, REASON_FREQUENT,
            REASON_USER_SET, REASON_OTHER}, prefix = { "REASON_" })
    public @interface SuggestionReason {}

    public static final int REASON_NONE = 0;
    public static final int REASON_INTRA_CARRIER = 1;
    public static final int REASON_FREQUENT = 2;
    public static final int REASON_USER_SET = 3;
    public static final int REASON_OTHER = 4;

    private PhoneAccountHandle mHandle;
    private int mReason;
    private boolean mShouldAutoSelect;

    /**
     * @hide
     */
    @SystemApi
    public PhoneAccountSuggestion(PhoneAccountHandle handle, @SuggestionReason int reason,
            boolean shouldAutoSelect) {
        this.mHandle = handle;
        this.mReason = reason;
        this.mShouldAutoSelect = shouldAutoSelect;
    }

    private PhoneAccountSuggestion(Parcel in) {
        mHandle = in.readParcelable(PhoneAccountHandle.class.getClassLoader());
        mReason = in.readInt();
        mShouldAutoSelect = in.readByte() != 0;
    }

    public static final Creator<PhoneAccountSuggestion> CREATOR =
            new Creator<PhoneAccountSuggestion>() {
                @Override
                public PhoneAccountSuggestion createFromParcel(Parcel in) {
                    return new PhoneAccountSuggestion(in);
                }

                @Override
                public PhoneAccountSuggestion[] newArray(int size) {
                    return new PhoneAccountSuggestion[size];
                }
            };

    public PhoneAccountHandle getHandle() {
        return mHandle;
    }

    public @SuggestionReason int getReason() {
        return mReason;
    }

    public boolean shouldAutoSelect() {
        return mShouldAutoSelect;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mHandle, flags);
        dest.writeInt(mReason);
        dest.writeByte((byte) (mShouldAutoSelect ? 1 : 0));
    }
}
