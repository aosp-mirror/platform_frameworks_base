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
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @hide
 */
public abstract class MediaRoute2ProviderService extends Service {
    private static final String TAG = "MR2ProviderService";

    public static final String SERVICE_INTERFACE = "android.media.MediaRoute2ProviderService";

    private final Handler mHandler;
    private final Object mSessionLock = new Object();
    private final AtomicBoolean mStatePublishScheduled = new AtomicBoolean(false);
    private ProviderStub mStub;
    private IMediaRoute2ProviderClient mClient;
    private MediaRoute2ProviderInfo mProviderInfo;

    @GuardedBy("mSessionLock")
    private ArrayMap<String, RouteSessionInfo> mSessionInfo = new ArrayMap<>();

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
    public final RouteSessionInfo getSessionInfo(@NonNull String sessionId) {
        if (TextUtils.isEmpty(sessionId)) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
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
     * @see #notifySessionCreated(RouteSessionInfo, long)
     */
    public final void updateSessionInfo(@NonNull RouteSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");
        String sessionId = sessionInfo.getId();
        if (sessionInfo.getSelectedRoutes().isEmpty()) {
            releaseSession(sessionId);
            return;
        }

        synchronized (mSessionLock) {
            if (mSessionInfo.containsKey(sessionId)) {
                mSessionInfo.put(sessionId, sessionInfo);
                schedulePublishState();
            } else {
                Log.w(TAG, "Ignoring unknown session info.");
                return;
            }
        }
    }

    /**
     * Notifies the session is changed.
     *
     * TODO: This method is temporary, only created for tests. Remove when the alternative is ready.
     * @hide
     */
    public final void notifySessionInfoChanged(@NonNull RouteSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        String sessionId = sessionInfo.getId();
        synchronized (mSessionLock) {
            if (mSessionInfo.containsKey(sessionId)) {
                mSessionInfo.put(sessionId, sessionInfo);
            } else {
                Log.w(TAG, "Ignoring unknown session info.");
                return;
            }
        }

        if (mClient == null) {
            return;
        }
        try {
            mClient.notifySessionInfoChanged(sessionInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify session info changed.");
        }
    }

    /**
     * Notifies clients of that the session is created and ready for use. If the session can be
     * controlled, pass a {@link Bundle} that contains how to control it.
     *
     * @param sessionInfo information of the new session.
     *                    The {@link RouteSessionInfo#getId() id} of the session must be
     *                    unique. Pass {@code null} to reject the request or inform clients that
     *                    session creation is failed.
     * @param requestId id of the previous request to create this session
     */
    // TODO: fail reason?
    // TODO: Maybe better to create notifySessionCreationFailed?
    public final void notifySessionCreated(@Nullable RouteSessionInfo sessionInfo, long requestId) {
        if (sessionInfo != null) {
            String sessionId = sessionInfo.getId();
            synchronized (mSessionLock) {
                if (mSessionInfo.containsKey(sessionId)) {
                    Log.w(TAG, "Ignoring duplicate session id.");
                    return;
                }
                mSessionInfo.put(sessionInfo.getId(), sessionInfo);
            }
            schedulePublishState();
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
     * @see #onDestroySession(String, RouteSessionInfo)
     */
    public final void releaseSession(@NonNull String sessionId) {
        if (TextUtils.isEmpty(sessionId)) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
        //TODO: notify media router service of release.
        RouteSessionInfo sessionInfo;
        synchronized (mSessionLock) {
            sessionInfo = mSessionInfo.remove(sessionId);
        }
        if (sessionInfo != null) {
            mHandler.sendMessage(obtainMessage(
                    MediaRoute2ProviderService::onDestroySession, this, sessionId, sessionInfo));
            schedulePublishState();
        }
    }

    /**
     * Called when a session should be created.
     * You should create and maintain your own session and notifies the client of
     * session info. Call {@link #notifySessionCreated(RouteSessionInfo, long)}
     * with the given {@code requestId} to notify the information of a new session.
     * If you can't create the session or want to reject the request, pass {@code null}
     * as session info in {@link #notifySessionCreated(RouteSessionInfo, long)}
     * with the given {@code requestId}.
     *
     * @param packageName the package name of the application that selected the route
     * @param routeId the id of the route initially being connected
     * @param routeType the route type of the new session
     * @param requestId the id of this session creation request
     */
    public abstract void onCreateSession(@NonNull String packageName, @NonNull String routeId,
            @NonNull String routeType, long requestId);

    /**
     * Called when a session is about to be destroyed.
     * You can clean up your session here. This can happen by the
     * client or provider itself.
     *
     * @param sessionId id of the session being destroyed.
     * @param lastSessionInfo information of the session being destroyed.
     * @see #releaseSession(String)
     */
    public abstract void onDestroySession(@NonNull String sessionId,
            @NonNull RouteSessionInfo lastSessionInfo);

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
    public abstract void onSelectRoute(@NonNull String sessionId, @NonNull String routeId);

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
    public abstract void onDeselectRoute(@NonNull String sessionId, @NonNull String routeId);

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
    public abstract void onTransferToRoute(@NonNull String sessionId, @NonNull String routeId);

    /**
     * Called when the {@link RouteDiscoveryRequest discovery request} has changed.
     * <p>
     * Whenever an application registers a {@link MediaRouter2.RouteCallback callback},
     * it also provides a discovery request to specify types of routes that it is interested in.
     * The media router combines all of these discovery request into a single discovery request
     * and notifies each provider.
     * </p><p>
     * The provider should examine {@link RouteDiscoveryRequest#getRouteTypes() route types}
     * in the discovery request to determine what kind of routes it should try to discover
     * and whether it should perform active or passive scans. In many cases, the provider may be
     * able to save power by not performing any scans when the request doesn't have any matching
     * route types.
     * </p>
     *
     * @param request the new discovery request
     */
    public void onDiscoveryRequestChanged(@NonNull RouteDiscoveryRequest request) {}

    /**
     * Updates provider info and publishes routes and session info.
     */
    public final void updateProviderInfo(@NonNull MediaRoute2ProviderInfo providerInfo) {
        mProviderInfo = Objects.requireNonNull(providerInfo, "providerInfo must not be null");
        schedulePublishState();
    }

    void setClient(IMediaRoute2ProviderClient client) {
        mClient = client;
        schedulePublishState();
    }

    void schedulePublishState() {
        if (mStatePublishScheduled.compareAndSet(false, true)) {
            mHandler.post(this::publishState);
        }
    }

    private void publishState() {
        if (!mStatePublishScheduled.compareAndSet(true, false)) {
            return;
        }

        if (mClient == null) {
            return;
        }

        List<RouteSessionInfo> sessionInfos;
        synchronized (mSessionLock) {
            sessionInfos = new ArrayList<>(mSessionInfo.values());
        }
        try {
            mClient.updateState(mProviderInfo, sessionInfos);
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
                String routeType, long requestId) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onCreateSession,
                    MediaRoute2ProviderService.this, packageName, routeId, routeType,
                    requestId));
        }
        @Override
        public void releaseSession(@NonNull String sessionId) {
            if (!checkCallerisSystem()) {
                return;
            }
            if (TextUtils.isEmpty(sessionId)) {
                Log.w(TAG, "releaseSession: Ignoring empty sessionId from system service.");
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::releaseSession,
                    MediaRoute2ProviderService.this, sessionId));
        }

        @Override
        public void selectRoute(@NonNull String sessionId, String routeId) {
            if (!checkCallerisSystem()) {
                return;
            }
            if (TextUtils.isEmpty(sessionId)) {
                Log.w(TAG, "selectRoute: Ignoring empty sessionId from system service.");
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSelectRoute,
                    MediaRoute2ProviderService.this, sessionId, routeId));
        }

        @Override
        public void deselectRoute(@NonNull String sessionId, String routeId) {
            if (!checkCallerisSystem()) {
                return;
            }
            if (TextUtils.isEmpty(sessionId)) {
                Log.w(TAG, "deselectRoute: Ignoring empty sessionId from system service.");
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onDeselectRoute,
                    MediaRoute2ProviderService.this, sessionId, routeId));
        }

        @Override
        public void transferToRoute(@NonNull String sessionId, String routeId) {
            if (!checkCallerisSystem()) {
                return;
            }
            if (TextUtils.isEmpty(sessionId)) {
                Log.w(TAG, "transferToRoute: Ignoring empty sessionId from system service.");
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onTransferToRoute,
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
