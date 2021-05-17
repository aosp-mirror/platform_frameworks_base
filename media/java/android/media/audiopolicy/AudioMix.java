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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.os.Build;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * @hide
 */
@SystemApi
public class AudioMix {

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private AudioMixingRule mRule;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private AudioFormat mFormat;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private int mRouteFlags;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private int mMixType = MIX_TYPE_INVALID;

    // written by AudioPolicy
    int mMixState = MIX_STATE_DISABLED;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    int mCallbackFlags;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    String mDeviceAddress;

    // initialized in constructor, read by AudioPolicyConfig
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    final int mDeviceSystemType; // an AudioSystem.DEVICE_* value, not AudioDeviceInfo.TYPE_*

    /**
     * All parameters are guaranteed valid through the Builder.
     */
    private AudioMix(AudioMixingRule rule, AudioFormat format, int routeFlags, int callbackFlags,
            int deviceType, String deviceAddress) {
        mRule = rule;
        mFormat = format;
        mRouteFlags = routeFlags;
        mMixType = rule.getTargetMixType();
        mCallbackFlags = callbackFlags;
        mDeviceSystemType = deviceType;
        mDeviceAddress = (deviceAddress == null) ? new String("") : deviceAddress;
    }

    // CALLBACK_FLAG_* values: keep in sync with AudioMix::kCbFlag* values defined
    // in frameworks/av/include/media/AudioPolicy.h
    /** @hide */
    public final static int CALLBACK_FLAG_NOTIFY_ACTIVITY = 0x1;
    // when adding new MIX_FLAG_* flags, add them to this mask of authorized masks:
    private final static int CALLBACK_FLAGS_ALL = CALLBACK_FLAG_NOTIFY_ACTIVITY;

    // ROUTE_FLAG_* values: keep in sync with MIX_ROUTE_FLAG_* values defined
    // in frameworks/av/include/media/AudioPolicy.h
    /**
     * An audio mix behavior where the output of the mix is sent to the original destination of
     * the audio signal, i.e. an output device for an output mix, or a recording for an input mix.
     */
    public static final int ROUTE_FLAG_RENDER    = 0x1;
    /**
     * An audio mix behavior where the output of the mix is rerouted back to the framework and
     * is accessible for injection or capture through the {@link AudioTrack} and {@link AudioRecord}
     * APIs.
     */
    public static final int ROUTE_FLAG_LOOP_BACK = 0x1 << 1;

    /**
     * An audio mix behavior where the targeted audio is played unaffected but a copy is
     * accessible for capture through {@link AudioRecord}.
     *
     * Only capture of playback is supported, not capture of capture.
     * Use concurrent capture instead to capture what is captured by other apps.
     *
     * The captured audio is an approximation of the played audio.
     * Effects and volume are not applied, and track are mixed with different delay then in the HAL.
     * As a result, this API is not suitable for echo cancelling.
     * @hide
     */
    public static final int ROUTE_FLAG_LOOP_BACK_RENDER = ROUTE_FLAG_LOOP_BACK | ROUTE_FLAG_RENDER;

    private static final int ROUTE_FLAG_SUPPORTED = ROUTE_FLAG_RENDER | ROUTE_FLAG_LOOP_BACK;

    // MIX_TYPE_* values to keep in sync with frameworks/av/include/media/AudioPolicy.h
    /**
     * @hide
     * Invalid mix type, default value.
     */
    public static final int MIX_TYPE_INVALID = -1;
    /**
     * Mix type indicating playback streams are mixed.
     */
    public static final int MIX_TYPE_PLAYERS = 0;
    /**
     * Mix type indicating recording streams are mixed.
     */
    public static final int MIX_TYPE_RECORDERS = 1;


    // MIX_STATE_* values to keep in sync with frameworks/av/include/media/AudioPolicy.h
    /**
     * State of a mix before its policy is enabled.
     */
    public static final int MIX_STATE_DISABLED = -1;
    /**
     * State of a mix when there is no audio to mix.
     */
    public static final int MIX_STATE_IDLE = 0;
    /**
     * State of a mix that is actively mixing audio.
     */
    public static final int MIX_STATE_MIXING = 1;

