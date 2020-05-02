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
import android.annotation.IntDef;
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
 * {@link #onCreateSession(long, String, String, Bundle)} will be called to handle the request.
 * A session can be considered as a group of currently selected routes for each connection.
 * Create and manage the sessions by yourself, and notify the {@link RoutingSessionInfo
 * session infos} when there are any changes.
 * </p><p>
 * The system media router service will bind to media route provider services when a
 * {@link RouteDiscoveryPreference discovery preference} is registered via
 * a {@link MediaRouter2 media router} by an application. See
 * {@link #onDiscoveryPreferenceChanged(RouteDiscoveryPreference)} for the details.
 * </p>
 * Use {@link #notifyRequestFailed(long, int)} to notify the failure with previously received
 * request ID.
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
     * The request ID to pass {@link #notifySessionCreated(long, RoutingSessionInfo)}
     * when {@link MediaRoute2ProviderService} created a session although there was no creation
     * request.
     *
     * @see #notifySessionCreated(long, RoutingSessionInfo)
     */
    public static final long REQUEST_ID_NONE = 0;

    /**
     * The request has failed due to unknown reason.
     *
     * @see #notifyRequestFailed(long, int)
     */
    public static final int REASON_UNKNOWN_ERROR = 0;

    /**
     * The request has failed since this service rejected the request.
     *
     * @see #notifyRequestFailed(long, int)
     */
    public static final int REASON_REJECTED = 1;

    /**
     * The request has failed due to a network error.
     *
     * @see #notifyRequestFailed(long, int)
     */
    public static final int REASON_NETWORK_ERROR = 2;

    /**
     * The request has failed since the requested route is no longer available.
     *
     * @see #notifyRequestFailed(long, int)
     */
    public static final int REASON_ROUTE_NOT_AVAILABLE = 3;

    /**
     * The request has failed since the request is not valid. For example, selecting a route
     * which is not selectable.
     *
     * @see #notifyRequestFailed(long, int)
     */
    public static final int REASON_INVALID_COMMAND = 4;

    /**
     * @hide
     */
    @IntDef(prefix = "REASON_", value = {
            REASON_UNKNOWN_ERROR, REASON_REJECTED, REASON_NETWORK_ERROR, REASON_ROUTE_NOT_AVAILABLE,
            REASON_INVALID_COMMAND
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Reason {}

    private final Handler mHandler;
    private final Object mSessionLock = new Object();
    private final AtomicBoolean mStatePublishScheduled = new AtomicBoolean(false);
    private MediaRoute2ProviderServiceStub mStub;
    private IMediaRoute2ProviderServiceCallback mRemoteCallback;
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
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            if (mStub == null) {
                mStub = new MediaRoute2ProviderServiceStub();
            }
            return mStub;
        }
        return null;
    }

    /**
     * Called when a volume setting is requested on a route of the provider
     *
     * @param requestId the id of this request
     * @param routeId the id of the route
     * @param volume the target volume
     * @see MediaRoute2Info.Builder#setVolume(int)
     */
    public abstract void onSetRouteVolume(long requestId, @NonNull String routeId, int volume);

    /**
     * Called when {@link MediaRouter2.RoutingController#setVolume(int)} is called on
     * a routing session of the provider
     *
     * @param requestId the id of this request
     * @param sessionId the id of the routing session
     * @param volume the target volume
     * @see RoutingSessionInfo.Builder#setVolume(int)
     */
    public abstract void onSetSessionVolume(long requestId, @NonNull String sessionId, int volume);

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
     * If this session is created without any creation request, use {@link #REQUEST_ID_NONE}
     * as the request ID.
     *
     * @param requestId id of the previous request to create this session provided in
     *                  {@link #onCreateSession(long, String, String, Bundle)}. Can be
     *                  {@link #REQUEST_ID_NONE} if this session is created without any request.
     * @param sessionInfo information of the new session.
     *                    The {@link RoutingSessionInfo#getId() id} of the session must be unique.
     * @see #onCreateSession(long, String, String, Bundle)
     * @see #getSessionInfo(String)
     */
    public final void notifySessionCreated(long requestId,
            @NonNull RoutingSessionInfo sessionInfo) {
        Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

        String sessionId = sessionInfo.getId();
        synchronized (mSessionLock) {
            if (mSessionInfo.containsKey(sessionId)) {
                // TODO: Notify failure to the requester, and throw exception if needed.
                Log.w(TAG, "Ignoring duplicate session id.");
                return;
            }
            mSessionInfo.put(sessionInfo.getId(), sessionInfo);
        }

        if (mRemoteCallback == null) {
            return;
        }
        try {
            // TODO: Calling binder calls in multiple thread may cause timing issue.
            //       Consider to change implementations to avoid the problems.
            //       For example, post binder calls, always send all sessions at once, etc.
            mRemoteCallback.notifySessionCreated(requestId, sessionInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify session created.");
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

        if (mRemoteCallback == null) {
            return;
        }
        try {
            mRemoteCallback.notifySessionUpdated(sessionInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify session info changed.");
        }
    }

    /**
     * Notifies that the session is released.
     *
     * @param sessionId id of the released session.
     * @see #onReleaseSession(long, String)
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

        if (mRemoteCallback == null) {
            return;
        }
        try {
            mRemoteCallback.notifySessionReleased(sessionInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify session info changed.");
        }
    }

    /**
     * Notifies to the client that the request has failed.
     *
     * @param requestId the ID of the previous request
     * @param reason the reason why the request has failed
     *
     * @see #REASON_UNKNOWN_ERROR
     * @see #REASON_REJECTED
     * @see #REASON_NETWORK_ERROR
     * @see #REASON_ROUTE_NOT_AVAILABLE
     * @see #REASON_INVALID_COMMAND
     */
    public final void notifyRequestFailed(long requestId, @Reason int reason) {
        if (mRemoteCallback == null) {
            return;
        }
        try {
            mRemoteCallback.notifyRequestFailed(requestId, reason);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify that the request has failed.");
        }
    }

    /**
     * Called when the service receives a request to create a session.
     * <p>
     * You should create and maintain your own session and notifies the client of
     * session info. Call {@link #notifySessionCreated(long, RoutingSessionInfo)}
     * with the given {@code requestId} to notify the information of a new session.
     * The created session must have the same route feature and must include the given route
     * specified by {@code routeId}.
     * <p>
     * If the session can be controlled, you can optionally pass the control hints to
     * {@link RoutingSessionInfo.Builder#setControlHints(Bundle)}. Control hints is a
     * {@link Bundle} which contains how to control the session.
     * <p>
     * If you can't create the session or want to reject the request, call
     * {@link #notifyRequestFailed(long, int)} with the given {@code requestId}.
     *
     * @param requestId the id of this request
     * @param packageName the package name of the application that selected the route
     * @param routeId the id of the route initially being connected
     * @param sessionHints an optional bundle of app-specific arguments sent by
     *                     {@link MediaRouter2}, or null if none. The contents of this bundle
     *                     may affect the result of session creation.
     *
     * @see RoutingSessionInfo.Builder#RoutingSessionInfo.Builder(String, String)
     * @see RoutingSessionInfo.Builder#addSelectedRoute(String)
     * @see RoutingSessionInfo.Builder#setControlHints(Bundle)
     */
    public abstract void onCreateSession(long requestId, @NonNull String packageName,
            @NonNull String routeId, @Nullable Bundle sessionHints);

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
     * @param requestId the id of this request
     * @param sessionId id of the session being released.
     * @see #notifySessionReleased(String)
     * @see #getSessionInfo(String)
     */
    public abstract void onReleaseSession(long requestId, @NonNull String sessionId);

    /**
     * Called when a client requests selecting a route for the session.
     * After the route is selected, call {@link #notifySessionUpdated(RoutingSessionInfo)}
     * to update session info.
     *
     * @param requestId the id of this request
     * @param sessionId id of the session
     * @param routeId id of the route
     */
    public abstract void onSelectRoute(long requestId, @NonNull String sessionId,
            @NonNull String routeId);

    /**
     * Called when a client requests deselecting a route from the session.
     * After the route is deselected, call {@link #notifySessionUpdated(RoutingSessionInfo)}
     * to update session info.
     *
     * @param requestId the id of this request
     * @param sessionId id of the session
     * @param routeId id of the route
     */
    public abstract void onDeselectRoute(long requestId, @NonNull String sessionId,
            @NonNull String routeId);

    /**
     * Called when a client requests transferring a session to a route.
     * After the transfer is finished, call {@link #notifySessionUpdated(RoutingSessionInfo)}
     * to update session info.
     *
     * @param requestId the id of this request
     * @param sessionId id of the session
     * @param routeId id of the route
     */
    public abstract void onTransferToRoute(long requestId, @NonNull String sessionId,
            @NonNull String routeId);

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

    void setCallback(IMediaRoute2ProviderServiceCallback callback) {
        mRemoteCallback = callback;
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

        if (mRemoteCallback == null) {
            return;
        }

        try {
            mRemoteCallback.updateState(mProviderInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to send onProviderInfoUpdated");
        }
    }

    final class MediaRoute2ProviderServiceStub extends IMediaRoute2ProviderService.Stub {
        MediaRoute2ProviderServiceStub() { }

        boolean checkCallerisSystem() {
            return Binder.getCallingUid() == Process.SYSTEM_UID;
        }

        @Override
        public void setCallback(IMediaRoute2ProviderServiceCallback callback) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::setCallback,
                    MediaRoute2ProviderService.this, callback));
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
        public void setRouteVolume(long requestId, String routeId, int volume) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSetRouteVolume,
                    MediaRoute2ProviderService.this, requestId, routeId, volume));
        }

        @Override
        public void requestCreateSession(long requestId, String packageName, String routeId,
                @Nullable Bundle requestCreateSession) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onCreateSession,
                    MediaRoute2ProviderService.this, requestId, packageName, routeId,
                    requestCreateSession));
        }

        //TODO: Ignore requests with unknown session ID.
        @Override
        public void selectRoute(long requestId, String sessionId, String routeId) {
            if (!checkCallerisSystem()) {
                return;
            }
            if (TextUtils.isEmpty(sessionId)) {
                Log.w(TAG, "selectRoute: Ignoring empty sessionId from system service.");
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSelectRoute,
                    MediaRoute2ProviderService.this, requestId, sessionId, routeId));
        }

        @Override
        public void deselectRoute(long requestId, String sessionId, String routeId) {
            if (!checkCallerisSystem()) {
                return;
            }
            if (TextUtils.isEmpty(sessionId)) {
                Log.w(TAG, "deselectRoute: Ignoring empty sessionId from system service.");
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onDeselectRoute,
                    MediaRoute2ProviderService.this, requestId, sessionId, routeId));
        }

        @Override
        public void transferToRoute(long requestId, String sessionId, String routeId) {
            if (!checkCallerisSystem()) {
                return;
            }
            if (TextUtils.isEmpty(sessionId)) {
                Log.w(TAG, "transferToRoute: Ignoring empty sessionId from system service.");
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onTransferToRoute,
                    MediaRoute2ProviderService.this, requestId, sessionId, routeId));
        }

        @Override
        public void setSessionVolume(long requestId, String sessionId, int volume) {
            if (!checkCallerisSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSetSessionVolume,
                    MediaRoute2ProviderService.this, requestId, sessionId, volume));
        }

        @Override
        public void releaseSession(long requestId, String sessionId) {
            if (!checkCallerisSystem()) {
                return;
            }
            if (TextUtils.isEmpty(sessionId)) {
                Log.w(TAG, "releaseSession: Ignoring empty sessionId from system service.");
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onReleaseSession,
                    MediaRoute2ProviderService.this, requestId, sessionId));
        }
    }
}
