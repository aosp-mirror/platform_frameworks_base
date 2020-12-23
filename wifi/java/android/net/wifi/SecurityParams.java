/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiConfiguration.GroupCipher;
import android.net.wifi.WifiConfiguration.GroupMgmtCipher;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.PairwiseCipher;
import android.net.wifi.WifiConfiguration.Protocol;
import android.net.wifi.WifiConfiguration.SecurityType;
import android.net.wifi.WifiConfiguration.SuiteBCipher;
import android.os.Parcel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.BitSet;
import java.util.Objects;

/**
 * A class representing a security configuration.
 * @hide
 */
public class SecurityParams {
    private static final String TAG = "SecurityParams";

    /** Passpoint Release 1 */
    public static final int PASSPOINT_R1 = 1;

    /** Passpoint Release 2 */
    public static final int PASSPOINT_R2 = 2;

    /** Passpoint Release 3 */
    public static final int PASSPOINT_R3 = 3;

    @IntDef(prefix = { "PASSPOINT_" }, value = {
        PASSPOINT_R1,
        PASSPOINT_R2,
        PASSPOINT_R3,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PasspointRelease {}

    private @SecurityType int mSecurityType = WifiConfiguration.SECURITY_TYPE_PSK;

    /**
     * This indicates that this security type is enabled or disabled.
     * Ex. While receiving Transition Disable Indication, older
     * security should be disabled.
     */
    private boolean mEnabled = true;

    /**
     * The set of key management protocols supported by this configuration.
     * See {@link KeyMgmt} for descriptions of the values.
     * This is set automatically based on the security type.
     */
    private BitSet mAllowedKeyManagement = new BitSet();

    /**
     * The set of security protocols supported by this configuration.
     * See {@link Protocol} for descriptions of the values.
     * This is set automatically based on the security type.
     */
    private BitSet mAllowedProtocols = new BitSet();

    /**
     * The set of authentication protocols supported by this configuration.
     * See {@link AuthAlgorithm} for descriptions of the values.
     * This is set automatically based on the security type.
     */
    private BitSet mAllowedAuthAlgorithms = new BitSet();

    /**
     * The set of pairwise ciphers for WPA supported by this configuration.
     * See {@link PairwiseCipher} for descriptions of the values.
     * This is set automatically based on the security type.
     */
    private BitSet mAllowedPairwiseCiphers = new BitSet();

    /**
     * The set of group ciphers supported by this configuration.
     * See {@link GroupCipher} for descriptions of the values.
     * This is set automatically based on the security type.
     */
    private BitSet mAllowedGroupCiphers = new BitSet();

    /**
     * The set of group management ciphers supported by this configuration.
     * See {@link GroupMgmtCipher} for descriptions of the values.
     */
    private BitSet mAllowedGroupManagementCiphers = new BitSet();

    /**
     * The set of SuiteB ciphers supported by this configuration.
     * To be used for WPA3-Enterprise mode. Set automatically by the framework based on the
     * certificate type that is used in this configuration.
     */
    private BitSet mAllowedSuiteBCiphers = new BitSet();

    /**
     * True if the network requires Protected Management Frames (PMF), false otherwise.
     */
    private boolean mRequirePmf = false;

    private @PasspointRelease int mPasspointRelease = PASSPOINT_R2;

    /** Indicate that this SAE security type only accepts H2E (Hash-to-Element) mode. */
    private boolean mIsSaeH2eOnlyMode = false;

    /** Indicate that this SAE security type only accepts PK (Public Key) mode. */
    private boolean mIsSaePkOnlyMode = false;

    /** Indicate whether this is added by auto-upgrade or not. */
    private boolean mIsAddedByAutoUpgrade = false;

    /** Constructor */
    private SecurityParams() {
    }

    /** Copy constructor */
    public SecurityParams(@NonNull SecurityParams source) {
        this.mSecurityType = source.mSecurityType;
        this.mEnabled = source.mEnabled;
        this.mAllowedKeyManagement = (BitSet) source.mAllowedKeyManagement.clone();
        this.mAllowedProtocols = (BitSet) source.mAllowedProtocols.clone();
        this.mAllowedAuthAlgorithms = (BitSet) source.mAllowedAuthAlgorithms.clone();
        this.mAllowedPairwiseCiphers = (BitSet) source.mAllowedPairwiseCiphers.clone();
        this.mAllowedGroupCiphers = (BitSet) source.mAllowedGroupCiphers.clone();
        this.mAllowedGroupManagementCiphers =
                (BitSet) source.mAllowedGroupManagementCiphers.clone();
        this.mAllowedSuiteBCiphers =
                (BitSet) source.mAllowedSuiteBCiphers.clone();
        this.mRequirePmf = source.mRequirePmf;
        this.mIsSaeH2eOnlyMode = source.mIsSaeH2eOnlyMode;
        this.mIsSaePkOnlyMode = source.mIsSaePkOnlyMode;
        this.mIsAddedByAutoUpgrade = source.mIsAddedByAutoUpgrade;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof SecurityParams)) {
            return false;
        }
        SecurityParams that = (SecurityParams) thatObject;

        if (this.mSecurityType != that.mSecurityType) return false;
        if (this.mEnabled != that.mEnabled) return false;
        if (!this.mAllowedKeyManagement.equals(that.mAllowedKeyManagement)) return false;
        if (!this.mAllowedProtocols.equals(that.mAllowedProtocols)) return false;
        if (!this.mAllowedAuthAlgorithms.equals(that.mAllowedAuthAlgorithms)) return false;
        if (!this.mAllowedPairwiseCiphers.equals(that.mAllowedPairwiseCiphers)) return false;
        if (!this.mAllowedGroupCiphers.equals(that.mAllowedGroupCiphers)) return false;
        if (!this.mAllowedGroupManagementCiphers.equals(that.mAllowedGroupManagementCiphers)) {
            return false;
        }
        if (!this.mAllowedSuiteBCiphers.equals(that.mAllowedSuiteBCiphers)) return false;
        if (this.mRequirePmf != that.mRequirePmf) return false;
        if (this.mIsSaeH2eOnlyMode != that.mIsSaeH2eOnlyMode) return false;
        if (this.mIsSaePkOnlyMode != that.mIsSaePkOnlyMode) return false;
        if (this.mIsAddedByAutoUpgrade != that.mIsAddedByAutoUpgrade) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSecurityType, mEnabled,
                mAllowedKeyManagement, mAllowedProtocols, mAllowedAuthAlgorithms,
                mAllowedPairwiseCiphers, mAllowedGroupCiphers, mAllowedGroupManagementCiphers,
                mAllowedSuiteBCiphers, mRequirePmf,
                mIsSaeH2eOnlyMode, mIsSaePkOnlyMode, mIsAddedByAutoUpgrade);
    }

    /**
     * Get the security type of this params.
     *
     * @return The security type defined in {@link WifiConfiguration}.
     */
    public @SecurityType int getSecurityType() {
        return mSecurityType;
    }

    /**
     * Check the security type of this params.
     *
     * @param type the testing security type.
     * @return true if this is for the corresponiding type.
     */
    public boolean isSecurityType(@SecurityType int type) {
        return type == mSecurityType;
    }

    /**
     * Check whether the security of given params is the same as this one.
     *
     * @param params the testing security params.
     * @return true if their security types are the same.
     */
    public boolean isSameSecurityType(SecurityParams params) {
        return params.mSecurityType == mSecurityType;
    }

    /**
     * Update security params to legacy WifiConfiguration object.
     *
     * @param config the target configuration.
     */
    public void updateLegacyWifiConfiguration(WifiConfiguration config) {
        config.allowedKeyManagement = (BitSet) mAllowedKeyManagement.clone();
        config.allowedProtocols = (BitSet) mAllowedProtocols.clone();
        config.allowedAuthAlgorithms = (BitSet) mAllowedAuthAlgorithms.clone();
        config.allowedPairwiseCiphers = (BitSet) mAllowedPairwiseCiphers.clone();
        config.allowedGroupCiphers = (BitSet) mAllowedGroupCiphers.clone();
        config.allowedGroupManagementCiphers = (BitSet) mAllowedGroupManagementCiphers.clone();
        config.allowedSuiteBCiphers = (BitSet) mAllowedSuiteBCiphers.clone();
        config.requirePmf = mRequirePmf;
    }

    /**
     * Set this params enabled.
     *
     * @param enable enable a specific security type.
     */
    public void setEnabled(boolean enable) {
        mEnabled = enable;
    }

    /**
     * Indicate this params is enabled or not.
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Set the supporting Fast Initial Link Set-up (FILS) key management.
     *
     * FILS can be applied to all security types.
     * @param enableFilsSha256 Enable FILS SHA256.
     * @param enableFilsSha384 Enable FILS SHA256.
     */
    public void enableFils(boolean enableFilsSha256, boolean enableFilsSha384) {
        if (enableFilsSha256) {
            mAllowedKeyManagement.set(KeyMgmt.FILS_SHA256);
        }

        if (enableFilsSha384) {
            mAllowedKeyManagement.set(KeyMgmt.FILS_SHA384);
        }
    }

    /**
     * Get the copy of allowed key management.
     */
    public BitSet getAllowedKeyManagement() {
        return (BitSet) mAllowedKeyManagement.clone();
    }

    /**
     * Get the copy of allowed protocols.
     */
    public BitSet getAllowedProtocols() {
        return (BitSet) mAllowedProtocols.clone();
    }

    /**
     * Get the copy of allowed auth algorithms.
     */
    public BitSet getAllowedAuthAlgorithms() {
        return (BitSet) mAllowedAuthAlgorithms.clone();
    }

    /**
     * Get the copy of allowed pairwise ciphers.
     */
    public BitSet getAllowedPairwiseCiphers() {
        return (BitSet) mAllowedPairwiseCiphers.clone();
    }

    /**
     * Get the copy of allowed group ciphers.
     */
    public BitSet getAllowedGroupCiphers() {
        return (BitSet) mAllowedGroupCiphers.clone();
    }

    /**
     * Get the copy of allowed group management ciphers.
     */
    public BitSet getAllowedGroupManagementCiphers() {
        return (BitSet) mAllowedGroupManagementCiphers.clone();
    }

    /**
     * Enable Suite-B ciphers.
     *
     * @param enableEcdheEcdsa enable Diffie-Hellman with Elliptic Curve ECDSA cipher support.
     * @param enableEcdheRsa enable Diffie-Hellman with RSA cipher support.
     */
    public void enableSuiteBCiphers(boolean enableEcdheEcdsa, boolean enableEcdheRsa) {
        if (enableEcdheEcdsa) {
            mAllowedSuiteBCiphers.set(SuiteBCipher.ECDHE_ECDSA);
        } else {
            mAllowedSuiteBCiphers.clear(SuiteBCipher.ECDHE_ECDSA);
        }

        if (enableEcdheRsa) {
            mAllowedSuiteBCiphers.set(SuiteBCipher.ECDHE_RSA);
        } else {
            mAllowedSuiteBCiphers.clear(SuiteBCipher.ECDHE_RSA);
        }
    }

    /**
     * Get the copy of allowed suite-b ciphers.
     */
    public BitSet getAllowedSuiteBCiphers() {
        return (BitSet) mAllowedSuiteBCiphers.clone();
    }

    /**
     * Indicate PMF is required or not.
     */
    public boolean isRequirePmf() {
        return mRequirePmf;
    }

    /**
     * Indicate that this is open security type.
     */
    public boolean isOpenSecurityType() {
        return isSecurityType(WifiConfiguration.SECURITY_TYPE_OPEN)
                || isSecurityType(WifiConfiguration.SECURITY_TYPE_OWE);
    }

    /**
     * Indicate that this is enterprise security type.
     */
    public boolean isEnterpriseSecurityType() {
        return mAllowedKeyManagement.get(KeyMgmt.WPA_EAP)
                || mAllowedKeyManagement.get(KeyMgmt.IEEE8021X)
                || mAllowedKeyManagement.get(KeyMgmt.SUITE_B_192)
                || mAllowedKeyManagement.get(KeyMgmt.WAPI_CERT);
    }

    /**
     * Enable Hash-to-Element only mode.
     *
     * @param enable set H2E only mode enabled or not.
     */
    public void enableSaeH2eOnlyMode(boolean enable) {
        mIsSaeH2eOnlyMode = enable;
    }

    /**
     * Indicate whether this params is H2E only mode.
     *
     * @return true if this is H2E only mode params.
     */
    public boolean isSaeH2eOnlyMode() {
        return mIsSaeH2eOnlyMode;
    }
    /**
     * Enable Pubilc-Key only mode.
     *
     * @param enable set PK only mode enabled or not.
     */
    public void enableSaePkOnlyMode(boolean enable) {
        mIsSaePkOnlyMode = enable;
    }

    /**
     * Indicate whether this params is PK only mode.
     *
     * @return true if this is PK only mode params.
     */
    public boolean isSaePkOnlyMode() {
        return mIsSaePkOnlyMode;
    }

    /**
     * Set whether this is added by auto-upgrade.
     *
     * @param addedByAutoUpgrade true if added by auto-upgrade.
     */
    public void setIsAddedByAutoUpgrade(boolean addedByAutoUpgrade) {
        mIsAddedByAutoUpgrade = addedByAutoUpgrade;
    }

    /**
     * Indicate whether this is added by auto-upgrade or not.
     *
     * @return true if added by auto-upgrade; otherwise, false.
     */
    public boolean isAddedByAutoUpgrade() {
        return mIsAddedByAutoUpgrade;
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("Security Parameters:\n");
        sbuf.append(" Type: ").append(mSecurityType).append("\n");
        sbuf.append(" Enabled: ").append(mEnabled).append("\n");
        sbuf.append(" KeyMgmt:");
        for (int k = 0; k < mAllowedKeyManagement.size(); k++) {
            if (mAllowedKeyManagement.get(k)) {
                sbuf.append(" ");
                if (k < KeyMgmt.strings.length) {
                    sbuf.append(KeyMgmt.strings[k]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" Protocols:");
        for (int p = 0; p < mAllowedProtocols.size(); p++) {
            if (mAllowedProtocols.get(p)) {
                sbuf.append(" ");
                if (p < Protocol.strings.length) {
                    sbuf.append(Protocol.strings[p]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" AuthAlgorithms:");
        for (int a = 0; a < mAllowedAuthAlgorithms.size(); a++) {
            if (mAllowedAuthAlgorithms.get(a)) {
                sbuf.append(" ");
                if (a < AuthAlgorithm.strings.length) {
                    sbuf.append(AuthAlgorithm.strings[a]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" PairwiseCiphers:");
        for (int pc = 0; pc < mAllowedPairwiseCiphers.size(); pc++) {
            if (mAllowedPairwiseCiphers.get(pc)) {
                sbuf.append(" ");
                if (pc < PairwiseCipher.strings.length) {
                    sbuf.append(PairwiseCipher.strings[pc]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" GroupCiphers:");
        for (int gc = 0; gc < mAllowedGroupCiphers.size(); gc++) {
            if (mAllowedGroupCiphers.get(gc)) {
                sbuf.append(" ");
                if (gc < GroupCipher.strings.length) {
                    sbuf.append(GroupCipher.strings[gc]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" GroupMgmtCiphers:");
        for (int gmc = 0; gmc < mAllowedGroupManagementCiphers.size(); gmc++) {
            if (mAllowedGroupManagementCiphers.get(gmc)) {
                sbuf.append(" ");
                if (gmc < GroupMgmtCipher.strings.length) {
                    sbuf.append(GroupMgmtCipher.strings[gmc]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" SuiteBCiphers:");
        for (int sbc = 0; sbc < mAllowedSuiteBCiphers.size(); sbc++) {
            if (mAllowedSuiteBCiphers.get(sbc)) {
                sbuf.append(" ");
                if (sbc < SuiteBCipher.strings.length) {
                    sbuf.append(SuiteBCipher.strings[sbc]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" RequirePmf: ").append(mRequirePmf).append('\n');
        sbuf.append(" IsAddedByAutoUpgrade: ").append(mIsAddedByAutoUpgrade).append("\n");
        sbuf.append(" IsSaeH2eOnlyMode: ").append(mIsSaeH2eOnlyMode).append("\n");
        sbuf.append(" IsSaePkOnlyMode: ").append(mIsSaePkOnlyMode).append("\n");
        return sbuf.toString();
    }

    private static BitSet readBitSet(Parcel src) {
        int cardinality = src.readInt();

        BitSet set = new BitSet();
        for (int i = 0; i < cardinality; i++) {
            set.set(src.readInt());
        }

        return set;
    }

    private static void writeBitSet(Parcel dest, BitSet set) {
        int nextSetBit = -1;

        dest.writeInt(set.cardinality());

        while ((nextSetBit = set.nextSetBit(nextSetBit + 1)) != -1) {
            dest.writeInt(nextSetBit);
        }
    }

    /** Write this object to the parcel. */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSecurityType);
        dest.writeBoolean(mEnabled);
        writeBitSet(dest, mAllowedKeyManagement);
        writeBitSet(dest, mAllowedProtocols);
        writeBitSet(dest, mAllowedAuthAlgorithms);
        writeBitSet(dest, mAllowedPairwiseCiphers);
        writeBitSet(dest, mAllowedGroupCiphers);
        writeBitSet(dest, mAllowedGroupManagementCiphers);
        writeBitSet(dest, mAllowedSuiteBCiphers);
        dest.writeBoolean(mRequirePmf);
        dest.writeBoolean(mIsAddedByAutoUpgrade);
        dest.writeBoolean(mIsSaeH2eOnlyMode);
        dest.writeBoolean(mIsSaePkOnlyMode);

    }

    /** Create a SecurityParams object from the parcel. */
    public static final @NonNull SecurityParams createFromParcel(Parcel in) {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = in.readInt();
        params.mEnabled = in.readBoolean();
        params.mAllowedKeyManagement = readBitSet(in);
        params.mAllowedProtocols = readBitSet(in);
        params.mAllowedAuthAlgorithms = readBitSet(in);
        params.mAllowedPairwiseCiphers = readBitSet(in);
        params.mAllowedGroupCiphers = readBitSet(in);
        params.mAllowedGroupManagementCiphers = readBitSet(in);
        params.mAllowedSuiteBCiphers = readBitSet(in);
        params.mRequirePmf = in.readBoolean();
        params.mIsAddedByAutoUpgrade = in.readBoolean();
        params.mIsSaeH2eOnlyMode = in.readBoolean();
        params.mIsSaePkOnlyMode = in.readBoolean();
        return params;
    }

    /**
     * Create a params according to the security type.
     *
     * @param securityType One of the following security types:
     * {@link WifiConfiguration#SECURITY_TYPE_OPEN},
     * {@link WifiConfiguration#SECURITY_TYPE_WEP},
     * {@link WifiConfiguration#SECURITY_TYPE_PSK},
     * {@link WifiConfiguration#SECURITY_TYPE_EAP},
     * {@link WifiConfiguration#SECURITY_TYPE_SAE},
     * {@link WifiConfiguration#SECURITY_TYPE_OWE},
     * {@link WifiConfiguration#SECURITY_TYPE_WAPI_PSK},
     * {@link WifiConfiguration#SECURITY_TYPE_WAPI_CERT},
     * {@link WifiConfiguration#SECURITY_TYPE_EAP_WPA3_ENTERPRISE},
     * {@link WifiConfiguration#SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT},
     *
     * @return the corresponding security params if the security type is valid;
     *         otherwise, throw IllegalArgumentException.
     */
    public static @NonNull SecurityParams createSecurityParamsBySecurityType(
            @WifiConfiguration.SecurityType int securityType) {
        switch (securityType) {
            case WifiConfiguration.SECURITY_TYPE_OPEN:
                return createOpenParams();
            case WifiConfiguration.SECURITY_TYPE_WEP:
                return createWepParams();
            case WifiConfiguration.SECURITY_TYPE_PSK:
                return createWpaWpa2PersonalParams();
            case WifiConfiguration.SECURITY_TYPE_EAP:
                return createWpaWpa2EnterpriseParams();
            case WifiConfiguration.SECURITY_TYPE_SAE:
                return createWpa3PersonalParams();
            // The value of {@link WifiConfiguration.SECURITY_TYPE_EAP_SUITE_B} is the same as
            // {@link #WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT}, remove it
            // to avoid duplicate case label errors.
            case WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT:
                return createWpa3Enterprise192BitParams();
            case WifiConfiguration.SECURITY_TYPE_OWE:
                return createEnhancedOpenParams();
            case WifiConfiguration.SECURITY_TYPE_WAPI_PSK:
                return createWapiPskParams();
            case WifiConfiguration.SECURITY_TYPE_WAPI_CERT:
                return createWapiCertParams();
            case WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE:
                return createWpa3EnterpriseParams();
            case WifiConfiguration.SECURITY_TYPE_OSEN:
                return createOsenParams();
            case WifiConfiguration.SECURITY_TYPE_PASSPOINT_R1_R2:
                return SecurityParams.createPasspointParams(PASSPOINT_R2);
            case WifiConfiguration.SECURITY_TYPE_PASSPOINT_R3:
                return SecurityParams.createPasspointParams(PASSPOINT_R3);
            default:
                throw new IllegalArgumentException("unknown security type " + securityType);
        }
    }

    /**
     * Create EAP security params.
     */
    private static @NonNull SecurityParams createWpaWpa2EnterpriseParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_EAP;

        params.mAllowedKeyManagement.set(KeyMgmt.WPA_EAP);
        params.mAllowedKeyManagement.set(KeyMgmt.IEEE8021X);

        params.mAllowedProtocols.set(Protocol.RSN);
        params.mAllowedProtocols.set(Protocol.WPA);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.CCMP);
        params.mAllowedPairwiseCiphers.set(PairwiseCipher.TKIP);

        params.mAllowedGroupCiphers.set(GroupCipher.CCMP);
        params.mAllowedGroupCiphers.set(GroupCipher.TKIP);
        return params;
    }

    /**
     * Create Passpoint security params.
     */
    private static @NonNull SecurityParams createPasspointParams(@PasspointRelease int release) {
        SecurityParams params = new SecurityParams();
        switch (release) {
            case PASSPOINT_R1:
            case PASSPOINT_R2:
                params.mSecurityType = WifiConfiguration.SECURITY_TYPE_PASSPOINT_R1_R2;
                break;
            case PASSPOINT_R3:
                params.mSecurityType = WifiConfiguration.SECURITY_TYPE_PASSPOINT_R3;
                params.mRequirePmf = true;
                break;
            default:
                throw new IllegalArgumentException("invalid passpoint release " + release);
        }

        params.mAllowedKeyManagement.set(KeyMgmt.WPA_EAP);
        params.mAllowedKeyManagement.set(KeyMgmt.IEEE8021X);

        params.mAllowedProtocols.set(Protocol.RSN);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.CCMP);

        params.mAllowedGroupCiphers.set(GroupCipher.CCMP);

        return params;
    }

    /**
     * Create Enhanced Open params.
     */
    private static @NonNull SecurityParams createEnhancedOpenParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_OWE;

        params.mAllowedKeyManagement.set(KeyMgmt.OWE);

        params.mAllowedProtocols.set(Protocol.RSN);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.CCMP);
        params.mAllowedPairwiseCiphers.set(PairwiseCipher.GCMP_128);
        params.mAllowedPairwiseCiphers.set(PairwiseCipher.GCMP_256);

        params.mAllowedGroupCiphers.set(GroupCipher.CCMP);
        params.mAllowedGroupCiphers.set(GroupCipher.GCMP_128);
        params.mAllowedGroupCiphers.set(GroupCipher.GCMP_256);

        params.mRequirePmf = true;
        return params;
    }

    /**
     * Create Open params.
     */
    private static @NonNull SecurityParams createOpenParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_OPEN;

        params.mAllowedKeyManagement.set(KeyMgmt.NONE);

        params.mAllowedProtocols.set(Protocol.RSN);
        params.mAllowedProtocols.set(Protocol.WPA);
        return params;
    }

    /**
     * Create OSEN params.
     */
    private static @NonNull SecurityParams createOsenParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_OSEN;

        params.mAllowedKeyManagement.set(KeyMgmt.OSEN);

        params.mAllowedProtocols.set(Protocol.OSEN);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.CCMP);
        params.mAllowedPairwiseCiphers.set(PairwiseCipher.TKIP);

        params.mAllowedGroupCiphers.set(GroupCipher.CCMP);
        params.mAllowedGroupCiphers.set(GroupCipher.TKIP);
        return params;
    }

    /**
     * Create WAPI-CERT params.
     */
    private static @NonNull SecurityParams createWapiCertParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_WAPI_CERT;

        params.mAllowedKeyManagement.set(KeyMgmt.WAPI_CERT);

        params.mAllowedProtocols.set(Protocol.WAPI);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.SMS4);

        params.mAllowedGroupCiphers.set(GroupCipher.SMS4);
        return params;
    }

    /**
     * Create WAPI-PSK params.
     */
    private static @NonNull SecurityParams createWapiPskParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_WAPI_PSK;

        params.mAllowedKeyManagement.set(KeyMgmt.WAPI_PSK);

        params.mAllowedProtocols.set(Protocol.WAPI);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.SMS4);

        params.mAllowedGroupCiphers.set(GroupCipher.SMS4);
        return params;
    }

    /**
     * Create WEP params.
     */
    private static @NonNull SecurityParams createWepParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_WEP;

        params.mAllowedKeyManagement.set(KeyMgmt.NONE);

        params.mAllowedProtocols.set(Protocol.RSN);

        params.mAllowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
        params.mAllowedAuthAlgorithms.set(AuthAlgorithm.SHARED);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.CCMP);
        params.mAllowedPairwiseCiphers.set(PairwiseCipher.TKIP);

        params.mAllowedGroupCiphers.set(GroupCipher.CCMP);
        params.mAllowedGroupCiphers.set(GroupCipher.TKIP);
        params.mAllowedGroupCiphers.set(GroupCipher.WEP40);
        params.mAllowedGroupCiphers.set(GroupCipher.WEP104);
        return params;
    }

    /**
     * Create WPA3 Enterprise 192-bit params.
     */
    private static @NonNull SecurityParams createWpa3Enterprise192BitParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT;

        params.mAllowedKeyManagement.set(KeyMgmt.WPA_EAP);
        params.mAllowedKeyManagement.set(KeyMgmt.IEEE8021X);
        params.mAllowedKeyManagement.set(KeyMgmt.SUITE_B_192);

        params.mAllowedProtocols.set(Protocol.RSN);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.GCMP_128);
        params.mAllowedPairwiseCiphers.set(PairwiseCipher.GCMP_256);

        params.mAllowedGroupCiphers.set(GroupCipher.GCMP_128);
        params.mAllowedGroupCiphers.set(GroupCipher.GCMP_256);

        params.mAllowedGroupManagementCiphers.set(GroupMgmtCipher.BIP_GMAC_256);

        // Note: allowedSuiteBCiphers bitset will be set by the service once the
        // certificates are attached to this profile

        params.mRequirePmf = true;
        return params;
    }

    /**
     * Create WPA3 Enterprise params.
     */
    private static @NonNull SecurityParams createWpa3EnterpriseParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE;

        params.mAllowedKeyManagement.set(KeyMgmt.WPA_EAP);
        params.mAllowedKeyManagement.set(KeyMgmt.IEEE8021X);

        params.mAllowedProtocols.set(Protocol.RSN);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.CCMP);
        params.mAllowedPairwiseCiphers.set(PairwiseCipher.GCMP_256);

        params.mAllowedGroupCiphers.set(GroupCipher.CCMP);
        params.mAllowedGroupCiphers.set(GroupCipher.GCMP_256);

        params.mRequirePmf = true;
        return params;
    }

    /**
     * Create WPA3 Personal params.
     */
    private static @NonNull SecurityParams createWpa3PersonalParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_SAE;

        params.mAllowedKeyManagement.set(KeyMgmt.SAE);

        params.mAllowedProtocols.set(Protocol.RSN);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.CCMP);
        params.mAllowedPairwiseCiphers.set(PairwiseCipher.GCMP_128);
        params.mAllowedPairwiseCiphers.set(PairwiseCipher.GCMP_256);

        params.mAllowedGroupCiphers.set(GroupCipher.CCMP);
        params.mAllowedGroupCiphers.set(GroupCipher.GCMP_128);
        params.mAllowedGroupCiphers.set(GroupCipher.GCMP_256);

        params.mRequirePmf = true;
        return params;
    }

    /**
     * Create WPA/WPA2 Personal params.
     */
    private static @NonNull SecurityParams createWpaWpa2PersonalParams() {
        SecurityParams params = new SecurityParams();
        params.mSecurityType = WifiConfiguration.SECURITY_TYPE_PSK;

        params.mAllowedKeyManagement.set(KeyMgmt.WPA_PSK);

        params.mAllowedProtocols.set(Protocol.RSN);
        params.mAllowedProtocols.set(Protocol.WPA);

        params.mAllowedPairwiseCiphers.set(PairwiseCipher.CCMP);
        params.mAllowedPairwiseCiphers.set(PairwiseCipher.TKIP);

        params.mAllowedGroupCiphers.set(GroupCipher.CCMP);
        params.mAllowedGroupCiphers.set(GroupCipher.TKIP);
        params.mAllowedGroupCiphers.set(GroupCipher.WEP40);
        params.mAllowedGroupCiphers.set(GroupCipher.WEP104);
        return params;
    }
}