    /** Maximum sampling rate for privileged playback capture*/
    private static final int PRIVILEDGED_CAPTURE_MAX_SAMPLE_RATE = 16000;

    /** Maximum channel number for privileged playback capture*/
    private static final int PRIVILEDGED_CAPTURE_MAX_CHANNEL_NUMBER = 1;

    /** Maximum channel number for privileged playback capture*/
    private static final int PRIVILEDGED_CAPTURE_MAX_BYTES_PER_SAMPLE = 2;

    /**
     * The current mixing state.
     * @return one of {@link #MIX_STATE_DISABLED}, {@link #MIX_STATE_IDLE},
     *          {@link #MIX_STATE_MIXING}.
     */
    public int getMixState() {
        return mMixState;
    }


    /** @hide */
    public int getRouteFlags() {
        return mRouteFlags;
    }

    /** @hide */
    public AudioFormat getFormat() {
        return mFormat;
    }

    /** @hide */
    public AudioMixingRule getRule() {
        return mRule;
    }

    /** @hide */
    public int getMixType() {
        return mMixType;
    }

    void setRegistration(String regId) {
        mDeviceAddress = regId;
    }

    /** @hide */
    public String getRegistration() {
        return mDeviceAddress;
    }

    /** @hide */
    public boolean isAffectingUsage(int usage) {
        return mRule.isAffectingUsage(usage);
    }

    /**
      * Returns {@code true} if the rule associated with this mix contains a
      * RULE_MATCH_ATTRIBUTE_USAGE criterion for the given usage
      *
      * @hide
      */
    public boolean containsMatchAttributeRuleForUsage(int usage) {
        return mRule.containsMatchAttributeRuleForUsage(usage);
    }

    /** @hide */
    public boolean isRoutedToDevice(int deviceType, @NonNull String deviceAddress) {
        if ((mRouteFlags & ROUTE_FLAG_RENDER) != ROUTE_FLAG_RENDER) {
            return false;
        }
        if (deviceType != mDeviceSystemType) {
            return false;
        }
        if (!deviceAddress.equals(mDeviceAddress)) {
            return false;
        }
        return true;
    }

    /** @return an error string if the format would not allow Privileged playbackCapture
     *          null otherwise
     * @hide */
    public static String canBeUsedForPrivilegedMediaCapture(AudioFormat format) {
        int sampleRate = format.getSampleRate();
        if (sampleRate > PRIVILEDGED_CAPTURE_MAX_SAMPLE_RATE || sampleRate <= 0) {
            return "Privileged audio capture sample rate " + sampleRate
                   + " can not be over " + PRIVILEDGED_CAPTURE_MAX_SAMPLE_RATE + "kHz";
        }
        int channelCount = format.getChannelCount();
        if (channelCount > PRIVILEDGED_CAPTURE_MAX_CHANNEL_NUMBER || channelCount <= 0) {
            return "Privileged audio capture channel count " + channelCount + " can not be over "
                   + PRIVILEDGED_CAPTURE_MAX_CHANNEL_NUMBER;
        }
        int encoding = format.getEncoding();
        if (!format.isPublicEncoding(encoding) || !format.isEncodingLinearPcm(encoding)) {
            return "Privileged audio capture encoding " + encoding + "is not linear";
        }
        if (format.getBytesPerSample(encoding) > PRIVILEDGED_CAPTURE_MAX_BYTES_PER_SAMPLE) {
            return "Privileged audio capture encoding " + encoding + " can not be over "
                   + PRIVILEDGED_CAPTURE_MAX_BYTES_PER_SAMPLE + " bytes per sample";
        }
        return null;
    }

