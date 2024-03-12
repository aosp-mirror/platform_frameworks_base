/**
 * Copyright (C) 2015 The Android Open Source Project
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

package android.hardware.radio;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * The RadioManager class allows to control a broadcast radio tuner present on the device.
 * It provides data structures and methods to query for available radio modules, list their
 * properties and open an interface to control tuning operations and receive callbacks when
 * asynchronous operations complete or events occur.
 * @hide
 */
@SystemApi
@SystemService(Context.RADIO_SERVICE)
@RequiresFeature(PackageManager.FEATURE_BROADCAST_RADIO)
public class RadioManager {
    private static final String TAG = "BroadcastRadio.manager";

    /** Method return status: successful operation */
    public static final int STATUS_OK = 0;
    /** Method return status: unspecified error */
    public static final int STATUS_ERROR = Integer.MIN_VALUE;
    /** Method return status: permission denied */
    public static final int STATUS_PERMISSION_DENIED = -1;
    /** Method return status: initialization failure */
    public static final int STATUS_NO_INIT = -19;
    /** Method return status: invalid argument provided */
    public static final int STATUS_BAD_VALUE = -22;
    /** Method return status: cannot reach service */
    public static final int STATUS_DEAD_OBJECT = -32;
    /** Method return status: invalid or out of sequence operation */
    public static final int STATUS_INVALID_OPERATION = -38;
    /** Method return status: time out before operation completion */
    public static final int STATUS_TIMED_OUT = -110;

    /**
     *  Radio operation status types
     *
     * @hide
     */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_OK,
            STATUS_ERROR,
            STATUS_PERMISSION_DENIED,
            STATUS_NO_INIT,
            STATUS_BAD_VALUE,
            STATUS_DEAD_OBJECT,
            STATUS_INVALID_OPERATION,
            STATUS_TIMED_OUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RadioStatusType{}


    // keep in sync with radio_class_t in /system/core/incluse/system/radio.h
    /** Radio module class supporting FM (including HD radio) and AM */
    public static final int CLASS_AM_FM = 0;
    /** Radio module class supporting satellite radio */
    public static final int CLASS_SAT = 1;
    /** Radio module class supporting Digital terrestrial radio */
    public static final int CLASS_DT = 2;

