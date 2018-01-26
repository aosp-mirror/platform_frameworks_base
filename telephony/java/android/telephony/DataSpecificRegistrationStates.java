package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;


/**
 * Class that stores information specific to data network registration.
 * @hide
 */
public class DataSpecificRegistrationStates implements Parcelable{
    /**
     * The maximum number of simultaneous Data Calls that
     * must be established using setupDataCall().
     */
    public final int maxDataCalls;

    DataSpecificRegistrationStates(int maxDataCalls) {
        this.maxDataCalls = maxDataCalls;
    }

    private DataSpecificRegistrationStates(Parcel source) {
        maxDataCalls = source.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(maxDataCalls);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "DataSpecificRegistrationStates {" + " mMaxDataCalls=" + maxDataCalls + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxDataCalls);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o == null || !(o instanceof DataSpecificRegistrationStates)) {
            return false;
        }

        DataSpecificRegistrationStates other = (DataSpecificRegistrationStates) o;
        return this.maxDataCalls == other.maxDataCalls;
    }

    public static final Parcelable.Creator<DataSpecificRegistrationStates> CREATOR =
            new Parcelable.Creator<DataSpecificRegistrationStates>() {
                @Override
                public DataSpecificRegistrationStates createFromParcel(Parcel source) {
                    return new DataSpecificRegistrationStates(source);
                }

                @Override
                public DataSpecificRegistrationStates[] newArray(int size) {
                    return new DataSpecificRegistrationStates[size];
                }
            };
}