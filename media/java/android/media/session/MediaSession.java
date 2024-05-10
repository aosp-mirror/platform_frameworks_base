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

package android.media.session;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.media.session.MediaSessionManager.RemoteUserInfo;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;

/**
 * Allows interaction with media controllers, volume keys, media buttons, and
 * transport controls.
 * <p>
 * A MediaSession should be created when an app wants to publish media playback
 * information or handle media keys. In general an app only needs one session
 * for all playback, though multiple sessions can be created to provide finer
 * grain controls of media.
 * <p>
 * Once a session is created the owner of the session may pass its
 * {@link #getSessionToken() session token} to other processes to allow them to
 * create a {@link MediaController} to interact with the session.
 * <p>
 * To receive commands, media keys, and other events a {@link Callback} must be
 * set with {@link #setCallback(Callback)} and {@link #setActive(boolean)
 * setActive(true)} must be called.
 * <p>
 * When an app is finished performing playback it must call {@link #release()}
 * to clean up the session and notify any controllers.
 * <p>
 * MediaSession objects are thread safe.
 */
public final class MediaSession {
    static final String TAG = "MediaSession";

    /**
     * Set this flag on the session to indicate that it can handle media button
     * events.
     * @deprecated This flag is no longer used. All media sessions are expected to handle media
     * button events now.
     */
    @Deprecated
    public static final int FLAG_HANDLES_MEDIA_BUTTONS = 1 << 0;

    /**
     * Set this flag on the session to indicate that it handles transport
     * control commands through its {@link Callback}.
     * @deprecated This flag is no longer used. All media sessions are expected to handle transport
     * controls now.
     */
    @Deprecated
    public static final int FLAG_HANDLES_TRANSPORT_CONTROLS = 1 << 1;

    /**
     * System only flag for a session that needs to have priority over all other
     * sessions. This flag ensures this session will receive media button events
     * regardless of the current ordering in the system.
     * If there are two or more sessions with this flag, the last session that sets this flag
     * will be the global priority session.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int FLAG_EXCLUSIVE_GLOBAL_PRIORITY = 1 << 16;

    /**
     * @hide
     */
    public static final int INVALID_UID = -1;

