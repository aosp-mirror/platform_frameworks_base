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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.media.AudioManager;
import android.media.IRemoteVolumeController;
import android.media.MediaSession2;
import android.media.Session2Token;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.media.MediaBrowserService;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Provides support for interacting with {@link MediaSession media sessions}
 * that applications have published to express their ongoing media playback
 * state.
 *
 * @see MediaSession
 * @see MediaController
 */
// TODO: (jinpark) Add API for getting and setting session policies from MediaSessionService once
//  b/149006225 is fixed.
@SystemService(Context.MEDIA_SESSION_SERVICE)
public final class MediaSessionManager {
    private static final String TAG = "SessionManager";

    /**
     * Used by IOnMediaKeyListener to indicate that the media key event isn't handled.
     * @hide
     */
    public static final int RESULT_MEDIA_KEY_NOT_HANDLED = 0;

    /**
     * Used by IOnMediaKeyListener to indicate that the media key event is handled.
     * @hide
     */
    public static final int RESULT_MEDIA_KEY_HANDLED = 1;
    private final ISessionManager mService;
    private final OnMediaKeyEventDispatchedListenerStub mOnMediaKeyEventDispatchedListenerStub =
            new OnMediaKeyEventDispatchedListenerStub();
    private final OnMediaKeyEventSessionChangedListenerStub
            mOnMediaKeyEventSessionChangedListenerStub =
            new OnMediaKeyEventSessionChangedListenerStub();

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArrayMap<OnActiveSessionsChangedListener, SessionsChangedWrapper> mListeners =
            new ArrayMap<OnActiveSessionsChangedListener, SessionsChangedWrapper>();
    @GuardedBy("mLock")
    private final ArrayMap<OnSession2TokensChangedListener, Session2TokensChangedWrapper>
            mSession2TokensListeners = new ArrayMap<>();
    @GuardedBy("mLock")
    private final Map<OnMediaKeyEventDispatchedListener, Executor>
            mOnMediaKeyEventDispatchedListeners = new HashMap<>();
    @GuardedBy("mLock")
    private final Map<OnMediaKeyEventSessionChangedListener, Executor>
            mMediaKeyEventSessionChangedCallbacks = new HashMap<>();
    @GuardedBy("mLock")
    private String mCurMediaKeyEventSessionPackage;
    @GuardedBy("mLock")
    private MediaSession.Token mCurMediaKeyEventSession;

    private Context mContext;
    private OnVolumeKeyLongPressListenerImpl mOnVolumeKeyLongPressListener;
    private OnMediaKeyListenerImpl mOnMediaKeyListener;

    /**
     * @hide
     */
    public MediaSessionManager(Context context) {
        // Consider rewriting like DisplayManagerGlobal
        // Decide if we need context
        mContext = context;
        IBinder b = ServiceManager.getService(Context.MEDIA_SESSION_SERVICE);
        mService = ISessionManager.Stub.asInterface(b);
    }

