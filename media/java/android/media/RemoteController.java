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

import android.app.ActivityManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionLegacyHelper;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * The RemoteController class is used to control media playback, display and update media metadata
 * and playback status, published by applications using the {@link RemoteControlClient} class.
 * <p>
 * A RemoteController shall be registered through
 * {@link AudioManager#registerRemoteController(RemoteController)} in order for the system to send
 * media event updates to the {@link OnClientUpdateListener} listener set in the class constructor.
 * Implement the methods of the interface to receive the information published by the active
 * {@link RemoteControlClient} instances.
 * <br>By default an {@link OnClientUpdateListener} implementation will not receive bitmaps for
 * album art. Use {@link #setArtworkConfiguration(int, int)} to receive images as well.
 * <p>
 * Registration requires the {@link OnClientUpdateListener} listener to be one of the enabled
 * notification listeners (see {@link android.service.notification.NotificationListenerService}).
 *
 * @deprecated Use {@link MediaController} instead.
 */
@Deprecated public final class RemoteController
{
    private final static int MAX_BITMAP_DIMENSION = 512;
    private final static String TAG = "RemoteController";
    private final static boolean DEBUG = false;
    private final static Object mInfoLock = new Object();
    private final Context mContext;
    private final int mMaxBitmapDimension;
    private MetadataEditor mMetadataEditor;

    private MediaSessionManager mSessionManager;
    private MediaSessionManager.OnActiveSessionsChangedListener mSessionListener;
    private MediaController.Callback mSessionCb = new MediaControllerCallback();

    /**
     * Synchronized on mInfoLock
     */
    private boolean mIsRegistered = false;
    private OnClientUpdateListener mOnClientUpdateListener;
    private PlaybackInfo mLastPlaybackInfo;
    private int mArtworkWidth = -1;
    private int mArtworkHeight = -1;
    private boolean mEnabled = true;
    // synchronized on mInfoLock, for USE_SESSION apis.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private MediaController mCurrentSession;

    /**
     * Class constructor.
     * @param context the {@link Context}, must be non-null.
     * @param updateListener the listener to be called whenever new client information is available,
     *     must be non-null.
     * @throws IllegalArgumentException
     */
    public RemoteController(Context context, OnClientUpdateListener updateListener)
            throws IllegalArgumentException {
        this(context, updateListener, null);
    }

    /**
     * Class constructor.
     * @param context the {@link Context}, must be non-null.
     * @param updateListener the listener to be called whenever new client information is available,
     *     must be non-null.
     * @param looper the {@link Looper} on which to run the event loop,
     *     or null to use the current thread's looper.
     * @throws java.lang.IllegalArgumentException
     */
    public RemoteController(Context context, OnClientUpdateListener updateListener, Looper looper)
            throws IllegalArgumentException {
        if (context == null) {
            throw new IllegalArgumentException("Invalid null Context");
        }
        if (updateListener == null) {
            throw new IllegalArgumentException("Invalid null OnClientUpdateListener");
        }
        if (looper != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            Looper l = Looper.myLooper();
            if (l != null) {
                mEventHandler = new EventHandler(this, l);
            } else {
                throw new IllegalArgumentException("Calling thread not associated with a looper");
            }
        }
        mOnClientUpdateListener = updateListener;
        mContext = context;
        mSessionManager = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        mSessionListener = new TopTransportSessionListener();

        if (ActivityManager.isLowRamDeviceStatic()) {
            mMaxBitmapDimension = MAX_BITMAP_DIMENSION;
        } else {
            final DisplayMetrics dm = context.getResources().getDisplayMetrics();
            mMaxBitmapDimension = Math.max(dm.widthPixels, dm.heightPixels);
        }
    }


    /**
     * Interface definition for the callbacks to be invoked whenever media events, metadata
     * and playback status are available.
     */
    public interface OnClientUpdateListener {
        /**
         * Called whenever all information, previously received through the other
         * methods of the listener, is no longer valid and is about to be refreshed.
         * This is typically called whenever a new {@link RemoteControlClient} has been selected
         * by the system to have its media information published.
         * @param clearing true if there is no selected RemoteControlClient and no information
         *     is available.
         */
        public void onClientChange(boolean clearing);

        /**
         * Called whenever the playback state has changed.
         * It is called when no information is known about the playback progress in the media and
         * the playback speed.
         * @param state one of the playback states authorized
         *     in {@link RemoteControlClient#setPlaybackState(int)}.
         */
        public void onClientPlaybackStateUpdate(int state);
        /**
         * Called whenever the playback state has changed, and playback position
         * and speed are known.
         * @param state one of the playback states authorized
         *     in {@link RemoteControlClient#setPlaybackState(int)}.
         * @param stateChangeTimeMs the system time at which the state change was reported,
         *     expressed in ms. Based on {@link android.os.SystemClock#elapsedRealtime()}.
         * @param currentPosMs a positive value for the current media playback position expressed
         *     in ms, a negative value if the position is temporarily unknown.
         * @param speed  a value expressed as a ratio of 1x playback: 1.0f is normal playback,
         *    2.0f is 2x, 0.5f is half-speed, -2.0f is rewind at 2x speed. 0.0f means nothing is
         *    playing (e.g. when state is {@link RemoteControlClient#PLAYSTATE_ERROR}).
         */
        public void onClientPlaybackStateUpdate(int state, long stateChangeTimeMs,
                long currentPosMs, float speed);
        /**
         * Called whenever the transport control flags have changed.
         * @param transportControlFlags one of the flags authorized
         *     in {@link RemoteControlClient#setTransportControlFlags(int)}.
         */
        public void onClientTransportControlUpdate(int transportControlFlags);
        /**
         * Called whenever new metadata is available.
         * See the {@link MediaMetadataEditor#putLong(int, long)},
         *  {@link MediaMetadataEditor#putString(int, String)},
         *  {@link MediaMetadataEditor#putBitmap(int, Bitmap)}, and
         *  {@link MediaMetadataEditor#putObject(int, Object)} methods for the various keys that
         *  can be queried.
         * @param metadataEditor the container of the new metadata.
         */
        public void onClientMetadataUpdate(MetadataEditor metadataEditor);
    };

    /**
     * Return the estimated playback position of the current media track or a negative value
     * if not available.
     *
     * <p>The value returned is estimated by the current process and may not be perfect.
     * The time returned by this method is calculated from the last state change time based
     * on the current play position at that time and the last known playback speed.
     * An application may call {@link #setSynchronizationMode(int)} to apply
     * a synchronization policy that will periodically re-sync the estimated position
     * with the RemoteControlClient.</p>
     *
     * @return the current estimated playback position in milliseconds or a negative value
     *         if not available
     *
     * @see OnClientUpdateListener#onClientPlaybackStateUpdate(int, long, long, float)
     */
    public long getEstimatedMediaPosition() {
        synchronized (mInfoLock) {
            if (mCurrentSession != null) {
                PlaybackState state = mCurrentSession.getPlaybackState();
                if (state != null) {
                    return state.getPosition();
                }
            }
        }
        return -1;
    }

    /**
     * Send a simulated key event for a media button to be received by the current client. To
     * simulate a key press, you must first send a KeyEvent built with a {@link
     * KeyEvent#ACTION_DOWN} action, then another event with the {@link KeyEvent#ACTION_UP} action.
     *
     * <p>The key event will be sent to the registered receiver (see {@link
     * AudioManager#registerMediaButtonEventReceiver(PendingIntent)}) whose associated {@link
     * RemoteControlClient}'s metadata and playback state is published (there may be none under some
     * circumstances).
     *
     * @param keyEvent a media session {@link KeyEvent}, as defined by {@link
     *     KeyEvent#isMediaSessionKey}.
     * @return true if the event was successfully sent, false otherwise.
     * @throws IllegalArgumentException If the provided {@link KeyEvent} is not a media session key,
     *     as defined by {@link KeyEvent#isMediaSessionKey}.
     */
    public boolean sendMediaKeyEvent(KeyEvent keyEvent) throws IllegalArgumentException {
        if (!KeyEvent.isMediaSessionKey(keyEvent.getKeyCode())) {
            throw new IllegalArgumentException("not a media key event");
        }
        synchronized (mInfoLock) {
            if (mCurrentSession != null) {
                return mCurrentSession.dispatchMediaButtonEvent(keyEvent);
            }
            return false;
        }
    }


    /**
     * Sets the new playback position.
     * This method can only be called on a registered RemoteController.
     * @param timeMs a 0 or positive value for the new playback position, expressed in ms.
     * @return true if the command to set the playback position was successfully sent.
     * @throws IllegalArgumentException
     */
    public boolean seekTo(long timeMs) throws IllegalArgumentException {
        if (!mEnabled) {
            Log.e(TAG, "Cannot use seekTo() from a disabled RemoteController");
            return false;
        }
        if (timeMs < 0) {
            throw new IllegalArgumentException("illegal negative time value");
        }
        synchronized (mInfoLock) {
            if (mCurrentSession != null) {
                mCurrentSession.getTransportControls().seekTo(timeMs);
            }
        }
        return true;
    }


    /**
     * @hide
     * @param wantBitmap
     * @param width
     * @param height
     * @return true if successful
     * @throws IllegalArgumentException
     */
    @UnsupportedAppUsage
    public boolean setArtworkConfiguration(boolean wantBitmap, int width, int height)
            throws IllegalArgumentException {
        synchronized (mInfoLock) {
            if (wantBitmap) {
                if ((width > 0) && (height > 0)) {
                    if (width > mMaxBitmapDimension) { width = mMaxBitmapDimension; }
                    if (height > mMaxBitmapDimension) { height = mMaxBitmapDimension; }
                    mArtworkWidth = width;
                    mArtworkHeight = height;
                } else {
                    throw new IllegalArgumentException("Invalid dimensions");
                }
            } else {
                mArtworkWidth = -1;
                mArtworkHeight = -1;
            }
        }
        return true;
    }

    /**
     * Set the maximum artwork image dimensions to be received in the metadata.
     * No bitmaps will be received unless this has been specified.
     * @param width the maximum width in pixels
     * @param height  the maximum height in pixels
     * @return true if the artwork dimension was successfully set.
     * @throws IllegalArgumentException
     */
    public boolean setArtworkConfiguration(int width, int height) throws IllegalArgumentException {
        return setArtworkConfiguration(true, width, height);
    }

    /**
     * Prevents this RemoteController from receiving artwork images.
     * @return true if receiving artwork images was successfully disabled.
     */
    public boolean clearArtworkConfiguration() {
        return setArtworkConfiguration(false, -1, -1);
    }


    /**
     * Default playback position synchronization mode where the RemoteControlClient is not
     * asked regularly for its playback position to see if it has drifted from the estimated
     * position.
     */
    public static final int POSITION_SYNCHRONIZATION_NONE = 0;

    /**
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
     * Set the playback position synchronization mode.
     * Must be called on a registered RemoteController.
     * @param sync {@link #POSITION_SYNCHRONIZATION_NONE} or {@link #POSITION_SYNCHRONIZATION_CHECK}
     * @return true if the synchronization mode was successfully set.
     * @throws IllegalArgumentException
     */
    public boolean setSynchronizationMode(int sync) throws IllegalArgumentException {
        if ((sync != POSITION_SYNCHRONIZATION_NONE) && (sync != POSITION_SYNCHRONIZATION_CHECK)) {
            throw new IllegalArgumentException("Unknown synchronization mode " + sync);
        }
        if (!mIsRegistered) {
            Log.e(TAG, "Cannot set synchronization mode on an unregistered RemoteController");
            return false;
        }
        // deprecated, no-op
        return true;
    }


    /**
     * Creates a {@link MetadataEditor} for updating metadata values of the editable keys of
     * the current {@link RemoteControlClient}.
     * This method can only be called on a registered RemoteController.
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
     * A class to read the metadata published by a {@link RemoteControlClient}, or send a
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

            mEditorArtwork = (Bitmap) metadata.getParcelable(
                    String.valueOf(MediaMetadataEditor.BITMAP_KEY_ARTWORK), android.graphics.Bitmap.class);
            if (mEditorArtwork != null) {
                cleanupBitmapFromBundle(MediaMetadataEditor.BITMAP_KEY_ARTWORK);
            }

            mMetadataChanged = true;
            mArtworkChanged = true;
            mApplied = false;
        }

        private void cleanupBitmapFromBundle(int key) {
            if (METADATA_KEYS_TYPE.get(key, METADATA_TYPE_INVALID) == METADATA_TYPE_BITMAP) {
                mEditorMetadata.remove(String.valueOf(key));
            }
        }

        /**
         * Applies all of the metadata changes that have been set since the MediaMetadataEditor
         * instance was created with {@link RemoteController#editMetadata()}
         * or since {@link #clear()} was called.
         */
        public synchronized void apply() {
            // "applying" a metadata bundle in RemoteController is only for sending edited
            // key values back to the RemoteControlClient, so here we only care about the only
            // editable key we support: RATING_KEY_BY_USER
            if (!mMetadataChanged) {
                return;
            }
            synchronized (mInfoLock) {
                if (mCurrentSession != null) {
                    if (mEditorMetadata.containsKey(
                            String.valueOf(MediaMetadataEditor.RATING_KEY_BY_USER))) {
                        Rating rating = (Rating) getObject(
                                MediaMetadataEditor.RATING_KEY_BY_USER, null);
                        if (rating != null) {
                            mCurrentSession.getTransportControls().setRating(rating);
                        }
                    }
                }
            }
            // NOT setting mApplied to true as this type of MetadataEditor will be applied
            // multiple times, whenever the user of a RemoteController needs to change the
            // metadata (e.g. user changes the rating of a song more than once during playback)
            mApplied = false;
        }

    }

    /**
     * This receives updates when the current session changes. This is
     * registered to receive the updates on the handler thread so it can call
     * directly into the appropriate methods.
     */
    private class MediaControllerCallback extends MediaController.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            onNewPlaybackState(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            onNewMediaMetadata(metadata);
        }
    }

    /**
     * Listens for changes to the active session stack and replaces the
     * currently tracked session if it has changed.
     */
    private class TopTransportSessionListener implements
            MediaSessionManager.OnActiveSessionsChangedListener {

        @Override
        public void onActiveSessionsChanged(List<MediaController> controllers) {
            int size = controllers.size();
            for (int i = 0; i < size; i++) {
                MediaController controller = controllers.get(i);
                long flags = controller.getFlags();
                // We only care about sessions that handle transport controls,
                // which will be true for apps using RCC
                if ((flags & MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS) != 0) {
                    updateController(controller);
                    return;
                }
            }
            updateController(null);
        }

    }

    //==================================================
    // Event handling
    private final EventHandler mEventHandler;
    private final static int MSG_CLIENT_CHANGE      = 0;
    private final static int MSG_NEW_PLAYBACK_STATE = 1;
    private final static int MSG_NEW_MEDIA_METADATA = 2;

    private class EventHandler extends Handler {

        public EventHandler(RemoteController rc, Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_CLIENT_CHANGE:
                    onClientChange(msg.arg2 == 1);
                    break;
                case MSG_NEW_PLAYBACK_STATE:
                    onNewPlaybackState((PlaybackState) msg.obj);
                    break;
                case MSG_NEW_MEDIA_METADATA:
                    onNewMediaMetadata((MediaMetadata) msg.obj);
                    break;
                default:
                    Log.e(TAG, "unknown event " + msg.what);
            }
        }
    }

    /**
     * @hide
     */
    void startListeningToSessions() {
        final ComponentName listenerComponent = new ComponentName(mContext,
                mOnClientUpdateListener.getClass());
        Handler handler = null;
        if (Looper.myLooper() == null) {
            handler = new Handler(Looper.getMainLooper());
        }
        mSessionManager.addOnActiveSessionsChangedListener(mSessionListener, listenerComponent,
                handler);
        mSessionListener.onActiveSessionsChanged(mSessionManager
                .getActiveSessions(listenerComponent));
        if (DEBUG) {
            Log.d(TAG, "Registered session listener with component " + listenerComponent
                    + " for user " + UserHandle.myUserId());
        }
    }

    /**
     * @hide
     */
    void stopListeningToSessions() {
        mSessionManager.removeOnActiveSessionsChangedListener(mSessionListener);
        if (DEBUG) {
            Log.d(TAG, "Unregistered session listener for user "
                    + UserHandle.myUserId());
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

    private void onClientChange(boolean clearing) {
        final OnClientUpdateListener l;
        synchronized(mInfoLock) {
            l = mOnClientUpdateListener;
            mMetadataEditor = null;
        }
        if (l != null) {
            l.onClientChange(clearing);
        }
    }

    private void updateController(MediaController controller) {
        if (DEBUG) {
            Log.d(TAG, "Updating controller to " + controller + " previous controller is "
                    + mCurrentSession);
        }
        synchronized (mInfoLock) {
            if (controller == null) {
                if (mCurrentSession != null) {
                    mCurrentSession.unregisterCallback(mSessionCb);
                    mCurrentSession = null;
                    sendMsg(mEventHandler, MSG_CLIENT_CHANGE, SENDMSG_REPLACE,
                            0 /* arg1 ignored */, 1 /* clearing */, null /* obj */, 0 /* delay */);
                }
            } else if (mCurrentSession == null
                    || !controller.getSessionToken()
                            .equals(mCurrentSession.getSessionToken())) {
                if (mCurrentSession != null) {
                    mCurrentSession.unregisterCallback(mSessionCb);
                }
                sendMsg(mEventHandler, MSG_CLIENT_CHANGE, SENDMSG_REPLACE,
                        0 /* arg1 ignored */, 0 /* clearing */, null /* obj */, 0 /* delay */);
                mCurrentSession = controller;
                mCurrentSession.registerCallback(mSessionCb, mEventHandler);

                PlaybackState state = controller.getPlaybackState();
                sendMsg(mEventHandler, MSG_NEW_PLAYBACK_STATE, SENDMSG_REPLACE,
                        0 /* arg1 ignored */, 0 /* arg2 ignored */, state /* obj */, 0 /* delay */);

                MediaMetadata metadata = controller.getMetadata();
                sendMsg(mEventHandler, MSG_NEW_MEDIA_METADATA, SENDMSG_REPLACE,
                        0 /* arg1 ignored */, 0 /* arg2 ignored*/, metadata /* obj */, 0 /*delay*/);
            }
            // else same controller, no need to update
        }
    }

    private void onNewPlaybackState(PlaybackState state) {
        final OnClientUpdateListener l;
        synchronized (mInfoLock) {
            l = this.mOnClientUpdateListener;
        }
        if (l != null) {
            int playstate = state == null ? RemoteControlClient.PLAYSTATE_NONE
                    : RemoteControlClient.getRccStateFromState(state.getState());
            if (state == null || state.getPosition() == PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
                l.onClientPlaybackStateUpdate(playstate);
            } else {
                l.onClientPlaybackStateUpdate(playstate, state.getLastPositionUpdateTime(),
                        state.getPosition(), state.getPlaybackSpeed());
            }
            if (state != null) {
                l.onClientTransportControlUpdate(
                        RemoteControlClient.getRccControlFlagsFromActions(state.getActions()));
            }
        }
    }

    private void onNewMediaMetadata(MediaMetadata metadata) {
        if (metadata == null) {
            // RemoteController only handles non-null metadata
            return;
        }
        final OnClientUpdateListener l;
        final MetadataEditor metadataEditor;
        // prepare the received Bundle to be used inside a MetadataEditor
        synchronized(mInfoLock) {
            l = mOnClientUpdateListener;
            boolean canRate = mCurrentSession != null
                    && mCurrentSession.getRatingType() != Rating.RATING_NONE;
            long editableKeys = canRate ? MediaMetadataEditor.RATING_KEY_BY_USER : 0;
            Bundle legacyMetadata = MediaSessionLegacyHelper.getOldMetadata(metadata,
                    mArtworkWidth, mArtworkHeight);
            mMetadataEditor = new MetadataEditor(legacyMetadata, editableKeys);
            metadataEditor = mMetadataEditor;
        }
        if (l != null) {
            l.onClientMetadataUpdate(metadataEditor);
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
     * Used by AudioManager to access user listener receiving the client update notifications
     * @return
     */
    @UnsupportedAppUsage
    OnClientUpdateListener getUpdateListener() {
        return mOnClientUpdateListener;
    }
}
