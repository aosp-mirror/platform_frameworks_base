/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telephony;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.telephony.flags.Flags;

import java.util.Objects;

/**
 * Contains attributes required to determine the domain for a telephony service, including
 * the network registration state.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_USE_OEM_DOMAIN_SELECTION_SERVICE)
public final class EmergencyRegResult implements Parcelable {

    /**
     * Indicates the cellular network type of the acquired system.
     */
    private @AccessNetworkConstants.RadioAccessNetworkType int mAccessNetworkType;

    /**
     * Registration state of the acquired system.
     */
    private @NetworkRegistrationInfo.RegistrationState int mRegState;

    /**
     * EMC domain indicates the current domain of the acquired system.
     */
    private @NetworkRegistrationInfo.Domain int mDomain;

    /**
     * Indicates whether the network supports voice over PS network.
     */
    private boolean mIsVopsSupported;

    /**
     * This indicates if camped network support VoLTE emergency bearers.
     * This should only be set if the UE is in LTE mode.
     */
    private boolean mIsEmcBearerSupported;

    /**
     * The value of the network provided EMC in 5G Registration ACCEPT.
     * This should be set only if the UE is in 5G mode.
     */
    private int mNwProvidedEmc;

    /**
     * The value of the network provided EMF(EPS Fallback) in 5G Registration ACCEPT.
     * This should be set only if the UE is in 5G mode.
     */
    private int mNwProvidedEmf;

    /** 3-digit Mobile Country Code, 000..999, empty string if unknown. */
    private @NonNull String mMcc;

    /** 2 or 3-digit Mobile Network Code, 00..999, empty string if unknown. */
    private @NonNull String mMnc;

    /**
     * The ISO-3166-1 alpha-2 country code equivalent for the network's country code,
     * empty string if unknown.
     */
    private @NonNull String mCountryIso;

    /**
     * Constructor
     * @param accessNetwork Indicates the network type of the acquired system.
     * @param regState Indicates the registration state of the acquired system.
     * @param domain Indicates the current domain of the acquired system.
     * @param isVopsSupported Indicates whether the network supports voice over PS network.
     * @param isEmcBearerSupported  Indicates if camped network support VoLTE emergency bearers.
     * @param emc The value of the network provided EMC in 5G Registration ACCEPT.
     * @param emf The value of the network provided EMF(EPS Fallback) in 5G Registration ACCEPT.
     * @param mcc Mobile country code, empty string if unknown.
     * @param mnc Mobile network code, empty string if unknown.
     * @param iso The ISO-3166-1 alpha-2 country code equivalent, empty string if unknown.
     * @hide
     */
    public EmergencyRegResult(
            @AccessNetworkConstants.RadioAccessNetworkType int accessNetwork,
            @NetworkRegistrationInfo.RegistrationState int regState,
            @NetworkRegistrationInfo.Domain int domain,
            boolean isVopsSupported, boolean isEmcBearerSupported, int emc, int emf,
            @NonNull String mcc, @NonNull String mnc, @NonNull String iso) {
        mAccessNetworkType = accessNetwork;
        mRegState = regState;
        mDomain = domain;
        mIsVopsSupported = isVopsSupported;
        mIsEmcBearerSupported = isEmcBearerSupported;
        mNwProvidedEmc = emc;
        mNwProvidedEmf = emf;
        mMcc = mcc;
        mMnc = mnc;
        mCountryIso = iso;
    }

    /**
     * Copy constructors
     *
     * @param s Source emergency scan result
     * @hide
     */
    public EmergencyRegResult(@NonNull EmergencyRegResult s) {
        mAccessNetworkType = s.mAccessNetworkType;
        mRegState = s.mRegState;
        mDomain = s.mDomain;
        mIsVopsSupported = s.mIsVopsSupported;
        mIsEmcBearerSupported = s.mIsEmcBearerSupported;
        mNwProvidedEmc = s.mNwProvidedEmc;
        mNwProvidedEmf = s.mNwProvidedEmf;
        mMcc = s.mMcc;
        mMnc = s.mMnc;
        mCountryIso = s.mCountryIso;
    }

    /**
     * Construct a EmergencyRegResult object from the given parcel.
     */
    private EmergencyRegResult(@NonNull Parcel in) {
        readFromParcel(in);
    }

    /**
     * Returns the cellular access network type of the acquired system.
     *
     * @return the cellular network type.
     */
    public @AccessNetworkConstants.RadioAccessNetworkType int getAccessNetwork() {
        return mAccessNetworkType;
    }

    /**
     * Returns the registration state of the acquired system.
     *
     * @return the registration state.
     */
    public @NetworkRegistrationInfo.RegistrationState int getRegState() {
        return mRegState;
    }

    /**
     * Returns the current domain of the acquired system.
     *
     * @return the current domain.
     */
    public @NetworkRegistrationInfo.Domain int getDomain() {
        return mDomain;
    }

    /**
     * Returns whether the network supports voice over PS network.
     *
     * @return {@code true} if the network supports voice over PS network.
     */
    public boolean isVopsSupported() {
        return mIsVopsSupported;
    }

    /**
     * Returns whether camped network support VoLTE emergency bearers.
     * This is not valid if the UE is not in LTE mode.
     *
     * @return {@code true} if the network supports VoLTE emergency bearers.
     */
    public boolean isEmcBearerSupported() {
        return mIsEmcBearerSupported;
    }

    /**
     * Returns the value of the network provided EMC in 5G Registration ACCEPT.
     * This is not valid if UE is not in 5G mode.
     *
     * @return the value of the network provided EMC.
     */
    public int getNwProvidedEmc() {
        return mNwProvidedEmc;
    }

    /**
     * Returns the value of the network provided EMF(EPS Fallback) in 5G Registration ACCEPT.
     * This is not valid if UE is not in 5G mode.
     *
     * @return the value of the network provided EMF.
     */
    public int getNwProvidedEmf() {
        return mNwProvidedEmf;
    }

    /**
     * Returns 3-digit Mobile Country Code.
     *
     * @return Mobile Country Code.
     */
    public @NonNull String getMcc() {
        return mMcc;
    }

    /**
     * Returns 2 or 3-digit Mobile Network Code.
     *
     * @return Mobile Network Code.
     */
    public @NonNull String getMnc() {
        return mMnc;
    }

    /**
     * Returns the ISO-3166-1 alpha-2 country code is provided in lowercase 2 character format.
     *
     * @return Country code.
     */
    public @NonNull String getCountryIso() {
        return mCountryIso;
    }

    @Override
    public @NonNull String toString() {
        return "{ accessNetwork="
                + AccessNetworkConstants.AccessNetworkType.toString(mAccessNetworkType)
                + ", regState=" + NetworkRegistrationInfo.registrationStateToString(mRegState)
                + ", domain=" + NetworkRegistrationInfo.domainToString(mDomain)
                + ", vops=" + mIsVopsSupported
                + ", emcBearer=" + mIsEmcBearerSupported
                + ", emc=" + mNwProvidedEmc
                + ", emf=" + mNwProvidedEmf
                + ", mcc=" + mMcc
                + ", mnc=" + mMnc
                + ", iso=" + mCountryIso
                + " }";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmergencyRegResult that = (EmergencyRegResult) o;
        return mAccessNetworkType == that.mAccessNetworkType
                && mRegState == that.mRegState
                && mDomain == that.mDomain
                && mIsVopsSupported == that.mIsVopsSupported
                && mIsEmcBearerSupported == that.mIsEmcBearerSupported
                && mNwProvidedEmc == that.mNwProvidedEmc
                && mNwProvidedEmf == that.mNwProvidedEmf
                && TextUtils.equals(mMcc, that.mMcc)
                && TextUtils.equals(mMnc, that.mMnc)
                && TextUtils.equals(mCountryIso, that.mCountryIso);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAccessNetworkType, mRegState, mDomain,
                mIsVopsSupported, mIsEmcBearerSupported,
                mNwProvidedEmc, mNwProvidedEmf,
                mMcc, mMnc, mCountryIso);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mAccessNetworkType);
        out.writeInt(mRegState);
        out.writeInt(mDomain);
        out.writeBoolean(mIsVopsSupported);
        out.writeBoolean(mIsEmcBearerSupported);
        out.writeInt(mNwProvidedEmc);
        out.writeInt(mNwProvidedEmf);
        out.writeString8(mMcc);
        out.writeString8(mMnc);
        out.writeString8(mCountryIso);
    }

    private void readFromParcel(@NonNull Parcel in) {
        mAccessNetworkType = in.readInt();
        mRegState = in.readInt();
        mDomain = in.readInt();
        mIsVopsSupported = in.readBoolean();
        mIsEmcBearerSupported = in.readBoolean();
        mNwProvidedEmc = in.readInt();
        mNwProvidedEmf = in.readInt();
        mMcc = in.readString8();
        mMnc = in.readString8();
        mCountryIso = in.readString8();
    }

    public static final @NonNull Creator<EmergencyRegResult> CREATOR =
            new Creator<EmergencyRegResult>() {
        @Override
        public EmergencyRegResult createFromParcel(@NonNull Parcel in) {
            return new EmergencyRegResult(in);
        }

        @Override
        public EmergencyRegResult[] newArray(int size) {
            return new EmergencyRegResult[size];
        }
    };
}
