/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;

/**
 * The AudioRecordingConfiguration class collects the information describing an audio recording
 * session.
 * <p>Direct polling (see {@link AudioManager#getActiveRecordingConfigurations()}) or callback
 * (see {@link AudioManager#registerAudioRecordingCallback(android.media.AudioManager.AudioRecordingCallback, android.os.Handler)}
 * methods are ways to receive information about the current recording configuration of the device.
 * <p>An audio recording configuration contains information about the recording format as used by
 * the application ({@link #getClientFormat()}, as well as the recording format actually used by
 * the device ({@link #getFormat()}). The two recording formats may, for instance, be at different
 * sampling rates due to hardware limitations (e.g. application recording at 44.1kHz whereas the
 * device always records at 48kHz, and the Android framework resamples for the application).
 * <p>The configuration also contains the use case for which audio is recorded
 * ({@link #getClientAudioSource()}), enabling the ability to distinguish between different
 * activities such as ongoing voice recognition or camcorder recording.
 *
 */
public final class AudioRecordingConfiguration implements Parcelable {
    private final static String TAG = new String("AudioRecordingConfiguration");

    private final int mSessionId;

    private final int mClientSource;

    private final AudioFormat mDeviceFormat;
    private final AudioFormat mClientFormat;

    private final int mPatchHandle;

    /**
     * @hide
     */
    public AudioRecordingConfiguration(int session, int source, AudioFormat devFormat,
            AudioFormat clientFormat, int patchHandle) {
        mSessionId = session;
        mClientSource = source;
        mDeviceFormat = devFormat;
        mClientFormat = clientFormat;
        mPatchHandle = patchHandle;
    }

    // matches the sources that return false in MediaRecorder.isSystemOnlyAudioSource(source)
    /** @hide */
    @IntDef({
        MediaRecorder.AudioSource.DEFAULT,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_UPLINK,
        MediaRecorder.AudioSource.VOICE_DOWNLINK,
        MediaRecorder.AudioSource.VOICE_CALL,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.UNPROCESSED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioSource {}

    // documented return values match the sources that return false
    //   in MediaRecorder.isSystemOnlyAudioSource(source)
    /**
     * Returns the audio source being used for the recording.
     * @return one of {@link MediaRecorder.AudioSource#DEFAULT},
     *       {@link MediaRecorder.AudioSource#MIC},
     *       {@link MediaRecorder.AudioSource#VOICE_UPLINK},
     *       {@link MediaRecorder.AudioSource#VOICE_DOWNLINK},
     *       {@link MediaRecorder.AudioSource#VOICE_CALL},
     *       {@link MediaRecorder.AudioSource#CAMCORDER},
     *       {@link MediaRecorder.AudioSource#VOICE_RECOGNITION},
     *       {@link MediaRecorder.AudioSource#VOICE_COMMUNICATION},
     *       {@link MediaRecorder.AudioSource#UNPROCESSED}.
     */
    public @AudioSource int getClientAudioSource() { return mClientSource; }

    /**
     * Returns the session number of the recording, see {@link AudioRecord#getAudioSessionId()}.
     * @return the session number.
     */
    public int getClientAudioSessionId() { return mSessionId; }

    /**
     * Returns the audio format at which audio is recorded on this Android device.
     * Note that it may differ from the client application recording format
     * (see {@link #getClientFormat()}).
     * @return the device recording format
     */
    public AudioFormat getFormat() { return mDeviceFormat; }

    /**
     * Returns the audio format at which the client application is recording audio.
     * Note that it may differ from the actual recording format (see {@link #getFormat()}).
     * @return the recording format
     */
    public AudioFormat getClientFormat() { return mClientFormat; }

    /**
     * Returns information about the audio input device used for this recording.
     * @return the audio recording device or null if this information cannot be retrieved
     */
    public AudioDeviceInfo getAudioDevice() {
        // build the AudioDeviceInfo from the patch handle
        ArrayList<AudioPatch> patches = new ArrayList<AudioPatch>();
        if (AudioManager.listAudioPatches(patches) != AudioManager.SUCCESS) {
            Log.e(TAG, "Error retrieving list of audio patches");
            return null;
        }
        for (int i = 0 ; i < patches.size() ; i++) {
            final AudioPatch patch = patches.get(i);
            if (patch.id() == mPatchHandle) {
                final AudioPortConfig[] sources = patch.sources();
                if ((sources != null) && (sources.length > 0)) {
                    // not supporting multiple sources, so just look at the first source
                    final int devId = sources[0].port().id();
                    final AudioDeviceInfo[] devices =
                            AudioManager.getDevicesStatic(AudioManager.GET_DEVICES_INPUTS);
                    for (int j = 0; j < devices.length; j++) {
                        if (devices[j].getId() == devId) {
                            return devices[j];
                        }
                    }
                }
                // patch handle is unique, there won't be another with the same handle
                break;
            }
        }
        Log.e(TAG, "Couldn't find device for recording, did recording end already?");
        return null;
    }

    public static final Parcelable.Creator<AudioRecordingConfiguration> CREATOR
            = new Parcelable.Creator<AudioRecordingConfiguration>() {
        /**
         * Rebuilds an AudioRecordingConfiguration previously stored with writeToParcel().
         * @param p Parcel object to read the AudioRecordingConfiguration from
         * @return a new AudioRecordingConfiguration created from the data in the parcel
         */
        public AudioRecordingConfiguration createFromParcel(Parcel p) {
            return new AudioRecordingConfiguration(p);
        }
        public AudioRecordingConfiguration[] newArray(int size) {
            return new AudioRecordingConfiguration[size];
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(mSessionId, mClientSource);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSessionId);
        dest.writeInt(mClientSource);
        mClientFormat.writeToParcel(dest, 0);
        mDeviceFormat.writeToParcel(dest, 0);
        dest.writeInt(mPatchHandle);
    }

    private AudioRecordingConfiguration(Parcel in) {
        mSessionId = in.readInt();
        mClientSource = in.readInt();
        mClientFormat = AudioFormat.CREATOR.createFromParcel(in);
        mDeviceFormat = AudioFormat.CREATOR.createFromParcel(in);
        mPatchHandle = in.readInt();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof AudioRecordingConfiguration)) return false;

        AudioRecordingConfiguration that = (AudioRecordingConfiguration) o;

        return ((mSessionId == that.mSessionId)
                && (mClientSource == that.mClientSource)
                && (mPatchHandle == that.mPatchHandle)
                && (mClientFormat.equals(that.mClientFormat))
                && (mDeviceFormat.equals(that.mDeviceFormat)));
    }
}
