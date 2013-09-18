/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.IRemoteControlDisplay;
import android.media.MediaMetadataEditor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.KeyEvent;

/**
 * @hide
 * CANDIDATE FOR PUBLIC API
 * The RemoteController class is used to control media playback, display and update media metadata
 * and playback status, published by applications using the {@link RemoteControlClient} class.
 * <p>
 * A RemoteController shall be registered through
 * {@link AudioManager#registerRemoteController(RemoteController)} in order for the system to send
 * media event updates to the listener set in
 * {@link #setOnClientUpdateListener(OnClientUpdateListener)}. This listener is a subclass of
 * the {@link OnClientUpdateListener} abstract class. Override its methods to receive the
 * information published by the active {@link RemoteControlClient} instances.
 * By default an {@link OnClientUpdateListener} implementation will not receive bitmaps for album
 * art. Use {@link #setBitmapConfiguration(boolean, int, int)} to receive images as well.
 * <p>
 * A RemoteController can also be used without being registered, when it is only meant to send
 * media key events (for play or stop events for instance),
 * with {@link #sendMediaKeyEvent(KeyEvent)}.
 */
public class RemoteController
{
    private final static int MAX_BITMAP_DIMENSION = 512;
    private final static int TRANSPORT_UNKNOWN = 0;
    private RcDisplay mRcd;
    private final static String TAG = "RemoteController";
    private final static boolean DEBUG = false;
    private final static Object mGenLock = new Object();
    private final static Object mInfoLock = new Object();
    private Context mContext;
    private AudioManager mAudioManager;
    private MetadataEditor mMetadataEditor;

    /**
     * Synchronized on mGenLock
     */
    private int mClientGenerationIdCurrent = 0;

