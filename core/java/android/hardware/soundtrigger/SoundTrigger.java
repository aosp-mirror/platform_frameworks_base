/**
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.soundtrigger;

import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.SOUNDTRIGGER_DELEGATE_IDENTITY;
import static android.system.OsConstants.EBUSY;
import static android.system.OsConstants.EINVAL;
import static android.system.OsConstants.ENODEV;
import static android.system.OsConstants.ENOSYS;
import static android.system.OsConstants.EPERM;
import static android.system.OsConstants.EPIPE;

import static java.util.Objects.requireNonNull;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.media.soundtrigger.Status;
import android.media.soundtrigger_middleware.ISoundTriggerInjection;
import android.media.soundtrigger_middleware.ISoundTriggerMiddlewareService;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The SoundTrigger class provides access to the service managing the sound trigger HAL.
 *
 * @hide
 */
@SystemApi
public class SoundTrigger {
    private static final String TAG = "SoundTrigger";

    private SoundTrigger() {
    }

    /**
     * Model architecture associated with a fake STHAL which can be injected.
     * Used for testing purposes.
     * @hide
     */
    public static final String FAKE_HAL_ARCH = ISoundTriggerInjection.FAKE_HAL_ARCH;

    /**
     * Status code used when the operation succeeded
     */
    public static final int STATUS_OK = 0;
    /** @hide */
    public static final int STATUS_ERROR = Integer.MIN_VALUE;
    /** @hide */
    public static final int STATUS_PERMISSION_DENIED = -EPERM;
    /** @hide */
    public static final int STATUS_NO_INIT = -ENODEV;
    /** @hide */
    public static final int STATUS_BAD_VALUE = -EINVAL;
    /** @hide */
    public static final int STATUS_DEAD_OBJECT = -EPIPE;
    /** @hide */
    public static final int STATUS_INVALID_OPERATION = -ENOSYS;
    /** @hide */
    public static final int STATUS_BUSY = -EBUSY;

    /*****************************************************************************
     * A ModuleProperties describes a given sound trigger hardware module
     * managed by the native sound trigger service. Each module has a unique
     * ID used to target any API call to this paricular module. Module
     * properties are returned by listModules() method.
     *
     ****************************************************************************/
    public static final class ModuleProperties implements Parcelable {

        /**
         * Bit field values of AudioCapabilities supported by the implemented HAL
         * driver.
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = { "AUDIO_CAPABILITY_" }, value = {
                AUDIO_CAPABILITY_ECHO_CANCELLATION,
                AUDIO_CAPABILITY_NOISE_SUPPRESSION
        })
        public @interface AudioCapabilities {}

        /**
         * If set the underlying module supports AEC.
         * Describes bit field {@link ModuleProperties#mAudioCapabilities}
         */
        public static final int AUDIO_CAPABILITY_ECHO_CANCELLATION = 0x1;
        /**
         * If set, the underlying module supports noise suppression.
         * Describes bit field {@link ModuleProperties#mAudioCapabilities}
         */
        public static final int AUDIO_CAPABILITY_NOISE_SUPPRESSION = 0x2;

        private final int mId;
        @NonNull
        private final String mImplementor;
        @NonNull
        private final String mDescription;
        @NonNull
        private final UUID mUuid;
        private final int mVersion;
        @NonNull
        private final String mSupportedModelArch;
        private final int mMaxSoundModels;
        private final int mMaxKeyphrases;
        private final int mMaxUsers;
        @RecognitionModes
        private final int mRecognitionModes;
        private final boolean mSupportsCaptureTransition;
        private final int mMaxBufferMillis;
        private final boolean mSupportsConcurrentCapture;
        private final int mPowerConsumptionMw;
        private final boolean mReturnsTriggerInEvent;
        @AudioCapabilities
        private final int mAudioCapabilities;

        /** @hide */
        @TestApi
        public ModuleProperties(int id, @NonNull String implementor, @NonNull String description,
                @NonNull String uuid, int version, @NonNull String supportedModelArch,
                int maxSoundModels, int maxKeyphrases, int maxUsers,
                @RecognitionModes int recognitionModes, boolean supportsCaptureTransition,
                int maxBufferMs, boolean supportsConcurrentCapture, int powerConsumptionMw,
                boolean returnsTriggerInEvent, int audioCapabilities) {
            this.mId = id;
            this.mImplementor = requireNonNull(implementor);
            this.mDescription = requireNonNull(description);
            this.mUuid = UUID.fromString(requireNonNull(uuid));
            this.mVersion = version;
            this.mSupportedModelArch = requireNonNull(supportedModelArch);
            this.mMaxSoundModels = maxSoundModels;
            this.mMaxKeyphrases = maxKeyphrases;
            this.mMaxUsers = maxUsers;
            this.mRecognitionModes = recognitionModes;
            this.mSupportsCaptureTransition = supportsCaptureTransition;
            this.mMaxBufferMillis = maxBufferMs;
            this.mSupportsConcurrentCapture = supportsConcurrentCapture;
            this.mPowerConsumptionMw = powerConsumptionMw;
            this.mReturnsTriggerInEvent = returnsTriggerInEvent;
            this.mAudioCapabilities = audioCapabilities;
        }

        /** Unique module ID provided by the native service */
        public int getId() {
            return mId;
        }

        /** human readable voice detection engine implementor */
        @NonNull
        public String getImplementor() {
            return mImplementor;
        }

        /** human readable voice detection engine description */
        @NonNull
        public String getDescription() {
            return mDescription;
        }

        /** Unique voice engine Id (changes with each version) */
        @NonNull
        public UUID getUuid() {
            return mUuid;
        }

        /** Voice detection engine version */
        public int getVersion() {
            return mVersion;
        }

        /**
         * String naming the architecture used for running the supported models.
         * (eg. a platform running models on a DSP could implement this string to convey the DSP
         * architecture used)
         */
        @NonNull
        public String getSupportedModelArch() {
            return mSupportedModelArch;
        }

        /** Maximum number of active sound models */
        public int getMaxSoundModels() {
            return mMaxSoundModels;
        }

        /** Maximum number of key phrases */
        public int getMaxKeyphrases() {
            return mMaxKeyphrases;
        }

        /** Maximum number of users per key phrase */
        public int getMaxUsers() {
            return mMaxUsers;
        }

        /** Supported recognition modes (bit field, RECOGNITION_MODE_VOICE_TRIGGER ...) */
        @RecognitionModes
        public int getRecognitionModes() {
            return mRecognitionModes;
        }

        /** Supports seamless transition to capture mode after recognition */
        public boolean isCaptureTransitionSupported() {
            return mSupportsCaptureTransition;
        }

        /** Maximum buffering capacity in ms if supportsCaptureTransition() is true */
        public int getMaxBufferMillis() {
            return mMaxBufferMillis;
        }

        /** Supports capture by other use cases while detection is active */
        public boolean isConcurrentCaptureSupported() {
            return mSupportsConcurrentCapture;
        }

        /** Rated power consumption when detection is active with TDB silence/sound/speech ratio */
        public int getPowerConsumptionMw() {
            return mPowerConsumptionMw;
        }

        /** Returns the trigger (key phrase) capture in the binary data of the
         * recognition callback event */
        public boolean isTriggerReturnedInEvent() {
            return mReturnsTriggerInEvent;
        }

