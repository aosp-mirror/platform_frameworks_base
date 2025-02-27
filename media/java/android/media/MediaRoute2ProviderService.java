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

import static java.util.Objects.requireNonNull;

import android.Manifest;
import android.annotation.CallSuper;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
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
import android.util.LongSparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.media.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
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
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * The {@link Intent} action that must be declared as handled by the service.
     * Put this in your manifest to provide media routes.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.media.MediaRoute2ProviderService";

    /**
     * A category that indicates that the declaring service supports routing of the system media.
     *
     * <p>Providers must include this action if they intend to publish routes that support the
     * system media, as described by {@link MediaRoute2Info#getSupportedRoutingTypes()}.
     *
     * @see #onCreateSystemRoutingSession
     * @hide
     */
    // TODO: b/362507305 - Unhide once the implementation and CTS are in place.
    @FlaggedApi(Flags.FLAG_ENABLE_MIRRORING_IN_MEDIA_ROUTER_2)
    @SdkConstant(SdkConstant.SdkConstantType.INTENT_CATEGORY)
    public static final String SERVICE_INTERFACE_SYSTEM_MEDIA =
            "android.media.MediaRoute2ProviderService.SYSTEM_MEDIA";

    /**
     * A category indicating that the associated provider is only intended for use within the app
     * that hosts the provider.
     *
     * <p>Declaring this category helps the system save resources by avoiding the launch of services
     * whose routes are known to be private to the app that provides them.
     *
     * @hide
     */
    public static final String CATEGORY_SELF_SCAN_ONLY =
            "android.media.MediaRoute2ProviderService.SELF_SCAN_ONLY";

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
     * The request has failed because the requested operation is not implemented by the provider.
     *
     * @see #notifyRequestFailed
     * @hide
     */
    // TODO: b/362507305 - Unhide once the implementation and CTS are in place.
    @FlaggedApi(Flags.FLAG_ENABLE_MIRRORING_IN_MEDIA_ROUTER_2)
    public static final int REASON_UNIMPLEMENTED = 5;

    /**
     * The request has failed because the provider has failed to route system media.
     *
     * @see #notifyRequestFailed
     * @hide
     */
    // TODO: b/362507305 - Unhide once the implementation and CTS are in place.
    @FlaggedApi(Flags.FLAG_ENABLE_MIRRORING_IN_MEDIA_ROUTER_2)
    public static final int REASON_FAILED_TO_REROUTE_SYSTEM_MEDIA = 6;

    /** @hide */
    @IntDef(
            prefix = "REASON_",
            value = {
                REASON_UNKNOWN_ERROR,
                REASON_REJECTED,
                REASON_NETWORK_ERROR,
                REASON_ROUTE_NOT_AVAILABLE,
                REASON_INVALID_COMMAND,
                REASON_UNIMPLEMENTED,
                REASON_FAILED_TO_REROUTE_SYSTEM_MEDIA
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Reason {}

    private static final int MAX_REQUEST_IDS_SIZE = 500;

    private final Handler mHandler;
    private final Object mSessionLock = new Object();
    private final Object mRequestIdsLock = new Object();
    private final AtomicBoolean mStatePublishScheduled = new AtomicBoolean(false);
    private final AtomicBoolean mSessionUpdateScheduled = new AtomicBoolean(false);
    private MediaRoute2ProviderServiceStub mStub;
    /** Populated by system_server in {@link #setCallback}. Monotonically non-null. */
    private IMediaRoute2ProviderServiceCallback mRemoteCallback;
    private volatile MediaRoute2ProviderInfo mProviderInfo;

    @GuardedBy("mRequestIdsLock")
    private final Deque<Long> mRequestIds = new ArrayDeque<>(MAX_REQUEST_IDS_SIZE);

    /**
     * Maps system media session creation request ids to a package uid whose media to route. The
     * value may be {@link Process#INVALID_UID} for routing sessions that don't affect a specific
     * package (for example, if they affect the entire system).
     */
    @GuardedBy("mRequestIdsLock")
    private final LongSparseArray<Integer> mSystemMediaSessionCreationRequests =
            new LongSparseArray<>();

    @GuardedBy("mSessionLock")
    private final ArrayMap<String, RoutingSessionInfo> mSessionInfos = new ArrayMap<>();

    @GuardedBy("mSessionLock")
    private final ArrayMap<String, MediaStreams> mOngoingMediaStreams = new ArrayMap<>();

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
     * @param requestId the ID of this request
     * @param routeId the ID of the route
     * @param volume the target volume
     * @see MediaRoute2Info.Builder#setVolume(int)
     */
    public abstract void onSetRouteVolume(long requestId, @NonNull String routeId, int volume);

    /**
     * Called when {@link MediaRouter2.RoutingController#setVolume(int)} is called on
     * a routing session of the provider
     *
     * @param requestId the ID of this request
     * @param sessionId the ID of the routing session
     * @param volume the target volume
     * @see RoutingSessionInfo.Builder#setVolume(int)
     */
    public abstract void onSetSessionVolume(long requestId, @NonNull String sessionId, int volume);

    /**
     * Gets information of the session with the given id.
     *
     * @param sessionId the ID of the session
     * @return information of the session with the given id.
     *         null if the session is released or ID is not valid.
     */
    @Nullable
    public final RoutingSessionInfo getSessionInfo(@NonNull String sessionId) {
        if (TextUtils.isEmpty(sessionId)) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
        synchronized (mSessionLock) {
            return mSessionInfos.get(sessionId);
        }
    }

    /**
     * Gets the list of {@link RoutingSessionInfo session info} that the provider service maintains.
     */
    @NonNull
    public final List<RoutingSessionInfo> getAllSessionInfo() {
        synchronized (mSessionLock) {
            return new ArrayList<>(mSessionInfos.values());
        }
    }

    /**
     * Notifies clients of that the session is created and ready for use.
     * <p>
     * If this session is created without any creation request, use {@link #REQUEST_ID_NONE}
     * as the request ID.
     *
     * @param requestId the ID of the previous request to create this session provided in
     *                  {@link #onCreateSession(long, String, String, Bundle)}. Can be
     *                  {@link #REQUEST_ID_NONE} if this session is created without any request.
     * @param sessionInfo information of the new session.
     *                    The {@link RoutingSessionInfo#getId() id} of the session must be unique.
     * @see #onCreateSession(long, String, String, Bundle)
     * @see #getSessionInfo(String)
     */
    public final void notifySessionCreated(long requestId,
            @NonNull RoutingSessionInfo sessionInfo) {
        requireNonNull(sessionInfo, "sessionInfo must not be null");

        if (DEBUG) {
            Log.d(TAG, "notifySessionCreated: Creating a session. requestId=" + requestId
                    + ", sessionInfo=" + sessionInfo);
        }

        if (requestId != REQUEST_ID_NONE && !removeRequestId(requestId)) {
            Log.w(TAG, "notifySessionCreated: The requestId doesn't exist. requestId=" + requestId);
            return;
        }

        String sessionId = sessionInfo.getId();
        synchronized (mSessionLock) {
            if (mSessionInfos.containsKey(sessionId)) {
                Log.w(TAG, "notifySessionCreated: Ignoring duplicate session id.");
                return;
            }
            mSessionInfos.put(sessionInfo.getId(), sessionInfo);

            if (mRemoteCallback == null) {
                return;
            }
            try {
                mRemoteCallback.notifySessionCreated(requestId, sessionInfo);
            } catch (RemoteException ex) {
                Log.w(TAG, "Failed to notify session created.");
            }
        }
    }

    /**
     * Notifies the system of the successful creation of a system media routing session.
     *
     * <p>This method can only be called as the result of a prior call to {@link
     * #onCreateSystemRoutingSession}.
     *
     * @param requestId the ID of the {@link #onCreateSystemRoutingSession} request which this call
     *     is in response to.
     * @param sessionInfo a {@link RoutingSessionInfo} that describes the newly created routing
     *     session.
     * @param formats the {@link MediaStreamsFormats} that describes the format for the {@link
     *     MediaStreams} to return.
     * @return a {@link MediaStreams} instance that holds the media streams to route as part of the
     *     newly created routing session. May be null if system media capture failed, in which case
     *     you can ignore the return value, as you will receive a call to {@link #onReleaseSession}
     *     where you can clean up this session
     * @hide
     */
    // TODO: b/362507305 - Unhide once the implementation and CTS are in place.
    @FlaggedApi(Flags.FLAG_ENABLE_MIRRORING_IN_MEDIA_ROUTER_2)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    @Nullable
    public final MediaStreams notifySystemMediaSessionCreated(
            long requestId,
            @NonNull RoutingSessionInfo sessionInfo,
            @NonNull MediaStreamsFormats formats) {
        requireNonNull(sessionInfo, "sessionInfo must not be null");
        requireNonNull(formats, "formats must not be null");
        if (DEBUG) {
            Log.d(
                    TAG,
                    "notifySystemMediaSessionCreated: Creating a session. requestId="
                            + requestId
                            + ", sessionInfo="
                            + sessionInfo);
        }

        Integer uid;
        synchronized (mRequestIdsLock) {
            uid = mSystemMediaSessionCreationRequests.get(requestId);
            mSystemMediaSessionCreationRequests.remove(requestId);
        }

        if (uid == null) {
            throw new IllegalStateException(
                    "Unexpected system routing session created (request id="
                            + requestId
                            + "):"
                            + sessionInfo);
        }

        if (mRemoteCallback == null) {
            throw new IllegalStateException("Unexpected: remote callback is null.");
        }

        int routingTypes = 0;
        var providerInfo = mProviderInfo;
        for (String selectedRouteId : sessionInfo.getSelectedRoutes()) {
            MediaRoute2Info route = providerInfo.mRoutes.get(selectedRouteId);
            if (route == null) {
                throw new IllegalArgumentException(
                        "Invalid selected route with id: " + selectedRouteId);
            }
            routingTypes |= route.getSupportedRoutingTypes();
        }

        if ((routingTypes & MediaRoute2Info.FLAG_ROUTING_TYPE_SYSTEM_AUDIO) == 0) {
            // TODO: b/380431086 - Populate video stream once we add support for video.
            throw new IllegalArgumentException(
                    "Selected routes for system media don't support any system media routing"
                            + " types.");
        }

        AudioFormat audioFormat = formats.mAudioFormat;
        var mediaStreamsBuilder = new MediaStreams.Builder();
        if (audioFormat != null) {
            populateAudioStream(audioFormat, uid, mediaStreamsBuilder);
        }
        // TODO: b/380431086 - Populate video stream once we add support for video.

        MediaStreams streams = mediaStreamsBuilder.build();
        var audioRecord = streams.mAudioRecord;
        if (audioRecord == null) {
            Log.e(
                    TAG,
                    "Audio record is not populated. Returning an empty stream and scheduling the"
                            + " session release for: "
                            + sessionInfo);
            mHandler.post(() -> onReleaseSession(REQUEST_ID_NONE, sessionInfo.getOriginalId()));
            notifyRequestFailed(requestId, REASON_FAILED_TO_REROUTE_SYSTEM_MEDIA);
            return null;
        }

        synchronized (mSessionLock) {
            try {
                mRemoteCallback.notifySessionCreated(requestId, sessionInfo);
            } catch (RemoteException ex) {
                ex.rethrowFromSystemServer();
            }
            mOngoingMediaStreams.put(sessionInfo.getOriginalId(), streams);
            return streams;
        }
    }

    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    private void populateAudioStream(
            AudioFormat audioFormat, int uid, MediaStreams.Builder builder) {
        var audioAttributes =
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
        var audioMixingRuleBuilder =
                new AudioMixingRule.Builder()
                        .addRule(audioAttributes, AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
        if (uid != Process.INVALID_UID) {
            audioMixingRuleBuilder.addMixRule(AudioMixingRule.RULE_MATCH_UID, uid);
        }

        AudioMix mix =
                new AudioMix.Builder(audioMixingRuleBuilder.build())
                        .setFormat(audioFormat)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK)
                        .build();
        AudioPolicy audioPolicy =
                new AudioPolicy.Builder(this).setLooper(mHandler.getLooper()).addMix(mix).build();
        var audioManager = getSystemService(AudioManager.class);
        if (audioManager == null) {
            Log.e(TAG, "Couldn't fetch the audio manager.");
            return;
        }
        audioManager.registerAudioPolicy(audioPolicy);
        var audioRecord = audioPolicy.createAudioRecordSink(mix);
        if (audioRecord == null) {
            Log.e(TAG, "Audio record creation failed.");
            audioManager.unregisterAudioPolicy(audioPolicy);
            return;
        }
        builder.setAudioStream(audioPolicy, audioRecord);
    }

    /**
     * Notifies the existing session is updated. For example, when
     * {@link RoutingSessionInfo#getSelectedRoutes() selected routes} are changed.
     */
    public final void notifySessionUpdated(@NonNull RoutingSessionInfo sessionInfo) {
        requireNonNull(sessionInfo, "sessionInfo must not be null");

        if (DEBUG) {
            Log.d(TAG, "notifySessionUpdated: Updating session id=" + sessionInfo);
        }

        String sessionId = sessionInfo.getId();
        synchronized (mSessionLock) {
            if (mSessionInfos.containsKey(sessionId)) {
                mSessionInfos.put(sessionId, sessionInfo);
            } else {
                Log.w(TAG, "notifySessionUpdated: Ignoring unknown session info.");
                return;
            }
        }
        scheduleUpdateSessions();
    }

    /**
     * Notifies that the session is released.
     *
     * @param sessionId the ID of the released session.
     * @see #onReleaseSession(long, String)
     */
    public final void notifySessionReleased(@NonNull String sessionId) {
        if (TextUtils.isEmpty(sessionId)) {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
        if (DEBUG) {
            Log.d(TAG, "notifySessionReleased: Releasing session id=" + sessionId);
        }

        RoutingSessionInfo sessionInfo;
        synchronized (mSessionLock) {
            sessionInfo = mSessionInfos.remove(sessionId);
            maybeReleaseMediaStreams(sessionId);

            if (sessionInfo == null) {
                Log.w(TAG, "notifySessionReleased: Ignoring unknown session info.");
                return;
            }

            if (mRemoteCallback == null) {
                return;
            }
            try {
                mRemoteCallback.notifySessionReleased(sessionInfo);
            } catch (RemoteException ex) {
                Log.w(TAG, "Failed to notify session released.", ex);
            }
        }
    }

    /** Releases any system media routing resources associated with the given {@code sessionId}. */
    private void maybeReleaseMediaStreams(String sessionId) {
        if (!Flags.enableMirroringInMediaRouter2()) {
            return;
        }
        synchronized (mSessionLock) {
            var streams = mOngoingMediaStreams.remove(sessionId);
            if (streams != null) {
                releaseAudioStream(streams.mAudioPolicy, streams.mAudioRecord);
                // TODO: b/380431086: Release the video stream once implemented.
            }
        }
    }

    // We cannot reach the code that requires MODIFY_AUDIO_ROUTING without holding it.
    @SuppressWarnings("MissingPermission")
    private void releaseAudioStream(AudioPolicy audioPolicy, AudioRecord audioRecord) {
        if (audioPolicy == null) {
            return;
        }
        var audioManager = getSystemService(AudioManager.class);
        if (audioManager == null) {
            return;
        }
        audioRecord.stop();
        audioManager.unregisterAudioPolicy(audioPolicy);
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

        if (!removeRequestId(requestId)) {
            Log.w(TAG, "notifyRequestFailed: The requestId doesn't exist. requestId="
                    + requestId);
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
     * @param requestId the ID of this request
     * @param packageName the package name of the application that selected the route
     * @param routeId the ID of the route initially being connected
     * @param sessionHints an optional bundle of app-specific arguments sent by
     *                     {@link MediaRouter2}, or null if none. The contents of this bundle
     *                     may affect the result of session creation.
     *
     * @see RoutingSessionInfo.Builder#Builder(String, String)
     * @see RoutingSessionInfo.Builder#addSelectedRoute(String)
     * @see RoutingSessionInfo.Builder#setControlHints(Bundle)
     */
    public abstract void onCreateSession(long requestId, @NonNull String packageName,
            @NonNull String routeId, @Nullable Bundle sessionHints);

    /**
     * Called when the service receives a request to create a system routing session.
     *
     * <p>This method will only be called for routes that support routing of the system media, as
     * described by {@link MediaRoute2Info#getSupportedRoutingTypes()}.
     *
     * <p>Implementors of this method must call {@link #notifySystemMediaSessionCreated} with the
     * given {@code requestId} to indicate a successful session creation. If the session creation
     * fails (for example, if the connection to the receiver device fails), the implementor must
     * call {@link #notifyRequestFailed}, passing the {@code requestId}.
     *
     * <p>Unlike {@link #onCreateSession}, system sessions route the system media (for example,
     * audio and/or video) which is to be retrieved by calling {@link
     * #notifySystemMediaSessionCreated}.
     *
     * <p>Changes to the session can be notified by calling {@link #notifySessionUpdated}.
     *
     * @param requestId the ID of this request
     * @param packageName the package name of the application whose media to route.
     * @param routeId the ID of the route initially being {@link
     *     RoutingSessionInfo#getSelectedRoutes() selected}.
     * @param sessionHints an optional bundle of arguments sent by {@link MediaRouter2}, or null if
     *     none.
     * @see RoutingSessionInfo.Builder
     * @see #notifySystemMediaSessionCreated
     * @hide
     */
    // TODO: b/362507305 - Unhide once the implementation and CTS are in place.
    @FlaggedApi(Flags.FLAG_ENABLE_MIRRORING_IN_MEDIA_ROUTER_2)
    public void onCreateSystemRoutingSession(
            long requestId,
            @NonNull String packageName,
            @NonNull String routeId,
            @Nullable Bundle sessionHints) {
        mHandler.post(() -> notifyRequestFailed(requestId, REASON_UNIMPLEMENTED));
    }

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
     * @param requestId the ID of this request
     * @param sessionId the ID of the session being released.
     * @see #notifySessionReleased(String)
     * @see #getSessionInfo(String)
     */
    public abstract void onReleaseSession(long requestId, @NonNull String sessionId);

    /**
     * Called when a client requests selecting a route for the session.
     * After the route is selected, call {@link #notifySessionUpdated(RoutingSessionInfo)}
     * to update session info.
     *
     * @param requestId the ID of this request
     * @param sessionId the ID of the session
     * @param routeId the ID of the route
     */
    public abstract void onSelectRoute(long requestId, @NonNull String sessionId,
            @NonNull String routeId);

    /**
     * Called when a client requests deselecting a route from the session.
     * After the route is deselected, call {@link #notifySessionUpdated(RoutingSessionInfo)}
     * to update session info.
     *
     * @param requestId the ID of this request
     * @param sessionId the ID of the session
     * @param routeId the ID of the route
     */
    public abstract void onDeselectRoute(long requestId, @NonNull String sessionId,
            @NonNull String routeId);

    /**
     * Called when a client requests transferring a session to a route.
     * After the transfer is finished, call {@link #notifySessionUpdated(RoutingSessionInfo)}
     * to update session info.
     *
     * @param requestId the ID of this request
     * @param sessionId the ID of the session
     * @param routeId the ID of the route
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
        requireNonNull(routes, "routes must not be null");
        List<MediaRoute2Info> sanitizedRoutes = new ArrayList<>(routes.size());

        for (MediaRoute2Info route : routes) {
            if (route.isSystemRouteType()) {
                Log.w(
                        TAG,
                        "Attempting to add a system route type from a non-system route "
                                + "provider. Overriding type to TYPE_UNKNOWN. Route: "
                                + route);
                sanitizedRoutes.add(
                        new MediaRoute2Info.Builder(route)
                                .setType(MediaRoute2Info.TYPE_UNKNOWN)
                                .build());
            } else {
                sanitizedRoutes.add(route);
            }
        }

        mProviderInfo = new MediaRoute2ProviderInfo.Builder().addRoutes(sanitizedRoutes).build();
        schedulePublishState();
    }

    void setCallback(IMediaRoute2ProviderServiceCallback callback) {
        mRemoteCallback = callback;
        schedulePublishState();
        scheduleUpdateSessions();
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

        if (mProviderInfo == null) {
            return;
        }

        try {
            mRemoteCallback.notifyProviderUpdated(mProviderInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to publish provider state.", ex);
        }
    }

    void scheduleUpdateSessions() {
        if (mSessionUpdateScheduled.compareAndSet(false, true)) {
            mHandler.post(this::updateSessions);
        }
    }

    private void updateSessions() {
        if (!mSessionUpdateScheduled.compareAndSet(true, false)) {
            return;
        }

        if (mRemoteCallback == null) {
            return;
        }

        List<RoutingSessionInfo> sessions;
        synchronized (mSessionLock) {
            sessions = new ArrayList<>(mSessionInfos.values());
        }

        try {
            mRemoteCallback.notifySessionsUpdated(sessions);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify session info changed.");
        }

    }

    /**
     * Adds a requestId in the request ID list whose max size is {@link #MAX_REQUEST_IDS_SIZE}.
     * When the max size is reached, the first element is removed (FIFO).
     */
    private void addRequestId(long requestId) {
        synchronized (mRequestIdsLock) {
            if (mRequestIds.size() >= MAX_REQUEST_IDS_SIZE) {
                mRequestIds.removeFirst();
            }
            mRequestIds.addLast(requestId);
        }
    }

    /**
     * Removes the given {@code requestId} from received request ID list.
     * <p>
     * Returns whether the list contains the {@code requestId}. These are the cases when the list
     * doesn't contain the given {@code requestId}:
     * <ul>
     *     <li>This service has never received a request with the requestId. </li>
     *     <li>{@link #notifyRequestFailed} or {@link #notifySessionCreated} already has been called
     *         for the requestId. </li>
     * </ul>
     */
    private boolean removeRequestId(long requestId) {
        synchronized (mRequestIdsLock) {
            return mRequestIds.removeFirstOccurrence(requestId);
        }
    }

    final class MediaRoute2ProviderServiceStub extends IMediaRoute2ProviderService.Stub {
        MediaRoute2ProviderServiceStub() { }

        private boolean checkCallerIsSystem() {
            return Binder.getCallingUid() == Process.SYSTEM_UID;
        }

        private boolean checkSessionIdIsValid(String sessionId, String description) {
            if (TextUtils.isEmpty(sessionId)) {
                Log.w(TAG, description + ": Ignoring empty sessionId from system service.");
                return false;
            }
            if (getSessionInfo(sessionId) == null) {
                Log.w(TAG, description + ": Ignoring unknown session from system service. "
                        + "sessionId=" + sessionId);
                return false;
            }
            return true;
        }

        private boolean checkRouteIdIsValid(String routeId, String description) {
            if (TextUtils.isEmpty(routeId)) {
                Log.w(TAG, description + ": Ignoring empty routeId from system service.");
                return false;
            }
            if (mProviderInfo == null || mProviderInfo.getRoute(routeId) == null) {
                Log.w(TAG, description + ": Ignoring unknown route from system service. "
                        + "routeId=" + routeId);
                return false;
            }
            return true;
        }

        @Override
        public void setCallback(IMediaRoute2ProviderServiceCallback callback) {
            if (!checkCallerIsSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::setCallback,
                    MediaRoute2ProviderService.this, callback));
        }

        @Override
        public void updateDiscoveryPreference(RouteDiscoveryPreference discoveryPreference) {
            if (!checkCallerIsSystem()) {
                return;
            }
            mHandler.sendMessage(obtainMessage(
                    MediaRoute2ProviderService::onDiscoveryPreferenceChanged,
                    MediaRoute2ProviderService.this, discoveryPreference));
        }

        @Override
        public void setRouteVolume(long requestId, String routeId, int volume) {
            if (!checkCallerIsSystem()) {
                return;
            }
            if (!checkRouteIdIsValid(routeId, "setRouteVolume")) {
                return;
            }
            addRequestId(requestId);
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSetRouteVolume,
                    MediaRoute2ProviderService.this, requestId, routeId, volume));
        }

        @Override
        public void requestCreateSession(long requestId, String packageName, String routeId,
                @Nullable Bundle requestCreateSession) {
            if (!checkCallerIsSystem()) {
                return;
            }
            if (!checkRouteIdIsValid(routeId, "requestCreateSession")) {
                return;
            }
            addRequestId(requestId);
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onCreateSession,
                    MediaRoute2ProviderService.this, requestId, packageName, routeId,
                    requestCreateSession));
        }

        @Override
        public void requestCreateSystemMediaSession(
                long requestId,
                int uid,
                String packageName,
                String routeId,
                @Nullable Bundle sessionHints) {
            if (!checkCallerIsSystem()) {
                return;
            }
            if (!checkRouteIdIsValid(routeId, "requestCreateSession")) {
                return;
            }
            synchronized (mRequestIdsLock) {
                mSystemMediaSessionCreationRequests.put(requestId, uid);
            }
            mHandler.sendMessage(
                    obtainMessage(
                            MediaRoute2ProviderService::onCreateSystemRoutingSession,
                            MediaRoute2ProviderService.this,
                            requestId,
                            packageName,
                            routeId,
                            sessionHints));
        }

        @Override
        public void selectRoute(long requestId, String sessionId, String routeId) {
            if (!checkCallerIsSystem()) {
                return;
            }
            if (!checkSessionIdIsValid(sessionId, "selectRoute")
                    || !checkRouteIdIsValid(routeId, "selectRoute")) {
                return;
            }
            addRequestId(requestId);
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSelectRoute,
                    MediaRoute2ProviderService.this, requestId, sessionId, routeId));
        }

        @Override
        public void deselectRoute(long requestId, String sessionId, String routeId) {
            if (!checkCallerIsSystem()) {
                return;
            }
            if (!checkSessionIdIsValid(sessionId, "deselectRoute")
                    || !checkRouteIdIsValid(routeId, "deselectRoute")) {
                return;
            }
            addRequestId(requestId);
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onDeselectRoute,
                    MediaRoute2ProviderService.this, requestId, sessionId, routeId));
        }

        @Override
        public void transferToRoute(long requestId, String sessionId, String routeId) {
            if (!checkCallerIsSystem()) {
                return;
            }
            if (!checkSessionIdIsValid(sessionId, "transferToRoute")
                    || !checkRouteIdIsValid(routeId, "transferToRoute")) {
                return;
            }
            addRequestId(requestId);
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onTransferToRoute,
                    MediaRoute2ProviderService.this, requestId, sessionId, routeId));
        }

        @Override
        public void setSessionVolume(long requestId, String sessionId, int volume) {
            if (!checkCallerIsSystem()) {
                return;
            }
            if (!checkSessionIdIsValid(sessionId, "setSessionVolume")) {
                return;
            }
            addRequestId(requestId);
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSetSessionVolume,
                    MediaRoute2ProviderService.this, requestId, sessionId, volume));
        }

        @Override
        public void releaseSession(long requestId, String sessionId) {
            if (!checkCallerIsSystem()) {
                return;
            }
            if (!checkSessionIdIsValid(sessionId, "releaseSession")) {
                return;
            }
            // We proactively release the system media routing once the system requests it, to
            // ensure it happens immediately.
            maybeReleaseMediaStreams(sessionId);

            addRequestId(requestId);
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onReleaseSession,
                    MediaRoute2ProviderService.this, requestId, sessionId));
        }
    }

    /**
     * Holds the streams to be routed as part of a system media routing session.
     *
     * <p>The encoded data format matches the {@link MediaStreamsFormats} passed to {@link
     * #notifySystemMediaSessionCreated}.
     *
     * @hide
     */
    // TODO: b/362507305 - Unhide once the implementation and CTS are in place.
    @FlaggedApi(Flags.FLAG_ENABLE_MIRRORING_IN_MEDIA_ROUTER_2)
    public static final class MediaStreams {

        @Nullable private final AudioPolicy mAudioPolicy;
        @Nullable private final AudioRecord mAudioRecord;

        // TODO: b/380431086: Add the video equivalent.

        private MediaStreams(Builder builder) {
            this.mAudioPolicy = builder.mAudioPolicy;
            this.mAudioRecord = builder.mAudioRecord;
        }

        /**
         * Returns the {@link AudioRecord} from which to read the audio data to route, or null if
         * the routing session doesn't include audio.
         */
        @Nullable
        public AudioRecord getAudioRecord() {
            return mAudioRecord;
        }

        /**
         * Builder for {@link MediaStreams}.
         *
         * @hide
         */
        public static final class Builder {

            @Nullable private AudioPolicy mAudioPolicy;
            @Nullable private AudioRecord mAudioRecord;

            /** Populates system media audio-related structures. */
            public Builder setAudioStream(
                    @NonNull AudioPolicy audioPolicy, @NonNull AudioRecord audioRecord) {
                mAudioPolicy = requireNonNull(audioPolicy);
                mAudioRecord = requireNonNull(audioRecord);
                return this;
            }

            /** Builds a {@link MediaStreams} instance. */
            public MediaStreams build() {
                return new MediaStreams(this);
            }
        }
    }


    /**
     * Holds the formats to encode media data to be read from {@link MediaStreams}.
     *
     * @see MediaStreams
     * @see #notifySystemMediaSessionCreated
     * @hide
     */
    // TODO: b/362507305 - Unhide once the implementation and CTS are in place.
    @FlaggedApi(Flags.FLAG_ENABLE_MIRRORING_IN_MEDIA_ROUTER_2)
    public static final class MediaStreamsFormats {

        private final AudioFormat mAudioFormat;

        // TODO: b/380431086: Add the video equivalent.

        private MediaStreamsFormats(Builder builder) {
            this.mAudioFormat = builder.mAudioFormat;
        }

        /**
         * Returns the audio format to use for creating the {@link MediaStreams#getAudioRecord} to
         * return from {@link #notifySystemMediaSessionCreated}.
         *
         * @hide
         */
        // TODO: b/362507305 - Unhide once the implementation and CTS are in place.
        @FlaggedApi(Flags.FLAG_ENABLE_MIRRORING_IN_MEDIA_ROUTER_2)
        public AudioFormat getAudioFormat() {
            return mAudioFormat;
        }

        /**
         * Builder for {@link MediaStreamsFormats}
         *
         * @hide
         */
        // TODO: b/362507305 - Unhide once the implementation and CTS are in place.
        @FlaggedApi(Flags.FLAG_ENABLE_MIRRORING_IN_MEDIA_ROUTER_2)
        public static final class Builder {
            private AudioFormat mAudioFormat;

            /**
             * Sets the audio format to use for creating the {@link MediaStreams#getAudioRecord} to
             * return from {@link #notifySystemMediaSessionCreated}.
             *
             * @param audioFormat the audio format
             * @return this builder
             */
            @NonNull
            public Builder setAudioFormat(@NonNull AudioFormat audioFormat) {
                this.mAudioFormat = requireNonNull(audioFormat);
                return this;
            }

            /**
             * Builds the {@link MediaStreamsFormats} instance.
             *
             * @return the built {@link MediaStreamsFormats} instance
             */
            @NonNull
            public MediaStreamsFormats build() {
                return new MediaStreamsFormats(this);
            }
        }
    }
}
