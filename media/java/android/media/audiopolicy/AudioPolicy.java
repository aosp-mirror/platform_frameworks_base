/*
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

package android.media.audiopolicy;

import android.annotation.IntDef;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioSystem;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * @hide
 * AudioPolicy provides access to the management of audio routing and audio focus.
 */
public class AudioPolicy {

    private static final String TAG = "AudioPolicy";

    /**
     * The status of an audio policy that cannot be used because it is invalid.
     */
    public static final int POLICY_STATUS_INVALID = 0;
    /**
     * The status of an audio policy that is valid but cannot be used because it is not registered.
     */
    public static final int POLICY_STATUS_UNREGISTERED = 1;
    /**
     * The status of an audio policy that is valid, successfully registered and thus active.
     */
    public static final int POLICY_STATUS_REGISTERED = 2;

    private int mStatus;
    private String mRegistrationId;
    private AudioPolicyStatusListener mStatusListener;

    private final IBinder mToken = new Binder();
    /** @hide */
    public IBinder token() { return mToken; }
    private Context mContext;

    private AudioPolicyConfig mConfig;
    /** @hide */
    public AudioPolicyConfig getConfig() { return mConfig; }

    /**
     * The parameter is guaranteed non-null through the Builder
     */
    private AudioPolicy(AudioPolicyConfig config, Context context) {
        mConfig = config;
        if (mConfig.mMixes.isEmpty()) {
            mStatus = POLICY_STATUS_INVALID;
        } else {
            mStatus = POLICY_STATUS_UNREGISTERED;
        }
        mContext = context;
    }

    /**
     * Builder class for {@link AudioPolicy} objects
     */
    public static class Builder {
        private ArrayList<AudioMix> mMixes;
        private Context mContext;

        /**
         * Constructs a new Builder with no audio mixes.
         * @param context the context for the policy
         */
        public Builder(Context context) {
            mMixes = new ArrayList<AudioMix>();
            mContext = context;
        }

        /**
         * Add an {@link AudioMix} to be part of the audio policy being built.
         * @param mix a non-null {@link AudioMix} to be part of the audio policy.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public Builder addMix(AudioMix mix) throws IllegalArgumentException {
            if (mix == null) {
                throw new IllegalArgumentException("Illegal null AudioMix argument");
            }
            mMixes.add(mix);
            return this;
        }

        public AudioPolicy build() {
            return new AudioPolicy(new AudioPolicyConfig(mMixes), mContext);
        }
    }

    /** @hide */
    public void setRegistration(String regId) {
        mRegistrationId = regId;
        mConfig.setRegistration(regId);
    }

    private boolean policyReadyToUse() {
        if (mContext == null) {
            Log.e(TAG, "Cannot use AudioPolicy without context");
            return false;
        }
        if (mRegistrationId == null) {
            Log.e(TAG, "Cannot use unregistered AudioPolicy");
            return false;
        }
        if (!(PackageManager.PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.MODIFY_AUDIO_ROUTING))) {
            Slog.w(TAG, "Cannot use AudioPolicy for pid " + Binder.getCallingPid() + " / uid "
                    + Binder.getCallingUid() + ", needs MODIFY_AUDIO_ROUTING");
            return false;
        }
        return true;
    }

    private void checkMixReadyToUse(AudioMix mix, boolean forTrack)
            throws IllegalArgumentException{
        if (mix == null) {
            String msg = forTrack ? "Invalid null AudioMix for AudioTrack creation"
                    : "Invalid null AudioMix for AudioRecord creation";
            throw new IllegalArgumentException(msg);
        }
        if (!mConfig.mMixes.contains(mix)) {
            throw new IllegalArgumentException("Invalid mix: not part of this policy");
        }
        if ((mix.getRouteFlags() & AudioMix.ROUTE_FLAG_LOOP_BACK) != AudioMix.ROUTE_FLAG_LOOP_BACK)
        {
            throw new IllegalArgumentException("Invalid AudioMix: not defined for loop back");
        }
    }