    /**
     * Create a new session in the system and get the binder for it.
     *
     * @param tag A short name for debugging purposes.
     * @param sessionInfo A bundle for additional information about this session.
     * @return The binder object from the system
     * @hide
     */
    @NonNull
    public ISession createSession(@NonNull MediaSession.CallbackStub cbStub, @NonNull String tag,
            @Nullable Bundle sessionInfo) {
        try {
            return mService.createSession(mContext.getPackageName(), cbStub, tag, sessionInfo,
                    UserHandle.myUserId());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Notifies that a new {@link MediaSession2} with type {@link Session2Token#TYPE_SESSION} is
     * created.
     * <p>
     * Do not use this API directly, but create a new instance through the
     * {@link MediaSession2.Builder} instead.
     *
     * @param token newly created session2 token
     */
    public void notifySession2Created(@NonNull Session2Token token) {
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        if (token.getType() != Session2Token.TYPE_SESSION) {
            throw new IllegalArgumentException("token's type should be TYPE_SESSION");
        }
        try {
            mService.notifySession2Created(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a list of controllers for all ongoing sessions. The controllers will
     * be provided in priority order with the most important controller at index
     * 0.
     * <p>
     * This requires the android.Manifest.permission.MEDIA_CONTENT_CONTROL
     * permission be held by the calling app. You may also retrieve this list if
     * your app is an enabled notification listener using the
     * {@link NotificationListenerService} APIs, in which case you must pass the
     * {@link ComponentName} of your enabled listener.
     *
     * @param notificationListener The enabled notification listener component.
     *            May be null.
     * @return A list of controllers for ongoing sessions.
     */
    public @NonNull List<MediaController> getActiveSessions(
            @Nullable ComponentName notificationListener) {
        return getActiveSessionsForUser(notificationListener, UserHandle.myUserId());
    }

    /**
     * Get active sessions for a specific user. To retrieve actions for a user
     * other than your own you must hold the
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission
     * in addition to any other requirements. If you are an enabled notification
     * listener you may only get sessions for the users you are enabled for.
     *
     * @param notificationListener The enabled notification listener component.
     *            May be null.
     * @param userId The user id to fetch sessions for.
     * @return A list of controllers for ongoing sessions.
     * @hide
     */
    @UnsupportedAppUsage
    public @NonNull List<MediaController> getActiveSessionsForUser(
            @Nullable ComponentName notificationListener, int userId) {
        ArrayList<MediaController> controllers = new ArrayList<MediaController>();
        try {
            List<MediaSession.Token> tokens = mService.getSessions(notificationListener, userId);
            int size = tokens.size();
            for (int i = 0; i < size; i++) {
                MediaController controller = new MediaController(mContext, tokens.get(i));
                controllers.add(controller);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get active sessions: ", e);
        }
        return controllers;
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Gets a list of {@link Session2Token} with type {@link Session2Token#TYPE_SESSION} for the
     * current user.
     * <p>
     * Although this API can be used without any restriction, each session owners can accept or
     * reject your uses of {@link MediaSession2}.
     *
     * @return A list of {@link Session2Token}.
     */
    @NonNull
    public List<Session2Token> getSession2Tokens() {
        return getSession2Tokens(UserHandle.myUserId());
    }

    /**
     * Gets a list of {@link Session2Token} with type {@link Session2Token#TYPE_SESSION} for the
     * given user.
     * <p>
     * If you want to get tokens for another user, you must hold the
     * android.Manifest.permission#INTERACT_ACROSS_USERS_FULL permission.
     *
     * @param userId The user id to fetch sessions for.
     * @return A list of {@link Session2Token}
     * @hide
     */
    @NonNull
    public List<Session2Token> getSession2Tokens(int userId) {
        try {
            ParceledListSlice slice = mService.getSession2Tokens(userId);
            return slice == null ? new ArrayList<>() : slice.getList();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get session tokens", e);
        }
        return new ArrayList<>();
    }

    /**
     * Add a listener to be notified when the list of active sessions
     * changes.This requires the
     * android.Manifest.permission.MEDIA_CONTENT_CONTROL permission be held by
     * the calling app. You may also retrieve this list if your app is an
     * enabled notification listener using the
     * {@link NotificationListenerService} APIs, in which case you must pass the
     * {@link ComponentName} of your enabled listener. Updates will be posted to
     * the thread that registered the listener.
     *
     * @param sessionListener The listener to add.
     * @param notificationListener The enabled notification listener component.
     *            May be null.
     */
    public void addOnActiveSessionsChangedListener(
            @NonNull OnActiveSessionsChangedListener sessionListener,
            @Nullable ComponentName notificationListener) {
        addOnActiveSessionsChangedListener(sessionListener, notificationListener, null);
    }

    /**
     * Add a listener to be notified when the list of active sessions
     * changes.This requires the
     * android.Manifest.permission.MEDIA_CONTENT_CONTROL permission be held by
     * the calling app. You may also retrieve this list if your app is an
     * enabled notification listener using the
     * {@link NotificationListenerService} APIs, in which case you must pass the
     * {@link ComponentName} of your enabled listener. Updates will be posted to
     * the handler specified or to the caller's thread if the handler is null.
     *
     * @param sessionListener The listener to add.
     * @param notificationListener The enabled notification listener component.
     *            May be null.
     * @param handler The handler to post events to.
     */
    public void addOnActiveSessionsChangedListener(
            @NonNull OnActiveSessionsChangedListener sessionListener,
            @Nullable ComponentName notificationListener, @Nullable Handler handler) {
        addOnActiveSessionsChangedListener(sessionListener, notificationListener,
                UserHandle.myUserId(), handler);
    }

    /**
     * Add a listener to be notified when the list of active sessions
     * changes.This requires the
     * android.Manifest.permission.MEDIA_CONTENT_CONTROL permission be held by
     * the calling app. You may also retrieve this list if your app is an
     * enabled notification listener using the
     * {@link NotificationListenerService} APIs, in which case you must pass the
     * {@link ComponentName} of your enabled listener.
     *
     * @param sessionListener The listener to add.
     * @param notificationListener The enabled notification listener component.
     *            May be null.
     * @param userId The userId to listen for changes on.
     * @param handler The handler to post updates on.
     * @hide
     */
    public void addOnActiveSessionsChangedListener(
            @NonNull OnActiveSessionsChangedListener sessionListener,
            @Nullable ComponentName notificationListener, int userId, @Nullable Handler handler) {
        if (sessionListener == null) {
            throw new IllegalArgumentException("listener may not be null");
        }
        if (handler == null) {
            handler = new Handler();
        }
        synchronized (mLock) {
            if (mListeners.get(sessionListener) != null) {
                Log.w(TAG, "Attempted to add session listener twice, ignoring.");
                return;
            }
            SessionsChangedWrapper wrapper = new SessionsChangedWrapper(mContext, sessionListener,
                    handler);
            try {
                mService.addSessionsListener(wrapper.mStub, notificationListener, userId);
                mListeners.put(sessionListener, wrapper);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in addOnActiveSessionsChangedListener.", e);
            }
        }
    }

    /**
     * Stop receiving active sessions updates on the specified listener.
     *
     * @param listener The listener to remove.
     */
    public void removeOnActiveSessionsChangedListener(
            @NonNull OnActiveSessionsChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener may not be null");
        }
        synchronized (mLock) {
            SessionsChangedWrapper wrapper = mListeners.remove(listener);
            if (wrapper != null) {
                try {
                    mService.removeSessionsListener(wrapper.mStub);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error in removeOnActiveSessionsChangedListener.", e);
                } finally {
                    wrapper.release();
                }
            }
        }
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Adds a listener to be notified when the {@link #getSession2Tokens()} changes.
     *
     * @param listener The listener to add
     */
    public void addOnSession2TokensChangedListener(
            @NonNull OnSession2TokensChangedListener listener) {
        addOnSession2TokensChangedListener(UserHandle.myUserId(), listener, new Handler());
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Adds a listener to be notified when the {@link #getSession2Tokens()} changes.
     *
     * @param listener The listener to add
     * @param handler The handler to call listener on.
     */
    public void addOnSession2TokensChangedListener(
            @NonNull OnSession2TokensChangedListener listener, @NonNull Handler handler) {
        addOnSession2TokensChangedListener(UserHandle.myUserId(), listener, handler);
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Adds a listener to be notified when the {@link #getSession2Tokens()} changes.
     *
     * @param userId The userId to listen for changes on
     * @param listener The listener to add
     * @param handler The handler to call listener on. If {@code null}, calling thread's looper will
     *                be used.
     * @hide
     */
    public void addOnSession2TokensChangedListener(int userId,
            @NonNull OnSession2TokensChangedListener listener, @Nullable Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener shouldn't be null");
        }
        synchronized (mLock) {
            if (mSession2TokensListeners.get(listener) != null) {
                Log.w(TAG, "Attempted to add session listener twice, ignoring.");
                return;
            }
            Session2TokensChangedWrapper wrapper =
                    new Session2TokensChangedWrapper(listener, handler);
            try {
                mService.addSession2TokensListener(wrapper.getStub(), userId);
                mSession2TokensListeners.put(listener, wrapper);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in addSessionTokensListener.", e);
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Removes the {@link OnSession2TokensChangedListener} to stop receiving session token updates.
     *
     * @param listener The listener to remove.
     */
    public void removeOnSession2TokensChangedListener(
            @NonNull OnSession2TokensChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener may not be null");
        }
        final Session2TokensChangedWrapper wrapper;
        synchronized (mLock) {
            wrapper = mSession2TokensListeners.remove(listener);
        }
        if (wrapper != null) {
            try {
                mService.removeSession2TokensListener(wrapper.getStub());
            } catch (RemoteException e) {
                Log.e(TAG, "Error in removeSessionTokensListener.", e);
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Set the remote volume controller to receive volume updates on.
     * Only for use by System UI and Settings application.
     *
     * @param rvc The volume controller to receive updates on.
     * @hide
     */
    public void registerRemoteVolumeController(IRemoteVolumeController rvc) {
        try {
            mService.registerRemoteVolumeController(rvc);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in registerRemoteVolumeController.", e);
        }
    }

    /**
     * Unregisters the remote volume controller which was previously registered with
     * {@link #registerRemoteVolumeController(IRemoteVolumeController)}.
     * Only for use by System UI and Settings application.
     *
     * @param rvc The volume controller which was registered.
     * @hide
     */
    public void unregisterRemoteVolumeController(IRemoteVolumeController rvc) {
        try {
            mService.unregisterRemoteVolumeController(rvc);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in unregisterRemoteVolumeController.", e);
        }
    }

    /**
     * Send a media key event. The receiver will be selected automatically.
     *
     * @param keyEvent The KeyEvent to send.
     * @hide
     */
    public void dispatchMediaKeyEvent(@NonNull KeyEvent keyEvent) {
        dispatchMediaKeyEvent(keyEvent, false);
    }

    /**
     * Send a media key event. The receiver will be selected automatically.
     *
     * @param keyEvent The KeyEvent to send.
     * @param needWakeLock True if a wake lock should be held while sending the key.
     * @hide
     */
    public void dispatchMediaKeyEvent(@NonNull KeyEvent keyEvent, boolean needWakeLock) {
        dispatchMediaKeyEventInternal(false, keyEvent, needWakeLock);
    }

    /**
     * Send a media key event as system component. The receiver will be selected automatically.
     * <p>
     * Should be only called by the {@link com.android.internal.policy.PhoneWindow} or
     * {@link android.view.FallbackEventHandler} when the foreground activity didn't consume the key
     * from the hardware devices.
     *
     * @param keyEvent The KeyEvent to send.
     * @hide
     */
    public void dispatchMediaKeyEventAsSystemService(KeyEvent keyEvent) {
        dispatchMediaKeyEventInternal(true, keyEvent, false);
    }

    private void dispatchMediaKeyEventInternal(boolean asSystemService, @NonNull KeyEvent keyEvent,
            boolean needWakeLock) {
        try {
            mService.dispatchMediaKeyEvent(mContext.getPackageName(), asSystemService, keyEvent,
                    needWakeLock);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send key event.", e);
        }
    }

    /**
     * Dispatches the media button event as system service to the session.
     * <p>
     * Should be only called by the {@link com.android.internal.policy.PhoneWindow} when the
     * foreground activity didn't consume the key from the hardware devices.
     *
     * @param sessionToken session token
     * @param keyEvent media key event
     * @return {@code true} if the event was sent to the session, {@code false} otherwise
     * @hide
     */
    public boolean dispatchMediaKeyEventAsSystemService(@NonNull MediaSession.Token sessionToken,
            @NonNull KeyEvent keyEvent) {
        if (sessionToken == null) {
            throw new IllegalArgumentException("sessionToken shouldn't be null");
        }
        if (keyEvent == null) {
            throw new IllegalArgumentException("keyEvent shouldn't be null");
        }
        if (!KeyEvent.isMediaSessionKey(keyEvent.getKeyCode())) {
            return false;
        }
        try {
            return mService.dispatchMediaKeyEventToSessionAsSystemService(mContext.getPackageName(),
                    sessionToken, keyEvent);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send key event.", e);
        }
        return false;
    }

    /**
     * Send a volume key event. The receiver will be selected automatically.
     *
     * @param keyEvent The volume KeyEvent to send.
     * @hide
     */
    public void dispatchVolumeKeyEvent(@NonNull KeyEvent keyEvent, int stream, boolean musicOnly) {
        dispatchVolumeKeyEventInternal(false, keyEvent, stream, musicOnly);
    }

    /**
     * Dispatches the volume button event as system service to the session. This only effects the
     * {@link MediaSession.Callback#getCurrentControllerInfo()} and doesn't bypass any permission
     * check done by the system service.
     * <p>
     * Should be only called by the {@link com.android.internal.policy.PhoneWindow} or
     * {@link android.view.FallbackEventHandler} when the foreground activity didn't consume the key
     * from the hardware devices.
     *
     * @param keyEvent The KeyEvent to send.
     * @hide
     */
    public void dispatchVolumeKeyEventAsSystemService(@NonNull KeyEvent keyEvent, int streamType) {
        dispatchVolumeKeyEventInternal(true, keyEvent, streamType, false);
    }

    private void dispatchVolumeKeyEventInternal(boolean asSystemService, @NonNull KeyEvent keyEvent,
            int stream, boolean musicOnly) {
        try {
            mService.dispatchVolumeKeyEvent(mContext.getPackageName(), mContext.getOpPackageName(),
                    asSystemService, keyEvent, stream, musicOnly);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send volume key event.", e);
        }
    }

    /**
     * Dispatches the volume key event as system service to the session.
     * <p>
     * Should be only called by the {@link com.android.internal.policy.PhoneWindow} when the
     * foreground activity didn't consume the key from the hardware devices.
     *
     * @param sessionToken sessionToken
     * @param keyEvent volume key event
     * @hide
     */
    public void dispatchVolumeKeyEventAsSystemService(@NonNull MediaSession.Token sessionToken,
            @NonNull KeyEvent keyEvent) {
        if (sessionToken == null) {
            throw new IllegalArgumentException("sessionToken shouldn't be null");
        }
        if (keyEvent == null) {
            throw new IllegalArgumentException("keyEvent shouldn't be null");
        }
        try {
            mService.dispatchVolumeKeyEventToSessionAsSystemService(mContext.getPackageName(),
                    mContext.getOpPackageName(), sessionToken, keyEvent);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling dispatchVolumeKeyEventAsSystemService", e);
        }
    }

    /**
     * Dispatch an adjust volume request to the system. It will be sent to the
     * most relevant audio stream or media session. The direction must be one of
     * {@link AudioManager#ADJUST_LOWER}, {@link AudioManager#ADJUST_RAISE},
     * {@link AudioManager#ADJUST_SAME}.
     *
     * @param suggestedStream The stream to fall back to if there isn't a
     *            relevant stream
     * @param direction The direction to adjust volume in.
     * @param flags Any flags to include with the volume change.
     * @hide
     */
    public void dispatchAdjustVolume(int suggestedStream, int direction, int flags) {
        try {
            mService.dispatchAdjustVolume(mContext.getPackageName(), mContext.getOpPackageName(),
                    suggestedStream, direction, flags);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send adjust volume.", e);
        }
    }

    /**
     * Checks whether the remote user is a trusted app.
     * <p>
     * An app is trusted if the app holds the android.Manifest.permission.MEDIA_CONTENT_CONTROL
     * permission or has an enabled notification listener.
     *
     * @param userInfo The remote user info from either
     *            {@link MediaSession#getCurrentControllerInfo()} or
     *            {@link MediaBrowserService#getCurrentBrowserInfo()}.
     * @return {@code true} if the remote user is trusted and its package name matches with the UID.
     *            {@code false} otherwise.
     */
    public boolean isTrustedForMediaControl(@NonNull RemoteUserInfo userInfo) {
        if (userInfo == null) {
            throw new IllegalArgumentException("userInfo may not be null");
        }
        if (userInfo.getPackageName() == null) {
            return false;
        }
        try {
            return mService.isTrusted(
                    userInfo.getPackageName(), userInfo.getPid(), userInfo.getUid());
        } catch (RemoteException e) {
            Log.wtf(TAG, "Cannot communicate with the service.", e);
        }
        return false;
    }

    /**
     * Check if the global priority session is currently active. This can be
     * used to decide if media keys should be sent to the session or to the app.
     *
     * @hide
     */
    public boolean isGlobalPriorityActive() {
        try {
            return mService.isGlobalPriorityActive();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to check if the global priority is active.", e);
        }
        return false;
    }

    /**
     * Set the volume key long-press listener. While the listener is set, the listener
     * gets the volume key long-presses instead of changing volume.
     *
     * <p>System can only have a single volume key long-press listener.
     *
     * @param listener The volume key long-press listener. {@code null} to reset.
     * @param handler The handler on which the listener should be invoked, or {@code null}
     *            if the listener should be invoked on the calling thread's looper.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER)
    public void setOnVolumeKeyLongPressListener(
            OnVolumeKeyLongPressListener listener, @Nullable Handler handler) {
        synchronized (mLock) {
            try {
                if (listener == null) {
                    mOnVolumeKeyLongPressListener = null;
                    mService.setOnVolumeKeyLongPressListener(null);
                } else {
                    if (handler == null) {
                        handler = new Handler();
                    }
                    mOnVolumeKeyLongPressListener =
                            new OnVolumeKeyLongPressListenerImpl(listener, handler);
                    mService.setOnVolumeKeyLongPressListener(mOnVolumeKeyLongPressListener);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to set volume key long press listener", e);
            }
        }
    }

    /**
     * Set the media key listener. While the listener is set, the listener
     * gets the media key before any other media sessions but after the global priority session.
     * If the listener handles the key (i.e. returns {@code true}),
     * other sessions will not get the event.
     *
     * <p>System can only have a single media key listener.
     *
     * @param listener The media key listener. {@code null} to reset.
     * @param handler The handler on which the listener should be invoked, or {@code null}
     *            if the listener should be invoked on the calling thread's looper.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.SET_MEDIA_KEY_LISTENER)
    public void setOnMediaKeyListener(OnMediaKeyListener listener, @Nullable Handler handler) {
        synchronized (mLock) {
            try {
                if (listener == null) {
                    mOnMediaKeyListener = null;
                    mService.setOnMediaKeyListener(null);
                } else {
                    if (handler == null) {
                        handler = new Handler();
                    }
                    mOnMediaKeyListener = new OnMediaKeyListenerImpl(listener, handler);
                    mService.setOnMediaKeyListener(mOnMediaKeyListener);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to set media key listener", e);
            }
        }
    }

    /**
     * Add a {@link OnMediaKeyEventDispatchedListener}.
     *
     * @param executor The executor on which the callback should be invoked
     * @param listener A {@link OnMediaKeyEventDispatchedListener}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void addOnMediaKeyEventDispatchedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnMediaKeyEventDispatchedListener listener) {
        if (executor == null) {
            throw new NullPointerException("executor shouldn't be null");
        }
        if (listener == null) {
            throw new NullPointerException("listener shouldn't be null");
        }
        synchronized (mLock) {
            try {
                mOnMediaKeyEventDispatchedListeners.put(listener, executor);
                if (mOnMediaKeyEventDispatchedListeners.size() == 1) {
                    mService.addOnMediaKeyEventDispatchedListener(
                            mOnMediaKeyEventDispatchedListenerStub);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to set media key listener", e);
            }
        }
    }

    /**
     * Remove a {@link OnMediaKeyEventDispatchedListener}.
     *
     * @param listener A {@link OnMediaKeyEventDispatchedListener}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void removeOnMediaKeyEventDispatchedListener(
            @NonNull OnMediaKeyEventDispatchedListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener shouldn't be null");
        }
        synchronized (mLock) {
            try {
                mOnMediaKeyEventDispatchedListeners.remove(listener);
                if (mOnMediaKeyEventDispatchedListeners.size() == 0) {
                    mService.removeOnMediaKeyEventDispatchedListener(
                            mOnMediaKeyEventDispatchedListenerStub);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to set media key event dispatched listener", e);
            }
        }
    }

    /**
     * Add a {@link OnMediaKeyEventDispatchedListener}.
     *
     * @param executor The executor on which the callback should be invoked
     * @param listener A {@link OnMediaKeyEventSessionChangedListener}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void addOnMediaKeyEventSessionChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnMediaKeyEventSessionChangedListener listener) {
        if (executor == null) {
            throw new NullPointerException("executor shouldn't be null");
        }
        if (listener == null) {
            throw new NullPointerException("listener shouldn't be null");
        }
        synchronized (mLock) {
            try {
                mMediaKeyEventSessionChangedCallbacks.put(listener, executor);
                executor.execute(
                        () -> listener.onMediaKeyEventSessionChanged(
                                mCurMediaKeyEventSessionPackage, mCurMediaKeyEventSession));
                if (mMediaKeyEventSessionChangedCallbacks.size() == 1) {
                    mService.addOnMediaKeyEventSessionChangedListener(
                            mOnMediaKeyEventSessionChangedListenerStub);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to set media key listener", e);
            }
        }
    }

    /**
     * Remove a {@link OnMediaKeyEventSessionChangedListener}.
     *
     * @param listener A {@link OnMediaKeyEventSessionChangedListener}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void removeOnMediaKeyEventSessionChangedListener(
            @NonNull OnMediaKeyEventSessionChangedListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener shouldn't be null");
        }
        synchronized (mLock) {
            try {
                mMediaKeyEventSessionChangedCallbacks.remove(listener);
                if (mMediaKeyEventSessionChangedCallbacks.size() == 0) {
                    mService.removeOnMediaKeyEventSessionChangedListener(
                            mOnMediaKeyEventSessionChangedListenerStub);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to set media key listener", e);
            }
        }
    }

    /**
     * Set the component name for the custom
     * {@link com.android.server.media.MediaKeyDispatcher} class. Set to null to restore to the
     * custom {@link com.android.server.media.MediaKeyDispatcher} class name retrieved from the
     * config value.
     *
     * @hide
     */
    @VisibleForTesting
    public void setCustomMediaKeyDispatcherForTesting(@Nullable String name) {
        try {
            mService.setCustomMediaKeyDispatcherForTesting(name);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set custom media key dispatcher name", e);
        }
    }

    /**
     * Set the component name for the custom
     * {@link com.android.server.media.SessionPolicyProvider} class. Set to null to restore to the
     * custom {@link com.android.server.media.SessionPolicyProvider} class name retrieved from the
     * config value.
     *
     * @hide
     */
    @VisibleForTesting
    public void setCustomSessionPolicyProviderForTesting(@Nullable String name) {
        try {
            mService.setCustomSessionPolicyProviderForTesting(name);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set custom session policy provider name", e);
        }
    }

    /**
     * Get session policies of the specified {@link MediaSession.Token}.
     *
     * @hide
     */
    @Nullable
    public int getSessionPolicies(@NonNull MediaSession.Token token) {
        try {
            return mService.getSessionPolicies(token);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get session policies", e);
        }
        return 0;
    }

    /**
     * Set new session policies to the specified {@link MediaSession.Token}.
     *
     * @hide
     */
    public void setSessionPolicies(@NonNull MediaSession.Token token, @Nullable int policies) {
        try {
            mService.setSessionPolicies(token, policies);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set session policies", e);
        }
    }

    /**
     * Listens for changes to the list of active sessions. This can be added
     * using {@link #addOnActiveSessionsChangedListener}.
     */
    public interface OnActiveSessionsChangedListener {
        public void onActiveSessionsChanged(@Nullable List<MediaController> controllers);
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Listens for changes to the {@link #getSession2Tokens()}. This can be added
     * using {@link #addOnSession2TokensChangedListener(OnSession2TokensChangedListener, Handler)}.
     */
    public interface OnSession2TokensChangedListener {
        /**
         * Called when the {@link #getSession2Tokens()} is changed.
         *
         * @param tokens list of {@link Session2Token}
         */
        void onSession2TokensChanged(@NonNull List<Session2Token> tokens);
    }

    /**
     * Listens the volume key long-presses.
     * @hide
     */
    @SystemApi
    public interface OnVolumeKeyLongPressListener {
        /**
         * Called when the volume key is long-pressed.
         * <p>This will be called for both down and up events.
         */
        void onVolumeKeyLongPress(KeyEvent event);
    }

    /**
     * Listens the media key.
     * @hide
     */
    @SystemApi
    public interface OnMediaKeyListener {
        /**
         * Called when the media key is pressed.
         * <p>If the listener consumes the initial down event (i.e. ACTION_DOWN with
         * repeat count zero), it must also comsume all following key events.
         * (i.e. ACTION_DOWN with repeat count more than zero, and ACTION_UP).
         * <p>If it takes more than 1s to return, the key event will be sent to
         * other media sessions.
         */
        boolean onMediaKey(KeyEvent event);
    }

    /**
     * Listener to be called when the media session service dispatches a media key event.
     * @hide
     */
    @SystemApi
    public interface OnMediaKeyEventDispatchedListener {
        /**
         * Called when a media key event is dispatched through the media session service. The
         * session token can be {@link null} if the framework has sent the media key event to the
         * media button receiver to revive the media app's playback after the corresponding session
         * is released.
         *
         * @param event Dispatched media key event.
         * @param packageName The package name
         * @param sessionToken The media session's token. Can be {@code null}.
         */
        void onMediaKeyEventDispatched(@NonNull KeyEvent event, @NonNull String packageName,
                @Nullable MediaSession.Token sessionToken);
    }

    /**
     * Listener to receive changes in the media key event session, which would receive a media key
     * event unless specified.
     * @hide
     */
    @SystemApi
    public interface OnMediaKeyEventSessionChangedListener {
        /**
         * Called when the media key session is changed to the given media session. The key event
         * session is the media session which would receive key event by default, unless the caller
         * has specified the target.
         * <p>
         * The session token can be {@link null} if the media button session is unset. In that case,
         * framework would dispatch to the last sessions's media button receiver. If the media
         * button receive isn't set as well, then it
         *
         * @param packageName The package name who would receive the media key event. Can be empty.
         * @param sessionToken The media session's token. Can be {@code null}.
         */
        void onMediaKeyEventSessionChanged(@NonNull String packageName,
                @Nullable MediaSession.Token sessionToken);
    }

    /**
     * Information of a remote user of {@link MediaSession} or {@link MediaBrowserService}.
     * This can be used to decide whether the remote user is trusted app, and also differentiate
     * caller of {@link MediaSession} and {@link MediaBrowserService} callbacks.
     * <p>
     * See {@link #equals(Object)} to take a look at how it differentiate media controller.
     *
     * @see #isTrustedForMediaControl(RemoteUserInfo)
     */
    public static final class RemoteUserInfo {
        private final String mPackageName;
        private final int mPid;
        private final int mUid;

        /**
         * Create a new remote user information.
         *
         * @param packageName The package name of the remote user
         * @param pid The pid of the remote user
         * @param uid The uid of the remote user
         */
        public RemoteUserInfo(@NonNull String packageName, int pid, int uid) {
            mPackageName = packageName;
            mPid = pid;
            mUid = uid;
        }

        /**
         * @return package name of the controller
         */
        public String getPackageName() {
            return mPackageName;
        }

        /**
         * @return pid of the controller
         */
        public int getPid() {
            return mPid;
        }

        /**
         * @return uid of the controller
         */
        public int getUid() {
            return mUid;
        }

        /**
         * Returns equality of two RemoteUserInfo. Two RemoteUserInfo objects are equal
         * if and only if they have the same package name, same pid, and same uid.
         *
         * @param obj the reference object with which to compare.
         * @return {@code true} if equals, {@code false} otherwise
         */
        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof RemoteUserInfo)) {
                return false;
            }
            if (this == obj) {
                return true;
            }
            RemoteUserInfo otherUserInfo = (RemoteUserInfo) obj;
            return TextUtils.equals(mPackageName, otherUserInfo.mPackageName)
                    && mPid == otherUserInfo.mPid
                    && mUid == otherUserInfo.mUid;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPackageName, mPid, mUid);
        }
    }

    private static final class SessionsChangedWrapper {
        private Context mContext;
        private OnActiveSessionsChangedListener mListener;
        private Handler mHandler;

        public SessionsChangedWrapper(Context context, OnActiveSessionsChangedListener listener,
                Handler handler) {
            mContext = context;
            mListener = listener;
            mHandler = handler;
        }

        private final IActiveSessionsListener.Stub mStub = new IActiveSessionsListener.Stub() {
            @Override
            public void onActiveSessionsChanged(final List<MediaSession.Token> tokens) {
                final Handler handler = mHandler;
                if (handler != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            final Context context = mContext;
                            if (context != null) {
                                ArrayList<MediaController> controllers = new ArrayList<>();
                                int size = tokens.size();
                                for (int i = 0; i < size; i++) {
                                    controllers.add(new MediaController(context, tokens.get(i)));
                                }
                                final OnActiveSessionsChangedListener listener = mListener;
                                if (listener != null) {
                                    listener.onActiveSessionsChanged(controllers);
                                }
                            }
                        }
                    });
                }
            }
        };

        private void release() {
            mListener = null;
            mContext = null;
            mHandler = null;
        }
    }

    private static final class Session2TokensChangedWrapper {
        private final OnSession2TokensChangedListener mListener;
        private final Handler mHandler;
        private final ISession2TokensListener.Stub mStub =
                new ISession2TokensListener.Stub() {
                    @Override
                    public void onSession2TokensChanged(final List<Session2Token> tokens) {
                        mHandler.post(() -> mListener.onSession2TokensChanged(tokens));
                    }
                };

        Session2TokensChangedWrapper(OnSession2TokensChangedListener listener, Handler handler) {
            mListener = listener;
            mHandler = (handler == null) ? new Handler() : new Handler(handler.getLooper());
        }

        public ISession2TokensListener.Stub getStub() {
            return mStub;
        }
    }

    private static final class OnVolumeKeyLongPressListenerImpl
            extends IOnVolumeKeyLongPressListener.Stub {
        private OnVolumeKeyLongPressListener mListener;
        private Handler mHandler;

        public OnVolumeKeyLongPressListenerImpl(
                OnVolumeKeyLongPressListener listener, Handler handler) {
            mListener = listener;
            mHandler = handler;
        }

        @Override
        public void onVolumeKeyLongPress(KeyEvent event) {
            if (mListener == null || mHandler == null) {
                Log.w(TAG, "Failed to call volume key long-press listener." +
                        " Either mListener or mHandler is null");
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onVolumeKeyLongPress(event);
                }
            });
        }
    }

    private static final class OnMediaKeyListenerImpl extends IOnMediaKeyListener.Stub {
        private OnMediaKeyListener mListener;
        private Handler mHandler;

        public OnMediaKeyListenerImpl(OnMediaKeyListener listener, Handler handler) {
            mListener = listener;
            mHandler = handler;
        }

        @Override
        public void onMediaKey(KeyEvent event, ResultReceiver result) {
            if (mListener == null || mHandler == null) {
                Log.w(TAG, "Failed to call media key listener." +
                        " Either mListener or mHandler is null");
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    boolean handled = mListener.onMediaKey(event);
                    Log.d(TAG, "The media key listener is returned " + handled);
                    if (result != null) {
                        result.send(
                                handled ? RESULT_MEDIA_KEY_HANDLED : RESULT_MEDIA_KEY_NOT_HANDLED,
                                null);
                    }
                }
            });
        }
    }

    private final class OnMediaKeyEventDispatchedListenerStub
            extends IOnMediaKeyEventDispatchedListener.Stub {

        @Override
        public void onMediaKeyEventDispatched(KeyEvent event, String packageName,
                MediaSession.Token sessionToken) {
            synchronized (mLock) {
                for (Map.Entry<OnMediaKeyEventDispatchedListener, Executor> e
                        : mOnMediaKeyEventDispatchedListeners.entrySet()) {
                    e.getValue().execute(
                            () -> e.getKey().onMediaKeyEventDispatched(event, packageName,
                                    sessionToken));
                }
            }
        }
    }

    private final class OnMediaKeyEventSessionChangedListenerStub
            extends IOnMediaKeyEventSessionChangedListener.Stub {
        @Override
        public void onMediaKeyEventSessionChanged(String packageName,
                MediaSession.Token sessionToken) {
            synchronized (mLock) {
                mCurMediaKeyEventSessionPackage = packageName;
                mCurMediaKeyEventSession = sessionToken;
                for (Map.Entry<OnMediaKeyEventSessionChangedListener, Executor> e
                        : mMediaKeyEventSessionChangedCallbacks.entrySet()) {
                    e.getValue().execute(() -> e.getKey().onMediaKeyEventSessionChanged(packageName,
                            sessionToken));
                }
            }
        }
    }
}