        /**
         * Bit field encoding of the AudioCapabilities
         * supported by the firmware.
         */
        @AudioCapabilities
        public int getAudioCapabilities() {
            return mAudioCapabilities;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<ModuleProperties> CREATOR
                = new Parcelable.Creator<ModuleProperties>() {
            public ModuleProperties createFromParcel(Parcel in) {
                return ModuleProperties.fromParcel(in);
            }

            public ModuleProperties[] newArray(int size) {
                return new ModuleProperties[size];
            }
        };

        private static ModuleProperties fromParcel(Parcel in) {
            int id = in.readInt();
            String implementor = in.readString();
            String description = in.readString();
            String uuid = in.readString();
            int version = in.readInt();
            String supportedModelArch = in.readString();
            int maxSoundModels = in.readInt();
            int maxKeyphrases = in.readInt();
            int maxUsers = in.readInt();
            int recognitionModes = in.readInt();
            boolean supportsCaptureTransition = in.readByte() == 1;
            int maxBufferMs = in.readInt();
            boolean supportsConcurrentCapture = in.readByte() == 1;
            int powerConsumptionMw = in.readInt();
            boolean returnsTriggerInEvent = in.readByte() == 1;
            int audioCapabilities = in.readInt();
            return new ModuleProperties(id, implementor, description, uuid, version,
                    supportedModelArch, maxSoundModels, maxKeyphrases, maxUsers, recognitionModes,
                    supportsCaptureTransition, maxBufferMs, supportsConcurrentCapture,
                    powerConsumptionMw, returnsTriggerInEvent, audioCapabilities);
        }

        @Override
        public void writeToParcel(@SuppressLint("MissingNullability") Parcel dest, int flags) {
            dest.writeInt(getId());
            dest.writeString(getImplementor());
            dest.writeString(getDescription());
            dest.writeString(getUuid().toString());
            dest.writeInt(getVersion());
            dest.writeString(getSupportedModelArch());
            dest.writeInt(getMaxSoundModels());
            dest.writeInt(getMaxKeyphrases());
            dest.writeInt(getMaxUsers());
            dest.writeInt(getRecognitionModes());
            dest.writeByte((byte) (isCaptureTransitionSupported() ? 1 : 0));
            dest.writeInt(getMaxBufferMillis());
            dest.writeByte((byte) (isConcurrentCaptureSupported() ? 1 : 0));
            dest.writeInt(getPowerConsumptionMw());
            dest.writeByte((byte) (isTriggerReturnedInEvent() ? 1 : 0));
            dest.writeInt(getAudioCapabilities());
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (!(obj instanceof ModuleProperties)) {
                return false;
            }
            ModuleProperties other = (ModuleProperties) obj;
            if (mId != other.mId) {
                return false;
            }
            if (!mImplementor.equals(other.mImplementor)) {
                return false;
            }
            if (!mDescription.equals(other.mDescription)) {
                return false;
            }
            if (!mUuid.equals(other.mUuid)) {
                return false;
            }
            if (mVersion != other.mVersion) {
                return false;
            }
            if (!mSupportedModelArch.equals(other.mSupportedModelArch)) {
                return false;
            }
            if (mMaxSoundModels != other.mMaxSoundModels) {
                return false;
            }
            if (mMaxKeyphrases != other.mMaxKeyphrases) {
                return false;
            }
            if (mMaxUsers != other.mMaxUsers) {
                return false;
            }
            if (mRecognitionModes != other.mRecognitionModes) {
                return false;
            }
            if (mSupportsCaptureTransition != other.mSupportsCaptureTransition) {
                return false;
            }
            if (mMaxBufferMillis != other.mMaxBufferMillis) {
                return false;
            }
            if (mSupportsConcurrentCapture != other.mSupportsConcurrentCapture) {
                return false;
            }
            if (mPowerConsumptionMw != other.mPowerConsumptionMw) {
                return false;
            }
            if (mReturnsTriggerInEvent != other.mReturnsTriggerInEvent) {
                return false;
            }
            if (mAudioCapabilities != other.mAudioCapabilities) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + mId;
            result = prime * result + mImplementor.hashCode();
            result = prime * result + mDescription.hashCode();
            result = prime * result + mUuid.hashCode();
            result = prime * result + mVersion;
            result = prime * result + mSupportedModelArch.hashCode();
            result = prime * result + mMaxSoundModels;
            result = prime * result + mMaxKeyphrases;
            result = prime * result + mMaxUsers;
            result = prime * result + mRecognitionModes;
            result = prime * result + (mSupportsCaptureTransition ? 1 : 0);
            result = prime * result + mMaxBufferMillis;
            result = prime * result + (mSupportsConcurrentCapture ? 1 : 0);
            result = prime * result + mPowerConsumptionMw;
            result = prime * result + (mReturnsTriggerInEvent ? 1 : 0);
            result = prime * result + mAudioCapabilities;
            return result;
        }

        @Override
        public String toString() {
            return "ModuleProperties [id=" + getId() + ", implementor=" + getImplementor()
                    + ", description=" + getDescription() + ", uuid=" + getUuid()
                    + ", version=" + getVersion() + " , supportedModelArch="
                    + getSupportedModelArch() + ", maxSoundModels=" + getMaxSoundModels()
                    + ", maxKeyphrases=" + getMaxKeyphrases() + ", maxUsers=" + getMaxUsers()
                    + ", recognitionModes=" + getRecognitionModes() + ", supportsCaptureTransition="
                    + isCaptureTransitionSupported() + ", maxBufferMs=" + getMaxBufferMillis()
                    + ", supportsConcurrentCapture=" + isConcurrentCaptureSupported()
                    + ", powerConsumptionMw=" + getPowerConsumptionMw()
                    + ", returnsTriggerInEvent=" + isTriggerReturnedInEvent()
                    + ", audioCapabilities=" + getAudioCapabilities() + "]";
        }
    }

    /**
     * A SoundModel describes the attributes and contains the binary data used by the hardware
     * implementation to detect a particular sound pattern.
     * A specialized version {@link KeyphraseSoundModel} is defined for key phrase
     * sound models.
     */
    public static class SoundModel {

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({
                TYPE_GENERIC_SOUND,
                TYPE_KEYPHRASE,
                TYPE_UNKNOWN,
        })
        public @interface SoundModelType {}

        /**
         * Undefined sound model type
         * @hide
         */
        public static final int TYPE_UNKNOWN = -1;

        /** Keyphrase sound model */
        public static final int TYPE_KEYPHRASE = 0;

        /**
         * A generic sound model. Use this type only for non-keyphrase sound models such as
         * ones that match a particular sound pattern.
         */
        public static final int TYPE_GENERIC_SOUND = 1;

        @NonNull
        private final UUID mUuid;
        @SoundModelType
        private final int mType;
        @NonNull
        private final UUID mVendorUuid;
        private final int mVersion;
        @NonNull
        private final byte[] mData;

        /** @hide */
        public SoundModel(@NonNull UUID uuid, @Nullable UUID vendorUuid, @SoundModelType int type,
                @Nullable byte[] data, int version) {
            this.mUuid = requireNonNull(uuid);
            this.mVendorUuid = vendorUuid != null ? vendorUuid : new UUID(0, 0);
            this.mType = type;
            this.mVersion = version;
            this.mData = data != null ? data : new byte[0];
        }

        /** Unique sound model identifier */
        @NonNull
        public UUID getUuid() {
            return mUuid;
        }

        /** Sound model type (e.g. TYPE_KEYPHRASE); */
        @SoundModelType
        public int getType() {
            return mType;
        }

        /** Unique sound model vendor identifier */
        @NonNull
        public UUID getVendorUuid() {
            return mVendorUuid;
        }

        /** vendor specific version number of the model */
        public int getVersion() {
            return mVersion;
        }

        /** Opaque data. For use by vendor implementation and enrollment application */
        @NonNull
        public byte[] getData() {
            return mData;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + getVersion();
            result = prime * result + Arrays.hashCode(getData());
            result = prime * result + getType();
            result = prime * result + ((getUuid() == null) ? 0 : getUuid().hashCode());
            result = prime * result + ((getVendorUuid() == null) ? 0 : getVendorUuid().hashCode());
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (!(obj instanceof SoundModel)) {
                return false;
            }
            SoundModel other = (SoundModel) obj;
            if (getType() != other.getType()) {
                return false;
            }
            if (getUuid() == null) {
                if (other.getUuid() != null) {
                    return false;
                }
            } else if (!getUuid().equals(other.getUuid())) {
                return false;
            }
            if (getVendorUuid() == null) {
                if (other.getVendorUuid() != null) {
                    return false;
                }
            } else if (!getVendorUuid().equals(other.getVendorUuid())) {
                return false;
            }
            if (!Arrays.equals(getData(), other.getData())) {
                return false;
            }
            if (getVersion() != other.getVersion()) {
                return false;
            }
            return true;
        }
    }

    /**
     * A Keyphrase describes a key phrase that can be detected by a
     * {@link KeyphraseSoundModel}
     */
    public static final class Keyphrase implements Parcelable {

        private final int mId;
        @RecognitionModes
        private final int mRecognitionModes;
        @NonNull
        private final Locale mLocale;
        @NonNull
        private final String mText;
        @NonNull
        private final int[] mUsers;

        /**
         * Constructor for Keyphrase describes a key phrase that can be detected by a
         * {@link KeyphraseSoundModel}
         *
         * @param id Unique keyphrase identifier for this keyphrase
         * @param recognitionModes Bit field representation of recognition modes this keyphrase
         *                         supports
         * @param locale Locale of the keyphrase
         * @param text Key phrase text
         * @param users Users this key phrase has been trained for.
         */
        public Keyphrase(int id, @RecognitionModes int recognitionModes, @NonNull Locale locale,
                @NonNull String text, @Nullable int[] users) {
            this.mId = id;
            this.mRecognitionModes = recognitionModes;
            this.mLocale = requireNonNull(locale);
            this.mText = requireNonNull(text);
            this.mUsers = users != null ? users : new int[0];
        }

        /** Unique identifier for this keyphrase */
        public int getId() {
            return mId;
        }

        /**
         * Recognition modes supported for this key phrase in the model
         *
         * @see #RECOGNITION_MODE_VOICE_TRIGGER
         * @see #RECOGNITION_MODE_USER_IDENTIFICATION
         * @see #RECOGNITION_MODE_USER_AUTHENTICATION
         * @see #RECOGNITION_MODE_GENERIC
         */
        @RecognitionModes
        public int getRecognitionModes() {
            return mRecognitionModes;
        }

        /** Locale of the keyphrase. */
        @NonNull
        public Locale getLocale() {
            return mLocale;
        }

        /** Key phrase text */
        @NonNull
        public String getText() {
            return mText;
        }

        /**
         * Users this key phrase has been trained for. countains sound trigger specific user IDs
         * derived from system user IDs {@link android.os.UserHandle#getIdentifier()}.
         */
        @NonNull
        public int[] getUsers() {
            return mUsers;
        }

        public static final @NonNull Parcelable.Creator<Keyphrase> CREATOR =
                new Parcelable.Creator<Keyphrase>() {
            @NonNull
            public Keyphrase createFromParcel(@NonNull Parcel in) {
                return Keyphrase.readFromParcel(in);
            }

            @NonNull
            public Keyphrase[] newArray(int size) {
                return new Keyphrase[size];
            }
        };

