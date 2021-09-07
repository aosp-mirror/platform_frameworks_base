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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.audio.policy.configuration.V7_0.AudioUsage;
import android.compat.annotation.UnsupportedAppUsage;
import android.media.audiopolicy.AudioProductStrategy;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A class to encapsulate a collection of attributes describing information about an audio
 * stream.
 * <p><code>AudioAttributes</code> supersede the notion of stream types (see for instance
 * {@link AudioManager#STREAM_MUSIC} or {@link AudioManager#STREAM_ALARM}) for defining the
 * behavior of audio playback. Attributes allow an application to specify more information than is
 * conveyed in a stream type by allowing the application to define:
 * <ul>
 * <li>usage: "why" you are playing a sound, what is this sound used for. This is achieved with
 *     the "usage" information. Examples of usage are {@link #USAGE_MEDIA} and {@link #USAGE_ALARM}.
 *     These two examples are the closest to stream types, but more detailed use cases are
 *     available. Usage information is more expressive than a stream type, and allows certain
 *     platforms or routing policies to use this information for more refined volume or routing
 *     decisions. Usage is the most important information to supply in <code>AudioAttributes</code>
 *     and it is recommended to build any instance with this information supplied, see
 *     {@link AudioAttributes.Builder} for exceptions.</li>
 * <li>content type: "what" you are playing. The content type expresses the general category of
 *     the content. This information is optional. But in case it is known (for instance
 *     {@link #CONTENT_TYPE_MOVIE} for a movie streaming service or {@link #CONTENT_TYPE_MUSIC} for
 *     a music playback application) this information might be used by the audio framework to
 *     selectively configure some audio post-processing blocks.</li>
 * <li>flags: "how" is playback to be affected, see the flag definitions for the specific playback
 *     behaviors they control. </li>
 * </ul>
 * <p><code>AudioAttributes</code> are used for example in one of the {@link AudioTrack}
 * constructors (see {@link AudioTrack#AudioTrack(AudioAttributes, AudioFormat, int, int, int)}),
 * to configure a {@link MediaPlayer}
 * (see {@link MediaPlayer#setAudioAttributes(AudioAttributes)} or a
 * {@link android.app.Notification} (see {@link android.app.Notification#audioAttributes}). An
 * <code>AudioAttributes</code> instance is built through its builder,
 * {@link AudioAttributes.Builder}.
 */
public final class AudioAttributes implements Parcelable {
    private final static String TAG = "AudioAttributes";

    /**
     * Content type value to use when the content type is unknown, or other than the ones defined.
     */
    public final static int CONTENT_TYPE_UNKNOWN = 0;
    /**
     * Content type value to use when the content type is speech.
     */
    public final static int CONTENT_TYPE_SPEECH = 1;
    /**
     * Content type value to use when the content type is music.
     */
    public final static int CONTENT_TYPE_MUSIC = 2;
    /**
     * Content type value to use when the content type is a soundtrack, typically accompanying
     * a movie or TV program.
     */
    public final static int CONTENT_TYPE_MOVIE = 3;
    /**
     * Content type value to use when the content type is a sound used to accompany a user
     * action, such as a beep or sound effect expressing a key click, or event, such as the
     * type of a sound for a bonus being received in a game. These sounds are mostly synthesized
     * or short Foley sounds.
     */
    public final static int CONTENT_TYPE_SONIFICATION = 4;

    /**
     * Invalid value, only ever used for an uninitialized usage value
     */
    private static final int USAGE_INVALID = -1;
    /**
     * Usage value to use when the usage is unknown.
     */
    public final static int USAGE_UNKNOWN = 0;
    /**
     * Usage value to use when the usage is media, such as music, or movie
     * soundtracks.
     */
    public final static int USAGE_MEDIA = 1;
    /**
     * Usage value to use when the usage is voice communications, such as telephony
     * or VoIP.
     */
    public final static int USAGE_VOICE_COMMUNICATION = 2;
    /**
     * Usage value to use when the usage is in-call signalling, such as with
     * a "busy" beep, or DTMF tones.
     */
    public final static int USAGE_VOICE_COMMUNICATION_SIGNALLING = 3;
    /**
     * Usage value to use when the usage is an alarm (e.g. wake-up alarm).
     */
    public final static int USAGE_ALARM = 4;
    /**
     * Usage value to use when the usage is notification. See other
     * notification usages for more specialized uses.
     */
    public final static int USAGE_NOTIFICATION = 5;
    /**
     * Usage value to use when the usage is telephony ringtone.
     */
    public final static int USAGE_NOTIFICATION_RINGTONE = 6;
    /**
     * Usage value to use when the usage is a request to enter/end a
     * communication, such as a VoIP communication or video-conference.
     * @deprecated Use {@link #USAGE_NOTIFICATION} which is handled
     *             the same way as this usage by the audio framework
     */
    @Deprecated
    public final static int USAGE_NOTIFICATION_COMMUNICATION_REQUEST = 7;
    /**
     * Usage value to use when the usage is notification for an "instant"
     * communication such as a chat, or SMS.
     * @deprecated Use {@link #USAGE_NOTIFICATION} which is handled
     *             the same way as this usage by the audio framework
     */
    @Deprecated
    public final static int USAGE_NOTIFICATION_COMMUNICATION_INSTANT = 8;
    /**
     * Usage value to use when the usage is notification for a
     * non-immediate type of communication such as e-mail.
     * @deprecated Use {@link #USAGE_NOTIFICATION} which is handled
     *             the same way as this usage by the audio framework
     */
    @Deprecated
    public final static int USAGE_NOTIFICATION_COMMUNICATION_DELAYED = 9;
    /**
     * Usage value to use when the usage is to attract the user's attention,
     * such as a reminder or low battery warning.
     */
    public final static int USAGE_NOTIFICATION_EVENT = 10;
    /**
     * Usage value to use when the usage is for accessibility, such as with
     * a screen reader.
     */
    public final static int USAGE_ASSISTANCE_ACCESSIBILITY = 11;
    /**
     * Usage value to use when the usage is driving or navigation directions.
     */
    public final static int USAGE_ASSISTANCE_NAVIGATION_GUIDANCE = 12;
    /**
     * Usage value to use when the usage is sonification, such as  with user
     * interface sounds.
     */
    public final static int USAGE_ASSISTANCE_SONIFICATION = 13;
    /**
     * Usage value to use when the usage is for game audio.
     */
    public final static int USAGE_GAME = 14;
    /**
     * @hide
     * Usage value to use when feeding audio to the platform and replacing "traditional" audio
     * source, such as audio capture devices.
     */
    public final static int USAGE_VIRTUAL_SOURCE = 15;
    /**
     * Usage value to use for audio responses to user queries, audio instructions or help
     * utterances.
     */
    public final static int USAGE_ASSISTANT = 16;
    /**
     * @hide
     * Usage value to use for assistant voice interaction with remote caller on Cell and VoIP calls.
     */
    @SystemApi
    @RequiresPermission(allOf = {
            android.Manifest.permission.MODIFY_PHONE_STATE,
            android.Manifest.permission.MODIFY_AUDIO_ROUTING
    })
    public static final int USAGE_CALL_ASSISTANT = 17;

    private static final int SYSTEM_USAGE_OFFSET = 1000;

    /**
     * @hide
     * Usage value to use when the usage is an emergency.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int USAGE_EMERGENCY = SYSTEM_USAGE_OFFSET;
    /**
     * @hide
     * Usage value to use when the usage is a safety sound.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int USAGE_SAFETY = SYSTEM_USAGE_OFFSET + 1;
    /**
     * @hide
     * Usage value to use when the usage is a vehicle status.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int USAGE_VEHICLE_STATUS = SYSTEM_USAGE_OFFSET + 2;
    /**
     * @hide
     * Usage value to use when the usage is an announcement.
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
    public static final int USAGE_ANNOUNCEMENT = SYSTEM_USAGE_OFFSET + 3;

    /**
     * IMPORTANT: when adding new usage types, add them to SDK_USAGES and update SUPPRESSIBLE_USAGES
     *            if applicable, as well as audioattributes.proto.
     *            Also consider adding them to <aaudio/AAudio.h> for the NDK.
     *            Also consider adding them to UsageTypeConverter for service dump and etc.
     */

    /**
     * @hide
     * Denotes a usage for notifications that do not expect immediate intervention from the user,
     * will be muted when the Zen mode disables notifications
     * @see #SUPPRESSIBLE_USAGES
     */
    public final static int SUPPRESSIBLE_NOTIFICATION = 1;
    /**
     * @hide
     * Denotes a usage for notifications that do expect immediate intervention from the user,
     * will be muted when the Zen mode disables calls
     * @see #SUPPRESSIBLE_USAGES
     */
    public final static int SUPPRESSIBLE_CALL = 2;
    /**
     * @hide
     * Denotes a usage that is never going to be muted, even in Total Silence.
     * @see #SUPPRESSIBLE_USAGES
     */
    public final static int SUPPRESSIBLE_NEVER = 3;
    /**
     * @hide
     * Denotes a usage for alarms,
     * will be muted when the Zen mode priority doesn't allow alarms or in Alarms Only Mode
     * @see #SUPPRESSIBLE_USAGES
     */
    public final static int SUPPRESSIBLE_ALARM = 4;
    /**
     * @hide
     * Denotes a usage for media, game, assistant, and navigation
     * will be muted when the Zen priority mode doesn't allow media
     * @see #SUPPRESSIBLE_USAGES
     */
    public final static int SUPPRESSIBLE_MEDIA = 5;
    /**
     * @hide
     * Denotes a usage for sounds not caught in SUPPRESSIBLE_NOTIFICATION,
     * SUPPRESSIBLE_CALL,SUPPRESSIBLE_NEVER, SUPPRESSIBLE_ALARM or SUPPRESSIBLE_MEDIA.
     * This includes sonification sounds.
     * These will be muted when the Zen priority mode doesn't allow system sounds
     * @see #SUPPRESSIBLE_USAGES
     */
    public final static int SUPPRESSIBLE_SYSTEM = 6;

    /**
     * @hide
     * Array of all usage types for calls and notifications to assign the suppression behavior,
     * used by the Zen mode restrictions.
     * @see com.android.server.notification.ZenModeHelper
     */
    public static final SparseIntArray SUPPRESSIBLE_USAGES;

    static {
        SUPPRESSIBLE_USAGES = new SparseIntArray();
        SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION,                      SUPPRESSIBLE_NOTIFICATION);
        SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION_RINGTONE,             SUPPRESSIBLE_CALL);
        SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION_COMMUNICATION_REQUEST,SUPPRESSIBLE_CALL);
        SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION_COMMUNICATION_INSTANT,SUPPRESSIBLE_NOTIFICATION);
        SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION_COMMUNICATION_DELAYED,SUPPRESSIBLE_NOTIFICATION);
        SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION_EVENT,                SUPPRESSIBLE_NOTIFICATION);
        SUPPRESSIBLE_USAGES.put(USAGE_ASSISTANCE_ACCESSIBILITY,          SUPPRESSIBLE_NEVER);
        SUPPRESSIBLE_USAGES.put(USAGE_VOICE_COMMUNICATION,               SUPPRESSIBLE_NEVER);
        SUPPRESSIBLE_USAGES.put(USAGE_VOICE_COMMUNICATION_SIGNALLING,    SUPPRESSIBLE_NEVER);
        SUPPRESSIBLE_USAGES.put(USAGE_ALARM,                             SUPPRESSIBLE_ALARM);
        SUPPRESSIBLE_USAGES.put(USAGE_MEDIA,                             SUPPRESSIBLE_MEDIA);
        SUPPRESSIBLE_USAGES.put(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,    SUPPRESSIBLE_MEDIA);
        SUPPRESSIBLE_USAGES.put(USAGE_GAME,                              SUPPRESSIBLE_MEDIA);
        SUPPRESSIBLE_USAGES.put(USAGE_ASSISTANT,                         SUPPRESSIBLE_MEDIA);
        SUPPRESSIBLE_USAGES.put(USAGE_CALL_ASSISTANT,                    SUPPRESSIBLE_NEVER);
        /** default volume assignment is STREAM_MUSIC, handle unknown usage as media */
        SUPPRESSIBLE_USAGES.put(USAGE_UNKNOWN,                           SUPPRESSIBLE_MEDIA);
        SUPPRESSIBLE_USAGES.put(USAGE_ASSISTANCE_SONIFICATION,           SUPPRESSIBLE_SYSTEM);
    }

    /**
     * @hide
     * Array of all usage types exposed in the SDK that applications can use.
     */
    public final static int[] SDK_USAGES = {
            USAGE_UNKNOWN,
            USAGE_MEDIA,
            USAGE_VOICE_COMMUNICATION,
            USAGE_VOICE_COMMUNICATION_SIGNALLING,
            USAGE_ALARM,
            USAGE_NOTIFICATION,
            USAGE_NOTIFICATION_RINGTONE,
            USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            USAGE_NOTIFICATION_EVENT,
            USAGE_ASSISTANCE_ACCESSIBILITY,
            USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            USAGE_ASSISTANCE_SONIFICATION,
            USAGE_GAME,
            USAGE_ASSISTANT,
    };

    /**
     * @hide
     */
    @TestApi
    public static int[] getSdkUsages() {
        return SDK_USAGES;
    }

    /**
     * Flag defining a behavior where the audibility of the sound will be ensured by the system.
     * To ensure sound audibility, the system only uses built-in speakers or wired headphones
     * and specifically excludes wireless audio devices.
     * <p>Note this flag should only be used for sounds subject to regulatory behaviors in some
     * countries, such as for camera shutter sound, and not for routing behaviors.
     */
    public final static int FLAG_AUDIBILITY_ENFORCED = 0x1 << 0;
    /**
     * @hide
     * Flag defining a behavior where the playback of the sound is ensured without
     * degradation only when going to a secure sink.
     */
    // FIXME not guaranteed yet
    // TODO  add in FLAG_ALL_PUBLIC when supported and in public API
    public final static int FLAG_SECURE = 0x1 << 1;
    /**
     * @hide
     * Flag to enable when the stream is associated with SCO usage.
     * Internal use only for dealing with legacy STREAM_BLUETOOTH_SCO
     */
    public final static int FLAG_SCO = 0x1 << 2;
    /**
     * @hide
     * Flag defining a behavior where the system ensures that the playback of the sound will
     * be compatible with its use as a broadcast for surrounding people and/or devices.
     * Ensures audibility with no or minimal post-processing applied.
     */
    @SystemApi
    public final static int FLAG_BEACON = 0x1 << 3;

    /**
     * Flag requesting the use of an output stream supporting hardware A/V synchronization.
     */
    public final static int FLAG_HW_AV_SYNC = 0x1 << 4;

    /**
     * @hide
     * Flag requesting capture from the source used for hardware hotword detection.
     * To be used with capture preset MediaRecorder.AudioSource.HOTWORD or
     * MediaRecorder.AudioSource.VOICE_RECOGNITION.
     */
    @SystemApi
    public final static int FLAG_HW_HOTWORD = 0x1 << 5;

    /**
     * @hide
     * Flag requesting audible playback even under limited interruptions.
     */
    @SystemApi
    public final static int FLAG_BYPASS_INTERRUPTION_POLICY = 0x1 << 6;

    /**
     * @hide
     * Flag requesting audible playback even when the underlying stream is muted.
     */
    @SystemApi
    public final static int FLAG_BYPASS_MUTE = 0x1 << 7;

    /**
     * Flag requesting a low latency path when creating an AudioTrack.
     * When using this flag, the sample rate must match the native sample rate
     * of the device. Effects processing is also unavailable.
     *
     * Note that if this flag is used without specifying a bufferSizeInBytes then the
     * AudioTrack's actual buffer size may be too small. It is recommended that a fairly
     * large buffer should be specified when the AudioTrack is created.
     * Then the actual size can be reduced by calling
     * {@link AudioTrack#setBufferSizeInFrames(int)}. The buffer size can be optimized
     * by lowering it after each write() call until the audio glitches, which is detected by calling
     * {@link AudioTrack#getUnderrunCount()}. Then the buffer size can be increased
     * until there are no glitches.
     * This tuning step should be done while playing silence.
     * This technique provides a compromise between latency and glitch rate.
     *
     * @deprecated Use {@link AudioTrack.Builder#setPerformanceMode(int)} with
     * {@link AudioTrack#PERFORMANCE_MODE_LOW_LATENCY} to control performance.
     */
    public final static int FLAG_LOW_LATENCY = 0x1 << 8;

    /**
     * @hide
     * Flag requesting a deep buffer path when creating an {@code AudioTrack}.
     *
     * A deep buffer path, if available, may consume less power and is
     * suitable for media playback where latency is not a concern.
     * Use {@link AudioTrack.Builder#setPerformanceMode(int)} with
     * {@link AudioTrack#PERFORMANCE_MODE_POWER_SAVING} to enable.
     */
    public final static int FLAG_DEEP_BUFFER = 0x1 << 9;

    /**
     * @hide
     * Flag specifying that the audio shall not be captured by third-party apps
     * with a MediaProjection.
     */
    public static final int FLAG_NO_MEDIA_PROJECTION = 0x1 << 10;

    /**
     * @hide
     * Flag indicating force muting haptic channels.
     */
    public static final int FLAG_MUTE_HAPTIC = 0x1 << 11;

    /**
     * @hide
     * Flag specifying that the audio shall not be captured by any apps, not even system apps.
     */
    public static final int FLAG_NO_SYSTEM_CAPTURE = 0x1 << 12;

    /**
     * @hide
     * Flag requesting private audio capture. When set in audio attributes passed to an
     * AudioRecord, this prevents a privileged Assistant from capturing audio while this
     * AudioRecord is active.
     */
    public static final int FLAG_CAPTURE_PRIVATE = 0x1 << 13;

    /**
     * @hide
     * Flag indicating the audio content has been processed to provide a virtual multichannel
     * audio experience
     */
    public static final int FLAG_CONTENT_SPATIALIZED = 0x1 << 14;

    /**
     * @hide
     * Flag indicating the audio content is to never be spatialized
     */
    public static final int FLAG_NEVER_SPATIALIZE = 0x1 << 15;

    // Note that even though FLAG_MUTE_HAPTIC is stored as a flag bit, it is not here since
    // it is known as a boolean value outside of AudioAttributes.
    private static final int FLAG_ALL = FLAG_AUDIBILITY_ENFORCED | FLAG_SECURE | FLAG_SCO
            | FLAG_BEACON | FLAG_HW_AV_SYNC | FLAG_HW_HOTWORD | FLAG_BYPASS_INTERRUPTION_POLICY
            | FLAG_BYPASS_MUTE | FLAG_LOW_LATENCY | FLAG_DEEP_BUFFER | FLAG_NO_MEDIA_PROJECTION
            | FLAG_NO_SYSTEM_CAPTURE | FLAG_CAPTURE_PRIVATE | FLAG_CONTENT_SPATIALIZED
            | FLAG_NEVER_SPATIALIZE;
    private final static int FLAG_ALL_PUBLIC = FLAG_AUDIBILITY_ENFORCED |
            FLAG_HW_AV_SYNC | FLAG_LOW_LATENCY;
    /* mask of flags that can be set by SDK and System APIs through the Builder */
    private static final int FLAG_ALL_API_SET = FLAG_ALL_PUBLIC
            | FLAG_BYPASS_INTERRUPTION_POLICY
            | FLAG_BYPASS_MUTE;

    /**
     * Indicates that the audio may be captured by any app.
     *
     * For privacy, the following usages cannot be recorded: VOICE_COMMUNICATION*,
     * USAGE_NOTIFICATION*, USAGE_ASSISTANCE* and USAGE_ASSISTANT.
     *
     * On {@link android.os.Build.VERSION_CODES#Q}, this means only {@link #USAGE_UNKNOWN},
     * {@link #USAGE_MEDIA} and {@link #USAGE_GAME} may be captured.
     *
     * See {@link android.media.projection.MediaProjection} and
     * {@link Builder#setAllowedCapturePolicy}.
     */
    public static final int ALLOW_CAPTURE_BY_ALL = 1;
    /**
     * Indicates that the audio may only be captured by system apps.
     *
     * System apps can capture for many purposes like accessibility, live captions, user guidance...
     * but abide to the following restrictions:
     *  - the audio cannot leave the device
     *  - the audio cannot be passed to a third party app
     *  - the audio cannot be recorded at a higher quality than 16kHz 16bit mono
     *
     * See {@link Builder#setAllowedCapturePolicy}.
     */
    public static final int ALLOW_CAPTURE_BY_SYSTEM = 2;
    /**
     * Indicates that the audio is not to be recorded by any app, even if it is a system app.
     *
     * It is encouraged to use {@link #ALLOW_CAPTURE_BY_SYSTEM} instead of this value as system apps
     * provide significant and useful features for the user (such as live captioning
     * and accessibility).
     *
     * See {@link Builder#setAllowedCapturePolicy}.
     */
    public static final int ALLOW_CAPTURE_BY_NONE = 3;

    /** @hide */
    @IntDef({
        ALLOW_CAPTURE_BY_ALL,
        ALLOW_CAPTURE_BY_SYSTEM,
        ALLOW_CAPTURE_BY_NONE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CapturePolicy {}

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private int mUsage = USAGE_UNKNOWN;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mContentType = CONTENT_TYPE_UNKNOWN;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mSource = MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mFlags = 0x0;
    private HashSet<String> mTags;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private String mFormattedTags;
    private Bundle mBundle; // lazy-initialized, may be null

    private AudioAttributes() {
    }

    /**
     * Return the content type.
     * @return one of the values that can be set in {@link Builder#setContentType(int)}
     */
    public int getContentType() {
        return mContentType;
    }

    /**
     * Return the usage.
     * @return one of the values that can be set in {@link Builder#setUsage(int)}
     */
    public int getUsage() {
        if (isSystemUsage(mUsage)) {
            return USAGE_UNKNOWN;
        }
        return mUsage;
    }

    /**
     * @hide
     * Return the system usage.
     * @return one of the values that can be set in {@link Builder#setUsage(int)} or
     * {@link Builder#setSystemUsage(int)}
     */
    @SystemApi
    public int getSystemUsage() {
        return mUsage;
    }

    /**
     * @hide
     * Return the capture preset.
     * @return one of the values that can be set in {@link Builder#setCapturePreset(int)} or a
     *    negative value if none has been set.
     */
    @SystemApi
    public int getCapturePreset() {
        return mSource;
    }

    /**
     * Return the flags.
     * @return a combined mask of all flags
     */
    public int getFlags() {
        // only return the flags that are public
        return (mFlags & (FLAG_ALL_PUBLIC));
    }

    /**
     * @hide
     * Return all the flags, even the non-public ones.
     * Internal use only
     * @return a combined mask of all flags
     */
    @SystemApi
    public int getAllFlags() {
        return (mFlags & FLAG_ALL);
    }

    /**
     * @hide
     * Return the Bundle of data.
     * @return a copy of the Bundle for this instance, may be null.
     */
    @SystemApi
    public Bundle getBundle() {
        if (mBundle == null) {
            return mBundle;
        } else {
            return new Bundle(mBundle);
        }
    }

    /**
     * @hide
     * Return the set of tags.
     * @return a read-only set of all tags stored as strings.
     */
    public Set<String> getTags() {
        return Collections.unmodifiableSet(mTags);
    }

    /**
     * Return if haptic channels are muted.
     * @return {@code true} if haptic channels are muted, {@code false} otherwise.
     */
    public boolean areHapticChannelsMuted() {
        return (mFlags & FLAG_MUTE_HAPTIC) != 0;
    }

    /**
     * Return true if the audio content associated with these attributes has already been
     * spatialized, that is it has already been processed to offer a binaural or transaural
     * immersive audio experience.
     * @return {@code true} if the content has been processed
     */
    public boolean isContentSpatialized() {
        return (mFlags & FLAG_CONTENT_SPATIALIZED) != 0;
    }

    /** @hide */
    @IntDef(flag = false, value = {
            SPATIALIZATION_BEHAVIOR_AUTO,
            SPATIALIZATION_BEHAVIOR_NEVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SpatializationBehavior {};

    /**
     * Constant indicating the audio content associated with these attributes will follow the
     * default platform behavior with regards to which content will be spatialized or not.
     * @see #getSpatializationBehavior()
     * @see Spatializer
     */
    public static final int SPATIALIZATION_BEHAVIOR_AUTO = 0;

    /**
     * Constant indicating the audio content associated with these attributes should never
     * be virtualized.
     * @see #getSpatializationBehavior()
     * @see Spatializer
     */
    public static final int SPATIALIZATION_BEHAVIOR_NEVER = 1;

    /**
     * Return the behavior affecting whether spatialization will be used.
     * @return the spatialization behavior
     */
    public @SpatializationBehavior int getSpatializationBehavior() {
        return ((mFlags & FLAG_NEVER_SPATIALIZE) != 0)
                ? SPATIALIZATION_BEHAVIOR_NEVER : SPATIALIZATION_BEHAVIOR_AUTO;
    }

    /**
     * Return the capture policy.
     * @return the capture policy set by {@link Builder#setAllowedCapturePolicy(int)} or
     *         the default if it was not called.
     */
    @CapturePolicy
    public int getAllowedCapturePolicy() {
        if ((mFlags & FLAG_NO_SYSTEM_CAPTURE) == FLAG_NO_SYSTEM_CAPTURE) {
            return ALLOW_CAPTURE_BY_NONE;
        }
        if ((mFlags & FLAG_NO_MEDIA_PROJECTION) == FLAG_NO_MEDIA_PROJECTION) {
            return ALLOW_CAPTURE_BY_SYSTEM;
        }
        return ALLOW_CAPTURE_BY_ALL;
    }


    /**
     * Builder class for {@link AudioAttributes} objects.
     * <p> Here is an example where <code>Builder</code> is used to define the
     * {@link AudioAttributes} to be used by a new <code>AudioTrack</code> instance:
     *
     * <pre class="prettyprint">
     * AudioTrack myTrack = new AudioTrack(
     *         new AudioAttributes.Builder()
     *             .setUsage(AudioAttributes.USAGE_MEDIA)
     *             .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
     *             .build(),
     *         myFormat, myBuffSize, AudioTrack.MODE_STREAM, mySession);
     * </pre>
     *
     * <p>By default all types of information (usage, content type, flags) conveyed by an
     * <code>AudioAttributes</code> instance are set to "unknown". Unknown information will be
     * interpreted as a default value that is dependent on the context of use, for instance a
     * {@link MediaPlayer} will use a default usage of {@link AudioAttributes#USAGE_MEDIA}.
     */
    public static class Builder {
        private int mUsage = USAGE_INVALID;
        private int mSystemUsage = USAGE_INVALID;
        private int mContentType = CONTENT_TYPE_UNKNOWN;
        private int mSource = MediaRecorder.AudioSource.AUDIO_SOURCE_INVALID;
        private int mFlags = 0x0;
        private boolean mMuteHapticChannels = true;
        private boolean mIsContentSpatialized = false;
        private int mSpatializationBehavior = SPATIALIZATION_BEHAVIOR_AUTO;
        private HashSet<String> mTags = new HashSet<String>();
        private Bundle mBundle;
        private int mPrivacySensitive = PRIVACY_SENSITIVE_DEFAULT;

        private static final int PRIVACY_SENSITIVE_DEFAULT = -1;
        private static final int PRIVACY_SENSITIVE_DISABLED = 0;
        private static final int PRIVACY_SENSITIVE_ENABLED = 1;

        /**
         * Constructs a new Builder with the defaults.
         * By default, usage and content type are respectively {@link AudioAttributes#USAGE_UNKNOWN}
         * and {@link AudioAttributes#CONTENT_TYPE_UNKNOWN}, and flags are 0. It is recommended to
         * configure the usage (with {@link #setUsage(int)}) or deriving attributes from a legacy
         * stream type (with {@link #setLegacyStreamType(int)}) before calling {@link #build()}
         * to override any default playback behavior in terms of routing and volume management.
         */
        public Builder() {
        }

        /**
         * Constructs a new Builder from a given AudioAttributes
         * @param aa the AudioAttributes object whose data will be reused in the new Builder.
         */
        @SuppressWarnings("unchecked") // for cloning of mTags
        public Builder(AudioAttributes aa) {
            mUsage = aa.mUsage;
            mContentType = aa.mContentType;
            mFlags = aa.getAllFlags();
            mTags = (HashSet<String>) aa.mTags.clone();
            mMuteHapticChannels = aa.areHapticChannelsMuted();
            mIsContentSpatialized = aa.isContentSpatialized();
            mSpatializationBehavior = aa.getSpatializationBehavior();
        }

        /**
         * Combines all of the attributes that have been set and return a new
         * {@link AudioAttributes} object.
         * @return a new {@link AudioAttributes} object
         */
        @SuppressWarnings("unchecked") // for cloning of mTags
        public AudioAttributes build() {
            AudioAttributes aa = new AudioAttributes();
            aa.mContentType = mContentType;

            if (mUsage == USAGE_INVALID) {
                if (mSystemUsage == USAGE_INVALID) {
                    aa.mUsage = USAGE_UNKNOWN;
                } else {
                    aa.mUsage = mSystemUsage;
                }
            } else {
                if (mSystemUsage == USAGE_INVALID) {
                    aa.mUsage = mUsage;
                } else {
                    throw new IllegalArgumentException(
                            "Cannot set both usage and system usage on same builder");
                }
            }

            // handle deprecation of notification usages by remapping to USAGE_NOTIFICATION
            switch (aa.mUsage) {
                case USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
                case USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
                case USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
                    aa.mUsage = USAGE_NOTIFICATION;
                    break;
                default:
                    break;
            }

            aa.mSource = mSource;
            aa.mFlags = mFlags;
            if (mMuteHapticChannels) {
                aa.mFlags |= FLAG_MUTE_HAPTIC;
            }
            if (mIsContentSpatialized) {
                aa.mFlags |= FLAG_CONTENT_SPATIALIZED;
            }
            if (mSpatializationBehavior == SPATIALIZATION_BEHAVIOR_NEVER) {
                aa.mFlags |= FLAG_NEVER_SPATIALIZE;
            }

            if (mPrivacySensitive == PRIVACY_SENSITIVE_DEFAULT) {
                // capturing for camcorder or communication is private by default to
                // reflect legacy behavior
                if (mSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        || mSource == MediaRecorder.AudioSource.CAMCORDER) {
                    aa.mFlags |= FLAG_CAPTURE_PRIVATE;
                } else {
                    aa.mFlags &= ~FLAG_CAPTURE_PRIVATE;
                }
            } else if (mPrivacySensitive == PRIVACY_SENSITIVE_ENABLED) {
                aa.mFlags |= FLAG_CAPTURE_PRIVATE;
            } else {
                aa.mFlags &= ~FLAG_CAPTURE_PRIVATE;
            }
            aa.mTags = (HashSet<String>) mTags.clone();
            aa.mFormattedTags = TextUtils.join(";", mTags);
            if (mBundle != null) {
                aa.mBundle = new Bundle(mBundle);
            }

            // Allow the FLAG_HW_HOTWORD only for AudioSource.VOICE_RECOGNITION
            if (mSource != MediaRecorder.AudioSource.VOICE_RECOGNITION
                    && (mFlags & FLAG_HW_HOTWORD) == FLAG_HW_HOTWORD) {
                aa.mFlags &= ~FLAG_HW_HOTWORD;
            }
            return aa;
        }

        /**
         * Sets the attribute describing what is the intended use of the audio signal,
         * such as alarm or ringtone.
         * @param usage one of {@link AttributeSdkUsage#USAGE_UNKNOWN},
         *     {@link AttributeSdkUsage#USAGE_MEDIA},
         *     {@link AttributeSdkUsage#USAGE_VOICE_COMMUNICATION},
         *     {@link AttributeSdkUsage#USAGE_VOICE_COMMUNICATION_SIGNALLING},
         *     {@link AttributeSdkUsage#USAGE_ALARM}, {@link AudioAttributes#USAGE_NOTIFICATION},
         *     {@link AttributeSdkUsage#USAGE_NOTIFICATION_RINGTONE},
         *     {@link AttributeSdkUsage#USAGE_NOTIFICATION_EVENT},
         *     {@link AttributeSdkUsage#USAGE_ASSISTANT},
         *     {@link AttributeSdkUsage#USAGE_ASSISTANCE_ACCESSIBILITY},
         *     {@link AttributeSdkUsage#USAGE_ASSISTANCE_NAVIGATION_GUIDANCE},
         *     {@link AttributeSdkUsage#USAGE_ASSISTANCE_SONIFICATION},
         *     {@link AttributeSdkUsage#USAGE_GAME}.
         * @return the same Builder instance.
         */
        public Builder setUsage(@AttributeSdkUsage int usage) {
            switch (usage) {
                case USAGE_UNKNOWN:
                case USAGE_MEDIA:
                case USAGE_VOICE_COMMUNICATION:
                case USAGE_VOICE_COMMUNICATION_SIGNALLING:
                case USAGE_ALARM:
                case USAGE_NOTIFICATION:
                case USAGE_NOTIFICATION_RINGTONE:
                case USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
                case USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
                case USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
                case USAGE_NOTIFICATION_EVENT:
                case USAGE_ASSISTANCE_ACCESSIBILITY:
                case USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
                case USAGE_ASSISTANCE_SONIFICATION:
                case USAGE_GAME:
                case USAGE_VIRTUAL_SOURCE:
                case USAGE_ASSISTANT:
                    mUsage = usage;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid usage " + usage);
            }
            return this;
        }

        /**
         * @hide
         * Sets the attribute describing what is the intended use of the audio signal for categories
         * of sounds restricted to the system, such as vehicle status or emergency.
         *
         * <p>Note that the AudioAttributes have a single usage value, therefore it is illegal to
         * call both this method and {@link #setUsage(int)}.
         * @param systemUsage the system-restricted usage.
         * @return the same Builder instance.
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)
        public @NonNull Builder setSystemUsage(@AttributeSystemUsage int systemUsage) {
            if (isSystemUsage(systemUsage)) {
                mSystemUsage = systemUsage;
            } else {
                throw new IllegalArgumentException("Invalid system usage " + systemUsage);
            }

            return this;
        }

        /**
         * Sets the attribute describing the content type of the audio signal, such as speech,
         * or music.
         * @param contentType the content type values, one of
         *     {@link AudioAttributes#CONTENT_TYPE_MOVIE},
         *     {@link AudioAttributes#CONTENT_TYPE_MUSIC},
         *     {@link AudioAttributes#CONTENT_TYPE_SONIFICATION},
         *     {@link AudioAttributes#CONTENT_TYPE_SPEECH},
         *     {@link AudioAttributes#CONTENT_TYPE_UNKNOWN}.
         * @return the same Builder instance.
         */
        public Builder setContentType(@AttributeContentType int contentType) {
            switch (contentType) {
                case CONTENT_TYPE_UNKNOWN:
                case CONTENT_TYPE_MOVIE:
                case CONTENT_TYPE_MUSIC:
                case CONTENT_TYPE_SONIFICATION:
                case CONTENT_TYPE_SPEECH:
                    mContentType = contentType;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid content type " + contentType);
            }
            return this;
        }

        /**
         * Sets the combination of flags.
         *
         * This is a bitwise OR with the existing flags.
         * @param flags a combination of {@link AudioAttributes#FLAG_AUDIBILITY_ENFORCED},
         *    {@link AudioAttributes#FLAG_HW_AV_SYNC}.
         * @return the same Builder instance.
         */
        public Builder setFlags(int flags) {
            flags &= AudioAttributes.FLAG_ALL_API_SET;
            mFlags |= flags;
            return this;
        }

        /**
         * @hide
         * Request for capture in hotword mode.
         *
         * Requests an audio path optimized for Hotword detection use cases from
         * the low power audio DSP. This is valid only for capture with
         * audio source {@link MediaRecorder.AudioSource#VOICE_RECOGNITION}.
         * There is no guarantee that this mode is available on the device.
         * @return the same Builder instance.
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.CAPTURE_AUDIO_HOTWORD)
        public @NonNull Builder setHotwordModeEnabled(boolean enable) {
            if (enable) {
                mFlags |= FLAG_HW_HOTWORD;
            } else {
                mFlags &= ~FLAG_HW_HOTWORD;
            }
            return this;
        }

        /**
         * Specifies whether the audio may or may not be captured by other apps or the system.
         *
         * The default is {@link AudioAttributes#ALLOW_CAPTURE_BY_ALL}.
         *
         * There are multiple ways to set this policy:
         * <ul>
         * <li> for each track independently, with this method </li>
         * <li> application-wide at runtime, with
         *      {@link AudioManager#setAllowedCapturePolicy(int)} </li>
         * <li> application-wide at build time, see {@code allowAudioPlaybackCapture} in the
         *      application manifest. </li>
         * </ul>
         * The most restrictive policy is always applied.
         *
         * See {@link AudioPlaybackCaptureConfiguration} for more details on
         * which audio signals can be captured.
         *
         * @return the same Builder instance
         * @throws IllegalArgumentException if the argument is not a valid value.
         */
        public @NonNull Builder setAllowedCapturePolicy(@CapturePolicy int capturePolicy) {
            mFlags = capturePolicyToFlags(capturePolicy, mFlags);
            return this;
        }

        /**
         * Specifies whether the content has already been processed for spatialization.
         * If it has, setting this to true will prevent issues such as double-processing.
         * @param isSpatialized
         * @return the same Builder instance
         */
        public @NonNull Builder setIsContentSpatialized(boolean isSpatialized) {
            mIsContentSpatialized = isSpatialized;
            return this;
        }

        /**
         * Sets the behavior affecting whether spatialization will be used.
         * @param sb the spatialization behavior
         * @return the same Builder instance
         *
         */
        public @NonNull Builder setSpatializationBehavior(@SpatializationBehavior int sb) {
            switch (sb) {
                case SPATIALIZATION_BEHAVIOR_NEVER:
                case SPATIALIZATION_BEHAVIOR_AUTO:
                    break;
                default:
                    throw new IllegalArgumentException("Invalid spatialization behavior " + sb);
            }
            mSpatializationBehavior = sb;
            return this;
        }

        /**
         * @hide
         * Replaces flags.
         * @param flags any combination of {@link AudioAttributes#FLAG_ALL}.
         * @return the same Builder instance.
         */
        public Builder replaceFlags(int flags) {
            mFlags = flags & AudioAttributes.FLAG_ALL;
            return this;
        }

        /**
         * @hide
         * Adds a Bundle of data
         * @param bundle a non-null Bundle
         * @return the same builder instance
         */
        @SystemApi
        public Builder addBundle(@NonNull Bundle bundle) {
            if (bundle == null) {
                throw new IllegalArgumentException("Illegal null bundle");
            }
            if (mBundle == null) {
                mBundle = new Bundle(bundle);
            } else {
                mBundle.putAll(bundle);
            }
            return this;
        }

        /**
         * @hide
         * Add a custom tag stored as a string
         * @param tag
         * @return the same Builder instance.
         */
        @UnsupportedAppUsage
        public Builder addTag(String tag) {
            mTags.add(tag);
            return this;
        }

        /**
         * Sets attributes as inferred from the legacy stream types.
         * Warning: do not use this method in combination with setting any other attributes such as
         * usage, content type, flags or haptic control, as this method will overwrite (the more
         * accurate) information describing the use case previously set in the <code>Builder</code>.
         * In general, avoid using it and prefer setting usage and content type directly
         * with {@link #setUsage(int)} and {@link #setContentType(int)}.
         * <p>Use this method when building an {@link AudioAttributes} instance to initialize some
         * of the attributes by information derived from a legacy stream type.
         * @param streamType one of {@link AudioManager#STREAM_VOICE_CALL},
         *   {@link AudioManager#STREAM_SYSTEM}, {@link AudioManager#STREAM_RING},
         *   {@link AudioManager#STREAM_MUSIC}, {@link AudioManager#STREAM_ALARM},
         *    or {@link AudioManager#STREAM_NOTIFICATION}.
         * @return the same Builder instance.
         */
        public Builder setLegacyStreamType(int streamType) {
            if (streamType == AudioManager.STREAM_ACCESSIBILITY) {
                throw new IllegalArgumentException("STREAM_ACCESSIBILITY is not a legacy stream "
                        + "type that was used for audio playback");
            }
            setInternalLegacyStreamType(streamType);
            return this;
        }

        /**
         * @hide
         * For internal framework use only, enables building from hidden stream types.
         * @param streamType
         * @return the same Builder instance.
         */
        @UnsupportedAppUsage
        public Builder setInternalLegacyStreamType(int streamType) {
            mContentType = CONTENT_TYPE_UNKNOWN;
            mUsage = USAGE_UNKNOWN;
            if (AudioProductStrategy.getAudioProductStrategies().size() > 0) {
                AudioAttributes attributes =
                        AudioProductStrategy.getAudioAttributesForStrategyWithLegacyStreamType(
                                streamType);
                if (attributes != null) {
                    mUsage = attributes.mUsage;
                    mContentType = attributes.mContentType;
                    mFlags = attributes.getAllFlags();
                    mMuteHapticChannels = attributes.areHapticChannelsMuted();
                    mIsContentSpatialized = attributes.isContentSpatialized();
                    mSpatializationBehavior = attributes.getSpatializationBehavior();
                    mTags = attributes.mTags;
                    mBundle = attributes.mBundle;
                    mSource = attributes.mSource;
                }
            }
            if (mContentType == CONTENT_TYPE_UNKNOWN) {
                switch (streamType) {
                    case AudioSystem.STREAM_VOICE_CALL:
                        mContentType = CONTENT_TYPE_SPEECH;
                        break;
                    case AudioSystem.STREAM_SYSTEM_ENFORCED:
                        mFlags |= FLAG_AUDIBILITY_ENFORCED;
                        // intended fall through, attributes in common with STREAM_SYSTEM
                    case AudioSystem.STREAM_SYSTEM:
                        mContentType = CONTENT_TYPE_SONIFICATION;
                        break;
                    case AudioSystem.STREAM_RING:
                        mContentType = CONTENT_TYPE_SONIFICATION;
                        break;
                    case AudioSystem.STREAM_MUSIC:
                        mContentType = CONTENT_TYPE_MUSIC;
                        break;
                    case AudioSystem.STREAM_ALARM:
                        mContentType = CONTENT_TYPE_SONIFICATION;
                        break;
                    case AudioSystem.STREAM_NOTIFICATION:
                        mContentType = CONTENT_TYPE_SONIFICATION;
                        break;
                    case AudioSystem.STREAM_BLUETOOTH_SCO:
                        mContentType = CONTENT_TYPE_SPEECH;
                        mFlags |= FLAG_SCO;
                        break;
                    case AudioSystem.STREAM_DTMF:
                        mContentType = CONTENT_TYPE_SONIFICATION;
                        break;
                    case AudioSystem.STREAM_TTS:
                        mContentType = CONTENT_TYPE_SONIFICATION;
                        mFlags |= FLAG_BEACON;
                        break;
                    case AudioSystem.STREAM_ACCESSIBILITY:
                        mContentType = CONTENT_TYPE_SPEECH;
                        break;
                    default:
                        Log.e(TAG, "Invalid stream type " + streamType + " for AudioAttributes");
                }
            }
            if (mUsage == USAGE_UNKNOWN) {
                mUsage = usageForStreamType(streamType);
            }
            return this;
        }

        /**
         * @hide
         * Sets the capture preset.
         * Use this audio attributes configuration method when building an {@link AudioRecord}
         * instance with {@link AudioRecord#AudioRecord(AudioAttributes, AudioFormat, int)}.
         * @param preset one of {@link MediaRecorder.AudioSource#DEFAULT},
         *     {@link MediaRecorder.AudioSource#MIC}, {@link MediaRecorder.AudioSource#CAMCORDER},
         *     {@link MediaRecorder.AudioSource#VOICE_RECOGNITION},
         *     {@link MediaRecorder.AudioSource#VOICE_COMMUNICATION},
         *     {@link MediaRecorder.AudioSource#UNPROCESSED} or
         *     {@link MediaRecorder.AudioSource#VOICE_PERFORMANCE}
         * @return the same Builder instance.
         */
        @SystemApi
        public Builder setCapturePreset(int preset) {
            switch (preset) {
                case MediaRecorder.AudioSource.DEFAULT:
                case MediaRecorder.AudioSource.MIC:
                case MediaRecorder.AudioSource.CAMCORDER:
                case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                case MediaRecorder.AudioSource.VOICE_COMMUNICATION:
                case MediaRecorder.AudioSource.UNPROCESSED:
                case MediaRecorder.AudioSource.VOICE_PERFORMANCE:
                    mSource = preset;
                    break;
                default:
                    Log.e(TAG, "Invalid capture preset " + preset + " for AudioAttributes");
            }
            return this;
        }

        /**
         * @hide
         * Same as {@link #setCapturePreset(int)} but authorizes the use of HOTWORD,
         * REMOTE_SUBMIX, RADIO_TUNER, VOICE_DOWNLINK, VOICE_UPLINK, VOICE_CALL and ECHO_REFERENCE.
         * @param preset
         * @return the same Builder instance.
         */
        @SystemApi
        public Builder setInternalCapturePreset(int preset) {
            if ((preset == MediaRecorder.AudioSource.HOTWORD)
                    || (preset == MediaRecorder.AudioSource.REMOTE_SUBMIX)
                    || (preset == MediaRecorder.AudioSource.RADIO_TUNER)
                    || (preset == MediaRecorder.AudioSource.VOICE_DOWNLINK)
                    || (preset == MediaRecorder.AudioSource.VOICE_UPLINK)
                    || (preset == MediaRecorder.AudioSource.VOICE_CALL)
                    || (preset == MediaRecorder.AudioSource.ECHO_REFERENCE)) {
                mSource = preset;
            } else {
                setCapturePreset(preset);
            }
            return this;
        }

        /**
         * Specifying if haptic should be muted or not when playing audio-haptic coupled data.
         * By default, haptic channels are disabled.
         * @param muted true to force muting haptic channels.
         * @return the same Builder instance.
         */
        public @NonNull Builder setHapticChannelsMuted(boolean muted) {
            mMuteHapticChannels = muted;
            return this;
        }

        /**
         * @hide
         * Indicates if an AudioRecord build with this AudioAttributes is privacy sensitive or not.
         * See {@link AudioRecord.Builder#setPrivacySensitive(boolean)}.
         * @param privacySensitive True if capture must be marked as privacy sensitive,
         * false otherwise.
         * @return the same Builder instance.
         */
        public @NonNull Builder setPrivacySensitive(boolean privacySensitive) {
            mPrivacySensitive =
                privacySensitive ? PRIVACY_SENSITIVE_ENABLED : PRIVACY_SENSITIVE_DISABLED;
            return this;
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     * Used to indicate that when parcelling, the tags should be parcelled through the flattened
     * formatted string, not through the array of strings.
     * Keep in sync with frameworks/av/media/libmediaplayerservice/MediaPlayerService.cpp
     * see definition of kAudioAttributesMarshallTagFlattenTags
     */
    public final static int FLATTEN_TAGS = 0x1;

    private final static int ATTR_PARCEL_IS_NULL_BUNDLE = -1977;
    private final static int ATTR_PARCEL_IS_VALID_BUNDLE = 1980;

    /**
     * When adding tags for writeToParcel(Parcel, int), add them in the list of flags (| NEW_FLAG)
     */
    private final static int ALL_PARCEL_FLAGS = FLATTEN_TAGS;
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUsage);
        dest.writeInt(mContentType);
        dest.writeInt(mSource);
        dest.writeInt(mFlags);
        dest.writeInt(flags & ALL_PARCEL_FLAGS);
        if ((flags & FLATTEN_TAGS) == 0) {
            String[] tagsArray = new String[mTags.size()];
            mTags.toArray(tagsArray);
            dest.writeStringArray(tagsArray);
        } else if ((flags & FLATTEN_TAGS) == FLATTEN_TAGS) {
            dest.writeString(mFormattedTags);
        }
        if (mBundle == null) {
            dest.writeInt(ATTR_PARCEL_IS_NULL_BUNDLE);
        } else {
            dest.writeInt(ATTR_PARCEL_IS_VALID_BUNDLE);
            dest.writeBundle(mBundle);
        }
    }

    private AudioAttributes(Parcel in) {
        mUsage = in.readInt();
        mContentType = in.readInt();
        mSource = in.readInt();
        mFlags = in.readInt();
        boolean hasFlattenedTags = ((in.readInt() & FLATTEN_TAGS) == FLATTEN_TAGS);
        mTags = new HashSet<String>();
        if (hasFlattenedTags) {
            mFormattedTags = new String(in.readString());
            mTags.add(mFormattedTags);
        } else {
            String[] tagsArray = in.readStringArray();
            for (int i = tagsArray.length - 1 ; i >= 0 ; i--) {
                mTags.add(tagsArray[i]);
            }
            mFormattedTags = TextUtils.join(";", mTags);
        }
        switch (in.readInt()) {
            case ATTR_PARCEL_IS_NULL_BUNDLE:
                mBundle = null;
                break;
            case ATTR_PARCEL_IS_VALID_BUNDLE:
                mBundle = new Bundle(in.readBundle());
                break;
            default:
                Log.e(TAG, "Illegal value unmarshalling AudioAttributes, can't initialize bundle");
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AudioAttributes> CREATOR
            = new Parcelable.Creator<AudioAttributes>() {
        /**
         * Rebuilds an AudioAttributes previously stored with writeToParcel().
         * @param p Parcel object to read the AudioAttributes from
         * @return a new AudioAttributes created from the data in the parcel
         */
        public AudioAttributes createFromParcel(Parcel p) {
            return new AudioAttributes(p);
        }
        public AudioAttributes[] newArray(int size) {
            return new AudioAttributes[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioAttributes that = (AudioAttributes) o;

        return ((mContentType == that.mContentType)
                && (mFlags == that.mFlags)
                && (mSource == that.mSource)
                && (mUsage == that.mUsage)
                //mFormattedTags is never null due to assignment in Builder or unmarshalling
                && (mFormattedTags.equals(that.mFormattedTags)));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mContentType, mFlags, mSource, mUsage, mFormattedTags, mBundle);
    }

    @Override
    public String toString () {
        return new String("AudioAttributes:"
                + " usage=" + usageToString()
                + " content=" + contentTypeToString()
                + " flags=0x" + Integer.toHexString(mFlags).toUpperCase()
                + " tags=" + mFormattedTags
                + " bundle=" + (mBundle == null ? "null" : mBundle.toString()));
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        proto.write(AudioAttributesProto.USAGE, mUsage);
        proto.write(AudioAttributesProto.CONTENT_TYPE, mContentType);
        proto.write(AudioAttributesProto.FLAGS, mFlags);
        // mFormattedTags is never null due to assignment in Builder or unmarshalling.
        for (String t : mFormattedTags.split(";")) {
            t = t.trim();
            if (t != "") {
                proto.write(AudioAttributesProto.TAGS, t);
            }
        }
        // TODO: is the data in mBundle useful for debugging?

        proto.end(token);
    }

    /** @hide */
    public String usageToString() {
        return usageToString(mUsage);
    }

    /**
     * Returns the string representation for the usage constant passed as parameter.
     *
     * @param usage one of the {@link AudioAttributes} usage constants
     * @return string representing the {@link AudioAttributes} usage constant passed as a parameter
     *
     * @hide
     */
    @NonNull
    public static String usageToString(@AttributeSdkUsage int usage) {
        switch(usage) {
            case USAGE_UNKNOWN:
                return "USAGE_UNKNOWN";
            case USAGE_MEDIA:
                return "USAGE_MEDIA";
            case USAGE_VOICE_COMMUNICATION:
                return "USAGE_VOICE_COMMUNICATION";
            case USAGE_VOICE_COMMUNICATION_SIGNALLING:
                return "USAGE_VOICE_COMMUNICATION_SIGNALLING";
            case USAGE_ALARM:
                return "USAGE_ALARM";
            case USAGE_NOTIFICATION:
                return "USAGE_NOTIFICATION";
            case USAGE_NOTIFICATION_RINGTONE:
                return "USAGE_NOTIFICATION_RINGTONE";
            case USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
                return "USAGE_NOTIFICATION_COMMUNICATION_REQUEST";
            case USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
                return "USAGE_NOTIFICATION_COMMUNICATION_INSTANT";
            case USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
                return "USAGE_NOTIFICATION_COMMUNICATION_DELAYED";
            case USAGE_NOTIFICATION_EVENT:
                return "USAGE_NOTIFICATION_EVENT";
            case USAGE_ASSISTANCE_ACCESSIBILITY:
                return "USAGE_ASSISTANCE_ACCESSIBILITY";
            case USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
                return "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE";
            case USAGE_ASSISTANCE_SONIFICATION:
                return "USAGE_ASSISTANCE_SONIFICATION";
            case USAGE_GAME:
                return "USAGE_GAME";
            case USAGE_ASSISTANT:
                return "USAGE_ASSISTANT";
            case USAGE_CALL_ASSISTANT:
                return "USAGE_CALL_ASSISTANT";
            case USAGE_EMERGENCY:
                return "USAGE_EMERGENCY";
            case USAGE_SAFETY:
                return "USAGE_SAFETY";
            case USAGE_VEHICLE_STATUS:
                return "USAGE_VEHICLE_STATUS";
            case USAGE_ANNOUNCEMENT:
                return "USAGE_ANNOUNCEMENT";
            default:
                return "unknown usage " + usage;
        }
    }

    /** @hide **/
    @TestApi
    @NonNull
    public static String usageToXsdString(@AttributeUsage int usage) {
        switch (usage) {
            case AudioAttributes.USAGE_UNKNOWN:
                return AudioUsage.AUDIO_USAGE_UNKNOWN.toString();
            case AudioAttributes.USAGE_MEDIA:
                return AudioUsage.AUDIO_USAGE_MEDIA.toString();
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
                return AudioUsage.AUDIO_USAGE_VOICE_COMMUNICATION.toString();
            case AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING:
                return AudioUsage.AUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING.toString();
            case AudioAttributes.USAGE_ALARM:
                return AudioUsage.AUDIO_USAGE_ALARM.toString();
            case AudioAttributes.USAGE_NOTIFICATION:
                return AudioUsage.AUDIO_USAGE_NOTIFICATION.toString();
            case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
                return AudioUsage.AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE.toString();
            case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
                return AudioUsage.AUDIO_USAGE_ASSISTANCE_ACCESSIBILITY.toString();
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
                return AudioUsage.AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE.toString();
            case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION:
                return AudioUsage.AUDIO_USAGE_ASSISTANCE_SONIFICATION.toString();
            case AudioAttributes.USAGE_GAME:
                return AudioUsage.AUDIO_USAGE_GAME.toString();
            case AudioAttributes.USAGE_VIRTUAL_SOURCE:
                return AudioUsage.AUDIO_USAGE_VIRTUAL_SOURCE.toString();
            case AudioAttributes.USAGE_ASSISTANT:
                return AudioUsage.AUDIO_USAGE_ASSISTANT.toString();
            case AudioAttributes.USAGE_CALL_ASSISTANT:
                return AudioUsage.AUDIO_USAGE_CALL_ASSISTANT.toString();
            case AudioAttributes.USAGE_EMERGENCY:
                return AudioUsage.AUDIO_USAGE_EMERGENCY.toString();
            case AudioAttributes.USAGE_SAFETY:
                return AudioUsage.AUDIO_USAGE_SAFETY.toString();
            case AudioAttributes.USAGE_VEHICLE_STATUS:
                return AudioUsage.AUDIO_USAGE_VEHICLE_STATUS.toString();
            case AudioAttributes.USAGE_ANNOUNCEMENT:
                return AudioUsage.AUDIO_USAGE_ANNOUNCEMENT.toString();
            default:
                Log.w(TAG, "Unknown usage value " + usage);
                return AudioUsage.AUDIO_USAGE_UNKNOWN.toString();
        }
    }

    private static final Map<String, Integer> sXsdStringToUsage = new HashMap<>();

    static {
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_UNKNOWN.toString(), USAGE_UNKNOWN);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_UNKNOWN.toString(), USAGE_UNKNOWN);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_MEDIA.toString(), USAGE_MEDIA);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_VOICE_COMMUNICATION.toString(),
                USAGE_VOICE_COMMUNICATION);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING.toString(),
                USAGE_VOICE_COMMUNICATION_SIGNALLING);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_ALARM.toString(), USAGE_ALARM);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_NOTIFICATION.toString(), USAGE_NOTIFICATION);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_NOTIFICATION_TELEPHONY_RINGTONE.toString(),
                USAGE_NOTIFICATION_RINGTONE);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_ASSISTANCE_ACCESSIBILITY.toString(),
                USAGE_ASSISTANCE_ACCESSIBILITY);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE.toString(),
                USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_ASSISTANCE_SONIFICATION.toString(),
                USAGE_ASSISTANCE_SONIFICATION);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_GAME.toString(), USAGE_GAME);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_VIRTUAL_SOURCE.toString(),
                USAGE_VIRTUAL_SOURCE);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_ASSISTANT.toString(), USAGE_ASSISTANT);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_CALL_ASSISTANT.toString(),
                USAGE_CALL_ASSISTANT);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_EMERGENCY.toString(), USAGE_EMERGENCY);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_SAFETY.toString(), USAGE_SAFETY);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_VEHICLE_STATUS.toString(),
                USAGE_VEHICLE_STATUS);
        sXsdStringToUsage.put(AudioUsage.AUDIO_USAGE_ANNOUNCEMENT.toString(), USAGE_ANNOUNCEMENT);
    }

    /** @hide **/
    @TestApi
    public static @AttributeUsage int xsdStringToUsage(@NonNull String xsdUsage) {
        if (sXsdStringToUsage.containsKey(xsdUsage)) {
            return sXsdStringToUsage.get(xsdUsage);
        } else {
            Log.w(TAG, "Usage name not found in AudioUsage enum: " + xsdUsage);
            return USAGE_UNKNOWN;
        }
    }

    /** @hide */
    public String contentTypeToString() {
        switch(mContentType) {
            case CONTENT_TYPE_UNKNOWN:
                return new String("CONTENT_TYPE_UNKNOWN");
            case CONTENT_TYPE_SPEECH: return new String("CONTENT_TYPE_SPEECH");
            case CONTENT_TYPE_MUSIC: return new String("CONTENT_TYPE_MUSIC");
            case CONTENT_TYPE_MOVIE: return new String("CONTENT_TYPE_MOVIE");
            case CONTENT_TYPE_SONIFICATION: return new String("CONTENT_TYPE_SONIFICATION");
            default: return new String("unknown content type " + mContentType);
        }
    }

    private static int usageForStreamType(int streamType) {
        switch(streamType) {
            case AudioSystem.STREAM_VOICE_CALL:
                return USAGE_VOICE_COMMUNICATION;
            case AudioSystem.STREAM_SYSTEM_ENFORCED:
            case AudioSystem.STREAM_SYSTEM:
                return USAGE_ASSISTANCE_SONIFICATION;
            case AudioSystem.STREAM_RING:
                return USAGE_NOTIFICATION_RINGTONE;
            case AudioSystem.STREAM_MUSIC:
                return USAGE_MEDIA;
            case AudioSystem.STREAM_ALARM:
                return USAGE_ALARM;
            case AudioSystem.STREAM_NOTIFICATION:
                return USAGE_NOTIFICATION;
            case AudioSystem.STREAM_BLUETOOTH_SCO:
                return USAGE_VOICE_COMMUNICATION;
            case AudioSystem.STREAM_DTMF:
                return USAGE_VOICE_COMMUNICATION_SIGNALLING;
            case AudioSystem.STREAM_ACCESSIBILITY:
                return USAGE_ASSISTANCE_ACCESSIBILITY;
            case AudioSystem.STREAM_TTS:
            default:
                return USAGE_UNKNOWN;
        }
    }

    /**
     * @param usage one of {@link AttributeSystemUsage},
     *     {@link AttributeSystemUsage#USAGE_CALL_ASSISTANT},
     *     {@link AttributeSystemUsage#USAGE_EMERGENCY},
     *     {@link AttributeSystemUsage#USAGE_SAFETY},
     *     {@link AttributeSystemUsage#USAGE_VEHICLE_STATUS},
     *     {@link AttributeSystemUsage#USAGE_ANNOUNCEMENT}
     * @return boolean indicating if the usage is a system usage or not
     * @hide
     */
    @SystemApi
    public static boolean isSystemUsage(@AttributeSystemUsage int usage) {
        return (usage == USAGE_CALL_ASSISTANT
                || usage == USAGE_EMERGENCY
                || usage == USAGE_SAFETY
                || usage == USAGE_VEHICLE_STATUS
                || usage == USAGE_ANNOUNCEMENT);
    }

    /**
     * Returns the stream type matching this {@code AudioAttributes} instance for volume control.
     * Use this method to derive the stream type needed to configure the volume
     * control slider in an {@link android.app.Activity} with
     * {@link android.app.Activity#setVolumeControlStream(int)} for playback conducted with these
     * attributes.
     * <BR>Do not use this method to set the stream type on an audio player object
     * (e.g. {@link AudioTrack}, {@link MediaPlayer}) as this is deprecated,
     * use {@code AudioAttributes} instead.
     * @return a valid stream type for {@code Activity} or stream volume control that matches
     *     the attributes, or {@link AudioManager#USE_DEFAULT_STREAM_TYPE} if there isn't a direct
     *     match. Note that {@code USE_DEFAULT_STREAM_TYPE} is not a valid value
     *     for {@link AudioManager#setStreamVolume(int, int, int)}.
     */
    public int getVolumeControlStream() {
        return toVolumeStreamType(true /*fromGetVolumeControlStream*/, this);
    }

    /**
     * @hide
     * Only use to get which stream type should be used for volume control, NOT for audio playback
     * (all audio playback APIs are supposed to take AudioAttributes as input parameters)
     * @param aa non-null AudioAttributes.
     * @return a valid stream type for volume control that matches the attributes.
     */
    @UnsupportedAppUsage
    public static int toLegacyStreamType(@NonNull AudioAttributes aa) {
        return toVolumeStreamType(false /*fromGetVolumeControlStream*/, aa);
    }

    private static int toVolumeStreamType(boolean fromGetVolumeControlStream, AudioAttributes aa) {
        // flags to stream type mapping
        if ((aa.getFlags() & FLAG_AUDIBILITY_ENFORCED) == FLAG_AUDIBILITY_ENFORCED) {
            return fromGetVolumeControlStream ?
                    AudioSystem.STREAM_SYSTEM : AudioSystem.STREAM_SYSTEM_ENFORCED;
        }
        if ((aa.getAllFlags() & FLAG_SCO) == FLAG_SCO) {
            return fromGetVolumeControlStream ?
                    AudioSystem.STREAM_VOICE_CALL : AudioSystem.STREAM_BLUETOOTH_SCO;
        }
        if ((aa.getAllFlags() & FLAG_BEACON) == FLAG_BEACON) {
            return fromGetVolumeControlStream ?
                    AudioSystem.STREAM_MUSIC : AudioSystem.STREAM_TTS;
        }

        if (AudioProductStrategy.getAudioProductStrategies().size() > 0) {
            return AudioProductStrategy.getLegacyStreamTypeForStrategyWithAudioAttributes(aa);
        }
        // usage to stream type mapping
        switch (aa.getUsage()) {
            case USAGE_MEDIA:
            case USAGE_GAME:
            case USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
            case USAGE_ASSISTANT:
                return AudioSystem.STREAM_MUSIC;
            case USAGE_ASSISTANCE_SONIFICATION:
                return AudioSystem.STREAM_SYSTEM;
            case USAGE_VOICE_COMMUNICATION:
            case USAGE_CALL_ASSISTANT:
                return AudioSystem.STREAM_VOICE_CALL;
            case USAGE_VOICE_COMMUNICATION_SIGNALLING:
                return fromGetVolumeControlStream ?
                        AudioSystem.STREAM_VOICE_CALL : AudioSystem.STREAM_DTMF;
            case USAGE_ALARM:
                return AudioSystem.STREAM_ALARM;
            case USAGE_NOTIFICATION_RINGTONE:
                return AudioSystem.STREAM_RING;
            case USAGE_NOTIFICATION:
            case USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
            case USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
            case USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
            case USAGE_NOTIFICATION_EVENT:
                return AudioSystem.STREAM_NOTIFICATION;
            case USAGE_ASSISTANCE_ACCESSIBILITY:
                return AudioSystem.STREAM_ACCESSIBILITY;
            case USAGE_EMERGENCY:
            case USAGE_SAFETY:
            case USAGE_VEHICLE_STATUS:
            case USAGE_ANNOUNCEMENT:
            case USAGE_UNKNOWN:
                return AudioSystem.STREAM_MUSIC;
            default:
                if (fromGetVolumeControlStream) {
                    throw new IllegalArgumentException("Unknown usage value " + aa.getUsage() +
                            " in audio attributes");
                } else {
                    return AudioSystem.STREAM_MUSIC;
                }
        }
    }

    /**
     * @hide
     */
    public static int capturePolicyToFlags(@CapturePolicy int capturePolicy, int flags) {
        switch (capturePolicy) {
            case ALLOW_CAPTURE_BY_NONE:
                flags |= FLAG_NO_MEDIA_PROJECTION | FLAG_NO_SYSTEM_CAPTURE;
                break;
            case ALLOW_CAPTURE_BY_SYSTEM:
                flags |= FLAG_NO_MEDIA_PROJECTION;
                flags &= ~FLAG_NO_SYSTEM_CAPTURE;
                break;
            case ALLOW_CAPTURE_BY_ALL:
                flags &= ~FLAG_NO_SYSTEM_CAPTURE & ~FLAG_NO_MEDIA_PROJECTION;
                break;
            default:
                throw new IllegalArgumentException("Unknown allow playback capture policy");
        }
        return flags;
    }

    /** @hide */
    @IntDef({
            USAGE_CALL_ASSISTANT,
            USAGE_EMERGENCY,
            USAGE_SAFETY,
            USAGE_VEHICLE_STATUS,
            USAGE_ANNOUNCEMENT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AttributeSystemUsage {}

    /** @hide */
    @IntDef({
            USAGE_UNKNOWN,
            USAGE_MEDIA,
            USAGE_VOICE_COMMUNICATION,
            USAGE_VOICE_COMMUNICATION_SIGNALLING,
            USAGE_ALARM,
            USAGE_NOTIFICATION,
            USAGE_NOTIFICATION_RINGTONE,
            USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            USAGE_NOTIFICATION_EVENT,
            USAGE_ASSISTANCE_ACCESSIBILITY,
            USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            USAGE_ASSISTANCE_SONIFICATION,
            USAGE_GAME,
            USAGE_ASSISTANT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AttributeSdkUsage {}

    /** @hide */
    @IntDef({
        USAGE_UNKNOWN,
        USAGE_MEDIA,
        USAGE_VOICE_COMMUNICATION,
        USAGE_VOICE_COMMUNICATION_SIGNALLING,
        USAGE_ALARM,
        USAGE_NOTIFICATION,
        USAGE_NOTIFICATION_RINGTONE,
        USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
        USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
        USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
        USAGE_NOTIFICATION_EVENT,
        USAGE_ASSISTANCE_ACCESSIBILITY,
        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        USAGE_ASSISTANCE_SONIFICATION,
        USAGE_GAME,
        USAGE_ASSISTANT,
        USAGE_CALL_ASSISTANT,
        USAGE_EMERGENCY,
        USAGE_SAFETY,
        USAGE_VEHICLE_STATUS,
        USAGE_ANNOUNCEMENT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AttributeUsage {}

    /** @hide */
    @IntDef({
        CONTENT_TYPE_UNKNOWN,
        CONTENT_TYPE_SPEECH,
        CONTENT_TYPE_MUSIC,
        CONTENT_TYPE_MOVIE,
        CONTENT_TYPE_SONIFICATION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AttributeContentType {}
}
