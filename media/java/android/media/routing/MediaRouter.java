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
package android.media.routing;

import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Presentation;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.VolumeProvider;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.view.Display;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * Media router allows applications to discover, connect to, control,
 * and send content to nearby media devices known as destinations.
 * <p>
 * There are generally two participants involved in media routing: an
 * application that wants to send media content to a destination and a
 * {@link MediaRouteService media route service} that provides the
 * service of transporting that content where it needs to go on behalf of the
 * application.
 * </p><p>
 * To send media content to a destination, the application must ask the system
 * to discover available routes to destinations that provide certain capabilities,
 * establish a connection to a route, then send messages through the connection to
 * control the routing of audio and video streams, launch remote applications,
 * and invoke other functions of the destination.
 * </p><p>
 * Media router objects are thread-safe.
 * </p>
 *
 * <h3>Destinations</h3>
 * <p>
 * The media devices to which an application may send media content are referred
 * to in the API as destinations.  Each destination therefore represents a single
 * independent device such as a speaker or TV set.  Destinations are given meaningful
 * names and descriptions to help the user associate them with devices in their
 * environment.
 * </p><p>
 * Destinations may be local or remote and may be accessed through various means,
 * often wirelessly.  The user may install media route services to enable
 * media applications to connect to a variety of destinations with different
 * capabilities.
 * </p>
 *
 * <h3>Routes</h3>
 * <p>
 * Routes represent possible usages or means of reaching and interacting with
 * a destination.  Since destinations may support many different features, they may
 * each offer multiple routes for applications to choose from based on their needs.
 * For example, one route might express the ability to stream locally rendered audio
 * and video to the device; another route might express the ability to send a URL for
 * the destination to download from the network and play all by itself.
 * </p><p>
 * Routes are discovered according to the set of capabilities that
 * an application or the system is seeking to use at a particular time.  For example,
 * if an application wants to stream music to a destination then it will ask the
 * {@link MediaRouter} to find routes to destinations can stream music and ignore
 * all other destinations that cannot.
 * </p><p>
 * In general, the application will inspect the set of routes that have been
 * offered then connect to the most appropriate route for its desired purpose.
 * </p>
 *
 * <h3>Route Selection</h3>
 * <p>
 * When the user open the media route chooser activity, the system will display
 * a list of nearby media destinations which have been discovered.  After the
 * choice is made the application may connect to one of the routes offered by
 * this destination and begin communicating with the destination.
 * </p><p>
 * Destinations are located through a process called discovery.  During discovery,
 * the system will start installed {@link MediaRouteService media route services}
 * to scan the network for nearby devices that offer the kinds of capabilities that the
 * application is seeking to use.  The application specifies the capabilities it requires by
 * adding {@link MediaRouteSelector media route selectors} to the media router
 * using the {@link #addSelector} method.  Only destinations that provide routes
 * which satisfy at least one of these media route selectors will be discovered.
 * </p><p>
 * Once the user has selected a destination, the application will be given a chance
 * to choose one of the routes to which it would like to connect.  The application
 * may switch to a different route from the same destination at a later time but
 * in order to connect to a new destination, the application must once again launch
 * the media route chooser activity to ask the user to choose a destination.
 * </p>
 *
 * <h3>Route Protocols</h3>
 * <p>
 * Route protocols express capabilities offered by routes.  Each media route selector
 * must specify at least one required protocol by which the routes will be selected.
 * </p><p>
 * The framework provides several predefined <code>MediaRouteProtocols</code> which are
 * defined in the <code>android-support-media-protocols.jar</code> support library.
 * Applications must statically link this library to make use of these protocols.
 * </p><p>
 * The static library approach is used to enable ongoing extension and refinement
 * of protocols in the SDK and interoperability with the media router implementation
 * for older platform versions which is offered by the framework support library.
 * </p><p>
 * Media route services may also define custom media route protocols of their own
 * to enable applications to access specialized capabilities of certain destinations
 * assuming they have linked in the required protocol code.
 * </p><p>
 * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code> for more information.
 * </p>
 *
 * <h3>Connections</h3>
 * <p>
 * After connecting to a media route, the application can send commands to
 * the route using any of the protocols that it requested.  If the route supports live
 * audio or video streaming then the application can create an {@link AudioTrack} or
 * {@link Presentation} to route locally generated content to the destination.
 * </p>
 *
 * <h3>Delegation</h3>
 * <p>
 * The creator of the media router is responsible for establishing the policy for
 * discovering and connecting to destinations.  UI components may observe the state
 * of the media router by {@link #createDelegate creating} a {@link Delegate}.
 * </p><p>
 * The media router should also be attached to the {@link MediaSession media session}
 * that is handling media playback lifecycle.  This will allow
 * authorized {@link MediaController media controllers}, possibly running in other
 * processes, to provide UI to examine and change the media destination by
 * {@link MediaController#createMediaRouterDelegate creating} a {@link Delegate}
 * for the media router associated with the session.
 * </p>
 */
public final class MediaRouter {
    private final DisplayManager mDisplayManager;

    private final Object mLock = new Object();

    private RoutingCallback mRoutingCallback;
    private Handler mRoutingCallbackHandler;

    private boolean mReleased;
    private int mDiscoveryState;
    private int mConnectionState;
    private final ArrayList<MediaRouteSelector> mSelectors =
            new ArrayList<MediaRouteSelector>();
    private final ArrayMap<DestinationInfo, List<RouteInfo>> mDiscoveredDestinations =
            new ArrayMap<DestinationInfo, List<RouteInfo>>();
    private RouteInfo mSelectedRoute;
    private ConnectionInfo mConnection;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = { DISCOVERY_STATE_STOPPED, DISCOVERY_STATE_STARTED })
    public @interface DiscoveryState { }

    /**
     * Discovery state: Discovery is not currently in progress.
     */
    public static final int DISCOVERY_STATE_STOPPED = 0;

    /**
     * Discovery state: Discovery is being performed.
     */
    public static final int DISCOVERY_STATE_STARTED = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = { DISCOVERY_FLAG_BACKGROUND })
    public @interface DiscoveryFlags { }

    /**
     * Discovery flag: Indicates that the client has requested passive discovery in
     * the background.  The media route service should try to use less power and rely
     * more on its internal caches to minimize its impact.
     */
    public static final int DISCOVERY_FLAG_BACKGROUND = 1 << 0;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = { DISCOVERY_ERROR_UNKNOWN, DISCOVERY_ERROR_ABORTED,
            DISCOVERY_ERROR_NO_CONNECTIVITY })
    public @interface DiscoveryError { }

    /**
     * Discovery error: Unknown error; refer to the error message for details.
     */
    public static final int DISCOVERY_ERROR_UNKNOWN = 0;

    /**
     * Discovery error: The media router or media route service has decided not to
     * handle the discovery request for some reason.
     */
    public static final int DISCOVERY_ERROR_ABORTED = 1;

    /**
     * Discovery error: The media route service is unable to perform discovery
     * due to a lack of connectivity such as because the radio is disabled.
     */
    public static final int DISCOVERY_ERROR_NO_CONNECTIVITY = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = { CONNECTION_STATE_DISCONNECTED, CONNECTION_STATE_CONNECTING,
            CONNECTION_STATE_CONNECTED })
    public @interface ConnectionState { }

    /**
     * Connection state: No destination has been selected.  Media content should
     * be sent to the default output.
     */
    public static final int CONNECTION_STATE_DISCONNECTED = 0;

    /**
     * Connection state: The application is in the process of connecting to
     * a route offered by the selected destination.
     */
    public static final int CONNECTION_STATE_CONNECTING = 1;

    /**
     * Connection state: The application has connected to a route offered by
     * the selected destination.
     */
    public static final int CONNECTION_STATE_CONNECTED = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = { CONNECTION_FLAG_BARGE })
    public @interface ConnectionFlags { }

    /**
     * Connection flag: Indicates that the client has requested to barge in and evict
     * other clients that might have already connected to the destination and that
     * would otherwise prevent this client from connecting.  When this flag is not
     * set, the media route service should be polite and report
     * {@link MediaRouter#CONNECTION_ERROR_BUSY} in case the destination is
     * already occupied and cannot accept additional connections.
     */
    public static final int CONNECTION_FLAG_BARGE = 1 << 0;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = { CONNECTION_ERROR_UNKNOWN, CONNECTION_ERROR_ABORTED,
            CONNECTION_ERROR_UNAUTHORIZED, CONNECTION_ERROR_UNAUTHORIZED,
            CONNECTION_ERROR_BUSY, CONNECTION_ERROR_TIMEOUT, CONNECTION_ERROR_BROKEN })
    public @interface ConnectionError { }

    /**
     * Connection error: Unknown error; refer to the error message for details.
     */
    public static final int CONNECTION_ERROR_UNKNOWN = 0;

    /**
     * Connection error: The media router or media route service has decided not to
     * handle the connection request for some reason.
     */
    public static final int CONNECTION_ERROR_ABORTED = 1;

    /**
     * Connection error: The device has refused the connection from this client.
     * This error should be avoided because the media route service should attempt
     * to filter out devices that the client cannot access as it performs discovery
     * on behalf of that client.
     */
    public static final int CONNECTION_ERROR_UNAUTHORIZED = 2;

    /**
     * Connection error: The device is unreachable over the network.
     */
    public static final int CONNECTION_ERROR_UNREACHABLE = 3;

    /**
     * Connection error: The device is already busy serving another client and
     * the connection request did not ask to barge in.
     */
    public static final int CONNECTION_ERROR_BUSY = 4;

    /**
     * Connection error: A timeout occurred during connection.
     */
    public static final int CONNECTION_ERROR_TIMEOUT = 5;

    /**
     * Connection error: The connection to the device was severed unexpectedly.
     */
    public static final int CONNECTION_ERROR_BROKEN = 6;

    /**
     * Connection error: The connection was terminated because a different client barged
     * in and took control of the destination.
     */
    public static final int CONNECTION_ERROR_BARGED = 7;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = { DISCONNECTION_REASON_APPLICATION_REQUEST,
            DISCONNECTION_REASON_USER_REQUEST, DISCONNECTION_REASON_ERROR })
    public @interface DisconnectionReason { }

    /**
     * Disconnection reason: The application requested disconnection itself.
     */
    public static final int DISCONNECTION_REASON_APPLICATION_REQUEST = 0;

    /**
     * Disconnection reason: The user requested disconnection.
     */
    public static final int DISCONNECTION_REASON_USER_REQUEST = 1;

    /**
     * Disconnection reason: An error occurred.
     */
    public static final int DISCONNECTION_REASON_ERROR = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = { ROUTE_FEATURE_LIVE_AUDIO, ROUTE_FEATURE_LIVE_VIDEO })
    public @interface RouteFeatures { }

    /**
     * Route feature: Live audio.
     * <p>
     * A route that supports live audio streams audio rendered by the application
     * to the destination.
     * </p><p>
     * To take advantage of live audio routing, the application must render its
     * media using the audio attributes specified by {@link #getPreferredAudioAttributes}.
     * </p>
     *
     * @see #getPreferredAudioAttributes
     * @see android.media.AudioAttributes
     */
    public static final int ROUTE_FEATURE_LIVE_AUDIO = 1 << 0;

    /**
     * Route feature: Live video.
     * <p>
     * A route that supports live video streams video rendered by the application
     * to the destination.
     * </p><p>
     * To take advantage of live video routing, the application must render its
     * media to a {@link android.app.Presentation presentation window} on the
     * display specified by {@link #getPreferredPresentationDisplay}.
     * </p>
     *
     * @see #getPreferredPresentationDisplay
     * @see android.app.Presentation
     */
    public static final int ROUTE_FEATURE_LIVE_VIDEO = 1 << 1;

    /**
     * Creates a media router.
     *
     * @param context The context with which the router is associated.
     */
    public MediaRouter(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }

        mDisplayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** @hide */
    public IMediaRouter getBinder() {
        // todo
        return null;
    }

    /**
     * Disconnects from the selected destination and releases the media router.
     * <p>
     * This method should be called by the application when it no longer requires
     * the media router to ensure that all bound resources may be cleaned up.
     * </p>
     */
    public void release() {
        synchronized (mLock) {
            mReleased = true;
            // todo
        }
    }

    /**
     * Returns true if the media router has been released.
     */
    public boolean isReleased() {
        synchronized (mLock) {
            return mReleased;
        }
    }

    /**
     * Gets the current route discovery state.
     *
     * @return The current discovery state: one of {@link #DISCOVERY_STATE_STOPPED},
     * {@link #DISCOVERY_STATE_STARTED}.
     */
    public @DiscoveryState int getDiscoveryState() {
        synchronized (mLock) {
            return mDiscoveryState;
        }
    }

    /**
     * Gets the current route connection state.
     *
     * @return The current state: one of {@link #CONNECTION_STATE_DISCONNECTED},
     * {@link #CONNECTION_STATE_CONNECTING} or {@link #CONNECTION_STATE_CONNECTED}.
     */
    public @ConnectionState int getConnectionState() {
        synchronized (mLock) {
            return mConnectionState;
        }
    }

    /**
     * Creates a media router delegate through which the destination of the media
     * router may be controlled.
     * <p>
     * This is the point of entry for UI code that initiates discovery and
     * connection to routes.
     * </p>
     */
    public @NonNull Delegate createDelegate() {
        return null; // todo
    }

    /**
     * Sets a callback to participate in route discovery, filtering, and connection
     * establishment.
     *
     * @param callback The callback to set, or null if none.
     * @param handler The handler to receive callbacks, or null to use the current thread.
     */
    public void setRoutingCallback(@Nullable RoutingCallback callback,
            @Nullable Handler handler) {
        synchronized (mLock) {
            if (callback == null) {
                mRoutingCallback = null;
                mRoutingCallbackHandler = null;
            } else {
                mRoutingCallback = callback;
                mRoutingCallbackHandler = handler != null ? handler : new Handler();
            }
        }
    }

    /**
     * Adds a media route selector to use to find destinations that have
     * routes with the specified capabilities during route discovery.
     */
    public void addSelector(@NonNull MediaRouteSelector selector) {
        if (selector == null) {
            throw new IllegalArgumentException("selector must not be null");
        }

        synchronized (mLock) {
            if (!mSelectors.contains(selector)) {
                mSelectors.add(selector);
                // todo
            }
        }
    }

    /**
     * Removes a media route selector.
     */
    public void removeSelector(@NonNull MediaRouteSelector selector) {
        if (selector == null) {
            throw new IllegalArgumentException("selector must not be null");
        }

        synchronized (mLock) {
            if (mSelectors.remove(selector)) {
                // todo
            }
        }
    }

    /**
     * Removes all media route selectors.
     * <p>
     * Note that at least one selector must be added in order to perform discovery.
     * </p>
     */
    public void clearSelectors() {
        synchronized (mLock) {
            if (!mSelectors.isEmpty()) {
                mSelectors.clear();
                // todo
            }
        }
    }

    /**
     * Gets a list of all media route selectors to consider during discovery.
     */
    public @NonNull List<MediaRouteSelector> getSelectors() {
        synchronized (mLock) {
            return new ArrayList<MediaRouteSelector>(mSelectors);
        }
    }

    /**
     * Gets the connection to the currently selected route.
     *
     * @return The connection to the currently selected route, or null if not connected.
     */
    public @NonNull ConnectionInfo getConnection() {
        synchronized (mLock) {
            return mConnection;
        }
    }

    /**
     * Gets the list of discovered destinations.
     * <p>
     * This list is only valid while discovery is running and is null otherwise.
     * </p>
     *
     * @return The list of discovered destinations, or null if discovery is not running.
     */
    public @NonNull List<DestinationInfo> getDiscoveredDestinations() {
        synchronized (mLock) {
            if (mDiscoveryState == DISCOVERY_STATE_STARTED) {
                return new ArrayList<DestinationInfo>(mDiscoveredDestinations.keySet());
            }
            return null;
        }
    }

    /**
     * Gets the list of discovered routes for a particular destination.
     * <p>
     * This list is only valid while discovery is running and is null otherwise.
     * </p>
     *
     * @param destination The destination for which to get the list of discovered routes.
     * @return The list of discovered routes for the destination, or null if discovery
     * is not running.
     */
    public @NonNull List<RouteInfo> getDiscoveredRoutes(@NonNull DestinationInfo destination) {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        synchronized (mLock) {
            if (mDiscoveryState == DISCOVERY_STATE_STARTED) {
                List<RouteInfo> routes = mDiscoveredDestinations.get(destination);
                if (routes != null) {
                    return new ArrayList<RouteInfo>(routes);
                }
            }
            return null;
        }
    }

    /**
     * Gets the destination that has been selected.
     *
     * @return The selected destination, or null if disconnected.
     */
    public @Nullable DestinationInfo getSelectedDestination() {
        synchronized (mLock) {
            return mSelectedRoute != null ? mSelectedRoute.getDestination() : null;
        }
    }

    /**
     * Gets the route that has been selected.
     *
     * @return The selected destination, or null if disconnected.
     */
    public @Nullable RouteInfo getSelectedRoute() {
        synchronized (mLock) {
            return mSelectedRoute;
        }
    }

    /**
     * Gets the preferred audio attributes that should be used to stream live audio content
     * based on the connected route.
     * <p>
     * Use an {@link AudioTrack} to send audio content to the destination with these
     * audio attributes.
     * </p><p>
     * The preferred audio attributes may change when a connection is established but it
     * will remain constant until disconnected.
     * </p>
     *
     * @return The preferred audio attributes to use.  When connected, returns the
     * route's audio attributes or null if it does not support live audio streaming.
     * Otherwise returns audio attributes associated with {@link AudioAttributes#USAGE_MEDIA}.
     */
    public @Nullable AudioAttributes getPreferredAudioAttributes() {
        synchronized (mLock) {
            if (mConnection != null) {
                return mConnection.getAudioAttributes();
            }
            return new AudioAttributes.Builder()
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build();
        }
    }

    /**
     * Gets the preferred presentation display that should be used to stream live video content
     * based on the connected route.
     * <p>
     * Use a {@link Presentation} to send video content to the destination with this display.
     * </p><p>
     * The preferred presentation display may change when a connection is established but it
     * will remain constant until disconnected.
     * </p>
     *
     * @return The preferred presentation display to use.  When connected, returns
     * the route's presentation display or null if it does not support live video
     * streaming.  Otherwise returns the first available
     * {@link DisplayManager#DISPLAY_CATEGORY_PRESENTATION presentation display},
     * such as a mirrored wireless or HDMI display or null if none.
     */
    public @Nullable Display getPreferredPresentationDisplay() {
        synchronized (mLock) {
            if (mConnection != null) {
                return mConnection.getPresentationDisplay();
            }
            Display[] displays = mDisplayManager.getDisplays(
                    DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            return displays.length != 0 ? displays[0] : null;
        }
    }

    /**
     * Gets the preferred volume provider that should be used to control the volume
     * of content rendered on the currently selected route.
     * <p>
     * The preferred volume provider may change when a connection is established but it
     * will remain the same until disconnected.
     * </p>
     *
     * @return The preferred volume provider to use, or null if the currently
     * selected route does not support remote volume adjustment or if the connection
     * is not yet established.  If no route is selected, returns null to indicate
     * that system volume control should be used.
     */
    public @Nullable VolumeProvider getPreferredVolumeProvider() {
        synchronized (mLock) {
            if (mConnection != null) {
                return mConnection.getVolumeProvider();
            }
            return null;
        }
    }

    /**
     * Requests to pause streaming of live audio or video routes.
     * Should be called when the application is going into the background and is
     * no longer rendering content locally.
     * <p>
     * This method does nothing unless a connection has been established.
     * </p>
     */
    public void pauseStream() {
        // todo
    }

    /**
     * Requests to resume streaming of live audio or video routes.
     * May be called when the application is returning to the foreground and is
     * about to resume rendering content locally.
     * <p>
     * This method does nothing unless a connection has been established.
     * </p>
     */
    public void resumeStream() {
        // todo
    }

    /**
     * This class is used by UI components to let the user discover and
     * select a destination to which the media router should connect.
     * <p>
     * This API has somewhat more limited functionality than the {@link MediaRouter}
     * itself because it is designed to allow applications to control
     * the destination of media router instances that belong to other processes.
     * </p><p>
     * To control the destination of your own media router, call
     * {@link #createDelegate} to obtain a local delegate object.
     * </p><p>
     * To control the destination of a media router that belongs to another process,
     * first obtain a {@link MediaController} that is associated with the media playback
     * that is occurring in that process, then call
     * {@link MediaController#createMediaRouterDelegate} to obtain an instance of
     * its destination controls.  Note that special permissions may be required to
     * obtain the {@link MediaController} instance in the first place.
     * </p>
     */
    public static final class Delegate {
        /**
         * Returns true if the media router has been released.
         */
        public boolean isReleased() {
            // todo
            return false;
        }

        /**
         * Gets the current route discovery state.
         *
         * @return The current discovery state: one of {@link #DISCOVERY_STATE_STOPPED},
         * {@link #DISCOVERY_STATE_STARTED}.
         */
        public @DiscoveryState int getDiscoveryState() {
            // todo
            return -1;
        }

        /**
         * Gets the current route connection state.
         *
         * @return The current state: one of {@link #CONNECTION_STATE_DISCONNECTED},
         * {@link #CONNECTION_STATE_CONNECTING} or {@link #CONNECTION_STATE_CONNECTED}.
         */
        public @ConnectionState int getConnectionState() {
            // todo
            return -1;
        }

        /**
         * Gets the currently selected destination.
         *
         * @return The destination information, or null if none.
         */
        public @Nullable DestinationInfo getSelectedDestination() {
            return null;
        }

        /**
         * Gets the list of discovered destinations.
         * <p>
         * This list is only valid while discovery is running and is null otherwise.
         * </p>
         *
         * @return The list of discovered destinations, or null if discovery is not running.
         */
        public @NonNull List<DestinationInfo> getDiscoveredDestinations() {
            return null;
        }

        /**
         * Adds a callback to receive state changes.
         *
         * @param callback The callback to set, or null if none.
         * @param handler The handler to receive callbacks, or null to use the current thread.
         */
        public void addStateCallback(@Nullable StateCallback callback,
                @Nullable Handler handler) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            if (handler == null) {
                handler = new Handler();
            }
            // todo
        }

        /**
         * Removes a callback for state changes.
         *
         * @param callback The callback to set, or null if none.
         */
        public void removeStateCallback(@Nullable StateCallback callback) {
            // todo
        }

        /**
         * Starts performing discovery.
         * <p>
         * Performing discovery is expensive.  Make sure to call {@link #stopDiscovery}
         * as soon as possible once a new destination has been selected to allow the system
         * to stop services associated with discovery.
         * </p>
         *
         * @param flags The discovery flags, such as {@link MediaRouter#DISCOVERY_FLAG_BACKGROUND}.
         */
        public void startDiscovery(@DiscoveryFlags int flags) {
            // todo
        }

        /**
         * Stops performing discovery.
         */
        public void stopDiscovery() {
            // todo
        }

        /**
         * Connects to a destination during route discovery.
         * <p>
         * This method may only be called while route discovery is active and the
         * destination appears in the
         * {@link #getDiscoveredDestinations list of discovered destinations}.
         * If the media router is already connected to a route then it will first disconnect
         * from the current route then connect to the new route.
         * </p>
         *
         * @param destination The destination to which the media router should connect.
         * @param flags The connection flags, such as {@link MediaRouter#CONNECTION_FLAG_BARGE}.
         */
        public void connect(@NonNull DestinationInfo destination, @DiscoveryFlags int flags) {
            // todo
        }

        /**
         * Disconnects from the currently selected destination.
         * <p>
         * Does nothing if not currently connected.
         * </p>
         *
         * @param reason The reason for the disconnection: one of
         * {@link #DISCONNECTION_REASON_APPLICATION_REQUEST},
         * {@link #DISCONNECTION_REASON_USER_REQUEST}, or {@link #DISCONNECTION_REASON_ERROR}.
         */
        public void disconnect(@DisconnectionReason int reason) {
            // todo
        }
    }

    /**
     * Describes immutable properties of a connection to a route.
     */
    public static final class ConnectionInfo {
        private final RouteInfo mRoute;
        private final AudioAttributes mAudioAttributes;
        private final Display mPresentationDisplay;
        private final VolumeProvider mVolumeProvider;
        private final IBinder[] mProtocolBinders;
        private final Object[] mProtocolInstances;
        private final Bundle mExtras;
        private final ArrayList<Closeable> mCloseables;

        private static final Class<?>[] MEDIA_ROUTE_PROTOCOL_CTOR_PARAMETERS =
                new Class<?>[] { IBinder.class };

        ConnectionInfo(RouteInfo route,
                AudioAttributes audioAttributes, Display display,
                VolumeProvider volumeProvider, IBinder[] protocolBinders,
                Bundle extras, ArrayList<Closeable> closeables) {
            mRoute = route;
            mAudioAttributes = audioAttributes;
            mPresentationDisplay = display;
            mVolumeProvider = volumeProvider;
            mProtocolBinders = protocolBinders;
            mProtocolInstances = new Object[mProtocolBinders.length];
            mExtras = extras;
            mCloseables = closeables;
        }

        /**
         * Gets the route that is connected.
         */
        public @NonNull RouteInfo getRoute() {
            return mRoute;
        }

        /**
         * Gets the audio attributes which the client should use to stream audio
         * to the destination, or null if the route does not support live audio streaming.
         */
        public @Nullable AudioAttributes getAudioAttributes() {
            return mAudioAttributes;
        }

        /**
         * Gets the display which the client should use to stream video to the
         * destination using a {@link Presentation}, or null if the route does not
         * support live video streaming.
         */
        public @Nullable Display getPresentationDisplay() {
            return mPresentationDisplay;
        }

        /**
         * Gets the route's volume provider, or null if none.
         */
        public @Nullable VolumeProvider getVolumeProvider() {
            return mVolumeProvider;
        }

        /**
         * Gets the set of supported route features.
         */
        public @RouteFeatures int getFeatures() {
            return mRoute.getFeatures();
        }

        /**
         * Gets the list of supported route protocols.
         * <p>
         * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
         * for more information.
         * </p>
         */
        public @NonNull List<String> getProtocols() {
            return mRoute.getProtocols();
        }

        /**
         * Gets an instance of a route protocol object that wraps the protocol binder
         * and provides easy access to the protocol's functionality.
         * <p>
         * This is a convenience method which invokes {@link #getProtocolBinder(String)}
         * using the name of the provided class then passes the resulting {@link IBinder}
         * to a single-argument constructor of that class.
         * </p><p>
         * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
         * for more information.
         * </p>
         */
        @SuppressWarnings("unchecked")
        public @Nullable <T> T getProtocolObject(Class<T> clazz) {
            int index = getProtocols().indexOf(clazz.getName());
            if (index < 0) {
                return null;
            }
            if (mProtocolInstances[index] == null && mProtocolBinders[index] != null) {
                final Constructor<T> ctor;
                try {
                    ctor = clazz.getConstructor(MEDIA_ROUTE_PROTOCOL_CTOR_PARAMETERS);
                } catch (NoSuchMethodException ex) {
                    throw new RuntimeException("Could not find public constructor "
                            + "with IBinder argument in protocol class: " + clazz.getName(), ex);
                }
                try {
                    mProtocolInstances[index] = ctor.newInstance(mProtocolBinders[index]);
                } catch (InstantiationException | IllegalAccessException
                        | InvocationTargetException ex) {
                    throw new RuntimeException("Could create instance of protocol class: "
                            + clazz.getName(), ex);
                }
            }
            return (T)mProtocolInstances[index];
        }

        /**
         * Gets the {@link IBinder} that provides access to the specified route protocol
         * or null if the protocol is not supported.
         * <p>
         * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
         * for more information.
         * </p>
         */
        public @Nullable IBinder getProtocolBinder(@NonNull String name) {
            int index = getProtocols().indexOf(name);
            return index >= 0 ? mProtocolBinders[index] : null;
        }

        /**
         * Gets the {@link IBinder} that provides access to the specified route protocol
         * at the given index in the protocol list or null if the protocol is not supported.
         * <p>
         * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
         * for more information.
         * </p>
         */
        public @Nullable IBinder getProtocolBinder(int index) {
            return mProtocolBinders[index];
        }

        /**
         * Gets optional extra media route service or protocol specific information about
         * the connection.  Use the service or protocol name as the prefix for
         * any extras to avoid namespace collisions.
         */
        public @Nullable Bundle getExtras() {
            return mExtras;
        }

        /**
         * Closes all closeables associated with the connection when the connection
         * is being torn down.
         */
        void close() {
            final int count = mCloseables.size();
            for (int i = 0; i < count; i++) {
                try {
                    mCloseables.get(i).close();
                } catch (IOException ex) {
                }
            }
        }

        @Override
        public @NonNull String toString() {
            return "ConnectionInfo{ route=" + mRoute
                    + ", audioAttributes=" + mAudioAttributes
                    + ", presentationDisplay=" + mPresentationDisplay
                    + ", volumeProvider=" + mVolumeProvider
                    + ", protocolBinders=" + mProtocolBinders + " }";
        }

        /**
         * Builds {@link ConnectionInfo} objects.
         */
        public static final class Builder {
            private final RouteInfo mRoute;
            private AudioAttributes mAudioAttributes;
            private Display mPresentationDisplay;
            private VolumeProvider mVolumeProvider;
            private final IBinder[] mProtocols;
            private Bundle mExtras;
            private final ArrayList<Closeable> mCloseables = new ArrayList<Closeable>();

            /**
             * Creates a builder for connection information.
             *
             * @param route The route that is connected.
             */
            public Builder(@NonNull RouteInfo route) {
                if (route == null) {
                    throw new IllegalArgumentException("route");
                }
                mRoute = route;
                mProtocols = new IBinder[route.getProtocols().size()];
            }

            /**
             * Sets the audio attributes which the client should use to stream audio
             * to the destination, or null if the route does not support live audio streaming.
             */
            public @NonNull Builder setAudioAttributes(
                    @Nullable AudioAttributes audioAttributes) {
                mAudioAttributes = audioAttributes;
                return this;
            }

            /**
             * Sets the display which the client should use to stream video to the
             * destination using a {@link Presentation}, or null if the route does not
             * support live video streaming.
             */
            public @NonNull Builder setPresentationDisplay(@Nullable Display display) {
                mPresentationDisplay = display;
                return this;
            }

            /**
             * Sets the route's volume provider, or null if none.
             */
            public @NonNull Builder setVolumeProvider(@Nullable VolumeProvider provider) {
                mVolumeProvider = provider;
                return this;
            }

            /**
             * Sets the binder stub of a supported route protocol using
             * the protocol's fully qualified class name.  The protocol must be one
             * of those that was indicated as being supported by the route.
             * <p>
             * If the stub implements {@link Closeable} then it will automatically
             * be closed when the client disconnects from the route.
             * </p><p>
             * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
             * for more information.
             * </p>
             */
            public @NonNull Builder setProtocolStub(@NonNull Class<?> clazz,
                    @NonNull IInterface stub) {
                if (clazz == null) {
                    throw new IllegalArgumentException("clazz must not be null");
                }
                if (stub == null) {
                    throw new IllegalArgumentException("stub must not be null");
                }
                if (stub instanceof Closeable) {
                    mCloseables.add((Closeable)stub);
                }
                return setProtocolBinder(clazz.getName(), stub.asBinder());
            }

            /**
             * Sets the binder interface of a supported route protocol by name.
             * The protocol must be one of those that was indicated as being supported
             * by the route.
             * <p>
             * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
             * for more information.
             * </p>
             */
            public @NonNull Builder setProtocolBinder(@NonNull String name,
                    @NonNull IBinder binder) {
                if (TextUtils.isEmpty(name)) {
                    throw new IllegalArgumentException("name must not be null or empty");
                }
                if (binder == null) {
                    throw new IllegalArgumentException("binder must not be null");
                }
                int index = mRoute.getProtocols().indexOf(name);
                if (index < 0) {
                    throw new IllegalArgumentException("name must specify a protocol that "
                            + "the route actually declared that it supports: "
                            + "name=" + name + ", protocols=" + mRoute.getProtocols());
                }
                mProtocols[index] = binder;
                return this;
            }

            /**
             * Sets optional extra media route service or protocol specific information about
             * the connection.  Use the service or protocol name as the prefix for
             * any extras to avoid namespace collisions.
             */
            public @NonNull Builder setExtras(@Nullable Bundle extras) {
                mExtras = extras;
                return this;
            }

            /**
             * Builds the {@link ConnectionInfo} object.
             */
            public @NonNull ConnectionInfo build() {
                return new ConnectionInfo(mRoute,
                        mAudioAttributes, mPresentationDisplay,
                        mVolumeProvider, mProtocols, mExtras, mCloseables);
            }
        }
    }

    /**
     * Describes one particular way of routing media content to a destination
     * according to the capabilities specified by a media route selector on behalf
     * of an application.
     */
    public static final class RouteInfo {
        private final String mId;
        private final DestinationInfo mDestination;
        private final MediaRouteSelector mSelector;
        private final int mFeatures;
        private final ArrayList<String> mProtocols;
        private final Bundle mExtras;

        RouteInfo(String id, DestinationInfo destination, MediaRouteSelector selector,
                int features, ArrayList<String> protocols, Bundle extras) {
            mId = id;
            mDestination = destination;
            mSelector = selector;
            mFeatures = features;
            mProtocols = protocols;
            mExtras = extras;
        }

        /**
         * Gets the route's stable identifier.
         * <p>
         * The id is intended to uniquely identify the route among all routes that
         * are offered by a particular destination in such a way that the client can
         * refer to it at a later time.
         * </p>
         */
        public @NonNull String getId() {
            return mId;
        }

        /**
         * Gets the destination that is offering this route.
         */
        public @NonNull DestinationInfo getDestination() {
            return mDestination;
        }

        /**
         * Gets the media route selector provided by the client for which this
         * route was created.
         * <p>
         * It is implied that this route supports all of the required capabilities
         * that were expressed in the selector.
         * </p>
         */
        public @NonNull MediaRouteSelector getSelector() {
            return mSelector;
        }

        /**
         * Gets the set of supported route features.
         */
        public @RouteFeatures int getFeatures() {
            return mFeatures;
        }

        /**
         * Gets the list of supported route protocols.
         * <p>
         * Refer to <code>android.support.media.protocols.MediaRouteProtocol</code>
         * for more information.
         * </p>
         */
        public @NonNull List<String> getProtocols() {
            return mProtocols;
        }

        /**
         * Gets optional extra information about the route, or null if none.
         */
        public @Nullable Bundle getExtras() {
            return mExtras;
        }

        @Override
        public @NonNull String toString() {
            return "RouteInfo{ id=" + mId + ", destination=" + mDestination
                    + ", features=0x" + Integer.toHexString(mFeatures)
                    + ", selector=" + mSelector + ", protocols=" + mProtocols
                    + ", extras=" + mExtras + " }";
        }

        /**
         * Builds {@link RouteInfo} objects.
         */
        public static final class Builder {
            private final DestinationInfo mDestination;
            private final String mId;
            private final MediaRouteSelector mSelector;
            private int mFeatures;
            private final ArrayList<String> mProtocols = new ArrayList<String>();
            private Bundle mExtras;

            /**
             * Creates a builder for route information.
             *
             * @param id The route's stable identifier.
             * @param destination The destination of this route.
             * @param selector The media route selector provided by the client for which
             * this route was created.  This must be one of the selectors that was
             * included in the discovery request.
             */
            public Builder(@NonNull String id, @NonNull DestinationInfo destination,
                    @NonNull MediaRouteSelector selector) {
                if (TextUtils.isEmpty(id)) {
                    throw new IllegalArgumentException("id must not be null or empty");
                }
                if (destination == null) {
                    throw new IllegalArgumentException("destination must not be null");
                }
                if (selector == null) {
                    throw new IllegalArgumentException("selector must not be null");
                }
                mDestination = destination;
                mId = id;
                mSelector = selector;
            }

            /**
             * Sets the set of supported route features.
             */
            public @NonNull Builder setFeatures(@RouteFeatures int features) {
                mFeatures = features;
                return this;
            }

            /**
             * Adds a supported route protocol using its fully qualified class name.
             * <p>
             * If the protocol was not requested by the client in its selector
             * then it will be silently discarded.
             * </p>
             */
            public @NonNull <T extends IInterface> Builder addProtocol(@NonNull Class<T> clazz) {
                if (clazz == null) {
                    throw new IllegalArgumentException("clazz must not be null");
                }
                return addProtocol(clazz.getName());
            }

            /**
             * Adds a supported route protocol by name.
             * <p>
             * If the protocol was not requested by the client in its selector
             * then it will be silently discarded.
             * </p>
             */
            public @NonNull Builder addProtocol(@NonNull String name) {
                if (TextUtils.isEmpty(name)) {
                    throw new IllegalArgumentException("name must not be null");
                }
                if (mSelector.containsProtocol(name)) {
                    mProtocols.add(name);
                }
                return this;
            }

            /**
             * Sets optional extra information about the route, or null if none.
             */
            public @NonNull Builder setExtras(@Nullable Bundle extras) {
                mExtras = extras;
                return this;
            }

            /**
             * Builds the {@link RouteInfo} object.
             * <p>
             * Ensures that all required protocols have been supplied.
             * </p>
             */
            public @NonNull RouteInfo build() {
                int missingFeatures = mSelector.getRequiredFeatures() & ~mFeatures;
                if (missingFeatures != 0) {
                    throw new IllegalStateException("The media route selector "
                            + "specified required features which this route does "
                            + "not appear to support so it should not have been published: "
                            + "missing 0x" + Integer.toHexString(missingFeatures));
                }
                for (String protocol : mSelector.getRequiredProtocols()) {
                    if (!mProtocols.contains(protocol)) {
                        throw new IllegalStateException("The media route selector "
                                + "specified required protocols which this route "
                                + "does not appear to support so it should not have "
                                + "been published: missing " + protocol);
                    }
                }
                return new RouteInfo(mId, mDestination, mSelector,
                        mFeatures, mProtocols, mExtras);
            }
        }
    }

    /**
     * Describes a destination for media content such as a device,
     * an individual port on a device, or a group of devices.
     */
    public static final class DestinationInfo {
        private final String mId;
        private final ServiceMetadata mService;
        private final CharSequence mName;
        private final CharSequence mDescription;
        private final int mIconResourceId;
        private final Bundle mExtras;

        DestinationInfo(String id, ServiceMetadata service,
                CharSequence name, CharSequence description,
                int iconResourceId, Bundle extras) {
            mId = id;
            mService = service;
            mName = name;
            mDescription = description;
            mIconResourceId = iconResourceId;
            mExtras = extras;
        }

        /**
         * Gets the destination's stable identifier.
         * <p>
         * The id is intended to uniquely identify the destination among all destinations
         * provided by the media route service in such a way that the client can
         * refer to it at a later time.  Ideally, the id should be resilient to
         * user-initiated actions such as changes to the name or description
         * of the destination.
         * </p>
         */
        public @NonNull String getId() {
            return mId;
        }

        /**
         * Gets metadata about the service that is providing access to this destination.
         */
        public @NonNull ServiceMetadata getServiceMetadata() {
            return mService;
        }

        /**
         * Gets the destination's name for display to the user.
         */
        public @NonNull CharSequence getName() {
            return mName;
        }

        /**
         * Gets the destination's description for display to the user, or null if none.
         */
        public @Nullable CharSequence getDescription() {
            return mDescription;
        }

        /**
         * Gets an icon resource from the service's package which is used
         * to identify the destination, or -1 if none.
         */
        public @DrawableRes int getIconResourceId() {
            return mIconResourceId;
        }

        /**
         * Loads the icon drawable, or null if none.
         */
        public @Nullable Drawable loadIcon(@NonNull PackageManager pm) {
            return mIconResourceId >= 0 ? mService.getDrawable(pm, mIconResourceId) : null;
        }

        /**
         * Gets optional extra information about the destination, or null if none.
         */
        public @Nullable Bundle getExtras() {
            return mExtras;
        }

        @Override
        public @NonNull String toString() {
            return "DestinationInfo{ id=" + mId + ", service=" + mService + ", name=" + mName
                    + ", description=" + mDescription + ", iconResourceId=" + mIconResourceId
                    + ", extras=" + mExtras + " }";
        }

        /**
         * Builds {@link DestinationInfo} objects.
         */
        public static final class Builder {
            private final String mId;
            private final ServiceMetadata mService;
            private final CharSequence mName;
            private CharSequence mDescription;
            private int mIconResourceId = -1;
            private Bundle mExtras;

            /**
             * Creates a builder for destination information.
             *
             * @param id The destination's stable identifier.
             * @param service Metatada about the service that is providing access to
             * this destination.
             * @param name The destination's name for display to the user.
             */
            public Builder(@NonNull String id, @NonNull ServiceMetadata service,
                    @NonNull CharSequence name) {
                if (TextUtils.isEmpty(id)) {
                    throw new IllegalArgumentException("id must not be null or empty");
                }
                if (service == null) {
                    throw new IllegalArgumentException("service must not be null");
                }
                if (TextUtils.isEmpty(name)) {
                    throw new IllegalArgumentException("name must not be null or empty");
                }
                mId = id;
                mService = service;
                mName = name;
            }

            /**
             * Sets the destination's description for display to the user, or null if none.
             */
            public @NonNull Builder setDescription(@Nullable CharSequence description) {
                mDescription = description;
                return this;
            }

            /**
             * Sets an icon resource from this package used to identify the destination,
             * or -1 if none.
             */
            public @NonNull Builder setIconResourceId(@DrawableRes int resid) {
                mIconResourceId = resid;
                return this;
            }

            /**
             * Gets optional extra information about the destination, or null if none.
             */
            public @NonNull Builder setExtras(@Nullable Bundle extras) {
                mExtras = extras;
                return this;
            }

            /**
             * Builds the {@link DestinationInfo} object.
             */
            public @NonNull DestinationInfo build() {
                return new DestinationInfo(mId, mService, mName, mDescription,
                        mIconResourceId, mExtras);
            }
        }
    }

    /**
     * Describes metadata about a {@link MediaRouteService} which is providing
     * access to certain kinds of destinations.
     */
    public static final class ServiceMetadata {
        private final ServiceInfo mService;
        private CharSequence mLabel;
        private Drawable mIcon;

        ServiceMetadata(Service service) throws NameNotFoundException {
            mService = service.getPackageManager().getServiceInfo(
                    new ComponentName(service, service.getClass()),
                    PackageManager.GET_META_DATA);
        }

        ServiceMetadata(ServiceInfo service) {
            mService = service;
        }

        /**
         * Gets the service's component information including it name, label and icon.
         */
        public @NonNull ServiceInfo getService() {
            return mService;
        }

        /**
         * Gets the service's component name.
         */
        public @NonNull ComponentName getComponentName() {
            return new ComponentName(mService.packageName, mService.name);
        }

        /**
         * Gets the service's package name.
         */
        public @NonNull String getPackageName() {
            return mService.packageName;
        }

        /**
         * Gets the service's name for display to the user, or null if none.
         */
        public @NonNull CharSequence getLabel(@NonNull PackageManager pm) {
            if (mLabel == null) {
                mLabel = mService.loadLabel(pm);
            }
            return mLabel;
        }

        /**
         * Gets the icon drawable, or null if none.
         */
        public @Nullable Drawable getIcon(@NonNull PackageManager pm) {
            if (mIcon == null) {
                mIcon = mService.loadIcon(pm);
            }
            return mIcon;
        }

        // TODO: add service metadata

        Drawable getDrawable(PackageManager pm, int resid) {
            return pm.getDrawable(getPackageName(), resid, mService.applicationInfo);
        }

        @Override
        public @NonNull String toString() {
            return "ServiceInfo{ service=" + getComponentName().toShortString() + " }";
        }
    }

    /**
     * Describes a request to discover routes on behalf of an application.
     */
    public static final class DiscoveryRequest {
        private final ArrayList<MediaRouteSelector> mSelectors =
                new ArrayList<MediaRouteSelector>();
        private int mFlags;

        DiscoveryRequest(@NonNull List<MediaRouteSelector> selectors) {
            setSelectors(selectors);
        }

        /**
         * Sets the list of media route selectors to consider during discovery.
         */
        public void setSelectors(@NonNull List<MediaRouteSelector> selectors) {
            if (selectors == null) {
                throw new IllegalArgumentException("selectors");
            }
            mSelectors.clear();
            mSelectors.addAll(selectors);
        }

        /**
         * Gets the list of media route selectors to consider during discovery.
         */
        public @NonNull List<MediaRouteSelector> getSelectors() {
            return mSelectors;
        }

        /**
         * Gets discovery flags, such as {@link MediaRouter#DISCOVERY_FLAG_BACKGROUND}.
         */
        public @DiscoveryFlags int getFlags() {
            return mFlags;
        }

        /**
         * Sets discovery flags, such as {@link MediaRouter#DISCOVERY_FLAG_BACKGROUND}.
         */
        public void setFlags(@DiscoveryFlags int flags) {
            mFlags = flags;
        }

        @Override
        public @NonNull String toString() {
            return "DiscoveryRequest{ selectors=" + mSelectors
                    + ", flags=0x" + Integer.toHexString(mFlags)
                    + " }";
        }
    }

    /**
     * Describes a request to connect to a previously discovered route on
     * behalf of an application.
     */
    public static final class ConnectionRequest {
        private RouteInfo mRoute;
        private int mFlags;
        private Bundle mExtras;

        ConnectionRequest(@NonNull RouteInfo route) {
            setRoute(route);
        }

        /**
         * Gets the route to which to connect.
         */
        public @NonNull RouteInfo getRoute() {
            return mRoute;
        }

        /**
         * Sets the route to which to connect.
         */
        public void setRoute(@NonNull RouteInfo route) {
            if (route == null) {
                throw new IllegalArgumentException("route must not be null");
            }
            mRoute = route;
        }

        /**
         * Gets connection flags, such as {@link MediaRouter#CONNECTION_FLAG_BARGE}.
         */
        public @ConnectionFlags int getFlags() {
            return mFlags;
        }

        /**
         * Sets connection flags, such as {@link MediaRouter#CONNECTION_FLAG_BARGE}.
         */
        public void setFlags(@ConnectionFlags int flags) {
            mFlags = flags;
        }

        /**
         * Gets optional extras supplied by the application as part of the call to
         * connect, or null if none.  The media route service may use this
         * information to configure the route during connection.
         */
        public @Nullable Bundle getExtras() {
            return mExtras;
        }

        /**
         * Sets optional extras supplied by the application as part of the call to
         * connect, or null if none.  The media route service may use this
         * information to configure the route during connection.
         */
        public void setExtras(@Nullable Bundle extras) {
            mExtras = extras;
        }

        @Override
        public @NonNull String toString() {
            return "ConnectionRequest{ route=" + mRoute
                    + ", flags=0x" + Integer.toHexString(mFlags)
                    + ", extras=" + mExtras + " }";
        }
    }

    /**
     * Callback interface to specify policy for route discovery, filtering,
     * and connection establishment as well as observe media router state changes.
     */
    public static abstract class RoutingCallback extends StateCallback {
        /**
         * Called to prepare a discovery request object to specify the desired
         * media route selectors when the media router has been asked to start discovery.
         * <p>
         * By default, the discovery request contains all of the selectors which
         * have been added to the media router.  Subclasses may override the list of
         * selectors by modifying the discovery request object before returning.
         * </p>
         *
         * @param request The discovery request object which may be modified by
         * this method to alter how discovery will be performed.
         * @param selectors The immutable list of media route selectors which were
         * added to the media router.
         * @return True to allow discovery to proceed or false to abort it.
         * By default, this methods returns true.
         */
        public boolean onPrepareDiscoveryRequest(@NonNull DiscoveryRequest request,
                @NonNull List<MediaRouteSelector> selectors) {
            return true;
        }

        /**
         * Called to prepare a connection request object to specify the desired
         * route and connection parameters when the media router has been asked to
         * connect to a particular destination.
         * <p>
         * By default, the connection request specifies the first available route
         * to the destination.  Subclasses may override the route and destination
         * or set additional connection parameters by modifying the connection request
         * object before returning.
         * </p>
         *
         * @param request The connection request object which may be modified by
         * this method to alter how the connection will be established.
         * @param destination The destination to which the media router was asked
         * to connect.
         * @param routes The list of routes that belong to that destination sorted
         * in the same order as their matching media route selectors which were
         * used during discovery.
         * @return True to allow the connection to proceed or false to abort it.
         * By default, this methods returns true.
         */
        public boolean onPrepareConnectionRequest(
                @NonNull ConnectionRequest request,
                @NonNull DestinationInfo destination, @NonNull List<RouteInfo> routes) {
            return true;
        }
    }

    /**
     * Callback class to receive events from a {@link MediaRouter.Delegate}.
     */
    public static abstract class StateCallback {
        /**
         * Called when the media router has been released.
         */
        public void onReleased() { }

        /**
         * Called when the discovery state has changed.
         *
         * @param state The new discovery state: one of
         * {@link #DISCOVERY_STATE_STOPPED} or {@link #DISCOVERY_STATE_STARTED}.
         */
        public void onDiscoveryStateChanged(@DiscoveryState int state) { }

        /**
         * Called when the connection state has changed.
         *
         * @param state The new connection state: one of
         * {@link #CONNECTION_STATE_DISCONNECTED}, {@link #CONNECTION_STATE_CONNECTING}
         * or {@link #CONNECTION_STATE_CONNECTED}.
         */
        public void onConnectionStateChanged(@ConnectionState int state) { }

        /**
         * Called when the selected destination has changed.
         *
         * @param destination The new selected destination, or null if none.
         */
        public void onSelectedDestinationChanged(@Nullable DestinationInfo destination) { }

        /**
         * Called when route discovery has started.
         */
        public void onDiscoveryStarted() { }

        /**
         * Called when route discovery has stopped normally.
         * <p>
         * Abnormal termination is reported via {@link #onDiscoveryFailed}.
         * </p>
         */
        public void onDiscoveryStopped() { }

        /**
         * Called when discovery has failed in a non-recoverable manner.
         *
         * @param error The error code: one of
         * {@link MediaRouter#DISCOVERY_ERROR_UNKNOWN},
         * {@link MediaRouter#DISCOVERY_ERROR_ABORTED},
         * or {@link MediaRouter#DISCOVERY_ERROR_NO_CONNECTIVITY}.
         * @param message The localized error message, or null if none.  This message
         * may be shown to the user.
         * @param extras Additional information about the error which a client
         * may use, or null if none.
         */
        public void onDiscoveryFailed(@DiscoveryError int error, @Nullable CharSequence message,
                @Nullable Bundle extras) { }

        /**
         * Called when a new destination is found or has changed during discovery.
         * <p>
         * Certain destinations may be omitted because they have been filtered
         * out by the media router's routing callback.
         * </p>
         *
         * @param destination The destination that was found.
         */
        public void onDestinationFound(@NonNull DestinationInfo destination) { }

        /**
         * Called when a destination is no longer reachable or is no longer
         * offering any routes that satisfy the discovery request.
         *
         * @param destination The destination that went away.
         */
        public void onDestinationLost(@NonNull DestinationInfo destination) { }

        /**
         * Called when a connection attempt begins.
         */
        public void onConnecting() { }

        /**
         * Called when the connection succeeds.
         */
        public void onConnected() { }

        /**
         * Called when the connection is terminated normally.
         * <p>
         * Abnormal termination is reported via {@link #onConnectionFailed}.
         * </p>
         */
        public void onDisconnected() { }

        /**
         * Called when a connection attempt or connection in
         * progress has failed in a non-recoverable manner.
         *
         * @param error The error code: one of
         * {@link MediaRouter#CONNECTION_ERROR_ABORTED},
         * {@link MediaRouter#CONNECTION_ERROR_UNAUTHORIZED},
         * {@link MediaRouter#CONNECTION_ERROR_UNREACHABLE},
         * {@link MediaRouter#CONNECTION_ERROR_BUSY},
         * {@link MediaRouter#CONNECTION_ERROR_TIMEOUT},
         * {@link MediaRouter#CONNECTION_ERROR_BROKEN},
         * or {@link MediaRouter#CONNECTION_ERROR_BARGED}.
         * @param message The localized error message, or null if none.  This message
         * may be shown to the user.
         * @param extras Additional information about the error which a client
         * may use, or null if none.
         */
        public void onConnectionFailed(@ConnectionError int error,
                @Nullable CharSequence message, @Nullable Bundle extras) { }
    }
}
