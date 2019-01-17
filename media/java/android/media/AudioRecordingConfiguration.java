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
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.media.audiofx.AudioEffect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    private final int mClientSessionId;

    private final int mClientSource;

    private final AudioFormat mDeviceFormat;
    private final AudioFormat mClientFormat;

    @NonNull private final String mClientPackageName;
    private final int mClientUid;

    private final int mPatchHandle;

    private final int mClientPortId;

    private boolean mClientSilenced;

    private final int mDeviceSource;

    private final AudioEffect.Descriptor[] mClientEffects;

    private final AudioEffect.Descriptor[] mDeviceEffects;

    /**
     * @hide
     */
    @TestApi
    public AudioRecordingConfiguration(int uid, int session, int source, AudioFormat clientFormat,
            AudioFormat devFormat, int patchHandle, String packageName, int clientPortId,
            boolean clientSilenced, int deviceSource,
            AudioEffect.Descriptor[] clientEffects, AudioEffect.Descriptor[] deviceEffects) {
        mClientUid = uid;
        mClientSessionId = session;
        mClientSource = source;
        mClientFormat = clientFormat;
        mDeviceFormat = devFormat;
        mPatchHandle = patchHandle;
        mClientPackageName = packageName;
        mClientPortId = clientPortId;
        mClientSilenced = clientSilenced;
        mDeviceSource = deviceSource;
        mClientEffects = clientEffects;
        mDeviceEffects = deviceEffects;
    }

    /**
     * @hide
     */
    @TestApi
    public AudioRecordingConfiguration(int uid, int session, int source,
                                       AudioFormat clientFormat, AudioFormat devFormat,
                                       int patchHandle, String packageName) {
        this(uid, session, source, clientFormat,
                   devFormat, patchHandle, packageName, 0 /*clientPortId*/,
                   false /*clientSilenced*/, MediaRecorder.AudioSource.DEFAULT /*deviceSource*/,
                   new AudioEffect.Descriptor[0] /*clientEffects*/,
                   new AudioEffect.Descriptor[0] /*deviceEffects*/);
    }

    /**
     * @hide
     * For AudioService dump
     * @param pw
     */
    public void dump(PrintWriter pw) {
        pw.println("  " + toLogFriendlyString(this));
    }

    /**
     * @hide
     */
    public static String toLogFriendlyString(AudioRecordingConfiguration arc) {
        String clientEffects = new String();
        for (AudioEffect.Descriptor desc : arc.mClientEffects) {
            clientEffects += "'" + desc.name + "' ";
        }
        String deviceEffects = new String();
        for (AudioEffect.Descriptor desc : arc.mDeviceEffects) {
            deviceEffects += "'" + desc.name + "' ";
        }

        return new String("session:" + arc.mClientSessionId
                + " -- source client=" + MediaRecorder.toLogFriendlyAudioSource(arc.mClientSource)
                + ", dev=" + arc.mDeviceFormat.toLogFriendlyString()
                + " -- uid:" + arc.mClientUid
                + " -- patch:" + arc.mPatchHandle
                + " -- pack:" + arc.mClientPackageName
                + " -- format client=" + arc.mClientFormat.toLogFriendlyString()
                + ", dev=" + arc.mDeviceFormat.toLogFriendlyString()
                + " -- silenced:" + arc.mClientSilenced
                + " -- effects client=" + clientEffects
                + ", dev=" + deviceEffects);
    }

    // Note that this method is called server side, so no "privileged" information is ever sent
    // to a client that is not supposed to have access to it.
    /**
     * @hide
     * Creates a copy of the recording configuration that is stripped of any data enabling
     * identification of which application it is associated with ("anonymized").
     * @param in
     */
    public static AudioRecordingConfiguration anonymizedCopy(AudioRecordingConfiguration in) {
        return new AudioRecordingConfiguration( /*anonymized uid*/ -1,
                in.mClientSessionId, in.mClientSource, in.mClientFormat,
                in.mDeviceFormat, in.mPatchHandle, "" /*empty package name*/,
                in.mClientPortId, in.mClientSilenced, in.mDeviceSource, in.mClientEffects,
                in.mDeviceEffects);
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
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_PERFORMANCE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioSource {}

    // documented return values match the sources that return false
    //   in MediaRecorder.isSystemOnlyAudioSource(source)
    /**
     * Returns the audio source selected by the client.
     * @return the audio source selected by the client.
     */
    public @AudioSource int getClientAudioSource() { return mClientSource; }

    /**
     * Returns the session number of the recording, see {@link AudioRecord#getAudioSessionId()}.
     * @return the session number.
     */
    public int getClientAudioSessionId() {
        return mClientSessionId;
    }

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
     * @pending for SystemApi
     * Returns the package name of the application performing the recording.
     * Where there are multiple packages sharing the same user id through the "sharedUserId"
     * mechanism, only the first one with that id will be returned
     * (see {@link PackageManager#getPackagesForUid(int)}).
     * <p>This information is only available if the caller has the
     * {@link android.Manifest.permission.MODIFY_AUDIO_ROUTING} permission.
     * <br>When called without the permission, the result is an empty string.
     * @return the package name
     */
    @UnsupportedAppUsage
    public String getClientPackageName() { return mClientPackageName; }

    /**
     * @pending for SystemApi
     * Returns the user id of the application performing the recording.
     * <p>This information is only available if the caller has the
     * {@link android.Manifest.permission.MODIFY_AUDIO_ROUTING}
     * permission.
     * <br>The result is -1 without the permission.
     * @return the user id
     */
    @UnsupportedAppUsage
    public int getClientUid() { return mClientUid; }

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

    /**
     * Returns the system unique ID assigned for the AudioRecord object corresponding to this
     * AudioRecordingConfiguration client.
     * @return the port ID.
     */
    int getClientPortId() {
        return mClientPortId;
    }

    /**
     * Returns true if the audio returned to the client is currently being silenced by the
     * audio framework due to concurrent capture policy (e.g the capturing application does not have
     * an active foreground process or service anymore).
     * @return true if captured audio is silenced, false otherwise .
     */
    public boolean isClientSilenced() {
        return mClientSilenced;
    }

    /**
     * Returns the audio source currently used to configure the capture path. It can be different
     * from the source returned by {@link #getClientAudioSource()} if another capture is active.
     * @return the audio source active on the capture path.
     */
    public @AudioSource int getAudioSource() {
        return mDeviceSource;
    }

    /**
     * Returns the list of {@link AudioEffect.Descriptor} for all effects currently enabled on
     * the audio capture client (e.g. {@link AudioRecord} or {@link MediaRecorder}).
     * @return List of {@link AudioEffect.Descriptor} containing all effects enabled for the client.
     */
    public @NonNull List<AudioEffect.Descriptor> getClientEffects() {
        return new ArrayList<AudioEffect.Descriptor>(Arrays.asList(mClientEffects));
    }

    /**
     * Returns the list of {@link AudioEffect.Descriptor} for all effects currently enabled on
     * the capture stream.
     * @return List of {@link AudioEffect.Descriptor} containing all effects enabled on the
     * capture stream. This can be different from the list returned by {@link #getClientEffects()}
     * if another capture is active.
     */
    public @NonNull List<AudioEffect.Descriptor> getEffects() {
        return new ArrayList<AudioEffect.Descriptor>(Arrays.asList(mDeviceEffects));
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
        return Objects.hash(mClientSessionId, mClientSource);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mClientSessionId);
        dest.writeInt(mClientSource);
        mClientFormat.writeToParcel(dest, 0);
        mDeviceFormat.writeToParcel(dest, 0);
        dest.writeInt(mPatchHandle);
        dest.writeString(mClientPackageName);
        dest.writeInt(mClientUid);
        dest.writeInt(mClientPortId);
        dest.writeBoolean(mClientSilenced);
        dest.writeInt(mDeviceSource);
        dest.writeInt(mClientEffects.length);
        for (int i = 0; i < mClientEffects.length; i++) {
            mClientEffects[i].writeToParcel(dest, 0);
        }
        dest.writeInt(mDeviceEffects.length);
        for (int i = 0; i < mDeviceEffects.length; i++) {
            mDeviceEffects[i].writeToParcel(dest, 0);
        }
    }

    private AudioRecordingConfiguration(Parcel in) {
        mClientSessionId = in.readInt();
        mClientSource = in.readInt();
        mClientFormat = AudioFormat.CREATOR.createFromParcel(in);
        mDeviceFormat = AudioFormat.CREATOR.createFromParcel(in);
        mPatchHandle = in.readInt();
        mClientPackageName = in.readString();
        mClientUid = in.readInt();
        mClientPortId = in.readInt();
        mClientSilenced = in.readBoolean();
        mDeviceSource = in.readInt();
        mClientEffects = AudioEffect.Descriptor.CREATOR.newArray(in.readInt());
        for (int i = 0; i < mClientEffects.length; i++) {
            mClientEffects[i] = AudioEffect.Descriptor.CREATOR.createFromParcel(in);
        }
        mDeviceEffects = AudioEffect.Descriptor.CREATOR.newArray(in.readInt());
        for (int i = 0; i < mClientEffects.length; i++) {
            mDeviceEffects[i] = AudioEffect.Descriptor.CREATOR.createFromParcel(in);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof AudioRecordingConfiguration)) return false;

        AudioRecordingConfiguration that = (AudioRecordingConfiguration) o;

        return ((mClientUid == that.mClientUid)
                && (mClientSessionId == that.mClientSessionId)
                && (mClientSource == that.mClientSource)
                && (mPatchHandle == that.mPatchHandle)
                && (mClientFormat.equals(that.mClientFormat))
                && (mDeviceFormat.equals(that.mDeviceFormat))
                && (mClientPackageName.equals(that.mClientPackageName))
                && (mClientPortId == that.mClientPortId)
                && (mClientSilenced == that.mClientSilenced)
                && (mDeviceSource == that.mDeviceSource)
                && (mClientEffects.equals(that.mClientEffects))
                && (mDeviceEffects.equals(that.mDeviceEffects)));
    }
}
