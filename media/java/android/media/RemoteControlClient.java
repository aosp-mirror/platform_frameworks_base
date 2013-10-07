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

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import java.lang.IllegalArgumentException;
import java.util.ArrayList;
import java.util.Iterator;

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
 */
public class RemoteControlClient
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

        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
            Log.e(TAG, "RemoteControlClient() couldn't find main application thread");
        }
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

        mEventHandler = new EventHandler(this, looper);
    }

    /**
     * Class used to modify metadata in a {@link RemoteControlClient} object.
     * Use {@link RemoteControlClient#editMetadata(boolean)} to create an instance of an editor,
     * on which you set the metadata for the RemoteControlClient instance. Once all the information
     * has been set, use {@link #apply()} to make it the new metadata that should be displayed
     * for the associated client. Once the metadata has been "applied", you cannot reuse this
     * instance of the MetadataEditor.
     */
    public class MetadataEditor extends MediaMetadataEditor {

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
            synchronized(mCacheLock) {
                // assign the edited data
                mMetadata = new Bundle(mEditorMetadata);
                // add the information about editable keys
                mMetadata.putLong(String.valueOf(KEY_EDITABLE_MASK), mEditableKeys);
                if ((mOriginalArtwork != null) && (!mOriginalArtwork.equals(mEditorArtwork))) {
                    mOriginalArtwork.recycle();
                }
                mOriginalArtwork = mEditorArtwork;
                mEditorArtwork = null;
                if (mMetadataChanged & mArtworkChanged) {
                    // send to remote control display if conditions are met
                    sendMetadataWithArtwork_syncCacheLock(null, 0, 0);
                } else if (mMetadataChanged) {
                    // send to remote control display if conditions are met
                    sendMetadata_syncCacheLock(null);
                } else if (mArtworkChanged) {
                    // send to remote control display if conditions are met
                    sendArtwork_syncCacheLock(null, 0, 0);
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

                // send to remote control display if conditions are met
                sendPlaybackState_syncCacheLock(null);
                // update AudioService
                sendAudioServiceNewPlaybackState_syncCacheLock();

                // handle automatic playback position refreshes
                initiateCheckForDrift_syncCacheLock();
            }
        }
    }

    private void initiateCheckForDrift_syncCacheLock() {
        if (mEventHandler == null) {
            return;
        }
        mEventHandler.removeMessages(MSG_POSITION_DRIFT_CHECK);
        if (!mNeedsPositionSync) {
            return;
        }
        if (mPlaybackPositionMs < 0) {
            // the current playback state has no known playback position, it's no use
            // trying to see if there is any drift at this point
            // (this also bypasses this mechanism for older apps that use the old
            //  setPlaybackState(int) API)
            return;
        }
        if (playbackPositionShouldMove(mPlaybackState)) {
            // playback position moving, schedule next position drift check
            mEventHandler.sendMessageDelayed(
                    mEventHandler.obtainMessage(MSG_POSITION_DRIFT_CHECK),
                    getCheckPeriodFromSpeed(mPlaybackSpeed));
        }
    }

    private void onPositionDriftCheck() {
        if (DEBUG) { Log.d(TAG, "onPositionDriftCheck()"); }
        synchronized(mCacheLock) {
            if ((mEventHandler == null) || (mPositionProvider == null) || !mNeedsPositionSync) {
                return;
            }
            if ((mPlaybackPositionMs < 0) || (mPlaybackSpeed == 0.0f)) {
                if (DEBUG) { Log.d(TAG, " no valid position or 0 speed, no check needed"); }
                return;
            }
            long estPos = mPlaybackPositionMs + (long)
                    ((SystemClock.elapsedRealtime() - mPlaybackStateChangeTimeMs) / mPlaybackSpeed);
            long actPos = mPositionProvider.onGetPlaybackPosition();
            if (actPos >= 0) {
                if (Math.abs(estPos - actPos) > POSITION_DRIFT_MAX_MS) {
                    // drift happened, report the new position
                    if (DEBUG) { Log.w(TAG, " drift detected: actual=" +actPos +"  est=" +estPos); }
                    setPlaybackState(mPlaybackState, actPos, mPlaybackSpeed);
                } else {
                    if (DEBUG) { Log.d(TAG, " no drift: actual=" + actPos +"  est=" + estPos); }
                    // no drift, schedule the next drift check
                    mEventHandler.sendMessageDelayed(
                            mEventHandler.obtainMessage(MSG_POSITION_DRIFT_CHECK),
                            getCheckPeriodFromSpeed(mPlaybackSpeed));
                }
            } else {
                // invalid position (negative value), can't check for drift
                mEventHandler.removeMessages(MSG_POSITION_DRIFT_CHECK);
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

            // send to remote control display if conditions are met
            sendTransportControlInfo_syncCacheLock(null);
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
            int oldCapa = mPlaybackPositionCapabilities;
            if (l != null) {
                mPlaybackPositionCapabilities |= MEDIA_POSITION_WRITABLE;
            } else {
                mPlaybackPositionCapabilities &= ~MEDIA_POSITION_WRITABLE;
            }
            mPositionUpdateListener = l;
            if (oldCapa != mPlaybackPositionCapabilities) {
                // tell RCDs that this RCC's playback position capabilities have changed
                sendTransportControlInfo_syncCacheLock(null);
            }
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
            int oldCapa = mPlaybackPositionCapabilities;
            if (l != null) {
                mPlaybackPositionCapabilities |= MEDIA_POSITION_READABLE;
            } else {
                mPlaybackPositionCapabilities &= ~MEDIA_POSITION_READABLE;
            }
            mPositionProvider = l;
            if (oldCapa != mPlaybackPositionCapabilities) {
                // tell RCDs that this RCC's playback position capabilities have changed
                sendTransportControlInfo_syncCacheLock(null);
            }
            if ((mPositionProvider != null) && (mEventHandler != null)
                    && playbackPositionShouldMove(mPlaybackState)) {
                // playback position is already moving, but now we have a position provider,
                // so schedule a drift check right now
                mEventHandler.sendMessageDelayed(
                        mEventHandler.obtainMessage(MSG_POSITION_DRIFT_CHECK),
                        0 /*check now*/);
            }
        }
    }

    /**
     * @hide
     * Flag to reflect that the application controlling this RemoteControlClient sends playback
     * position updates. The playback position being "readable" is considered from the application's
     * point of view.
     */
    public static int MEDIA_POSITION_READABLE = 1 << 0;
    /**
     * @hide
     * Flag to reflect that the application controlling this RemoteControlClient can receive
     * playback position updates. The playback position being "writable"
     * is considered from the application's point of view.
     */
    public static int MEDIA_POSITION_WRITABLE = 1 << 1;

    private int mPlaybackPositionCapabilities = 0;

    /** @hide */
    public final static int DEFAULT_PLAYBACK_VOLUME_HANDLING = PLAYBACK_VOLUME_VARIABLE;
    /** @hide */
    // hard-coded to the same number of steps as AudioService.MAX_STREAM_VOLUME[STREAM_MUSIC]
    public final static int DEFAULT_PLAYBACK_VOLUME = 15;

    private int mPlaybackType = PLAYBACK_TYPE_LOCAL;
    private int mPlaybackVolumeMax = DEFAULT_PLAYBACK_VOLUME;
    private int mPlaybackVolume = DEFAULT_PLAYBACK_VOLUME;
    private int mPlaybackVolumeHandling = DEFAULT_PLAYBACK_VOLUME_HANDLING;
    private int mPlaybackStream = AudioManager.STREAM_MUSIC;

    /**
     * @hide
     * Set information describing information related to the playback of media so the system
     * can implement additional behavior to handle non-local playback usecases.
     * @param what a key to specify the type of information to set. Valid keys are
     *        {@link #PLAYBACKINFO_PLAYBACK_TYPE},
     *        {@link #PLAYBACKINFO_USES_STREAM},
     *        {@link #PLAYBACKINFO_VOLUME},
     *        {@link #PLAYBACKINFO_VOLUME_MAX},
     *        and {@link #PLAYBACKINFO_VOLUME_HANDLING}.
     * @param value the value for the supplied information to set.
     */
    public void setPlaybackInformation(int what, int value) {
        synchronized(mCacheLock) {
            switch (what) {
                case PLAYBACKINFO_PLAYBACK_TYPE:
                    if ((value >= PLAYBACK_TYPE_MIN) && (value <= PLAYBACK_TYPE_MAX)) {
                        if (mPlaybackType != value) {
                            mPlaybackType = value;
                            sendAudioServiceNewPlaybackInfo_syncCacheLock(what, value);
                        }
                    } else {
                        Log.w(TAG, "using invalid value for PLAYBACKINFO_PLAYBACK_TYPE");
                    }
                    break;
                case PLAYBACKINFO_VOLUME:
                    if ((value > -1) && (value <= mPlaybackVolumeMax)) {
                        if (mPlaybackVolume != value) {
                            mPlaybackVolume = value;
                            sendAudioServiceNewPlaybackInfo_syncCacheLock(what, value);
                        }
                    } else {
                        Log.w(TAG, "using invalid value for PLAYBACKINFO_VOLUME");
                    }
                    break;
                case PLAYBACKINFO_VOLUME_MAX:
                    if (value > 0) {
                        if (mPlaybackVolumeMax != value) {
                            mPlaybackVolumeMax = value;
                            sendAudioServiceNewPlaybackInfo_syncCacheLock(what, value);
                        }
                    } else {
                        Log.w(TAG, "using invalid value for PLAYBACKINFO_VOLUME_MAX");
                    }
                    break;
                case PLAYBACKINFO_USES_STREAM:
                    if ((value >= 0) && (value < AudioSystem.getNumStreamTypes())) {
                        mPlaybackStream = value;
                    } else {
                        Log.w(TAG, "using invalid value for PLAYBACKINFO_USES_STREAM");
                    }
                    break;
                case PLAYBACKINFO_VOLUME_HANDLING:
                    if ((value >= PLAYBACK_VOLUME_FIXED) && (value <= PLAYBACK_VOLUME_VARIABLE)) {
                        if (mPlaybackVolumeHandling != value) {
                            mPlaybackVolumeHandling = value;
                            sendAudioServiceNewPlaybackInfo_syncCacheLock(what, value);
                        }
                    } else {
                        Log.w(TAG, "using invalid value for PLAYBACKINFO_VOLUME_HANDLING");
                    }
                    break;
                default:
                    // not throwing an exception or returning an error if more keys are to be
                    // supported in the future
                    Log.w(TAG, "setPlaybackInformation() ignoring unknown key " + what);
                    break;
            }
        }
    }

    /**
     * @hide
     * Return playback information represented as an integer value.
     * @param what a key to specify the type of information to retrieve. Valid keys are
     *        {@link #PLAYBACKINFO_PLAYBACK_TYPE},
     *        {@link #PLAYBACKINFO_USES_STREAM},
     *        {@link #PLAYBACKINFO_VOLUME},
     *        {@link #PLAYBACKINFO_VOLUME_MAX},
     *        and {@link #PLAYBACKINFO_VOLUME_HANDLING}.
     * @return the current value for the given information type, or
     *   {@link #PLAYBACKINFO_INVALID_VALUE} if an error occurred or the request is invalid, or
     *   the value is unknown.
     */
    public int getIntPlaybackInformation(int what) {
        synchronized(mCacheLock) {
            switch (what) {
                case PLAYBACKINFO_PLAYBACK_TYPE:
                    return mPlaybackType;
                case PLAYBACKINFO_VOLUME:
                    return mPlaybackVolume;
                case PLAYBACKINFO_VOLUME_MAX:
                    return mPlaybackVolumeMax;
                case PLAYBACKINFO_USES_STREAM:
                    return mPlaybackStream;
                case PLAYBACKINFO_VOLUME_HANDLING:
                    return mPlaybackVolumeHandling;
                default:
                    Log.e(TAG, "getIntPlaybackInformation() unknown key " + what);
                    return PLAYBACKINFO_INVALID_VALUE;
            }
        }
    }

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
     * The remote control client generation ID, the last time it was told it was the current RC.
     * If (mCurrentClientGenId == mInternalClientGenId) is true, it means that this remote control
     * client is the "focused" one, and that whenever this client's info is updated, it needs to
     * send it to the known IRemoteControlDisplay interfaces.
     */
    private int mInternalClientGenId = -2;

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
     * A class to encapsulate all the information about a remote control display.
     * A RemoteControlClient's metadata and state may be displayed on multiple IRemoteControlDisplay
     */
    private class DisplayInfoForClient {
        /** may never be null */
        private IRemoteControlDisplay mRcDisplay;
        private int mArtworkExpectedWidth;
        private int mArtworkExpectedHeight;
        private boolean mWantsPositionSync = false;
        private boolean mEnabled = true;

        DisplayInfoForClient(IRemoteControlDisplay rcd, int w, int h) {
            mRcDisplay = rcd;
            mArtworkExpectedWidth = w;
            mArtworkExpectedHeight = h;
        }
    }

    /**
     * The list of remote control displays to which this client will send information.
     * Accessed and modified synchronized on mCacheLock
     */
    private ArrayList<DisplayInfoForClient> mRcDisplays = new ArrayList<DisplayInfoForClient>(1);

    /**
     * @hide
     * Accessor to media button intent description (includes target component)
     */
    public PendingIntent getRcMediaIntent() {
        return mRcMediaIntent;
    }
    /**
     * @hide
     * Accessor to IRemoteControlClient
     */
    public IRemoteControlClient getIRemoteControlClient() {
        return mIRCC;
    }

    /**
     * The IRemoteControlClient implementation
     */
    private final IRemoteControlClient mIRCC = new IRemoteControlClient.Stub() {

        //TODO change name to informationRequestForAllDisplays()
        public void onInformationRequested(int generationId, int infoFlags) {
            // only post messages, we can't block here
            if (mEventHandler != null) {
                // signal new client
                mEventHandler.removeMessages(MSG_NEW_INTERNAL_CLIENT_GEN);
                mEventHandler.sendMessage(
                        mEventHandler.obtainMessage(MSG_NEW_INTERNAL_CLIENT_GEN,
                                /*arg1*/ generationId, /*arg2, ignored*/ 0));
                // send the information
                mEventHandler.removeMessages(MSG_REQUEST_PLAYBACK_STATE);
                mEventHandler.removeMessages(MSG_REQUEST_METADATA);
                mEventHandler.removeMessages(MSG_REQUEST_TRANSPORTCONTROL);
                mEventHandler.removeMessages(MSG_REQUEST_ARTWORK);
                mEventHandler.removeMessages(MSG_REQUEST_METADATA_ARTWORK);
                mEventHandler.sendMessage(
                        mEventHandler.obtainMessage(MSG_REQUEST_PLAYBACK_STATE, null));
                mEventHandler.sendMessage(
                        mEventHandler.obtainMessage(MSG_REQUEST_TRANSPORTCONTROL, null));
                mEventHandler.sendMessage(mEventHandler.obtainMessage(MSG_REQUEST_METADATA_ARTWORK,
                        0, 0, null));
            }
        }

        public void informationRequestForDisplay(IRemoteControlDisplay rcd, int w, int h) {
            // only post messages, we can't block here
            if (mEventHandler != null) {
                mEventHandler.sendMessage(
                        mEventHandler.obtainMessage(MSG_REQUEST_TRANSPORTCONTROL, rcd));
                mEventHandler.sendMessage(
                        mEventHandler.obtainMessage(MSG_REQUEST_PLAYBACK_STATE, rcd));
                if ((w > 0) && (h > 0)) {
                    mEventHandler.sendMessage(
                            mEventHandler.obtainMessage(MSG_REQUEST_METADATA_ARTWORK, w, h, rcd));
                } else {
                    mEventHandler.sendMessage(
                            mEventHandler.obtainMessage(MSG_REQUEST_METADATA, rcd));
                }
            }
        }

        public void setCurrentClientGenerationId(int clientGeneration) {
            // only post messages, we can't block here
            if (mEventHandler != null) {
                mEventHandler.removeMessages(MSG_NEW_CURRENT_CLIENT_GEN);
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                        MSG_NEW_CURRENT_CLIENT_GEN, clientGeneration, 0/*ignored*/));
            }
        }

        public void plugRemoteControlDisplay(IRemoteControlDisplay rcd, int w, int h) {
            // only post messages, we can't block here
            if ((mEventHandler != null) && (rcd != null)) {
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                        MSG_PLUG_DISPLAY, w, h, rcd));
            }
        }

        public void unplugRemoteControlDisplay(IRemoteControlDisplay rcd) {
            // only post messages, we can't block here
            if ((mEventHandler != null) && (rcd != null)) {
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                        MSG_UNPLUG_DISPLAY, rcd));
            }
        }

        public void setBitmapSizeForDisplay(IRemoteControlDisplay rcd, int w, int h) {
            // only post messages, we can't block here
            if ((mEventHandler != null) && (rcd != null)) {
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                        MSG_UPDATE_DISPLAY_ARTWORK_SIZE, w, h, rcd));
            }
        }

        public void setWantsSyncForDisplay(IRemoteControlDisplay rcd, boolean wantsSync) {
            // only post messages, we can't block here
            if ((mEventHandler != null) && (rcd != null)) {
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                        MSG_DISPLAY_WANTS_POS_SYNC, wantsSync ? 1 : 0, 0/*arg2 ignored*/, rcd));
            }
        }

        public void enableRemoteControlDisplay(IRemoteControlDisplay rcd, boolean enabled) {
            // only post messages, we can't block here
            if ((mEventHandler != null) && (rcd != null)) {
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                        MSG_DISPLAY_ENABLE, enabled ? 1 : 0, 0/*arg2 ignored*/, rcd));
            }
        }

        public void seekTo(int generationId, long timeMs) {
            // only post messages, we can't block here
            if (mEventHandler != null) {
                mEventHandler.removeMessages(MSG_SEEK_TO);
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                        MSG_SEEK_TO, generationId /* arg1 */, 0 /* arg2, ignored */,
                        new Long(timeMs)));
            }
        }

        public void updateMetadata(int generationId, int key, Rating value) {
            // only post messages, we can't block here
            if (mEventHandler != null) {
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                        MSG_UPDATE_METADATA, generationId /* arg1 */, key /* arg2*/, value));
            }
        }
    };

    /**
     * @hide
     * Default value for the unique identifier
     */
    public final static int RCSE_ID_UNREGISTERED = -1;
    /**
     * Unique identifier of the RemoteControlStackEntry in AudioService with which
     * this RemoteControlClient is associated.
     */
    private int mRcseId = RCSE_ID_UNREGISTERED;
    /**
     * @hide
     * To be only used by AudioManager after it has received the unique id from
     * IAudioService.registerRemoteControlClient()
     * @param id the unique identifier of the RemoteControlStackEntry in AudioService with which
     *              this RemoteControlClient is associated.
     */
    public void setRcseId(int id) {
        mRcseId = id;
    }

    /**
     * @hide
     */
    public int getRcseId() {
        return mRcseId;
    }

    private EventHandler mEventHandler;
    private final static int MSG_REQUEST_PLAYBACK_STATE = 1;
    private final static int MSG_REQUEST_METADATA = 2;
    private final static int MSG_REQUEST_TRANSPORTCONTROL = 3;
    private final static int MSG_REQUEST_ARTWORK = 4;
    private final static int MSG_NEW_INTERNAL_CLIENT_GEN = 5;
    private final static int MSG_NEW_CURRENT_CLIENT_GEN = 6;
    private final static int MSG_PLUG_DISPLAY = 7;
    private final static int MSG_UNPLUG_DISPLAY = 8;
    private final static int MSG_UPDATE_DISPLAY_ARTWORK_SIZE = 9;
    private final static int MSG_SEEK_TO = 10;
    private final static int MSG_POSITION_DRIFT_CHECK = 11;
    private final static int MSG_DISPLAY_WANTS_POS_SYNC = 12;
    private final static int MSG_UPDATE_METADATA = 13;
    private final static int MSG_REQUEST_METADATA_ARTWORK = 14;
    private final static int MSG_DISPLAY_ENABLE = 15;

    private class EventHandler extends Handler {
        public EventHandler(RemoteControlClient rcc, Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_REQUEST_PLAYBACK_STATE:
                    synchronized (mCacheLock) {
                        sendPlaybackState_syncCacheLock((IRemoteControlDisplay)msg.obj);
                    }
                    break;
                case MSG_REQUEST_METADATA:
                    synchronized (mCacheLock) {
                        sendMetadata_syncCacheLock((IRemoteControlDisplay)msg.obj);
                    }
                    break;
                case MSG_REQUEST_TRANSPORTCONTROL:
                    synchronized (mCacheLock) {
                        sendTransportControlInfo_syncCacheLock((IRemoteControlDisplay)msg.obj);
                    }
                    break;
                case MSG_REQUEST_ARTWORK:
                    synchronized (mCacheLock) {
                        sendArtwork_syncCacheLock((IRemoteControlDisplay)msg.obj,
                                msg.arg1, msg.arg2);
                    }
                    break;
                case MSG_REQUEST_METADATA_ARTWORK:
                    synchronized (mCacheLock) {
                        sendMetadataWithArtwork_syncCacheLock((IRemoteControlDisplay)msg.obj,
                                msg.arg1, msg.arg2);
                    }
                    break;
                case MSG_NEW_INTERNAL_CLIENT_GEN:
                    onNewInternalClientGen(msg.arg1);
                    break;
                case MSG_NEW_CURRENT_CLIENT_GEN:
                    onNewCurrentClientGen(msg.arg1);
                    break;
                case MSG_PLUG_DISPLAY:
                    onPlugDisplay((IRemoteControlDisplay)msg.obj, msg.arg1, msg.arg2);
                    break;
                case MSG_UNPLUG_DISPLAY:
                    onUnplugDisplay((IRemoteControlDisplay)msg.obj);
                    break;
                case MSG_UPDATE_DISPLAY_ARTWORK_SIZE:
                    onUpdateDisplayArtworkSize((IRemoteControlDisplay)msg.obj, msg.arg1, msg.arg2);
                    break;
                case MSG_SEEK_TO:
                    onSeekTo(msg.arg1, ((Long)msg.obj).longValue());
                    break;
                case MSG_POSITION_DRIFT_CHECK:
                    onPositionDriftCheck();
                    break;
                case MSG_DISPLAY_WANTS_POS_SYNC:
                    onDisplayWantsSync((IRemoteControlDisplay)msg.obj, msg.arg1 == 1);
                    break;
                case MSG_UPDATE_METADATA:
                    onUpdateMetadata(msg.arg1, msg.arg2, msg.obj);
                    break;
                case MSG_DISPLAY_ENABLE:
                    onDisplayEnable((IRemoteControlDisplay)msg.obj, msg.arg1 == 1);
                    break;
                default:
                    Log.e(TAG, "Unknown event " + msg.what + " in RemoteControlClient handler");
            }
        }
    }

    //===========================================================
    // Communication with the IRemoteControlDisplay (the displays known to the system)

    private void sendPlaybackState_syncCacheLock(IRemoteControlDisplay target) {
        if (mCurrentClientGenId == mInternalClientGenId) {
            if (target != null) {
                try {
                    target.setPlaybackState(mInternalClientGenId,
                            mPlaybackState, mPlaybackStateChangeTimeMs, mPlaybackPositionMs,
                            mPlaybackSpeed);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error in setPlaybackState() for dead display " + target, e);
                }
                return;
            }
            // target == null implies all displays must be updated
            final Iterator<DisplayInfoForClient> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                final DisplayInfoForClient di = (DisplayInfoForClient) displayIterator.next();
                if (di.mEnabled) {
                    try {
                        di.mRcDisplay.setPlaybackState(mInternalClientGenId,
                                mPlaybackState, mPlaybackStateChangeTimeMs, mPlaybackPositionMs,
                                mPlaybackSpeed);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error in setPlaybackState(), dead display " + di.mRcDisplay, e);
                        displayIterator.remove();
                    }
                }
            }
        }
    }

    private void sendMetadata_syncCacheLock(IRemoteControlDisplay target) {
        if (mCurrentClientGenId == mInternalClientGenId) {
            if (target != null) {
                try {
                    target.setMetadata(mInternalClientGenId, mMetadata);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error in setMetadata() for dead display " + target, e);
                }
                return;
            }
            // target == null implies all displays must be updated
            final Iterator<DisplayInfoForClient> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                final DisplayInfoForClient di = (DisplayInfoForClient) displayIterator.next();
                if (di.mEnabled) {
                    try {
                        di.mRcDisplay.setMetadata(mInternalClientGenId, mMetadata);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error in setMetadata(), dead display " + di.mRcDisplay, e);
                        displayIterator.remove();
                    }
                }
            }
        }
    }

    private void sendTransportControlInfo_syncCacheLock(IRemoteControlDisplay target) {
        if (mCurrentClientGenId == mInternalClientGenId) {
            if (target != null) {
                try {
                    target.setTransportControlInfo(mInternalClientGenId,
                            mTransportControlFlags, mPlaybackPositionCapabilities);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error in setTransportControlFlags() for dead display " + target,
                            e);
                }
                return;
            }
            // target == null implies all displays must be updated
            final Iterator<DisplayInfoForClient> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                final DisplayInfoForClient di = (DisplayInfoForClient) displayIterator.next();
                if (di.mEnabled) {
                    try {
                        di.mRcDisplay.setTransportControlInfo(mInternalClientGenId,
                                mTransportControlFlags, mPlaybackPositionCapabilities);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error in setTransportControlFlags(), dead display " + di.mRcDisplay,
                                e);
                        displayIterator.remove();
                    }
                }
            }
        }
    }

    private void sendArtwork_syncCacheLock(IRemoteControlDisplay target, int w, int h) {
        // FIXME modify to cache all requested sizes?
        if (mCurrentClientGenId == mInternalClientGenId) {
            if (target != null) {
                final DisplayInfoForClient di = new DisplayInfoForClient(target, w, h);
                sendArtworkToDisplay(di);
                return;
            }
            // target == null implies all displays must be updated
            final Iterator<DisplayInfoForClient> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                if (!sendArtworkToDisplay((DisplayInfoForClient) displayIterator.next())) {
                    displayIterator.remove();
                }
            }
        }
    }

    /**
     * Send artwork to an IRemoteControlDisplay.
     * @param di encapsulates the IRemoteControlDisplay that will receive the artwork, and its
     *    dimension requirements.
     * @return false if there was an error communicating with the IRemoteControlDisplay.
     */
    private boolean sendArtworkToDisplay(DisplayInfoForClient di) {
        if ((di.mArtworkExpectedWidth > 0) && (di.mArtworkExpectedHeight > 0)) {
            Bitmap artwork = scaleBitmapIfTooBig(mOriginalArtwork,
                    di.mArtworkExpectedWidth, di.mArtworkExpectedHeight);
            try {
                di.mRcDisplay.setArtwork(mInternalClientGenId, artwork);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in sendArtworkToDisplay(), dead display " + di.mRcDisplay, e);
                return false;
            }
        }
        return true;
    }

    private void sendMetadataWithArtwork_syncCacheLock(IRemoteControlDisplay target, int w, int h) {
        // FIXME modify to cache all requested sizes?
        if (mCurrentClientGenId == mInternalClientGenId) {
            if (target != null) {
                try {
                    if ((w > 0) && (h > 0)) {
                        Bitmap artwork = scaleBitmapIfTooBig(mOriginalArtwork, w, h);
                        target.setAllMetadata(mInternalClientGenId, mMetadata, artwork);
                    } else {
                        target.setMetadata(mInternalClientGenId, mMetadata);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Error in set(All)Metadata() for dead display " + target, e);
                }
                return;
            }
            // target == null implies all displays must be updated
            final Iterator<DisplayInfoForClient> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                final DisplayInfoForClient di = (DisplayInfoForClient) displayIterator.next();
                try {
                    if (di.mEnabled) {
                        if ((di.mArtworkExpectedWidth > 0) && (di.mArtworkExpectedHeight > 0)) {
                            Bitmap artwork = scaleBitmapIfTooBig(mOriginalArtwork,
                                    di.mArtworkExpectedWidth, di.mArtworkExpectedHeight);
                            di.mRcDisplay.setAllMetadata(mInternalClientGenId, mMetadata, artwork);
                        } else {
                            di.mRcDisplay.setMetadata(mInternalClientGenId, mMetadata);
                        }
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Error when setting metadata, dead display " + di.mRcDisplay, e);
                    displayIterator.remove();
                }
            }
        }
    }

    //===========================================================
    // Communication with AudioService

    private static IAudioService sService;

    private static IAudioService getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }

    private void sendAudioServiceNewPlaybackInfo_syncCacheLock(int what, int value) {
        if (mRcseId == RCSE_ID_UNREGISTERED) {
            return;
        }
        //Log.d(TAG, "sending to AudioService key=" + what + ", value=" + value);
        IAudioService service = getService();
        try {
            service.setPlaybackInfoForRcc(mRcseId, what, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setPlaybackInfoForRcc", e);
        }
    }

    private void sendAudioServiceNewPlaybackState_syncCacheLock() {
        if (mRcseId == RCSE_ID_UNREGISTERED) {
            return;
        }
        IAudioService service = getService();
        try {
            service.setPlaybackStateForRcc(mRcseId,
                    mPlaybackState, mPlaybackPositionMs, mPlaybackSpeed);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setPlaybackStateForRcc", e);
        }
    }

    //===========================================================
    // Message handlers

    private void onNewInternalClientGen(int clientGeneration) {
        synchronized (mCacheLock) {
            // this remote control client is told it is the "focused" one:
            // it implies that now (mCurrentClientGenId == mInternalClientGenId) is true
            mInternalClientGenId = clientGeneration;
        }
    }

    private void onNewCurrentClientGen(int clientGeneration) {
        synchronized (mCacheLock) {
            mCurrentClientGenId = clientGeneration;
        }
    }

    /** pre-condition rcd != null */
    private void onPlugDisplay(IRemoteControlDisplay rcd, int w, int h) {
        synchronized(mCacheLock) {
            // do we have this display already?
            boolean displayKnown = false;
            final Iterator<DisplayInfoForClient> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext() && !displayKnown) {
                final DisplayInfoForClient di = (DisplayInfoForClient) displayIterator.next();
                displayKnown = di.mRcDisplay.asBinder().equals(rcd.asBinder());
                if (displayKnown) {
                    // this display was known but the change in artwork size will cause the
                    // artwork to be refreshed
                    if ((di.mArtworkExpectedWidth != w) || (di.mArtworkExpectedHeight != h)) {
                        di.mArtworkExpectedWidth = w;
                        di.mArtworkExpectedHeight = h;
                        if (!sendArtworkToDisplay(di)) {
                            displayIterator.remove();
                        }
                    }
                }
            }
            if (!displayKnown) {
                mRcDisplays.add(new DisplayInfoForClient(rcd, w, h));
            }
        }
    }

    /** pre-condition rcd != null */
    private void onUnplugDisplay(IRemoteControlDisplay rcd) {
        synchronized(mCacheLock) {
            Iterator<DisplayInfoForClient> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                final DisplayInfoForClient di = (DisplayInfoForClient) displayIterator.next();
                if (di.mRcDisplay.asBinder().equals(rcd.asBinder())) {
                    displayIterator.remove();
                    break;
                }
            }
            // list of RCDs has changed, reevaluate whether position check is still needed
            boolean oldNeedsPositionSync = mNeedsPositionSync;
            boolean newNeedsPositionSync = false;
            displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                final DisplayInfoForClient di = (DisplayInfoForClient) displayIterator.next();
                if (di.mWantsPositionSync) {
                    newNeedsPositionSync = true;
                    break;
                }
            }
            mNeedsPositionSync = newNeedsPositionSync;
            if (oldNeedsPositionSync != mNeedsPositionSync) {
                // update needed?
                initiateCheckForDrift_syncCacheLock();
            }
        }
    }

    /** pre-condition rcd != null */
    private void onUpdateDisplayArtworkSize(IRemoteControlDisplay rcd, int w, int h) {
        synchronized(mCacheLock) {
            final Iterator<DisplayInfoForClient> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                final DisplayInfoForClient di = (DisplayInfoForClient) displayIterator.next();
                if (di.mRcDisplay.asBinder().equals(rcd.asBinder()) &&
                        ((di.mArtworkExpectedWidth != w) || (di.mArtworkExpectedHeight != h))) {
                    di.mArtworkExpectedWidth = w;
                    di.mArtworkExpectedHeight = h;
                    if (di.mEnabled) {
                        if (!sendArtworkToDisplay(di)) {
                            displayIterator.remove();
                        }
                    }
                    break;
                }
            }
        }
    }

    /** pre-condition rcd != null */
    private void onDisplayWantsSync(IRemoteControlDisplay rcd, boolean wantsSync) {
        synchronized(mCacheLock) {
            boolean oldNeedsPositionSync = mNeedsPositionSync;
            boolean newNeedsPositionSync = false;
            final Iterator<DisplayInfoForClient> displayIterator = mRcDisplays.iterator();
            // go through the list of RCDs and for each entry, check both whether this is the RCD
            //  that gets upated, and whether the list has one entry that wants position sync
            while (displayIterator.hasNext()) {
                final DisplayInfoForClient di = (DisplayInfoForClient) displayIterator.next();
                if (di.mEnabled) {
                    if (di.mRcDisplay.asBinder().equals(rcd.asBinder())) {
                        di.mWantsPositionSync = wantsSync;
                    }
                    if (di.mWantsPositionSync) {
                        newNeedsPositionSync = true;
                    }
                }
            }
            mNeedsPositionSync = newNeedsPositionSync;
            if (oldNeedsPositionSync != mNeedsPositionSync) {
                // update needed?
                initiateCheckForDrift_syncCacheLock();
            }
        }
    }

    /** pre-condition rcd != null */
    private void onDisplayEnable(IRemoteControlDisplay rcd, boolean enable) {
        synchronized(mCacheLock) {
            final Iterator<DisplayInfoForClient> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                final DisplayInfoForClient di = (DisplayInfoForClient) displayIterator.next();
                if (di.mRcDisplay.asBinder().equals(rcd.asBinder())) {
                    di.mEnabled = enable;
                }
            }
        }
    }

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
     * Scale a bitmap to fit the smallest dimension by uniformly scaling the incoming bitmap.
     * If the bitmap fits, then do nothing and return the original.
     *
     * @param bitmap
     * @param maxWidth
     * @param maxHeight
     * @return
     */

    private Bitmap scaleBitmapIfTooBig(Bitmap bitmap, int maxWidth, int maxHeight) {
        if (bitmap != null) {
            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();
            if (width > maxWidth || height > maxHeight) {
                float scale = Math.min((float) maxWidth / width, (float) maxHeight / height);
                int newWidth = Math.round(scale * width);
                int newHeight = Math.round(scale * height);
                Bitmap.Config newConfig = bitmap.getConfig();
                if (newConfig == null) {
                    newConfig = Bitmap.Config.ARGB_8888;
                }
                Bitmap outBitmap = Bitmap.createBitmap(newWidth, newHeight, newConfig);
                Canvas canvas = new Canvas(outBitmap);
                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setFilterBitmap(true);
                canvas.drawBitmap(bitmap, null,
                        new RectF(0, 0, outBitmap.getWidth(), outBitmap.getHeight()), paint);
                bitmap = outBitmap;
            }
        }
        return bitmap;
    }


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
}
