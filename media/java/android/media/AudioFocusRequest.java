/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.os.Handler;
import android.os.Looper;

/**
 * A class to encapsulate information about an audio focus request.
 * An {@code AudioFocusRequest} instance is built by {@link Builder}, and is used to
 * request and abandon audio focus, respectively
 * with {@link AudioManager#requestAudioFocus(AudioFocusRequest)} and
 * {@link AudioManager#abandonAudioFocusRequest(AudioFocusRequest)}.
 * <p>In the context of describing audio focus, the term "ducking" is used. It describes a temporary
 * lowering of the audio level of an application in response to another application playing audio
 * concurrently. An example is during the playback of driving directions,
 * a user listening to music expects the music to "duck" during the playback of the message
 * announcing directions.
 */
// TODO use this class to provide more documentation about audio focus and the new behaviors
//      describe up to N, and after.
public final class AudioFocusRequest {

    // default attributes for the request when not specified
    private final static AudioAttributes FOCUS_DEFAULT_ATTR = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).build();

    private final OnAudioFocusChangeListener mFocusListener; // may be null
    private final Handler mListenerHandler;                  // may be null
    private final AudioAttributes mAttr;                     // never null
    private final int mFocusGain;
    private final int mFlags;

    //TODO implement use of optional handler
    private AudioFocusRequest(OnAudioFocusChangeListener listener, Handler handler,
            AudioAttributes attr, int focusGain, int flags) {
        mFocusListener = listener;
        mListenerHandler = handler;
        mFocusGain = focusGain;
        mAttr = attr;
        mFlags = flags;
    }

    /**
     * @hide
     * Checks whether a focus gain constant is a valid value for an audio focus request.
     * @param focusGain value to check
     * @return true if focusGain is a valid value for an audio focus request.
     */
    final static boolean isValidFocusGain(int focusGain) {
        switch (focusGain) {
            case AudioManager.AUDIOFOCUS_GAIN:
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns the focus change listener set for this {@code AudioFocusRequest}.
     * @return null if no {@link AudioManager.OnAudioFocusChangeListener} was set.
     */
    public @Nullable OnAudioFocusChangeListener getOnAudioFocusChangeListener() {
        return mFocusListener;
    }

    /**
     * Returns the {@link Handler} to be used for the focus change listener.
     * @return the same {@code Handler} set in.
     *   {@link Builder#setOnAudioFocusChangeListener(OnAudioFocusChangeListener, Handler)}, or null
     *   if no listener was set.
     */
    public @Nullable Handler getOnAudioFocusChangeListenerHandler() {
        return mListenerHandler;
    }

    /**
     * Returns the {@link AudioAttributes} set for this {@code AudioFocusRequest}, or the default
     * attributes if none were set.
     * @return non-null {@link AudioAttributes}.
     */
    public @NonNull AudioAttributes getAudioAttributes() {
        return mAttr;
    }

    /**
     * Returns the type of audio focus request configured for this {@code AudioFocusRequest}.
     * @return one of {@link AudioManager#AUDIOFOCUS_GAIN},
     * {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT},
     * {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK}, and
     * {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE}.
     */
    public int getFocusGain() {
        return mFocusGain;
    }

    /**
     * Returns whether the application that would use this {@code AudioFocusRequest} would pause
     * when it is requested to duck.
     * @return the duck/pause behavior.
     */
    public boolean willPauseWhenDucked() {
        return (mFlags & AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS)
                == AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS;
    }

    /**
     * Returns whether the application that would use this {@code AudioFocusRequest} supports
     * a focus gain granted after a temporary request failure.
     * @return whether delayed focus gain is supported.
     */
    public boolean acceptsDelayedFocusGain() {
        return (mFlags & AudioManager.AUDIOFOCUS_FLAG_DELAY_OK)
                == AudioManager.AUDIOFOCUS_FLAG_DELAY_OK;
    }

    int getFlags() {
        return mFlags;
    }

    /**
     * Builder class for {@link AudioFocusRequest} objects.
     * <p> Here is an example where {@code Builder} is used to define the
     * {@link AudioFocusRequest} for requesting audio focus:
     *
     * <pre class="prettyprint">
     * mAudioManager = (AudioManager) Context.getSystemService(Context.AUDIO_SERVICE);
     * mPlaybackAttributes = new AudioAttributes.Builder()
     *         .setUsage(AudioAttributes.USAGE_GAME)
     *         .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
     *         .build();
     * mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
     *         .setAudioAttributes(mPlaybackAttributes)
     *         .setAcceptsDelayedFocusGain(true)
     *         .setOnAudioFocusChangeListener(mMyFocusListener, mMyHandler)
     *         .build();
     * mMediaPlayer = new MediaPlayer();
     *  ...
     * mMediaPlayer.setAudioAttributes(mPlaybackAttributes);
     *  ...
     * mAudioManager.requestAudioFocus(mFocusRequest);
     *  ...
     * mAudioManager.abandonAudioFocusRequest(mFocusRequest);
     * </pre>
     *
     */
    public static final class Builder {
        private OnAudioFocusChangeListener mFocusListener;
        private Handler mListenerHandler;
        private AudioAttributes mAttr = FOCUS_DEFAULT_ATTR;
        private int mFocusGain;
        private boolean mPausesOnDuck = false;
        private boolean mDelayedFocus = false;

        /**
         * Constructs a new {@code Builder}, and specifies how audio focus
         * will be requested. Valid values for focus requests are
         * {@link AudioManager#AUDIOFOCUS_GAIN}, {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT},
         * {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK}, and
         * {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE}.
         * <p>By default there is no focus change listener, and the <code>AudioAttributes</code>
         * have a usage of {@link AudioAttributes#USAGE_MEDIA}.
         * @param focusGain the type of audio focus gain that will be requested
         * @throws IllegalArgumentException thrown when an invalid focus gain type is used
         */
        public Builder(int focusGain) {
            setFocusGain(focusGain);
        }

        /**
         * Constructs a new {@code Builder} with all the properties of the {@code AudioFocusRequest}
         * passed as parameter.
         * Use this method when you want a new request to differ only by some properties.
         * @param requestToCopy the non-null {@code AudioFocusRequest} to build a duplicate from.
         * @throws IllegalArgumentException thrown when a null {@code AudioFocusRequest} is used.
         */
        public Builder(@NonNull AudioFocusRequest requestToCopy) {
            if (requestToCopy == null) {
                throw new IllegalArgumentException("Illegal null AudioFocusRequest");
            }
            mAttr = requestToCopy.mAttr;
            mFocusListener = requestToCopy.mFocusListener;
            mListenerHandler = requestToCopy.mListenerHandler;
            mFocusGain = requestToCopy.mFocusGain;
            mPausesOnDuck = requestToCopy.willPauseWhenDucked();
            mDelayedFocus = requestToCopy.acceptsDelayedFocusGain();
        }

        /**
         * Sets the type of focus gain that will be requested.
         * Use this method to replace the focus gain when building a request by modifying an
         * existing {@code AudioFocusRequest} instance.
         * @param focusGain the type of audio focus gain that will be requested.
         * @return this {@code Builder} instance
         * @throws IllegalArgumentException thrown when an invalid focus gain type is used
         */
        public @NonNull Builder setFocusGain(int focusGain) {
            if (!isValidFocusGain(focusGain)) {
                throw new IllegalArgumentException("Illegal audio focus gain type " + focusGain);
            }
            mFocusGain = focusGain;
            return this;
        }

        /**
         * Sets the listener called when audio focus changes after being requested with
         *   {@link AudioManager#requestAudioFocus(AudioFocusRequest)}, and until being abandoned
         *   with {@link AudioManager#abandonAudioFocusRequest(AudioFocusRequest)}.
         *   Note that only focus changes (gains and losses) affecting the focus owner are reported,
         *   not gains and losses of other focus requesters in the system.
         * @param listener the listener receiving the focus change notifications.
         * @param handler the {@link Handler} for the thread on which to execute
         *   the notifications. If {@code null}, the {@code Handler} associated with the main
         *   {@link Looper} will be used.
         * @return this {@code Builder} instance.
         * @throws IllegalArgumentException thrown when a non-null handler is used with a null
         *   listener.
         */
        public @NonNull Builder setOnAudioFocusChangeListener(
                @Nullable OnAudioFocusChangeListener listener, @Nullable Handler handler) {
            if (listener == null && handler != null) {
                throw new IllegalArgumentException(
                        "Illegal non-null handler without a focus listener");
            }
            mFocusListener = listener;
            mListenerHandler = handler;
            return this;
        }

        /**
         * Sets the {@link AudioAttributes} to be associated with the focus request, and which
         * describe the use case describing why focus is requested.
         * As the focus requests typically precede audio playback, this information is used on
         * certain platforms to declare the subsequent playback use case. It is therefore good
         * practice to use in this method the same {@code AudioAttributes} as used for
         * playback, see for example {@link MediaPlayer#setAudioAttributes(AudioAttributes)} in
         * {@code MediaPlayer} or {@link AudioTrack.Builder#setAudioAttributes(AudioAttributes)}
         * in {@code AudioTrack}.
         * @param attributes the {@link AudioAttributes} for the focus request.
         * @return this {@code Builder} instance.
         * @throws IllegalArgumentException thrown when using null for the attributes.
         */
        public @NonNull Builder setAudioAttributes(@NonNull AudioAttributes attributes) {
            if (attributes == null) {
                throw new IllegalArgumentException("Illegal null AudioAttributes");
            }
            mAttr = attributes;
            return this;
        }

        /**
         * Declare the intended behavior of the application with regards to audio ducking.
         * See more details in the {@link AudioFocusRequest} class documentation.
         * @param pauseOnDuck use {@code true} if the application intends to pause audio playback
         *    when losing focus with {@link AudioManager#AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}.
         *    If {@code true}, note that you must also set a focus listener to receive such an
         *    event, with
         *    {@link #setOnAudioFocusChangeListener(OnAudioFocusChangeListener, Handler)}.
         * @return this {@code Builder} instance.
         */
        public @NonNull Builder setWillPauseWhenDucked(boolean pauseOnDuck) {
            mPausesOnDuck = pauseOnDuck;
            return this;
        }

        /**
         * Marks this focus request as compatible with delayed focus.
         * See more details about delayed focus in the {@link AudioFocusRequest} class
         * documentation.
         * @param acceptsDelayedFocusGain use {@code true} if the application supports delayed
         *    focus. If {@code true}, note that you must also set a focus listener to be notified
         *    of delayed focus gain, with
         *    {@link #setOnAudioFocusChangeListener(OnAudioFocusChangeListener, Handler)}.
         * @return this {@code Builder} instance
         */
        public @NonNull Builder setAcceptsDelayedFocusGain(boolean acceptsDelayedFocusGain) {
            mDelayedFocus = acceptsDelayedFocusGain;
            return this;
        }

        /**
         * Builds a new {@code AudioFocusRequest} instance combining all the information gathered
         * by this {@code Builder}'s configuration methods.
         * @return the {@code AudioFocusRequest} instance qualified by all the properties set
         *   on this {@code Builder}.
         * @throws IllegalArgumentException thrown when focus request is set to accept delayed
         *    focus, or to pause on duck, but no focus change listener was set.
         */
        public AudioFocusRequest build() {
            if ((mDelayedFocus || mPausesOnDuck) && (mFocusListener == null)) {
                throw new IllegalArgumentException(
                        "Can't use delayed focus or pause on duck without a listener");
            }
            final int flags = 0
                    | (mDelayedFocus ? AudioManager.AUDIOFOCUS_FLAG_DELAY_OK : 0)
                    | (mPausesOnDuck ? AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS : 0);
            return new AudioFocusRequest(mFocusListener, mListenerHandler,
                    mAttr, mFocusGain, flags);
        }
    }
}
