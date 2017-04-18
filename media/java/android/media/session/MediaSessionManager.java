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
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.IRemoteVolumeController;
import android.media.session.ISessionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.util.ArrayMap;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides support for interacting with {@link MediaSession media sessions}
 * that applications have published to express their ongoing media playback
 * state.
 * <p>
 * Use <code>Context.getSystemService(Context.MEDIA_SESSION_SERVICE)</code> to
 * get an instance of this class.
 *
 * @see MediaSession
 * @see MediaController
 */
public final class MediaSessionManager {
    private static final String TAG = "SessionManager";

    private final ArrayMap<OnActiveSessionsChangedListener, SessionsChangedWrapper> mListeners
            = new ArrayMap<OnActiveSessionsChangedListener, SessionsChangedWrapper>();
    private final Object mLock = new Object();
    private final ISessionManager mService;

    private Context mContext;

    private CallbackImpl mCallback;

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
     * @return The binder object from the system
     * @hide
     */
    public @NonNull ISession createSession(@NonNull MediaSession.CallbackStub cbStub,
            @NonNull String tag, int userId) throws RemoteException {
        return mService.createSession(mContext.getPackageName(), cbStub, tag, userId);
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
    public @NonNull List<MediaController> getActiveSessionsForUser(
            @Nullable ComponentName notificationListener, int userId) {
        ArrayList<MediaController> controllers = new ArrayList<MediaController>();
        try {
            List<IBinder> binders = mService.getSessions(notificationListener, userId);
            int size = binders.size();
            for (int i = 0; i < size; i++) {
                MediaController controller = new MediaController(mContext, ISessionController.Stub
                        .asInterface(binders.get(i)));
                controllers.add(controller);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get active sessions: ", e);
        }
        return controllers;
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
        try {
            mService.dispatchMediaKeyEvent(keyEvent, needWakeLock);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send key event.", e);
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
            mService.dispatchAdjustVolume(suggestedStream, direction, flags);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send adjust volume.", e);
        }
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
                if (mHandler != null) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mListener != null) {
                                ArrayList<MediaController> controllers
                                        = new ArrayList<MediaController>();
                                int size = tokens.size();
                                for (int i = 0; i < size; i++) {
                                    controllers.add(new MediaController(mContext, tokens.get(i)));
                                }
                                mListener.onActiveSessionsChanged(controllers);
                            }
                        }
                    });
                }
            }
        };

        private void release() {
            mContext = null;
            mListener = null;
            mHandler = null;
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
