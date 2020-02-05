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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for media route provider services.
 * <p>
 * Media route provider services are used to publish {@link MediaRoute2Info media routes} such as
 * speakers, TVs, etc. The routes are published by calling {@link #notifyRoutes(Collection)}.
 * Media apps which use {@link MediaRouter2} can request to play their media on the routes.
 * </p><p>
 * When {@link MediaRouter2 media router} wants to play media on a route,
 * {@link #onCreateSession(String, String, long, Bundle)} will be called to handle the request.
 * A session can be considered as a group of currently selected routes for each connection.
 * Create and manage the sessions by yourself, and notify the {@link RoutingSessionInfo
 * session infos} when there are any changes.
 * </p><p>
 * The system media router service will bind to media route provider services when a
 * {@link RouteDiscoveryPreference discovery preference} is registered via
 * a {@link MediaRouter2 media router} by an application. See
 * {@link #onDiscoveryPreferenceChanged(RouteDiscoveryPreference)} for the details.
 * </p>
 */
public abstract class MediaRoute2ProviderService extends Service {
    private static final String TAG = "MR2ProviderService";

    /**
     * The {@link Intent} action that must be declared as handled by the service.
     * Put this in your manifest to provide media routes.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.media.MediaRoute2ProviderService";

    /**
     * The request ID to pass {@link #notifySessionCreated(RoutingSessionInfo, long)}
     * when {@link MediaRoute2ProviderService} created a session although there was no creation
     * request.
     *
     * @see #notifySessionCreated(RoutingSessionInfo, long)
     */
    public static final long REQUEST_ID_UNKNOWN = 0;

    private final Handler mHandler;
    private final Object mSessionLock = new Object();
    private final AtomicBoolean mStatePublishScheduled = new AtomicBoolean(false);
    private ProviderStub mStub;
    private IMediaRoute2ProviderClient mClient;
    private MediaRoute2ProviderInfo mProviderInfo;

    @GuardedBy("mSessionLock")
    private ArrayMap<String, RoutingSessionInfo> mSessionInfo = new ArrayMap<>();

    public MediaRoute2ProviderService() {
        mHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * If overriding this method, call through to the super method for any unknown actions.
     * <p>
     * {@inheritDoc}
     */
    @CallSuper
    @Override
    @Nullable
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
     * @hide
     */
    //TODO: Discuss what to use for request (e.g., Intent? Request class?)
    public void onControlRequest(@NonNull String routeId, @NonNull Intent request) {}

    /**
     * Called when a volume setting is requested on a route of the provider
     *
     * @param routeId the id of the route
     * @param volume the target volume
     * @see MediaRoute2Info#getVolumeMax()
     */
    public abstract void onSetRouteVolume(@NonNull String routeId, int volume);

    /**
     * Called when {@link MediaRouter2.RoutingController#setVolume(int)} is called on
     * a routing session of the provider
     *
     * @param sessionId the id of the routing session
     * @param volume the target volume
     */
    public abstract void onSetSessionVolume(@NonNull String sessionId, int volume);

    /**
     * Gets information of the session with the given id.
     *
     * @param sessionId id of the session
     * @return information of the session with the given id.
     *         null if the session is released or ID is not valid.
     */
    @Nullable
    public final RoutingSessionInfo getSessionInfo(@NonNull String sessionId) {
        if (TextUtils.isEmpty(sessionId)) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
        synchronized (mSessionLock) {
            return mSessionInfo.get(sessionId);
        }
    }

    /**
     * Gets the list of {@link RoutingSessionInfo session info} that the provider service maintains.
     */
    @NonNull
    public final List<RoutingSessionInfo> getAllSessionInfo() {
        synchronized (mSessionLock) {
            return new ArrayList<>(mSessionInfo.values());
        }
    }

    /**
     * Notifies clients of that the session is created and ready for use.
     * <p>
     * If this session is created without any creation request, use {@link #REQUEST_ID_UNKNOWN}
     * as the request ID.
     *
     * @param sessionInfo information of the new session.
     *                    The {@link RoutingSessionInfo#getId() id} of the session must be unique.
     * @param requestId id of the previous request to create this session provided in
     *                  {@link #onCreateSession(String, String, long, Bundle)}
     * @see #onCreateSession(String, String, long, Bundle)
     * @see #getSessionInfo(String)
     */
    public final void notifySessionCreated(@NonNull RoutingSessionInfo sessionInfo,
            long requestId) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        String sessionId = sessionInfo.getId();
        synchronized (mSessionLock) {
            if (mSessionInfo.containsKey(sessionId)) {
                Log.w(TAG, "Ignoring duplicate session id.");
                return;
            }
            mSessionInfo.put(sessionInfo.getId(), sessionInfo);
        }

        if (mClient == null) {
            return;
        }
        try {
            // TODO: Calling binder calls in multiple thread may cause timing issue.
            //       Consider to change implementations to avoid the problems.
            //       For example, post binder calls, always send all sessions at once, etc.
            mClient.notifySessionCreated(sessionInfo, requestId);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify session created.");
        }
    }

    /**
     * Notifies clients of that the session could not be created.
     *
     * @param requestId id of the previous request to create the session provided in
     *                  {@link #onCreateSession(String, String, long, Bundle)}.
     * @see #onCreateSession(String, String, long, Bundle)
     */
    public final void notifySessionCreationFailed(long requestId) {
        if (mClient == null) {
            return;
        }
        try {
            mClient.notifySessionCreationFailed(requestId);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify session creation failed.");
        }
    }

    /**
     * Notifies the existing session is updated. For example, when
     * {@link RoutingSessionInfo#getSelectedRoutes() selected routes} are changed.
     */
    public final void notifySessionUpdated(@NonNull RoutingSessionInfo sessionInfo) {
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
            mClient.notifySessionUpdated(sessionInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify session info changed.");
        }
    }

    /**
     * Notifies that the session is released.
     *
     * @param sessionId id of the released session.
     * @see #onReleaseSession(String)
     */
    public final void notifySessionReleased(@NonNull String sessionId) {
        if (TextUtils.isEmpty(sessionId)) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
        RoutingSessionInfo sessionInfo;
        synchronized (mSessionLock) {
            sessionInfo = mSessionInfo.remove(sessionId);
        }

        if (sessionInfo == null) {
            Log.w(TAG, "Ignoring unknown session info.");
            return;
        }

        if (mClient == null) {
            return;
        }
        try {
            mClient.notifySessionReleased(sessionInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify session info changed.");
        }
    }

    /**
     * Called when the service receives a request to create a session.
     * <p>
     * You should create and maintain your own session and notifies the client of
     * session info. Call {@link #notifySessionCreated(RoutingSessionInfo, long)}
     * with the given {@code requestId} to notify the information of a new session.
     * The created session must have the same route feature and must include the given route
     * specified by {@code routeId}.
     * <p>
     * If the session can be controlled, you can optionally pass the control hints to
     * {@link RoutingSessionInfo.Builder#setControlHints(Bundle)}. Control hints is a
     * {@link Bundle} which contains how to control the session.
     * <p>
     * If you can't create the session or want to reject the request, call
     * {@link #notifySessionCreationFailed(long)} with the given {@code requestId}.
     *
     * @param packageName the package name of the application that selected the route
     * @param routeId the id of the route initially being connected
     * @param requestId the id of this session creation request
     * @param sessionHints an optional bundle of app-specific arguments sent by
     *                     {@link MediaRouter2}, or null if none. The contents of this bundle
     *                     may affect the result of session creation.
     *
     * @see RoutingSessionInfo.Builder#Builder(String, String)
     * @see RoutingSessionInfo.Builder#addSelectedRoute(String)
     * @see RoutingSessionInfo.Builder#setControlHints(Bundle)
     */
    public abstract void onCreateSession(@NonNull String packageName, @NonNull String routeId,
            long requestId, @Nullable Bundle sessionHints);

    /**
     * Called when the session should be released. A client of the session or system can request
     * a session to be released.
     * <p>
     * After releasing the session, call {@link #notifySessionReleased(String)}
     * with the ID of the released session.
     *
     * Note: Calling {@link #notifySessionReleased(String)} will <em>NOT</em> trigger
     * this method to be called.
     *
     * @param sessionId id of the session being released.
     * @see #notifySessionReleased(String)
     * @see #getSessionInfo(String)
     */
    public abstract void onReleaseSession(@NonNull String sessionId);

    //TODO: make a way to reject the request
    /**
     * Called when a client requests selecting a route for the session.
     * After the route is selected, call {@link #notifySessionUpdated(RoutingSessionInfo)}
     * to update session info.
     *
     * @param sessionId id of the session
     * @param routeId id of the route
     */
    public abstract void onSelectRoute(@NonNull String sessionId, @NonNull String routeId);

    //TODO: make a way to reject the request
    /**
     * Called when a client requests deselecting a route from the session.
     * After the route is deselected, call {@link #notifySessionUpdated(RoutingSessionInfo)}
     * to update session info.
     *
     * @param sessionId id of the session
     * @param routeId id of the route
     */
    public abstract void onDeselectRoute(@NonNull String sessionId, @NonNull String routeId);

    //TODO: make a way to reject the request
    /**
     * Called when a client requests transferring a session to a route.
     * After the transfer is finished, call {@link #notifySessionUpdated(RoutingSessionInfo)}
     * to update session info.
     *
     * @param sessionId id of the session
     * @param routeId id of the route
     */
    public abstract void onTransferToRoute(@NonNull String sessionId, @NonNull String routeId);

    /**
     * Called when the {@link RouteDiscoveryPreference discovery preference} has changed.
     * <p>
     * Whenever an application registers a {@link MediaRouter2.RouteCallback callback},
     * it also provides a discovery preference to specify features of routes that it is interested
     * in. The media router combines all of these discovery request into a single discovery
     * preference and notifies each provider.
     * </p><p>
     * The provider should examine {@link RouteDiscoveryPreference#getPreferredFeatures()
     * preferred features} in the discovery preference to determine what kind of routes it should
     * try to discover and whether it should perform active or passive scans. In many cases,
     * the provider may be able to save power by not performing any scans when the request doesn't
     * have any matching route features.
     * </p>
     *
     * @param preference the new discovery preference
     */
    public void onDiscoveryPreferenceChanged(@NonNull RouteDiscoveryPreference preference) {}

    /**
     * Updates routes of the provider and notifies the system media router service.
     */
    public final void notifyRoutes(@NonNull Collection<MediaRoute2Info> routes) {
        Objects.requireNonNull(routes, "routes must not be null");
        mProviderInfo = new MediaRoute2ProviderInfo.Builder()
                .addRoutes(routes)
                .build();
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

        List<RoutingSessionInfo> sessionInfos;
        synchronized (mSessionLock) {
            sessionInfos = new ArrayList<>(mSessionInfo.values());
        }
        try {
            mClient.updateState(mProviderInfo);
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
        public void requestCreateSession(String packageName, String routeId, long requestId,
                @Nullable Bundle requestCreateSession) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onCreateSession,
                    MediaRoute2ProviderService.this, packageName, routeId, requestId,
                    requestCreateSession));
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
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onReleaseSession,
                    MediaRoute2ProviderService.this, sessionId));
        }

        @Override
        public void updateDiscoveryPreference(RouteDiscoveryPreference discoveryPreference) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(
                    MediaRoute2ProviderService::onDiscoveryPreferenceChanged,
                    MediaRoute2ProviderService.this, discoveryPreference));
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
        public void setRouteVolume(String routeId, int volume) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSetRouteVolume,
                    MediaRoute2ProviderService.this, routeId, volume));
        }

        @Override
        public void setSessionVolume(String sessionId, int volume) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSetSessionVolume,
                    MediaRoute2ProviderService.this, sessionId, volume));
        }
    }
}
