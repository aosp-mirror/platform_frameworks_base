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

import static android.system.OsConstants.EINVAL;
import static android.system.OsConstants.ENODEV;
import static android.system.OsConstants.ENOSYS;
import static android.system.OsConstants.EPERM;
import static android.system.OsConstants.EPIPE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityThread;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

/**
 * The SoundTrigger class provides access via JNI to the native service managing
 * the sound trigger HAL.
 *
 * @hide
 */
@SystemApi
public class SoundTrigger {

    private SoundTrigger() {
    }

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

    /*****************************************************************************
     * A ModuleProperties describes a given sound trigger hardware module
     * managed by the native sound trigger service. Each module has a unique
     * ID used to target any API call to this paricular module. Module
     * properties are returned by listModules() method.
     *
     ****************************************************************************/
    public static final class ModuleProperties implements Parcelable {
        /** Unique module ID provided by the native service */
        public final int id;

        /** human readable voice detection engine implementor */
        @NonNull
        public final String implementor;

        /** human readable voice detection engine description */
        @NonNull
        public final String description;

        /** Unique voice engine Id (changes with each version) */
        @NonNull
        public final UUID uuid;

        /** Voice detection engine version */
        public final int version;

        /** Maximum number of active sound models */
        public final int maxSoundModels;

        /** Maximum number of key phrases */
        public final int maxKeyphrases;

        /** Maximum number of users per key phrase */
        public final int maxUsers;

        /** Supported recognition modes (bit field, RECOGNITION_MODE_VOICE_TRIGGER ...) */
        public final int recognitionModes;

        /** Supports seamless transition to capture mode after recognition */
        public final boolean supportsCaptureTransition;

        /** Maximum buffering capacity in ms if supportsCaptureTransition() is true */
        public final int maxBufferMs;

        /** Supports capture by other use cases while detection is active */
        public final boolean supportsConcurrentCapture;

        /** Rated power consumption when detection is active with TDB silence/sound/speech ratio */
        public final int powerConsumptionMw;

        /** Returns the trigger (key phrase) capture in the binary data of the
         * recognition callback event */
        public final boolean returnsTriggerInEvent;

        ModuleProperties(int id, String implementor, String description,
                String uuid, int version, int maxSoundModels, int maxKeyphrases,
                int maxUsers, int recognitionModes, boolean supportsCaptureTransition,
                int maxBufferMs, boolean supportsConcurrentCapture,
                int powerConsumptionMw, boolean returnsTriggerInEvent) {
            this.id = id;
            this.implementor = implementor;
            this.description = description;
            this.uuid = UUID.fromString(uuid);
            this.version = version;
            this.maxSoundModels = maxSoundModels;
            this.maxKeyphrases = maxKeyphrases;
            this.maxUsers = maxUsers;
            this.recognitionModes = recognitionModes;
            this.supportsCaptureTransition = supportsCaptureTransition;
            this.maxBufferMs = maxBufferMs;
            this.supportsConcurrentCapture = supportsConcurrentCapture;
            this.powerConsumptionMw = powerConsumptionMw;
            this.returnsTriggerInEvent = returnsTriggerInEvent;
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
            int maxSoundModels = in.readInt();
            int maxKeyphrases = in.readInt();
            int maxUsers = in.readInt();
            int recognitionModes = in.readInt();
            boolean supportsCaptureTransition = in.readByte() == 1;
            int maxBufferMs = in.readInt();
            boolean supportsConcurrentCapture = in.readByte() == 1;
            int powerConsumptionMw = in.readInt();
            boolean returnsTriggerInEvent = in.readByte() == 1;
            return new ModuleProperties(id, implementor, description, uuid, version,
                    maxSoundModels, maxKeyphrases, maxUsers, recognitionModes,
                    supportsCaptureTransition, maxBufferMs, supportsConcurrentCapture,
                    powerConsumptionMw, returnsTriggerInEvent);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            dest.writeString(implementor);
            dest.writeString(description);
            dest.writeString(uuid.toString());
            dest.writeInt(version);
            dest.writeInt(maxSoundModels);
            dest.writeInt(maxKeyphrases);
            dest.writeInt(maxUsers);
            dest.writeInt(recognitionModes);
            dest.writeByte((byte) (supportsCaptureTransition ? 1 : 0));
            dest.writeInt(maxBufferMs);
            dest.writeByte((byte) (supportsConcurrentCapture ? 1 : 0));
            dest.writeInt(powerConsumptionMw);
            dest.writeByte((byte) (returnsTriggerInEvent ? 1 : 0));
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public String toString() {
            return "ModuleProperties [id=" + id + ", implementor=" + implementor + ", description="
                    + description + ", uuid=" + uuid + ", version=" + version + ", maxSoundModels="
                    + maxSoundModels + ", maxKeyphrases=" + maxKeyphrases + ", maxUsers="
                    + maxUsers + ", recognitionModes=" + recognitionModes
                    + ", supportsCaptureTransition=" + supportsCaptureTransition + ", maxBufferMs="
                    + maxBufferMs + ", supportsConcurrentCapture=" + supportsConcurrentCapture
                    + ", powerConsumptionMw=" + powerConsumptionMw
                    + ", returnsTriggerInEvent=" + returnsTriggerInEvent + "]";
        }
    }

