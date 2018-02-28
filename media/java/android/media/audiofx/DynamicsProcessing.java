/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media.audiofx;

import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing.Settings;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.StringTokenizer;

/**
 * DynamicsProcessing is an audio effect for equalizing and changing dynamic range properties of the
 * sound. It is composed of multiple stages including equalization, multi-band compression and
 * limiter.
 * <p>The number of bands and active stages is configurable, and most parameters can be controlled
 * in realtime, such as gains, attack/release times, thresholds, etc.
 * <p>The effect is instantiated and controlled by channels. Each channel has the same basic
 * architecture, but all of their parameters are independent from other channels.
 * <p>The basic channel configuration is:
 * <pre>
 *
 *    Channel 0          Channel 1       ....       Channel N-1
 *      Input              Input                       Input
 *        |                  |                           |
 *   +----v----+        +----v----+                 +----v----+
 *   |inputGain|        |inputGain|                 |inputGain|
 *   +---------+        +---------+                 +---------+
 *        |                  |                           |
 *  +-----v-----+      +-----v-----+               +-----v-----+
 *  |   PreEQ   |      |   PreEQ   |               |   PreEQ   |
 *  +-----------+      +-----------+               +-----------+
 *        |                  |                           |
 *  +-----v-----+      +-----v-----+               +-----v-----+
 *  |    MBC    |      |    MBC    |               |    MBC    |
 *  +-----------+      +-----------+               +-----------+
 *        |                  |                           |
 *  +-----v-----+      +-----v-----+               +-----v-----+
 *  |  PostEQ   |      |  PostEQ   |               |  PostEQ   |
 *  +-----------+      +-----------+               +-----------+
 *        |                  |                           |
 *  +-----v-----+      +-----v-----+               +-----v-----+
 *  |  Limiter  |      |  Limiter  |               |  Limiter  |
 *  +-----------+      +-----------+               +-----------+
 *        |                  |                           |
 *     Output             Output                      Output
 * </pre>
 *
 * <p>Where the stages are:
 * inputGain: input gain factor in decibels (dB). 0 dB means no change in level.
 * PreEQ:  Multi-band Equalizer.
 * MBC:    Multi-band Compressor
 * PostEQ: Multi-band Equalizer
 * Limiter: Single band compressor/limiter.
 *
 * <p>An application creates a DynamicsProcessing object to instantiate and control this audio
 * effect in the audio framework. A DynamicsProcessor.Config and DynamicsProcessor.Config.Builder
 * are available to help configure the multiple stages and each band parameters if desired.
 * <p>See each stage documentation for further details.
 * <p>If no Config is specified during creation, a default configuration is chosen.
 * <p>To attach the DynamicsProcessing to a particular AudioTrack or MediaPlayer,
 * specify the audio session ID of this AudioTrack or MediaPlayer when constructing the effect
 * (see {@link AudioTrack#getAudioSessionId()} and {@link MediaPlayer#getAudioSessionId()}).
 *
 * <p>To attach the DynamicsProcessing to a particular AudioTrack or MediaPlayer, specify the audio
 * session ID of this AudioTrack or MediaPlayer when constructing the DynamicsProcessing.
 * <p>See {@link android.media.MediaPlayer#getAudioSessionId()} for details on audio sessions.
 * <p>See {@link android.media.audiofx.AudioEffect} class for more details on controlling audio
 * effects.
 */

public final class DynamicsProcessing extends AudioEffect {

    private final static String TAG = "DynamicsProcessing";

    /**
     * Config object used to initialize and change effect parameters at runtime.
     */
    private Config mConfig = null;


    // These parameter constants must be synchronized with those in
    // /system/media/audio_effects/include/audio_effects/effect_dynamicsprocessing.h

    private static final int PARAM_GET_CHANNEL_COUNT = 0x0;
    private static final int PARAM_EQ_BAND_COUNT = 0x1;
    private static final int PARAM_MBC_BAND_COUNT = 0x2;
    private static final int PARAM_INPUT_GAIN = 0x3;
    private static final int PARAM_PRE_EQ_ENABLED = 0x10;
    private static final int PARAM_PRE_EQ_BAND_ENABLED = 0x11;
    private static final int PARAM_PRE_EQ_BAND_FREQUENCY = 0x12;
    private static final int PARAM_PRE_EQ_BAND_GAIN = 0x13;
    private static final int PARAM_EQ_FREQUENCY_RANGE = 0x22;
    private static final int PARAM_EQ_GAIN_RANGE = 0x23;
    private static final int PARAM_MBC_ENABLED = 0x30;
    private static final int PARAM_MBC_BAND_ENABLED = 0x31;
    private static final int PARAM_MBC_BAND_FREQUENCY = 0x32;
    private static final int PARAM_MBC_BAND_ATTACK_TIME = 0x33;
    private static final int PARAM_MBC_BAND_RELEASE_TIME = 0x34;
    private static final int PARAM_MBC_BAND_RATIO = 0x35;
    private static final int PARAM_MBC_BAND_THRESHOLD = 0x36;
    private static final int PARAM_MBC_BAND_KNEE_WIDTH = 0x37;
    private static final int PARAM_MBC_BAND_NOISE_GATE_THRESHOLD = 0x38;
    private static final int PARAM_MBC_BAND_EXPANDER_RATIO = 0x39;
    private static final int PARAM_MBC_BAND_GAIN_PRE = 0x3A;
    private static final int PARAM_MBC_BAND_GAIN_POST = 0x3B;
    private static final int PARAM_MBC_FREQUENCY_RANGE = 0x42;
    private static final int PARAM_MBC_ATTACK_TIME_RANGE = 0x43;
    private static final int PARAM_MBC_RELEASE_TIME_RANGE = 0x44;
    private static final int PARAM_MBC_RATIO_RANGE = 0x45;
    private static final int PARAM_MBC_THRESHOLD_RANGE = 0x46;
    private static final int PARAM_MBC_KNEE_WIDTH_RANGE = 0x47;
    private static final int PARAM_MBC_NOISE_GATE_THRESHOLD_RANGE = 0x48;
    private static final int PARAM_MBC_EXPANDER_RATIO_RANGE = 0x49;
    private static final int PARAM_MBC_GAIN_RANGE = 0x4A;
    private static final int PARAM_POST_EQ_ENABLED = 0x50;
    private static final int PARAM_POST_EQ_BAND_ENABLED = 0x51;
    private static final int PARAM_POST_EQ_BAND_FREQUENCY = 0x52;
    private static final int PARAM_POST_EQ_BAND_GAIN = 0x53;
    private static final int PARAM_LIMITER_ENABLED = 0x60;
    private static final int PARAM_LIMITER_LINK_GROUP = 0x61;
    private static final int PARAM_LIMITER_ATTACK_TIME = 0x62;
    private static final int PARAM_LIMITER_RELEASE_TIME = 0x63;
    private static final int PARAM_LIMITER_RATIO = 0x64;
    private static final int PARAM_LIMITER_THRESHOLD = 0x65;
    private static final int PARAM_LIMITER_GAIN_POST = 0x66;
    private static final int PARAM_LIMITER_ATTACK_TIME_RANGE = 0x72;
    private static final int PARAM_LIMITER_RELEASE_TIME_RANGE = 0x73;
    private static final int PARAM_LIMITER_RATIO_RANGE = 0x74;
    private static final int PARAM_LIMITER_THRESHOLD_RANGE = 0x75;
    private static final int PARAM_LIMITER_GAIN_RANGE = 0x76;
    private static final int PARAM_VARIANT = 0x100;
    private static final int PARAM_VARIANT_DESCRIPTION = 0x101;
    private static final int PARAM_VARIANT_COUNT = 0x102;
    private static final int PARAM_SET_ENGINE_ARCHITECTURE = 0x200;

    /**
     * Index of variant that favors frequency resolution. Frequency domain based implementation.
     */
    public static final int VARIANT_FAVOR_FREQUENCY_RESOLUTION  = 0;

    /**
     * Index of variant that favors time resolution resolution. Time domain based implementation.
     */
    public static final int VARIANT_FAVOR_TIME_RESOLUTION       = 1;

    /**
     * Maximum expected channels to be reported by effect
     */
    private static final int CHANNEL_COUNT_MAX = 32;

    /**
     * Number of channels in effect architecture
     */
    private int mChannelCount = 0;

    /**
     * Registered listener for parameter changes.
     */
    private OnParameterChangeListener mParamListener = null;

    /**
     * Listener used internally to to receive raw parameter change events
     * from AudioEffect super class
     */
    private BaseParameterListener mBaseParamListener = null;

    /**
     * Lock for access to mParamListener
     */
    private final Object mParamListenerLock = new Object();

    /**
     * Class constructor.
     * @param audioSession system-wide unique audio session identifier. The DynamicsProcessing
     * will be attached to the MediaPlayer or AudioTrack in the same audio session.
     */
    public DynamicsProcessing(int audioSession) {
        this(0 /*priority*/, audioSession);
    }

    /**
     * @hide
     * Class constructor for the DynamicsProcessing audio effect.
     * @param priority the priority level requested by the application for controlling the
     * DynamicsProcessing engine. As the same engine can be shared by several applications,
     * this parameter indicates how much the requesting application needs control of effect
     * parameters. The normal priority is 0, above normal is a positive number, below normal a
     * negative number.
     * @param audioSession system-wide unique audio session identifier. The DynamicsProcessing
     * will be attached to the MediaPlayer or AudioTrack in the same audio session.
     */
    public DynamicsProcessing(int priority, int audioSession) {
        this(priority, audioSession, null);
    }

