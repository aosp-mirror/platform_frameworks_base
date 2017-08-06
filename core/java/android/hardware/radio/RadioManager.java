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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.text.TextUtils;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static void writeStringMap(@NonNull Parcel dest, @NonNull Map<String, String> map) {
        dest.writeInt(map.size());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeString(entry.getValue());
        }
    }

    private static @NonNull Map<String, String> readStringMap(@NonNull Parcel in) {
        int size = in.readInt();
        Map<String, String> map = new HashMap<>();
        while (size-- > 0) {
            String key = in.readString();
            String value = in.readString();
            map.put(key, value);
        }
        return map;
    }

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
        private final boolean mIsCaptureSupported;
        private final BandDescriptor[] mBands;
        private final boolean mIsBgScanSupported;
        private final Set<Integer> mSupportedProgramTypes;
        private final Set<Integer> mSupportedIdentifierTypes;
        @NonNull private final Map<String, String> mVendorInfo;

        ModuleProperties(int id, String serviceName, int classId, String implementor,
                String product, String version, String serial, int numTuners, int numAudioSources,
                boolean isCaptureSupported, BandDescriptor[] bands, boolean isBgScanSupported,
                @ProgramSelector.ProgramType int[] supportedProgramTypes,
                @ProgramSelector.IdentifierType int[] supportedIdentifierTypes,
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
            mIsCaptureSupported = isCaptureSupported;
            mBands = bands;
            mIsBgScanSupported = isBgScanSupported;
            mSupportedProgramTypes = arrayToSet(supportedProgramTypes);
            mSupportedIdentifierTypes = arrayToSet(supportedIdentifierTypes);
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
        public int getNumAudioSources() {
            return mNumAudioSources;
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
            mIsCaptureSupported = in.readInt() == 1;
            Parcelable[] tmp = in.readParcelableArray(BandDescriptor.class.getClassLoader());
            mBands = new BandDescriptor[tmp.length];
            for (int i = 0; i < tmp.length; i++) {
                mBands[i] = (BandDescriptor) tmp[i];
            }
            mIsBgScanSupported = in.readInt() == 1;
            mSupportedProgramTypes = arrayToSet(in.createIntArray());
            mSupportedIdentifierTypes = arrayToSet(in.createIntArray());
            mVendorInfo = readStringMap(in);
        }

        public static final Parcelable.Creator<ModuleProperties> CREATOR
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
            dest.writeInt(mIsCaptureSupported ? 1 : 0);
            dest.writeParcelableArray(mBands, flags);
            dest.writeInt(mIsBgScanSupported ? 1 : 0);
            dest.writeIntArray(setToArray(mSupportedProgramTypes));
            dest.writeIntArray(setToArray(mSupportedIdentifierTypes));
            writeStringMap(dest, mVendorInfo);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public String toString() {
            return "ModuleProperties [mId=" + mId
                    + ", mServiceName=" + mServiceName + ", mClassId=" + mClassId
                    + ", mImplementor=" + mImplementor + ", mProduct=" + mProduct
                    + ", mVersion=" + mVersion + ", mSerial=" + mSerial
                    + ", mNumTuners=" + mNumTuners
                    + ", mNumAudioSources=" + mNumAudioSources
                    + ", mIsCaptureSupported=" + mIsCaptureSupported
                    + ", mIsBgScanSupported=" + mIsBgScanSupported
                    + ", mBands=" + Arrays.toString(mBands) + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + mId;
            result = prime * result + mServiceName.hashCode();
            result = prime * result + mClassId;
            result = prime * result + ((mImplementor == null) ? 0 : mImplementor.hashCode());
            result = prime * result + ((mProduct == null) ? 0 : mProduct.hashCode());
            result = prime * result + ((mVersion == null) ? 0 : mVersion.hashCode());
            result = prime * result + ((mSerial == null) ? 0 : mSerial.hashCode());
            result = prime * result + mNumTuners;
            result = prime * result + mNumAudioSources;
            result = prime * result + (mIsCaptureSupported ? 1 : 0);
            result = prime * result + Arrays.hashCode(mBands);
            result = prime * result + (mIsBgScanSupported ? 1 : 0);
            result = prime * result + mVendorInfo.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof ModuleProperties))
                return false;
            ModuleProperties other = (ModuleProperties) obj;
            if (mId != other.getId())
                return false;
            if (!TextUtils.equals(mServiceName, other.mServiceName)) return false;
            if (mClassId != other.getClassId())
                return false;
            if (mImplementor == null) {
                if (other.getImplementor() != null)
                    return false;
            } else if (!mImplementor.equals(other.getImplementor()))
                return false;
            if (mProduct == null) {
                if (other.getProduct() != null)
                    return false;
            } else if (!mProduct.equals(other.getProduct()))
                return false;
            if (mVersion == null) {
                if (other.getVersion() != null)
                    return false;
            } else if (!mVersion.equals(other.getVersion()))
                return false;
            if (mSerial == null) {
                if (other.getSerial() != null)
                    return false;
            } else if (!mSerial.equals(other.getSerial()))
                return false;
            if (mNumTuners != other.getNumTuners())
                return false;
            if (mNumAudioSources != other.getNumAudioSources())
                return false;
            if (mIsCaptureSupported != other.isCaptureSupported())
                return false;
            if (!Arrays.equals(mBands, other.getBands()))
                return false;
            if (mIsBgScanSupported != other.isBackgroundScanningSupported())
                return false;
            if (!mVendorInfo.equals(other.mVendorInfo)) return false;
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

        public static final Parcelable.Creator<BandDescriptor> CREATOR
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
        public boolean equals(Object obj) {
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

        FmBandDescriptor(int region, int type, int lowerLimit, int upperLimit, int spacing,
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

        public static final Parcelable.Creator<FmBandDescriptor> CREATOR
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
        public boolean equals(Object obj) {
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

        AmBandDescriptor(int region, int type, int lowerLimit, int upperLimit, int spacing,
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

        public static final Parcelable.Creator<AmBandDescriptor> CREATOR
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
        public boolean equals(Object obj) {
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

        final BandDescriptor mDescriptor;

        BandConfig(BandDescriptor descriptor) {
            mDescriptor = descriptor;
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


        public static final Parcelable.Creator<BandConfig> CREATOR
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
        public boolean equals(Object obj) {
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

        FmBandConfig(FmBandDescriptor descriptor) {
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

        public static final Parcelable.Creator<FmBandConfig> CREATOR
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
        public boolean equals(Object obj) {
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

        AmBandConfig(AmBandDescriptor descriptor) {
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

        public static final Parcelable.Creator<AmBandConfig> CREATOR
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
        public boolean equals(Object obj) {
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

    /** Radio program information returned by
     * {@link RadioTuner#getProgramInformation(RadioManager.ProgramInfo[])} */
    public static class ProgramInfo implements Parcelable {

        // sourced from hardware/interfaces/broadcastradio/1.1/types.hal
        private static final int FLAG_LIVE = 1 << 0;
        private static final int FLAG_MUTED = 1 << 1;
        private static final int FLAG_TRAFFIC_PROGRAM = 1 << 2;
        private static final int FLAG_TRAFFIC_ANNOUNCEMENT = 1 << 3;

        @NonNull private final ProgramSelector mSelector;
        private final boolean mTuned;
        private final boolean mStereo;
        private final boolean mDigital;
        private final int mFlags;
        private final int mSignalStrength;
        private final RadioMetadata mMetadata;
        @NonNull private final Map<String, String> mVendorInfo;

        ProgramInfo(@NonNull ProgramSelector selector, boolean tuned, boolean stereo,
                boolean digital, int signalStrength, RadioMetadata metadata, int flags,
                Map<String, String> vendorInfo) {
            mSelector = selector;
            mTuned = tuned;
            mStereo = stereo;
            mDigital = digital;
            mFlags = flags;
            mSignalStrength = signalStrength;
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
            return mTuned;
        }
        /** {@code true} if the received program is stereo
         * @return {@code true} if stereo, {@code false} otherwise.
         */
        public boolean isStereo() {
            return mStereo;
        }
        /** {@code true} if the received program is digital (e.g HD radio)
         * @return {@code true} if digital, {@code false} otherwise.
         */
        public boolean isDigital() {
            return mDigital;
        }

        /**
         * {@code true} if the program is currently playing live stream.
         * This may result in a slightly altered reception parameters,
         * usually targetted at reduced latency.
         */
        public boolean isLive() {
            return (mFlags & FLAG_LIVE) != 0;
        }

        /**
         * {@code true} if radio stream is not playing, ie. due to bad reception
         * conditions or buffering. In this state volume knob MAY be disabled to
         * prevent user increasing volume too much.
         * It does NOT mean the user has muted audio.
         */
        public boolean isMuted() {
            return (mFlags & FLAG_MUTED) != 0;
        }

        /**
         * {@code true} if radio station transmits traffic information
         * regularily.
         */
        public boolean isTrafficProgram() {
            return (mFlags & FLAG_TRAFFIC_PROGRAM) != 0;
        }

        /**
         * {@code true} if radio station transmits traffic information
         * at the very moment.
         */
        public boolean isTrafficAnnouncementActive() {
            return (mFlags & FLAG_TRAFFIC_ANNOUNCEMENT) != 0;
        }

        /** Signal strength indicator from 0 (no signal) to 100 (excellent)
         * @return the signal strength indication.
         */
        public int getSignalStrength() {
            return mSignalStrength;
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
            mSelector = in.readParcelable(null);
            mTuned = in.readByte() == 1;
            mStereo = in.readByte() == 1;
            mDigital = in.readByte() == 1;
            mSignalStrength = in.readInt();
            if (in.readByte() == 1) {
                mMetadata = RadioMetadata.CREATOR.createFromParcel(in);
            } else {
                mMetadata = null;
            }
            mFlags = in.readInt();
            mVendorInfo = readStringMap(in);
        }

        public static final Parcelable.Creator<ProgramInfo> CREATOR
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
            dest.writeParcelable(mSelector, 0);
            dest.writeByte((byte)(mTuned ? 1 : 0));
            dest.writeByte((byte)(mStereo ? 1 : 0));
            dest.writeByte((byte)(mDigital ? 1 : 0));
            dest.writeInt(mSignalStrength);
            if (mMetadata == null) {
                dest.writeByte((byte)0);
            } else {
                dest.writeByte((byte)1);
                mMetadata.writeToParcel(dest, flags);
            }
            dest.writeInt(mFlags);
            writeStringMap(dest, mVendorInfo);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public String toString() {
            return "ProgramInfo [mSelector=" + mSelector
                    + ", mTuned=" + mTuned + ", mStereo=" + mStereo + ", mDigital=" + mDigital
                    + ", mFlags=" + mFlags + ", mSignalStrength=" + mSignalStrength
                    + ((mMetadata == null) ? "" : (", mMetadata=" + mMetadata.toString()))
                    + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + mSelector.hashCode();
            result = prime * result + (mTuned ? 1 : 0);
            result = prime * result + (mStereo ? 1 : 0);
            result = prime * result + (mDigital ? 1 : 0);
            result = prime * result + mFlags;
            result = prime * result + mSignalStrength;
            result = prime * result + ((mMetadata == null) ? 0 : mMetadata.hashCode());
            result = prime * result + mVendorInfo.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof ProgramInfo))
                return false;
            ProgramInfo other = (ProgramInfo) obj;
            if (!mSelector.equals(other.getSelector())) return false;
            if (mTuned != other.isTuned())
                return false;
            if (mStereo != other.isStereo())
                return false;
            if (mDigital != other.isDigital())
                return false;
            if (mFlags != other.mFlags)
                return false;
            if (mSignalStrength != other.getSignalStrength())
                return false;
            if (mMetadata == null) {
                if (other.getMetadata() != null)
                    return false;
            } else if (!mMetadata.equals(other.getMetadata()))
                return false;
            if (!mVendorInfo.equals(other.mVendorInfo)) return false;
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
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to open tuner", e);
            return null;
        }
        if (tuner == null) {
            Log.e(TAG, "Failed to open tuner");
            return null;
        }
        return new TunerAdapter(tuner, config != null ? config.getType() : BAND_INVALID);
    }

    @NonNull private final Context mContext;
    @NonNull private final IRadioService mService;

    /**
     * @hide
     */
    public RadioManager(@NonNull Context context) throws ServiceNotFoundException {
        mContext = context;
        mService = IRadioService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.RADIO_SERVICE));
    }
}