    /**
     * @hide
     * Create an {@link AudioRecord} instance that is associated with the given {@link AudioMix}.
     * Audio buffers recorded through the created instance will contain the mix of the audio
     * streams that fed the given mixer.
     * @param mix a non-null {@link AudioMix} instance whose routing flags was defined with
     *     {@link AudioMix#ROUTE_FLAG_LOOP_BACK}, previously added to this policy.
     * @return a new {@link AudioRecord} instance whose data format is the one defined in the
     *     {@link AudioMix}, or null if this policy was not successfully registered
     *     with {@link AudioManager#registerAudioPolicy(AudioPolicy)}.
     * @throws IllegalArgumentException
     */
    public AudioRecord createAudioRecordSink(AudioMix mix) throws IllegalArgumentException {
        if (!policyReadyToUse()) {
            Log.e(TAG, "Cannot create AudioRecord sink for AudioMix");
            return null;
        }
        checkMixReadyToUse(mix, false/*not for an AudioTrack*/);
        // create the AudioRecord, configured for loop back, using the same format as the mix
        AudioRecord ar = new AudioRecord(
                new AudioAttributes.Builder()
                        .setInternalCapturePreset(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                        .addTag(mix.getRegistration())
                        .build(),
                mix.getFormat(),
                AudioRecord.getMinBufferSize(mix.getFormat().getSampleRate(),
                        // using stereo for buffer size to avoid the current poor support for masks
                        AudioFormat.CHANNEL_IN_STEREO, mix.getFormat().getEncoding()),
                AudioManager.AUDIO_SESSION_ID_GENERATE
                );
        return ar;
    }

    /**
     * @hide
     * Create an {@link AudioTrack} instance that is associated with the given {@link AudioMix}.
     * Audio buffers played through the created instance will be sent to the given mix
     * to be recorded through the recording APIs.
     * @param mix a non-null {@link AudioMix} instance whose routing flags was defined with
     *     {@link AudioMix#ROUTE_FLAG_LOOP_BACK}, previously added to this policy.
     * @returna new {@link AudioTrack} instance whose data format is the one defined in the
     *     {@link AudioMix}, or null if this policy was not successfully registered
     *     with {@link AudioManager#registerAudioPolicy(AudioPolicy)}.
     * @throws IllegalArgumentException
     */
    public AudioTrack createAudioTrackSource(AudioMix mix) throws IllegalArgumentException {
        if (!policyReadyToUse()) {
            Log.e(TAG, "Cannot create AudioTrack source for AudioMix");
            return null;
        }
        checkMixReadyToUse(mix, true/*for an AudioTrack*/);
        // create the AudioTrack, configured for loop back, using the same format as the mix
        AudioTrack at = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VIRTUAL_SOURCE)
                        .addTag(mix.getRegistration())
                        .build(),
                mix.getFormat(),
                AudioTrack.getMinBufferSize(mix.getFormat().getSampleRate(),
                        mix.getFormat().getChannelMask(), mix.getFormat().getEncoding()),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
                );
        return at;
    }

    public int getStatus() {
        return mStatus;
    }

    public static abstract class AudioPolicyStatusListener {
        void onStatusChange() {}
        void onMixStateUpdate(AudioMix mix) {}
    }

    void setStatusListener(AudioPolicyStatusListener l) {
        mStatusListener = l;
    }

    /** @hide */
    public String toLogFriendlyString() {
        String textDump = new String("android.media.audiopolicy.AudioPolicy:\n");
        textDump += "config=" + mConfig.toLogFriendlyString();
        return (textDump);
    }

    /** @hide */
    @IntDef({
        POLICY_STATUS_INVALID,
        POLICY_STATUS_REGISTERED,
        POLICY_STATUS_UNREGISTERED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PolicyStatus {}
}