    /*****************************************************************************
     * A SoundModel describes the attributes and contains the binary data used by the hardware
     * implementation to detect a particular sound pattern.
     * A specialized version {@link KeyphraseSoundModel} is defined for key phrase
     * sound models.
     *
     * @hide
     ****************************************************************************/
    public static class SoundModel {
        /** Undefined sound model type */
        public static final int TYPE_UNKNOWN = -1;

        /** Keyphrase sound model */
        public static final int TYPE_KEYPHRASE = 0;

        /**
         * A generic sound model. Use this type only for non-keyphrase sound models such as
         * ones that match a particular sound pattern.
         */
        public static final int TYPE_GENERIC_SOUND = 1;

        /** Unique sound model identifier */
        @UnsupportedAppUsage
        public final UUID uuid;

        /** Sound model type (e.g. TYPE_KEYPHRASE); */
        public final int type;

        /** Unique sound model vendor identifier */
        @UnsupportedAppUsage
        public final UUID vendorUuid;

        /** Opaque data. For use by vendor implementation and enrollment application */
        @UnsupportedAppUsage
        public final byte[] data;

        public SoundModel(UUID uuid, UUID vendorUuid, int type, byte[] data) {
            this.uuid = uuid;
            this.vendorUuid = vendorUuid;
            this.type = type;
            this.data = data;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode(data);
            result = prime * result + type;
            result = prime * result + ((uuid == null) ? 0 : uuid.hashCode());
            result = prime * result + ((vendorUuid == null) ? 0 : vendorUuid.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (!(obj instanceof SoundModel))
                return false;
            SoundModel other = (SoundModel) obj;
            if (!Arrays.equals(data, other.data))
                return false;
            if (type != other.type)
                return false;
            if (uuid == null) {
                if (other.uuid != null)
                    return false;
            } else if (!uuid.equals(other.uuid))
                return false;
            if (vendorUuid == null) {
                if (other.vendorUuid != null)
                    return false;
            } else if (!vendorUuid.equals(other.vendorUuid))
                return false;
            return true;
        }
    }

    /*****************************************************************************
     * A Keyphrase describes a key phrase that can be detected by a
     * {@link KeyphraseSoundModel}
     *
     * @hide
     ****************************************************************************/
    public static class Keyphrase implements Parcelable {
        /** Unique identifier for this keyphrase */
        @UnsupportedAppUsage
        public final int id;

        /** Recognition modes supported for this key phrase in the model */
        @UnsupportedAppUsage
        public final int recognitionModes;

        /** Locale of the keyphrase. JAVA Locale string e.g en_US */
        @UnsupportedAppUsage
        public final String locale;