    /**
     * @hide
     */
    public static final int INVALID_PID = -1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            FLAG_HANDLES_MEDIA_BUTTONS,
            FLAG_HANDLES_TRANSPORT_CONTROLS,
            FLAG_EXCLUSIVE_GLOBAL_PRIORITY })
    public @interface SessionFlags { }

    private final Object mLock = new Object();
    private Context mContext;
    private final int mMaxBitmapSize;

    private final Token mSessionToken;
    private final MediaController mController;
    private final ISession mBinder;
    private final CallbackStub mCbStub;

    // Do not change the name of mCallback. Support lib accesses this by using reflection.
    @UnsupportedAppUsage
    private CallbackMessageHandler mCallback;
    private VolumeProvider mVolumeProvider;
    private PlaybackState mPlaybackState;

    private boolean mActive = false;

    /**
     * Creates a new session. The session will automatically be registered with
     * the system but will not be published until {@link #setActive(boolean)
     * setActive(true)} is called. You must call {@link #release()} when
     * finished with the session.
     * <p>
     * Note that {@link RuntimeException} will be thrown if an app creates too many sessions.
     *
     * @param context The context to use to create the session.
     * @param tag A short name for debugging purposes.
     */
    public MediaSession(@NonNull Context context, @NonNull String tag) {
        this(context, tag, null);
    }

    /**
     * Creates a new session. The session will automatically be registered with
     * the system but will not be published until {@link #setActive(boolean)
     * setActive(true)} is called. You must call {@link #release()} when
     * finished with the session.
     * <p>
     * The {@code sessionInfo} can include additional unchanging information about this session.
     * For example, it can include the version of the application, or the list of the custom
     * commands that this session supports.
     * <p>
     * Note that {@link RuntimeException} will be thrown if an app creates too many sessions.
     *
     * @param context The context to use to create the session.
     * @param tag A short name for debugging purposes.
     * @param sessionInfo A bundle for additional information about this session.
     *                    Controllers can get this information by calling
     *                    {@link MediaController#getSessionInfo()}.
     *                    An {@link IllegalArgumentException} will be thrown if this contains
     *                    any non-framework Parcelable objects.
     */
    public MediaSession(@NonNull Context context, @NonNull String tag,
            @Nullable Bundle sessionInfo) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null.");
        }
        if (TextUtils.isEmpty(tag)) {
            throw new IllegalArgumentException("tag cannot be null or empty");
        }
        if (hasCustomParcelable(sessionInfo)) {
            throw new IllegalArgumentException("sessionInfo shouldn't contain any custom "
                    + "parcelables");
        }

        mContext = context;
        mMaxBitmapSize = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.config_mediaMetadataBitmapMaxSize);
        mCbStub = new CallbackStub(this);
        MediaSessionManager manager = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            mBinder = manager.createSession(mCbStub, tag, sessionInfo);
            mSessionToken = new Token(Process.myUid(), mBinder.getController());
            mController = new MediaController(context, mSessionToken);
        } catch (RemoteException e) {
            throw new RuntimeException("Remote error creating session.", e);
        }
    }

    /**
     * Set the callback to receive updates for the MediaSession. This includes
     * media button events and transport controls. The caller's thread will be
     * used to post updates.
     * <p>
     * Set the callback to null to stop receiving updates.
     *
     * @param callback The callback object
     */
    public void setCallback(@Nullable Callback callback) {
        setCallback(callback, null);
    }

    /**
     * Set the callback to receive updates for the MediaSession. This includes
     * media button events and transport controls.
     * <p>
     * Set the callback to null to stop receiving updates.
     *
     * @param callback The callback to receive updates on.
     * @param handler The handler that events should be posted on.
     */
    public void setCallback(@Nullable Callback callback, @Nullable Handler handler) {
        synchronized (mLock) {
            if (mCallback != null) {
                // We're updating the callback, clear the session from the old one.
                mCallback.mCallback.mSession = null;
                mCallback.removeCallbacksAndMessages(null);
            }
            if (callback == null) {
                mCallback = null;
                return;
            }
            Looper looper = handler != null ? handler.getLooper() : Looper.myLooper();
            callback.mSession = this;
            CallbackMessageHandler msgHandler = new CallbackMessageHandler(looper, callback);
            mCallback = msgHandler;
        }
    }

    /**
     * Set an intent for launching UI for this Session. This can be used as a
     * quick link to an ongoing media screen. The intent should be for an
     * activity that may be started using {@link Activity#startActivity(Intent)}.
     *
     * @param pi The intent to launch to show UI for this Session.
     */
    public void setSessionActivity(@Nullable PendingIntent pi) {
        try {
            mBinder.setLaunchPendingIntent(pi);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in setLaunchPendingIntent.", e);
        }
    }

    /**
     * Set a pending intent for your media button receiver to allow restarting playback after the
     * session has been stopped.
     *
     * <p>If your app is started in this way an {@link Intent#ACTION_MEDIA_BUTTON} intent will be
     * sent via the pending intent.
     *
     * <p>The provided {@link PendingIntent} must not target an activity. On apps targeting Android
     * V and above, passing an activity pending intent to this method causes an {@link
     * IllegalArgumentException}. On apps targeting Android U and below, passing an activity pending
     * intent causes the call to be ignored. Refer to this <a
     * href="https://developer.android.com/guide/components/activities/background-starts">guide</a>
     * for more information.
     *
     * <p>The pending intent is recommended to be explicit to follow the security recommendation of
     * {@link PendingIntent#getService}.
     *
     * @param mbr The {@link PendingIntent} to send the media button event to.
     * @deprecated Use {@link #setMediaButtonBroadcastReceiver(ComponentName)} instead.
     * @throws IllegalArgumentException if the pending intent targets an activity on apps targeting
     * Android V and above.
     */
    @Deprecated
    public void setMediaButtonReceiver(@Nullable PendingIntent mbr) {
        try {
            mBinder.setMediaButtonReceiver(mbr);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the component name of the manifest-declared {@link android.content.BroadcastReceiver}
     * class that should receive media buttons. This allows restarting playback after the session
     * has been stopped. If your app is started in this way an {@link Intent#ACTION_MEDIA_BUTTON}
     * intent will be sent to the broadcast receiver. On apps targeting Android U and above, this
     * will throw an {@link IllegalArgumentException} if the provided {@link ComponentName} does not
     * resolve to an existing {@link android.content.BroadcastReceiver broadcast receiver}.
     *
     * <p>Note: The given {@link android.content.BroadcastReceiver} should belong to the same
     * package as the context that was given when creating {@link MediaSession}.
     *
     * @param broadcastReceiver the component name of the BroadcastReceiver class
     * @throws IllegalArgumentException if {@code broadcastReceiver} does not exist on apps
     *     targeting Android U and above
     */
    public void setMediaButtonBroadcastReceiver(@Nullable ComponentName broadcastReceiver) {
        try {
            if (broadcastReceiver != null) {
                if (!TextUtils.equals(broadcastReceiver.getPackageName(),
                        mContext.getPackageName())) {
                    throw new IllegalArgumentException("broadcastReceiver should belong to the same"
                            + " package as the context given when creating MediaSession.");
                }
            }
            mBinder.setMediaButtonBroadcastReceiver(broadcastReceiver);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Set any flags for the session.
     *
     * @param flags The flags to set for this session.
     */
    public void setFlags(@SessionFlags int flags) {
        try {
            mBinder.setFlags(flags);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in setFlags.", e);
        }
    }

    /**
     * Set the attributes for this session's audio. This will affect the
     * system's volume handling for this session. If
     * {@link #setPlaybackToRemote} was previously called it will stop receiving
     * volume commands and the system will begin sending volume changes to the
     * appropriate stream.
     * <p>
     * By default sessions use attributes for media.
     *
     * @param attributes The {@link AudioAttributes} for this session's audio.
     */
    public void setPlaybackToLocal(AudioAttributes attributes) {
        if (attributes == null) {
            throw new IllegalArgumentException("Attributes cannot be null for local playback.");
        }
        try {
            mBinder.setPlaybackToLocal(attributes);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in setPlaybackToLocal.", e);
        }
    }

    /**
     * Configure this session to use remote volume handling. This must be called
     * to receive volume button events, otherwise the system will adjust the
     * appropriate stream volume for this session. If
     * {@link #setPlaybackToLocal} was previously called the system will stop
     * handling volume changes for this session and pass them to the volume
     * provider instead.
     *
     * @param volumeProvider The provider that will handle volume changes. May
     *            not be null.
     */
    public void setPlaybackToRemote(@NonNull VolumeProvider volumeProvider) {
        if (volumeProvider == null) {
            throw new IllegalArgumentException("volumeProvider may not be null!");
        }
        synchronized (mLock) {
            mVolumeProvider = volumeProvider;
        }
        volumeProvider.setCallback(new VolumeProvider.Callback() {
            @Override
            public void onVolumeChanged(VolumeProvider volumeProvider) {
                notifyRemoteVolumeChanged(volumeProvider);
            }
        });

        try {
            mBinder.setPlaybackToRemote(volumeProvider.getVolumeControl(),
                    volumeProvider.getMaxVolume(), volumeProvider.getVolumeControlId());
            mBinder.setCurrentVolume(volumeProvider.getCurrentVolume());
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in setPlaybackToRemote.", e);
        }
    }

    /**
     * Set if this session is currently active and ready to receive commands. If
     * set to false your session's controller may not be discoverable. You must
     * set the session to active before it can start receiving media button
     * events or transport commands.
     *
     * @param active Whether this session is active or not.
     */
    public void setActive(boolean active) {
        if (mActive == active) {
            return;
        }
        try {
            mBinder.setActive(active);
            mActive = active;
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in setActive.", e);
        }
    }

    /**
     * Get the current active state of this session.
     *
     * @return True if the session is active, false otherwise.
     */
    public boolean isActive() {
        return mActive;
    }

    /**
     * Send a proprietary event to all MediaControllers listening to this
     * Session. It's up to the Controller/Session owner to determine the meaning
     * of any events.
     *
     * @param event The name of the event to send
     * @param extras Any extras included with the event
     */
    public void sendSessionEvent(@NonNull String event, @Nullable Bundle extras) {
        if (TextUtils.isEmpty(event)) {
            throw new IllegalArgumentException("event cannot be null or empty");
        }
        try {
            mBinder.sendEvent(event, extras);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error sending event", e);
        }
    }

    /**
     * This must be called when an app has finished performing playback. If
     * playback is expected to start again shortly the session can be left open,
     * but it must be released if your activity or service is being destroyed.
     */
    public void release() {
        setCallback(null);
        try {
            mBinder.destroySession();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error releasing session: ", e);
        }
    }

    /**
     * Retrieve a token object that can be used by apps to create a
     * {@link MediaController} for interacting with this session. The owner of
     * the session is responsible for deciding how to distribute these tokens.
     *
     * @return A token that can be used to create a MediaController for this
     *         session
     */
    public @NonNull Token getSessionToken() {
        return mSessionToken;
    }

    /**
     * Get a controller for this session. This is a convenience method to avoid
     * having to cache your own controller in process.
     *
     * @return A controller for this session.
     */
    public @NonNull MediaController getController() {
        return mController;
    }

    /**
     * Update the current playback state.
     *
     * @param state The current state of playback
     */
    public void setPlaybackState(@Nullable PlaybackState state) {
        mPlaybackState = state;
        try {
            mBinder.setPlaybackState(state);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Dead object in setPlaybackState.", e);
        }
    }

    /**
     * Update the current metadata. New metadata can be created using
     * {@link android.media.MediaMetadata.Builder}. This operation may take time proportional to
     * the size of the bitmap to replace large bitmaps with a scaled down copy.
     *
     * @param metadata The new metadata
     * @see android.media.MediaMetadata.Builder#putBitmap
     */
    public void setMetadata(@Nullable MediaMetadata metadata) {
        long duration = -1;
        int fields = 0;
        MediaDescription description = null;
        if (metadata != null) {
            metadata = new MediaMetadata.Builder(metadata)
                    .setBitmapDimensionLimit(mMaxBitmapSize)
                    .build();
            if (metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
                duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            }
            fields = metadata.size();
            description = metadata.getDescription();
        }
        String metadataDescription = "size=" + fields + ", description=" + description;

        try {
            mBinder.setMetadata(metadata, duration, metadataDescription);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Dead object in setPlaybackState.", e);
        }
    }

    /**
     * Update the list of items in the play queue. It is an ordered list and
     * should contain the current item, and previous or upcoming items if they
     * exist. Specify null if there is no current play queue.
     * <p>
     * The queue should be of reasonable size. If the play queue is unbounded
     * within your app, it is better to send a reasonable amount in a sliding
     * window instead.
     *
     * @param queue A list of items in the play queue.
     */
    public void setQueue(@Nullable List<QueueItem> queue) {
        try {
            if (queue == null) {
                mBinder.resetQueue();
            } else {
                IBinder binder = mBinder.getBinderForSetQueue();
                ParcelableListBinder.send(binder, queue);
            }
        } catch (RemoteException e) {
            Log.wtf("Dead object in setQueue.", e);
        }
    }

    /**
     * Set the title of the play queue. The UI should display this title along
     * with the play queue itself.
     * e.g. "Play Queue", "Now Playing", or an album name.
     *
     * @param title The title of the play queue.
     */
    public void setQueueTitle(@Nullable CharSequence title) {
        try {
            mBinder.setQueueTitle(title);
        } catch (RemoteException e) {
            Log.wtf("Dead object in setQueueTitle.", e);
        }
    }

    /**
     * Set the style of rating used by this session. Apps trying to set the
     * rating should use this style. Must be one of the following:
     * <ul>
     * <li>{@link Rating#RATING_NONE}</li>
     * <li>{@link Rating#RATING_3_STARS}</li>
     * <li>{@link Rating#RATING_4_STARS}</li>
     * <li>{@link Rating#RATING_5_STARS}</li>
     * <li>{@link Rating#RATING_HEART}</li>
     * <li>{@link Rating#RATING_PERCENTAGE}</li>
     * <li>{@link Rating#RATING_THUMB_UP_DOWN}</li>
     * </ul>
     */
    public void setRatingType(@Rating.Style int type) {
        try {
            mBinder.setRatingType(type);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in setRatingType.", e);
        }
    }

    /**
     * Set some extras that can be associated with the {@link MediaSession}. No assumptions should
     * be made as to how a {@link MediaController} will handle these extras.
     * Keys should be fully qualified (e.g. com.example.MY_EXTRA) to avoid conflicts.
     *
     * @param extras The extras associated with the {@link MediaSession}.
     */
    public void setExtras(@Nullable Bundle extras) {
        try {
            mBinder.setExtras(extras);
        } catch (RemoteException e) {
            Log.wtf("Dead object in setExtras.", e);
        }
    }

    /**
     * Gets the controller information who sent the current request.
     * <p>
     * Note: This is only valid while in a request callback, such as {@link Callback#onPlay}.
     *
     * @throws IllegalStateException If this method is called outside of {@link Callback} methods.
     * @see MediaSessionManager#isTrustedForMediaControl(RemoteUserInfo)
     */
    public final @NonNull RemoteUserInfo getCurrentControllerInfo() {
        if (mCallback == null || mCallback.mCurrentControllerInfo == null) {
            throw new IllegalStateException(
                    "This should be called inside of MediaSession.Callback methods");
        }
        return mCallback.mCurrentControllerInfo;
    }

    /**
     * Notify the system that the remote volume changed.
     *
     * @param provider The provider that is handling volume changes.
     * @hide
     */
    public void notifyRemoteVolumeChanged(VolumeProvider provider) {
        synchronized (mLock) {
            if (provider == null || provider != mVolumeProvider) {
                Log.w(TAG, "Received update from stale volume provider");
                return;
            }
        }
        try {
            mBinder.setCurrentVolume(provider.getCurrentVolume());
        } catch (RemoteException e) {
            Log.e(TAG, "Error in notifyVolumeChanged", e);
        }
    }

    /**
     * Returns the name of the package that sent the last media button, transport control, or
     * command from controllers and the system. This is only valid while in a request callback, such
     * as {@link Callback#onPlay}.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public String getCallingPackage() {
        if (mCallback != null && mCallback.mCurrentControllerInfo != null) {
            return mCallback.mCurrentControllerInfo.getPackageName();
        }
        return null;
    }

    /**
     * Returns whether the given bundle includes non-framework Parcelables.
     */
    static boolean hasCustomParcelable(@Nullable Bundle bundle) {
        if (bundle == null) {
            return false;
        }

        // Try writing the bundle to parcel, and read it with framework classloader.
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.writeBundle(bundle);
            parcel.setDataPosition(0);
            Bundle out = parcel.readBundle(null);

            for (String key : out.keySet()) {
                out.get(key);
            }
        } catch (BadParcelableException e) {
            Log.d(TAG, "Custom parcelable in bundle.", e);
            return true;
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
        return false;
    }

    void dispatchPrepare(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_PREPARE, null, null);
    }

    void dispatchPrepareFromMediaId(RemoteUserInfo caller, String mediaId, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PREPARE_MEDIA_ID, mediaId, extras);
    }

    void dispatchPrepareFromSearch(RemoteUserInfo caller, String query, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PREPARE_SEARCH, query, extras);
    }

    void dispatchPrepareFromUri(RemoteUserInfo caller, Uri uri, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PREPARE_URI, uri, extras);
    }

    void dispatchPlay(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_PLAY, null, null);
    }

    void dispatchPlayFromMediaId(RemoteUserInfo caller, String mediaId, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PLAY_MEDIA_ID, mediaId, extras);
    }

    void dispatchPlayFromSearch(RemoteUserInfo caller, String query, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PLAY_SEARCH, query, extras);
    }

    void dispatchPlayFromUri(RemoteUserInfo caller, Uri uri, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PLAY_URI, uri, extras);
    }

    void dispatchSkipToItem(RemoteUserInfo caller, long id) {
        postToCallback(caller, CallbackMessageHandler.MSG_SKIP_TO_ITEM, id, null);
    }

    void dispatchPause(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_PAUSE, null, null);
    }

    void dispatchStop(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_STOP, null, null);
    }

    void dispatchNext(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_NEXT, null, null);
    }

    void dispatchPrevious(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_PREVIOUS, null, null);
    }

    void dispatchFastForward(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_FAST_FORWARD, null, null);
    }

    void dispatchRewind(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_REWIND, null, null);
    }

    void dispatchSeekTo(RemoteUserInfo caller, long pos) {
        postToCallback(caller, CallbackMessageHandler.MSG_SEEK_TO, pos, null);
    }

    void dispatchRate(RemoteUserInfo caller, Rating rating) {
        postToCallback(caller, CallbackMessageHandler.MSG_RATE, rating, null);
    }

    void dispatchSetPlaybackSpeed(RemoteUserInfo caller, float speed) {
        postToCallback(caller, CallbackMessageHandler.MSG_SET_PLAYBACK_SPEED, speed, null);
    }

    void dispatchCustomAction(RemoteUserInfo caller, String action, Bundle args) {
        postToCallback(caller, CallbackMessageHandler.MSG_CUSTOM_ACTION, action, args);
    }

    void dispatchMediaButton(RemoteUserInfo caller, Intent mediaButtonIntent) {
        postToCallback(caller, CallbackMessageHandler.MSG_MEDIA_BUTTON, mediaButtonIntent, null);
    }

    void dispatchMediaButtonDelayed(RemoteUserInfo info, Intent mediaButtonIntent,
            long delay) {
        postToCallbackDelayed(info, CallbackMessageHandler.MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT,
                mediaButtonIntent, null, delay);
    }

    void dispatchAdjustVolume(RemoteUserInfo caller, int direction) {
        postToCallback(caller, CallbackMessageHandler.MSG_ADJUST_VOLUME, direction, null);
    }

    void dispatchSetVolumeTo(RemoteUserInfo caller, int volume) {
        postToCallback(caller, CallbackMessageHandler.MSG_SET_VOLUME, volume, null);
    }

    void dispatchCommand(RemoteUserInfo caller, String command, Bundle args,
            ResultReceiver resultCb) {
        Command cmd = new Command(command, args, resultCb);
        postToCallback(caller, CallbackMessageHandler.MSG_COMMAND, cmd, null);
    }

    void postToCallback(RemoteUserInfo caller, int what, Object obj, Bundle data) {
        postToCallbackDelayed(caller, what, obj, data, 0);
    }

    void postToCallbackDelayed(RemoteUserInfo caller, int what, Object obj, Bundle data,
            long delay) {
        synchronized (mLock) {
            if (mCallback != null) {
                mCallback.post(caller, what, obj, data, delay);
            }
        }
    }

    /**
     * Represents an ongoing session. This may be passed to apps by the session
     * owner to allow them to create a {@link MediaController} to communicate with
     * the session.
     */
    public static final class Token implements Parcelable {

        private final int mUid;
        private final ISessionController mBinder;

        /**
         * @hide
         */
        public Token(int uid, ISessionController binder) {
            mUid = uid;
            mBinder = binder;
        }

        Token(Parcel in) {
            mUid = in.readInt();
            mBinder = ISessionController.Stub.asInterface(in.readStrongBinder());
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mUid);
            dest.writeStrongBinder(mBinder.asBinder());
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = mUid;
            result = prime * result + (mBinder == null ? 0 : mBinder.asBinder().hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Token other = (Token) obj;
            if (mUid != other.mUid) {
                return false;
            }
            if (mBinder == null || other.mBinder == null) {
                return mBinder == other.mBinder;
            }
            return Objects.equals(mBinder.asBinder(), other.mBinder.asBinder());
        }

        /**
         * Gets the UID of the application that created the media session.
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        public int getUid() {
            return mUid;
        }

        /**
         * Gets the controller binder in this token.
         * @hide
         */
        public ISessionController getBinder() {
            return mBinder;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Token> CREATOR =
                new Parcelable.Creator<Token>() {
            @Override
            public Token createFromParcel(Parcel in) {
                return new Token(in);
            }

            @Override
            public Token[] newArray(int size) {
                return new Token[size];
            }
        };
    }

    /**
     * Receives media buttons, transport controls, and commands from controllers
     * and the system. A callback may be set using {@link #setCallback}.
     */
    public abstract static class Callback {

        private MediaSession mSession;
        private CallbackMessageHandler mHandler;
        private boolean mMediaPlayPauseKeyPending;

        public Callback() {
        }

        /**
         * Called when a controller has sent a command to this session.
         * The owner of the session may handle custom commands but is not
         * required to.
         *
         * @param command The command name.
         * @param args Optional parameters for the command, may be null.
         * @param cb A result receiver to which a result may be sent by the command, may be null.
         */
        public void onCommand(@NonNull String command, @Nullable Bundle args,
                @Nullable ResultReceiver cb) {
        }

        /**
         * Called when a media button is pressed and this session has the
         * highest priority or a controller sends a media button event to the
         * session. The default behavior will call the relevant method if the
         * action for it was set.
         * <p>
         * The intent will be of type {@link Intent#ACTION_MEDIA_BUTTON} with a
         * KeyEvent in {@link Intent#EXTRA_KEY_EVENT}
         *
         * @param mediaButtonIntent an intent containing the KeyEvent as an
         *            extra
         * @return True if the event was handled, false otherwise.
         */
        public boolean onMediaButtonEvent(@NonNull Intent mediaButtonIntent) {
            if (mSession != null && mHandler != null
                    && Intent.ACTION_MEDIA_BUTTON.equals(mediaButtonIntent.getAction())) {
                KeyEvent ke = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent.class);
                if (ke != null && ke.getAction() == KeyEvent.ACTION_DOWN) {
                    PlaybackState state = mSession.mPlaybackState;
                    long validActions = state == null ? 0 : state.getActions();
                    switch (ke.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                            if (ke.getRepeatCount() > 0) {
                                // Consider long-press as a single tap.
                                handleMediaPlayPauseKeySingleTapIfPending();
                            } else if (mMediaPlayPauseKeyPending) {
                                // Consider double tap as the next.
                                mHandler.removeMessages(CallbackMessageHandler
                                        .MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT);
                                mMediaPlayPauseKeyPending = false;
                                if ((validActions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
                                    onSkipToNext();
                                }
                            } else {
                                mMediaPlayPauseKeyPending = true;
                                mSession.dispatchMediaButtonDelayed(
                                        mSession.getCurrentControllerInfo(),
                                        mediaButtonIntent, ViewConfiguration.getDoubleTapTimeout());
                            }
                            return true;
                        default:
                            // If another key is pressed within double tap timeout, consider the
                            // pending play/pause as a single tap to handle media keys in order.
                            handleMediaPlayPauseKeySingleTapIfPending();
                            break;
                    }

                    switch (ke.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                            if ((validActions & PlaybackState.ACTION_PLAY) != 0) {
                                onPlay();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE:
                            if ((validActions & PlaybackState.ACTION_PAUSE) != 0) {
                                onPause();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_NEXT:
                            if ((validActions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
                                onSkipToNext();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            if ((validActions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
                                onSkipToPrevious();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_STOP:
                            if ((validActions & PlaybackState.ACTION_STOP) != 0) {
                                onStop();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                            if ((validActions & PlaybackState.ACTION_FAST_FORWARD) != 0) {
                                onFastForward();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_REWIND:
                            if ((validActions & PlaybackState.ACTION_REWIND) != 0) {
                                onRewind();
                                return true;
                            }
                            break;
                    }
                }
            }
            return false;
        }

        private void handleMediaPlayPauseKeySingleTapIfPending() {
            if (!mMediaPlayPauseKeyPending) {
                return;
            }
            mMediaPlayPauseKeyPending = false;
            mHandler.removeMessages(CallbackMessageHandler.MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT);
            PlaybackState state = mSession.mPlaybackState;
            long validActions = state == null ? 0 : state.getActions();
            boolean isPlaying = state != null
                    && state.getState() == PlaybackState.STATE_PLAYING;
            boolean canPlay = (validActions & (PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_PLAY)) != 0;
            boolean canPause = (validActions & (PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_PAUSE)) != 0;
            if (isPlaying && canPause) {
                onPause();
            } else if (!isPlaying && canPlay) {
                onPlay();
            }
        }

        /**
         * Override to handle requests to prepare playback. During the preparation, a session should
         * not hold audio focus in order to allow other sessions play seamlessly. The state of
         * playback should be updated to {@link PlaybackState#STATE_PAUSED} after the preparation is
         * done.
         */
        public void onPrepare() {
        }

        /**
         * Override to handle requests to prepare for playing a specific mediaId that was provided
         * by your app's {@link MediaBrowserService}. During the preparation, a session should not
         * hold audio focus in order to allow other sessions play seamlessly. The state of playback
         * should be updated to {@link PlaybackState#STATE_PAUSED} after the preparation is done.
         * The playback of the prepared content should start in the implementation of
         * {@link #onPlay}. Override {@link #onPlayFromMediaId} to handle requests for starting
         * playback without preparation.
         */
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
        }

        /**
         * Override to handle requests to prepare playback from a search query. An empty query
         * indicates that the app may prepare any music. The implementation should attempt to make a
         * smart choice about what to play. During the preparation, a session should not hold audio
         * focus in order to allow other sessions play seamlessly. The state of playback should be
         * updated to {@link PlaybackState#STATE_PAUSED} after the preparation is done. The playback
         * of the prepared content should start in the implementation of {@link #onPlay}. Override
         * {@link #onPlayFromSearch} to handle requests for starting playback without preparation.
         */
        public void onPrepareFromSearch(String query, Bundle extras) {
        }

        /**
         * Override to handle requests to prepare a specific media item represented by a URI.
         * During the preparation, a session should not hold audio focus in order to allow
         * other sessions play seamlessly. The state of playback should be updated to
         * {@link PlaybackState#STATE_PAUSED} after the preparation is done.
         * The playback of the prepared content should start in the implementation of
         * {@link #onPlay}. Override {@link #onPlayFromUri} to handle requests
         * for starting playback without preparation.
         */
        public void onPrepareFromUri(Uri uri, Bundle extras) {
        }

        /**
         * Override to handle requests to begin playback.
         */
        public void onPlay() {
        }

        /**
         * Override to handle requests to begin playback from a search query. An
         * empty query indicates that the app may play any music. The
         * implementation should attempt to make a smart choice about what to
         * play.
         */
        public void onPlayFromSearch(String query, Bundle extras) {
        }

        /**
         * Override to handle requests to play a specific mediaId that was
         * provided by your app's {@link MediaBrowserService}.
         */
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
        }

        /**
         * Override to handle requests to play a specific media item represented by a URI.
         */
        public void onPlayFromUri(Uri uri, Bundle extras) {
        }

        /**
         * Override to handle requests to play an item with a given id from the
         * play queue.
         */
        public void onSkipToQueueItem(long id) {
        }

        /**
         * Override to handle requests to pause playback.
         */
        public void onPause() {
        }

        /**
         * Override to handle requests to skip to the next media item.
         */
        public void onSkipToNext() {
        }

        /**
         * Override to handle requests to skip to the previous media item.
         */
        public void onSkipToPrevious() {
        }

        /**
         * Override to handle requests to fast forward.
         */
        public void onFastForward() {
        }

        /**
         * Override to handle requests to rewind.
         */
        public void onRewind() {
        }

        /**
         * Override to handle requests to stop playback.
         */
        public void onStop() {
        }

        /**
         * Override to handle requests to seek to a specific position in ms.
         *
         * @param pos New position to move to, in milliseconds.
         */
        public void onSeekTo(long pos) {
        }

        /**
         * Override to handle the item being rated.
         *
         * @param rating
         */
        public void onSetRating(@NonNull Rating rating) {
        }

        /**
         * Override to handle the playback speed change.
         * To update the new playback speed, create a new {@link PlaybackState} by using {@link
         * PlaybackState.Builder#setState(int, long, float)}, and set it with
         * {@link #setPlaybackState(PlaybackState)}.
         * <p>
         * A value of {@code 1.0f} is the default playback value, and a negative value indicates
         * reverse playback. The {@code speed} will not be equal to zero.
         *
         * @param speed the playback speed
         * @see #setPlaybackState(PlaybackState)
         * @see PlaybackState.Builder#setState(int, long, float)
         */
        public void onSetPlaybackSpeed(float speed) {
        }

        /**
         * Called when a {@link MediaController} wants a {@link PlaybackState.CustomAction} to be
         * performed.
         *
         * @param action The action that was originally sent in the
         *               {@link PlaybackState.CustomAction}.
         * @param extras Optional extras specified by the {@link MediaController}.
         */
        public void onCustomAction(@NonNull String action, @Nullable Bundle extras) {
        }
    }

    /**
     * @hide
     */
    public static class CallbackStub extends ISessionCallback.Stub {
        private WeakReference<MediaSession> mMediaSession;

        public CallbackStub(MediaSession session) {
            mMediaSession = new WeakReference<>(session);
        }

        private static RemoteUserInfo createRemoteUserInfo(String packageName, int pid, int uid) {
            return new RemoteUserInfo(packageName, pid, uid);
        }

        @Override
        public void onCommand(String packageName, int pid, int uid, String command, Bundle args,
                ResultReceiver cb) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchCommand(createRemoteUserInfo(packageName, pid, uid),
                        command, args, cb);
            }
        }

        @Override
        public void onMediaButton(String packageName, int pid, int uid, Intent mediaButtonIntent,
                int sequenceNumber, ResultReceiver cb) {
            MediaSession session = mMediaSession.get();
            try {
                if (session != null) {
                    session.dispatchMediaButton(createRemoteUserInfo(packageName, pid, uid),
                            mediaButtonIntent);
                }
            } finally {
                if (cb != null) {
                    cb.send(sequenceNumber, null);
                }
            }
        }

        @Override
        public void onMediaButtonFromController(String packageName, int pid, int uid,
                Intent mediaButtonIntent) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchMediaButton(createRemoteUserInfo(packageName, pid, uid),
                        mediaButtonIntent);
            }
        }

        @Override
        public void onPrepare(String packageName, int pid, int uid) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPrepare(createRemoteUserInfo(packageName, pid, uid));
            }
        }

        @Override
        public void onPrepareFromMediaId(String packageName, int pid, int uid, String mediaId,
                Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPrepareFromMediaId(
                        createRemoteUserInfo(packageName, pid, uid), mediaId, extras);
            }
        }

        @Override
        public void onPrepareFromSearch(String packageName, int pid, int uid, String query,
                Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPrepareFromSearch(
                        createRemoteUserInfo(packageName, pid, uid), query, extras);
            }
        }

        @Override
        public void onPrepareFromUri(String packageName, int pid, int uid, Uri uri, Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPrepareFromUri(createRemoteUserInfo(packageName, pid, uid),
                        uri, extras);
            }
        }

        @Override
        public void onPlay(String packageName, int pid, int uid) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPlay(createRemoteUserInfo(packageName, pid, uid));
            }
        }

        @Override
        public void onPlayFromMediaId(String packageName, int pid, int uid, String mediaId,
                Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPlayFromMediaId(createRemoteUserInfo(packageName, pid, uid),
                        mediaId, extras);
            }
        }

        @Override
        public void onPlayFromSearch(String packageName, int pid, int uid, String query,
                Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPlayFromSearch(createRemoteUserInfo(packageName, pid, uid),
                        query, extras);
            }
        }

        @Override
        public void onPlayFromUri(String packageName, int pid, int uid, Uri uri, Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPlayFromUri(createRemoteUserInfo(packageName, pid, uid),
                        uri, extras);
            }
        }

        @Override
        public void onSkipToTrack(String packageName, int pid, int uid, long id) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchSkipToItem(createRemoteUserInfo(packageName, pid, uid), id);
            }
        }

        @Override
        public void onPause(String packageName, int pid, int uid) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPause(createRemoteUserInfo(packageName, pid, uid));
            }
        }

        @Override
        public void onStop(String packageName, int pid, int uid) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchStop(createRemoteUserInfo(packageName, pid, uid));
            }
        }

        @Override
        public void onNext(String packageName, int pid, int uid) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchNext(createRemoteUserInfo(packageName, pid, uid));
            }
        }

        @Override
        public void onPrevious(String packageName, int pid, int uid) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPrevious(createRemoteUserInfo(packageName, pid, uid));
            }
        }

        @Override
        public void onFastForward(String packageName, int pid, int uid) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchFastForward(createRemoteUserInfo(packageName, pid, uid));
            }
        }

        @Override
        public void onRewind(String packageName, int pid, int uid) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchRewind(createRemoteUserInfo(packageName, pid, uid));
            }
        }

        @Override
        public void onSeekTo(String packageName, int pid, int uid, long pos) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchSeekTo(createRemoteUserInfo(packageName, pid, uid), pos);
            }
        }

        @Override
        public void onRate(String packageName, int pid, int uid, Rating rating) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchRate(createRemoteUserInfo(packageName, pid, uid), rating);
            }
        }

        @Override
        public void onSetPlaybackSpeed(String packageName, int pid, int uid, float speed) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchSetPlaybackSpeed(
                        createRemoteUserInfo(packageName, pid, uid), speed);
            }
        }

        @Override
        public void onCustomAction(String packageName, int pid, int uid, String action,
                Bundle args) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchCustomAction(createRemoteUserInfo(packageName, pid, uid),
                        action, args);
            }
        }

        @Override
        public void onAdjustVolume(String packageName, int pid, int uid, int direction) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchAdjustVolume(createRemoteUserInfo(packageName, pid, uid),
                        direction);
            }
        }

        @Override
        public void onSetVolumeTo(String packageName, int pid, int uid, int value) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchSetVolumeTo(createRemoteUserInfo(packageName, pid, uid),
                        value);
            }
        }
    }

    /**
     * A single item that is part of the play queue. It contains a description
     * of the item and its id in the queue.
     */
    public static final class QueueItem implements Parcelable {
        /**
         * This id is reserved. No items can be explicitly assigned this id.
         */
        public static final int UNKNOWN_ID = -1;

        private final MediaDescription mDescription;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private final long mId;

        /**
         * Create a new {@link MediaSession.QueueItem}.
         *
         * @param description The {@link MediaDescription} for this item.
         * @param id An identifier for this item. It must be unique within the
         *            play queue and cannot be {@link #UNKNOWN_ID}.
         */
        public QueueItem(MediaDescription description, long id) {
            if (description == null) {
                throw new IllegalArgumentException("Description cannot be null.");
            }
            if (id == UNKNOWN_ID) {
                throw new IllegalArgumentException("Id cannot be QueueItem.UNKNOWN_ID");
            }
            mDescription = description;
            mId = id;
        }

        private QueueItem(Parcel in) {
            mDescription = MediaDescription.CREATOR.createFromParcel(in);
            mId = in.readLong();
        }

        /**
         * Get the description for this item.
         */
        public MediaDescription getDescription() {
            return mDescription;
        }

        /**
         * Get the queue id for this item.
         */
        public long getQueueId() {
            return mId;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mDescription.writeToParcel(dest, flags);
            dest.writeLong(mId);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @android.annotation.NonNull Creator<MediaSession.QueueItem> CREATOR =
                new Creator<MediaSession.QueueItem>() {

                    @Override
                    public MediaSession.QueueItem createFromParcel(Parcel p) {
                        return new MediaSession.QueueItem(p);
                    }

                    @Override
                    public MediaSession.QueueItem[] newArray(int size) {
                        return new MediaSession.QueueItem[size];
                    }
                };

        @Override
        public String toString() {
            return "MediaSession.QueueItem {" + "Description=" + mDescription + ", Id=" + mId
                    + " }";
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) {
                return false;
            }

            if (!(o instanceof QueueItem)) {
                return false;
            }

            final QueueItem item = (QueueItem) o;
            if (mId != item.mId) {
                return false;
            }

            if (!Objects.equals(mDescription, item.mDescription)) {
                return false;
            }

            return true;
        }
    }

    private static final class Command {
        public final String command;
        public final Bundle extras;
        public final ResultReceiver stub;

        Command(String command, Bundle extras, ResultReceiver stub) {
            this.command = command;
            this.extras = extras;
            this.stub = stub;
        }
    }

    private class CallbackMessageHandler extends Handler {
        private static final int MSG_COMMAND = 1;
        private static final int MSG_MEDIA_BUTTON = 2;
        private static final int MSG_PREPARE = 3;
        private static final int MSG_PREPARE_MEDIA_ID = 4;
        private static final int MSG_PREPARE_SEARCH = 5;
        private static final int MSG_PREPARE_URI = 6;
        private static final int MSG_PLAY = 7;
        private static final int MSG_PLAY_MEDIA_ID = 8;
        private static final int MSG_PLAY_SEARCH = 9;
        private static final int MSG_PLAY_URI = 10;
        private static final int MSG_SKIP_TO_ITEM = 11;
        private static final int MSG_PAUSE = 12;
        private static final int MSG_STOP = 13;
        private static final int MSG_NEXT = 14;
        private static final int MSG_PREVIOUS = 15;
        private static final int MSG_FAST_FORWARD = 16;
        private static final int MSG_REWIND = 17;
        private static final int MSG_SEEK_TO = 18;
        private static final int MSG_RATE = 19;
        private static final int MSG_SET_PLAYBACK_SPEED = 20;
        private static final int MSG_CUSTOM_ACTION = 21;
        private static final int MSG_ADJUST_VOLUME = 22;
        private static final int MSG_SET_VOLUME = 23;
        private static final int MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT = 24;

        private MediaSession.Callback mCallback;
        private RemoteUserInfo mCurrentControllerInfo;

        CallbackMessageHandler(Looper looper, MediaSession.Callback callback) {
            super(looper);
            mCallback = callback;
            mCallback.mHandler = this;
        }

        void post(RemoteUserInfo caller, int what, Object obj, Bundle data, long delayMs) {
            Pair<RemoteUserInfo, Object> objWithCaller = Pair.create(caller, obj);
            Message msg = obtainMessage(what, objWithCaller);
            msg.setAsynchronous(true);
            msg.setData(data);
            if (delayMs > 0) {
                sendMessageDelayed(msg, delayMs);
            } else {
                sendMessage(msg);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            mCurrentControllerInfo = ((Pair<RemoteUserInfo, Object>) msg.obj).first;

            VolumeProvider vp;
            Object obj = ((Pair<RemoteUserInfo, Object>) msg.obj).second;

            switch (msg.what) {
                case MSG_COMMAND:
                    Command cmd = (Command) obj;
                    mCallback.onCommand(cmd.command, cmd.extras, cmd.stub);
                    break;
                case MSG_MEDIA_BUTTON:
                    mCallback.onMediaButtonEvent((Intent) obj);
                    break;
                case MSG_PREPARE:
                    mCallback.onPrepare();
                    break;
                case MSG_PREPARE_MEDIA_ID:
                    mCallback.onPrepareFromMediaId((String) obj, msg.getData());
                    break;
                case MSG_PREPARE_SEARCH:
                    mCallback.onPrepareFromSearch((String) obj, msg.getData());
                    break;
                case MSG_PREPARE_URI:
                    mCallback.onPrepareFromUri((Uri) obj, msg.getData());
                    break;
                case MSG_PLAY:
                    mCallback.onPlay();
                    break;
                case MSG_PLAY_MEDIA_ID:
                    mCallback.onPlayFromMediaId((String) obj, msg.getData());
                    break;
                case MSG_PLAY_SEARCH:
                    mCallback.onPlayFromSearch((String) obj, msg.getData());
                    break;
                case MSG_PLAY_URI:
                    mCallback.onPlayFromUri((Uri) obj, msg.getData());
                    break;
                case MSG_SKIP_TO_ITEM:
                    mCallback.onSkipToQueueItem((Long) obj);
                    break;
                case MSG_PAUSE:
                    mCallback.onPause();
                    break;
                case MSG_STOP:
                    mCallback.onStop();
                    break;
                case MSG_NEXT:
                    mCallback.onSkipToNext();
                    break;
                case MSG_PREVIOUS:
                    mCallback.onSkipToPrevious();
                    break;
                case MSG_FAST_FORWARD:
                    mCallback.onFastForward();
                    break;
                case MSG_REWIND:
                    mCallback.onRewind();
                    break;
                case MSG_SEEK_TO:
                    mCallback.onSeekTo((Long) obj);
                    break;
                case MSG_RATE:
                    mCallback.onSetRating((Rating) obj);
                    break;
                case MSG_SET_PLAYBACK_SPEED:
                    mCallback.onSetPlaybackSpeed((Float) obj);
                    break;
                case MSG_CUSTOM_ACTION:
                    mCallback.onCustomAction((String) obj, msg.getData());
                    break;
                case MSG_ADJUST_VOLUME:
                    synchronized (mLock) {
                        vp = mVolumeProvider;
                    }
                    if (vp != null) {
                        vp.onAdjustVolume((int) obj);
                    }
                    break;
                case MSG_SET_VOLUME:
                    synchronized (mLock) {
                        vp = mVolumeProvider;
                    }
                    if (vp != null) {
                        vp.onSetVolumeTo((int) obj);
                    }
                    break;
                case MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT:
                    mCallback.handleMediaPlayPauseKeySingleTapIfPending();
                    break;
            }
            mCurrentControllerInfo = null;
        }
    }
}