    /**
     * Class constructor for the DynamicsProcessing audio effect
     * @param priority the priority level requested by the application for controlling the
     * DynamicsProcessing engine. As the same engine can be shared by several applications,
     * this parameter indicates how much the requesting application needs control of effect
     * parameters. The normal priority is 0, above normal is a positive number, below normal a
     * negative number.
     * @param audioSession system-wide unique audio session identifier. The DynamicsProcessing
     * will be attached to the MediaPlayer or AudioTrack in the same audio session.
     * @param cfg Config object used to setup the audio effect, including bands per stage, and
     * specific parameters for each stage/band. Use
     * {@link android.media.audiofx.DynamicsProcessing.Config.Builder} to create a
     * Config object that suits your needs. A null cfg parameter will create and use a default
     * configuration for the effect
     */
    public DynamicsProcessing(int priority, int audioSession, Config cfg) {
        super(EFFECT_TYPE_DYNAMICS_PROCESSING, EFFECT_TYPE_NULL, priority, audioSession);
        if (audioSession == 0) {
            Log.w(TAG, "WARNING: attaching a DynamicsProcessing to global output mix is"
                    + "deprecated!");
        }
        mChannelCount = getChannelCount();
        if (cfg == null) {
            //create a default configuration and effect, with the number of channels this effect has
            DynamicsProcessing.Config.Builder builder =
                    new DynamicsProcessing.Config.Builder(
                            CONFIG_DEFAULT_VARIANT,
                            mChannelCount,
                            true /*use preEQ*/, 6 /*pre eq bands*/,
                            true /*use mbc*/, 6 /*mbc bands*/,
                            true /*use postEQ*/, 6 /*postEq bands*/,
                            true /*use Limiter*/);
            mConfig = builder.build();
        } else {
            //validate channels are ok. decide what to do: replicate channels if more, or fail, or
            mConfig = new DynamicsProcessing.Config(mChannelCount, cfg);
        }

        setEngineArchitecture(mConfig.getVariant(),
                mConfig.isPreEqInUse(), mConfig.getPreEqBandCount(),
                mConfig.isMbcInUse(), mConfig.getMbcBandCount(),
                mConfig.isPostEqInUse(), mConfig.getPostEqBandCount(),
                mConfig.isLimiterInUse());
    }

    /**
     * Returns the Config object used to setup this effect.
     * @return Config Current Config object used to setup this DynamicsProcessing effect.
     */
    public Config getConfig() {
        return mConfig;
    }


    private static final int CONFIG_DEFAULT_VARIANT = 0; //favor frequency
    private static final float CHANNEL_DEFAULT_INPUT_GAIN = 0; // dB
    private static final float CONFIG_PREFERRED_FRAME_DURATION_MS = 10.0f; //milliseconds

    private static final float EQ_DEFAULT_GAIN = 0; // dB
    private static final boolean PREEQ_DEFAULT_ENABLED = true;
    private static final boolean POSTEQ_DEFAULT_ENABLED = true;


    private static final boolean MBC_DEFAULT_ENABLED = true;
    private static final float MBC_DEFAULT_ATTACK_TIME = 50; // ms
    private static final float MBC_DEFAULT_RELEASE_TIME = 120; // ms
    private static final float MBC_DEFAULT_RATIO = 2; // 1:N
    private static final float MBC_DEFAULT_THRESHOLD = -30; // dB
    private static final float MBC_DEFAULT_KNEE_WIDTH = 0; // dB
    private static final float MBC_DEFAULT_NOISE_GATE_THRESHOLD = -80; // dB
    private static final float MBC_DEFAULT_EXPANDER_RATIO = 1.5f; // N:1
    private static final float MBC_DEFAULT_PRE_GAIN = 0; // dB
    private static final float MBC_DEFAULT_POST_GAIN = 10; // dB

    private static final boolean LIMITER_DEFAULT_ENABLED = true;
    private static final int LIMITER_DEFAULT_LINK_GROUP = 0;//;
    private static final float LIMITER_DEFAULT_ATTACK_TIME = 50; // ms
    private static final float LIMITER_DEFAULT_RELEASE_TIME = 120; // ms
    private static final float LIMITER_DEFAULT_RATIO = 2; // 1:N
    private static final float LIMITER_DEFAULT_THRESHOLD = -30; // dB
    private static final float LIMITER_DEFAULT_POST_GAIN = 10; // dB

    private static final float DEFAULT_MIN_FREQUENCY = 220; // Hz
    private static final float DEFAULT_MAX_FREQUENCY = 20000; // Hz
    private static final float mMinFreqLog = (float)Math.log10(DEFAULT_MIN_FREQUENCY);
    private static final float mMaxFreqLog = (float)Math.log10(DEFAULT_MAX_FREQUENCY);

    /**
     * base class for the different stages.
     */
    public static class Stage {
        private boolean mInUse;
        private boolean mEnabled;
        /**
         * Class constructor for stage
         * @param inUse true if this stage is set to be used. False otherwise. Stages that are not
         * set "inUse" at initialization time are not available to be used at any time.
         * @param enabled true if this stage is currently used to process sound. When disabled,
         * the stage is bypassed and the sound is copied unaltered from input to output.
         */
        public Stage(boolean inUse, boolean enabled) {
            mInUse = inUse;
            mEnabled = enabled;
        }

        /**
         * returns enabled state of the stage
         * @return true if stage is enabled for processing, false otherwise
         */
        public boolean isEnabled() {
            return mEnabled;
        }
        /**
         * sets enabled state of the stage
         * @param enabled true for enabled, false otherwise
         */
        public void setEnabled(boolean enabled) {
            mEnabled = enabled;
        }