    /**
     * Synchronized on mInfoLock
     */
    private boolean mIsRegistered = false;
    private PendingIntent mClientPendingIntentCurrent;
    private OnClientUpdateListener mOnClientUpdateListener;
    private PlaybackInfo mLastPlaybackInfo;
    private int mLastTransportControlFlags = TRANSPORT_UNKNOWN;

    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * @param ctxt non-null {@link Context}
     * @throws java.lang.IllegalArgumentException
     */
    public RemoteController(Context ctxt) throws IllegalArgumentException {
        if (ctxt == null) {
            throw new IllegalArgumentException("Invalid null Context");
        }
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
            Log.e(TAG, "RemoteController() couldn't find main application thread");
        }
        mContext = ctxt;
        mRcd = new RcDisplay();
        mAudioManager = (AudioManager) ctxt.getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * @param looper
     * @param ctxt non-null {@link Context}
     * @throws java.lang.IllegalArgumentException
     */
    public RemoteController(Looper looper, Context ctxt) throws IllegalArgumentException {
        if (ctxt == null) {
            throw new IllegalArgumentException("Invalid null Context");
        }
        mEventHandler = new EventHandler(this, looper);
        mContext = ctxt;
        mRcd = new RcDisplay();
        mAudioManager = (AudioManager) ctxt.getSystemService(Context.AUDIO_SERVICE);
    }


    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     */
    public static abstract class OnClientUpdateListener {
        /**
         * @hide
         * CANDIDATE FOR PUBLIC API
         * @param clearing
         */
        public void onClientReset(boolean clearing) { }
        /**
         * @hide
         * CANDIDATE FOR PUBLIC API
         * @param state
         */
        public void onClientPlaybackStateUpdate(int state) { }
        /**
         * @hide
         * CANDIDATE FOR PUBLIC API
         * @param state
         * @param stateChangeTimeMs
         * @param currentPosMs
         * @param speed
         */
        public void onClientPlaybackStateUpdate(int state, long stateChangeTimeMs,
                long currentPosMs, float speed) { }
        /**
         * @hide
         * CANDIDATE FOR PUBLIC API
         * @param transportControlFlags
         * @param posCapabilities
         */
        public void onClientTransportControlUpdate(int transportControlFlags) { }
        /**
         * @hide
         * CANDIDATE FOR PUBLIC API
         * @param metadataEditor
         */
        public void onClientMetadataUpdate(MetadataEditor metadataEditor) { }
    };

    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * @param l
     */
    public void setOnClientUpdateListener(OnClientUpdateListener l) {
        synchronized(mInfoLock) {
            mOnClientUpdateListener = l;
            if (!mIsRegistered) {
                // since the object is not registered, it hasn't received any information from
                // RemoteControlClients yet, so we can exit here.
                return;
            }
            if (mLastPlaybackInfo != null) {
                sendMsg(mEventHandler, MSG_NEW_PLAYBACK_INFO, SENDMSG_REPLACE,
                        mClientGenerationIdCurrent /*arg1*/, 0,
                        mLastPlaybackInfo /*obj*/, 0 /*delay*/);
            }
            if (mLastTransportControlFlags != TRANSPORT_UNKNOWN) {
                sendMsg(mEventHandler, MSG_NEW_TRANSPORT_INFO, SENDMSG_REPLACE,
                        mClientGenerationIdCurrent /*arg1*/, mLastTransportControlFlags /*arg2*/,
                        null /*obj*/, 0 /*delay*/);
            }
            if (mMetadataEditor != null) {
                sendMsg(mEventHandler, MSG_NEW_METADATA, SENDMSG_QUEUE,
                        mClientGenerationIdCurrent /*arg1*/, 0 /*arg2*/,
                        mMetadataEditor /*obj*/, 0 /*delay*/);
            }
        }
    }


    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * Send a simulated key event for a media button.
     * May be used without registering the RemoteController
     * with {@link AudioManager#registerRemoteController(RemoteController)}. To simulate a key
     * press, you must first send a KeyEvent built with a {@link KeyEvent#ACTION_DOWN} action, then
     * another event with the {@link KeyEvent#ACTION_UP} action.
     * <p> When used from a registered RemoteController, the key event will be sent to the
     * application currently promoted to publish its media metadata and playback state (there may be
     * none under some circumstances). With an unregistered RemoteController, the key event will be
     * sent to the current media key event consumer
     * (see {@link AudioManager#registerMediaButtonEventReceiver(PendingIntent)}).
     * @param keyEvent a {@link KeyEvent} instance whose key code is one of
     *     {@link KeyEvent.KEYCODE_MUTE},
     *     {@link KeyEvent.KEYCODE_HEADSETHOOK},
     *     {@link KeyEvent.KEYCODE_MEDIA_PLAY},
     *     {@link KeyEvent.KEYCODE_MEDIA_PAUSE},
     *     {@link KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE},
     *     {@link KeyEvent.KEYCODE_MEDIA_STOP},
     *     {@link KeyEvent.KEYCODE_MEDIA_NEXT},
     *     {@link KeyEvent.KEYCODE_MEDIA_PREVIOUS},
     *     {@link KeyEvent.KEYCODE_MEDIA_REWIND},
     *     {@link KeyEvent.KEYCODE_MEDIA_RECORD},
     *     {@link KeyEvent.KEYCODE_MEDIA_FAST_FORWARD},
     *     {@link KeyEvent.KEYCODE_MEDIA_CLOSE},
     *     {@link KeyEvent.KEYCODE_MEDIA_EJECT},
     *     or {@link KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK}.
     */
    public int sendMediaKeyEvent(KeyEvent keyEvent) {
        if (!MediaFocusControl.isMediaKeyCode(keyEvent.getKeyCode())) {
            Log.e(TAG, "Cannot use sendMediaKeyEvent() for a non-media key event");
            return ERROR_BAD_VALUE;
        }
        boolean registered = false;
        final PendingIntent pi;
        synchronized(mInfoLock) {
            registered = mIsRegistered;
            pi = mClientPendingIntentCurrent;
        }
        if (registered) {
            if (pi != null) {
                Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
                try {
                    pi.send(mContext, 0, intent);
                } catch (CanceledException e) {
                    Log.e(TAG, "Error sending intent for media button down: ", e);
                    return ERROR;
                }
            } else {
                Log.i(TAG, "No-op when sending key click, no receiver right now");
                return ERROR;
            }
        } else {
            mAudioManager.dispatchMediaKeyEvent(keyEvent);
        }
        return SUCCESS;
    }


    // Error codes
    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * Successful operation.
     */
    public  static final int SUCCESS            = 0;
    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * Unspecified error.
     */
    public  static final int ERROR              = -1;
    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * Operation failed due to bad parameter value.
     */
    public  static final int ERROR_BAD_VALUE    = -2;


    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * @param timeMs
     * @return {@link #SUCCESS}, {@link #ERROR} or {@link #ERROR_BAD_VALUE}
     */
    public int seekTo(long timeMs) {
        if (timeMs < 0) {
            return ERROR_BAD_VALUE;
        }
        final int genId;
        synchronized (mGenLock) {
            genId = mClientGenerationIdCurrent;
        }
        mAudioManager.setRemoteControlClientPlaybackPosition(genId, timeMs);
        return SUCCESS;
    }


    /**
     * @hide
     * must be called on a registered RemoteController
     * @param wantBitmap
     * @param width
     * @param height
     * @return {@link #SUCCESS}, {@link #ERROR} or {@link #ERROR_BAD_VALUE}
     */
    public int setBitmapConfiguration(boolean wantBitmap, int width, int height) {
        synchronized (mInfoLock) {
            if (!mIsRegistered) {
                Log.e(TAG, "Cannot specify bitmap configuration on unregistered RemoteController");
                return ERROR;
            }
        }
        if (wantBitmap) {
            if ((width > 0) && (height > 0)) {
                if (width > MAX_BITMAP_DIMENSION) { width = MAX_BITMAP_DIMENSION; }
                if (height > MAX_BITMAP_DIMENSION) { height = MAX_BITMAP_DIMENSION; }
                mAudioManager.remoteControlDisplayUsesBitmapSize(mRcd, width, height);
            } else {
                Log.e(TAG, "Invalid dimensions");
                return ERROR_BAD_VALUE;
            }
        } else {
            mAudioManager.remoteControlDisplayUsesBitmapSize(mRcd, -1, -1);
        }
        return SUCCESS;
    }

    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * must be called on a registered RemoteController
     * @param width
     * @param height
     * @return {@link #SUCCESS}, {@link #ERROR} or {@link #ERROR_BAD_VALUE}
     */
    public int setBitmapConfiguration(int width, int height) {
        return setBitmapConfiguration(true, width, height);
    }

    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * must be called on a registered RemoteController
     * @return {@link #SUCCESS}, {@link #ERROR}
     */
    public int setBitmapConfigurationNone() {
        return setBitmapConfiguration(false, -1, -1);
    }


    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * Default playback position synchronization mode where the RemoteControlClient is not
     * asked regularly for its playback position to see if it has drifted from the estimated
     * position.
     */
    public static final int POSITION_SYNCHRONIZATION_NONE = 0;

    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * The playback position synchronization mode where the RemoteControlClient instances which
     * expose their playback position to the framework, will be regularly polled to check
     * whether any drift has been noticed between their estimated position and the one they report.
     * Note that this mode should only ever be used when needing to display very accurate playback
     * position, as regularly polling a RemoteControlClient for its position may have an impact
     * on battery life (if applicable) when this query will trigger network transactions in the
     * case of remote playback.
     */
    public static final int POSITION_SYNCHRONIZATION_CHECK = 1;

    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * Set the playback position synchronization mode.
     * Must be called on a registered RemoteController.
     * @param sync {@link #POSITION_SYNCHRONIZATION_NONE} or {@link #POSITION_SYNCHRONIZATION_CHECK}
     * @return {@link #SUCCESS}, {@link #ERROR} or {@link #ERROR_BAD_VALUE}
     */
    public int setSynchronizationMode(int sync) {
        if ((sync != POSITION_SYNCHRONIZATION_NONE) || (sync != POSITION_SYNCHRONIZATION_CHECK)) {
            Log.e(TAG, "Unknown synchronization mode");
            return ERROR_BAD_VALUE;
        }
        if (!mIsRegistered) {
            Log.e(TAG, "Cannot set synchronization mode on an unregistered RemoteController");
            return ERROR;
        }
        mAudioManager.remoteControlDisplayWantsPlaybackPositionSync(mRcd,
                POSITION_SYNCHRONIZATION_CHECK == sync);
        return SUCCESS;
    }


    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * Creates a {@link MetadataEditor} for updating metadata values of the editable keys of
     * the current {@link RemoteControlClient}.
     * @return a new MetadataEditor instance.
     */
    public MetadataEditor editMetadata() {
        MetadataEditor editor = new MetadataEditor();
        editor.mEditorMetadata = new Bundle();
        editor.mEditorArtwork = null;
        editor.mMetadataChanged = true;
        editor.mArtworkChanged = true;
        editor.mEditableKeys = 0;
        return editor;
    }


    /**
     * @hide
     * CANDIDATE FOR PUBLIC API
     * Used to read the metadata published by a {@link RemoteControlClient}, or send a
     * {@link RemoteControlClient} new values for keys that can be edited.
     */
    public class MetadataEditor extends MediaMetadataEditor {
        /**
         * @hide
         */
        protected MetadataEditor() { }

        /**
         * @hide
         */
        protected MetadataEditor(Bundle metadata, long editableKeys) {
            mEditorMetadata = metadata;
            mEditableKeys = editableKeys;
            mEditorArtwork = null;
            mMetadataChanged = true;
            mArtworkChanged = true;
            mApplied = false;
        }

        /**
         * @hide
         * CANDIDATE FOR PUBLIC API
         */
        public synchronized void apply() {
            // "applying" a metadata bundle in RemoteController is only for sending edited
            // key values back to the RemoteControlClient, so here we only care about the only
            // editable key we support: RATING_KEY_BY_USER
            if (!mMetadataChanged) {
                return;
            }
            final int genId;
            synchronized(mGenLock) {
                genId = mClientGenerationIdCurrent;
            }
            synchronized(mInfoLock) {
                if (mEditorMetadata.containsKey(
                        String.valueOf(MediaMetadataEditor.RATING_KEY_BY_USER))) {
                    Rating rating = (Rating) getObject(
                            MediaMetadataEditor.RATING_KEY_BY_USER, null);
                    mAudioManager.updateRemoteControlClientMetadata(genId,
                          MediaMetadataEditor.RATING_KEY_BY_USER,
                          rating);
                } else {
                    Log.e(TAG, "no metadata to apply");
                }
                // NOT setting mApplied to true as this type of MetadataEditor will be applied
                // multiple times, whenever the user of a RemoteController needs to change the
                // metadata (e.g. user changes the rating of a song more than once during playback)
                mApplied = false;
            }
        }

    }


    //==================================================
    // Implementation of IRemoteControlDisplay interface
    private class RcDisplay extends IRemoteControlDisplay.Stub {
        /**
         * @hide
         */
        public void setCurrentClientId(int genId, PendingIntent clientMediaIntent,
                boolean clearing) {
            boolean isNew = false;
            synchronized(mGenLock) {
                if (mClientGenerationIdCurrent != genId) {
                    mClientGenerationIdCurrent = genId;
                    isNew = true;
                }
            }
            if (clientMediaIntent != null) {
                sendMsg(mEventHandler, MSG_NEW_PENDING_INTENT, SENDMSG_REPLACE,
                        genId /*arg1*/, 0, clientMediaIntent /*obj*/, 0 /*delay*/);
            }
            if (isNew || clearing) {
                sendMsg(mEventHandler, MSG_CLIENT_RESET, SENDMSG_REPLACE,
                        genId /*arg1*/, clearing ? 1 : 0, null /*obj*/, 0 /*delay*/);
            }
        }

        /**
         * @hide
         */
        public void setPlaybackState(int genId, int state,
                long stateChangeTimeMs, long currentPosMs, float speed) {
            if (DEBUG) {
                Log.d(TAG, "> new playback state: genId="+genId
                        + " state="+ state
                        + " changeTime="+ stateChangeTimeMs
                        + " pos=" + currentPosMs
                        + "ms speed=" + speed);
            }

            synchronized(mGenLock) {
                if (mClientGenerationIdCurrent != genId) {
                    return;
                }
            }
            final PlaybackInfo playbackInfo =
                    new PlaybackInfo(state, stateChangeTimeMs, currentPosMs, speed);
            sendMsg(mEventHandler, MSG_NEW_PLAYBACK_INFO, SENDMSG_REPLACE,
                    genId /*arg1*/, 0, playbackInfo /*obj*/, 0 /*delay*/);

        }

        /**
         * @hide
         */
        public void setTransportControlInfo(int genId, int transportControlFlags,
                int posCapabilities) {
            synchronized(mGenLock) {
                if (mClientGenerationIdCurrent != genId) {
                    return;
                }
            }
            sendMsg(mEventHandler, MSG_NEW_TRANSPORT_INFO, SENDMSG_REPLACE,
                    genId /*arg1*/, transportControlFlags /*arg2*/,
                    null /*obj*/, 0 /*delay*/);
        }

        /**
         * @hide
         */
        public void setMetadata(int genId, Bundle metadata) {
            if (DEBUG) { Log.e(TAG, "setMetadata("+genId+")"); }
            if (metadata == null) {
                return;
            }
            synchronized(mGenLock) {
                if (mClientGenerationIdCurrent != genId) {
                    return;
                }
            }
            sendMsg(mEventHandler, MSG_NEW_METADATA, SENDMSG_QUEUE,
                    genId /*arg1*/, 0 /*arg2*/,
                    metadata /*obj*/, 0 /*delay*/);
        }

        /**
         * @hide
         */
        public void setArtwork(int genId, Bitmap artwork) {
            if (DEBUG) { Log.v(TAG, "setArtwork("+genId+")"); }
            if (artwork == null) {
                return;
            }
            synchronized(mGenLock) {
                if (mClientGenerationIdCurrent != genId) {
                    return;
                }
            }
            Bundle metadata = new Bundle(1);
            metadata.putParcelable(String.valueOf(MediaMetadataEditor.BITMAP_KEY_ARTWORK), artwork);
            sendMsg(mEventHandler, MSG_NEW_METADATA, SENDMSG_QUEUE,
                    genId /*arg1*/, 0 /*arg2*/,
                    metadata /*obj*/, 0 /*delay*/);
        }

        /**
         * @hide
         */
        public void setAllMetadata(int genId, Bundle metadata, Bitmap artwork) {
            if (DEBUG) { Log.e(TAG, "setAllMetadata("+genId+")"); }
            if ((metadata == null) && (artwork == null)) {
                return;
            }
            synchronized(mGenLock) {
                if (mClientGenerationIdCurrent != genId) {
                    return;
                }
            }
            if (metadata == null) {
                metadata = new Bundle(1);
            }
            if (artwork != null) {
                metadata.putParcelable(String.valueOf(MediaMetadataEditor.BITMAP_KEY_ARTWORK),
                        artwork);
            }
            sendMsg(mEventHandler, MSG_NEW_METADATA, SENDMSG_QUEUE,
                    genId /*arg1*/, 0 /*arg2*/,
                    metadata /*obj*/, 0 /*delay*/);
        }
    }

    //==================================================
    // Event handling
    private EventHandler mEventHandler;
    private final static int MSG_NEW_PENDING_INTENT = 0;
    private final static int MSG_NEW_PLAYBACK_INFO =  1;
    private final static int MSG_NEW_TRANSPORT_INFO = 2;
    private final static int MSG_NEW_METADATA       = 3; // msg always has non-null obj parameter
    private final static int MSG_CLIENT_RESET       = 4;

    private class EventHandler extends Handler {

        public EventHandler(RemoteController rc, Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_NEW_PENDING_INTENT:
                    onNewPendingIntent(msg.arg1, (PendingIntent) msg.obj);
                    break;
                case MSG_NEW_PLAYBACK_INFO:
                    onNewPlaybackInfo(msg.arg1, (PlaybackInfo) msg.obj);
                    break;
                case MSG_NEW_TRANSPORT_INFO:
                    onNewTransportInfo(msg.arg1, msg.arg2);
                    break;
                case MSG_NEW_METADATA:
                    onNewMetadata(msg.arg1, (Bundle)msg.obj);
                    break;
                case MSG_CLIENT_RESET:
                    onClientReset(msg.arg1, msg.arg2 == 1);
                    break;
                default:
                    Log.e(TAG, "unknown event " + msg.what);
            }
        }
    }

    /** If the msg is already queued, replace it with this one. */
    private static final int SENDMSG_REPLACE = 0;
    /** If the msg is already queued, ignore this one and leave the old. */
    private static final int SENDMSG_NOOP = 1;
    /** If the msg is already queued, queue this one and leave the old. */
    private static final int SENDMSG_QUEUE = 2;

    private static void sendMsg(Handler handler, int msg, int existingMsgPolicy,
            int arg1, int arg2, Object obj, int delayMs) {
        if (handler == null) {
            Log.e(TAG, "null event handler, will not deliver message " + msg);
            return;
        }
        if (existingMsgPolicy == SENDMSG_REPLACE) {
            handler.removeMessages(msg);
        } else if (existingMsgPolicy == SENDMSG_NOOP && handler.hasMessages(msg)) {
            return;
        }
        handler.sendMessageDelayed(handler.obtainMessage(msg, arg1, arg2, obj), delayMs);
    }

    private void onNewPendingIntent(int genId, PendingIntent pi) {
        synchronized(mGenLock) {
            if (mClientGenerationIdCurrent != genId) {
                return;
            }
        }
        synchronized(mInfoLock) {
            mClientPendingIntentCurrent = pi;
        }
    }

    private void onNewPlaybackInfo(int genId, PlaybackInfo pi) {
        synchronized(mGenLock) {
            if (mClientGenerationIdCurrent != genId) {
                return;
            }
        }
        final OnClientUpdateListener l;
        synchronized(mInfoLock) {
            l = this.mOnClientUpdateListener;
            mLastPlaybackInfo = pi;
        }
        if (l != null) {
            if (pi.mCurrentPosMs == RemoteControlClient.PLAYBACK_POSITION_ALWAYS_UNKNOWN) {
                l.onClientPlaybackStateUpdate(pi.mState);
            } else {
                l.onClientPlaybackStateUpdate(pi.mState, pi.mStateChangeTimeMs, pi.mCurrentPosMs,
                        pi.mSpeed);
            }
        }
    }

    private void onNewTransportInfo(int genId, int transportControlFlags) {
        synchronized(mGenLock) {
            if (mClientGenerationIdCurrent != genId) {
                return;
            }
        }
        final OnClientUpdateListener l;
        synchronized(mInfoLock) {
            l = mOnClientUpdateListener;
            mLastTransportControlFlags = transportControlFlags;
        }
        if (l != null) {
            l.onClientTransportControlUpdate(transportControlFlags);
        }
    }

    /**
     * @param genId
     * @param metadata guaranteed to be always non-null
     */
    private void onNewMetadata(int genId, Bundle metadata) {
        synchronized(mGenLock) {
            if (mClientGenerationIdCurrent != genId) {
                return;
            }
        }
        final OnClientUpdateListener l;
        final MetadataEditor metadataEditor;
        // prepare the received Bundle to be used inside a MetadataEditor
        final long editableKeys = metadata.getLong(
                String.valueOf(MediaMetadataEditor.KEY_EDITABLE_MASK), 0);
        if (editableKeys != 0) {
            metadata.remove(String.valueOf(MediaMetadataEditor.KEY_EDITABLE_MASK));
        }
        synchronized(mInfoLock) {
            l = mOnClientUpdateListener;
            if ((mMetadataEditor != null) && (mMetadataEditor.mEditorMetadata != null)) {
                if (mMetadataEditor.mEditorMetadata != metadata) {
                    // existing metadata, merge existing and new
                    mMetadataEditor.mEditorMetadata.putAll(metadata);
                }
            } else {
                mMetadataEditor = new MetadataEditor(metadata, editableKeys);
            }
            metadataEditor = mMetadataEditor;
        }
        if (l != null) {
            l.onClientMetadataUpdate(metadataEditor);
        }
    }

    private void onClientReset(int genId, boolean clearing) {
        synchronized(mGenLock) {
            if (mClientGenerationIdCurrent != genId) {
                return;
            }
        }
        final OnClientUpdateListener l;
        synchronized(mInfoLock) {
            l = mOnClientUpdateListener;
        }
        if (l != null) {
            l.onClientReset(clearing);
        }
    }


    //==================================================
    private static class PlaybackInfo {
        int mState;
        long mStateChangeTimeMs;
        long mCurrentPosMs;
        float mSpeed;

        PlaybackInfo(int state, long stateChangeTimeMs, long currentPosMs, float speed) {
            mState = state;
            mStateChangeTimeMs = stateChangeTimeMs;
            mCurrentPosMs = currentPosMs;
            mSpeed = speed;
        }
    }

    /**
     * @hide
     * Used by AudioManager to mark this instance as registered.
     * @param registered
     */
    protected void setIsRegistered(boolean registered) {
        synchronized (mInfoLock) {
            mIsRegistered = registered;
        }
    }

    /**
     * @hide
     * Used by AudioManager to access binder to be registered/unregistered inside MediaFocusControl
     * @return
     */
    protected RcDisplay getRcDisplay() {
        return mRcd;
    }
}
