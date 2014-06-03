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
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A class to encapsulate a collection of attributes describing information about an audio
 * player or recorder.
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
    public final static int USAGE_NOTIFICATION_TELEPHONY_RINGTONE = 6;
    /**
     * Usage value to use when the usage is a request to enter/end a
     * communication, such as a VoIP communication or video-conference.
     */
    public final static int USAGE_NOTIFICATION_COMMUNICATION_REQUEST = 7;
    /**
     * Usage value to use when the usage is notification for an "instant"
     * communication such as a chat, or SMS.
     */
    public final static int USAGE_NOTIFICATION_COMMUNICATION_INSTANT = 8;
    /**
     * Usage value to use when the usage is notification for a
     * non-immediate type of communication such as e-mail.
     */
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
     * Flag defining a behavior where the audibility of the sound will be ensured by the system.
     */
    public final static int FLAG_AUDIBILITY_ENFORCED = 0x1 << 0;
    /**
     * @hide
     * Flag defining a behavior where the playback of the sound is ensured without
     * degradation only when going to a secure sink.
     */
    // FIXME not guaranteed yet
    // TODO  add OR to getFlags() when supported and in public API
    public final static int FLAG_SECURE = 0x1 << 1;
    /**
     * @hide
     * Flag to enable when the stream is associated with SCO usage.
     * Internal use only for dealing with legacy STREAM_BLUETOOTH_SCO
     */
    public final static int FLAG_SCO = 0x1 << 2;


    private int mUsage = USAGE_UNKNOWN;
    private int mContentType = CONTENT_TYPE_UNKNOWN;
    private int mFlags = 0x0;
    private HashSet<String> mTags;

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
        return mUsage;
    }

    /**
     * Return the flags.
     * @return a combined mask of all flags
     */
    public int getFlags() {
        // only return the flags that are public
        return (mFlags & (FLAG_AUDIBILITY_ENFORCED));
    }

    /**
     * @hide
     * Return all the flags, even the non-public ones.
     * Internal use only
     * @return a combined mask of all flags
     */
    public int getAllFlags() {
        return mFlags;
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
     * Builder class for {@link AudioAttributes} objects.
     */
    public static class Builder {
        private int mUsage = USAGE_UNKNOWN;
        private int mContentType = CONTENT_TYPE_UNKNOWN;
        private int mFlags = 0x0;
        private HashSet<String> mTags = new HashSet<String>();

        /**
         * Constructs a new Builder with the defaults.
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
            mFlags = aa.mFlags;
            mTags = (HashSet<String>) aa.mTags.clone();
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
            aa.mUsage = mUsage;
            aa.mFlags = mFlags;
            aa.mTags = (HashSet<String>) mTags.clone();
            return aa;
        }

        /**
         * Sets the attribute describing what is the intended use of the the audio signal,
         * such as alarm or ringtone.
         * @param usage one of {@link AudioAttributes#USAGE_UNKNOWN},
         *     {@link AudioAttributes#USAGE_MEDIA},
         *     {@link AudioAttributes#USAGE_VOICE_COMMUNICATION},
         *     {@link AudioAttributes#USAGE_VOICE_COMMUNICATION_SIGNALLING},
         *     {@link AudioAttributes#USAGE_ALARM}, {@link AudioAttributes#USAGE_NOTIFICATION},
         *     {@link AudioAttributes#USAGE_NOTIFICATION_TELEPHONY_RINGTONE},
         *     {@link AudioAttributes#USAGE_NOTIFICATION_COMMUNICATION_REQUEST},
         *     {@link AudioAttributes#USAGE_NOTIFICATION_COMMUNICATION_INSTANT},
         *     {@link AudioAttributes#USAGE_NOTIFICATION_COMMUNICATION_DELAYED},
         *     {@link AudioAttributes#USAGE_NOTIFICATION_EVENT},
         *     {@link AudioAttributes#USAGE_ASSISTANCE_ACCESSIBILITY},
         *     {@link AudioAttributes#USAGE_ASSISTANCE_NAVIGATION_GUIDANCE},
         *     {@link AudioAttributes#USAGE_ASSISTANCE_SONIFICATION},
         *     {@link AudioAttributes#USAGE_GAME}.
         * @return the same Builder instance.
         */
        public Builder setUsage(@AttributeUsage int usage) {
            switch (usage) {
                case USAGE_UNKNOWN:
                case USAGE_MEDIA:
                case USAGE_VOICE_COMMUNICATION:
                case USAGE_VOICE_COMMUNICATION_SIGNALLING:
                case USAGE_ALARM:
                case USAGE_NOTIFICATION:
                case USAGE_NOTIFICATION_TELEPHONY_RINGTONE:
                case USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
                case USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
                case USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
                case USAGE_NOTIFICATION_EVENT:
                case USAGE_ASSISTANCE_ACCESSIBILITY:
                case USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
                case USAGE_ASSISTANCE_SONIFICATION:
                case USAGE_GAME:
                     mUsage = usage;
                     break;
                default:
                     mUsage = USAGE_UNKNOWN;
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
                     mUsage = CONTENT_TYPE_UNKNOWN;
            }
            return this;
        }

        /**
         * Sets the combination of flags.
         * @param flags the {@link AudioAttributes#FLAG_AUDIBILITY_ENFORCED} flag.
         * @return the same Builder instance.
         */
        public Builder setFlags(int flags) {
            flags &= (AudioAttributes.FLAG_AUDIBILITY_ENFORCED | AudioAttributes.FLAG_SCO
                    | AudioAttributes.FLAG_SECURE);
            mFlags |= flags;
            return this;
        }

        /**
         * @hide
         * Add a custom tag stored as a string
         * @param tag
         * @return the same Builder instance.
         */
        public Builder addTag(String tag) {
            mTags.add(tag);
            return this;
        }

        /**
         * Adds attributes inferred from the legacy stream types.
         * @param streamType one of {@link AudioManager#STREAM_VOICE_CALL},
         *   {@link AudioManager#STREAM_SYSTEM}, {@link AudioManager#STREAM_RING},
         *   {@link AudioManager#STREAM_MUSIC}, {@link AudioManager#STREAM_ALARM},
         *    or {@link AudioManager#STREAM_NOTIFICATION}.
         * @return the same Builder instance.
         */
        public Builder setLegacyStreamType(int streamType) {
            return setInternalLegacyStreamType(streamType);
        }

        /**
         * @hide
         * For internal framework use only, enables building from hidden stream types.
         * @param streamType
         * @return the same Builder instance.
         */
        public Builder setInternalLegacyStreamType(int streamType) {
            switch(streamType) {
                case AudioSystem.STREAM_VOICE_CALL:
                    mContentType = CONTENT_TYPE_SPEECH;
                    mUsage = USAGE_VOICE_COMMUNICATION;
                    break;
                case AudioSystem.STREAM_SYSTEM_ENFORCED:
                    mFlags |= FLAG_AUDIBILITY_ENFORCED;
                    // intended fall through, attributes in common with STREAM_SYSTEM
                case AudioSystem.STREAM_SYSTEM:
                    mContentType = CONTENT_TYPE_SONIFICATION;
                    mUsage = USAGE_ASSISTANCE_SONIFICATION;
                    break;
                case AudioSystem.STREAM_RING:
                    mContentType = CONTENT_TYPE_SONIFICATION;
                    mUsage = USAGE_NOTIFICATION_TELEPHONY_RINGTONE;
                    break;
                case AudioSystem.STREAM_MUSIC:
                    mContentType = CONTENT_TYPE_MUSIC;
                    mUsage = USAGE_MEDIA;
                    break;
                case AudioSystem.STREAM_ALARM:
                    mContentType = CONTENT_TYPE_SONIFICATION;
                    mUsage = USAGE_ALARM;
                    break;
                case AudioSystem.STREAM_NOTIFICATION:
                    mContentType = CONTENT_TYPE_SONIFICATION;
                    mUsage = USAGE_NOTIFICATION;
                    break;
                case AudioSystem.STREAM_BLUETOOTH_SCO:
                    mContentType = CONTENT_TYPE_SPEECH;
                    mUsage = USAGE_VOICE_COMMUNICATION;
                    mFlags |= FLAG_SCO;
                    break;
                case AudioSystem.STREAM_DTMF:
                    mContentType = CONTENT_TYPE_SONIFICATION;
                    mUsage = USAGE_VOICE_COMMUNICATION_SIGNALLING;
                    break;
                case AudioSystem.STREAM_TTS:
                    mContentType = CONTENT_TYPE_SPEECH;
                    mUsage = USAGE_ASSISTANCE_ACCESSIBILITY;
                    break;
                default:
                    Log.e(TAG, "Invalid stream type " + streamType + " in for AudioAttributes");
            }
            return this;
        }
    };

    /** @hide */
    @Override
    public String toString () {
        return new String("AudioAttributes:"
                + " usage=" + mUsage
                + " content=" + mContentType
                + " flags=0x" + Integer.toHexString(mFlags)
                + " tags=" + mTags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUsage);
        dest.writeInt(mContentType);
        dest.writeInt(mFlags);
        String[] tagsArray = new String[mTags.size()];
        mTags.toArray(tagsArray);
        dest.writeStringArray(tagsArray);
    }

    private AudioAttributes(Parcel in) {
        mUsage = in.readInt();
        mContentType = in.readInt();
        mFlags = in.readInt();
        mTags = new HashSet<String>();
        String[] tagsArray = in.readStringArray();
        for (int i = tagsArray.length - 1 ; i >= 0 ; i--) {
            mTags.add(tagsArray[i]);
        }
    }

    /** @hide */
    public static final Parcelable.Creator<AudioAttributes> CREATOR
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


    /** @hide */
    @IntDef({
        USAGE_UNKNOWN,
        USAGE_MEDIA,
        USAGE_VOICE_COMMUNICATION,
        USAGE_VOICE_COMMUNICATION_SIGNALLING,
        USAGE_ALARM,
        USAGE_NOTIFICATION,
        USAGE_NOTIFICATION_TELEPHONY_RINGTONE,
        USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
        USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
        USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
        USAGE_NOTIFICATION_EVENT,
        USAGE_ASSISTANCE_ACCESSIBILITY,
        USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        USAGE_ASSISTANCE_SONIFICATION,
        USAGE_GAME
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
