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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provides support for interacting with {@link MediaSession media sessions}
 * that applications have published to express their ongoing media playback
 * state.
 *
 * @see MediaSession
 * @see MediaController
 */
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

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArrayMap<OnActiveSessionsChangedListener, SessionsChangedWrapper> mListeners
            = new ArrayMap<OnActiveSessionsChangedListener, SessionsChangedWrapper>();
    @GuardedBy("mLock")
    private final ArrayMap<OnSession2TokensChangedListener, Session2TokensChangedWrapper>
            mSession2TokensListeners = new ArrayMap<>();
    private final ISessionManager mService;

    private Context mContext;

    private CallbackImpl mCallback;
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
    public SessionLink createSession(@NonNull SessionCallbackLink cbStub, @NonNull String tag,
            @Nullable Bundle sessionInfo) {
        try {
            return mService.createSession(mContext.getPackageName(), cbStub, tag, sessionInfo,
                    UserHandle.myUserId());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
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
            return mService.getSession2Tokens(userId);
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
     * Adds a listener to be notified when the {@link #getSession2Tokens()} changes.
     * <p>
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
     * for consistent behavior across all devices.
     *
     * @param listener The listener to add
     */
    public void addOnSession2TokensChangedListener(
            @NonNull OnSession2TokensChangedListener listener) {
        addOnSession2TokensChangedListener(UserHandle.myUserId(), listener, new Handler());
    }

    /**
     * Adds a listener to be notified when the {@link #getSession2Tokens()} changes.
     * <p>
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
     * for consistent behavior across all devices.
     *
     * @param listener The listener to add
     * @param handler The handler to call listener on.
     */
    public void addOnSession2TokensChangedListener(
            @NonNull OnSession2TokensChangedListener listener, @NonNull Handler handler) {
        addOnSession2TokensChangedListener(UserHandle.myUserId(), listener, handler);
    }

    /**
     * Adds a listener to be notified when the {@link #getSession2Tokens()} changes.
     * <p>
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
     * for consistent behavior across all devices.
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
     * Set the remote volume controller to receive volume updates on. Only for
     * use by system UI.
     *
     * @param rvc The volume controller to receive updates on.
     * @hide
     */
    public void setRemoteVolumeController(IRemoteVolumeController rvc) {
        try {
            mService.setRemoteVolumeController(rvc);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in setRemoteVolumeController.", e);
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
     * Set a {@link Callback}.
     *
     * <p>System can only have a single callback, and the callback can only be set by
     * Bluetooth service process.
     *
     * @param callback A {@link Callback}. {@code null} to reset.
     * @param handler The handler on which the callback should be invoked, or {@code null}
     *            if the callback should be invoked on the calling thread's looper.
     * @hide
     */
    public void setCallback(@Nullable Callback callback, @Nullable Handler handler) {
        synchronized (mLock) {
            try {
                if (callback == null) {
                    mCallback = null;
                    mService.setCallback(null);
                } else {
                    if (handler == null) {
                        handler = new Handler();
                    }
                    mCallback = new CallbackImpl(callback, handler);
                    mService.setCallback(mCallback);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to set media key callback", e);
            }
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
     * Listens for changes to the {@link #getSession2Tokens()}. This can be added
     * using {@link #addOnSession2TokensChangedListener(OnSession2TokensChangedListener, Handler)}.
     * <p>
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
     * for consistent behavior across all devices.
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
     * Callbacks for the media session service.
     *
     * <p>Called when a media key event is dispatched or the addressed player is changed.
     * The addressed player is either the media session or the media button receiver that will
     * receive media key events.
     * @hide
     */
    public static abstract class Callback {
        /**
         * Called when a media key event is dispatched to the media session
         * through the media session service.
         *
         * @param event Dispatched media key event.
         * @param sessionToken The media session's token.
         */
        public abstract void onMediaKeyEventDispatched(KeyEvent event,
                MediaSession.Token sessionToken);

        /**
         * Called when a media key event is dispatched to the media button receiver
         * through the media session service.
         * <p>MediaSessionService may broadcast key events to the media button receiver
         * when reviving playback after the media session is released.
         *
         * @param event Dispatched media key event.
         * @param mediaButtonReceiver The media button receiver.
         */
        public abstract void onMediaKeyEventDispatched(KeyEvent event,
                ComponentName mediaButtonReceiver);

        /**
         * Called when the addressed player is changed to a media session.
         * <p>One of the {@ #onAddressedPlayerChanged} will be also called immediately after
         * {@link #setCallback} if the addressed player exists.
         *
         * @param sessionToken The media session's token.
         */
        public abstract void onAddressedPlayerChanged(MediaSession.Token sessionToken);

        /**
         * Called when the addressed player is changed to the media button receiver.
         * <p>One of the {@ #onAddressedPlayerChanged} will be also called immediately after
         * {@link #setCallback} if the addressed player exists.
         *
         * @param mediaButtonReceiver The media button receiver.
         */
        public abstract void onAddressedPlayerChanged(ComponentName mediaButtonReceiver);
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
        public boolean equals(Object obj) {
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

    private static final class CallbackImpl extends ICallback.Stub {
        private final Callback mCallback;
        private final Handler mHandler;

        public CallbackImpl(@NonNull Callback callback, @NonNull Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }

        @Override
        public void onMediaKeyEventDispatchedToMediaSession(KeyEvent event,
                MediaSession.Token sessionToken) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onMediaKeyEventDispatched(event, sessionToken);
                }
            });
        }

        @Override
        public void onMediaKeyEventDispatchedToMediaButtonReceiver(KeyEvent event,
                ComponentName mediaButtonReceiver) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onMediaKeyEventDispatched(event, mediaButtonReceiver);
                }
            });
        }

        @Override
        public void onAddressedPlayerChangedToMediaSession(MediaSession.Token sessionToken) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onAddressedPlayerChanged(sessionToken);
                }
            });
        }

        @Override
        public void onAddressedPlayerChangedToMediaButtonReceiver(
                ComponentName mediaButtonReceiver) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onAddressedPlayerChanged(mediaButtonReceiver);
                }
            });
        }
    }
}
