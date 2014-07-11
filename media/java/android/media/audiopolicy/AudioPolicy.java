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
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * @hide CANDIDATE FOR PUBLIC API
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
    private AudioPolicyStatusListener mStatusListener = null;

    private final IBinder mToken = new Binder();
    /** @hide */
    public IBinder token() { return mToken; }

    private AudioPolicyConfig mConfig;
    /** @hide */
    public AudioPolicyConfig getConfig() { return mConfig; }

    /**
     * The parameter is guaranteed non-null through the Builder
     */
    private AudioPolicy(AudioPolicyConfig config) {
        mConfig = config;
        if (mConfig.mMixes.isEmpty()) {
            mStatus = POLICY_STATUS_INVALID;
        } else {
            mStatus = POLICY_STATUS_UNREGISTERED;
        }
    }

    /**
     * Builder class for {@link AudioPolicy} objects
     */
    public static class Builder {
        private ArrayList<AudioMix> mMixes;

        /**
         * Constructs a new Builder with no audio mixes.
         */
        public Builder() {
            mMixes = new ArrayList<AudioMix>();
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
            return new AudioPolicy(new AudioPolicyConfig(mMixes));
        }
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
    @Override
    public String toString () {
        String textDump = new String("android.media.audiopolicy.AudioPolicy:\n");
        textDump += "config=" + mConfig.toString();
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
