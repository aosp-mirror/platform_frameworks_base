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

    /**
     * Indicates that if E-UTRA-NR Dual Connectivity (EN-DC) is supported by the primary serving
     * cell.
     *
     * True the primary serving cell is LTE cell and the plmn-InfoList-r15 is present in SIB2 and
     * at least one bit in this list is true, otherwise this value should be false.
     *
     * Reference: 3GPP TS 36.331 v15.2.2 6.3.1 System information blocks.
     */
    public final boolean isEnDcAvailable;

    /**
     * Provides network support info for LTE VoPS and LTE Emergency bearer support
     */
    public final LteVopsSupportInfo lteVopsSupportInfo;

    DataSpecificRegistrationStates(
            int maxDataCalls, boolean isDcNrRestricted, boolean isNrAvailable,
            boolean isEnDcAvailable, LteVopsSupportInfo lteVops) {
        this.maxDataCalls = maxDataCalls;
        this.isDcNrRestricted = isDcNrRestricted;
        this.isNrAvailable = isNrAvailable;
        this.isEnDcAvailable = isEnDcAvailable;
        this.lteVopsSupportInfo = lteVops;
    }

    private DataSpecificRegistrationStates(Parcel source) {
        maxDataCalls = source.readInt();
        isDcNrRestricted = source.readBoolean();
        isNrAvailable = source.readBoolean();
        isEnDcAvailable = source.readBoolean();
        lteVopsSupportInfo = LteVopsSupportInfo.CREATOR.createFromParcel(source);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(maxDataCalls);
        dest.writeBoolean(isDcNrRestricted);
        dest.writeBoolean(isNrAvailable);
        dest.writeBoolean(isEnDcAvailable);
        lteVopsSupportInfo.writeToParcel(dest, flags);
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
                .append(" isEnDcAvailable = " + isEnDcAvailable)
                .append(lteVopsSupportInfo.toString())
                .append(" }")
                .toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxDataCalls, isDcNrRestricted, isNrAvailable, isEnDcAvailable,
            lteVopsSupportInfo);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (!(o instanceof DataSpecificRegistrationStates)) return false;

        DataSpecificRegistrationStates other = (DataSpecificRegistrationStates) o;
        return this.maxDataCalls == other.maxDataCalls
                && this.isDcNrRestricted == other.isDcNrRestricted
                && this.isNrAvailable == other.isNrAvailable
                && this.isEnDcAvailable == other.isEnDcAvailable
                && this.lteVopsSupportInfo.equals(other.lteVopsSupportInfo);
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