        /**
         * Read from Parcel to generate keyphrase
         */
        @NonNull
        public static Keyphrase readFromParcel(@NonNull Parcel in) {
            int id = in.readInt();
            int recognitionModes = in.readInt();
            Locale locale = Locale.forLanguageTag(in.readString());
            String text = in.readString();
            int[] users = null;
            int numUsers = in.readInt();
            if (numUsers >= 0) {
                users = new int[numUsers];
                in.readIntArray(users);
            }
            return new Keyphrase(id, recognitionModes, locale, text, users);
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(getId());
            dest.writeInt(getRecognitionModes());
            dest.writeString(getLocale().toLanguageTag());
            dest.writeString(getText());
            if (getUsers() != null) {
                dest.writeInt(getUsers().length);
                dest.writeIntArray(getUsers());
            } else {
                dest.writeInt(-1);
            }
        }

        /** @hide */
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((getText() == null) ? 0 : getText().hashCode());
            result = prime * result + getId();
            result = prime * result + ((getLocale() == null) ? 0 : getLocale().hashCode());
            result = prime * result + getRecognitionModes();
            result = prime * result + Arrays.hashCode(getUsers());
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Keyphrase other = (Keyphrase) obj;
            if (getText() == null) {
                if (other.getText() != null) {
                    return false;
                }
            } else if (!getText().equals(other.getText())) {
                return false;
            }
            if (getId() != other.getId()) {
                return false;
            }
            if (getLocale() == null) {
                if (other.getLocale() != null) {
                    return false;
                }
            } else if (!getLocale().equals(other.getLocale())) {
                return false;
            }
            if (getRecognitionModes() != other.getRecognitionModes()) {
                return false;
            }
            if (!Arrays.equals(getUsers(), other.getUsers())) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "Keyphrase [id=" + getId() + ", recognitionModes=" + getRecognitionModes()
                    + ", locale=" + getLocale().toLanguageTag() + ", text=" + getText()
                    + ", users=" + Arrays.toString(getUsers()) + "]";
        }
    }

    /**
     * A KeyphraseSoundModel is a specialized {@link SoundModel} for key phrases.
     * It contains data needed by the hardware to detect a certain number of key phrases
     * and the list of corresponding {@link Keyphrase} descriptors.
     */
    public static final class KeyphraseSoundModel extends SoundModel implements Parcelable {

        @NonNull
        private final Keyphrase[] mKeyphrases;

        public KeyphraseSoundModel(
                @NonNull UUID uuid, @NonNull UUID vendorUuid, @Nullable byte[] data,
                @Nullable Keyphrase[] keyphrases, int version) {
            super(uuid, vendorUuid, TYPE_KEYPHRASE, data, version);
            this.mKeyphrases = keyphrases != null ? keyphrases : new Keyphrase[0];
        }

        public KeyphraseSoundModel(@NonNull UUID uuid, @NonNull UUID vendorUuid,
                @Nullable byte[] data, @Nullable Keyphrase[] keyphrases) {
            this(uuid, vendorUuid, data, keyphrases, -1);
        }

        /** Key phrases in this sound model */
        @NonNull
        public Keyphrase[] getKeyphrases() {
            return mKeyphrases;
        }

        public static final @NonNull Parcelable.Creator<KeyphraseSoundModel> CREATOR =
                new Parcelable.Creator<KeyphraseSoundModel>() {
            @NonNull
            public KeyphraseSoundModel createFromParcel(@NonNull Parcel in) {
                return KeyphraseSoundModel.readFromParcel(in);
            }

            @NonNull
            public KeyphraseSoundModel[] newArray(int size) {
                return new KeyphraseSoundModel[size];
            }
        };

        /**
         * Read from Parcel to generate KeyphraseSoundModel
         */
        @NonNull
        public static KeyphraseSoundModel readFromParcel(@NonNull Parcel in) {
            UUID uuid = UUID.fromString(in.readString());
            UUID vendorUuid = null;
            int length = in.readInt();
            if (length >= 0) {
                vendorUuid = UUID.fromString(in.readString());
            }
            int version = in.readInt();
            byte[] data = in.readBlob();
            Keyphrase[] keyphrases = in.createTypedArray(Keyphrase.CREATOR);
            return new KeyphraseSoundModel(uuid, vendorUuid, data, keyphrases, version);
        }

        /** @hide */
        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(getUuid().toString());
            if (getVendorUuid() == null) {
                dest.writeInt(-1);
            } else {
                dest.writeInt(getVendorUuid().toString().length());
                dest.writeString(getVendorUuid().toString());
            }
            dest.writeInt(getVersion());
            dest.writeBlob(getData());
            dest.writeTypedArray(getKeyphrases(), flags);
        }

