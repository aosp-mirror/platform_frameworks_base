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

import android.content.ComponentName;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;

/**
 * @hide
 * CANDIDATE FOR SDK
 * RemoteControlClient enables exposing information meant to be consumed by remote controls
 * capable of displaying metadata, album art and media transport control buttons.
 * A remote control client object is associated with a media button event receiver
 * when registered through
 * {@link AudioManager#registerRemoteControlClient(RemoteControlClient)}.
 */
public class RemoteControlClient
{
    private final static String TAG = "RemoteControlClient";

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
     * The value of a playback state when none has been declared
     */
    public final static int PLAYSTATE_NONE               = 0;

    /**
     * Flag indicating a RemoteControlClient makes use of the "previous" media key.
     *
     * @see #setTransportControlFlags(int)
     * @see android.view.KeyEvent#KEYCODE_MEDIA_PREVIOUS
     */
    public final static int FLAG_KEY_MEDIA_PREVIOUS = 1 << 0;
    /**
     * Flag indicating a RemoteControlClient makes use of the "rewing" media key.
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
     * @hide
     * The flags for when no media keys are declared supported
     */
    public final static int FLAGS_KEY_MEDIA_NONE = 0;

    /**
     * @hide
     * Flag used to signal some type of metadata exposed by the RemoteControlClient is requested.
     */
    public final static int FLAG_INFORMATION_REQUEST_METADATA = 1 << 0;
    /**
     * @hide
     * FIXME doc not valid
     * Flag used to signal that the transport control buttons supported by the
     * RemoteControlClient have changed.
     * This can for instance happen when playback is at the end of a playlist, and the "next"
     * operation is not supported anymore.
     */
    public final static int FLAG_INFORMATION_REQUEST_KEY_MEDIA = 1 << 1;
    /**
     * @hide
     * FIXME doc not valid
     * Flag used to signal that the playback state of the RemoteControlClient has changed.
     */
    public final static int FLAG_INFORMATION_REQUEST_PLAYSTATE = 1 << 2;
    /**
     * @hide
     * FIXME doc not valid
     * Flag used to signal that the album art for the RemoteControlClient has changed.
     */
    public final static int FLAG_INFORMATION_REQUEST_ALBUM_ART = 1 << 3;