        /** Key phrase text */
        @UnsupportedAppUsage
        public final String text;

        /** Users this key phrase has been trained for. countains sound trigger specific user IDs
         * derived from system user IDs {@link android.os.UserHandle#getIdentifier()}. */
        @UnsupportedAppUsage
        public final int[] users;

        @UnsupportedAppUsage
        public Keyphrase(int id, int recognitionModes, String locale, String text, int[] users) {
            this.id = id;
            this.recognitionModes = recognitionModes;
            this.locale = locale;
            this.text = text;
            this.users = users;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Keyphrase> CREATOR
                = new Parcelable.Creator<Keyphrase>() {
            public Keyphrase createFromParcel(Parcel in) {
                return Keyphrase.fromParcel(in);
            }

            public Keyphrase[] newArray(int size) {
                return new Keyphrase[size];
            }
        };

        private static Keyphrase fromParcel(Parcel in) {
            int id = in.readInt();
            int recognitionModes = in.readInt();
            String locale = in.readString();
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
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            dest.writeInt(recognitionModes);
            dest.writeString(locale);
            dest.writeString(text);
            if (users != null) {
                dest.writeInt(users.length);
                dest.writeIntArray(users);
            } else {
                dest.writeInt(-1);
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((text == null) ? 0 : text.hashCode());
            result = prime * result + id;
            result = prime * result + ((locale == null) ? 0 : locale.hashCode());
            result = prime * result + recognitionModes;
            result = prime * result + Arrays.hashCode(users);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Keyphrase other = (Keyphrase) obj;
            if (text == null) {
                if (other.text != null)
                    return false;
            } else if (!text.equals(other.text))
                return false;
            if (id != other.id)
                return false;
            if (locale == null) {
                if (other.locale != null)
                    return false;
            } else if (!locale.equals(other.locale))
                return false;
            if (recognitionModes != other.recognitionModes)
                return false;
            if (!Arrays.equals(users, other.users))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "Keyphrase [id=" + id + ", recognitionModes=" + recognitionModes + ", locale="
                    + locale + ", text=" + text + ", users=" + Arrays.toString(users) + "]";
        }
    }

    /*****************************************************************************
     * A KeyphraseSoundModel is a specialized {@link SoundModel} for key phrases.
     * It contains data needed by the hardware to detect a certain number of key phrases
     * and the list of corresponding {@link Keyphrase} descriptors.
     *
     * @hide
     ****************************************************************************/
    public static class KeyphraseSoundModel extends SoundModel implements Parcelable {
        /** Key phrases in this sound model */
        @UnsupportedAppUsage
        public final Keyphrase[] keyphrases; // keyword phrases in model

        @UnsupportedAppUsage
        public KeyphraseSoundModel(
                UUID uuid, UUID vendorUuid, byte[] data, Keyphrase[] keyphrases) {
            super(uuid, vendorUuid, TYPE_KEYPHRASE, data);
            this.keyphrases = keyphrases;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<KeyphraseSoundModel> CREATOR
                = new Parcelable.Creator<KeyphraseSoundModel>() {
            public KeyphraseSoundModel createFromParcel(Parcel in) {
                return KeyphraseSoundModel.fromParcel(in);
            }

            public KeyphraseSoundModel[] newArray(int size) {
                return new KeyphraseSoundModel[size];
            }
        };

        private static KeyphraseSoundModel fromParcel(Parcel in) {
            UUID uuid = UUID.fromString(in.readString());
            UUID vendorUuid = null;
            int length = in.readInt();
            if (length >= 0) {
                vendorUuid = UUID.fromString(in.readString());
            }
            byte[] data = in.readBlob();
            Keyphrase[] keyphrases = in.createTypedArray(Keyphrase.CREATOR);
            return new KeyphraseSoundModel(uuid, vendorUuid, data, keyphrases);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(uuid.toString());
            if (vendorUuid == null) {
                dest.writeInt(-1);
            } else {
                dest.writeInt(vendorUuid.toString().length());
                dest.writeString(vendorUuid.toString());
            }
            dest.writeBlob(data);
            dest.writeTypedArray(keyphrases, flags);
        }

        @Override
        public String toString() {
            return "KeyphraseSoundModel [keyphrases=" + Arrays.toString(keyphrases)
                    + ", uuid=" + uuid + ", vendorUuid=" + vendorUuid
                    + ", type=" + type + ", data=" + (data == null ? 0 : data.length) + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + Arrays.hashCode(keyphrases);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!super.equals(obj))
                return false;
            if (!(obj instanceof KeyphraseSoundModel))
                return false;
            KeyphraseSoundModel other = (KeyphraseSoundModel) obj;
            if (!Arrays.equals(keyphrases, other.keyphrases))
                return false;
            return true;
        }
    }


    /*****************************************************************************
     * A GenericSoundModel is a specialized {@link SoundModel} for non-voice sound
     * patterns.
     *
     * @hide
     ****************************************************************************/
    public static class GenericSoundModel extends SoundModel implements Parcelable {

        public static final @android.annotation.NonNull Parcelable.Creator<GenericSoundModel> CREATOR
                = new Parcelable.Creator<GenericSoundModel>() {
            public GenericSoundModel createFromParcel(Parcel in) {
                return GenericSoundModel.fromParcel(in);
            }

            public GenericSoundModel[] newArray(int size) {
                return new GenericSoundModel[size];
            }
        };

        @UnsupportedAppUsage
        public GenericSoundModel(UUID uuid, UUID vendorUuid, byte[] data) {
            super(uuid, vendorUuid, TYPE_GENERIC_SOUND, data);
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
            return new GenericSoundModel(uuid, vendorUuid, data);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(uuid.toString());
            if (vendorUuid == null) {
                dest.writeInt(-1);
            } else {
                dest.writeInt(vendorUuid.toString().length());
                dest.writeString(vendorUuid.toString());
            }
            dest.writeBlob(data);
        }

        @Override
        public String toString() {
            return "GenericSoundModel [uuid=" + uuid + ", vendorUuid=" + vendorUuid
                    + ", type=" + type + ", data=" + (data == null ? 0 : data.length) + "]";
        }
    }

    /**
     *  Modes for key phrase recognition
     */

    /**
     * Simple recognition of the key phrase
     *
     * @hide
     */
    public static final int RECOGNITION_MODE_VOICE_TRIGGER = 0x1;
    /**
     * Trigger only if one user is identified
     *
     * @hide
     */
    public static final int RECOGNITION_MODE_USER_IDENTIFICATION = 0x2;
    /**
     * Trigger only if one user is authenticated
     *
     * @hide
     */
    public static final int RECOGNITION_MODE_USER_AUTHENTICATION = 0x4;

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
        public final AudioFormat captureFormat;
        /**
         * Opaque data for use by system applications who know about voice engine internals,
         * typically during enrollment.
         *
         * @hide
         */
        @UnsupportedAppUsage
        public final byte[] data;

        /** @hide */
        @UnsupportedAppUsage
        public RecognitionEvent(int status, int soundModelHandle, boolean captureAvailable,
                int captureSession, int captureDelayMs, int capturePreambleMs,
                boolean triggerInData, AudioFormat captureFormat, byte[] data) {
            this.status = status;
            this.soundModelHandle = soundModelHandle;
            this.captureAvailable = captureAvailable;
            this.captureSession = captureSession;
            this.captureDelayMs = captureDelayMs;
            this.capturePreambleMs = capturePreambleMs;
            this.triggerInData = triggerInData;
            this.captureFormat = captureFormat;
            this.data = data;
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
        public byte[] getData() {
            return data;
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
            return new RecognitionEvent(status, soundModelHandle, captureAvailable, captureSession,
                    captureDelayMs, capturePreambleMs, triggerInData, captureFormat, data);
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
            if (soundModelHandle != other.soundModelHandle)
                return false;
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
                    + ", data=" + (data == null ? 0 : data.length) + "]";
        }
    }

    /**
     *  A RecognitionConfig is provided to
     *  {@link SoundTriggerModule#startRecognition(int, RecognitionConfig)} to configure the
     *  recognition request.
     *
     *  @hide
     */
    public static class RecognitionConfig implements Parcelable {
        /** True if the DSP should capture the trigger sound and make it available for further
         * capture. */
        @UnsupportedAppUsage
        public final boolean captureRequested;
        /**
         * True if the service should restart listening after the DSP triggers.
         * Note: This config flag is currently used at the service layer rather than by the DSP.
         */
        public final boolean allowMultipleTriggers;
        /** List of all keyphrases in the sound model for which recognition should be performed with
         * options for each keyphrase. */
        @UnsupportedAppUsage
        public final KeyphraseRecognitionExtra keyphrases[];
        /** Opaque data for use by system applications who know about voice engine internals,
         * typically during enrollment. */
        @UnsupportedAppUsage
        public final byte[] data;

        @UnsupportedAppUsage
        public RecognitionConfig(boolean captureRequested, boolean allowMultipleTriggers,
                KeyphraseRecognitionExtra[] keyphrases, byte[] data) {
            this.captureRequested = captureRequested;
            this.allowMultipleTriggers = allowMultipleTriggers;
            this.keyphrases = keyphrases;
            this.data = data;
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

        private static RecognitionConfig fromParcel(Parcel in) {
            boolean captureRequested = in.readByte() == 1;
            boolean allowMultipleTriggers = in.readByte() == 1;
            KeyphraseRecognitionExtra[] keyphrases =
                    in.createTypedArray(KeyphraseRecognitionExtra.CREATOR);
            byte[] data = in.readBlob();
            return new RecognitionConfig(captureRequested, allowMultipleTriggers, keyphrases, data);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeByte((byte) (captureRequested ? 1 : 0));
            dest.writeByte((byte) (allowMultipleTriggers ? 1 : 0));
            dest.writeTypedArray(keyphrases, flags);
            dest.writeBlob(data);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public String toString() {
            return "RecognitionConfig [captureRequested=" + captureRequested
                    + ", allowMultipleTriggers=" + allowMultipleTriggers + ", keyphrases="
                    + Arrays.toString(keyphrases) + ", data=" + Arrays.toString(data) + "]";
        }
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
        public boolean equals(Object obj) {
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
     *  Additional data conveyed by a {@link KeyphraseRecognitionEvent}
     *  for a key phrase detection.
     *
     * @hide
     */
    public static class KeyphraseRecognitionExtra implements Parcelable {
        /** The keyphrase ID */
        @UnsupportedAppUsage
        public final int id;

        /** Recognition modes matched for this event */
        @UnsupportedAppUsage
        public final int recognitionModes;

        /** Confidence level for mode RECOGNITION_MODE_VOICE_TRIGGER when user identification
         * is not performed */
        @UnsupportedAppUsage
        public final int coarseConfidenceLevel;

        /** Confidence levels for all users recognized (KeyphraseRecognitionEvent) or to
         * be recognized (RecognitionConfig) */
        @UnsupportedAppUsage
        public final ConfidenceLevel[] confidenceLevels;

        @UnsupportedAppUsage
        public KeyphraseRecognitionExtra(int id, int recognitionModes, int coarseConfidenceLevel,
                ConfidenceLevel[] confidenceLevels) {
            this.id = id;
            this.recognitionModes = recognitionModes;
            this.coarseConfidenceLevel = coarseConfidenceLevel;
            this.confidenceLevels = confidenceLevels;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<KeyphraseRecognitionExtra> CREATOR
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
        public void writeToParcel(Parcel dest, int flags) {
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
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            KeyphraseRecognitionExtra other = (KeyphraseRecognitionExtra) obj;
            if (!Arrays.equals(confidenceLevels, other.confidenceLevels))
                return false;
            if (id != other.id)
                return false;
            if (recognitionModes != other.recognitionModes)
                return false;
            if (coarseConfidenceLevel != other.coarseConfidenceLevel)
                return false;
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
        public final KeyphraseRecognitionExtra[] keyphraseExtras;

        @UnsupportedAppUsage
        public KeyphraseRecognitionEvent(int status, int soundModelHandle, boolean captureAvailable,
               int captureSession, int captureDelayMs, int capturePreambleMs,
               boolean triggerInData, AudioFormat captureFormat, byte[] data,
               KeyphraseRecognitionExtra[] keyphraseExtras) {
            super(status, soundModelHandle, captureAvailable, captureSession, captureDelayMs,
                  capturePreambleMs, triggerInData, captureFormat, data);
            this.keyphraseExtras = keyphraseExtras;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<KeyphraseRecognitionEvent> CREATOR
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
            KeyphraseRecognitionExtra[] keyphraseExtras =
                    in.createTypedArray(KeyphraseRecognitionExtra.CREATOR);
            return new KeyphraseRecognitionEvent(status, soundModelHandle, captureAvailable,
                    captureSession, captureDelayMs, capturePreambleMs, triggerInData,
                    captureFormat, data, keyphraseExtras);
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
        public boolean equals(Object obj) {
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
                    + ", soundModelHandle=" + soundModelHandle + ", captureAvailable="
                    + captureAvailable + ", captureSession=" + captureSession + ", captureDelayMs="
                    + captureDelayMs + ", capturePreambleMs=" + capturePreambleMs
                    + ", triggerInData=" + triggerInData
                    + ((captureFormat == null) ? "" :
                        (", sampleRate=" + captureFormat.getSampleRate()))
                    + ((captureFormat == null) ? "" :
                        (", encoding=" + captureFormat.getEncoding()))
                    + ((captureFormat == null) ? "" :
                        (", channelMask=" + captureFormat.getChannelMask()))
                    + ", data=" + (data == null ? 0 : data.length) + "]";
        }
    }

    /**
     * Sub-class of RecognitionEvent specifically for sound-trigger based sound
     * models(non-keyphrase). Currently does not contain any additional fields.
     *
     * @hide
     */
    public static class GenericRecognitionEvent extends RecognitionEvent implements Parcelable {
        @UnsupportedAppUsage
        public GenericRecognitionEvent(int status, int soundModelHandle,
                boolean captureAvailable, int captureSession, int captureDelayMs,
                int capturePreambleMs, boolean triggerInData, AudioFormat captureFormat,
                byte[] data) {
            super(status, soundModelHandle, captureAvailable, captureSession,
                    captureDelayMs, capturePreambleMs, triggerInData, captureFormat,
                    data);
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
                    event.capturePreambleMs, event.triggerInData, event.captureFormat, event.data);
        }

        @Override
        public boolean equals(Object obj) {
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

    /**
     *  Status codes for {@link SoundModelEvent}
     */
    /**
     * Sound Model was updated
     *
     * @hide
     */
    public static final int SOUNDMODEL_STATUS_UPDATED = 0;

    /**
     *  A SoundModelEvent is provided by the
     *  {@link StatusListener#onSoundModelUpdate(SoundModelEvent)}
     *  callback when a sound model has been updated by the implementation
     *
     *  @hide
     */
    public static class SoundModelEvent implements Parcelable {
        /** Status e.g {@link #SOUNDMODEL_STATUS_UPDATED} */
        public final int status;
        /** The updated sound model handle */
        public final int soundModelHandle;
        /** New sound model data */
        public final byte[] data;

        @UnsupportedAppUsage
        SoundModelEvent(int status, int soundModelHandle, byte[] data) {
            this.status = status;
            this.soundModelHandle = soundModelHandle;
            this.data = data;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<SoundModelEvent> CREATOR
                = new Parcelable.Creator<SoundModelEvent>() {
            public SoundModelEvent createFromParcel(Parcel in) {
                return SoundModelEvent.fromParcel(in);
            }

            public SoundModelEvent[] newArray(int size) {
                return new SoundModelEvent[size];
            }
        };

        private static SoundModelEvent fromParcel(Parcel in) {
            int status = in.readInt();
            int soundModelHandle = in.readInt();
            byte[] data = in.readBlob();
            return new SoundModelEvent(status, soundModelHandle, data);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(status);
            dest.writeInt(soundModelHandle);
            dest.writeBlob(data);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode(data);
            result = prime * result + soundModelHandle;
            result = prime * result + status;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            SoundModelEvent other = (SoundModelEvent) obj;
            if (!Arrays.equals(data, other.data))
                return false;
            if (soundModelHandle != other.soundModelHandle)
                return false;
            if (status != other.status)
                return false;
            return true;
        }

        @Override
        public String toString() {
            return "SoundModelEvent [status=" + status + ", soundModelHandle=" + soundModelHandle
                    + ", data=" + (data == null ? 0 : data.length) + "]";
        }
    }

    /**
     *  Native service state. {@link StatusListener#onServiceStateChange(int)}
     */
    // Keep in sync with system/core/include/system/sound_trigger.h
    /**
     * Sound trigger service is enabled
     *
     * @hide
     */
    public static final int SERVICE_STATE_ENABLED = 0;
    /**
     * Sound trigger service is disabled
     *
     * @hide
     */
    public static final int SERVICE_STATE_DISABLED = 1;

    /**
     * @return returns current package name.
     */
    static String getCurrentOpPackageName() {
        String packageName = ActivityThread.currentOpPackageName();
        if (packageName == null) {
            return "";
        }
        return packageName;
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
     * @hide
     */
    @UnsupportedAppUsage
    public static int listModules(ArrayList<ModuleProperties> modules) {
        return listModules(getCurrentOpPackageName(), modules);
    }

    /**
     * Returns a list of descriptors for all hardware modules loaded.
     * @param opPackageName
     * @param modules A ModuleProperties array where the list will be returned.
     * @return - {@link #STATUS_OK} in case of success
     *         - {@link #STATUS_ERROR} in case of unspecified error
     *         - {@link #STATUS_PERMISSION_DENIED} if the caller does not have system permission
     *         - {@link #STATUS_NO_INIT} if the native service cannot be reached
     *         - {@link #STATUS_BAD_VALUE} if modules is null
     *         - {@link #STATUS_DEAD_OBJECT} if the binder transaction to the native service fails
     */
    private static native int listModules(String opPackageName,
                                          ArrayList<ModuleProperties> modules);

    /**
     * Get an interface on a hardware module to control sound models and recognition on
     * this module.
     * @param moduleId Sound module system identifier {@link ModuleProperties#id}. mandatory.
     * @param listener {@link StatusListener} interface. Mandatory.
     * @param handler the Handler that will receive the callabcks. Can be null if default handler
     *                is OK.
     * @return a valid sound module in case of success or null in case of error.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static SoundTriggerModule attachModule(int moduleId,
                                                  StatusListener listener,
                                                  Handler handler) {
        if (listener == null) {
            return null;
        }
        SoundTriggerModule module = new SoundTriggerModule(moduleId, listener, handler);
        return module;
    }

    /**
     * Interface provided by the client application when attaching to a {@link SoundTriggerModule}
     * to received recognition and error notifications.
     *
     * @hide
     */
    public static interface StatusListener {
        /**
         * Called when recognition succeeds of fails
         */
        public abstract void onRecognition(RecognitionEvent event);

        /**
         * Called when a sound model has been updated
         */
        public abstract void onSoundModelUpdate(SoundModelEvent event);

        /**
         * Called when the sound trigger native service state changes.
         * @param state Native service state. One of {@link SoundTrigger#SERVICE_STATE_ENABLED},
         * {@link SoundTrigger#SERVICE_STATE_DISABLED}
         */
        public abstract void onServiceStateChange(int state);

        /**
         * Called when the sound trigger native service dies
         */
        public abstract void onServiceDied();
    }
}