        @Override
        public String toString() {
            return "KeyphraseSoundModel [keyphrases=" + Arrays.toString(getKeyphrases())
                    + ", uuid=" + getUuid() + ", vendorUuid=" + getVendorUuid()
                    + ", type=" + getType()
                    + ", data=" + (getData() == null ? 0 : getData().length)
                    + ", version=" + getVersion() + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + Arrays.hashCode(getKeyphrases());
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!super.equals(obj)) {
                return false;
            }
            if (!(obj instanceof KeyphraseSoundModel)) {
                return false;
            }
            KeyphraseSoundModel other = (KeyphraseSoundModel) obj;
            if (!Arrays.equals(getKeyphrases(), other.getKeyphrases())) {
                return false;
            }
            return true;
        }
    }


    /*****************************************************************************
     * A GenericSoundModel is a specialized {@link SoundModel} for non-voice sound
     * patterns.
     ****************************************************************************/
    @FlaggedApi(android.media.soundtrigger.Flags.FLAG_GENERIC_MODEL_API)
    public static final class GenericSoundModel extends SoundModel implements Parcelable {

        public static final @android.annotation.NonNull Parcelable.Creator<GenericSoundModel> CREATOR
                = new Parcelable.Creator<GenericSoundModel>() {
            public GenericSoundModel createFromParcel(Parcel in) {
                return GenericSoundModel.fromParcel(in);
            }

            public GenericSoundModel[] newArray(int size) {
                return new GenericSoundModel[size];
            }
        };

        /**
         * Constructor for {@link GenericSoundModel} with version.
         *
         * @param uuid Unique identifier for this sound model.
         * @param vendorUuid Unique vendor identifier for this sound model.
         * @param data Opaque data for this sound model.
         * @param version Vendor-specific version number of this sound model.
         */
        public GenericSoundModel(@NonNull UUID uuid, @NonNull UUID vendorUuid,
                @Nullable byte[] data, int version) {
            super(uuid, vendorUuid, TYPE_GENERIC_SOUND, data, version);
        }

        /**
         * Constructor for {@link GenericSoundModel} without version. The version is set to -1.
         *
         * @param uuid Unique identifier for this sound model.
         * @param vendorUuid Unique vendor identifier for this sound model.
         * @param data Opaque data for this sound model.
         */
        @UnsupportedAppUsage
        public GenericSoundModel(@NonNull UUID uuid, @NonNull UUID vendorUuid,
                @Nullable byte[] data) {
            this(uuid, vendorUuid, data, -1);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        private static GenericSoundModel fromParcel(Parcel in) {
            UUID uuid = UUID.fromString(in.readString());
            UUID vendorUuid = null;
            int length = in.readInt();
            if (length >= 0) {
                vendorUuid = UUID.fromString(in.readString());
            }
            byte[] data = in.readBlob();
            int version = in.readInt();
            return new GenericSoundModel(uuid, vendorUuid, data, version);
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(getUuid().toString());
            if (getVendorUuid() == null) {
                dest.writeInt(-1);
            } else {
                dest.writeInt(getVendorUuid().toString().length());
                dest.writeString(getVendorUuid().toString());
            }
            dest.writeBlob(getData());
            dest.writeInt(getVersion());
        }

        @Override
        public String toString() {
            return "GenericSoundModel [uuid=" + getUuid() + ", vendorUuid=" + getVendorUuid()
                    + ", type=" + getType()
                    + ", data=" + (getData() == null ? 0 : getData().length)
                    + ", version=" + getVersion() + "]";
        }
    }

    /**
     * A ModelParamRange is a representation of supported parameter range for a
     * given loaded model.
     */
    public static final class ModelParamRange implements Parcelable {

        /**
         * The inclusive start of supported range.
         */
        private final int mStart;

        /**
         * The inclusive end of supported range.
         */
        private final int mEnd;

        /** @hide */
        @TestApi
        public ModelParamRange(int start, int end) {
            this.mStart = start;
            this.mEnd = end;
        }

        /** @hide */
        private ModelParamRange(@NonNull Parcel in) {
            this.mStart = in.readInt();
            this.mEnd = in.readInt();
        }

        /**
         * Get the beginning of the param range
         *
         * @return The inclusive start of the supported range.
         */
        public int getStart() {
            return mStart;
        }

        /**
         * Get the end of the param range
         *
         * @return The inclusive end of the supported range.
         */
        public int getEnd() {
            return mEnd;
        }

        @NonNull
        public static final Creator<ModelParamRange> CREATOR =
                new Creator<ModelParamRange>() {
                    @Override
                    @NonNull
                    public ModelParamRange createFromParcel(@NonNull Parcel in) {
                        return new ModelParamRange(in);
                    }

                    @Override
                    @NonNull
                    public ModelParamRange[] newArray(int size) {
                        return new ModelParamRange[size];
                    }
                };

        /** @hide */
        @Override
        public int describeContents() {
            return 0;
        }

        /** @hide */
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (mStart);
            result = prime * result + (mEnd);
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ModelParamRange other = (ModelParamRange) obj;
            if (mStart != other.mStart) {
                return false;
            }
            if (mEnd != other.mEnd) {
                return false;
            }
            return true;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mStart);
            dest.writeInt(mEnd);
        }

        @Override
        @NonNull
        public String toString() {
            return "ModelParamRange [start=" + mStart + ", end=" + mEnd + "]";
        }
    }
    /**
     * SoundTrigger model parameter types.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "MODEL_PARAM" }, value = {
            MODEL_PARAM_INVALID,
            MODEL_PARAM_THRESHOLD_FACTOR
    })
    public @interface ModelParamTypes {}

    /**
     * See {@link ModelParams.INVALID}
     * @hide
     */
    @TestApi
    public static final int MODEL_PARAM_INVALID = ModelParams.INVALID;
    /**
     * See {@link ModelParams.THRESHOLD_FACTOR}
     * @hide
     */
    @TestApi
    public static final int MODEL_PARAM_THRESHOLD_FACTOR = ModelParams.THRESHOLD_FACTOR;

    /**
     * Modes for key phrase recognition
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "RECOGNITION_MODE_" }, value = {
            RECOGNITION_MODE_VOICE_TRIGGER,
            RECOGNITION_MODE_USER_IDENTIFICATION,
            RECOGNITION_MODE_USER_AUTHENTICATION,
            RECOGNITION_MODE_GENERIC
    })
    public @interface RecognitionModes {}

    /**
     * Trigger on recognition of a key phrase
     */
    public static final int RECOGNITION_MODE_VOICE_TRIGGER = 0x1;
    /**
     * Trigger only if one user is identified
     */
    public static final int RECOGNITION_MODE_USER_IDENTIFICATION = 0x2;
    /**
     * Trigger only if one user is authenticated
     */
    public static final int RECOGNITION_MODE_USER_AUTHENTICATION = 0x4;
    /**
     * Generic (non-speech) recognition.
     */
    public static final int RECOGNITION_MODE_GENERIC = 0x8;

    /**
     *  Status codes for {@link RecognitionEvent}
     */
    /**
     * Recognition success
     *
     * @hide
     */
    public static final int RECOGNITION_STATUS_SUCCESS = 0;
    /**
     * Recognition aborted (e.g. capture preempted by anotehr use case
     *
     * @hide
     */
    public static final int RECOGNITION_STATUS_ABORT = 1;
    /**
     * Recognition failure
     *
     * @hide
     */
    public static final int RECOGNITION_STATUS_FAILURE = 2;
    /**
     * Recognition event was triggered by a getModelState request, not by the
     * DSP.
     *
     * @hide
     */
    public static final int RECOGNITION_STATUS_GET_STATE_RESPONSE = 3;

    /**
     *  A RecognitionEvent is provided by the
     *  {@code StatusListener#onRecognition(RecognitionEvent)}
     *  callback upon recognition success or failure.
     */
    public static class RecognitionEvent {
        /**
         * Recognition status e.g RECOGNITION_STATUS_SUCCESS
         *
         * @hide
         */
        @UnsupportedAppUsage
        public final int status;
        /**
         *
         * Sound Model corresponding to this event callback
         *
         * @hide
         */
        @UnsupportedAppUsage
        public final int soundModelHandle;
        /**
         * True if it is possible to capture audio from this utterance buffered by the hardware
         *
         * @hide
         */
        @UnsupportedAppUsage
        public final boolean captureAvailable;
        /**
         * Audio session ID to be used when capturing the utterance with an AudioRecord
         * if captureAvailable() is true.
         *
         * @hide
         */
        @UnsupportedAppUsage
        public final int captureSession;
        /**
         * Delay in ms between end of model detection and start of audio available for capture.
         * A negative value is possible (e.g. if keyphrase is also available for capture)
         *
         * @hide
         */
        public final int captureDelayMs;
        /**
         * Duration in ms of audio captured before the start of the trigger. 0 if none.
         *
         * @hide
         */
        public final int capturePreambleMs;
        /**
         * True if  the trigger (key phrase capture is present in binary data
         *
         * @hide
         */
        public final boolean triggerInData;
        /**
         * Audio format of either the trigger in event data or to use for capture of the
         * rest of the utterance
         *
         * @hide
         */
        @NonNull
        public final AudioFormat captureFormat;
        /**
         * Opaque data for use by system applications who know about voice engine internals,
         * typically during enrollment.
         *
         * @hide
         */
        @UnsupportedAppUsage
        @NonNull
        public final byte[] data;
        /**
         * Is recognition still active after this event.
         * @hide
         */
        public final boolean recognitionStillActive;
        /**
         * Timestamp of when the trigger event from SoundTriggerHal was received by the
         * framework.
         *
         * <p>Clock monotonic including suspend time or its equivalent on the system,
         * in the same units and timebase as {@link SystemClock#elapsedRealtime()}.
         *
         * <p>Value represents elapsed realtime in milliseconds when the event was received from the
         * HAL. The value will be -1 if the event was not generated from the HAL.
         *
         * @hide
         */
        @ElapsedRealtimeLong
        public final long halEventReceivedMillis;

        /**
         * Binder token returned by {@link SoundTriggerModule#startRecognitionWithToken(
         * int soundModelHandle, SoundTrigger.RecognitionConfig config)}
         * @hide
         */
        public final IBinder token;


        /** @hide */
        @TestApi
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public RecognitionEvent(int status, int soundModelHandle, boolean captureAvailable,
                int captureSession, int captureDelayMs, int capturePreambleMs,
                boolean triggerInData, @NonNull AudioFormat captureFormat, @Nullable byte[] data,
                @ElapsedRealtimeLong long halEventReceivedMillis) {
            this(status, soundModelHandle, captureAvailable,
                    captureSession, captureDelayMs, capturePreambleMs, triggerInData, captureFormat,
                    data, status == RECOGNITION_STATUS_GET_STATE_RESPONSE, halEventReceivedMillis,
                    null);
        }

        /** @hide */
        public RecognitionEvent(int status, int soundModelHandle, boolean captureAvailable,
                int captureSession, int captureDelayMs, int capturePreambleMs,
                boolean triggerInData, @NonNull AudioFormat captureFormat, @Nullable byte[] data,
                boolean recognitionStillActive, @ElapsedRealtimeLong long halEventReceivedMillis,
                IBinder token) {
            this.status = status;
            this.soundModelHandle = soundModelHandle;
            this.captureAvailable = captureAvailable;
            this.captureSession = captureSession;
            this.captureDelayMs = captureDelayMs;
            this.capturePreambleMs = capturePreambleMs;
            this.triggerInData = triggerInData;
            this.captureFormat = requireNonNull(captureFormat);
            this.data = data != null ? data : new byte[0];
            this.recognitionStillActive = recognitionStillActive;
            this.halEventReceivedMillis = halEventReceivedMillis;
            this.token = token;
        }

        /**
         * Check if is possible to capture audio from this utterance buffered by the hardware.
         *
         * @return {@code true} iff a capturing is possible
         */
        public boolean isCaptureAvailable() {
            return captureAvailable;
        }

        /**
         * Get the audio format of either the trigger in event data or to use for capture of the
         * rest of the utterance
         *
         * @return the audio format
         */
        @Nullable public AudioFormat getCaptureFormat() {
            return captureFormat;
        }

        /**
         * Get Audio session ID to be used when capturing the utterance with an {@link AudioRecord}
         * if {@link #isCaptureAvailable()} is true.
         *
         * @return The id of the capture session
         */
        public int getCaptureSession() {
            return captureSession;
        }

        /**
         * Get the opaque data for use by system applications who know about voice engine
         * internals, typically during enrollment.
         *
         * @return The data of the event
         */
        @SuppressLint("MissingNullability")
        public byte[] getData() {
            return data;
        }

        /**
         * Timestamp of when the trigger event from SoundTriggerHal was received by the
         * framework.
         *
         * Clock monotonic including suspend time or its equivalent on the system,
         * in the same units and timebase as {@link SystemClock#elapsedRealtime()}.
         *
         * @return Elapsed realtime in milliseconds when the event was received from the HAL.
         *      Returns -1 if the event was not generated from the HAL.
         */
        @ElapsedRealtimeLong
        public long getHalEventReceivedMillis() {
            return halEventReceivedMillis;
        }

        /**
         * Get token associated with this recognition session returned by
         *{@link SoundTriggerModule#startRecognitionWithToken(
         * int soundModelHandle, SoundTrigger.RecognitionConfig config)}
         * @hide
         */
        public IBinder getToken() {
            return token;
        }

        /** @hide */
        public static final @android.annotation.NonNull Parcelable.Creator<RecognitionEvent> CREATOR
                = new Parcelable.Creator<RecognitionEvent>() {
            public RecognitionEvent createFromParcel(Parcel in) {
                return RecognitionEvent.fromParcel(in);
            }

            public RecognitionEvent[] newArray(int size) {
                return new RecognitionEvent[size];
            }
        };

        /** @hide */
        protected static RecognitionEvent fromParcel(Parcel in) {
            int status = in.readInt();
            int soundModelHandle = in.readInt();
            boolean captureAvailable = in.readByte() == 1;
            int captureSession = in.readInt();
            int captureDelayMs = in.readInt();
            int capturePreambleMs = in.readInt();
            boolean triggerInData = in.readByte() == 1;
            AudioFormat captureFormat = null;
            if (in.readByte() == 1) {
                int sampleRate = in.readInt();
                int encoding = in.readInt();
                int channelMask = in.readInt();
                captureFormat = (new AudioFormat.Builder())
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .build();
            }
            byte[] data = in.readBlob();
            boolean recognitionStillActive = in.readBoolean();
            long halEventReceivedMillis = in.readLong();
            IBinder token = in.readStrongBinder();
            return new RecognitionEvent(status, soundModelHandle, captureAvailable, captureSession,
                    captureDelayMs, capturePreambleMs, triggerInData, captureFormat, data,
                    recognitionStillActive, halEventReceivedMillis, token);
        }

        /** @hide */
        public int describeContents() {
            return 0;
        }

        /** @hide */
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(status);
            dest.writeInt(soundModelHandle);
            dest.writeByte((byte) (captureAvailable ? 1 : 0));
            dest.writeInt(captureSession);
            dest.writeInt(captureDelayMs);
            dest.writeInt(capturePreambleMs);
            dest.writeByte((byte) (triggerInData ? 1 : 0));
            if (captureFormat != null) {
                dest.writeByte((byte)1);
                dest.writeInt(captureFormat.getSampleRate());
                dest.writeInt(captureFormat.getEncoding());
                dest.writeInt(captureFormat.getChannelMask());
            } else {
                dest.writeByte((byte)0);
            }
            dest.writeBlob(data);
            dest.writeBoolean(recognitionStillActive);
            dest.writeLong(halEventReceivedMillis);
            dest.writeStrongBinder(token);
        }
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (captureAvailable ? 1231 : 1237);
            result = prime * result + captureDelayMs;
            result = prime * result + capturePreambleMs;
            result = prime * result + captureSession;
            result = prime * result + (triggerInData ? 1231 : 1237);
            if (captureFormat != null) {
                result = prime * result + captureFormat.getSampleRate();
                result = prime * result + captureFormat.getEncoding();
                result = prime * result + captureFormat.getChannelMask();
            }
            result = prime * result + Arrays.hashCode(data);
            result = prime * result + soundModelHandle;
            result = prime * result + status;
            result = result + (recognitionStillActive ? 1289 : 1291);
            result = prime * result + Long.hashCode(halEventReceivedMillis);
            result = prime * result +  Objects.hashCode(token);
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            RecognitionEvent other = (RecognitionEvent) obj;
            if (captureAvailable != other.captureAvailable)
                return false;
            if (captureDelayMs != other.captureDelayMs)
                return false;
            if (capturePreambleMs != other.capturePreambleMs)
                return false;
            if (captureSession != other.captureSession)
                return false;
            if (!Arrays.equals(data, other.data))
                return false;
            if (recognitionStillActive != other.recognitionStillActive)
                return false;
            if (soundModelHandle != other.soundModelHandle)
                return false;
            if (halEventReceivedMillis != other.halEventReceivedMillis) {
                return false;
            }
            if (!Objects.equals(token, other.token)) {
                return false;
            }
            if (status != other.status)
                return false;
            if (triggerInData != other.triggerInData)
                return false;
            if (captureFormat == null) {
                if (other.captureFormat != null)
                    return false;
            } else {
                if (other.captureFormat == null)
                    return false;
                if (captureFormat.getSampleRate() != other.captureFormat.getSampleRate())
                    return false;
                if (captureFormat.getEncoding() != other.captureFormat.getEncoding())
                    return false;
                if (captureFormat.getChannelMask() != other.captureFormat.getChannelMask())
                    return false;
            }
            return true;
        }

        @NonNull
        @Override
        public String toString() {
            return "RecognitionEvent [status=" + status + ", soundModelHandle=" + soundModelHandle
                    + ", captureAvailable=" + captureAvailable + ", captureSession="
                    + captureSession + ", captureDelayMs=" + captureDelayMs
                    + ", capturePreambleMs=" + capturePreambleMs
                    + ", triggerInData=" + triggerInData
                    + ((captureFormat == null) ? "" :
                        (", sampleRate=" + captureFormat.getSampleRate()))
                    + ((captureFormat == null) ? "" :
                        (", encoding=" + captureFormat.getEncoding()))
                    + ((captureFormat == null) ? "" :
                        (", channelMask=" + captureFormat.getChannelMask()))
                    + ", data=" + (data == null ? 0 : data.length)
                    + ", recognitionStillActive=" + recognitionStillActive
                    + ", halEventReceivedMillis=" + halEventReceivedMillis
                    + ", token=" + token
                    + "]"; }
    }

    /**
     *  A RecognitionConfig is provided to
     *  {@link SoundTriggerModule#startRecognition(int, RecognitionConfig)} to configure the
     *  recognition request.
     */
    @FlaggedApi(android.media.soundtrigger.Flags.FLAG_MANAGER_API)
    public static final class RecognitionConfig implements Parcelable {
        private final boolean mCaptureRequested;
        private final boolean mAllowMultipleTriggers;
        private final KeyphraseRecognitionExtra mKeyphrases[];
        private final byte[] mData;
        @ModuleProperties.AudioCapabilities
        private final int mAudioCapabilities;

        /**
         * Constructor for {@link RecognitionConfig} with {@code audioCapabilities} describes a
         * config that can be used by
         * {@link SoundTriggerModule#startRecognition(int, RecognitionConfig)}
         *
         * @deprecated should use builder-based constructor instead.
         *             TODO(b/368042125): remove this method.
         * @param captureRequested Whether the DSP should capture the trigger sound.
         * @param allowMultipleTriggers Whether the service should restart listening after the DSP
         *                              triggers.
         * @param keyphrases List of keyphrases in the sound model.
         * @param data Opaque data for use by system applications who know about voice engine
         *             internals, typically during enrollment.
         * @param audioCapabilities Bit field encoding of the AudioCapabilities.
         *
         * @hide
         */
        @Deprecated
        @SuppressWarnings("Todo")
        @TestApi
        public RecognitionConfig(boolean captureRequested, boolean allowMultipleTriggers,
                @SuppressLint("ArrayReturn") @Nullable KeyphraseRecognitionExtra[] keyphrases,
                @Nullable byte[] data, int audioCapabilities) {
            this.mCaptureRequested = captureRequested;
            this.mAllowMultipleTriggers = allowMultipleTriggers;
            this.mKeyphrases = keyphrases != null ? keyphrases : new KeyphraseRecognitionExtra[0];
            this.mData = data != null ? data : new byte[0];
            this.mAudioCapabilities = audioCapabilities;
        }

        /**
         * Constructor for {@link RecognitionConfig} without audioCapabilities. The
         * audioCapabilities is set to 0.
         *
         * @param captureRequested Whether the DSP should capture the trigger sound.
         * @param allowMultipleTriggers Whether the service should restart listening after the DSP
         *                              triggers.
         * @param keyphrases List of keyphrases in the sound model.
         * @param data Opaque data for use by system applications.
         *
         * @hide
         */
        @UnsupportedAppUsage
        @TestApi
        public RecognitionConfig(boolean captureRequested, boolean allowMultipleTriggers,
                @SuppressLint("ArrayReturn") @Nullable KeyphraseRecognitionExtra[] keyphrases,
                @Nullable byte[] data) {
            this(captureRequested, allowMultipleTriggers, keyphrases, data, 0);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<RecognitionConfig> CREATOR
                = new Parcelable.Creator<RecognitionConfig>() {
            public RecognitionConfig createFromParcel(Parcel in) {
                return RecognitionConfig.fromParcel(in);
            }

            public RecognitionConfig[] newArray(int size) {
                return new RecognitionConfig[size];
            }
        };

        /**
         * Returns whether the DSP should capture the trigger sound and make it available for
         * further capture.
         */
        public boolean isCaptureRequested() {
            return mCaptureRequested;
        }

        /**
         * Returns whether the service should restart listening after the DSP triggers.
         *
         * <p><b>Note:</b> This config flag is currently used at the service layer rather than by
         * the DSP.
         */
        public boolean isAllowMultipleTriggers() {
            return mAllowMultipleTriggers;
        }

        /**
         * Gets all keyphrases in the sound model for which recognition should be performed with
         * options for each keyphrase.
         */
        @NonNull
        public List<KeyphraseRecognitionExtra> getKeyphrases() {
            return Arrays.asList(mKeyphrases);
        }

        /**
         * Opaque data.
         *
         * <p>For use by system applications who knows about voice engine internals, typically
         * during enrollment.
         */
        @NonNull
        public byte[] getData() {
            return mData;
        }

        /** Bit field encoding of the AudioCapabilities supported by the firmware. */
        public int getAudioCapabilities() {
            return mAudioCapabilities;
        }

        private static RecognitionConfig fromParcel(Parcel in) {
            boolean captureRequested = in.readBoolean();
            boolean allowMultipleTriggers = in.readBoolean();
            KeyphraseRecognitionExtra[] keyphrases =
                    in.createTypedArray(KeyphraseRecognitionExtra.CREATOR);
            byte[] data = in.readBlob();
            int audioCapabilities = in.readInt();
            return new RecognitionConfig(captureRequested, allowMultipleTriggers, keyphrases, data,
                    audioCapabilities);
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeBoolean(mCaptureRequested);
            dest.writeBoolean(mAllowMultipleTriggers);
            dest.writeTypedArray(mKeyphrases, flags);
            dest.writeBlob(mData);
            dest.writeInt(mAudioCapabilities);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public String toString() {
            return "RecognitionConfig [captureRequested=" + mCaptureRequested
                    + ", allowMultipleTriggers=" + mAllowMultipleTriggers + ", keyphrases="
                    + Arrays.toString(mKeyphrases) + ", data=" + Arrays.toString(mData)
                    + ", audioCapabilities=" + Integer.toHexString(mAudioCapabilities) + "]";
        }

        @Override
        public final boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (!(obj instanceof RecognitionConfig))
                return false;
            RecognitionConfig other = (RecognitionConfig) obj;
            if (mCaptureRequested != other.mCaptureRequested) {
                return false;
            }
            if (mAllowMultipleTriggers != other.mAllowMultipleTriggers) {
                return false;
            }
            if (!Arrays.equals(mKeyphrases, other.mKeyphrases)) {
                return false;
            }
            if (!Arrays.equals(mData, other.mData)) {
                return false;
            }
            if (mAudioCapabilities != other.mAudioCapabilities) {
                return false;
            }
            return true;
        }

        @Override
        public final int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (mCaptureRequested ? 1 : 0);
            result = prime * result + (mAllowMultipleTriggers ? 1 : 0);
            result = prime * result + Arrays.hashCode(mKeyphrases);
            result = prime * result + Arrays.hashCode(mData);
            result = prime * result + mAudioCapabilities;
            return result;
        }

        /**
         * Builder class for {@link RecognitionConfig} objects.
         */
        public static final class Builder {
            private boolean mCaptureRequested;
            private boolean mAllowMultipleTriggers;
            @Nullable private KeyphraseRecognitionExtra[] mKeyphrases;
            @Nullable private byte[] mData;
            private int mAudioCapabilities;

            /**
             * Constructs a new Builder with the default values.
             */
            public Builder() {
            }

            /**
             * Sets capture requested state.
             * @param captureRequested The new requested state.
             * @return the same Builder instance.
             */
            public @NonNull Builder setCaptureRequested(boolean captureRequested) {
                mCaptureRequested = captureRequested;
                return this;
            }

            /**
             * Sets allow multiple triggers state.
             * @param allowMultipleTriggers The new allow multiple triggers state.
             * @return the same Builder instance.
             */
            public @NonNull Builder setAllowMultipleTriggers(boolean allowMultipleTriggers) {
                mAllowMultipleTriggers = allowMultipleTriggers;
                return this;
            }

            /**
             * Sets the keyphrases field.
             * @param keyphrases The new keyphrases.
             * @return the same Builder instance.
             */
            public @NonNull Builder setKeyphrases(
                    @NonNull Collection<KeyphraseRecognitionExtra> keyphrases) {
                mKeyphrases = keyphrases.toArray(new KeyphraseRecognitionExtra[keyphrases.size()]);
                return this;
            }

            /**
             * Sets the data field.
             * @param data The new data.
             * @return the same Builder instance.
             */
            public @NonNull Builder setData(@Nullable byte[] data) {
                mData = data;
                return this;
            }

            /**
             * Sets the audio capabilities field.
             * @param audioCapabilities The new audio capabilities.
             * @return the same Builder instance.
             */
            public @NonNull Builder setAudioCapabilities(int audioCapabilities) {
                mAudioCapabilities = audioCapabilities;
                return this;
            }

            /**
             * Combines all of the parameters that have been set and return a new
             * {@link RecognitionConfig} object.
             * @return a new {@link RecognitionConfig} object
             */
            public @NonNull RecognitionConfig build() {
                RecognitionConfig config = new RecognitionConfig(
                        /* captureRequested= */ mCaptureRequested,
                        /* allowMultipleTriggers= */ mAllowMultipleTriggers,
                        /* keyphrases= */ mKeyphrases,
                        /* data= */ mData,
                        /* audioCapabilities= */ mAudioCapabilities);
                return config;
            }
        };
    }

    /**
     * Confidence level for users defined in a keyphrase.
     * - The confidence level is expressed in percent (0% -100%).
     * When used in a {@link KeyphraseRecognitionEvent} it indicates the detected confidence level
     * When used in a {@link RecognitionConfig} it indicates the minimum confidence level that
     * should trigger a recognition.
     * - The user ID is derived from the system ID {@link android.os.UserHandle#getIdentifier()}.
     *
     * @hide
     */
    public static class ConfidenceLevel implements Parcelable {
        @UnsupportedAppUsage
        public final int userId;
        @UnsupportedAppUsage
        public final int confidenceLevel;

        @UnsupportedAppUsage
        public ConfidenceLevel(int userId, int confidenceLevel) {
            this.userId = userId;
            this.confidenceLevel = confidenceLevel;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<ConfidenceLevel> CREATOR
                = new Parcelable.Creator<ConfidenceLevel>() {
            public ConfidenceLevel createFromParcel(Parcel in) {
                return ConfidenceLevel.fromParcel(in);
            }

            public ConfidenceLevel[] newArray(int size) {
                return new ConfidenceLevel[size];
            }
        };

        private static ConfidenceLevel fromParcel(Parcel in) {
            int userId = in.readInt();
            int confidenceLevel = in.readInt();
            return new ConfidenceLevel(userId, confidenceLevel);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(userId);
            dest.writeInt(confidenceLevel);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + confidenceLevel;
            result = prime * result + userId;
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ConfidenceLevel other = (ConfidenceLevel) obj;
            if (confidenceLevel != other.confidenceLevel)
                return false;
            if (userId != other.userId)
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "ConfidenceLevel [userId=" + userId
                    + ", confidenceLevel=" + confidenceLevel + "]";
        }
    }

    /**
     * Additional data conveyed by a {@link KeyphraseRecognitionEvent}
     * for a key phrase detection.
     */
    public static final class KeyphraseRecognitionExtra implements Parcelable {
        /**
         * The keyphrase ID
         *
         * @hide
         */
        @UnsupportedAppUsage
        public final int id;

        /**
         * Recognition modes matched for this event
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public final int recognitionModes;

        /**
         * Confidence level for mode RECOGNITION_MODE_VOICE_TRIGGER when user identification
         * is not performed
         *
         * @hide
         */
        @UnsupportedAppUsage
        public final int coarseConfidenceLevel;

        /**
         * Confidence levels for all users recognized (KeyphraseRecognitionEvent) or to
         * be recognized (RecognitionConfig)
         *
         * @hide
         */
        @UnsupportedAppUsage
        @NonNull
        public final ConfidenceLevel[] confidenceLevels;


        /**
         * @hide
         */
        @TestApi
        public KeyphraseRecognitionExtra(int id, @RecognitionModes int recognitionModes,
                int coarseConfidenceLevel) {
            this(id, recognitionModes, coarseConfidenceLevel, new ConfidenceLevel[0]);
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public KeyphraseRecognitionExtra(int id, int recognitionModes,
                @IntRange(from = 0, to = 100) int coarseConfidenceLevel,
                @Nullable ConfidenceLevel[] confidenceLevels) {
            this.id = id;
            this.recognitionModes = recognitionModes;
            this.coarseConfidenceLevel = coarseConfidenceLevel;
            this.confidenceLevels =
                    confidenceLevels != null ? confidenceLevels : new ConfidenceLevel[0];
        }

        /**
         * The keyphrase ID associated with this class' additional data
         */
        public int getKeyphraseId() {
            return id;
        }

        /**
         * Recognition modes matched for this event
         */
        @RecognitionModes
        public int getRecognitionModes() {
            return recognitionModes;
        }

        /**
         * Confidence level for mode RECOGNITION_MODE_VOICE_TRIGGER when user identification
         * is not performed
         *
         * <p>The confidence level is expressed in percent (0% -100%).
         */
        @IntRange(from = 0, to = 100)
        public int getCoarseConfidenceLevel() {
            return coarseConfidenceLevel;
        }

        /**
         * Detected confidence level for users defined in a keyphrase.
         *
         * <p>The confidence level is expressed in percent (0% -100%).
         *
         * <p>The user ID is derived from the system ID
         * {@link android.os.UserHandle#getIdentifier()}.
         *
         * @hide
         */
        @NonNull
        public Collection<ConfidenceLevel> getConfidenceLevels() {
            return Arrays.asList(confidenceLevels);
        }

        public static final @NonNull Parcelable.Creator<KeyphraseRecognitionExtra> CREATOR
                = new Parcelable.Creator<KeyphraseRecognitionExtra>() {
            public KeyphraseRecognitionExtra createFromParcel(Parcel in) {
                return KeyphraseRecognitionExtra.fromParcel(in);
            }

            public KeyphraseRecognitionExtra[] newArray(int size) {
                return new KeyphraseRecognitionExtra[size];
            }
        };

        private static KeyphraseRecognitionExtra fromParcel(Parcel in) {
            int id = in.readInt();
            int recognitionModes = in.readInt();
            int coarseConfidenceLevel = in.readInt();
            ConfidenceLevel[] confidenceLevels = in.createTypedArray(ConfidenceLevel.CREATOR);
            return new KeyphraseRecognitionExtra(id, recognitionModes, coarseConfidenceLevel,
                    confidenceLevels);
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(id);
            dest.writeInt(recognitionModes);
            dest.writeInt(coarseConfidenceLevel);
            dest.writeTypedArray(confidenceLevels, flags);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode(confidenceLevels);
            result = prime * result + id;
            result = prime * result + recognitionModes;
            result = prime * result + coarseConfidenceLevel;
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            KeyphraseRecognitionExtra other = (KeyphraseRecognitionExtra) obj;
            if (!Arrays.equals(confidenceLevels, other.confidenceLevels)) {
                return false;
            }
            if (id != other.id) {
                return false;
            }
            if (recognitionModes != other.recognitionModes) {
                return false;
            }
            if (coarseConfidenceLevel != other.coarseConfidenceLevel) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "KeyphraseRecognitionExtra [id=" + id + ", recognitionModes=" + recognitionModes
                    + ", coarseConfidenceLevel=" + coarseConfidenceLevel
                    + ", confidenceLevels=" + Arrays.toString(confidenceLevels) + "]";
        }
    }

    /**
     *  Specialized {@link RecognitionEvent} for a key phrase detection.
     *
     *  @hide
     */
    public static class KeyphraseRecognitionEvent extends RecognitionEvent implements Parcelable {
        /** Indicates if the key phrase is present in the buffered audio available for capture */
        @UnsupportedAppUsage
        @NonNull
        public final KeyphraseRecognitionExtra[] keyphraseExtras;

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public KeyphraseRecognitionEvent(int status, int soundModelHandle, boolean captureAvailable,
                int captureSession, int captureDelayMs, int capturePreambleMs,
                boolean triggerInData, @NonNull AudioFormat captureFormat, @Nullable byte[] data,
                @Nullable KeyphraseRecognitionExtra[] keyphraseExtras,
                @ElapsedRealtimeLong long halEventReceivedMillis,
                IBinder token) {
            this(status, soundModelHandle, captureAvailable, captureSession, captureDelayMs,
                    capturePreambleMs, triggerInData, captureFormat, data, keyphraseExtras,
                    status == RECOGNITION_STATUS_GET_STATE_RESPONSE, halEventReceivedMillis,
                    token);
        }

        public KeyphraseRecognitionEvent(int status, int soundModelHandle,
                boolean captureAvailable,
                int captureSession, int captureDelayMs, int capturePreambleMs,
                boolean triggerInData, @NonNull AudioFormat captureFormat, @Nullable byte[] data,
                @Nullable KeyphraseRecognitionExtra[] keyphraseExtras,
                boolean recognitionStillActive, @ElapsedRealtimeLong long halEventReceivedMillis,
                IBinder token) {
            super(status, soundModelHandle, captureAvailable,
                    captureSession, captureDelayMs, capturePreambleMs, triggerInData, captureFormat,
                    data, recognitionStillActive, halEventReceivedMillis, token);
            this.keyphraseExtras =
                    keyphraseExtras != null ? keyphraseExtras : new KeyphraseRecognitionExtra[0];
        }

        public static final @NonNull Parcelable.Creator<KeyphraseRecognitionEvent> CREATOR
                = new Parcelable.Creator<KeyphraseRecognitionEvent>() {
            public KeyphraseRecognitionEvent createFromParcel(Parcel in) {
                return KeyphraseRecognitionEvent.fromParcelForKeyphrase(in);
            }

            public KeyphraseRecognitionEvent[] newArray(int size) {
                return new KeyphraseRecognitionEvent[size];
            }
        };

        private static KeyphraseRecognitionEvent fromParcelForKeyphrase(Parcel in) {
            int status = in.readInt();
            int soundModelHandle = in.readInt();
            boolean captureAvailable = in.readByte() == 1;
            int captureSession = in.readInt();
            int captureDelayMs = in.readInt();
            int capturePreambleMs = in.readInt();
            boolean triggerInData = in.readByte() == 1;
            AudioFormat captureFormat = null;
            if (in.readByte() == 1) {
                int sampleRate = in.readInt();
                int encoding = in.readInt();
                int channelMask = in.readInt();
                captureFormat = (new AudioFormat.Builder())
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .build();
            }
            byte[] data = in.readBlob();
            boolean recognitionStillActive = in.readBoolean();
            long halEventReceivedMillis = in.readLong();
            IBinder token = in.readStrongBinder();
            KeyphraseRecognitionExtra[] keyphraseExtras =
                    in.createTypedArray(KeyphraseRecognitionExtra.CREATOR);
            return new KeyphraseRecognitionEvent(status, soundModelHandle,
                    captureAvailable, captureSession, captureDelayMs, capturePreambleMs,
                    triggerInData, captureFormat, data, keyphraseExtras, recognitionStillActive,
                    halEventReceivedMillis, token);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(status);
            dest.writeInt(soundModelHandle);
            dest.writeByte((byte) (captureAvailable ? 1 : 0));
            dest.writeInt(captureSession);
            dest.writeInt(captureDelayMs);
            dest.writeInt(capturePreambleMs);
            dest.writeByte((byte) (triggerInData ? 1 : 0));
            if (captureFormat != null) {
                dest.writeByte((byte)1);
                dest.writeInt(captureFormat.getSampleRate());
                dest.writeInt(captureFormat.getEncoding());
                dest.writeInt(captureFormat.getChannelMask());
            } else {
                dest.writeByte((byte)0);
            }
            dest.writeBlob(data);
            dest.writeBoolean(recognitionStillActive);
            dest.writeLong(halEventReceivedMillis);
            dest.writeStrongBinder(token);
            dest.writeTypedArray(keyphraseExtras, flags);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + Arrays.hashCode(keyphraseExtras);
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj)
                return true;
            if (!super.equals(obj))
                return false;
            if (getClass() != obj.getClass())
                return false;
            KeyphraseRecognitionEvent other = (KeyphraseRecognitionEvent) obj;
            if (!Arrays.equals(keyphraseExtras, other.keyphraseExtras))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "KeyphraseRecognitionEvent [keyphraseExtras=" + Arrays.toString(keyphraseExtras)
                    + ", status=" + status
                    + ", soundModelHandle=" + soundModelHandle
                    + ", captureAvailable=" + captureAvailable
                    + ", captureSession=" + captureSession
                    + ", captureDelayMs=" + captureDelayMs
                    + ", capturePreambleMs=" + capturePreambleMs
                    + ", triggerInData=" + triggerInData
                    + ((captureFormat == null) ? "" :
                        (", sampleRate=" + captureFormat.getSampleRate()))
                    + ((captureFormat == null) ? "" :
                        (", encoding=" + captureFormat.getEncoding()))
                    + ((captureFormat == null) ? "" :
                        (", channelMask=" + captureFormat.getChannelMask()))
                    + ", data=" + (data == null ? 0 : data.length)
                    + ", recognitionStillActive=" + recognitionStillActive
                    + ", halEventReceivedMillis=" + halEventReceivedMillis
                    + ", token=" + token
                    + "]";
        }
    }

    /**
     * Sub-class of RecognitionEvent specifically for sound-trigger based sound
     * models(non-keyphrase). Currently does not contain any additional fields.
     *
     * @hide
     */
    public static class GenericRecognitionEvent extends RecognitionEvent implements Parcelable {
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public GenericRecognitionEvent(int status, int soundModelHandle, boolean captureAvailable,
                int captureSession, int captureDelayMs, int capturePreambleMs,
                boolean triggerInData, @NonNull AudioFormat captureFormat, @Nullable byte[] data,
                @ElapsedRealtimeLong long halEventReceivedMillis,
                IBinder token) {
            this(status, soundModelHandle, captureAvailable,
                    captureSession, captureDelayMs,
                    capturePreambleMs, triggerInData, captureFormat, data,
                    status == RECOGNITION_STATUS_GET_STATE_RESPONSE,
                    halEventReceivedMillis, token);
        }

        public GenericRecognitionEvent(int status, int soundModelHandle, boolean captureAvailable,
                int captureSession, int captureDelayMs, int capturePreambleMs,
                boolean triggerInData, @NonNull AudioFormat captureFormat, @Nullable byte[] data,
                boolean recognitionStillActive, @ElapsedRealtimeLong long halEventReceivedMillis,
                IBinder token) {
            super(status, soundModelHandle, captureAvailable,
                    captureSession, captureDelayMs, capturePreambleMs, triggerInData, captureFormat,
                    data, recognitionStillActive, halEventReceivedMillis, token);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<GenericRecognitionEvent> CREATOR
                = new Parcelable.Creator<GenericRecognitionEvent>() {
            public GenericRecognitionEvent createFromParcel(Parcel in) {
                return GenericRecognitionEvent.fromParcelForGeneric(in);
            }

            public GenericRecognitionEvent[] newArray(int size) {
                return new GenericRecognitionEvent[size];
            }
        };

        private static GenericRecognitionEvent fromParcelForGeneric(Parcel in) {
            RecognitionEvent event = RecognitionEvent.fromParcel(in);
            return new GenericRecognitionEvent(event.status, event.soundModelHandle,
                    event.captureAvailable, event.captureSession, event.captureDelayMs,
                    event.capturePreambleMs, event.triggerInData, event.captureFormat, event.data,
                    event.recognitionStillActive, event.halEventReceivedMillis, event.token);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass()) return false;
            RecognitionEvent other = (RecognitionEvent) obj;
            return super.equals(obj);
        }

        @Override
        public String toString() {
            return "GenericRecognitionEvent ::" + super.toString();
        }
    }

    private static Object mServiceLock = new Object();

    /**
     * Translate an exception thrown from interaction with the underlying service to an error code.
     * Throws a runtime exception for unexpected conditions.
     * @param e The caught exception.
     * @return The error code.
     *
     * @hide
     */
    public static int handleException(Exception e) {
        Log.w(TAG, "Exception caught", e);
        if (e instanceof RemoteException) {
            return STATUS_DEAD_OBJECT;
        }
        if (e instanceof ServiceSpecificException) {
            switch (((ServiceSpecificException) e).errorCode) {
                case Status.OPERATION_NOT_SUPPORTED:
                    return STATUS_INVALID_OPERATION;
                case Status.TEMPORARY_PERMISSION_DENIED:
                    return STATUS_PERMISSION_DENIED;
                case Status.DEAD_OBJECT:
                    return STATUS_DEAD_OBJECT;
                case Status.INTERNAL_ERROR:
                    return STATUS_ERROR;
                case Status.RESOURCE_CONTENTION:
                    return STATUS_BUSY;
            }
            return STATUS_ERROR;
        }
        if (e instanceof SecurityException) {
            return STATUS_PERMISSION_DENIED;
        }
        if (e instanceof IllegalStateException) {
            return STATUS_INVALID_OPERATION;
        }
        if (e instanceof IllegalArgumentException || e instanceof NullPointerException) {
            return STATUS_BAD_VALUE;
        }
        // This is not one of the conditions represented by our error code, escalate to a
        // RuntimeException.
        Log.e(TAG, "Escalating unexpected exception: ", e);
        throw new RuntimeException(e);
    }

    /**
     * Returns a list of descriptors for all hardware modules loaded.
     * @param modules A ModuleProperties array where the list will be returned.
     * @return - {@link #STATUS_OK} in case of success
     *         - {@link #STATUS_ERROR} in case of unspecified error
     *         - {@link #STATUS_PERMISSION_DENIED} if the caller does not have system permission
     *         - {@link #STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link #STATUS_BAD_VALUE} if modules is null
     *         - {@link #STATUS_DEAD_OBJECT} if the binder transaction to the native service fails
     *
     * @removed Please use {@link #listModulesAsOriginator(ArrayList, Identity)} or
     * {@link #listModulesAsMiddleman(ArrayList, Identity, Identity)}, based on whether the
     * client is acting on behalf of its own identity or a separate identity.
     * @hide
     */
    @UnsupportedAppUsage
    public static int listModules(@NonNull ArrayList<ModuleProperties> modules) {
        return STATUS_OK;
    }

    /**
     * Returns a list of descriptors for all hardware modules loaded.
     * This variant is intended for use when the caller itself is the originator of the operation.
     * @param modules A ModuleProperties array where the list will be returned.
     * @param originatorIdentity The identity of the originator, which will be used for permission
     *                           purposes.
     * @return - {@link #STATUS_OK} in case of success
     *         - {@link #STATUS_ERROR} in case of unspecified error
     *         - {@link #STATUS_PERMISSION_DENIED} if the caller does not have system permission
     *         - {@link #STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link #STATUS_BAD_VALUE} if modules is null
     *         - {@link #STATUS_DEAD_OBJECT} if the binder transaction to the native service fails
     * @deprecated Use {@link android.media.soundtrigger.SoundTriggerManager} instead.
     * @hide
     */
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    @Deprecated
    public static int listModulesAsOriginator(@NonNull ArrayList<ModuleProperties> modules,
            @NonNull Identity originatorIdentity) {
        try {
            SoundTriggerModuleDescriptor[] descs = getService().listModulesAsOriginator(
                    originatorIdentity);
            convertDescriptorsToModuleProperties(descs, modules);
            return STATUS_OK;
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Returns a list of descriptors for all hardware modules loaded.
     * This variant is intended for use when the caller is acting on behalf of a different identity
     * for permission purposes.
     * @param modules A ModuleProperties array where the list will be returned.
     * @param middlemanIdentity The identity of the caller, acting as middleman.
     * @param originatorIdentity The identity of the originator, which will be used for permission
     *                           purposes.
     * @return - {@link #STATUS_OK} in case of success
     *         - {@link #STATUS_ERROR} in case of unspecified error
     *         - {@link #STATUS_PERMISSION_DENIED} if the caller does not have system permission
     *         - {@link #STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link #STATUS_BAD_VALUE} if modules is null
     *         - {@link #STATUS_DEAD_OBJECT} if the binder transaction to the native service fails
     *
     * @deprecated Use {@link android.media.soundtrigger.SoundTriggerManager} instead.
     * @hide
     */
    @RequiresPermission(SOUNDTRIGGER_DELEGATE_IDENTITY)
    @Deprecated
    public static int listModulesAsMiddleman(@NonNull ArrayList<ModuleProperties> modules,
            @NonNull Identity middlemanIdentity,
            @NonNull Identity originatorIdentity) {
        try {
            SoundTriggerModuleDescriptor[] descs = getService().listModulesAsMiddleman(
                    middlemanIdentity, originatorIdentity);
            convertDescriptorsToModuleProperties(descs, modules);
            return STATUS_OK;
        } catch (Exception e) {
            return handleException(e);
        }
    }

    /**
     * Converts an array of SoundTriggerModuleDescriptor into an (existing) ArrayList of
     * ModuleProperties.
     * @param descsIn The input descriptors.
     * @param modulesOut The output list.
     */
    private static void convertDescriptorsToModuleProperties(
            @NonNull SoundTriggerModuleDescriptor[] descsIn,
            @NonNull ArrayList<ModuleProperties> modulesOut) {
        modulesOut.clear();
        modulesOut.ensureCapacity(descsIn.length);
        for (SoundTriggerModuleDescriptor desc : descsIn) {
            modulesOut.add(ConversionUtil.aidl2apiModuleDescriptor(desc));
        }
    }

    /**
     * Get an interface on a hardware module to control sound models and recognition on
     * this module.
     * @param moduleId Sound module system identifier {@link ModuleProperties#mId}. mandatory.
     * @param listener {@link StatusListener} interface. Mandatory.
     * @param handler the Handler that will receive the callabcks. Can be null if default handler
     *                is OK.
     * @return a valid sound module in case of success or null in case of error.
     *
     * @removed Use {@link android.media.soundtrigger.SoundTriggerManager} instead.
     * @hide
     */
    @UnsupportedAppUsage
    private static SoundTriggerModule attachModule(int moduleId,
            @NonNull StatusListener listener,
            @Nullable Handler handler) {
            return null;
    }

    /**
     * Get an interface on a hardware module to control sound models and recognition on
     * this module.
     * This variant is intended for use when the caller is acting on behalf of a different identity
     * for permission purposes.
     * @param moduleId Sound module system identifier {@link ModuleProperties#mId}. mandatory.
     * @param listener {@link StatusListener} interface. Mandatory.
     * @param handler the Handler that will receive the callabcks. Can be null if default handler
     *                is OK.
     * @param middlemanIdentity The identity of the caller, acting as middleman.
     * @param originatorIdentity The identity of the originator, which will be used for permission
     *                           purposes.
     * @return a valid sound module in case of success or null in case of error.
     * @deprecated Use {@link android.media.soundtrigger.SoundTriggerManager} instead.
     * @hide
     */
    @RequiresPermission(SOUNDTRIGGER_DELEGATE_IDENTITY)
    @Deprecated
    public static SoundTriggerModule attachModuleAsMiddleman(int moduleId,
            @NonNull SoundTrigger.StatusListener listener,
            @Nullable Handler handler, Identity middlemanIdentity,
            Identity originatorIdentity) {
        Looper looper = handler != null ? handler.getLooper() : Looper.getMainLooper();
        try {
            return new SoundTriggerModule(getService(), moduleId, listener, looper,
                    middlemanIdentity, originatorIdentity, false);
        } catch (Exception e) {
            Log.e(TAG, "", e);
            return null;
        }
    }

    /**
     * Get an interface on a hardware module to control sound models and recognition on
     * this module.
     * This variant is intended for use when the caller itself is the originator of the operation.
     * @param moduleId Sound module system identifier {@link ModuleProperties#mId}. mandatory.
     * @param listener {@link StatusListener} interface. Mandatory.
     * @param handler the Handler that will receive the callabcks. Can be null if default handler
     *                is OK.
     * @param originatorIdentity The identity of the originator, which will be used for permission
     *                           purposes.
     * @return a valid sound module in case of success or null in case of error.
     *
     * @hide
     */
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    public static SoundTriggerModule attachModuleAsOriginator(int moduleId,
            @NonNull SoundTrigger.StatusListener listener,
            @Nullable Handler handler, @NonNull Identity originatorIdentity) {
        Looper looper = handler != null ? handler.getLooper() : Looper.getMainLooper();
        try {
            return new SoundTriggerModule(getService(), moduleId, listener, looper,
                    originatorIdentity);
        } catch (Exception e) {
            Log.e(TAG, "", e);
            return null;
        }
    }

    private static ISoundTriggerMiddlewareService getService() {
        synchronized (mServiceLock) {
            while (true) {
                IBinder binder = null;
                try {
                    binder =
                            ServiceManager.getServiceOrThrow(
                                    Context.SOUND_TRIGGER_MIDDLEWARE_SERVICE);
                    return ISoundTriggerMiddlewareService.Stub.asInterface(binder);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to bind to soundtrigger service", e);
                }
            }
        }
    }

    /**
     * Interface provided by the client application when attaching to a {@link SoundTriggerModule}
     * to received recognition and error notifications.
     *
     * @hide
     */
    public interface StatusListener {
        /**
         * Called when recognition succeeds of fails
         */
        void onRecognition(RecognitionEvent event);

        /**
         * Called when a sound model has been preemptively unloaded by the underlying
         * implementation.
         */
        void onModelUnloaded(int modelHandle);

        /**
         * Called whenever underlying conditions change, such that load/start operations that have
         * previously failed or got preempted may now succeed. This is not a guarantee, merely a
         * hint that the client may want to retry operations.
         */
        void onResourcesAvailable();

        /**
         * Called when the sound trigger native service dies
         */
        void onServiceDied();
    }
}