    /**
     * Class constructor.
     * @param mediaButtonEventReceiver the receiver for the media button events.
     * @see AudioManager#registerMediaButtonEventReceiver(ComponentName)
     * @see AudioManager#registerRemoteControlClient(RemoteControlClient)
     */
    public RemoteControlClient(ComponentName mediaButtonEventReceiver) {
        mRcEventReceiver = mediaButtonEventReceiver;

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
     * @param mediaButtonEventReceiver the receiver for the media button events.
     * @param looper the Looper running the event loop.
     * @see AudioManager#registerMediaButtonEventReceiver(ComponentName)
     * @see AudioManager#registerRemoteControlClient(RemoteControlClient)
     */
    public RemoteControlClient(ComponentName mediaButtonEventReceiver, Looper looper) {
        mRcEventReceiver = mediaButtonEventReceiver;

        mEventHandler = new EventHandler(this, looper);
    }

    /**
     * Class used to modify metadata in a {@link RemoteControlClient} object.
     */
    public class MetadataEditor {

        private MetadataEditor() { /* only use factory */ }

        public MetadataEditor putString(int key, String value) {
            return this;
        }

        public MetadataEditor putBitmap(int key, Bitmap bitmap) {
            return this;
        }

        public void clear() {

        }

        public void apply() {

        }
    }

    public MetadataEditor editMetadata(boolean startEmpty) {
        return (new MetadataEditor());
    }


    /**
     * @hide
     * FIXME migrate this functionality under MetadataEditor
     * Start collecting information to be displayed.
     * Use {@link #commitMetadata()} to signal the end of the collection which has been created
     *  through one or multiple calls to {@link #addMetadataString(int, int, String)}.
     */
    public void startMetadata() {
        synchronized(mCacheLock) {
            mMetadata.clear();
        }
    }

    /**
     * @hide
     * FIXME migrate this functionality under MetadataEditor
     * Adds textual information to be displayed.
     * Note that none of the information added before {@link #startMetadata()},
     * and after {@link #commitMetadata()} has been called, will be displayed.
     * @param key the identifier of a the metadata field to set. Valid values are
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ALBUM},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ALBUMARTIST},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_TITLE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_ARTIST},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_AUTHOR},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_CD_TRACK_NUMBER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_COMPILATION},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_COMPOSER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DATE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DISC_NUMBER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_DURATION},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_GENRE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_TITLE},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_WRITER},
     *      {@link android.media.MediaMetadataRetriever#METADATA_KEY_YEAR}.
     * @param value the String for the field value, or null to signify there is no valid
     *      information for the field.
     */
    public void addMetadataString(int key, String value) {
        synchronized(mCacheLock) {
            // store locally
            mMetadata.putString(String.valueOf(key), value);
        }
    }

    /**
     * @hide
     * FIXME migrate this functionality under MetadataEditor
     * Marks all the metadata previously set with {@link #addMetadataString(int, int, String)} as
     * eligible to be displayed.
     */
    public void commitMetadata() {
        synchronized(mCacheLock) {
            // send to remote control display if conditions are met
            sendMetadata_syncCacheLock();
        }
    }

    /**
     * @hide
     * FIXME migrate this functionality under MetadataEditor
     * Sets the album / artwork picture to be displayed on the remote control.
     * @param artwork the bitmap for the artwork, or null if there isn't any.
     * @see android.graphics.Bitmap
     */
    public void setArtwork(Bitmap artwork) {
        synchronized(mCacheLock) {
            // resize and store locally
            if (mArtworkExpectedWidth > 0) {
                mArtwork = scaleBitmapIfTooBig(artwork,
                        mArtworkExpectedWidth, mArtworkExpectedHeight);
            } else {
                // no valid resize dimensions, store as is
                mArtwork = artwork;
            }
            // send to remote control display if conditions are met
            sendArtwork_syncCacheLock();
        }
    }

    /**
     * Sets the current playback state.
     * @param state the current playback state, one of the following values:
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
        synchronized(mCacheLock) {
            // store locally
            mPlaybackState = state;

            // send to remote control display if conditions are met
            sendPlaybackState_syncCacheLock();
        }
    }

    /**
     * Sets the flags for the media transport control buttons that this client supports.
     * @param a combination of the following flags:
     *      {@link #FLAG_KEY_MEDIA_PREVIOUS},
     *      {@link #FLAG_KEY_MEDIA_REWIND},
     *      {@link #FLAG_KEY_MEDIA_PLAY},
     *      {@link #FLAG_KEY_MEDIA_PLAY_PAUSE},
     *      {@link #FLAG_KEY_MEDIA_PAUSE},
     *      {@link #FLAG_KEY_MEDIA_STOP},
     *      {@link #FLAG_KEY_MEDIA_FAST_FORWARD},
     *      {@link #FLAG_KEY_MEDIA_NEXT}
     */
    public void setTransportControlFlags(int transportControlFlags) {
        synchronized(mCacheLock) {
            // store locally
            mTransportControlFlags = transportControlFlags;

            // send to remote control display if conditions are met
            sendTransportControlFlags_syncCacheLock();
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
     * Cache for the artwork bitmap.
     * Access synchronized on mCacheLock
     */
    private Bitmap mArtwork;
    private final int ARTWORK_DEFAULT_SIZE = 256;
    private int mArtworkExpectedWidth = ARTWORK_DEFAULT_SIZE;
    private int mArtworkExpectedHeight = ARTWORK_DEFAULT_SIZE;
    /**
     * Cache for the transport control mask.
     * Access synchronized on mCacheLock
     */
    private int mTransportControlFlags = FLAGS_KEY_MEDIA_NONE;
    /**
     * Cache for the metadata strings.
     * Access synchronized on mCacheLock
     */
    private Bundle mMetadata = new Bundle();
    /**
     * The current remote control client generation ID across the system
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
     * The media button event receiver associated with this remote control client
     */
    private final ComponentName mRcEventReceiver;

    /**
     * The remote control display to which this client will send information.
     * NOTE: Only one IRemoteControlDisplay supported in this implementation
     */
    private IRemoteControlDisplay mRcDisplay;

    /**
     * @hide
     * Accessor to media button event receiver
     */
    public ComponentName getRcEventReceiver() {
        return mRcEventReceiver;
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
    private IRemoteControlClient mIRCC = new IRemoteControlClient.Stub() {

        public void onInformationRequested(int clientGeneration, int infoFlags,
                int artWidth, int artHeight) {
            // only post messages, we can't block here
            if (mEventHandler != null) {
                // signal new client
                mEventHandler.removeMessages(MSG_NEW_INTERNAL_CLIENT_GEN);
                mEventHandler.dispatchMessage(
                        mEventHandler.obtainMessage(
                                MSG_NEW_INTERNAL_CLIENT_GEN,
                                artWidth, artHeight,
                                new Integer(clientGeneration)));
                // send the information
                mEventHandler.removeMessages(MSG_REQUEST_PLAYBACK_STATE);
                mEventHandler.removeMessages(MSG_REQUEST_METADATA);
                mEventHandler.removeMessages(MSG_REQUEST_TRANSPORTCONTROL);
                mEventHandler.removeMessages(MSG_REQUEST_ARTWORK);
                mEventHandler.dispatchMessage(
                        mEventHandler.obtainMessage(MSG_REQUEST_PLAYBACK_STATE));
                mEventHandler.dispatchMessage(
                        mEventHandler.obtainMessage(MSG_REQUEST_TRANSPORTCONTROL));
                mEventHandler.dispatchMessage(mEventHandler.obtainMessage(MSG_REQUEST_METADATA));
                mEventHandler.dispatchMessage(mEventHandler.obtainMessage(MSG_REQUEST_ARTWORK));
            }
        }

        public void setCurrentClientGenerationId(int clientGeneration) {
            // only post messages, we can't block here
            if (mEventHandler != null) {
                mEventHandler.removeMessages(MSG_NEW_CURRENT_CLIENT_GEN);
                mEventHandler.dispatchMessage(mEventHandler.obtainMessage(
                        MSG_NEW_CURRENT_CLIENT_GEN, clientGeneration, 0/*ignored*/));
            }
        }

        public void plugRemoteControlDisplay(IRemoteControlDisplay rcd) {
            // only post messages, we can't block here
            if (mEventHandler != null) {
                mEventHandler.dispatchMessage(mEventHandler.obtainMessage(
                        MSG_PLUG_DISPLAY, rcd));
            }
        }

        public void unplugRemoteControlDisplay(IRemoteControlDisplay rcd) {
            // only post messages, we can't block here
            if (mEventHandler != null) {
                mEventHandler.dispatchMessage(mEventHandler.obtainMessage(
                        MSG_UNPLUG_DISPLAY, rcd));
            }
        }
    };

    private EventHandler mEventHandler;
    private final static int MSG_REQUEST_PLAYBACK_STATE = 1;
    private final static int MSG_REQUEST_METADATA = 2;
    private final static int MSG_REQUEST_TRANSPORTCONTROL = 3;
    private final static int MSG_REQUEST_ARTWORK = 4;
    private final static int MSG_NEW_INTERNAL_CLIENT_GEN = 5;
    private final static int MSG_NEW_CURRENT_CLIENT_GEN = 6;
    private final static int MSG_PLUG_DISPLAY = 7;
    private final static int MSG_UNPLUG_DISPLAY = 8;

    private class EventHandler extends Handler {
        public EventHandler(RemoteControlClient rcc, Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_REQUEST_PLAYBACK_STATE:
                    synchronized (mCacheLock) {
                        sendPlaybackState_syncCacheLock();
                    }
                    break;
                case MSG_REQUEST_METADATA:
                    synchronized (mCacheLock) {
                        sendMetadata_syncCacheLock();
                    }
                    break;
                case MSG_REQUEST_TRANSPORTCONTROL:
                    synchronized (mCacheLock) {
                        sendTransportControlFlags_syncCacheLock();
                    }
                    break;
                case MSG_REQUEST_ARTWORK:
                    synchronized (mCacheLock) {
                        sendArtwork_syncCacheLock();
                    }
                    break;
                case MSG_NEW_INTERNAL_CLIENT_GEN:
                    onNewInternalClientGen((Integer)msg.obj, msg.arg1, msg.arg2);
                    break;
                case MSG_NEW_CURRENT_CLIENT_GEN:
                    onNewCurrentClientGen(msg.arg1);
                    break;
                case MSG_PLUG_DISPLAY:
                    onPlugDisplay((IRemoteControlDisplay)msg.obj);
                    break;
                case MSG_UNPLUG_DISPLAY:
                    onUnplugDisplay((IRemoteControlDisplay)msg.obj);
                    break;
                default:
                    Log.e(TAG, "Unknown event " + msg.what + " in RemoteControlClient handler");
            }
        }
    }

    private void sendPlaybackState_syncCacheLock() {
        if ((mCurrentClientGenId == mInternalClientGenId) && (mRcDisplay != null)) {
            try {
                mRcDisplay.setPlaybackState(mInternalClientGenId, mPlaybackState);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in setPlaybackState(), dead display "+e);
                mRcDisplay = null;
                mArtworkExpectedWidth = -1;
                mArtworkExpectedHeight = -1;
            }
        }
    }

    private void sendMetadata_syncCacheLock() {
        if ((mCurrentClientGenId == mInternalClientGenId) && (mRcDisplay != null)) {
            try {
                mRcDisplay.setMetadata(mInternalClientGenId, mMetadata);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in sendPlaybackState(), dead display "+e);
                mRcDisplay = null;
                mArtworkExpectedWidth = -1;
                mArtworkExpectedHeight = -1;
            }
        }
    }

    private void sendTransportControlFlags_syncCacheLock() {
        if ((mCurrentClientGenId == mInternalClientGenId) && (mRcDisplay != null)) {
            try {
                mRcDisplay.setTransportControlFlags(mInternalClientGenId,
                        mTransportControlFlags);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in sendTransportControlFlags(), dead display "+e);
                mRcDisplay = null;
                mArtworkExpectedWidth = -1;
                mArtworkExpectedHeight = -1;
            }
        }
    }

    private void sendArtwork_syncCacheLock() {
        if ((mCurrentClientGenId == mInternalClientGenId) && (mRcDisplay != null)) {
            // even though we have already scaled in setArtwork(), when this client needs to
            // send the bitmap, there might be newer and smaller expected dimensions, so we have
            // to check again.
            mArtwork = scaleBitmapIfTooBig(mArtwork, mArtworkExpectedWidth, mArtworkExpectedHeight);
            try {
                mRcDisplay.setArtwork(mInternalClientGenId, mArtwork);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in sendArtwork(), dead display "+e);
                mRcDisplay = null;
                mArtworkExpectedWidth = -1;
                mArtworkExpectedHeight = -1;
            }
        }
    }

    private void onNewInternalClientGen(Integer clientGeneration, int artWidth, int artHeight) {
        synchronized (mCacheLock) {
            // this remote control client is told it is the "focused" one:
            // it implies that now (mCurrentClientGenId == mInternalClientGenId) is true
            mInternalClientGenId = clientGeneration.intValue();
            if (artWidth > 0) {
                mArtworkExpectedWidth = artWidth;
                mArtworkExpectedHeight = artHeight;
            }
        }
    }

    private void onNewCurrentClientGen(int clientGeneration) {
        synchronized (mCacheLock) {
            mCurrentClientGenId = clientGeneration;
        }
    }

    private void onPlugDisplay(IRemoteControlDisplay rcd) {
        synchronized(mCacheLock) {
            mRcDisplay = rcd;
        }
    }

    private void onUnplugDisplay(IRemoteControlDisplay rcd) {
        synchronized(mCacheLock) {
            if ((mRcDisplay != null) && (mRcDisplay.equals(rcd))) {
                mRcDisplay = null;
                mArtworkExpectedWidth = ARTWORK_DEFAULT_SIZE;
                mArtworkExpectedHeight = ARTWORK_DEFAULT_SIZE;
            }
        }
    }

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
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        if (width > maxWidth || height > maxHeight) {
            float scale = Math.min((float) maxWidth / width, (float) maxHeight / height);
            int newWidth = Math.round(scale * width);
            int newHeight = Math.round(scale * height);
            Bitmap outBitmap = Bitmap.createBitmap(newWidth, newHeight, bitmap.getConfig());
            Canvas canvas = new Canvas(outBitmap);
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setFilterBitmap(true);
            canvas.drawBitmap(bitmap, null,
                    new RectF(0, 0, outBitmap.getWidth(), outBitmap.getHeight()), paint);
            bitmap = outBitmap;
        }
        return bitmap;

    }
}
