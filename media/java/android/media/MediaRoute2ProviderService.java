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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @hide
 */
public abstract class MediaRoute2ProviderService extends Service {
    private static final String TAG = "MR2ProviderService";

    public static final String SERVICE_INTERFACE = "android.media.MediaRoute2ProviderService";

    private final Handler mHandler;
    private final Object mSessionLock = new Object();
    private ProviderStub mStub;
    private IMediaRoute2ProviderClient mClient;
    private MediaRoute2ProviderInfo mProviderInfo;

    @GuardedBy("mSessionLock")
    private ArrayMap<Integer, RouteSessionInfo> mSessionInfo = new ArrayMap<>();

    public MediaRoute2ProviderService() {
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public IBinder onBind(@NonNull Intent intent) {
        //TODO: Allow binding from media router service only?
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            if (mStub == null) {
                mStub = new ProviderStub();
            }
            return mStub;
        }
        return null;
    }

    /**
     * Called when sendControlRequest is called on a route of the provider
     *
     * @param routeId the id of the target route
     * @param request the media control request intent
     */
    //TODO: Discuss what to use for request (e.g., Intent? Request class?)
    public abstract void onControlRequest(@NonNull String routeId, @NonNull Intent request);

    /**
     * Called when requestSetVolume is called on a route of the provider
     *
     * @param routeId the id of the route
     * @param volume the target volume
     */
    public abstract void onSetVolume(@NonNull String routeId, int volume);

    /**
     * Called when requestUpdateVolume is called on a route of the provider
     *
     * @param routeId id of the route
     * @param delta the delta to add to the current volume
     */
    public abstract void onUpdateVolume(@NonNull String routeId, int delta);

    /**
     * Gets information of the session with the given id.
     *
     * @param sessionId id of the session
     * @return information of the session with the given id.
     *         null if the session is destroyed or id is not valid.
     */
    @Nullable
    public final RouteSessionInfo getSessionInfo(int sessionId) {
        synchronized (mSessionLock) {
            return mSessionInfo.get(sessionId);
        }
    }

    /**
     * Gets the list of {@link RouteSessionInfo session info} that the provider service maintains.
     */
    @NonNull
    public final List<RouteSessionInfo> getAllSessionInfo() {
        synchronized (mSessionLock) {
            return new ArrayList<>(mSessionInfo.values());
        }
    }

    /**
     * Updates the information of a session.
     * If the session is destroyed or not created before, it will be ignored.
     * A session will be destroyed if it has no selected route.
     * Call {@link #updateProviderInfo(MediaRoute2ProviderInfo)} to notify clients of
     * session info changes.
     *
     * @param sessionInfo new session information
     * @see #notifySessionCreated(RouteSessionInfo, int)
     */
    public final void updateSessionInfo(@NonNull RouteSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        int sessionId = sessionInfo.getSessionId();

        //TODO: notify updated session info

        synchronized (mSessionLock) {
            if (mSessionInfo.containsKey(sessionId)) {
                mSessionInfo.put(sessionId, sessionInfo);
            } else {
                Log.w(TAG, "Ignoring unknown session info.");
            }
        }
        if (sessionInfo.getSelectedRoutes().isEmpty()) {
            releaseSession(sessionId);
        }
    }

    /**
     * Notifies clients of that the session is created and ready for use. If the session can be
     * controlled, pass a {@link Bundle} that contains how to control it.
     *
     * @param sessionInfo information of the new session.
     *                    The {@link RouteSessionInfo#getSessionId() id} of the session must be
     *                    unique. Pass {@code null} to reject the request or inform clients that
     *                    session creation is failed.
     * @param requestId id of the previous request to create this session
     */
    //TODO: fail reason?
    public final void notifySessionCreated(@Nullable RouteSessionInfo sessionInfo, int requestId) {
        if (sessionInfo != null) {
            int sessionId = sessionInfo.getSessionId();
            synchronized (mSessionLock) {
                if (mSessionInfo.containsKey(sessionId)) {
                    Log.w(TAG, "Ignoring duplicate session id.");
                    return;
                }
                mSessionInfo.put(sessionInfo.getSessionId(), sessionInfo);
            }
        }

        if (mClient == null) {
            return;
        }
        try {
            mClient.notifySessionCreated(sessionInfo, requestId);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify session created.");
        }
    }

    /**
     * Releases a session with the given id.
     * {@link #onDestroySession} is called if the session is released.
     *
     * @param sessionId id of the session to be released
     * @see #onDestroySession(int, RouteSessionInfo)
     */
    public final void releaseSession(int sessionId) {
        //TODO: notify media router service of release.
        RouteSessionInfo sessionInfo;
        synchronized (mSessionLock) {
            sessionInfo = mSessionInfo.put(sessionId, null);
        }
        if (sessionInfo != null) {
            mHandler.sendMessage(obtainMessage(
                    MediaRoute2ProviderService::onDestroySession, this, sessionId, sessionInfo));
        }
    }

