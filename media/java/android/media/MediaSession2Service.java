/*
 * Copyright 2019 The Android Open Source Project
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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service containing {@link MediaSession2}.
 * <p>
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
 * for consistent behavior across all devices.
 * @hide
 */
// TODO: Unhide
// TODO: Add onUpdateNotification(), and calls it to get Notification for startForegroundService()
//       when a session's player state becomes playing.
public abstract class MediaSession2Service extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE = "android.media.MediaSession2Service";

    private static final String TAG = "MediaSession2Service";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private Map<String, MediaSession2> mSessions = new ArrayMap<>();

    private MediaSession2ServiceStub mStub;

    /**
     * Called by the system when the service is first created. Do not call this method directly.
     * <p>
     * Override this method if you need your own initialization. Derived classes MUST call through
     * to the super class's implementation of this method.
     */
    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mStub = new MediaSession2ServiceStub(this);
    }

    @CallSuper
    @Override
    @Nullable
    public IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mStub;
        }
        return null;
    }

    @CallSuper
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // TODO: Dispatch media key events to the primary session.
        return START_STICKY;
    }

    /**
     * Called by the system to notify that it is no longer used and is being removed. Do not call
     * this method directly.
     * <p>
     * Override this method if you need your own clean up. Derived classes MUST call through
     * to the super class's implementation of this method.
     */
    @CallSuper
    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (mLock) {
            for (MediaSession2 session : mSessions.values()) {
                session.getCallback().setForegroundServiceEventCallback(null);
            }
            mSessions.clear();
        }
        mStub.close();
    }

    /**
     * Called when a {@link MediaController2} is created with the this service's
     * {@link Session2Token}. Return the primary session for telling the controller which session to
     * connect.
     * <p>
     * Primary session is the highest priority session that this service manages. Here are some
     * recommendations of the primary session.
     * <ol>
     * <li>When there's no {@link MediaSession2}, create and return a new session. Resume the
     * playback that the app has the lastly played with the new session. The behavior is what
     * framework expects when the framework sends key events to the service.</li>
     * <li>When there's multiple {@link MediaSession2}s, pick the session that has the lastly
     * started the playback. This is the same way as the framework prioritize sessions to receive
     * media key events.</li>
     * </ol>
     * <p>
     * Session returned here will be added to this service automatically. You don't need to call
     * {@link #addSession(MediaSession2)} for that.
     * <p>
     * Session service will accept or reject the connection with the
     * {@link MediaSession2.SessionCallback} in the session returned here.
     * <p>
     * This method is always called on the main thread.
     *
     * @return a new session
     * @see MediaSession2.Builder
     * @see #getSessions()
     */
    @NonNull
    public abstract MediaSession2 onGetPrimarySession();

    /**
     * Adds a session to this service.
     * <p>
     * Added session will be removed automatically when it's closed, or removed when
     * {@link #removeSession} is called.
     *
     * @param session a session to be added.
     * @see #removeSession(MediaSession2)
     */
    public final void addSession(@NonNull MediaSession2 session) {
        if (session == null) {
            throw new IllegalArgumentException("session shouldn't be null");
        }
        if (session.isClosed()) {
            throw new IllegalArgumentException("session is already closed");
        }
        synchronized (mLock) {
            MediaSession2 previousSession = mSessions.get(session.getSessionId());
            if (previousSession != session) {
                if (previousSession != null) {
                    Log.w(TAG, "Session ID should be unique, ID=" + session.getSessionId()
                            + ", previous=" + previousSession + ", session=" + session);
                }
                return;
            }
            mSessions.put(session.getSessionId(), session);
            session.getCallback().setForegroundServiceEventCallback(
                    new MediaSession2.SessionCallback.ForegroundServiceEventCallback() {
                        @Override
                        public void onSessionClosed(MediaSession2 session) {
                            removeSession(session);
                        }
                    });
        }
    }

    /**
     * Removes a session from this service.
     *
     * @param session a session to be removed.
     * @see #addSession(MediaSession2)
     */
    public final void removeSession(@NonNull MediaSession2 session) {
        if (session == null) {
            throw new IllegalArgumentException("session shouldn't be null");
        }
        synchronized (mLock) {
            mSessions.remove(session.getSessionId());
        }
    }

    /**
     * Gets the list of {@link MediaSession2}s that you've added to this service.
     *
     * @return sessions
     */
    public final @NonNull List<MediaSession2> getSessions() {
        List<MediaSession2> list = new ArrayList<>();
        synchronized (mLock) {
            list.addAll(mSessions.values());
        }
        return list;
    }

    private static final class MediaSession2ServiceStub extends IMediaSession2Service.Stub
            implements AutoCloseable {
        final WeakReference<MediaSession2Service> mService;
        final Handler mHandler;

        MediaSession2ServiceStub(MediaSession2Service service) {
            mService = new WeakReference<>(service);
            mHandler = new Handler(service.getMainLooper());
        }

        @Override
        public void connect(Controller2Link caller, int seq, Bundle connectionRequest) {
            if (mService.get() == null) {
                if (DEBUG) {
                    Log.d(TAG, "Service is already destroyed");
                }
                return;
            }
            if (caller == null || connectionRequest == null) {
                if (DEBUG) {
                    Log.d(TAG, "Ignoring calls with illegal arguments, caller=" + caller
                            + ", connectionRequest=" + connectionRequest);
                }
                return;
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> {
                    boolean shouldNotifyDisconnected = true;
                    try {
                        final MediaSession2Service service = mService.get();
                        if (service == null) {
                            if (DEBUG) {
                                Log.d(TAG, "Service isn't available");
                            }
                            return;
                        }
                        if (DEBUG) {
                            Log.d(TAG, "Handling incoming connection request from the"
                                    + " controller, controller=" + caller + ", uid=" + uid);
                        }
                        final MediaSession2 session;
                        session = service.onGetPrimarySession();
                        service.addSession(session);
                        shouldNotifyDisconnected = false;
                        session.onConnect(caller, pid, uid, seq, connectionRequest);
                    } catch (Exception e) {
                        // Don't propagate exception in service to the controller.
                        Log.w(TAG, "Failed to add a session to session service", e);
                    } finally {
                        // Trick to call onDisconnected() in one place.
                        if (shouldNotifyDisconnected) {
                            if (DEBUG) {
                                Log.d(TAG, "Service has destroyed prematurely."
                                        + " Rejecting connection");
                            }
                            try {
                                caller.notifyDisconnected(0);
                            } catch (RuntimeException e) {
                                // Controller may be died prematurely.
                                // Not an issue because we'll ignore it anyway.
                            }
                        }
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void close() {
            mHandler.removeCallbacksAndMessages(null);
            mService.clear();
        }
    }
}