        /**
         * returns inUse state of the stage.
         * @return inUse state of the stage. True if this stage is currently used to process sound.
         * When false, the stage is bypassed and the sound is copied unaltered from input to output.
         */
        public boolean isInUse() {
            return mInUse;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(" Stage InUse: %b\n", isInUse()));
            if (isInUse()) {
                sb.append(String.format(" Stage Enabled: %b\n", mEnabled));
            }
            return sb.toString();
        }
    }

    /**
     * Base class for stages that hold bands
     */
    public static class BandStage extends Stage{
        private int mBandCount;
        /**
         * Class constructor for BandStage
         * @param inUse true if this stage is set to be used. False otherwise. Stages that are not
         * set "inUse" at initialization time are not available to be used at any time.
         * @param enabled true if this stage is currently used to process sound. When disabled,
         * the stage is bypassed and the sound is copied unaltered from input to output.
         * @param bandCount number of bands this stage will handle. If stage is not inUse, bandcount
         * is set to 0
         */
        public BandStage(boolean inUse, boolean enabled, int bandCount) {
            super(inUse, enabled);
            mBandCount = isInUse() ? bandCount : 0;
        }

        /**
         * gets number of bands held in this stage
         * @return number of bands held in this stage
         */
        public int getBandCount() {
            return mBandCount;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(super.toString());
            if (isInUse()) {
                sb.append(String.format(" Band Count: %d\n", mBandCount));
            }
            return sb.toString();
        }
    }

    /**
     * Base class for bands
     */
    public static class BandBase {
        private boolean mEnabled;
        private float mCutoffFrequency;
        /**
         * Class constructor for BandBase
         * @param enabled true if this band is currently used to process sound. When false,
         * the band is effectively muted and sound set to zero.
         * @param cutoffFrequency topmost frequency number (in Hz) this band will process. The
         * effective bandwidth for the band is then computed using this and the previous band
         * topmost frequency (or 0 Hz for band number 0). Frequencies are expected to increase with
         * band number, thus band 0 cutoffFrequency <= band 1 cutoffFrequency, and so on.
         */
        public BandBase(boolean enabled, float cutoffFrequency) {
            mEnabled = enabled;
            mCutoffFrequency = cutoffFrequency;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(" Enabled: %b\n", mEnabled));
            sb.append(String.format(" CutoffFrequency: %f\n", mCutoffFrequency));
            return sb.toString();
        }

        /**
         * returns enabled state of the band
         * @return true if bands is enabled for processing, false otherwise
         */
        public boolean isEnabled() {
            return mEnabled;
        }
        /**
         * sets enabled state of the band
         * @param enabled true for enabled, false otherwise
         */
        public void setEnabled(boolean enabled) {
            mEnabled = enabled;
        }

        /**
         * gets cutoffFrequency for this band in Hertz (Hz)
         * @return cutoffFrequency for this band in Hertz (Hz)
         */
        public float getCutoffFrequency() {
            return mCutoffFrequency;
        }

        /**
         * sets topmost frequency number (in Hz) this band will process. The
         * effective bandwidth for the band is then computed using this and the previous band
         * topmost frequency (or 0 Hz for band number 0). Frequencies are expected to increase with
         * band number, thus band 0 cutoffFrequency <= band 1 cutoffFrequency, and so on.
         * @param frequency
         */
        public void setCutoffFrequency(float frequency) {
            mCutoffFrequency = frequency;
        }
    }

    /**
     * Class for Equalizer Bands
     * Equalizer bands have three controllable parameters: enabled/disabled, cutoffFrequency and
     * gain
     */
    public final static class EqBand extends BandBase {
        private float mGain;
        /**
         * Class constructor for EqBand
         * @param enabled true if this band is currently used to process sound. When false,
         * the band is effectively muted and sound set to zero.
         * @param cutoffFrequency topmost frequency number (in Hz) this band will process. The
         * effective bandwidth for the band is then computed using this and the previous band
         * topmost frequency (or 0 Hz for band number 0). Frequencies are expected to increase with
         * band number, thus band 0 cutoffFrequency <= band 1 cutoffFrequency, and so on.
         * @param gain of equalizer band in decibels (dB). A gain of 0 dB means no change in level.
         */
        public EqBand(boolean enabled, float cutoffFrequency, float gain) {
            super(enabled, cutoffFrequency);
            mGain = gain;
        }

        /**
         * Class constructor for EqBand
         * @param cfg copy constructor
         */
        public EqBand(EqBand cfg) {
            super(cfg.isEnabled(), cfg.getCutoffFrequency());
            mGain = cfg.mGain;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(super.toString());
            sb.append(String.format(" Gain: %f\n", mGain));
            return sb.toString();
        }

        /**
         * gets current gain of band in decibels (dB)
         * @return current gain of band in decibels (dB)
         */
        public float getGain() {
            return mGain;
        }

        /**
         * sets current gain of band in decibels (dB)
         * @param gain desired in decibels (db)
         */
        public void setGain(float gain) {
            mGain = gain;
        }
    }

    /**
     * Class for Multi-Band compressor bands
     * MBC bands have multiple controllable parameters: enabled/disabled, cutoffFrequency,
     * attackTime, releaseTime, ratio, threshold, kneeWidth, noiseGateThreshold, expanderRatio,
     * preGain and postGain.
     */
    public final static class MbcBand extends BandBase{
        private float mAttackTime;
        private float mReleaseTime;
        private float mRatio;
        private float mThreshold;
        private float mKneeWidth;
        private float mNoiseGateThreshold;
        private float mExpanderRatio;
        private float mPreGain;
        private float mPostGain;
        /**
         * Class constructor for MbcBand
         * @param enabled true if this band is currently used to process sound. When false,
         * the band is effectively muted and sound set to zero.
         * @param cutoffFrequency topmost frequency number (in Hz) this band will process. The
         * effective bandwidth for the band is then computed using this and the previous band
         * topmost frequency (or 0 Hz for band number 0). Frequencies are expected to increase with
         * band number, thus band 0 cutoffFrequency <= band 1 cutoffFrequency, and so on.
         * @param attackTime Attack Time for compressor in milliseconds (ms)
         * @param releaseTime Release Time for compressor in milliseconds (ms)
         * @param ratio Compressor ratio (1:N)
         * @param threshold Compressor threshold measured in decibels (dB) from 0 dB Full Scale
         * (dBFS).
         * @param kneeWidth Width in decibels (dB) around compressor threshold point.
         * @param noiseGateThreshold Noise gate threshold in decibels (dB) from 0 dB Full Scale
         * (dBFS).
         * @param expanderRatio Expander ratio (N:1) for signals below the Noise Gate Threshold.
         * @param preGain Gain applied to the signal BEFORE the compression.
         * @param postGain Gain applied to the signal AFTER compression.
         */
        public MbcBand(boolean enabled, float cutoffFrequency, float attackTime, float releaseTime,
                float ratio, float threshold, float kneeWidth, float noiseGateThreshold,
                float expanderRatio, float preGain, float postGain) {
            super(enabled, cutoffFrequency);
            mAttackTime = attackTime;
            mReleaseTime = releaseTime;
            mRatio = ratio;
            mThreshold = threshold;
            mKneeWidth = kneeWidth;
            mNoiseGateThreshold = noiseGateThreshold;
            mExpanderRatio = expanderRatio;
            mPreGain = preGain;
            mPostGain = postGain;
        }

        /**
         * Class constructor for MbcBand
         * @param cfg copy constructor
         */
        public MbcBand(MbcBand cfg) {
            super(cfg.isEnabled(), cfg.getCutoffFrequency());
            mAttackTime = cfg.mAttackTime;
            mReleaseTime = cfg.mReleaseTime;
            mRatio = cfg.mRatio;
            mThreshold = cfg.mThreshold;
            mKneeWidth = cfg.mKneeWidth;
            mNoiseGateThreshold = cfg.mNoiseGateThreshold;
            mExpanderRatio = cfg.mExpanderRatio;
            mPreGain = cfg.mPreGain;
            mPostGain = cfg.mPostGain;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(super.toString());
            sb.append(String.format(" AttackTime: %f (ms)\n", mAttackTime));
            sb.append(String.format(" ReleaseTime: %f (ms)\n", mReleaseTime));
            sb.append(String.format(" Ratio: 1:%f\n", mRatio));
            sb.append(String.format(" Threshold: %f (dB)\n", mThreshold));
            sb.append(String.format(" NoiseGateThreshold: %f(dB)\n", mNoiseGateThreshold));
            sb.append(String.format(" ExpanderRatio: %f:1\n", mExpanderRatio));
            sb.append(String.format(" PreGain: %f (dB)\n", mPreGain));
            sb.append(String.format(" PostGain: %f (dB)\n", mPostGain));
            return sb.toString();
        }

        /**
         * gets attack time for compressor in milliseconds (ms)
         * @return attack time for compressor in milliseconds (ms)
         */
        public float getAttackTime() { return mAttackTime; }
        /**
         * sets attack time for compressor in milliseconds (ms)
         * @param attackTime desired for compressor in milliseconds (ms)
         */
        public void setAttackTime(float attackTime) { mAttackTime = attackTime; }
        /**
         * gets release time for compressor in milliseconds (ms)
         * @return release time for compressor in milliseconds (ms)
         */
        public float getReleaseTime() { return mReleaseTime; }
        /**
         * sets release time for compressor in milliseconds (ms)
         * @param releaseTime desired for compressor in milliseconds (ms)
         */
        public void setReleaseTime(float releaseTime) { mReleaseTime = releaseTime; }
        /**
         * gets the compressor ratio (1:N)
         * @return compressor ratio (1:N)
         */
        public float getRatio() { return mRatio; }
        /**
         * sets compressor ratio (1:N)
         * @param ratio desired for the compressor (1:N)
         */
        public void setRatio(float ratio) { mRatio = ratio; }
        /**
         * gets the compressor threshold measured in decibels (dB) from 0 dB Full Scale (dBFS).
         * Thresholds are negative. A threshold of 0 dB means no compression will take place.
         * @return compressor threshold in decibels (dB)
         */
        public float getThreshold() { return mThreshold; }
        /**
         * sets the compressor threshold measured in decibels (dB) from 0 dB Full Scale (dBFS).
         * Thresholds are negative. A threshold of 0 dB means no compression will take place.
         * @param threshold desired for compressor in decibels(dB)
         */
        public void setThreshold(float threshold) { mThreshold = threshold; }
        /**
         * get Knee Width in decibels (dB) around compressor threshold point. Widths are always
         * positive, with higher values representing a wider area of transition from the linear zone
         * to the compression zone. A knee of 0 dB means a more abrupt transition.
         * @return Knee Width in decibels (dB)
         */
        public float getKneeWidth() { return mKneeWidth; }
        /**
         * sets knee width in decibels (dB). See
         * {@link android.media.audiofx.DynamicsProcessing.MbcBand#getKneeWidth} for more
         * information.
         * @param kneeWidth desired in decibels (dB)
         */
        public void setKneeWidth(float kneeWidth) { mKneeWidth = kneeWidth; }
        /**
         * gets the noise gate threshold in decibels (dB) from 0 dB Full Scale (dBFS). Noise gate
         * thresholds are negative. Signals below this level will be expanded according the
         * expanderRatio parameter. A Noise Gate Threshold of -75 dB means very quiet signals might
         * be effectively removed from the signal.
         * @return Noise Gate Threshold in decibels (dB)
         */
        public float getNoiseGateThreshold() { return mNoiseGateThreshold; }
        /**
         * sets noise gate threshod in decibels (dB). See
         * {@link android.media.audiofx.DynamicsProcessing.MbcBand#getNoiseGateThreshold} for more
         * information.
         * @param noiseGateThreshold desired in decibels (dB)
         */
        public void setNoiseGateThreshold(float noiseGateThreshold) {
            mNoiseGateThreshold = noiseGateThreshold; }
        /**
         * gets Expander ratio (N:1) for signals below the Noise Gate Threshold.
         * @return Expander ratio (N:1)
         */
        public float getExpanderRatio() { return mExpanderRatio; }
        /**
         * sets Expander ratio (N:1) for signals below the Noise Gate Threshold.
         * @param expanderRatio desired expander ratio (N:1)
         */
        public void setExpanderRatio(float expanderRatio) { mExpanderRatio = expanderRatio; }
        /**
         * gets the gain applied to the signal BEFORE the compression. Measured in decibels (dB)
         * where 0 dB means no level change.
         * @return preGain value in decibels (dB)
         */
        public float getPreGain() { return mPreGain; }
        /**
         * sets the gain to be applied to the signal BEFORE the compression, measured in decibels
         * (dB), where 0 dB means no level change.
         * @param preGain desired in decibels (dB)
         */
        public void setPreGain(float preGain) { mPreGain = preGain; }
        /**
         * gets the gain applied to the signal AFTER compression. Measured in decibels (dB) where 0
         * dB means no level change
         * @return postGain value in decibels (dB)
         */
        public float getPostGain() { return mPostGain; }
        /**
         * sets the gain to be applied to the siganl AFTER the compression. Measured in decibels
         * (dB), where 0 dB means no level change.
         * @param postGain desired value in decibels (dB)
         */
        public void setPostGain(float postGain) { mPostGain = postGain; }
    }

    /**
     * Class for Equalizer stage
     */
    public final static class Eq extends BandStage {
        private final EqBand[] mBands;
        /**
         * Class constructor for Equalizer (Eq) stage
         * @param inUse true if Eq stage will be used, false otherwise.
         * @param enabled true if Eq stage is enabled/disabled. This can be changed while effect is
         * running
         * @param bandCount number of bands for this Equalizer stage. Can't be changed while effect
         * is running
         */
        public Eq(boolean inUse, boolean enabled, int bandCount) {
            super(inUse, enabled, bandCount);
            if (isInUse()) {
                mBands = new EqBand[bandCount];
                for (int b = 0; b < bandCount; b++) {
                    float freq = DEFAULT_MAX_FREQUENCY;
                    if (bandCount > 1) {
                        freq = (float)Math.pow(10, mMinFreqLog +
                                b * (mMaxFreqLog - mMinFreqLog)/(bandCount -1));
                    }
                    mBands[b] = new EqBand(true, freq, EQ_DEFAULT_GAIN);
                }
            } else {
                mBands = null;
            }
        }
        /**
         * Class constructor for Eq stage
         * @param cfg copy constructor
         */
        public Eq(Eq cfg) {
            super(cfg.isInUse(), cfg.isEnabled(), cfg.getBandCount());
            if (isInUse()) {
                mBands = new EqBand[cfg.mBands.length];
                for (int b = 0; b < mBands.length; b++) {
                    mBands[b] = new EqBand(cfg.mBands[b]);
                }
            } else {
                mBands = null;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(super.toString());
            if (isInUse()) {
                sb.append("--->EqBands: " + mBands.length + "\n");
                for (int b = 0; b < mBands.length; b++) {
                    sb.append(String.format("  Band %d\n", b));
                    sb.append(mBands[b].toString());
                }
            }
            return sb.toString();
        }
        /**
         * Helper function to check if band index is within range
         * @param band index to check
         */
        private void checkBand(int band) {
            if (mBands == null || band < 0 || band >= mBands.length) {
                throw new IllegalArgumentException("band index " + band +" out of bounds");
            }
        }
        /**
         * Sets EqBand object for given band index
         * @param band index of band to be modified
         * @param bandCfg EqBand object.
         */
        public void setBand(int band, EqBand bandCfg) {
            checkBand(band);
            mBands[band] = new EqBand(bandCfg);
        }
        /**
         * Gets EqBand object for band of interest.
         * @param band index of band of interest
         * @return EqBand Object
         */
        public EqBand getBand(int band) {
            checkBand(band);
            return mBands[band];
        }
    }

    /**
     * Class for Multi-Band Compressor (MBC) stage
     */
    public final static class Mbc extends BandStage {
        private final MbcBand[] mBands;
        /**
         * Constructor for Multi-Band Compressor (MBC) stage
         * @param inUse true if MBC stage will be used, false otherwise.
         * @param enabled true if MBC stage is enabled/disabled. This can be changed while effect
         * is running
         * @param bandCount number of bands for this MBC stage. Can't be changed while effect is
         * running
         */
        public Mbc(boolean inUse, boolean enabled, int bandCount) {
            super(inUse, enabled, bandCount);
            if (isInUse()) {
                mBands = new MbcBand[bandCount];
                for (int b = 0; b < bandCount; b++) {
                    float freq = DEFAULT_MAX_FREQUENCY;
                    if (bandCount > 1) {
                        freq = (float)Math.pow(10, mMinFreqLog +
                                b * (mMaxFreqLog - mMinFreqLog)/(bandCount -1));
                    }
                    mBands[b] = new MbcBand(true, freq, MBC_DEFAULT_ATTACK_TIME,
                            MBC_DEFAULT_RELEASE_TIME, MBC_DEFAULT_RATIO,
                            MBC_DEFAULT_THRESHOLD, MBC_DEFAULT_KNEE_WIDTH,
                            MBC_DEFAULT_NOISE_GATE_THRESHOLD, MBC_DEFAULT_EXPANDER_RATIO,
                            MBC_DEFAULT_PRE_GAIN, MBC_DEFAULT_POST_GAIN);
                }
            } else {
                mBands = null;
            }
        }
        /**
         * Class constructor for MBC stage
         * @param cfg copy constructor
         */
        public Mbc(Mbc cfg) {
            super(cfg.isInUse(), cfg.isEnabled(), cfg.getBandCount());
            if (isInUse()) {
                mBands = new MbcBand[cfg.mBands.length];
                for (int b = 0; b < mBands.length; b++) {
                    mBands[b] = new MbcBand(cfg.mBands[b]);
                }
            } else {
                mBands = null;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(super.toString());
            if (isInUse()) {
                sb.append("--->MbcBands: " + mBands.length + "\n");
                for (int b = 0; b < mBands.length; b++) {
                    sb.append(String.format("  Band %d\n", b));
                    sb.append(mBands[b].toString());
                }
            }
            return sb.toString();
        }
        /**
         * Helper function to check if band index is within range
         * @param band index to check
         */
        private void checkBand(int band) {
            if (mBands == null || band < 0 || band >= mBands.length) {
                throw new IllegalArgumentException("band index " + band +" out of bounds");
            }
        }
        /**
         * Sets MbcBand object for given band index
         * @param band index of band to be modified
         * @param bandCfg MbcBand object.
         */
        public void setBand(int band, MbcBand bandCfg) {
            checkBand(band);
            mBands[band] = new MbcBand(bandCfg);
        }
        /**
         * Gets MbcBand object for band of interest.
         * @param band index of band of interest
         * @return MbcBand Object
         */
        public MbcBand getBand(int band) {
            checkBand(band);
            return mBands[band];
        }
    }

    /**
     * Class for Limiter Stage
     * Limiter is a single band compressor at the end of the processing chain, commonly used to
     * protect the signal from overloading and distortion. Limiters have multiple controllable
     * parameters: enabled/disabled, linkGroup, attackTime, releaseTime, ratio, threshold, and
     * postGain.
     * <p>Limiters can be linked in groups across multiple channels. Linked limiters will trigger
     * the same limiting if any of the linked limiters starts compressing.
     */
    public final static class Limiter extends Stage {
        private int mLinkGroup;
        private float mAttackTime;
        private float mReleaseTime;
        private float mRatio;
        private float mThreshold;
        private float mPostGain;

        /**
         * Class constructor for Limiter Stage
         * @param inUse true if MBC stage will be used, false otherwise.
         * @param enabled true if MBC stage is enabled/disabled. This can be changed while effect
         * is running
         * @param linkGroup index of group assigned to this Limiter. Only limiters that share the
         * same linkGroup index will react together.
         * @param attackTime Attack Time for limiter compressor in milliseconds (ms)
         * @param releaseTime Release Time for limiter compressor in milliseconds (ms)
         * @param ratio Limiter Compressor ratio (1:N)
         * @param threshold Limiter Compressor threshold measured in decibels (dB) from 0 dB Full
         * Scale (dBFS).
         * @param postGain Gain applied to the signal AFTER compression.
         */
        public Limiter(boolean inUse, boolean enabled, int linkGroup, float attackTime,
                float releaseTime, float ratio, float threshold, float postGain) {
            super(inUse, enabled);
            mLinkGroup = linkGroup;
            mAttackTime = attackTime;
            mReleaseTime = releaseTime;
            mRatio = ratio;
            mThreshold = threshold;
            mPostGain = postGain;
        }

        /**
         * Class Constructor for Limiter
         * @param cfg copy constructor
         */
        public Limiter(Limiter cfg) {
            super(cfg.isInUse(), cfg.isEnabled());
            mLinkGroup = cfg.mLinkGroup;
            mAttackTime = cfg.mAttackTime;
            mReleaseTime = cfg.mReleaseTime;
            mRatio = cfg.mRatio;
            mThreshold = cfg.mThreshold;
            mPostGain = cfg.mPostGain;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(super.toString());
            if (isInUse()) {
                sb.append(String.format(" LinkGroup: %d (group)\n", mLinkGroup));
                sb.append(String.format(" AttackTime: %f (ms)\n", mAttackTime));
                sb.append(String.format(" ReleaseTime: %f (ms)\n", mReleaseTime));
                sb.append(String.format(" Ratio: 1:%f\n", mRatio));
                sb.append(String.format(" Threshold: %f (dB)\n", mThreshold));
                sb.append(String.format(" PostGain: %f (dB)\n", mPostGain));
            }
            return sb.toString();
        }
        /**
         * Gets the linkGroup index for this Limiter Stage. Only limiters that share the same
         * linkGroup index will react together.
         * @return linkGroup index.
         */
        public int getLinkGroup() { return mLinkGroup; }
        /**
         * Sets the linkGroup index for this limiter Stage.
         * @param linkGroup desired linkGroup index
         */
        public void setLinkGroup(int linkGroup) { mLinkGroup = linkGroup; }
        /**
         * gets attack time for limiter compressor in milliseconds (ms)
         * @return attack time for limiter compressor in milliseconds (ms)
         */
        public float getAttackTime() { return mAttackTime; }
        /**
         * sets attack time for limiter compressor in milliseconds (ms)
         * @param attackTime desired for limiter compressor in milliseconds (ms)
         */
        public void setAttackTime(float attackTime) { mAttackTime = attackTime; }
        /**
         * gets release time for limiter compressor in milliseconds (ms)
         * @return release time for limiter compressor in milliseconds (ms)
         */
        public float getReleaseTime() { return mReleaseTime; }
        /**
         * sets release time for limiter compressor in milliseconds (ms)
         * @param releaseTime desired for limiter compressor in milliseconds (ms)
         */
        public void setReleaseTime(float releaseTime) { mReleaseTime = releaseTime; }
        /**
         * gets the limiter compressor ratio (1:N)
         * @return limiter compressor ratio (1:N)
         */
        public float getRatio() { return mRatio; }
        /**
         * sets limiter compressor ratio (1:N)
         * @param ratio desired for the limiter compressor (1:N)
         */
        public void setRatio(float ratio) { mRatio = ratio; }
        /**
         * gets the limiter compressor threshold measured in decibels (dB) from 0 dB Full Scale
         * (dBFS). Thresholds are negative. A threshold of 0 dB means no limiting will take place.
         * @return limiter compressor threshold in decibels (dB)
         */
        public float getThreshold() { return mThreshold; }
        /**
         * sets the limiter compressor threshold measured in decibels (dB) from 0 dB Full Scale
         * (dBFS). Thresholds are negative. A threshold of 0 dB means no limiting will take place.
         * @param threshold desired for limiter compressor in decibels(dB)
         */
        public void setThreshold(float threshold) { mThreshold = threshold; }
        /**
         * gets the gain applied to the signal AFTER limiting. Measured in decibels (dB) where 0
         * dB means no level change
         * @return postGain value in decibels (dB)
         */
        public float getPostGain() { return mPostGain; }
        /**
         * sets the gain to be applied to the siganl AFTER the limiter. Measured in decibels
         * (dB), where 0 dB means no level change.
         * @param postGain desired value in decibels (dB)
         */
        public void setPostGain(float postGain) { mPostGain = postGain; }
    }

    /**
     * Class for Channel configuration parameters. It is composed of multiple stages, which can be
     * used/enabled independently. Stages not used or disabled will be bypassed and the sound would
     * be unaffected by them.
     */
    public final static class Channel {
        private float   mInputGain;
        private Eq      mPreEq;
        private Mbc     mMbc;
        private Eq      mPostEq;
        private Limiter mLimiter;

        /**
         * Class constructor for Channel configuration.
         * @param inputGain value in decibels (dB) of level change applied to the audio before
         * processing. A value of 0 dB means no change.
         * @param preEqInUse true if PreEq stage will be used, false otherwise. This can't be
         * changed later.
         * @param preEqBandCount number of bands for PreEq stage. This can't be changed later.
         * @param mbcInUse true if Mbc stage will be used, false otherwise. This can't be changed
         * later.
         * @param mbcBandCount number of bands for Mbc stage. This can't be changed later.
         * @param postEqInUse true if PostEq stage will be used, false otherwise. This can't be
         * changed later.
         * @param postEqBandCount number of bands for PostEq stage. This can't be changed later.
         * @param limiterInUse true if Limiter stage will be used, false otherwise. This can't be
         * changed later.
         */
        public Channel (float inputGain,
                boolean preEqInUse, int preEqBandCount,
                boolean mbcInUse, int mbcBandCount,
                boolean postEqInUse, int postEqBandCount,
                boolean limiterInUse) {
            mInputGain = inputGain;
            mPreEq = new Eq(preEqInUse, PREEQ_DEFAULT_ENABLED, preEqBandCount);
            mMbc = new Mbc(mbcInUse, MBC_DEFAULT_ENABLED, mbcBandCount);
            mPostEq = new Eq(postEqInUse, POSTEQ_DEFAULT_ENABLED,
                    postEqBandCount);
            mLimiter = new Limiter(limiterInUse,
                    LIMITER_DEFAULT_ENABLED, LIMITER_DEFAULT_LINK_GROUP,
                    LIMITER_DEFAULT_ATTACK_TIME, LIMITER_DEFAULT_RELEASE_TIME,
                    LIMITER_DEFAULT_RATIO, LIMITER_DEFAULT_THRESHOLD, LIMITER_DEFAULT_POST_GAIN);
        }

        /**
         * Class constructor for Channel configuration
         * @param cfg copy constructor
         */
        public Channel(Channel cfg) {
            mInputGain = cfg.mInputGain;
            mPreEq = new Eq(cfg.mPreEq);
            mMbc = new Mbc(cfg.mMbc);
            mPostEq = new Eq(cfg.mPostEq);
            mLimiter = new Limiter(cfg.mLimiter);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(" InputGain: %f\n", mInputGain));
            sb.append("-->PreEq\n");
            sb.append(mPreEq.toString());
            sb.append("-->MBC\n");
            sb.append(mMbc.toString());
            sb.append("-->PostEq\n");
            sb.append(mPostEq.toString());
            sb.append("-->Limiter\n");
            sb.append(mLimiter.toString());
            return sb.toString();
        }
        /**
         * Gets inputGain value in decibels (dB). 0 dB means no change;
         * @return gain value in decibels (dB)
         */
        public float getInputGain() {
            return mInputGain;
        }
        /**
         * Sets inputGain value in decibels (dB). 0 dB means no change;
         * @param inputGain desired gain value in decibels (dB)
         */
        public void setInputGain(float inputGain) {
            mInputGain = inputGain;
        }

        /**
         * Gets PreEq configuration stage
         * @return PreEq configuration stage
         */
        public Eq getPreEq() {
            return mPreEq;
        }
        /**
         * Sets PreEq configuration stage. New PreEq stage must have the same number of bands than
         * original PreEq stage.
         * @param preEq configuration
         */
        public void setPreEq(Eq preEq) {
            if (preEq.getBandCount() != mPreEq.getBandCount()) {
                throw new IllegalArgumentException("PreEqBandCount changed from " +
                        mPreEq.getBandCount() + " to " + preEq.getBandCount());
            }
            mPreEq = new Eq(preEq);
        }
        /**
         * Gets EqBand for PreEq stage for given band index.
         * @param band index of band of interest from PreEq stage
         * @return EqBand configuration
         */
        public EqBand getPreEqBand(int band) {
            return mPreEq.getBand(band);
        }
        /**
         * Sets EqBand for PreEq stage for given band index
         * @param band index of band of interest from PreEq stage
         * @param preEqBand configuration to be set.
         */
        public void setPreEqBand(int band, EqBand preEqBand) {
            mPreEq.setBand(band, preEqBand);
        }

        /**
         * Gets Mbc configuration stage
         * @return Mbc configuration stage
         */
        public Mbc getMbc() {
            return mMbc;
        }
        /**
         * Sets Mbc configuration stage. New Mbc stage must have the same number of bands than
         * original Mbc stage.
         * @param mbc
         */
        public void setMbc(Mbc mbc) {
            if (mbc.getBandCount() != mMbc.getBandCount()) {
                throw new IllegalArgumentException("MbcBandCount changed from " +
                        mMbc.getBandCount() + " to " + mbc.getBandCount());
            }
            mMbc = new Mbc(mbc);
        }
        /**
         * Gets MbcBand configuration for Mbc stage, for given band index.
         * @param band index of band of interest from Mbc stage
         * @return MbcBand configuration
         */
        public MbcBand getMbcBand(int band) {
            return mMbc.getBand(band);
        }
        /**
         * Sets MbcBand for Mbc stage for given band index
         * @param band index of band of interest from Mbc Stage
         * @param mbcBand configuration to be set
         */
        public void setMbcBand(int band, MbcBand mbcBand) {
            mMbc.setBand(band, mbcBand);
        }

        /**
         * Gets PostEq configuration stage
         * @return PostEq configuration stage
         */
        public Eq getPostEq() {
            return mPostEq;
        }
        /**
         * Sets PostEq configuration stage. New PostEq stage must have the same number of bands than
         * original PostEq stage.
         * @param postEq configuration
         */
        public void setPostEq(Eq postEq) {
            if (postEq.getBandCount() != mPostEq.getBandCount()) {
                throw new IllegalArgumentException("PostEqBandCount changed from " +
                        mPostEq.getBandCount() + " to " + postEq.getBandCount());
            }
            mPostEq = new Eq(postEq);
        }
        /**
         * Gets EqBand for PostEq stage for given band index.
         * @param band index of band of interest from PostEq stage
         * @return EqBand configuration
         */
        public EqBand getPostEqBand(int band) {
            return mPostEq.getBand(band);
        }
        /**
         * Sets EqBand for PostEq stage for given band index
         * @param band index of band of interest from PostEq stage
         * @param postEqBand configuration to be set.
         */
        public void setPostEqBand(int band, EqBand postEqBand) {
            mPostEq.setBand(band, postEqBand);
        }

        /**
         * Gets Limiter configuration stage
         * @return Limiter configuration stage
         */
        public Limiter getLimiter() {
            return mLimiter;
        }
        /**
         * Sets Limiter configuration stage.
         * @param limiter configuration stage.
         */
        public void setLimiter(Limiter limiter) {
            mLimiter = new Limiter(limiter);
        }
    }

    /**
     * Class for Config object, used by DynamicsProcessing to configure and update the audio effect.
     * use Builder to instantiate objects of this type.
     */
    public final static class Config {
        private final int mVariant;
        private final int mChannelCount;
        private final boolean mPreEqInUse;
        private final int mPreEqBandCount;
        private final boolean mMbcInUse;
        private final int mMbcBandCount;
        private final boolean mPostEqInUse;
        private final int mPostEqBandCount;
        private final boolean mLimiterInUse;
        private final float mPreferredFrameDuration;
        private final Channel[] mChannel;

        /**
         * @hide
         * Class constructor for config. None of these parameters can be changed later.
         * @param variant index of variant used for effect engine. See
         * {@link #VARIANT_FAVOR_FREQUENCY_RESOLUTION} and {@link #VARIANT_FAVOR_TIME_RESOLUTION}.
         * @param frameDurationMs preferred frame duration in milliseconds (ms).
         * @param channelCount Number of channels to be configured.
         * @param preEqInUse true if PreEq stage will be used, false otherwise.
         * @param preEqBandCount number of bands for PreEq stage.
         * @param mbcInUse true if Mbc stage will be used, false otherwise.
         * @param mbcBandCount number of bands for Mbc stage.
         * @param postEqInUse true if PostEq stage will be used, false otherwise.
         * @param postEqBandCount number of bands for PostEq stage.
         * @param limiterInUse true if Limiter stage will be used, false otherwise.
         * @param channel array of Channel objects to be used for this configuration.
         */
        public Config(int variant, float frameDurationMs, int channelCount,
                boolean preEqInUse, int preEqBandCount,
                boolean mbcInUse, int mbcBandCount,
                boolean postEqInUse, int postEqBandCount,
                boolean limiterInUse,
                Channel[] channel) {
            mVariant = variant;
            mPreferredFrameDuration = frameDurationMs;
            mChannelCount = channelCount;
            mPreEqInUse = preEqInUse;
            mPreEqBandCount = preEqBandCount;
            mMbcInUse = mbcInUse;
            mMbcBandCount = mbcBandCount;
            mPostEqInUse = postEqInUse;
            mPostEqBandCount = postEqBandCount;
            mLimiterInUse = limiterInUse;

            mChannel = new Channel[mChannelCount];
            //check if channelconfig is null or has less channels than channel count.
            //options: fill the missing with default options.
            //  or fail?
            for (int ch = 0; ch < mChannelCount; ch++) {
                if (ch < channel.length) {
                    mChannel[ch] = new Channel(channel[ch]); //copy create
                } else {
                    //create a new one from scratch? //fail?
                }
            }
        }
        //a version that will scale to necessary number of channels
        /**
         * @hide
         * Class constructor for Configuration.
         * @param channelCount limit configuration to this number of channels. if channelCount is
         * greater than number of channels in cfg, the constructor will duplicate the last channel
         * found as many times as necessary to create a Config with channelCount number of channels.
         * If channelCount is less than channels in cfg, the extra channels in cfg will be ignored.
         * @param cfg copy constructor paremter.
         */
        public Config(int channelCount, Config cfg) {
            mVariant = cfg.mVariant;
            mPreferredFrameDuration = cfg.mPreferredFrameDuration;
            mChannelCount = cfg.mChannelCount;
            mPreEqInUse = cfg.mPreEqInUse;
            mPreEqBandCount = cfg.mPreEqBandCount;
            mMbcInUse = cfg.mMbcInUse;
            mMbcBandCount = cfg.mMbcBandCount;
            mPostEqInUse = cfg.mPostEqInUse;
            mPostEqBandCount = cfg.mPostEqBandCount;
            mLimiterInUse = cfg.mLimiterInUse;

            if (mChannelCount != cfg.mChannel.length) {
                throw new IllegalArgumentException("configuration channel counts differ " +
                        mChannelCount + " !=" + cfg.mChannel.length);
            }
            if (channelCount < 1) {
                throw new IllegalArgumentException("channel resizing less than 1 not allowed");
            }

            mChannel = new Channel[channelCount];
            for (int ch = 0; ch < channelCount; ch++) {
                if (ch < mChannelCount) {
                    mChannel[ch] = new Channel(cfg.mChannel[ch]);
                } else {
                    //duplicate last
                    mChannel[ch] = new Channel(cfg.mChannel[mChannelCount-1]);
                }
            }
        }

        /**
         * @hide
         * Class constructor for Config
         * @param cfg Configuration object copy constructor
         */
        public Config(Config cfg) {
            this(cfg.mChannelCount, cfg);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Variant: %d\n", mVariant));
            sb.append(String.format("PreferredFrameDuration: %f\n", mPreferredFrameDuration));
            sb.append(String.format("ChannelCount: %d\n", mChannelCount));
            sb.append(String.format("PreEq inUse: %b, bandCount:%d\n",mPreEqInUse,
                    mPreEqBandCount));
            sb.append(String.format("Mbc inUse: %b, bandCount: %d\n",mMbcInUse, mMbcBandCount));
            sb.append(String.format("PostEq inUse: %b, bandCount: %d\n", mPostEqInUse,
                    mPostEqBandCount));
            sb.append(String.format("Limiter inUse: %b\n", mLimiterInUse));
            for (int ch = 0; ch < mChannel.length; ch++) {
                sb.append(String.format("==Channel %d\n", ch));
                sb.append(mChannel[ch].toString());
            }
            return sb.toString();
        }
        private void checkChannel(int channelIndex) {
            if (channelIndex < 0 || channelIndex >= mChannel.length) {
                throw new IllegalArgumentException("ChannelIndex out of bounds");
            }
        }

        //getters and setters
        /**
         * Gets variant for effect engine See {@link #VARIANT_FAVOR_FREQUENCY_RESOLUTION} and
         * {@link #VARIANT_FAVOR_TIME_RESOLUTION}.
         * @return variant of effect engine
         */
        public int getVariant() {
            return mVariant;
        }
        /**
         * Gets preferred frame duration in milliseconds (ms).
         * @return preferred frame duration in milliseconds (ms)
         */
        public float getPreferredFrameDuration() {
            return mPreferredFrameDuration;
        }
        /**
         * Gets if preEq stage is in use
         * @return true if preEq stage is in use;
         */
        public boolean isPreEqInUse() {
            return mPreEqInUse;
        }
        /**
         * Gets number of bands configured for the PreEq stage.
         * @return number of bands configured for the PreEq stage.
         */
        public int getPreEqBandCount() {
            return mPreEqBandCount;
        }
        /**
         * Gets if Mbc stage is in use
         * @return true if Mbc stage is in use;
         */
        public boolean isMbcInUse() {
            return mMbcInUse;
        }
        /**
         * Gets number of bands configured for the Mbc stage.
         * @return number of bands configured for the Mbc stage.
         */
        public int getMbcBandCount() {
            return mMbcBandCount;
        }
        /**
         * Gets if PostEq stage is in use
         * @return true if PostEq stage is in use;
         */
        public boolean isPostEqInUse() {
            return mPostEqInUse;
        }
        /**
         * Gets number of bands configured for the PostEq stage.
         * @return number of bands configured for the PostEq stage.
         */
        public int getPostEqBandCount() {
            return mPostEqBandCount;
        }
        /**
         * Gets if Limiter stage is in use
         * @return true if Limiter stage is in use;
         */
        public boolean isLimiterInUse() {
            return mLimiterInUse;
        }

        //channel
        /**
         * Gets the Channel configuration object by using the channel index
         * @param channelIndex of desired Channel object
         * @return Channel configuration object
         */
        public Channel getChannelByChannelIndex(int channelIndex) {
            checkChannel(channelIndex);
            return mChannel[channelIndex];
        }

        /**
         * Sets the chosen Channel object in the selected channelIndex
         * Note that all the stages should have the same number of bands than the existing Channel
         * object.
         * @param channelIndex index of channel to be replaced
         * @param channel Channel configuration object to be set
         */
        public void setChannelTo(int channelIndex, Channel channel) {
            checkChannel(channelIndex);
            //check all things are compatible
            if (mMbcBandCount != channel.getMbc().getBandCount()) {
                throw new IllegalArgumentException("MbcBandCount changed from " +
                        mMbcBandCount + " to " + channel.getPreEq().getBandCount());
            }
            if (mPreEqBandCount != channel.getPreEq().getBandCount()) {
                throw new IllegalArgumentException("PreEqBandCount changed from " +
                        mPreEqBandCount + " to " + channel.getPreEq().getBandCount());
            }
            if (mPostEqBandCount != channel.getPostEq().getBandCount()) {
                throw new IllegalArgumentException("PostEqBandCount changed from " +
                        mPostEqBandCount + " to " + channel.getPostEq().getBandCount());
            }
            mChannel[channelIndex] = new Channel(channel);
        }

        /**
         * Sets ALL channels to the chosen Channel object. Note that all the stages should have the
         * same number of bands than the existing ones.
         * @param channel Channel configuration object to be set.
         */
        public void setAllChannelsTo(Channel channel) {
            for (int ch = 0; ch < mChannel.length; ch++) {
                setChannelTo(ch, channel);
            }
        }

        //===channel params
        /**
         * Gets inputGain value in decibels (dB) for channel indicated by channelIndex
         * @param channelIndex index of channel of interest
         * @return inputGain value in decibels (dB). 0 dB means no change.
         */
        public float getInputGainByChannelIndex(int channelIndex) {
            checkChannel(channelIndex);
            return mChannel[channelIndex].getInputGain();
        }
        /**
         * Sets the inputGain value in decibels (dB) for the channel indicated by channelIndex.
         * @param channelIndex index of channel of interest
         * @param inputGain desired value in decibels (dB).
         */
        public void setInputGainByChannelIndex(int channelIndex, float inputGain) {
            checkChannel(channelIndex);
            mChannel[channelIndex].setInputGain(inputGain);
        }
        /**
         * Sets the inputGain value in decibels (dB) for ALL channels
         * @param inputGain desired value in decibels (dB)
         */
        public void setInputGainAllChannelsTo(float inputGain) {
            for (int ch = 0; ch < mChannel.length; ch++) {
                mChannel[ch].setInputGain(inputGain);
            }
        }

        //=== PreEQ
        /**
         * Gets PreEq stage from channel indicated by channelIndex
         * @param channelIndex index of channel of interest
         * @return PreEq stage configuration object
         */
        public Eq getPreEqByChannelIndex(int channelIndex) {
            checkChannel(channelIndex);
            return mChannel[channelIndex].getPreEq();
        }
        /**
         * Sets the PreEq stage configuration for the channel indicated by channelIndex. Note that
         * new preEq stage must have the same number of bands than original preEq stage
         * @param channelIndex index of channel to be set
         * @param preEq desired PreEq configuration to be set
         */
        public void setPreEqByChannelIndex(int channelIndex, Eq preEq) {
            checkChannel(channelIndex);
            mChannel[channelIndex].setPreEq(preEq);
        }
        /**
         * Sets the PreEq stage configuration for ALL channels. Note that new preEq stage must have
         * the same number of bands than original preEq stages.
         * @param preEq desired PreEq configuration to be set
         */
        public void setPreEqAllChannelsTo(Eq preEq) {
            for (int ch = 0; ch < mChannel.length; ch++) {
                mChannel[ch].setPreEq(preEq);
            }
        }
        public EqBand getPreEqBandByChannelIndex(int channelIndex, int band) {
            checkChannel(channelIndex);
            return mChannel[channelIndex].getPreEqBand(band);
        }
        public void setPreEqBandByChannelIndex(int channelIndex, int band, EqBand preEqBand) {
            checkChannel(channelIndex);
            mChannel[channelIndex].setPreEqBand(band, preEqBand);
        }
        public void setPreEqBandAllChannelsTo(int band, EqBand preEqBand) {
            for (int ch = 0; ch < mChannel.length; ch++) {
                mChannel[ch].setPreEqBand(band, preEqBand);
            }
        }

        //=== MBC
        public Mbc getMbcByChannelIndex(int channelIndex) {
            checkChannel(channelIndex);
            return mChannel[channelIndex].getMbc();
        }
        public void setMbcByChannelIndex(int channelIndex, Mbc mbc) {
            checkChannel(channelIndex);
            mChannel[channelIndex].setMbc(mbc);
        }
        public void setMbcAllChannelsTo(Mbc mbc) {
            for (int ch = 0; ch < mChannel.length; ch++) {
                mChannel[ch].setMbc(mbc);
            }
        }
        public MbcBand getMbcBandByChannelIndex(int channelIndex, int band) {
            checkChannel(channelIndex);
            return mChannel[channelIndex].getMbcBand(band);
        }
        public void setMbcBandByChannelIndex(int channelIndex, int band, MbcBand mbcBand) {
            checkChannel(channelIndex);
            mChannel[channelIndex].setMbcBand(band, mbcBand);
        }
        public void setMbcBandAllChannelsTo(int band, MbcBand mbcBand) {
            for (int ch = 0; ch < mChannel.length; ch++) {
                mChannel[ch].setMbcBand(band, mbcBand);
            }
        }

        //=== PostEQ
        public Eq getPostEqByChannelIndex(int channelIndex) {
            checkChannel(channelIndex);
            return mChannel[channelIndex].getPostEq();
        }
        public void setPostEqByChannelIndex(int channelIndex, Eq postEq) {
            checkChannel(channelIndex);
            mChannel[channelIndex].setPostEq(postEq);
        }
        public void setPostEqAllChannelsTo(Eq postEq) {
            for (int ch = 0; ch < mChannel.length; ch++) {
                mChannel[ch].setPostEq(postEq);
            }
        }
        public EqBand getPostEqBandByChannelIndex(int channelIndex, int band) {
            checkChannel(channelIndex);
            return mChannel[channelIndex].getPostEqBand(band);
        }
        public void setPostEqBandByChannelIndex(int channelIndex, int band, EqBand postEqBand) {
            checkChannel(channelIndex);
            mChannel[channelIndex].setPostEqBand(band, postEqBand);
        }
        public void setPostEqBandAllChannelsTo(int band, EqBand postEqBand) {
            for (int ch = 0; ch < mChannel.length; ch++) {
                mChannel[ch].setPostEqBand(band, postEqBand);
            }
        }

        //Limiter
        public Limiter getLimiterByChannelIndex(int channelIndex) {
            checkChannel(channelIndex);
            return mChannel[channelIndex].getLimiter();
        }
        public void setLimiterByChannelIndex(int channelIndex, Limiter limiter) {
            checkChannel(channelIndex);
            mChannel[channelIndex].setLimiter(limiter);
        }
        public void setLimiterAllChannelsTo(Limiter limiter) {
            for (int ch = 0; ch < mChannel.length; ch++) {
                mChannel[ch].setLimiter(limiter);
            }
        }

        public final static class Builder {
            private int mVariant;
            private int mChannelCount;
            private boolean mPreEqInUse;
            private int mPreEqBandCount;
            private boolean mMbcInUse;
            private int mMbcBandCount;
            private boolean mPostEqInUse;
            private int mPostEqBandCount;
            private boolean mLimiterInUse;
            private float mPreferredFrameDuration = CONFIG_PREFERRED_FRAME_DURATION_MS;
            private Channel[] mChannel;

            public Builder(int variant, int channelCount,
                    boolean preEqInUse, int preEqBandCount,
                    boolean mbcInUse, int mbcBandCount,
                    boolean postEqInUse, int postEqBandCount,
                    boolean limiterInUse) {
                mVariant = variant;
                mChannelCount = channelCount;
                mPreEqInUse = preEqInUse;
                mPreEqBandCount = preEqBandCount;
                mMbcInUse = mbcInUse;
                mMbcBandCount = mbcBandCount;
                mPostEqInUse = postEqInUse;
                mPostEqBandCount = postEqBandCount;
                mLimiterInUse = limiterInUse;
                mChannel = new Channel[mChannelCount];
                for (int ch = 0; ch < mChannelCount; ch++) {
                    this.mChannel[ch] = new Channel(CHANNEL_DEFAULT_INPUT_GAIN,
                            this.mPreEqInUse, this.mPreEqBandCount,
                            this.mMbcInUse, this.mMbcBandCount,
                            this.mPostEqInUse, this.mPostEqBandCount,
                            this.mLimiterInUse);
                }
            }

            private void checkChannel(int channelIndex) {
                if (channelIndex < 0 || channelIndex >= mChannel.length) {
                    throw new IllegalArgumentException("ChannelIndex out of bounds");
                }
            }

            public Builder setPreferredFrameDuration(float frameDuration) {
                if (frameDuration < 0) {
                    throw new IllegalArgumentException("Expected positive frameDuration");
                }
                mPreferredFrameDuration = frameDuration;
                return this;
            }

            public Builder setInputGainByChannelIndex(int channelIndex, float inputGain) {
                checkChannel(channelIndex);
                mChannel[channelIndex].setInputGain(inputGain);
                return this;
            }
            public Builder setInputGainAllChannelsTo(float inputGain) {
                for (int ch = 0; ch < mChannel.length; ch++) {
                    mChannel[ch].setInputGain(inputGain);
                }
                return this;
            }

            public Builder setChannelTo(int channelIndex, Channel channel) {
                checkChannel(channelIndex);
                //check all things are compatible
                if (mMbcBandCount != channel.getMbc().getBandCount()) {
                    throw new IllegalArgumentException("MbcBandCount changed from " +
                            mMbcBandCount + " to " + channel.getPreEq().getBandCount());
                }
                if (mPreEqBandCount != channel.getPreEq().getBandCount()) {
                    throw new IllegalArgumentException("PreEqBandCount changed from " +
                            mPreEqBandCount + " to " + channel.getPreEq().getBandCount());
                }
                if (mPostEqBandCount != channel.getPostEq().getBandCount()) {
                    throw new IllegalArgumentException("PostEqBandCount changed from " +
                            mPostEqBandCount + " to " + channel.getPostEq().getBandCount());
                }
                mChannel[channelIndex] = new Channel(channel);
                return this;
            }
            public Builder setAllChannelsTo(Channel channel) {
                for (int ch = 0; ch < mChannel.length; ch++) {
                    setChannelTo(ch, channel);
                }
                return this;
            }

            public Builder setPreEqByChannelIndex(int channelIndex, Eq preEq) {
                checkChannel(channelIndex);
                mChannel[channelIndex].setPreEq(preEq);
                return this;
            }
            public Builder setPreEqAllChannelsTo(Eq preEq) {
                for (int ch = 0; ch < mChannel.length; ch++) {
                    setPreEqByChannelIndex(ch, preEq);
                }
                return this;
            }

            public Builder setMbcByChannelIndex(int channelIndex, Mbc mbc) {
                checkChannel(channelIndex);
                mChannel[channelIndex].setMbc(mbc);
                return this;
            }
            public Builder setMbcAllChannelsTo(Mbc mbc) {
                for (int ch = 0; ch < mChannel.length; ch++) {
                    setMbcByChannelIndex(ch, mbc);
                }
                return this;
            }

            public Builder setPostEqByChannelIndex(int channelIndex, Eq postEq) {
                checkChannel(channelIndex);
                mChannel[channelIndex].setPostEq(postEq);
                return this;
            }
            public Builder setPostEqAllChannelsTo(Eq postEq) {
                for (int ch = 0; ch < mChannel.length; ch++) {
                    setPostEqByChannelIndex(ch, postEq);
                }
                return this;
            }

            public Builder setLimiterByChannelIndex(int channelIndex, Limiter limiter) {
                checkChannel(channelIndex);
                mChannel[channelIndex].setLimiter(limiter);
                return this;
            }
            public Builder setLimiterAllChannelsTo(Limiter limiter) {
                for (int ch = 0; ch < mChannel.length; ch++) {
                    setLimiterByChannelIndex(ch, limiter);
                }
                return this;
            }

            public Config build() {
                return new Config(mVariant, mPreferredFrameDuration, mChannelCount,
                        mPreEqInUse, mPreEqBandCount,
                        mMbcInUse, mMbcBandCount,
                        mPostEqInUse, mPostEqBandCount,
                        mLimiterInUse, mChannel);
            }
        }
    }
    //=== CHANNEL
    public Channel getChannelByChannelIndex(int channelIndex) {
        return mConfig.getChannelByChannelIndex(channelIndex);
    }

    public void setChannelTo(int channelIndex, Channel channel) {
        mConfig.setChannelTo(channelIndex, channel);
    }

    public void setAllChannelsTo(Channel channel) {
        mConfig.setAllChannelsTo(channel);
    }

    //=== channel params
    public float getInputGainByChannelIndex(int channelIndex) {
        //TODO: return info from engine instead of cached config
        return mConfig.getInputGainByChannelIndex(channelIndex);
    }
    public void setInputGainbyChannel(int channelIndex, float inputGain) {
        mConfig.setInputGainByChannelIndex(channelIndex, inputGain);
        //TODO: communicate change to engine
    }
    public void setInputGainAllChannelsTo(float inputGain) {
        mConfig.setInputGainAllChannelsTo(inputGain);
        //TODO: communicate change to engine
    }

    //=== PreEQ
    public Eq getPreEqByChannelIndex(int channelIndex) {
        //TODO: return info from engine instead of cached config
        return mConfig.getPreEqByChannelIndex(channelIndex);
    }

    public void setPreEqByChannelIndex(int channelIndex, Eq preEq) {
        mConfig.setPreEqByChannelIndex(channelIndex, preEq);
        //TODO: communicate change to engine
    }

    public void setPreEqAllChannelsTo(Eq preEq) {
        mConfig.setPreEqAllChannelsTo(preEq);
        //TODO: communicate change to engine
    }

    public EqBand getPreEqBandByChannelIndex(int channelIndex, int band) {
        //TODO: return info from engine instead of cached config
        return mConfig.getPreEqBandByChannelIndex(channelIndex, band);
    }

    public void setPreEqBandByChannelIndex(int channelIndex, int band, EqBand preEqBand) {
        mConfig.setPreEqBandByChannelIndex(channelIndex, band, preEqBand);
        //TODO: communicate change to engine
    }

    public void setPreEqBandAllChannelsTo(int band, EqBand preEqBand) {
        mConfig.setPreEqBandAllChannelsTo(band, preEqBand);
        //TODO: communicate change to engine
    }

    //=== MBC
    public Mbc getMbcByChannelIndex(int channelIndex) {
        //TODO: return info from engine instead of cached config
        return mConfig.getMbcByChannelIndex(channelIndex);
    }

    public void setMbcByChannelIndex(int channelIndex, Mbc mbc) {
        mConfig.setMbcByChannelIndex(channelIndex, mbc);
        //TODO: communicate change to engine
    }

    public void setMbcAllChannelsTo(Mbc mbc) {
        mConfig.setMbcAllChannelsTo(mbc);
        //TODO: communicate change to engine
    }

    public MbcBand getMbcBandByChannelIndex(int channelIndex, int band) {
        //TODO: return info from engine instead of cached config
        return mConfig.getMbcBandByChannelIndex(channelIndex, band);
    }

    public void setMbcBandByChannelIndex(int channelIndex, int band, MbcBand mbcBand) {
        mConfig.setMbcBandByChannelIndex(channelIndex, band, mbcBand);
        //TODO: communicate change to engine
    }

    public void setMbcBandAllChannelsTo(int band, MbcBand mbcBand) {
        mConfig.setMbcBandAllChannelsTo(band, mbcBand);
        //TODO: communicate change to engine
    }

    //== PostEq
    public Eq getPostEqByChannelIndex(int channelIndex) {
        //TODO: return info from engine instead of cached config
        return mConfig.getPostEqByChannelIndex(channelIndex);
    }

    public void setPostEqByChannelIndex(int channelIndex, Eq postEq) {
        mConfig.setPostEqByChannelIndex(channelIndex, postEq);
        //TODO: communicate change to engine
    }

    public void setPostEqAllChannelsTo(Eq postEq) {
        mConfig.setPostEqAllChannelsTo(postEq);
        //TODO: communicate change to engine
    }

    public EqBand getPostEqBandByChannelIndex(int channelIndex, int band) {
        //TODO: return info from engine instead of cached config
        return mConfig.getPostEqBandByChannelIndex(channelIndex, band);
    }

    public void setPostEqBandByChannelIndex(int channelIndex, int band, EqBand postEqBand) {
        mConfig.setPostEqBandByChannelIndex(channelIndex, band, postEqBand);
        //TODO: communicate change to engine
    }

    public void setPostEqBandAllChannelsTo(int band, EqBand postEqBand) {
        mConfig.setPostEqBandAllChannelsTo(band, postEqBand);
        //TODO: communicate change to engine
    }

    //==== Limiter
    public Limiter getLimiterByChannelIndex(int channelIndex) {
        //TODO: return info from engine instead of cached config
        return mConfig.getLimiterByChannelIndex(channelIndex);
    }

    public void setLimiterByChannelIndex(int channelIndex, Limiter limiter) {
        mConfig.setLimiterByChannelIndex(channelIndex, limiter);
        //TODO: communicate change to engine
    }

    public void setLimiterAllChannelsTo(Limiter limiter) {
        mConfig.setLimiterAllChannelsTo(limiter);
        //TODO: communicate change to engine
    }

    /**
     * Gets the number of channels in the effect engine
     * @return number of channels currently in use by the effect engine
     */
    public int getChannelCount() {
        return getOneInt(PARAM_GET_CHANNEL_COUNT);
    }

    private void setEngineArchitecture(int variant, boolean preEqInUse, int preEqBandCount,
            boolean mbcInUse, int mbcBandCount, boolean postEqInUse, int postEqBandCount,
            boolean limiterInUse) {
        int[] values = { variant, (preEqInUse ? 1 : 0), preEqBandCount,
                (mbcInUse ? 1 : 0), mbcBandCount, (postEqInUse ? 1 : 0), postEqBandCount,
                (limiterInUse ? 1 : 0)};
        //TODO: enable later setIntArray(PARAM_SET_ENGINE_ARCHITECTURE, values);
    }

    //****** convenience methods:
    //
    private int getOneInt(int paramGet) {
        int[] param = new int[1];
        int[] result = new int[1];

        param[0] = paramGet;
        checkStatus(getParameter(param, result));
        return result[0];
    }

    private int getTwoInt(int paramGet, int paramA) {
        int[] param = new int[2];
        int[] result = new int[1];

        param[0] = paramGet;
        param[1] = paramA;
        checkStatus(getParameter(param, result));
        return result[0];
    }

    private int getThreeInt(int paramGet, int paramA, int paramB) {
        //have to use bytearrays, with more than 2 parameters.
        byte[] paramBytes = concatArrays(intToByteArray(paramGet),
                intToByteArray(paramA),
                intToByteArray(paramB));
        byte[] resultBytes = new byte[4]; //single int

        checkStatus(getParameter(paramBytes, resultBytes));

        return byteArrayToInt(resultBytes);
    }

    private void setOneInt(int paramSet, int valueSet) {
        int[] param = new int[1];
        int[] value = new int[1];

        param[0] = paramSet;
        value[0] = valueSet;
        checkStatus(setParameter(param, value));
    }

    private void setTwoInt(int paramSet, int paramA, int valueSet) {
        int[] param = new int[2];
        int[] value = new int[1];

        param[0] = paramSet;
        param[1] = paramA;
        value[0] = valueSet;
        checkStatus(setParameter(param, value));
    }

    private void setThreeInt(int paramSet, int paramA, int paramB, int valueSet) {
        //have to use bytearrays, with more than 2 parameters.
        byte[] paramBytes = concatArrays(intToByteArray(paramSet),
                intToByteArray(paramA),
                intToByteArray(paramB));
        byte[] valueBytes = intToByteArray(valueSet);

        checkStatus(setParameter(paramBytes, valueBytes));
    }

    private void setOneFloat(int paramSet, float valueSet) {
        int[] param = new int[1];
        byte[] value;

        param[0] = paramSet;
        value = floatToByteArray(valueSet);
        checkStatus(setParameter(param, value));
    }

    private void setTwoFloat(int paramSet, int paramA, float valueSet) {
        int[] param = new int[2];
        byte[] value;

        param[0] = paramSet;
        param[1] = paramA;
        value = floatToByteArray(valueSet);
        checkStatus(setParameter(param, value));
    }

    private void setThreeFloat(int paramSet, int paramA, int paramB, float valueSet) {
        //have to use bytearrays, with more than 2 parameters.
        byte[] paramBytes = concatArrays(intToByteArray(paramSet),
                intToByteArray(paramA),
                intToByteArray(paramB));
        byte[] valueBytes = floatToByteArray(valueSet);

        checkStatus(setParameter(paramBytes, valueBytes));
    }
    private byte[] intArrayToByteArray(int[] values) {
        int expectedBytes = values.length * 4;
        ByteBuffer converter = ByteBuffer.allocate(expectedBytes);
        converter.order(ByteOrder.nativeOrder());
        for (int k = 0; k < values.length; k++) {
            converter.putFloat(values[k]);
        }
        return converter.array();
    }
    private void setIntArray(int paramSet, int[] paramArray) {
        //have to use bytearrays, with more than 2 parameters.
        byte[] paramBytes = intToByteArray(paramSet);
        byte[] valueBytes = intArrayToByteArray(paramArray);

        checkStatus(setParameter(paramBytes, valueBytes));
    }

    private float getOneFloat(int paramGet) {
        int[] param = new int[1];
        byte[] result = new byte[4];

        param[0] = paramGet;
        checkStatus(getParameter(param, result));
        return byteArrayToFloat(result);
    }

    private float getTwoFloat(int paramGet, int paramA) {
        int[] param = new int[2];
        byte[] result = new byte[4];

        param[0] = paramGet;
        param[1] = paramA;
        checkStatus(getParameter(param, result));
        return byteArrayToFloat(result);
    }

    private float getThreeFloat(int paramGet, int paramA, int paramB) {
        //have to use bytearrays, with more than 2 parameters.
        byte[] paramBytes = concatArrays(intToByteArray(paramGet),
                intToByteArray(paramA),
                intToByteArray(paramB));
        byte[] resultBytes = new byte[4]; //single float

        checkStatus(getParameter(paramBytes, resultBytes));

        return byteArrayToFloat(resultBytes);
    }

    private float[] getOneFloatArray(int paramGet, int expectedSize) {
        int[] param = new int[1];
        byte[] result = new byte[4 * expectedSize];

        param[0] = paramGet;
        checkStatus(getParameter(param, result));
        float[] returnArray = new float[expectedSize];
        for (int k = 0; k < expectedSize; k++) {
            returnArray[k] = byteArrayToFloat(result, 4 * k);
        }
        return returnArray;
    }
    /**
     * @hide
     * The OnParameterChangeListener interface defines a method called by the DynamicsProcessing
     * when a parameter value has changed.
     */
    public interface OnParameterChangeListener {
        /**
         * Method called when a parameter value has changed. The method is called only if the
         * parameter was changed by another application having the control of the same
         * DynamicsProcessing engine.
         * @param effect the DynamicsProcessing on which the interface is registered.
         * @param param ID of the modified parameter. See {@link #PARAM_GENERIC_PARAM1} ...
         * @param value the new parameter value.
         */
        void onParameterChange(DynamicsProcessing effect, int param, int value);
    }

    /**
     * helper method to update effect architecture parameters
     */
    private void updateEffectArchitecture() {
        mChannelCount = getChannelCount();
    }

    /**
     * Listener used internally to receive unformatted parameter change events from AudioEffect
     * super class.
     */
    private class BaseParameterListener implements AudioEffect.OnParameterChangeListener {
        private BaseParameterListener() {

        }
        public void onParameterChange(AudioEffect effect, int status, byte[] param, byte[] value) {
            // only notify when the parameter was successfully change
            if (status != AudioEffect.SUCCESS) {
                return;
            }
            OnParameterChangeListener l = null;
            synchronized (mParamListenerLock) {
                if (mParamListener != null) {
                    l = mParamListener;
                }
            }
            if (l != null) {
                int p = -1;
                int v = Integer.MIN_VALUE;

                if (param.length == 4) {
                    p = byteArrayToInt(param, 0);
                }
                if (value.length == 4) {
                    v = byteArrayToInt(value, 0);
                }
                if (p != -1 && v != Integer.MIN_VALUE) {
                    l.onParameterChange(DynamicsProcessing.this, p, v);
                }
            }
        }
    }

    /**
     * @hide
     * Registers an OnParameterChangeListener interface.
     * @param listener OnParameterChangeListener interface registered
     */
    public void setParameterListener(OnParameterChangeListener listener) {
        synchronized (mParamListenerLock) {
            if (mParamListener == null) {
                mBaseParamListener = new BaseParameterListener();
                super.setParameterListener(mBaseParamListener);
            }
            mParamListener = listener;
        }
    }

    /**
     * @hide
     * The Settings class regroups the DynamicsProcessing parameters. It is used in
     * conjunction with the getProperties() and setProperties() methods to backup and restore
     * all parameters in a single call.
     */

    public static class Settings {
        public int channelCount;
        public float[] inputGain;

        public Settings() {
        }

        /**
         * Settings class constructor from a key=value; pairs formatted string. The string is
         * typically returned by Settings.toString() method.
         * @throws IllegalArgumentException if the string is not correctly formatted.
         */
        public Settings(String settings) {
            StringTokenizer st = new StringTokenizer(settings, "=;");
            //int tokens = st.countTokens();
            if (st.countTokens() != 3) {
                throw new IllegalArgumentException("settings: " + settings);
            }
            String key = st.nextToken();
            if (!key.equals("DynamicsProcessing")) {
                throw new IllegalArgumentException(
                        "invalid settings for DynamicsProcessing: " + key);
            }
            try {
                key = st.nextToken();
                if (!key.equals("channelCount")) {
                    throw new IllegalArgumentException("invalid key name: " + key);
                }
                channelCount = Short.parseShort(st.nextToken());
                if (channelCount > CHANNEL_COUNT_MAX) {
                    throw new IllegalArgumentException("too many channels Settings:" + settings);
                }
                if (st.countTokens() != channelCount*1) { //check expected parameters.
                    throw new IllegalArgumentException("settings: " + settings);
                }
                //check to see it is ok the size
                inputGain = new float[channelCount];
                for (int ch = 0; ch < channelCount; ch++) {
                    key = st.nextToken();
                    if (!key.equals(ch +"_inputGain")) {
                        throw new IllegalArgumentException("invalid key name: " + key);
                    }
                    inputGain[ch] = Float.parseFloat(st.nextToken());
                }
             } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("invalid value for key: " + key);
            }
        }

        @Override
        public String toString() {
            String str = new String (
                    "DynamicsProcessing"+
                    ";channelCount="+Integer.toString(channelCount));
                    for (int ch = 0; ch < channelCount; ch++) {
                        str = str.concat(";"+ch+"_inputGain="+Float.toString(inputGain[ch]));
                    }
            return str;
        }
    };


    /**
     * @hide
     * Gets the DynamicsProcessing properties. This method is useful when a snapshot of current
     * effect settings must be saved by the application.
     * @return a DynamicsProcessing.Settings object containing all current parameters values
     */
    public DynamicsProcessing.Settings getProperties() {
        Settings settings = new Settings();

        //TODO: just for testing, we are calling the getters one by one, this is
        // supposed to be done in a single (or few calls) and get all the parameters at once.

        settings.channelCount = getChannelCount();

        if (settings.channelCount > CHANNEL_COUNT_MAX) {
            throw new IllegalArgumentException("too many channels Settings:" + settings);
        }

        { // get inputGainmB per channel
            settings.inputGain = new float [settings.channelCount];
            for (int ch = 0; ch < settings.channelCount; ch++) {
//TODO:with config                settings.inputGain[ch] = getInputGain(ch);
            }
        }
        return settings;
    }

    /**
     * @hide
     * Sets the DynamicsProcessing properties. This method is useful when bass boost settings
     * have to be applied from a previous backup.
     * @param settings a DynamicsProcessing.Settings object containing the properties to apply
     */
    public void setProperties(DynamicsProcessing.Settings settings) {

        if (settings.channelCount != settings.inputGain.length ||
                settings.channelCount != mChannelCount) {
                throw new IllegalArgumentException("settings invalid channel count: "
                + settings.channelCount);
            }

        //TODO: for now calling multiple times.
        for (int ch = 0; ch < mChannelCount; ch++) {
//TODO: use config            setInputGain(ch, settings.inputGain[ch]);
        }
    }
}