    public static final int BAND_INVALID = -1;
    /** AM radio band (LW/MW/SW).
     * @see BandDescriptor */
    public static final int BAND_AM = 0;
    /** FM radio band.
     * @see BandDescriptor */
    public static final int BAND_FM = 1;
    /** FM HD radio or DRM  band.
     * @see BandDescriptor */
    public static final int BAND_FM_HD = 2;
    /** AM HD radio or DRM band.
     * @see BandDescriptor */
    public static final int BAND_AM_HD = 3;
    /** @removed mistakenly exposed previously */
    @IntDef(prefix = { "BAND_" }, value = {
        BAND_INVALID,
        BAND_AM,
        BAND_FM,
        BAND_AM_HD,
        BAND_FM_HD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Band {}

    // keep in sync with radio_region_t in /system/core/incluse/system/radio.h
    /** Africa, Europe.
     * @see BandDescriptor */
    public static final int REGION_ITU_1  = 0;
    /** Americas.
     * @see BandDescriptor */
    public static final int REGION_ITU_2  = 1;
    /** Russia.
     * @see BandDescriptor */
    public static final int REGION_OIRT   = 2;
    /** Japan.
     * @see BandDescriptor */
    public static final int REGION_JAPAN  = 3;
    /** Korea.
     * @see BandDescriptor */
    public static final int REGION_KOREA  = 4;

    /**
     * Forces mono audio stream reception.
     *
     * Analog broadcasts can recover poor reception conditions by jointing
     * stereo channels into one. Mainly for, but not limited to AM/FM.
     */
    public static final int CONFIG_FORCE_MONO = 1;
    /**
     * Forces the analog playback for the supporting radio technology.
     *
     * <p>User may disable digital playback for FM HD Radio or hybrid FM/DAB with
     * this option. This is purely user choice, i.e. does not reflect digital-
     * analog handover state managed from the HAL implementation side.
     *
     * <p>Some radio technologies may not support this, i.e. DAB.
     *
     * @deprecated Use {@link #CONFIG_FORCE_ANALOG_FM} instead. If {@link #CONFIG_FORCE_ANALOG_FM}
     * is supported in HAL, {@link RadioTuner#setConfigFlag} and {@link RadioTuner#isConfigFlagSet}
     * with CONFIG_FORCE_ANALOG will set/get the value of {@link #CONFIG_FORCE_ANALOG_FM}.
     */
    @Deprecated
    public static final int CONFIG_FORCE_ANALOG = 2;
    /**
     * Forces the digital playback for the supporting radio technology.
     *
     * User may disable digital-analog handover that happens with poor
     * reception conditions. With digital forced, the radio will remain silent
     * instead of switching to analog channel if it's available. This is purely
     * user choice, it does not reflect the actual state of handover.
     */
    public static final int CONFIG_FORCE_DIGITAL = 3;
    /**
     * RDS Alternative Frequencies.
     *
     * If set and the currently tuned RDS station broadcasts on multiple
     * channels, radio tuner automatically switches to the best available
     * alternative.
     */
    public static final int CONFIG_RDS_AF = 4;
    /**
     * RDS region-specific program lock-down.
     *
     * Allows user to lock to the current region as they move into the
     * other region.
     */
    public static final int CONFIG_RDS_REG = 5;
    /** Enables DAB-DAB hard- and implicit-linking (the same content). */
    public static final int CONFIG_DAB_DAB_LINKING = 6;
    /** Enables DAB-FM hard- and implicit-linking (the same content). */
    public static final int CONFIG_DAB_FM_LINKING = 7;
    /** Enables DAB-DAB soft-linking (related content). */
    public static final int CONFIG_DAB_DAB_SOFT_LINKING = 8;
    /** Enables DAB-FM soft-linking (related content). */
    public static final int CONFIG_DAB_FM_SOFT_LINKING = 9;

    /**
     * Forces the FM analog playback for the supporting radio technology.
     *
     * <p>User may disable FM digital playback for FM HD Radio or hybrid FM/DAB
     * with this option. This is purely user choice, i.e. does not reflect
     * digital-analog handover state managed from the HAL implementation side.
     *
     * <p>Some radio technologies may not support this, i.e. DAB.
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int CONFIG_FORCE_ANALOG_FM = 10;

    /**
     * Forces the AM analog playback for the supporting radio technology.
     *
     * <p>User may disable FM digital playback for AM HD Radio or hybrid AM/DAB
     * with this option. This is purely user choice, i.e. does not reflect
     * digital-analog handover state managed from the HAL implementation side.
     *
     * <p>Some radio technologies may not support this, i.e. DAB.
     */
    @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
    public static final int CONFIG_FORCE_ANALOG_AM = 11;

    /** @hide */
    @IntDef(prefix = { "CONFIG_" }, value = {
        CONFIG_FORCE_MONO,
        CONFIG_FORCE_ANALOG,
        CONFIG_FORCE_DIGITAL,
        CONFIG_RDS_AF,
        CONFIG_RDS_REG,
        CONFIG_DAB_DAB_LINKING,
        CONFIG_DAB_FM_LINKING,
        CONFIG_DAB_DAB_SOFT_LINKING,
        CONFIG_DAB_FM_SOFT_LINKING,
        CONFIG_FORCE_ANALOG_FM,
        CONFIG_FORCE_ANALOG_AM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConfigFlag {}

    /*****************************************************************************
     * Lists properties, options and radio bands supported by a given broadcast radio module.
     * Each module has a unique ID used to address it when calling RadioManager APIs.
     * Module properties are returned by {@link #listModules(List <ModuleProperties>)} method.
     ****************************************************************************/
    public static class ModuleProperties implements Parcelable {

        private final int mId;
        @NonNull private final String mServiceName;
        private final int mClassId;
        private final String mImplementor;
        private final String mProduct;
        private final String mVersion;
        private final String mSerial;
        private final int mNumTuners;
        private final int mNumAudioSources;
        private final boolean mIsInitializationRequired;
        private final boolean mIsCaptureSupported;
        private final BandDescriptor[] mBands;
        private final boolean mIsBgScanSupported;
        private final Set<Integer> mSupportedProgramTypes;
        private final Set<Integer> mSupportedIdentifierTypes;
        @Nullable private final Map<String, Integer> mDabFrequencyTable;
        @NonNull private final Map<String, String> mVendorInfo;

        /** @hide */
        public ModuleProperties(int id, String serviceName, int classId, String implementor,
                String product, String version, String serial, int numTuners, int numAudioSources,
                boolean isInitializationRequired, boolean isCaptureSupported,
                BandDescriptor[] bands, boolean isBgScanSupported,
                @ProgramSelector.ProgramType int[] supportedProgramTypes,
                @ProgramSelector.IdentifierType int[] supportedIdentifierTypes,
                @Nullable Map<String, Integer> dabFrequencyTable,
                Map<String, String> vendorInfo) {
            mId = id;
            mServiceName = TextUtils.isEmpty(serviceName) ? "default" : serviceName;
            mClassId = classId;
            mImplementor = implementor;
            mProduct = product;
            mVersion = version;
            mSerial = serial;
            mNumTuners = numTuners;
            mNumAudioSources = numAudioSources;
            mIsInitializationRequired = isInitializationRequired;
            mIsCaptureSupported = isCaptureSupported;
            mBands = bands;
            mIsBgScanSupported = isBgScanSupported;
            mSupportedProgramTypes = arrayToSet(supportedProgramTypes);
            mSupportedIdentifierTypes = arrayToSet(supportedIdentifierTypes);
            if (dabFrequencyTable != null) {
                for (Map.Entry<String, Integer> entry : dabFrequencyTable.entrySet()) {
                    Objects.requireNonNull(entry.getKey());
                    Objects.requireNonNull(entry.getValue());
                }
            }
            mDabFrequencyTable = (dabFrequencyTable == null || dabFrequencyTable.isEmpty())
                    ? null : dabFrequencyTable;
            mVendorInfo = (vendorInfo == null) ? new HashMap<>() : vendorInfo;
        }

        private static Set<Integer> arrayToSet(int[] arr) {
            return Arrays.stream(arr).boxed().collect(Collectors.toSet());
        }

        private static int[] setToArray(Set<Integer> set) {
            return set.stream().mapToInt(Integer::intValue).toArray();
        }

        /** Unique module identifier provided by the native service.
         * For use with {@link #openTuner(int, BandConfig, boolean, Callback, Handler)}.
         * @return the radio module unique identifier.
         */
        public int getId() {
            return mId;
        }

        /**
         * Module service (driver) name as registered with HIDL.
         * @return the module service name.
         */
        public @NonNull String getServiceName() {
            return mServiceName;
        }

        /** Module class identifier: {@link #CLASS_AM_FM}, {@link #CLASS_SAT}, {@link #CLASS_DT}
         * @return the radio module class identifier.
         */
        public int getClassId() {
            return mClassId;
        }

        /** Human readable broadcast radio module implementor
         * @return the name of the radio module implementator.
         */
        public String getImplementor() {
            return mImplementor;
        }

        /** Human readable broadcast radio module product name
         * @return the radio module product name.
         */
        public String getProduct() {
            return mProduct;
        }

        /** Human readable broadcast radio module version number
         * @return the radio module version.
         */
        public String getVersion() {
            return mVersion;
        }

        /** Radio module serial number.
         * Can be used for subscription services.
         * @return the radio module serial number.
         */
        public String getSerial() {
            return mSerial;
        }

        /** Number of tuners available.
         * This is the number of tuners that can be open simultaneously.
         * @return the number of tuners supported.
         */
        public int getNumTuners() {
            return mNumTuners;
        }

        /** Number tuner audio sources available. Must be less or equal to getNumTuners().
         * When more than one tuner is supported, one is usually for playback and has one
         * associated audio source and the other is for pre scanning and building a
         * program list.
         * @return the number of audio sources available.
         */
        @RadioStatusType
        public int getNumAudioSources() {
            return mNumAudioSources;
        }

        /**
         * Checks, if BandConfig initialization (after {@link RadioManager#openTuner})
         * is required to be done before other operations or not.
         *
         * If it is, the client has to wait for {@link RadioTuner.Callback#onConfigurationChanged}
         * callback before executing any other operations. Otherwise, such operation will fail
         * returning {@link RadioManager#STATUS_INVALID_OPERATION} error code.
         */
        public boolean isInitializationRequired() {
            return mIsInitializationRequired;
        }

        /** {@code true} if audio capture is possible from radio tuner output.
         * This indicates if routing to audio devices not connected to the same HAL as the FM radio
         * is possible (e.g. to USB) or DAR (Digital Audio Recorder) feature can be implemented.
         * @return {@code true} if audio capture is possible, {@code false} otherwise.
         */
        public boolean isCaptureSupported() {
            return mIsCaptureSupported;
        }

        /**
         * {@code true} if the module supports background scanning. At the given time it may not
         * be available though, see {@link RadioTuner#startBackgroundScan()}.
         *
         * @return {@code true} if background scanning is supported (not necessary available
         * at a given time), {@code false} otherwise.
         */
        public boolean isBackgroundScanningSupported() {
            return mIsBgScanSupported;
        }

        /**
         * Checks, if a given program type is supported by this tuner.
         *
         * If a program type is supported by radio module, it means it can tune
         * to ProgramSelector of a given type.
         *
         * @return {@code true} if a given program type is supported.
         */
        public boolean isProgramTypeSupported(@ProgramSelector.ProgramType int type) {
            return mSupportedProgramTypes.contains(type);
        }

        /**
         * Checks, if a given program identifier is supported by this tuner.
         *
         * If an identifier is supported by radio module, it means it can use it for
         * tuning to ProgramSelector with either primary or secondary Identifier of
         * a given type.
         *
         * @return {@code true} if a given program type is supported.
         */
        public boolean isProgramIdentifierSupported(@ProgramSelector.IdentifierType int type) {
            return mSupportedIdentifierTypes.contains(type);
        }

        /**
         * A frequency table for Digital Audio Broadcasting (DAB).
         *
         * The key is a channel name, i.e. 5A, 7B.
         *
         * The value is a frequency, in kHz.
         *
         * @return a frequency table, or {@code null} if the module doesn't support DAB
         */
        public @Nullable Map<String, Integer> getDabFrequencyTable() {
            return mDabFrequencyTable;
        }

        /**
         * A map of vendor-specific opaque strings, passed from HAL without changes.
         * Format of these strings can vary across vendors.
         *
         * It may be used for extra features, that's not supported by a platform,
         * for example: preset-slots=6; ultra-hd-capable=false.
         *
         * Keys must be prefixed with unique vendor Java-style namespace,
         * eg. 'com.somecompany.parameter1'.
         */
        public @NonNull Map<String, String> getVendorInfo() {
            return mVendorInfo;
        }

        /** List of descriptors for all bands supported by this module.
         * @return an array of {@link BandDescriptor}.
         */
        public BandDescriptor[] getBands() {
            return mBands;
        }

        private ModuleProperties(Parcel in) {
            mId = in.readInt();
            String serviceName = in.readString();
            mServiceName = TextUtils.isEmpty(serviceName) ? "default" : serviceName;
            mClassId = in.readInt();
            mImplementor = in.readString();
            mProduct = in.readString();
            mVersion = in.readString();
            mSerial = in.readString();
            mNumTuners = in.readInt();
            mNumAudioSources = in.readInt();
            mIsInitializationRequired = in.readInt() == 1;
            mIsCaptureSupported = in.readInt() == 1;
            Parcelable[] tmp = in.readParcelableArray(BandDescriptor.class.getClassLoader(),
                    BandDescriptor.class);
            mBands = new BandDescriptor[tmp.length];
            for (int i = 0; i < tmp.length; i++) {
                mBands[i] = (BandDescriptor) tmp[i];
            }
            mIsBgScanSupported = in.readInt() == 1;
            mSupportedProgramTypes = arrayToSet(in.createIntArray());
            mSupportedIdentifierTypes = arrayToSet(in.createIntArray());
            Map<String, Integer> dabFrequencyTableIn = Utils.readStringIntMap(in);
            mDabFrequencyTable = (dabFrequencyTableIn.isEmpty()) ? null : dabFrequencyTableIn;
            mVendorInfo = Utils.readStringMap(in);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<ModuleProperties> CREATOR
                = new Parcelable.Creator<ModuleProperties>() {
            public ModuleProperties createFromParcel(Parcel in) {
                return new ModuleProperties(in);
            }

            public ModuleProperties[] newArray(int size) {
                return new ModuleProperties[size];
            }
        };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mId);
            dest.writeString(mServiceName);
            dest.writeInt(mClassId);
            dest.writeString(mImplementor);
            dest.writeString(mProduct);
            dest.writeString(mVersion);
            dest.writeString(mSerial);
            dest.writeInt(mNumTuners);
            dest.writeInt(mNumAudioSources);
            dest.writeInt(mIsInitializationRequired ? 1 : 0);
            dest.writeInt(mIsCaptureSupported ? 1 : 0);
            dest.writeParcelableArray(mBands, flags);
            dest.writeInt(mIsBgScanSupported ? 1 : 0);
            dest.writeIntArray(setToArray(mSupportedProgramTypes));
            dest.writeIntArray(setToArray(mSupportedIdentifierTypes));
            Utils.writeStringIntMap(dest, mDabFrequencyTable);
            Utils.writeStringMap(dest, mVendorInfo);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        @Override
        public String toString() {
            return "ModuleProperties [mId=" + mId
                    + ", mServiceName=" + mServiceName + ", mClassId=" + mClassId
                    + ", mImplementor=" + mImplementor + ", mProduct=" + mProduct
                    + ", mVersion=" + mVersion + ", mSerial=" + mSerial
                    + ", mNumTuners=" + mNumTuners
                    + ", mNumAudioSources=" + mNumAudioSources
                    + ", mIsInitializationRequired=" + mIsInitializationRequired
                    + ", mIsCaptureSupported=" + mIsCaptureSupported
                    + ", mIsBgScanSupported=" + mIsBgScanSupported
                    + ", mBands=" + Arrays.toString(mBands) + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mId, mServiceName, mClassId, mImplementor, mProduct, mVersion,
                mSerial, mNumTuners, mNumAudioSources, mIsInitializationRequired,
                mIsCaptureSupported, Arrays.hashCode(mBands), mIsBgScanSupported,
                mDabFrequencyTable, mVendorInfo);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ModuleProperties)) return false;
            ModuleProperties other = (ModuleProperties) obj;

            if (mId != other.getId()) return false;
            if (!TextUtils.equals(mServiceName, other.mServiceName)) return false;
            if (mClassId != other.mClassId) return false;
            if (!Objects.equals(mImplementor, other.mImplementor)) return false;
            if (!Objects.equals(mProduct, other.mProduct)) return false;
            if (!Objects.equals(mVersion, other.mVersion)) return false;
            if (!Objects.equals(mSerial, other.mSerial)) return false;
            if (mNumTuners != other.mNumTuners) return false;
            if (mNumAudioSources != other.mNumAudioSources) return false;
            if (mIsInitializationRequired != other.mIsInitializationRequired) return false;
            if (mIsCaptureSupported != other.mIsCaptureSupported) return false;
            if (!Arrays.equals(mBands, other.mBands)) return false;
            if (mIsBgScanSupported != other.mIsBgScanSupported) return false;
            if (!Objects.equals(mDabFrequencyTable, other.mDabFrequencyTable)) return false;
            if (!Objects.equals(mVendorInfo, other.mVendorInfo)) return false;
            return true;
        }
    }

    /** Radio band descriptor: an element in ModuleProperties bands array.
     * It is either an instance of {@link FmBandDescriptor} or {@link AmBandDescriptor} */
    public static class BandDescriptor implements Parcelable {

        private final int mRegion;
        private final int mType;
        private final int mLowerLimit;
        private final int mUpperLimit;
        private final int mSpacing;

        BandDescriptor(int region, int type, int lowerLimit, int upperLimit, int spacing) {
            if (type != BAND_AM && type != BAND_FM && type != BAND_FM_HD && type != BAND_AM_HD) {
                throw new IllegalArgumentException("Unsupported band: " + type);
            }
            mRegion = region;
            mType = type;
            mLowerLimit = lowerLimit;
            mUpperLimit = upperLimit;
            mSpacing = spacing;
        }

        /** Region this band applies to. E.g. {@link #REGION_ITU_1}
         * @return the region this band is associated to.
         */
        public int getRegion() {
            return mRegion;
        }
        /** Band type, e.g {@link #BAND_FM}. Defines the subclass this descriptor can be cast to:
         * <ul>
         *  <li>{@link #BAND_FM} or {@link #BAND_FM_HD} cast to {@link FmBandDescriptor}, </li>
         *  <li>{@link #BAND_AM} cast to {@link AmBandDescriptor}, </li>
         * </ul>
         * @return the band type.
         */
        public int getType() {
            return mType;
        }

        /**
         * Checks if the band is either AM or AM_HD.
         *
         * @return {@code true}, if band is AM or AM_HD.
         */
        public boolean isAmBand() {
            return mType == BAND_AM || mType == BAND_AM_HD;
        }

        /**
         * Checks if the band is either FM or FM_HD.
         *
         * @return {@code true}, if band is FM or FM_HD.
         */
        public boolean isFmBand() {
            return mType == BAND_FM || mType == BAND_FM_HD;
        }

        /** Lower band limit expressed in units according to band type.
         * Currently all defined band types express channels as frequency in kHz
         * @return the lower band limit.
         */
        public int getLowerLimit() {
            return mLowerLimit;
        }
        /** Upper band limit expressed in units according to band type.
         * Currently all defined band types express channels as frequency in kHz
         * @return the upper band limit.
         */
        public int getUpperLimit() {
            return mUpperLimit;
        }
        /** Channel spacing in units according to band type.
         * Currently all defined band types express channels as frequency in kHz
         * @return the channel spacing.
         */
        public int getSpacing() {
            return mSpacing;
        }

        private BandDescriptor(Parcel in) {
            mRegion = in.readInt();
            mType = in.readInt();
            mLowerLimit = in.readInt();
            mUpperLimit = in.readInt();
            mSpacing = in.readInt();
        }

        private static int lookupTypeFromParcel(Parcel in) {
            int pos = in.dataPosition();
            in.readInt();  // skip region
            int type = in.readInt();
            in.setDataPosition(pos);
            return type;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<BandDescriptor> CREATOR
                = new Parcelable.Creator<BandDescriptor>() {
            public BandDescriptor createFromParcel(Parcel in) {
                int type = lookupTypeFromParcel(in);
                switch (type) {
                    case BAND_FM:
                    case BAND_FM_HD:
                        return new FmBandDescriptor(in);
                    case BAND_AM:
                    case BAND_AM_HD:
                        return new AmBandDescriptor(in);
                    default:
                        throw new IllegalArgumentException("Unsupported band: " + type);
                }
            }

            public BandDescriptor[] newArray(int size) {
                return new BandDescriptor[size];
            }
        };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mRegion);
            dest.writeInt(mType);
            dest.writeInt(mLowerLimit);
            dest.writeInt(mUpperLimit);
            dest.writeInt(mSpacing);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        @Override
        public String toString() {
            return "BandDescriptor [mRegion=" + mRegion + ", mType=" + mType + ", mLowerLimit="
                    + mLowerLimit + ", mUpperLimit=" + mUpperLimit + ", mSpacing=" + mSpacing + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + mRegion;
            result = prime * result + mType;
            result = prime * result + mLowerLimit;
            result = prime * result + mUpperLimit;
            result = prime * result + mSpacing;
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof BandDescriptor))
                return false;
            BandDescriptor other = (BandDescriptor) obj;
            if (mRegion != other.getRegion())
                return false;
            if (mType != other.getType())
                return false;
            if (mLowerLimit != other.getLowerLimit())
                return false;
            if (mUpperLimit != other.getUpperLimit())
                return false;
            if (mSpacing != other.getSpacing())
                return false;
            return true;
        }
    }

    /** FM band descriptor
     * @see #BAND_FM
     * @see #BAND_FM_HD */
    public static class FmBandDescriptor extends BandDescriptor {
        private final boolean mStereo;
        private final boolean mRds;
        private final boolean mTa;
        private final boolean mAf;
        private final boolean mEa;

        /** @hide */
        public FmBandDescriptor(int region, int type, int lowerLimit, int upperLimit, int spacing,
                boolean stereo, boolean rds, boolean ta, boolean af, boolean ea) {
            super(region, type, lowerLimit, upperLimit, spacing);
            mStereo = stereo;
            mRds = rds;
            mTa = ta;
            mAf = af;
            mEa = ea;
        }

        /** Stereo is supported
         * @return {@code true} if stereo is supported, {@code false} otherwise.
         */
        public boolean isStereoSupported() {
            return mStereo;
        }
        /** RDS or RBDS(if region is ITU2) is supported
         * @return {@code true} if RDS or RBDS is supported, {@code false} otherwise.
         */
        public boolean isRdsSupported() {
            return mRds;
        }
        /** Traffic announcement is supported
         * @return {@code true} if TA is supported, {@code false} otherwise.
         */
        public boolean isTaSupported() {
            return mTa;
        }
        /** Alternate Frequency Switching is supported
         * @return {@code true} if AF switching is supported, {@code false} otherwise.
         */
        public boolean isAfSupported() {
            return mAf;
        }

        /** Emergency Announcement is supported
         * @return {@code true} if Emergency annoucement is supported, {@code false} otherwise.
         */
        public boolean isEaSupported() {
            return mEa;
        }

        /* Parcelable implementation */
        private FmBandDescriptor(Parcel in) {
            super(in);
            mStereo = in.readByte() == 1;
            mRds = in.readByte() == 1;
            mTa = in.readByte() == 1;
            mAf = in.readByte() == 1;
            mEa = in.readByte() == 1;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<FmBandDescriptor> CREATOR
                = new Parcelable.Creator<FmBandDescriptor>() {
            public FmBandDescriptor createFromParcel(Parcel in) {
                return new FmBandDescriptor(in);
            }

            public FmBandDescriptor[] newArray(int size) {
                return new FmBandDescriptor[size];
            }
        };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeByte((byte) (mStereo ? 1 : 0));
            dest.writeByte((byte) (mRds ? 1 : 0));
            dest.writeByte((byte) (mTa ? 1 : 0));
            dest.writeByte((byte) (mAf ? 1 : 0));
            dest.writeByte((byte) (mEa ? 1 : 0));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        @Override
        public String toString() {
            return "FmBandDescriptor [ "+ super.toString() + " mStereo=" + mStereo
                    + ", mRds=" + mRds + ", mTa=" + mTa + ", mAf=" + mAf +
                    ", mEa =" + mEa + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + (mStereo ? 1 : 0);
            result = prime * result + (mRds ? 1 : 0);
            result = prime * result + (mTa ? 1 : 0);
            result = prime * result + (mAf ? 1 : 0);
            result = prime * result + (mEa ? 1 : 0);
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj)
                return true;
            if (!super.equals(obj))
                return false;
            if (!(obj instanceof FmBandDescriptor))
                return false;
            FmBandDescriptor other = (FmBandDescriptor) obj;
            if (mStereo != other.isStereoSupported())
                return false;
            if (mRds != other.isRdsSupported())
                return false;
            if (mTa != other.isTaSupported())
                return false;
            if (mAf != other.isAfSupported())
                return false;
            if (mEa != other.isEaSupported())
                return false;
            return true;
        }
    }

    /** AM band descriptor.
     * @see #BAND_AM */
    public static class AmBandDescriptor extends BandDescriptor {

        private final boolean mStereo;

        /** @hide */
        public AmBandDescriptor(int region, int type, int lowerLimit, int upperLimit, int spacing,
                boolean stereo) {
            super(region, type, lowerLimit, upperLimit, spacing);
            mStereo = stereo;
        }

        /** Stereo is supported
         *  @return {@code true} if stereo is supported, {@code false} otherwise.
         */
        public boolean isStereoSupported() {
            return mStereo;
        }

        private AmBandDescriptor(Parcel in) {
            super(in);
            mStereo = in.readByte() == 1;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<AmBandDescriptor> CREATOR
                = new Parcelable.Creator<AmBandDescriptor>() {
            public AmBandDescriptor createFromParcel(Parcel in) {
                return new AmBandDescriptor(in);
            }

            public AmBandDescriptor[] newArray(int size) {
                return new AmBandDescriptor[size];
            }
        };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeByte((byte) (mStereo ? 1 : 0));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        @Override
        public String toString() {
            return "AmBandDescriptor [ "+ super.toString() + " mStereo=" + mStereo + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + (mStereo ? 1 : 0);
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj)
                return true;
            if (!super.equals(obj))
                return false;
            if (!(obj instanceof AmBandDescriptor))
                return false;
            AmBandDescriptor other = (AmBandDescriptor) obj;
            if (mStereo != other.isStereoSupported())
                return false;
            return true;
        }
    }


    /** Radio band configuration. */
    public static class BandConfig implements Parcelable {

        @NonNull final BandDescriptor mDescriptor;

        BandConfig(BandDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "Descriptor cannot be null");
            mDescriptor = new BandDescriptor(descriptor.getRegion(), descriptor.getType(),
                    descriptor.getLowerLimit(), descriptor.getUpperLimit(),
                    descriptor.getSpacing());
        }

        BandConfig(int region, int type, int lowerLimit, int upperLimit, int spacing) {
            mDescriptor = new BandDescriptor(region, type, lowerLimit, upperLimit, spacing);
        }

        private BandConfig(Parcel in) {
            mDescriptor = new BandDescriptor(in);
        }

        BandDescriptor getDescriptor() {
            return mDescriptor;
        }

        /** Region this band applies to. E.g. {@link #REGION_ITU_1}
         *  @return the region associated with this band.
         */
        public int getRegion() {
            return mDescriptor.getRegion();
        }
        /** Band type, e.g {@link #BAND_FM}. Defines the subclass this descriptor can be cast to:
         * <ul>
         *  <li>{@link #BAND_FM} or {@link #BAND_FM_HD} cast to {@link FmBandDescriptor}, </li>
         *  <li>{@link #BAND_AM} cast to {@link AmBandDescriptor}, </li>
         * </ul>
         *  @return the band type.
         */
        public int getType() {
            return mDescriptor.getType();
        }
        /** Lower band limit expressed in units according to band type.
         * Currently all defined band types express channels as frequency in kHz
         *  @return the lower band limit.
         */
        public int getLowerLimit() {
            return mDescriptor.getLowerLimit();
        }
        /** Upper band limit expressed in units according to band type.
         * Currently all defined band types express channels as frequency in kHz
         *  @return the upper band limit.
         */
        public int getUpperLimit() {
            return mDescriptor.getUpperLimit();
        }
        /** Channel spacing in units according to band type.
         * Currently all defined band types express channels as frequency in kHz
         *  @return the channel spacing.
         */
        public int getSpacing() {
            return mDescriptor.getSpacing();
        }


        public static final @android.annotation.NonNull Parcelable.Creator<BandConfig> CREATOR
                = new Parcelable.Creator<BandConfig>() {
            public BandConfig createFromParcel(Parcel in) {
                int type = BandDescriptor.lookupTypeFromParcel(in);
                switch (type) {
                    case BAND_FM:
                    case BAND_FM_HD:
                        return new FmBandConfig(in);
                    case BAND_AM:
                    case BAND_AM_HD:
                        return new AmBandConfig(in);
                    default:
                        throw new IllegalArgumentException("Unsupported band: " + type);
                }
            }

            public BandConfig[] newArray(int size) {
                return new BandConfig[size];
            }
        };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mDescriptor.writeToParcel(dest, flags);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        @Override
        public String toString() {
            return "BandConfig [ " + mDescriptor.toString() + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + mDescriptor.hashCode();
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof BandConfig))
                return false;
            BandConfig other = (BandConfig) obj;
            BandDescriptor otherDesc = other.getDescriptor();
            if ((mDescriptor == null) != (otherDesc == null)) return false;
            if (mDescriptor != null && !mDescriptor.equals(otherDesc)) return false;
            return true;
        }
    }

    /** FM band configuration.
     * @see #BAND_FM
     * @see #BAND_FM_HD */
    public static class FmBandConfig extends BandConfig {
        private final boolean mStereo;
        private final boolean mRds;
        private final boolean mTa;
        private final boolean mAf;
        private final boolean mEa;

        /** @hide */
        public FmBandConfig(FmBandDescriptor descriptor) {
            super((BandDescriptor)descriptor);
            mStereo = descriptor.isStereoSupported();
            mRds = descriptor.isRdsSupported();
            mTa = descriptor.isTaSupported();
            mAf = descriptor.isAfSupported();
            mEa = descriptor.isEaSupported();
        }

        FmBandConfig(int region, int type, int lowerLimit, int upperLimit, int spacing,
                boolean stereo, boolean rds, boolean ta, boolean af, boolean ea) {
            super(region, type, lowerLimit, upperLimit, spacing);
            mStereo = stereo;
            mRds = rds;
            mTa = ta;
            mAf = af;
            mEa = ea;
        }

        /** Get stereo enable state
         * @return the enable state.
         */
        public boolean getStereo() {
            return mStereo;
        }

        /** Get RDS or RBDS(if region is ITU2) enable state
         * @return the enable state.
         */
        public boolean getRds() {
            return mRds;
        }

        /** Get Traffic announcement enable state
         * @return the enable state.
         */
        public boolean getTa() {
            return mTa;
        }

        /** Get Alternate Frequency Switching enable state
         * @return the enable state.
         */
        public boolean getAf() {
            return mAf;
        }

        /**
         * Get Emergency announcement enable state
         * @return the enable state.
         */
        public boolean getEa() {
            return mEa;
        }

        private FmBandConfig(Parcel in) {
            super(in);
            mStereo = in.readByte() == 1;
            mRds = in.readByte() == 1;
            mTa = in.readByte() == 1;
            mAf = in.readByte() == 1;
            mEa = in.readByte() == 1;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<FmBandConfig> CREATOR
                = new Parcelable.Creator<FmBandConfig>() {
            public FmBandConfig createFromParcel(Parcel in) {
                return new FmBandConfig(in);
            }

            public FmBandConfig[] newArray(int size) {
                return new FmBandConfig[size];
            }
        };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeByte((byte) (mStereo ? 1 : 0));
            dest.writeByte((byte) (mRds ? 1 : 0));
            dest.writeByte((byte) (mTa ? 1 : 0));
            dest.writeByte((byte) (mAf ? 1 : 0));
            dest.writeByte((byte) (mEa ? 1 : 0));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        @Override
        public String toString() {
            return "FmBandConfig [" + super.toString()
                    + ", mStereo=" + mStereo + ", mRds=" + mRds + ", mTa=" + mTa
                    + ", mAf=" + mAf + ", mEa =" + mEa + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + (mStereo ? 1 : 0);
            result = prime * result + (mRds ? 1 : 0);
            result = prime * result + (mTa ? 1 : 0);
            result = prime * result + (mAf ? 1 : 0);
            result = prime * result + (mEa ? 1 : 0);
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj)
                return true;
            if (!super.equals(obj))
                return false;
            if (!(obj instanceof FmBandConfig))
                return false;
            FmBandConfig other = (FmBandConfig) obj;
            if (mStereo != other.mStereo)
                return false;
            if (mRds != other.mRds)
                return false;
            if (mTa != other.mTa)
                return false;
            if (mAf != other.mAf)
                return false;
            if (mEa != other.mEa)
                return false;
            return true;
        }

        /**
         * Builder class for {@link FmBandConfig} objects.
         */
        public static class Builder {
            private final BandDescriptor mDescriptor;
            private boolean mStereo;
            private boolean mRds;
            private boolean mTa;
            private boolean mAf;
            private boolean mEa;

            /**
             * Constructs a new Builder with the defaults from an {@link FmBandDescriptor} .
             * @param descriptor the FmBandDescriptor defaults are read from .
             */
            public Builder(FmBandDescriptor descriptor) {
                mDescriptor = new BandDescriptor(descriptor.getRegion(), descriptor.getType(),
                        descriptor.getLowerLimit(), descriptor.getUpperLimit(),
                        descriptor.getSpacing());
                mStereo = descriptor.isStereoSupported();
                mRds = descriptor.isRdsSupported();
                mTa = descriptor.isTaSupported();
                mAf = descriptor.isAfSupported();
                mEa = descriptor.isEaSupported();
            }

            /**
             * Constructs a new Builder from a given {@link FmBandConfig}
             * @param config the FmBandConfig object whose data will be reused in the new Builder.
             */
            public Builder(FmBandConfig config) {
                mDescriptor = new BandDescriptor(config.getRegion(), config.getType(),
                        config.getLowerLimit(), config.getUpperLimit(), config.getSpacing());
                mStereo = config.getStereo();
                mRds = config.getRds();
                mTa = config.getTa();
                mAf = config.getAf();
                mEa = config.getEa();
            }

            /**
             * Combines all of the parameters that have been set and return a new
             * {@link FmBandConfig} object.
             * @return a new {@link FmBandConfig} object
             */
            public FmBandConfig build() {
                FmBandConfig config = new FmBandConfig(mDescriptor.getRegion(),
                        mDescriptor.getType(), mDescriptor.getLowerLimit(),
                        mDescriptor.getUpperLimit(), mDescriptor.getSpacing(),
                        mStereo, mRds, mTa, mAf, mEa);
                return config;
            }

            /** Set stereo enable state
             * @param state The new enable state.
             * @return the same Builder instance.
             */
            public Builder setStereo(boolean state) {
                mStereo = state;
                return this;
            }

            /** Set RDS or RBDS(if region is ITU2) enable state
             * @param state The new enable state.
             * @return the same Builder instance.
             */
            public Builder setRds(boolean state) {
                mRds = state;
                return this;
            }

            /** Set Traffic announcement enable state
             * @param state The new enable state.
             * @return the same Builder instance.
             */
            public Builder setTa(boolean state) {
                mTa = state;
                return this;
            }

            /** Set Alternate Frequency Switching enable state
             * @param state The new enable state.
             * @return the same Builder instance.
             */
            public Builder setAf(boolean state) {
                mAf = state;
                return this;
            }

            /** Set Emergency Announcement enable state
             * @param state The new enable state.
             * @return the same Builder instance.
             */
            public Builder setEa(boolean state) {
                mEa = state;
                return this;
            }
        };
    }

    /** AM band configuration.
     * @see #BAND_AM */
    public static class AmBandConfig extends BandConfig {
        private final boolean mStereo;

        /** @hide */
        public AmBandConfig(AmBandDescriptor descriptor) {
            super((BandDescriptor)descriptor);
            mStereo = descriptor.isStereoSupported();
        }

        AmBandConfig(int region, int type, int lowerLimit, int upperLimit, int spacing,
                boolean stereo) {
            super(region, type, lowerLimit, upperLimit, spacing);
            mStereo = stereo;
        }

        /** Get stereo enable state
         * @return the enable state.
         */
        public boolean getStereo() {
            return mStereo;
        }

        private AmBandConfig(Parcel in) {
            super(in);
            mStereo = in.readByte() == 1;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<AmBandConfig> CREATOR
                = new Parcelable.Creator<AmBandConfig>() {
            public AmBandConfig createFromParcel(Parcel in) {
                return new AmBandConfig(in);
            }

            public AmBandConfig[] newArray(int size) {
                return new AmBandConfig[size];
            }
        };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeByte((byte) (mStereo ? 1 : 0));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        @Override
        public String toString() {
            return "AmBandConfig [" + super.toString()
                    + ", mStereo=" + mStereo + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + (mStereo ? 1 : 0);
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj)
                return true;
            if (!super.equals(obj))
                return false;
            if (!(obj instanceof AmBandConfig))
                return false;
            AmBandConfig other = (AmBandConfig) obj;
            if (mStereo != other.getStereo())
                return false;
            return true;
        }

        /**
         * Builder class for {@link AmBandConfig} objects.
         */
        public static class Builder {
            private final BandDescriptor mDescriptor;
            private boolean mStereo;

            /**
             * Constructs a new Builder with the defaults from an {@link AmBandDescriptor} .
             * @param descriptor the FmBandDescriptor defaults are read from .
             */
            public Builder(AmBandDescriptor descriptor) {
                mDescriptor = new BandDescriptor(descriptor.getRegion(), descriptor.getType(),
                        descriptor.getLowerLimit(), descriptor.getUpperLimit(),
                        descriptor.getSpacing());
                mStereo = descriptor.isStereoSupported();
            }

            /**
             * Constructs a new Builder from a given {@link AmBandConfig}
             * @param config the FmBandConfig object whose data will be reused in the new Builder.
             */
            public Builder(AmBandConfig config) {
                mDescriptor = new BandDescriptor(config.getRegion(), config.getType(),
                        config.getLowerLimit(), config.getUpperLimit(), config.getSpacing());
                mStereo = config.getStereo();
            }

            /**
             * Combines all of the parameters that have been set and return a new
             * {@link AmBandConfig} object.
             * @return a new {@link AmBandConfig} object
             */
            public AmBandConfig build() {
                AmBandConfig config = new AmBandConfig(mDescriptor.getRegion(),
                        mDescriptor.getType(), mDescriptor.getLowerLimit(),
                        mDescriptor.getUpperLimit(), mDescriptor.getSpacing(),
                        mStereo);
                return config;
            }

            /** Set stereo enable state
             * @param state The new enable state.
             * @return the same Builder instance.
             */
            public Builder setStereo(boolean state) {
                mStereo = state;
                return this;
            }
        };
    }

    /** Radio program information. */
    public static class ProgramInfo implements Parcelable {

        // sourced from hardware/interfaces/broadcastradio/2.0/types.hal
        private static final int FLAG_LIVE = 1 << 0;
        private static final int FLAG_MUTED = 1 << 1;
        private static final int FLAG_TRAFFIC_PROGRAM = 1 << 2;
        private static final int FLAG_TRAFFIC_ANNOUNCEMENT = 1 << 3;
        private static final int FLAG_TUNED = 1 << 4;
        private static final int FLAG_STEREO = 1 << 5;
        private static final int FLAG_SIGNAL_ACQUIRED = 1 << 6;
        private static final int FLAG_HD_SIS_ACQUIRED = 1 << 7;
        private static final int FLAG_HD_AUDIO_ACQUIRED = 1 << 8;

        @NonNull private final ProgramSelector mSelector;
        @Nullable private final ProgramSelector.Identifier mLogicallyTunedTo;
        @Nullable private final ProgramSelector.Identifier mPhysicallyTunedTo;
        @NonNull private final Collection<ProgramSelector.Identifier> mRelatedContent;
        private final int mInfoFlags;
        private final int mSignalQuality;
        @Nullable private final RadioMetadata mMetadata;
        @NonNull private final Map<String, String> mVendorInfo;

        /** @hide */
        public ProgramInfo(@NonNull ProgramSelector selector,
                @Nullable ProgramSelector.Identifier logicallyTunedTo,
                @Nullable ProgramSelector.Identifier physicallyTunedTo,
                @Nullable Collection<ProgramSelector.Identifier> relatedContent,
                int infoFlags, int signalQuality, @Nullable RadioMetadata metadata,
                @Nullable Map<String, String> vendorInfo) {
            mSelector = Objects.requireNonNull(selector);
            mLogicallyTunedTo = logicallyTunedTo;
            mPhysicallyTunedTo = physicallyTunedTo;
            if (relatedContent == null) {
                mRelatedContent = Collections.emptyList();
            } else {
                Preconditions.checkCollectionElementsNotNull(relatedContent, "relatedContent");
                mRelatedContent = relatedContent;
            }
            mInfoFlags = infoFlags;
            mSignalQuality = signalQuality;
            mMetadata = metadata;
            mVendorInfo = (vendorInfo == null) ? new HashMap<>() : vendorInfo;
        }

        /**
         * Program selector, necessary for tuning to a program.
         *
         * @return the program selector.
         */
        public @NonNull ProgramSelector getSelector() {
            return mSelector;
        }

        /**
         * Identifier currently used for program selection.
         *
         * This identifier can be used to determine which technology is
         * currently being used for reception.
         *
         * Some program selectors contain tuning information for different radio
         * technologies (i.e. FM RDS and DAB). For example, user may tune using
         * a ProgramSelector with RDS_PI primary identifier, but the tuner hardware
         * may choose to use DAB technology to make actual tuning. This identifier
         * must reflect that.
         */
        public @Nullable ProgramSelector.Identifier getLogicallyTunedTo() {
            return mLogicallyTunedTo;
        }

        /**
         * Identifier currently used by hardware to physically tune to a channel.
         *
         * Some radio technologies broadcast the same program on multiple channels,
         * i.e. with RDS AF the same program may be broadcasted on multiple
         * alternative frequencies; the same DAB program may be broadcast on
         * multiple ensembles. This identifier points to the channel to which the
         * radio hardware is physically tuned to.
         */
        public @Nullable ProgramSelector.Identifier getPhysicallyTunedTo() {
            return mPhysicallyTunedTo;
        }

        /**
         * Primary identifiers of related contents.
         *
         * Some radio technologies provide pointers to other programs that carry
         * related content (i.e. DAB soft-links). This field is a list of pointers
         * to other programs on the program list.
         *
         * Please note, that these identifiers does not have to exist on the program
         * list - i.e. DAB tuner may provide information on FM RDS alternatives
         * despite not supporting FM RDS. If the system has multiple tuners, another
         * one may have it on its list.
         */
        public @Nullable Collection<ProgramSelector.Identifier> getRelatedContent() {
            return mRelatedContent;
        }

        /** Main channel expressed in units according to band type.
         * Currently all defined band types express channels as frequency in kHz
         * @return the program channel
         * @deprecated Use {@link getSelector()} instead.
         */
        @Deprecated
        public int getChannel() {
            try {
                return (int) mSelector.getFirstId(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY);
            } catch (IllegalArgumentException ex) {
                Log.w(TAG, "Not an AM/FM program");
                return 0;
            }
        }

        /** Sub channel ID. E.g 1 for HD radio HD1
         * @return the program sub channel
         * @deprecated Use {@link getSelector()} instead.
         */
        @Deprecated
        public int getSubChannel() {
            try {
                return (int) mSelector.getFirstId(
                        ProgramSelector.IDENTIFIER_TYPE_HD_SUBCHANNEL) + 1;
            } catch (IllegalArgumentException ex) {
                // this is a normal behavior for analog AM/FM selector
                return 0;
            }
        }

        /** {@code true} if the tuner is currently tuned on a valid station
         * @return {@code true} if currently tuned, {@code false} otherwise.
         */
        public boolean isTuned() {
            return (mInfoFlags & FLAG_TUNED) != 0;
        }

        /** {@code true} if the received program is stereo
         * @return {@code true} if stereo, {@code false} otherwise.
         */
        public boolean isStereo() {
            return (mInfoFlags & FLAG_STEREO) != 0;
        }

        /** {@code true} if the received program is digital (e.g HD radio)
         * @return {@code true} if digital, {@code false} otherwise.
         * @deprecated Use {@link getLogicallyTunedTo()} instead.
         */
        @Deprecated
        public boolean isDigital() {
            ProgramSelector.Identifier id = mLogicallyTunedTo;
            if (id == null) id = mSelector.getPrimaryId();

            int type = id.getType();
            return (type != ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY
                && type != ProgramSelector.IDENTIFIER_TYPE_RDS_PI);
        }

        /**
         * {@code true} if the program is currently playing live stream.
         * This may result in a slightly altered reception parameters,
         * usually targetted at reduced latency.
         */
        public boolean isLive() {
            return (mInfoFlags & FLAG_LIVE) != 0;
        }

        /**
         * {@code true} if radio stream is not playing, i.e. due to bad reception
         * conditions or buffering. In this state volume knob MAY be disabled to
         * prevent user increasing volume too much.
         * It does NOT mean the user has muted audio.
         */
        public boolean isMuted() {
            return (mInfoFlags & FLAG_MUTED) != 0;
        }

        /**
         * {@code true} if radio station transmits traffic information
         * regularily.
         */
        public boolean isTrafficProgram() {
            return (mInfoFlags & FLAG_TRAFFIC_PROGRAM) != 0;
        }

        /**
         * {@code true} if radio station transmits traffic information
         * at the very moment.
         */
        public boolean isTrafficAnnouncementActive() {
            return (mInfoFlags & FLAG_TRAFFIC_ANNOUNCEMENT) != 0;
        }

        /**
         * @return {@code true} if the signal has been acquired.
         */
        @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
        public boolean isSignalAcquired() {
            return (mInfoFlags & FLAG_SIGNAL_ACQUIRED) != 0;
        }
        /**
         * @return {@code true} if HD Station Information Service (SIS) information is available.
         */
        @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
        public boolean isHdSisAvailable() {
            return (mInfoFlags & FLAG_HD_SIS_ACQUIRED) != 0;
        }
        /**
         * @return {@code true} if HD audio is available.
         */
        @FlaggedApi(Flags.FLAG_HD_RADIO_IMPROVED)
        public boolean isHdAudioAvailable() {
            return (mInfoFlags & FLAG_HD_AUDIO_ACQUIRED) != 0;
        }

        /**
         * Signal quality (as opposed to the name) indication from 0 (no signal)
         * to 100 (excellent)
         * @return the signal quality indication.
         */
        public int getSignalStrength() {
            return mSignalQuality;
        }

        /** Metadata currently received from this station.
         * null if no metadata have been received
         * @return current meta data received from this program.
         */
        public RadioMetadata getMetadata() {
            return mMetadata;
        }

        /**
         * A map of vendor-specific opaque strings, passed from HAL without changes.
         * Format of these strings can vary across vendors.
         *
         * It may be used for extra features, that's not supported by a platform,
         * for example: paid-service=true; bitrate=320kbps.
         *
         * Keys must be prefixed with unique vendor Java-style namespace,
         * eg. 'com.somecompany.parameter1'.
         */
        public @NonNull Map<String, String> getVendorInfo() {
            return mVendorInfo;
        }

        private ProgramInfo(Parcel in) {
            mSelector = Objects.requireNonNull(in.readTypedObject(ProgramSelector.CREATOR));
            mLogicallyTunedTo = in.readTypedObject(ProgramSelector.Identifier.CREATOR);
            mPhysicallyTunedTo = in.readTypedObject(ProgramSelector.Identifier.CREATOR);
            mRelatedContent = in.createTypedArrayList(ProgramSelector.Identifier.CREATOR);
            mInfoFlags = in.readInt();
            mSignalQuality = in.readInt();
            mMetadata = in.readTypedObject(RadioMetadata.CREATOR);
            mVendorInfo = Utils.readStringMap(in);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<ProgramInfo> CREATOR
                = new Parcelable.Creator<ProgramInfo>() {
            public ProgramInfo createFromParcel(Parcel in) {
                return new ProgramInfo(in);
            }

            public ProgramInfo[] newArray(int size) {
                return new ProgramInfo[size];
            }
        };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeTypedObject(mSelector, flags);
            dest.writeTypedObject(mLogicallyTunedTo, flags);
            dest.writeTypedObject(mPhysicallyTunedTo, flags);
            Utils.writeTypedCollection(dest, mRelatedContent);
            dest.writeInt(mInfoFlags);
            dest.writeInt(mSignalQuality);
            dest.writeTypedObject(mMetadata, flags);
            Utils.writeStringMap(dest, mVendorInfo);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        @Override
        public String toString() {
            return "ProgramInfo"
                    + " [selector=" + mSelector
                    + ", logicallyTunedTo=" + Objects.toString(mLogicallyTunedTo)
                    + ", physicallyTunedTo=" + Objects.toString(mPhysicallyTunedTo)
                    + ", relatedContent=" + mRelatedContent.size()
                    + ", infoFlags=" + mInfoFlags
                    + ", mSignalQuality=" + mSignalQuality
                    + ", mMetadata=" + Objects.toString(mMetadata)
                    + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mSelector, mLogicallyTunedTo, mPhysicallyTunedTo,
                mRelatedContent, mInfoFlags, mSignalQuality, mMetadata, mVendorInfo);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ProgramInfo)) return false;
            ProgramInfo other = (ProgramInfo) obj;

            if (!mSelector.strictEquals(other.mSelector)) return false;
            if (!Objects.equals(mLogicallyTunedTo, other.mLogicallyTunedTo)) return false;
            if (!Objects.equals(mPhysicallyTunedTo, other.mPhysicallyTunedTo)) return false;
            if (!Objects.equals(mRelatedContent, other.mRelatedContent)) return false;
            if (mInfoFlags != other.mInfoFlags) return false;
            if (mSignalQuality != other.mSignalQuality) return false;
            if (!Objects.equals(mMetadata, other.mMetadata)) return false;
            if (!Objects.equals(mVendorInfo, other.mVendorInfo)) return false;

            return true;
        }
    }


    /**
     * Returns a list of descriptors for all broadcast radio modules present on the device.
     * @param modules An List of {@link ModuleProperties} where the list will be returned.
     * @return
     * <ul>
     *  <li>{@link #STATUS_OK} in case of success, </li>
     *  <li>{@link #STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link #STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link #STATUS_BAD_VALUE} if modules is null, </li>
     *  <li>{@link #STATUS_DEAD_OBJECT} if the binder transaction to the native service fails, </li>
     * </ul>
     */
    @RequiresPermission(Manifest.permission.ACCESS_BROADCAST_RADIO)
    @RadioStatusType
    public int listModules(List<ModuleProperties> modules) {
        if (modules == null) {
            Log.e(TAG, "the output list must not be empty");
            return STATUS_BAD_VALUE;
        }

        Log.d(TAG, "Listing available tuners...");
        List<ModuleProperties> returnedList;
        try {
            returnedList = mService.listModules();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed listing available tuners", e);
            return STATUS_DEAD_OBJECT;
        }

        if (returnedList == null) {
            Log.e(TAG, "Returned list was a null");
            return STATUS_ERROR;
        }

        modules.addAll(returnedList);
        return STATUS_OK;
    }

    private native int nativeListModules(List<ModuleProperties> modules);

    /**
     * Open an interface to control a tuner on a given broadcast radio module.
     * Optionally selects and applies the configuration passed as "config" argument.
     * @param moduleId radio module identifier {@link ModuleProperties#getId()}. Mandatory.
     * @param config desired band and configuration to apply when enabling the hardware module.
     * optional, can be null.
     * @param withAudio {@code true} to request a tuner with an audio source.
     * This tuner is intended for live listening or recording or a radio program.
     * If {@code false}, the tuner can only be used to retrieve program informations.
     * @param callback {@link RadioTuner.Callback} interface. Mandatory.
     * @param handler the Handler on which the callbacks will be received.
     * Can be null if default handler is OK.
     * @return a valid {@link RadioTuner} interface in case of success or null in case of error.
     */
    @RequiresPermission(Manifest.permission.ACCESS_BROADCAST_RADIO)
    public RadioTuner openTuner(int moduleId, BandConfig config, boolean withAudio,
            RadioTuner.Callback callback, Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be empty");
        }

        Log.d(TAG, "Opening tuner " + moduleId + "...");

        ITuner tuner;
        TunerCallbackAdapter halCallback = new TunerCallbackAdapter(callback, handler);
        try {
            tuner = mService.openTuner(moduleId, config, withAudio, halCallback);
        } catch (RemoteException | IllegalArgumentException | IllegalStateException ex) {
            Log.e(TAG, "Failed to open tuner", ex);
            return null;
        }
        if (tuner == null) {
            Log.e(TAG, "Failed to open tuner");
            return null;
        }
        return new TunerAdapter(tuner, halCallback,
                config != null ? config.getType() : BAND_INVALID);
    }

    private final Map<Announcement.OnListUpdatedListener, ICloseHandle> mAnnouncementListeners =
            new HashMap<>();

    /**
     * Adds new announcement listener.
     *
     * @param enabledAnnouncementTypes a set of announcement types to listen to
     * @param listener announcement listener
     */
    @RequiresPermission(Manifest.permission.ACCESS_BROADCAST_RADIO)
    public void addAnnouncementListener(@NonNull Set<Integer> enabledAnnouncementTypes,
            @NonNull Announcement.OnListUpdatedListener listener) {
        addAnnouncementListener(cmd -> cmd.run(), enabledAnnouncementTypes, listener);
    }

    /**
     * Adds new announcement listener with executor.
     *
     * @param executor the executor
     * @param enabledAnnouncementTypes a set of announcement types to listen to
     * @param listener announcement listener
     */
    @RequiresPermission(Manifest.permission.ACCESS_BROADCAST_RADIO)
    public void addAnnouncementListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull Set<Integer> enabledAnnouncementTypes,
            @NonNull Announcement.OnListUpdatedListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        int[] types = enabledAnnouncementTypes.stream().mapToInt(Integer::intValue).toArray();
        IAnnouncementListener listenerIface = new IAnnouncementListener.Stub() {
            public void onListUpdated(List<Announcement> activeAnnouncements) {
                executor.execute(() -> listener.onListUpdated(activeAnnouncements));
            }
        };
        synchronized (mAnnouncementListeners) {
            ICloseHandle closeHandle = null;
            try {
                closeHandle = mService.addAnnouncementListener(types, listenerIface);
            } catch (RemoteException ex) {
                ex.rethrowFromSystemServer();
            }
            Objects.requireNonNull(closeHandle);
            ICloseHandle oldCloseHandle = mAnnouncementListeners.put(listener, closeHandle);
            if (oldCloseHandle != null) Utils.close(oldCloseHandle);
        }
    }

    /**
     * Removes previously registered announcement listener.
     *
     * @param listener announcement listener, previously registered with
     *        {@link addAnnouncementListener}
     */
    @RequiresPermission(Manifest.permission.ACCESS_BROADCAST_RADIO)
    public void removeAnnouncementListener(@NonNull Announcement.OnListUpdatedListener listener) {
        Objects.requireNonNull(listener);
        synchronized (mAnnouncementListeners) {
            ICloseHandle closeHandle = mAnnouncementListeners.remove(listener);
            if (closeHandle != null) Utils.close(closeHandle);
        }
    }

    @NonNull private final Context mContext;
    @NonNull private final IRadioService mService;

    /**
     * @hide
     */
    public RadioManager(Context context) throws ServiceNotFoundException {
        this(context, IRadioService.Stub.asInterface(ServiceManager.getServiceOrThrow(
                Context.RADIO_SERVICE)));
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public RadioManager(Context context, IRadioService service) {
        mContext = context;
        mService = service;
    }
}