    /**
     * Called when a session should be created.
     * You should create and maintain your own session and notifies the client of
     * session info. Call {@link #notifySessionCreated(RouteSessionInfo, int)}
     * with the given {@code requestId} to notify the information of a new session.
     * If you can't create the session or want to reject the request, pass {@code null}
     * as session info in {@link #notifySessionCreated(RouteSessionInfo, int)}
     * with the given {@code requestId}.
     *
     * @param packageName the package name of the application that selected the route
     * @param routeId the id of the route initially being connected
     * @param controlCategory the control category of the new session
     * @param requestId the id of this session creation request
     */
    public abstract void onCreateSession(@NonNull String packageName, @NonNull String routeId,
            @NonNull String controlCategory, int requestId);

    /**
     * Called when a session is about to be destroyed.
     * You can clean up your session here. This can happen by the
     * client or provider itself.
     *
     * @param sessionId id of the session being destroyed.
     * @param lastSessionInfo information of the session being destroyed.
     * @see #releaseSession(int)
     */
    public abstract void onDestroySession(int sessionId, @NonNull RouteSessionInfo lastSessionInfo);

    //TODO: make a way to reject the request
    /**
     * Called when a client requests selecting a route for the session.
     * After the route is selected, call {@link #updateSessionInfo(RouteSessionInfo)} to update
     * session info and call {@link #updateProviderInfo(MediaRoute2ProviderInfo)} to notify
     * clients of updated session info.
     *
     * @param sessionId id of the session
     * @param routeId id of the route
     * @see #updateSessionInfo(RouteSessionInfo)
     */
    public abstract void onSelectRoute(int sessionId, @NonNull String routeId);

    //TODO: make a way to reject the request
    /**
     * Called when a client requests deselecting a route from the session.
     * After the route is deselected, call {@link #updateSessionInfo(RouteSessionInfo)} to update
     * session info and call {@link #updateProviderInfo(MediaRoute2ProviderInfo)} to notify
     * clients of updated session info.
     *
     * @param sessionId id of the session
     * @param routeId id of the route
     */
    public abstract void onDeselectRoute(int sessionId, @NonNull String routeId);

    //TODO: make a way to reject the request
    /**
     * Called when a client requests transferring a session to a route.
     * After the transfer is finished, call {@link #updateSessionInfo(RouteSessionInfo)} to update
     * session info and call {@link #updateProviderInfo(MediaRoute2ProviderInfo)} to notify
     * clients of updated session info.
     *
     * @param sessionId id of the session
     * @param routeId id of the route
     */
    public abstract void onTransferRoute(int sessionId, @NonNull String routeId);

    /**
     * Updates provider info and publishes routes and session info.
     */
    public final void updateProviderInfo(MediaRoute2ProviderInfo info) {
        mProviderInfo = info;
        publishState();
    }

    void setClient(IMediaRoute2ProviderClient client) {
        mClient = client;
        publishState();
    }

    void publishState() {
        //TODO: sends session info
        if (mClient == null) {
            return;
        }
        try {
            mClient.updateProviderInfo(mProviderInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to send onProviderInfoUpdated");
        }
    }

    final class ProviderStub extends IMediaRoute2Provider.Stub {
        ProviderStub() { }

        boolean checkCallerisSystem() {
            return Binder.getCallingUid() == Process.SYSTEM_UID;
        }

        @Override
        public void setClient(IMediaRoute2ProviderClient client) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::setClient,
                    MediaRoute2ProviderService.this, client));
        }

        @Override
        public void requestCreateSession(String packageName, String routeId,
                String controlCategory, int requestId) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onCreateSession,
                    MediaRoute2ProviderService.this, packageName, routeId, controlCategory,
                    requestId));
        }
        @Override
        public void releaseSession(int sessionId) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::releaseSession,
                    MediaRoute2ProviderService.this, sessionId));
        }

        @Override
        public void selectRoute(int sessionId, String routeId) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSelectRoute,
                    MediaRoute2ProviderService.this, sessionId, routeId));
        }

        @Override
        public void deselectRoute(int sessionId, String routeId) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onDeselectRoute,
                    MediaRoute2ProviderService.this, sessionId, routeId));
        }

        @Override
        public void transferRoute(int sessionId, String routeId) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onTransferRoute,
                    MediaRoute2ProviderService.this, sessionId, routeId));
        }

        @Override
        public void notifyControlRequestSent(String routeId, Intent request) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onControlRequest,
                    MediaRoute2ProviderService.this, routeId, request));
        }

        @Override
        public void requestSetVolume(String routeId, int volume) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSetVolume,
                    MediaRoute2ProviderService.this, routeId, volume));
        }

        @Override
        public void requestUpdateVolume(String routeId, int delta) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onUpdateVolume,
                    MediaRoute2ProviderService.this, routeId, delta));
        }
    }
}
