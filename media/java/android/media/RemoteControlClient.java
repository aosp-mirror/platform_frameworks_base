/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.annotation.UnsupportedAppUsage;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.session.MediaSession;
import android.media.session.MediaSessionLegacyHelper;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * RemoteControlClient enables exposing information meant to be consumed by remote controls
 * capable of displaying metadata, artwork and media transport control buttons.
 *
 * <p>A remote control client object is associated with a media button event receiver. This
 * event receiver must have been previously registered with
 * {@link AudioManager#registerMediaButtonEventReceiver(ComponentName)} before the
 * RemoteControlClient can be registered through
 * {@link AudioManager#registerRemoteControlClient(RemoteControlClient)}.
 *
 * <p>Here is an example of creating a RemoteControlClient instance after registering a media
 * button event receiver:
 * <pre>ComponentName myEventReceiver = new ComponentName(getPackageName(), MyRemoteControlEventReceiver.class.getName());
 * AudioManager myAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
 * myAudioManager.registerMediaButtonEventReceiver(myEventReceiver);
 * // build the PendingIntent for the remote control client
 * Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
 * mediaButtonIntent.setComponent(myEventReceiver);
 * PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, mediaButtonIntent, 0);
 * // create and register the remote control client
 * RemoteControlClient myRemoteControlClient = new RemoteControlClient(mediaPendingIntent);
 * myAudioManager.registerRemoteControlClient(myRemoteControlClient);</pre>
 *
 * @deprecated Use {@link MediaSession} instead.
 */
@Deprecated public class RemoteControlClient
{
    private final static String TAG = "RemoteControlClient";
    private final static boolean DEBUG = false;

    /**
     * Playback state of a RemoteControlClient which is stopped.
     *
     * @see #setPlaybackState(int)
     */
    public final static int PLAYSTATE_STOPPED            = 1;
    /**
     * Playback state of a RemoteControlClient which is paused.
     *
     * @see #setPlaybackState(int)
     */
    public final static int PLAYSTATE_PAUSED             = 2;
    /**
     * Playback state of a RemoteControlClient which is playing media.
     *
     * @see #setPlaybackState(int)
     */
    public final static int PLAYSTATE_PLAYING            = 3;
    /**
     * Playback state of a RemoteControlClient which is fast forwarding in the media
     *    it is currently playing.
     *
     * @see #setPlaybackState(int)
     */
    public final static int PLAYSTATE_FAST_FORWARDING    = 4;
    /**
     * Playback state of a RemoteControlClient which is fast rewinding in the media
     *    it is currently playing.
     *
     * @see #setPlaybackState(int)
     */
    public final static int PLAYSTATE_REWINDING          = 5;
    /**
     * Playback state of a RemoteControlClient which is skipping to the next
     *    logical chapter (such as a song in a playlist) in the media it is currently playing.
     *
     * @see #setPlaybackState(int)
     */
    public final static int PLAYSTATE_SKIPPING_FORWARDS  = 6;
    /**
     * Playback state of a RemoteControlClient which is skipping back to the previous
     *    logical chapter (such as a song in a playlist) in the media it is currently playing.
     *
     * @see #setPlaybackState(int)
     */
    public final static int PLAYSTATE_SKIPPING_BACKWARDS = 7;
    /**
     * Playback state of a RemoteControlClient which is buffering data to play before it can
     *    start or resume playback.
     *
     * @see #setPlaybackState(int)
     */
    public final static int PLAYSTATE_BUFFERING          = 8;
    /**
     * Playback state of a RemoteControlClient which cannot perform any playback related
     *    operation because of an internal error. Examples of such situations are no network
     *    connectivity when attempting to stream data from a server, or expired user credentials
     *    when trying to play subscription-based content.
     *
     * @see #setPlaybackState(int)
     */
    public final static int PLAYSTATE_ERROR              = 9;
    /**
     * @hide
     * The value of a playback state when none has been declared.
     * Intentionally hidden as an application shouldn't set such a playback state value.
     */
    public final static int PLAYSTATE_NONE               = 0;

    /**
     * @hide
     * The default playback type, "local", indicating the presentation of the media is happening on
     * the same device (e.g. a phone, a tablet) as where it is controlled from.
     */
    public final static int PLAYBACK_TYPE_LOCAL = 0;
    /**
     * @hide
     * A playback type indicating the presentation of the media is happening on
     * a different device (i.e. the remote device) than where it is controlled from.
     */
    public final static int PLAYBACK_TYPE_REMOTE = 1;
    private final static int PLAYBACK_TYPE_MIN = PLAYBACK_TYPE_LOCAL;
    private final static int PLAYBACK_TYPE_MAX = PLAYBACK_TYPE_REMOTE;
    /**
     * @hide
     * Playback information indicating the playback volume is fixed, i.e. it cannot be controlled
     * from this object. An example of fixed playback volume is a remote player, playing over HDMI
     * where the user prefer to control the volume on the HDMI sink, rather than attenuate at the
     * source.
     * @see #PLAYBACKINFO_VOLUME_HANDLING.
     */
    public final static int PLAYBACK_VOLUME_FIXED = 0;
    /**
     * @hide
     * Playback information indicating the playback volume is variable and can be controlled from
     * this object.
     * @see #PLAYBACKINFO_VOLUME_HANDLING.
     */
    public final static int PLAYBACK_VOLUME_VARIABLE = 1;
    /**
     * @hide (to be un-hidden)
     * The playback information value indicating the value of a given information type is invalid.
     * @see #PLAYBACKINFO_VOLUME_HANDLING.
     */
    public final static int PLAYBACKINFO_INVALID_VALUE = Integer.MIN_VALUE;

    /**
     * @hide
     * An unknown or invalid playback position value.
     */
    public final static long PLAYBACK_POSITION_INVALID = -1;
    /**
     * @hide
     * An invalid playback position value associated with the use of {@link #setPlaybackState(int)}
     * used to indicate that playback position will remain unknown.
     */
    public final static long PLAYBACK_POSITION_ALWAYS_UNKNOWN = 0x8019771980198300L;
    /**
     * @hide
     * The default playback speed, 1x.
     */
    public final static float PLAYBACK_SPEED_1X = 1.0f;

    //==========================================
    // Public keys for playback information
    /**
     * @hide
     * Playback information that defines the type of playback associated with this
     * RemoteControlClient. See {@link #PLAYBACK_TYPE_LOCAL} and {@link #PLAYBACK_TYPE_REMOTE}.
     */
    public final static int PLAYBACKINFO_PLAYBACK_TYPE = 1;
    /**
     * @hide
     * Playback information that defines at what volume the playback associated with this
     * RemoteControlClient is performed. This information is only used when the playback type is not
     * local (see {@link #PLAYBACKINFO_PLAYBACK_TYPE}).
     */
    public final static int PLAYBACKINFO_VOLUME = 2;
    /**
     * @hide
     * Playback information that defines the maximum volume volume value that is supported
     * by the playback associated with this RemoteControlClient. This information is only used
     * when the playback type is not local (see {@link #PLAYBACKINFO_PLAYBACK_TYPE}).
     */
    public final static int PLAYBACKINFO_VOLUME_MAX = 3;
    /**
     * @hide
     * Playback information that defines how volume is handled for the presentation of the media.
     * @see #PLAYBACK_VOLUME_FIXED
     * @see #PLAYBACK_VOLUME_VARIABLE
     */
    public final static int PLAYBACKINFO_VOLUME_HANDLING = 4;
    /**
     * @hide
     * Playback information that defines over what stream type the media is presented.
     */
    public final static int PLAYBACKINFO_USES_STREAM = 5;

    //==========================================
    // Public flags for the supported transport control capabilities
    /**
     * Flag indicating a RemoteControlClient makes use of the "previous" media key.
     *
     * @see #setTransportControlFlags(int)
     * @see android.view.KeyEvent#KEYCODE_MEDIA_PREVIOUS
     */
    public final static int FLAG_KEY_MEDIA_PREVIOUS = 1 << 0;
    /**
     * Flag indicating a RemoteControlClient makes use of the "rewind" media key.
     *
     * @see #setTransportControlFlags(int)
     * @see android.view.KeyEvent#KEYCODE_MEDIA_REWIND
     */
    public final static int FLAG_KEY_MEDIA_REWIND = 1 << 1;
    /**
     * Flag indicating a RemoteControlClient makes use of the "play" media key.
     *
     * @see #setTransportControlFlags(int)
     * @see android.view.KeyEvent#KEYCODE_MEDIA_PLAY
     */
    public final static int FLAG_KEY_MEDIA_PLAY = 1 << 2;
    /**
     * Flag indicating a RemoteControlClient makes use of the "play/pause" media key.
     *
     * @see #setTransportControlFlags(int)
     * @see android.view.KeyEvent#KEYCODE_MEDIA_PLAY_PAUSE
     */
    public final static int FLAG_KEY_MEDIA_PLAY_PAUSE = 1 << 3;
    /**
     * Flag indicating a RemoteControlClient makes use of the "pause" media key.
     *
     * @see #setTransportControlFlags(int)
     * @see android.view.KeyEvent#KEYCODE_MEDIA_PAUSE
     */
    public final static int FLAG_KEY_MEDIA_PAUSE = 1 << 4;
    /**
     * Flag indicating a RemoteControlClient makes use of the "stop" media key.
     *
     * @see #setTransportControlFlags(int)
     * @see android.view.KeyEvent#KEYCODE_MEDIA_STOP
     */
    public final static int FLAG_KEY_MEDIA_STOP = 1 << 5;
    /**
     * Flag indicating a RemoteControlClient makes use of the "fast forward" media key.
     *
     * @see #setTransportControlFlags(int)
     * @see android.view.KeyEvent#KEYCODE_MEDIA_FAST_FORWARD
     */
    public final static int FLAG_KEY_MEDIA_FAST_FORWARD = 1 << 6;
    /**
     * Flag indicating a RemoteControlClient makes use of the "next" media key.
     *
     * @see #setTransportControlFlags(int)
     * @see android.view.KeyEvent#KEYCODE_MEDIA_NEXT
     */
    public final static int FLAG_KEY_MEDIA_NEXT = 1 << 7;
    /**
     * Flag indicating a RemoteControlClient can receive changes in the media playback position
     * through the {@link OnPlaybackPositionUpdateListener} interface. This flag must be set
     * in order for components that display the RemoteControlClient information, to display and
     * let the user control media playback position.
     * @see #setTransportControlFlags(int)
     * @see #setOnGetPlaybackPositionListener(OnGetPlaybackPositionListener)
     * @see #setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener)
     */
    public final static int FLAG_KEY_MEDIA_POSITION_UPDATE = 1 << 8;
    /**
     * Flag indicating a RemoteControlClient supports ratings.
     * This flag must be set in order for components that display the RemoteControlClient
     * information, to display ratings information, and, if ratings are declared editable
     * (by calling {@link MediaMetadataEditor#addEditableKey(int)} with the
     * {@link MediaMetadataEditor#RATING_KEY_BY_USER} key), it will enable the user to rate
     * the media, with values being received through the interface set with
     * {@link #setMetadataUpdateListener(OnMetadataUpdateListener)}.
     * @see #setTransportControlFlags(int)
     */
    public final static int FLAG_KEY_MEDIA_RATING = 1 << 9;

    /**
     * @hide
     * The flags for when no media keys are declared supported.
     * Intentionally hidden as an application shouldn't set the transport control flags
     *     to this value.
     */
    public final static int FLAGS_KEY_MEDIA_NONE = 0;

    /**
     * @hide
     * Flag used to signal some type of metadata exposed by the RemoteControlClient is requested.
     */
    public final static int FLAG_INFORMATION_REQUEST_METADATA = 1 << 0;
    /**
     * @hide
     * Flag used to signal that the transport control buttons supported by the
     *     RemoteControlClient are requested.
     * This can for instance happen when playback is at the end of a playlist, and the "next"
     * operation is not supported anymore.
     */
    public final static int FLAG_INFORMATION_REQUEST_KEY_MEDIA = 1 << 1;
    /**
     * @hide
     * Flag used to signal that the playback state of the RemoteControlClient is requested.
     */
    public final static int FLAG_INFORMATION_REQUEST_PLAYSTATE = 1 << 2;
    /**
     * @hide
     * Flag used to signal that the album art for the RemoteControlClient is requested.
     */
    public final static int FLAG_INFORMATION_REQUEST_ALBUM_ART = 1 << 3;

    private MediaSession mSession;

    /**
     * Class constructor.
     * @param mediaButtonIntent The intent that will be sent for the media button events sent
     *     by remote controls.
     *     This intent needs to have been constructed with the {@link Intent#ACTION_MEDIA_BUTTON}
     *     action, and have a component that will handle the intent (set with
     *     {@link Intent#setComponent(ComponentName)}) registered with
     *     {@link AudioManager#registerMediaButtonEventReceiver(ComponentName)}
     *     before this new RemoteControlClient can itself be registered with
     *     {@link AudioManager#registerRemoteControlClient(RemoteControlClient)}.
     * @see AudioManager#registerMediaButtonEventReceiver(ComponentName)
     * @see AudioManager#registerRemoteControlClient(RemoteControlClient)
     */
    public RemoteControlClient(PendingIntent mediaButtonIntent) {
        mRcMediaIntent = mediaButtonIntent;
    }

    /**
     * Class constructor for a remote control client whose internal event handling
     * happens on a user-provided Looper.
     * @param mediaButtonIntent The intent that will be sent for the media button events sent
     *     by remote controls.
     *     This intent needs to have been constructed with the {@link Intent#ACTION_MEDIA_BUTTON}
     *     action, and have a component that will handle the intent (set with
     *     {@link Intent#setComponent(ComponentName)}) registered with
     *     {@link AudioManager#registerMediaButtonEventReceiver(ComponentName)}
     *     before this new RemoteControlClient can itself be registered with
     *     {@link AudioManager#registerRemoteControlClient(RemoteControlClient)}.
     * @param looper The Looper running the event loop.
     * @see AudioManager#registerMediaButtonEventReceiver(ComponentName)
     * @see AudioManager#registerRemoteControlClient(RemoteControlClient)
     */
    public RemoteControlClient(PendingIntent mediaButtonIntent, Looper looper) {
        mRcMediaIntent = mediaButtonIntent;
    }

    /**
     * @hide
     */
    public void registerWithSession(MediaSessionLegacyHelper helper) {
        helper.addRccListener(mRcMediaIntent, mTransportListener);
        mSession = helper.getSession(mRcMediaIntent);
        setTransportControlFlags(mTransportControlFlags);
    }

    /**
     * @hide
     */
    public void unregisterWithSession(MediaSessionLegacyHelper helper) {
        helper.removeRccListener(mRcMediaIntent);
        mSession = null;
    }

    /**
     * Get a {@link MediaSession} associated with this RCC. It will only have a
     * session while it is registered with
     * {@link AudioManager#registerRemoteControlClient}. The session returned
     * should not be modified directly by the application but may be used with
     * other APIs that require a session.
     *
     * @return A media session object or null.
     */
    public MediaSession getMediaSession() {
        return mSession;
    }

    /**
     * Class used to modify metadata in a {@link RemoteControlClient} object.
     * Use {@link RemoteControlClient#editMetadata(boolean)} to create an instance of an editor,
     * on which you set the metadata for the RemoteControlClient instance. Once all the information
     * has been set, use {@link #apply()} to make it the new metadata that should be displayed
     * for the associated client. Once the metadata has been "applied", you cannot reuse this
     * instance of the MetadataEditor.
     *
     * @deprecated Use {@link MediaMetadata} and {@link MediaSession} instead.
     */
    @Deprecated public class MetadataEditor extends MediaMetadataEditor {

        // only use RemoteControlClient.editMetadata() to get a MetadataEditor instance
        private MetadataEditor() { }
        /**
         * @hide
         */
        public Object clone() throws CloneNotSupportedException {
            throw new CloneNotSupportedException();
        }

        /**
         * The metadata key for the content artwork / album art.
         */
        public final static int BITMAP_KEY_ARTWORK = 100;

        /**
         * @hide
         * TODO(jmtrivi) have lockscreen move to the new key name and remove
         */
        public final static int METADATA_KEY_ARTWORK = BITMAP_KEY_ARTWORK;

        /**
         * Adds textual information to be displayed.
         * Note that none of the information added after {@link #apply()} has been called,
         * will be displayed.
         * @param key The identifier of a the metadata field to set. Valid values are
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ALBUM},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ALBUMARTIST},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_TITLE},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ARTIST},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_AUTHOR},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_COMPILATION},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_COMPOSER},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DATE},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_GENRE},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_TITLE},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_WRITER}.
         * @param value The text for the given key, or {@code null} to signify there is no valid
         *      information for the field.
         * @return Returns a reference to the same MetadataEditor object, so you can chain put
         *      calls together.
         */
        public synchronized MetadataEditor putString(int key, String value)
                throws IllegalArgumentException {
            super.putString(key, value);
            if (mMetadataBuilder != null) {
                // MediaMetadata supports all the same fields as MetadataEditor
                String metadataKey = MediaMetadata.getKeyFromMetadataEditorKey(key);
                // But just in case, don't add things we don't understand
                if (metadataKey != null) {
                    mMetadataBuilder.putText(metadataKey, value);
                }
            }

            return this;
        }

        /**
         * Adds numerical information to be displayed.
         * Note that none of the information added after {@link #apply()} has been called,
         * will be displayed.
         * @param key the identifier of a the metadata field to set. Valid values are
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_CD_TRACK_NUMBER},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DISC_NUMBER},
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DURATION} (with a value
         *      expressed in milliseconds),
         *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_YEAR}.
         * @param value The long value for the given key
         * @return Returns a reference to the same MetadataEditor object, so you can chain put
         *      calls together.
         * @throws IllegalArgumentException
         */
        public synchronized MetadataEditor putLong(int key, long value)
                throws IllegalArgumentException {
            super.putLong(key, value);
            if (mMetadataBuilder != null) {
                // MediaMetadata supports all the same fields as MetadataEditor
                String metadataKey = MediaMetadata.getKeyFromMetadataEditorKey(key);
                // But just in case, don't add things we don't understand
                if (metadataKey != null) {
                    mMetadataBuilder.putLong(metadataKey, value);
                }
            }
            return this;
        }

        /**
         * Sets the album / artwork picture to be displayed on the remote control.
         * @param key the identifier of the bitmap to set. The only valid value is
         *      {@link #BITMAP_KEY_ARTWORK}
         * @param bitmap The bitmap for the artwork, or null if there isn't any.
         * @return Returns a reference to the same MetadataEditor object, so you can chain put
         *      calls together.
         * @throws IllegalArgumentException
         * @see android.graphics.Bitmap
         */
        @Override
        public synchronized MetadataEditor putBitmap(int key, Bitmap bitmap)
                throws IllegalArgumentException {
            super.putBitmap(key, bitmap);
            if (mMetadataBuilder != null) {
                // MediaMetadata supports all the same fields as MetadataEditor
                String metadataKey = MediaMetadata.getKeyFromMetadataEditorKey(key);
                // But just in case, don't add things we don't understand
                if (metadataKey != null) {
                    mMetadataBuilder.putBitmap(metadataKey, bitmap);
                }
            }
            return this;
        }

        @Override
        public synchronized MetadataEditor putObject(int key, Object object)
                throws IllegalArgumentException {
            super.putObject(key, object);
            if (mMetadataBuilder != null &&
                    (key == MediaMetadataEditor.RATING_KEY_BY_USER ||
                    key == MediaMetadataEditor.RATING_KEY_BY_OTHERS)) {
                String metadataKey = MediaMetadata.getKeyFromMetadataEditorKey(key);
                if (metadataKey != null) {
                    mMetadataBuilder.putRating(metadataKey, (Rating) object);
                }
            }
            return this;
        }

        /**
         * Clears all the metadata that has been set since the MetadataEditor instance was created
         * (with {@link RemoteControlClient#editMetadata(boolean)}).
         * Note that clearing the metadata doesn't reset the editable keys
         * (use {@link MediaMetadataEditor#removeEditableKeys()} instead).
         */
        @Override
        public synchronized void clear() {
            super.clear();
        }

        /**
         * Associates all the metadata that has been set since the MetadataEditor instance was
         *     created with {@link RemoteControlClient#editMetadata(boolean)}, or since
         *     {@link #clear()} was called, with the RemoteControlClient. Once "applied",
         *     this MetadataEditor cannot be reused to edit the RemoteControlClient's metadata.
         */
        public synchronized void apply() {
            if (mApplied) {
                Log.e(TAG, "Can't apply a previously applied MetadataEditor");
                return;
            }
            synchronized (mCacheLock) {
                // Still build the old metadata so when creating a new editor
                // you get the expected values.
                // assign the edited data
                mMetadata = new Bundle(mEditorMetadata);
                // add the information about editable keys
                mMetadata.putLong(String.valueOf(KEY_EDITABLE_MASK), mEditableKeys);
                if ((mOriginalArtwork != null) && (!mOriginalArtwork.equals(mEditorArtwork))) {
                    mOriginalArtwork.recycle();
                }
                mOriginalArtwork = mEditorArtwork;
                mEditorArtwork = null;

                // USE_SESSIONS
                if (mSession != null && mMetadataBuilder != null) {
                    mMediaMetadata = mMetadataBuilder.build();
                    mSession.setMetadata(mMediaMetadata);
                }
                mApplied = true;
            }
        }
    }

    /**
     * Creates a {@link MetadataEditor}.
     * @param startEmpty Set to false if you want the MetadataEditor to contain the metadata that
     *     was previously applied to the RemoteControlClient, or true if it is to be created empty.
     * @return a new MetadataEditor instance.
     */
    public MetadataEditor editMetadata(boolean startEmpty) {
        MetadataEditor editor = new MetadataEditor();
        if (startEmpty) {
            editor.mEditorMetadata = new Bundle();
            editor.mEditorArtwork = null;
            editor.mMetadataChanged = true;
            editor.mArtworkChanged = true;
            editor.mEditableKeys = 0;
        } else {
            editor.mEditorMetadata = new Bundle(mMetadata);
            editor.mEditorArtwork = mOriginalArtwork;
            editor.mMetadataChanged = false;
            editor.mArtworkChanged = false;
        }
        // USE_SESSIONS
        if (startEmpty || mMediaMetadata == null) {
            editor.mMetadataBuilder = new MediaMetadata.Builder();
        } else {
            editor.mMetadataBuilder = new MediaMetadata.Builder(mMediaMetadata);
        }
        return editor;
    }

    /**
     * Sets the current playback state.
     * @param state The current playback state, one of the following values:
     *       {@link #PLAYSTATE_STOPPED},
     *       {@link #PLAYSTATE_PAUSED},
     *       {@link #PLAYSTATE_PLAYING},
     *       {@link #PLAYSTATE_FAST_FORWARDING},
     *       {@link #PLAYSTATE_REWINDING},
     *       {@link #PLAYSTATE_SKIPPING_FORWARDS},
     *       {@link #PLAYSTATE_SKIPPING_BACKWARDS},
     *       {@link #PLAYSTATE_BUFFERING},
     *       {@link #PLAYSTATE_ERROR}.
     */
    public void setPlaybackState(int state) {
        setPlaybackStateInt(state, PLAYBACK_POSITION_ALWAYS_UNKNOWN, PLAYBACK_SPEED_1X,
                false /* legacy API, converting to method with position and speed */);
    }

    /**
     * Sets the current playback state and the matching media position for the current playback
     *   speed.
     * @param state The current playback state, one of the following values:
     *       {@link #PLAYSTATE_STOPPED},
     *       {@link #PLAYSTATE_PAUSED},
     *       {@link #PLAYSTATE_PLAYING},
     *       {@link #PLAYSTATE_FAST_FORWARDING},
     *       {@link #PLAYSTATE_REWINDING},
     *       {@link #PLAYSTATE_SKIPPING_FORWARDS},
     *       {@link #PLAYSTATE_SKIPPING_BACKWARDS},
     *       {@link #PLAYSTATE_BUFFERING},
     *       {@link #PLAYSTATE_ERROR}.
     * @param timeInMs a 0 or positive value for the current media position expressed in ms
     *    (same unit as for when sending the media duration, if applicable, with
     *    {@link android.media.MediaMetadataRetriever#METADATA_KEY_DURATION} in the
     *    {@link RemoteControlClient.MetadataEditor}). Negative values imply that position is not
     *    known (e.g. listening to a live stream of a radio) or not applicable (e.g. when state
     *    is {@link #PLAYSTATE_BUFFERING} and nothing had played yet).
     * @param playbackSpeed a value expressed as a ratio of 1x playback: 1.0f is normal playback,
     *    2.0f is 2x, 0.5f is half-speed, -2.0f is rewind at 2x speed. 0.0f means nothing is
     *    playing (e.g. when state is {@link #PLAYSTATE_ERROR}).
     */
    public void setPlaybackState(int state, long timeInMs, float playbackSpeed) {
        setPlaybackStateInt(state, timeInMs, playbackSpeed, true);
    }

    private void setPlaybackStateInt(int state, long timeInMs, float playbackSpeed,
            boolean hasPosition) {
        synchronized(mCacheLock) {
            if ((mPlaybackState != state) || (mPlaybackPositionMs != timeInMs)
                    || (mPlaybackSpeed != playbackSpeed)) {
                // store locally
                mPlaybackState = state;
                // distinguish between an application not knowing the current playback position
                // at the moment and an application using the API where only the playback state
                // is passed, not the playback position.
                if (hasPosition) {
                    if (timeInMs < 0) {
                        mPlaybackPositionMs = PLAYBACK_POSITION_INVALID;
                    } else {
                        mPlaybackPositionMs = timeInMs;
                    }
                } else {
                    mPlaybackPositionMs = PLAYBACK_POSITION_ALWAYS_UNKNOWN;
                }
                mPlaybackSpeed = playbackSpeed;
                // keep track of when the state change occurred
                mPlaybackStateChangeTimeMs = SystemClock.elapsedRealtime();

                // USE_SESSIONS
                if (mSession != null) {
                    int pbState = getStateFromRccState(state);
                    long position = hasPosition ? mPlaybackPositionMs
                            : PlaybackState.PLAYBACK_POSITION_UNKNOWN;

                    PlaybackState.Builder bob = new PlaybackState.Builder(mSessionPlaybackState);
                    bob.setState(pbState, position, playbackSpeed, SystemClock.elapsedRealtime());
                    bob.setErrorMessage(null);
                    mSessionPlaybackState = bob.build();
                    mSession.setPlaybackState(mSessionPlaybackState);
                }
            }
        }
    }

    /**
     * Sets the flags for the media transport control buttons that this client supports.
     * @param transportControlFlags A combination of the following flags:
     *      {@link #FLAG_KEY_MEDIA_PREVIOUS},
     *      {@link #FLAG_KEY_MEDIA_REWIND},
     *      {@link #FLAG_KEY_MEDIA_PLAY},
     *      {@link #FLAG_KEY_MEDIA_PLAY_PAUSE},
     *      {@link #FLAG_KEY_MEDIA_PAUSE},
     *      {@link #FLAG_KEY_MEDIA_STOP},
     *      {@link #FLAG_KEY_MEDIA_FAST_FORWARD},
     *      {@link #FLAG_KEY_MEDIA_NEXT},
     *      {@link #FLAG_KEY_MEDIA_POSITION_UPDATE},
     *      {@link #FLAG_KEY_MEDIA_RATING}.
     */
    public void setTransportControlFlags(int transportControlFlags) {
        synchronized(mCacheLock) {
            // store locally
            mTransportControlFlags = transportControlFlags;

            // USE_SESSIONS
            if (mSession != null) {
                PlaybackState.Builder bob = new PlaybackState.Builder(mSessionPlaybackState);
                bob.setActions(getActionsFromRccControlFlags(transportControlFlags));
                mSessionPlaybackState = bob.build();
                mSession.setPlaybackState(mSessionPlaybackState);
            }
        }
    }

    /**
     * Interface definition for a callback to be invoked when one of the metadata values has
     * been updated.
     * Implement this interface to receive metadata updates after registering your listener
     * through {@link RemoteControlClient#setMetadataUpdateListener(OnMetadataUpdateListener)}.
     */
    public interface OnMetadataUpdateListener {
        /**
         * Called on the implementer to notify that the metadata field for the given key has
         * been updated to the new value.
         * @param key the identifier of the updated metadata field.
         * @param newValue the Object storing the new value for the key.
         */
        public abstract void onMetadataUpdate(int key, Object newValue);
    }

    /**
     * Sets the listener to be called whenever the metadata is updated.
     * New metadata values will be received in the same thread as the one in which
     * RemoteControlClient was created.
     * @param l the metadata update listener
     */
    public void setMetadataUpdateListener(OnMetadataUpdateListener l) {
        synchronized(mCacheLock) {
            mMetadataUpdateListener = l;
        }
    }


    /**
     * Interface definition for a callback to be invoked when the media playback position is
     * requested to be updated.
     * @see RemoteControlClient#FLAG_KEY_MEDIA_POSITION_UPDATE
     */
    public interface OnPlaybackPositionUpdateListener {
        /**
         * Called on the implementer to notify it that the playback head should be set at the given
         * position. If the position can be changed from its current value, the implementor of
         * the interface must also update the playback position using
         * {@link #setPlaybackState(int, long, float)} to reflect the actual new
         * position being used, regardless of whether it differs from the requested position.
         * Failure to do so would cause the system to not know the new actual playback position,
         * and user interface components would fail to show the user where playback resumed after
         * the position was updated.
         * @param newPositionMs the new requested position in the current media, expressed in ms.
         */
        void onPlaybackPositionUpdate(long newPositionMs);
    }

    /**
     * Interface definition for a callback to be invoked when the media playback position is
     * queried.
     * @see RemoteControlClient#FLAG_KEY_MEDIA_POSITION_UPDATE
     */
    public interface OnGetPlaybackPositionListener {
        /**
         * Called on the implementer of the interface to query the current playback position.
         * @return a negative value if the current playback position (or the last valid playback
         *     position) is not known, or a zero or positive value expressed in ms indicating the
         *     current position, or the last valid known position.
         */
        long onGetPlaybackPosition();
    }

    /**
     * Sets the listener to be called whenever the media playback position is requested
     * to be updated.
     * Notifications will be received in the same thread as the one in which RemoteControlClient
     * was created.
     * @param l the position update listener to be called
     */
    public void setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener l) {
        synchronized(mCacheLock) {
            mPositionUpdateListener = l;
        }
    }

    /**
     * Sets the listener to be called whenever the media current playback position is needed.
     * Queries will be received in the same thread as the one in which RemoteControlClient
     * was created.
     * @param l the listener to be called to retrieve the playback position
     */
    public void setOnGetPlaybackPositionListener(OnGetPlaybackPositionListener l) {
        synchronized(mCacheLock) {
            mPositionProvider = l;
        }
    }

    /**
     * @hide
     * Flag to reflect that the application controlling this RemoteControlClient sends playback
     * position updates. The playback position being "readable" is considered from the application's
     * point of view.
     */
    @UnsupportedAppUsage
    public static int MEDIA_POSITION_READABLE = 1 << 0;
    /**
     * @hide
     * Flag to reflect that the application controlling this RemoteControlClient can receive
     * playback position updates. The playback position being "writable"
     * is considered from the application's point of view.
     */
    @UnsupportedAppUsage
    public static int MEDIA_POSITION_WRITABLE = 1 << 1;

    /** @hide */
    public final static int DEFAULT_PLAYBACK_VOLUME_HANDLING = PLAYBACK_VOLUME_VARIABLE;
    /** @hide */
    // hard-coded to the same number of steps as AudioService.MAX_STREAM_VOLUME[STREAM_MUSIC]
    public final static int DEFAULT_PLAYBACK_VOLUME = 15;

    /**
     * Lock for all cached data
     */
    private final Object mCacheLock = new Object();
    /**
     * Cache for the playback state.
     * Access synchronized on mCacheLock
     */
    private int mPlaybackState = PLAYSTATE_NONE;
    /**
     * Time of last play state change
     * Access synchronized on mCacheLock
     */
    private long mPlaybackStateChangeTimeMs = 0;
    /**
     * Last playback position in ms reported by the user
     */
    private long mPlaybackPositionMs = PLAYBACK_POSITION_INVALID;
    /**
     * Last playback speed reported by the user
     */
    private float mPlaybackSpeed = PLAYBACK_SPEED_1X;
    /**
     * Cache for the artwork bitmap.
     * Access synchronized on mCacheLock
     * Artwork and metadata are not kept in one Bundle because the bitmap sometimes needs to be
     * accessed to be resized, in which case a copy will be made. This would add overhead in
     * Bundle operations.
     */
    private Bitmap mOriginalArtwork;
    /**
     * Cache for the transport control mask.
     * Access synchronized on mCacheLock
     */
    private int mTransportControlFlags = FLAGS_KEY_MEDIA_NONE;
    /**
     * Cache for the metadata strings.
     * Access synchronized on mCacheLock
     * This is re-initialized in apply() and so cannot be final.
     */
    private Bundle mMetadata = new Bundle();
    /**
     * Listener registered by user of RemoteControlClient to receive requests for playback position
     * update requests.
     */
    private OnPlaybackPositionUpdateListener mPositionUpdateListener;
    /**
     * Provider registered by user of RemoteControlClient to provide the current playback position.
     */
    private OnGetPlaybackPositionListener mPositionProvider;
    /**
     * Listener registered by user of RemoteControlClient to receive edit changes to metadata
     * it exposes.
     */
    private OnMetadataUpdateListener mMetadataUpdateListener;
    /**
     * The current remote control client generation ID across the system, as known by this object
     */
    private int mCurrentClientGenId = -1;

    /**
     * The media button intent description associated with this remote control client
     * (can / should include target component for intent handling, used when persisting media
     *    button event receiver across reboots).
     */
    private final PendingIntent mRcMediaIntent;

    /**
     * Reflects whether any "plugged in" IRemoteControlDisplay has mWantsPositonSync set to true.
     */
    // TODO consider using a ref count for IRemoteControlDisplay requiring sync instead
    private boolean mNeedsPositionSync = false;

    /**
     * Cache for the current playback state using Session APIs.
     */
    private PlaybackState mSessionPlaybackState = null;

    /**
     * Cache for metadata using Session APIs. This is re-initialized in apply().
     */
    private MediaMetadata mMediaMetadata;

    /**
     * @hide
     * Accessor to media button intent description (includes target component)
     */
    public PendingIntent getRcMediaIntent() {
        return mRcMediaIntent;
    }

    /**
     * @hide
     * Default value for the unique identifier
     */
    public final static int RCSE_ID_UNREGISTERED = -1;

    // USE_SESSIONS
    private MediaSession.Callback mTransportListener = new MediaSession.Callback() {

        @Override
        public void onSeekTo(long pos) {
            RemoteControlClient.this.onSeekTo(mCurrentClientGenId, pos);
        }

        @Override
        public void onSetRating(Rating rating) {
            if ((mTransportControlFlags & FLAG_KEY_MEDIA_RATING) != 0) {
                onUpdateMetadata(mCurrentClientGenId, MetadataEditor.RATING_KEY_BY_USER, rating);
            }
        }
    };

    //===========================================================
    // Message handlers

    private void onSeekTo(int generationId, long timeMs) {
        synchronized (mCacheLock) {
            if ((mCurrentClientGenId == generationId) && (mPositionUpdateListener != null)) {
                mPositionUpdateListener.onPlaybackPositionUpdate(timeMs);
            }
        }
    }

    private void onUpdateMetadata(int generationId, int key, Object value) {
        synchronized (mCacheLock) {
            if ((mCurrentClientGenId == generationId) && (mMetadataUpdateListener != null)) {
                mMetadataUpdateListener.onMetadataUpdate(key, value);
            }
        }
    }

    //===========================================================
    // Internal utilities

    /**
     * Returns whether, for the given playback state, the playback position is expected to
     * be changing.
     * @param playstate the playback state to evaluate
     * @return true during any form of playback, false if it's not playing anything while in this
     *     playback state
     */
    static boolean playbackPositionShouldMove(int playstate) {
        switch(playstate) {
            case PLAYSTATE_STOPPED:
            case PLAYSTATE_PAUSED:
            case PLAYSTATE_BUFFERING:
            case PLAYSTATE_ERROR:
            case PLAYSTATE_SKIPPING_FORWARDS:
            case PLAYSTATE_SKIPPING_BACKWARDS:
                return false;
            case PLAYSTATE_PLAYING:
            case PLAYSTATE_FAST_FORWARDING:
            case PLAYSTATE_REWINDING:
            default:
                return true;
        }
    }

    /**
     * Period for playback position drift checks, 15s when playing at 1x or slower.
     */
    private final static long POSITION_REFRESH_PERIOD_PLAYING_MS = 15000;

    /**
     * Minimum period for playback position drift checks, never more often when every 2s, when
     * fast forwarding or rewinding.
     */
    private final static long POSITION_REFRESH_PERIOD_MIN_MS = 2000;

    /**
     * The value above which the difference between client-reported playback position and
     * estimated position is considered a drift.
     */
    private final static long POSITION_DRIFT_MAX_MS = 500;

    /**
     * Compute the period at which the estimated playback position should be compared against the
     * actual playback position. Is a funciton of playback speed.
     * @param speed 1.0f is normal playback speed
     * @return the period in ms
     */
    private static long getCheckPeriodFromSpeed(float speed) {
        if (Math.abs(speed) <= 1.0f) {
            return POSITION_REFRESH_PERIOD_PLAYING_MS;
        } else {
            return Math.max((long)(POSITION_REFRESH_PERIOD_PLAYING_MS / Math.abs(speed)),
                    POSITION_REFRESH_PERIOD_MIN_MS);
        }
    }

    /**
     * Get the {@link PlaybackState} state for the given
     * {@link RemoteControlClient} state.
     *
     * @param rccState The state used by {@link RemoteControlClient}.
     * @return The equivalent state used by {@link PlaybackState}.
     */
    private static int getStateFromRccState(int rccState) {
        switch (rccState) {
            case PLAYSTATE_BUFFERING:
                return PlaybackState.STATE_BUFFERING;
            case PLAYSTATE_ERROR:
                return PlaybackState.STATE_ERROR;
            case PLAYSTATE_FAST_FORWARDING:
                return PlaybackState.STATE_FAST_FORWARDING;
            case PLAYSTATE_NONE:
                return PlaybackState.STATE_NONE;
            case PLAYSTATE_PAUSED:
                return PlaybackState.STATE_PAUSED;
            case PLAYSTATE_PLAYING:
                return PlaybackState.STATE_PLAYING;
            case PLAYSTATE_REWINDING:
                return PlaybackState.STATE_REWINDING;
            case PLAYSTATE_SKIPPING_BACKWARDS:
                return PlaybackState.STATE_SKIPPING_TO_PREVIOUS;
            case PLAYSTATE_SKIPPING_FORWARDS:
                return PlaybackState.STATE_SKIPPING_TO_NEXT;
            case PLAYSTATE_STOPPED:
                return PlaybackState.STATE_STOPPED;
            default:
                return -1;
        }
    }

    /**
     * Get the {@link RemoteControlClient} state for the given
     * {@link PlaybackState} state.
     *
     * @param state The state used by {@link PlaybackState}.
     * @return The equivalent state used by {@link RemoteControlClient}.
     */
    static int getRccStateFromState(int state) {
        switch (state) {
            case PlaybackState.STATE_BUFFERING:
                return PLAYSTATE_BUFFERING;
            case PlaybackState.STATE_ERROR:
                return PLAYSTATE_ERROR;
            case PlaybackState.STATE_FAST_FORWARDING:
                return PLAYSTATE_FAST_FORWARDING;
            case PlaybackState.STATE_NONE:
                return PLAYSTATE_NONE;
            case PlaybackState.STATE_PAUSED:
                return PLAYSTATE_PAUSED;
            case PlaybackState.STATE_PLAYING:
                return PLAYSTATE_PLAYING;
            case PlaybackState.STATE_REWINDING:
                return PLAYSTATE_REWINDING;
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                return PLAYSTATE_SKIPPING_BACKWARDS;
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
                return PLAYSTATE_SKIPPING_FORWARDS;
            case PlaybackState.STATE_STOPPED:
                return PLAYSTATE_STOPPED;
            default:
                return -1;
        }
    }

    private static long getActionsFromRccControlFlags(int rccFlags) {
        long actions = 0;
        long flag = 1;
        while (flag <= rccFlags) {
            if ((flag & rccFlags) != 0) {
                actions |= getActionForRccFlag((int) flag);
            }
            flag = flag << 1;
        }
        return actions;
    }

    static int getRccControlFlagsFromActions(long actions) {
        int rccFlags = 0;
        long action = 1;
        while (action <= actions && action < Integer.MAX_VALUE) {
            if ((action & actions) != 0) {
                rccFlags |= getRccFlagForAction(action);
            }
            action = action << 1;
        }
        return rccFlags;
    }

    private static long getActionForRccFlag(int flag) {
        switch (flag) {
            case FLAG_KEY_MEDIA_PREVIOUS:
                return PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            case FLAG_KEY_MEDIA_REWIND:
                return PlaybackState.ACTION_REWIND;
            case FLAG_KEY_MEDIA_PLAY:
                return PlaybackState.ACTION_PLAY;
            case FLAG_KEY_MEDIA_PLAY_PAUSE:
                return PlaybackState.ACTION_PLAY_PAUSE;
            case FLAG_KEY_MEDIA_PAUSE:
                return PlaybackState.ACTION_PAUSE;
            case FLAG_KEY_MEDIA_STOP:
                return PlaybackState.ACTION_STOP;
            case FLAG_KEY_MEDIA_FAST_FORWARD:
                return PlaybackState.ACTION_FAST_FORWARD;
            case FLAG_KEY_MEDIA_NEXT:
                return PlaybackState.ACTION_SKIP_TO_NEXT;
            case FLAG_KEY_MEDIA_POSITION_UPDATE:
                return PlaybackState.ACTION_SEEK_TO;
            case FLAG_KEY_MEDIA_RATING:
                return PlaybackState.ACTION_SET_RATING;
        }
        return 0;
    }

    private static int getRccFlagForAction(long action) {
        // We only care about the lower set of actions that can map to rcc
        // flags.
        int testAction = action < Integer.MAX_VALUE ? (int) action : 0;
        switch (testAction) {
            case (int) PlaybackState.ACTION_SKIP_TO_PREVIOUS:
                return FLAG_KEY_MEDIA_PREVIOUS;
            case (int) PlaybackState.ACTION_REWIND:
                return FLAG_KEY_MEDIA_REWIND;
            case (int) PlaybackState.ACTION_PLAY:
                return FLAG_KEY_MEDIA_PLAY;
            case (int) PlaybackState.ACTION_PLAY_PAUSE:
                return FLAG_KEY_MEDIA_PLAY_PAUSE;
            case (int) PlaybackState.ACTION_PAUSE:
                return FLAG_KEY_MEDIA_PAUSE;
            case (int) PlaybackState.ACTION_STOP:
                return FLAG_KEY_MEDIA_STOP;
            case (int) PlaybackState.ACTION_FAST_FORWARD:
                return FLAG_KEY_MEDIA_FAST_FORWARD;
            case (int) PlaybackState.ACTION_SKIP_TO_NEXT:
                return FLAG_KEY_MEDIA_NEXT;
            case (int) PlaybackState.ACTION_SEEK_TO:
                return FLAG_KEY_MEDIA_POSITION_UPDATE;
            case (int) PlaybackState.ACTION_SET_RATING:
                return FLAG_KEY_MEDIA_RATING;
        }
        return 0;
    }
}
