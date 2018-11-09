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

    /**
     * Indicates if the use of dual connectivity with NR is restricted.
     * Reference: 3GPP TS 24.301 v15.03 section 9.3.3.12A.
     */
    public final boolean isDcNrRestricted;

    /**
     * Indicates if NR is supported by the selected PLMN.
     *
     * {@code true} if the bit N is in the PLMN-InfoList-r15 is true and the selected PLMN is
     * present in plmn-IdentityList at position N.
     * Reference: 3GPP TS 36.331 v15.2.2 section 6.3.1 PLMN-InfoList-r15.
     *            3GPP TS 36.331 v15.2.2 section 6.2.2 SystemInformationBlockType1 message.
     */
    public final boolean isNrAvailable;

    DataSpecificRegistrationStates(
            int maxDataCalls, boolean isDcNrRestricted, boolean isNrAvailable) {
        this.maxDataCalls = maxDataCalls;
        this.isDcNrRestricted = isDcNrRestricted;
        this.isNrAvailable = isNrAvailable;
    }

    private DataSpecificRegistrationStates(Parcel source) {
        maxDataCalls = source.readInt();
        isDcNrRestricted = source.readBoolean();
        isNrAvailable = source.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(maxDataCalls);
        dest.writeBoolean(isDcNrRestricted);
        dest.writeBoolean(isNrAvailable);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return new StringBuilder().append(this.getClass().getName())
                .append(" :{")
                .append(" maxDataCalls = " + maxDataCalls)
                .append(" isDcNrRestricted = " + isDcNrRestricted)
                .append(" isNrAvailable = " + isNrAvailable)
                .append(" }")
                .toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxDataCalls, isDcNrRestricted, isNrAvailable);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (!(o instanceof DataSpecificRegistrationStates)) return false;

        DataSpecificRegistrationStates other = (DataSpecificRegistrationStates) o;
        return this.maxDataCalls == other.maxDataCalls
                && this.isDcNrRestricted == other.isDcNrRestricted
                && this.isNrAvailable == other.isNrAvailable;
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