    /** @hide */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        final AudioMix that = (AudioMix) o;
        return (this.mRouteFlags == that.mRouteFlags)
                && (this.mRule == that.mRule)
                && (this.mMixType == that.mMixType)
                && (this.mFormat == that.mFormat);
    }

    /** @hide */
    @Override
    public int hashCode() {
        return Objects.hash(mRouteFlags, mRule, mMixType, mFormat);
    }

    /** @hide */
    @IntDef(flag = true,
            value = { ROUTE_FLAG_RENDER, ROUTE_FLAG_LOOP_BACK } )
    @Retention(RetentionPolicy.SOURCE)
    public @interface RouteFlags {}

    /**
     * Builder class for {@link AudioMix} objects
     */
    public static class Builder {
        private AudioMixingRule mRule = null;
        private AudioFormat mFormat = null;
        private int mRouteFlags = 0;
        private int mCallbackFlags = 0;
        // an AudioSystem.DEVICE_* value, not AudioDeviceInfo.TYPE_*
        private int mDeviceSystemType = AudioSystem.DEVICE_NONE;
        private String mDeviceAddress = null;

        /**
         * @hide
         * Only used by AudioPolicyConfig, not a public API.
         */
        Builder() { }

        /**
         * Construct an instance for the given {@link AudioMixingRule}.
         * @param rule a non-null {@link AudioMixingRule} instance.
         * @throws IllegalArgumentException
         */
        public Builder(AudioMixingRule rule)
                throws IllegalArgumentException {
            if (rule == null) {
                throw new IllegalArgumentException("Illegal null AudioMixingRule argument");
            }
            mRule = rule;
        }

        /**
         * @hide
         * Only used by AudioPolicyConfig, not a public API.
         * @param rule
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        Builder setMixingRule(AudioMixingRule rule)
                throws IllegalArgumentException {
            if (rule == null) {
                throw new IllegalArgumentException("Illegal null AudioMixingRule argument");
            }
            mRule = rule;
            return this;
        }

        /**
         * @hide
         * Only used by AudioPolicyConfig, not a public API.
         * @param callbackFlags which callbacks are called from native
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        Builder setCallbackFlags(int flags) throws IllegalArgumentException {
            if ((flags != 0) && ((flags & CALLBACK_FLAGS_ALL) == 0)) {
                throw new IllegalArgumentException("Illegal callback flags 0x"
                        + Integer.toHexString(flags).toUpperCase());
            }
            mCallbackFlags = flags;
            return this;
        }

        /**
         * @hide
         * Only used by AudioPolicyConfig, not a public API.
         * @param deviceType an AudioSystem.DEVICE_* value, not AudioDeviceInfo.TYPE_*
         * @param address
         * @return the same Builder instance.
         */
        Builder setDevice(int deviceType, String address) {
            mDeviceSystemType = deviceType;
            mDeviceAddress = address;
            return this;
        }

        /**
         * Sets the {@link AudioFormat} for the mix.
         * @param format a non-null {@link AudioFormat} instance.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public Builder setFormat(AudioFormat format)
                throws IllegalArgumentException {
            if (format == null) {
                throw new IllegalArgumentException("Illegal null AudioFormat argument");
            }
            mFormat = format;
            return this;
        }

        /**
         * Sets the routing behavior for the mix. If not set, routing behavior will default to
         * {@link AudioMix#ROUTE_FLAG_LOOP_BACK}.
         * @param routeFlags one of {@link AudioMix#ROUTE_FLAG_LOOP_BACK},
         *     {@link AudioMix#ROUTE_FLAG_RENDER}
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public Builder setRouteFlags(@RouteFlags int routeFlags)
                throws IllegalArgumentException {
            if (routeFlags == 0) {
                throw new IllegalArgumentException("Illegal empty route flags");
            }
            if ((routeFlags & ROUTE_FLAG_SUPPORTED) == 0) {
                throw new IllegalArgumentException("Invalid route flags 0x"
                        + Integer.toHexString(routeFlags) + "when configuring an AudioMix");
            }
            if ((routeFlags & ~ROUTE_FLAG_SUPPORTED) != 0) {
                throw new IllegalArgumentException("Unknown route flags 0x"
                        + Integer.toHexString(routeFlags) + "when configuring an AudioMix");
            }
            mRouteFlags = routeFlags;
            return this;
        }

        /**
         * Sets the audio device used for playback. Cannot be used in the context of an audio
         * policy used to inject audio to be recorded, or in a mix whose route flags doesn't
         * specify {@link AudioMix#ROUTE_FLAG_RENDER}.
         * @param device a non-null AudioDeviceInfo describing the audio device to play the output
         *     of this mix.
         * @return the same Builder instance
         * @throws IllegalArgumentException
         */
        public Builder setDevice(@NonNull AudioDeviceInfo device) throws IllegalArgumentException {
            if (device == null) {
                throw new IllegalArgumentException("Illegal null AudioDeviceInfo argument");
            }
            if (!device.isSink()) {
                throw new IllegalArgumentException("Unsupported device type on mix, not a sink");
            }
            mDeviceSystemType = AudioDeviceInfo.convertDeviceTypeToInternalDevice(device.getType());
            mDeviceAddress = device.getAddress();
            return this;
        }

        /**
         * Combines all of the settings and return a new {@link AudioMix} object.
         * @return a new {@link AudioMix} object
         * @throws IllegalArgumentException if no {@link AudioMixingRule} has been set.
         */
        public AudioMix build() throws IllegalArgumentException {
            if (mRule == null) {
                throw new IllegalArgumentException("Illegal null AudioMixingRule");
            }
            if (mRouteFlags == 0) {
                // no route flags set, use default as described in Builder.setRouteFlags(int)
                mRouteFlags = ROUTE_FLAG_LOOP_BACK;
            }
            if (mFormat == null) {
                // FIXME Can we eliminate this?  Will AudioMix work with an unspecified sample rate?
                int rate = AudioSystem.getPrimaryOutputSamplingRate();
                if (rate <= 0) {
                    rate = 44100;
                }
                mFormat = new AudioFormat.Builder().setSampleRate(rate).build();
            }
            if ((mDeviceSystemType != AudioSystem.DEVICE_NONE)
                    && (mDeviceSystemType != AudioSystem.DEVICE_OUT_REMOTE_SUBMIX)
                    && (mDeviceSystemType != AudioSystem.DEVICE_IN_REMOTE_SUBMIX)) {
                if ((mRouteFlags & ROUTE_FLAG_RENDER) == 0) {
                    throw new IllegalArgumentException(
                            "Can't have audio device without flag ROUTE_FLAG_RENDER");
                }
                if (mRule.getTargetMixType() != AudioMix.MIX_TYPE_PLAYERS) {
                    throw new IllegalArgumentException("Unsupported device on non-playback mix");
                }
            } else {
                if ((mRouteFlags & ROUTE_FLAG_SUPPORTED) == ROUTE_FLAG_RENDER) {
                    throw new IllegalArgumentException(
                            "Can't have flag ROUTE_FLAG_RENDER without an audio device");
                }
                if ((mRouteFlags & ROUTE_FLAG_LOOP_BACK) == ROUTE_FLAG_LOOP_BACK) {
                    if (mRule.getTargetMixType() == MIX_TYPE_PLAYERS) {
                        mDeviceSystemType = AudioSystem.DEVICE_OUT_REMOTE_SUBMIX;
                    } else if (mRule.getTargetMixType() == MIX_TYPE_RECORDERS) {
                        mDeviceSystemType = AudioSystem.DEVICE_IN_REMOTE_SUBMIX;
                    } else {
                        throw new IllegalArgumentException("Unknown mixing rule type");
                    }
                }
            }
            if (mRule.allowPrivilegedMediaPlaybackCapture()) {
                String error = AudioMix.canBeUsedForPrivilegedMediaCapture(mFormat);
                if (error != null) {
                    throw new IllegalArgumentException(error);
                }
            }
            return new AudioMix(mRule, mFormat, mRouteFlags, mCallbackFlags, mDeviceSystemType,
                    mDeviceAddress);
        }
    }
}
