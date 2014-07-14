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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.routing.MediaRouter.ConnectionError;
import android.media.routing.MediaRouter.ConnectionInfo;
import android.media.routing.MediaRouter.ConnectionRequest;
import android.media.routing.MediaRouter.DestinationInfo;
import android.media.routing.MediaRouter.DiscoveryError;
import android.media.routing.MediaRouter.DiscoveryRequest;
import android.media.routing.MediaRouter.RouteInfo;
import android.media.routing.MediaRouter.ServiceMetadata;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Media route services implement strategies for discovering
 * and establishing connections to media devices and their routes.  These services
 * are also known as media route providers.
 * <p>
 * Each media route service subclass is responsible for enabling applications
 * and the system to interact with media devices of some kind.
 * For example, one particular media route service implementation might
 * offer support for discovering nearby wireless display devices and streaming
 * video contents to them; another media route service implementation might
 * offer support for discovering nearby speakers and streaming media appliances
 * and sending commands to play content on request.
 * </p><p>
 * Subclasses must override the {@link #onCreateClientSession} method to return
 * a {@link ClientSession} object that implements the {@link ClientSession#onStartDiscovery},
 * {@link ClientSession#onStopDiscovery}, and {@link ClientSession#onConnect} methods
 * to allow clients to discover and connect to media devices.
 * </p><p>
 * This object is not thread-safe.  All callbacks are invoked on the main looper.
 * </p>
 *
 * <h3>Clients</h3>
 * <p>
 * The clients of this API are media applications that would like to discover
 * and connect to media devices.  The client may also be the system, such as
 * when the user initiates display mirroring via the Cast Screen function.
 * </p><p>
 * There may be multiple client sessions active at the same time.  Each client
 * session can request discovery and connect to routes independently of any
 * other client.  It is the responsibility of the media route service to maintain
 * separate state for each client session and to ensure that clients cannot interfere
 * with one another in harmful ways.
 * </p><p>
 * Notwithstanding the requirement to support any number of concurrent client
 * sessions, the media route service may impose constraints on how many clients
 * can connect to the same media device in a particular mode at the same time.
 * In some cases, media devices may support connections from an arbitrary number
 * of clients simultaneously but often it may be necessary to ensure that only
 * one client is in control.  When this happens, the media route service should
 * report a connection error unless the connection request specifies that the
 * client should take control of the media device (and forcibly disconnect other
 * clients that may be using it).
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
 * <h3>Discovery</h3>
 * <p>
 * Discovery is the process of finding destinations based on a description of the
 * kinds of routes that an application or the system would like to use.
 * </p><p>
 * Discovery begins when {@link ClientSession#onStartDiscovery} is called and ends when
 * {@link ClientSession#onStopDiscovery} is called.  There may be multiple simultaneous
 * discovery requests in progress at the same time from different clients.  It is up to
 * the media route service to perform these requests in parallel or multiplex them
 * as required.
 * </p><p>
 * Media route services are <em>strongly encouraged</em> to use the information
 * in the discovery request to optimize discovery and avoid redundant work.
 * In the case where no media device supported by the media route service
 * could possibly offer the requested capabilities, the
 * {@link ClientSession#onStartDiscovery} method should return <code>false</code> to
 * let the system know that it can unbind from the media route service and
 * release its resources.
 * </p>
 *
 * <h3>Settings</h3>
 * <p>
 * Many kinds of devices can be discovered on demand simply by scanning the local network
 * or using wireless protocols such as Bluetooth to find them.  However, in some cases
 * it may be necessary for the user to manually configure destinations before they
 * can be used (or to adjust settings later).  Actual user configuration of destinations
 * is beyond the scope of this API but media route services may specify an activity
 * in their manifest that the user can launch to perform these tasks.
 * </p><p>
 * Note that media route services that are installed from the store must be enabled
 * by the user before they become available for applications to use.
 * The {@link android.provider.Settings#ACTION_CAST_SETTINGS Settings.ACTION_CAST_SETTINGS}
 * settings activity provides the ability for the user to configure media route services.
 * </p>
 *
 * <h3>Manifest Declaration</h3>
 * <p>
 * Media route services must be declared in the manifest along with meta-data
 * about the kinds of routes that they are capable of discovering.  The system
 * uses this information to optimize the set of services to which it binds in
 * order to satisfy a particular discovery request.
 * </p><p>
 * To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_MEDIA_ROUTE_SERVICE} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action.  You must
 * also add meta-data to describe the kinds of routes that your service is capable
 * of discovering.
 * </p><p>
 * For example:
 * </p><pre>
 * &lt;service android:name=".MediaRouteProvider"
 *          android:label="&#64;string/service_name"
 *          android:permission="android.permission.BIND_MEDIA_ROUTE_SERVICE">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.media.routing.MediaRouteService" />
 *     &lt;/intent-filter>
 *
 *     TODO: INSERT METADATA DECLARATIONS HERE
 *
 * &lt;/service>
 * </pre>
 */
public abstract class MediaRouteService extends Service {
    private static final String TAG = "MediaRouteService";

    private static final boolean DEBUG = true;

    private final Handler mHandler;
    private final BinderService mService;
    private final ArrayMap<IBinder, ClientRecord> mClientRecords =
            new ArrayMap<IBinder, ClientRecord>();

    private ServiceMetadata mMetadata;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.media.routing.MediaRouteService";

    /**
     * Creates a media route service.
     */
    public MediaRouteService() {
        mHandler = new Handler(true);
        mService = new BinderService();
    }

    @Override
    public @Nullable IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mService;
        }
        return null;
    }

    /**
     * Creates a new client session on behalf of a client.
     * <p>
     * The implementation should return a {@link ClientSession} for the client
     * to use.  The media route service must take care to manage the state of
     * each client session independently from any others that might also be
     * in use at the same time.
     * </p>
     *
     * @param client Information about the client.
     * @return The client session object, or null if the client is not allowed
     * to interact with this media route service.
     */
    public abstract @Nullable ClientSession onCreateClientSession(@NonNull ClientInfo client);

    /**
     * Gets metadata about this service.
     * <p>
     * Use this method to obtain a {@link ServiceMetadata} object to provide when creating
     * a {@link android.media.routing.MediaRouter.DestinationInfo.Builder}.
     * </p>
     *
     * @return Metadata about this service.
     */
    public @NonNull ServiceMetadata getServiceMetadata() {
        if (mMetadata == null) {
            try {
                mMetadata = new ServiceMetadata(this);
            } catch (NameNotFoundException ex) {
                Log.wtf(TAG, "Could not retrieve own service metadata!");
            }
        }
        return mMetadata;
    }

    /**
     * Enables a single client to access the functionality of the media route service.
     */
    public static abstract class ClientSession {
        /**
         * Starts discovery.
         * <p>
         * If the media route service is capable of discovering routes that satisfy
         * the request then this method should start discovery and return true.
         * Otherwise, this method should return false.  If false is returned,
         * then the framework will not call {@link #onStopDiscovery} since discovery
         * was never actually started.
         * </p><p>
         * There may already be other discovery requests in progress at the same time
         * for other clients; the media route service must keep track of them all.
         * </p>
         *
         * @param req The discovery request to start.
         * @param callback A callback to receive discovery events related to this
         * particular request.  The events that the service sends to this callback
         * will be sent to the client that initiated the discovery request.
         * @return True if discovery has started.  False if the media route service
         * is unable to discover routes that satisfy the request.
         */
        public abstract boolean onStartDiscovery(@NonNull DiscoveryRequest req,
                @NonNull DiscoveryCallback callback);

        /**
         * Stops discovery.
         * <p>
         * If {@link #onStartDiscovery} returned true, then this method will eventually
         * be called when the framework no longer requires this discovery request
         * to be performed.
         * </p><p>
         * There may still be other discovery requests in progress for other clients;
         * they must keep working until they have each been stopped by their client.
         * </p>
         */
        public abstract void onStopDiscovery();

        /**
         * Starts connecting to a route.
         *
         * @param req The connection request.
         * @param callback A callback to receive events connection events related
         * to this particular request.  The events that the service sends to this callback
         * will be sent to the client that initiated the discovery request.
         * @return True if the connection is in progress, or false if the client
         * unable to connect to the requested route.
         */
        public abstract boolean onConnect(@NonNull ConnectionRequest req,
                @NonNull ConnectionCallback callback);

        /**
         * Called when the client requests to disconnect from the route
         * or abort a connection attempt in progress.
         */
        public abstract void onDisconnect();

        /**
         * Called when the client requests to pause streaming of content to
         * live audio/video routes such as when it goes into the background.
         * <p>
         * The default implementation does nothing.
         * </p>
         */
        public void onPauseStream() { }

        /**
         * Called when the application requests to resume streaming of content to
         * live audio/video routes such as when it returns to the foreground.
         * <p>
         * The default implementation does nothing.
         * </p>
         */
        public void onResumeStream() { }

        /**
         * Called when the client is releasing the session.
         * <p>
         * The framework automatically takes care of stopping discovery and
         * terminating the connection politely before calling this method to release
         * the session.
         * </p><p>
         * The default implementation does nothing.
         * </p>
         */
        public void onRelease() { }
    }

    /**
     * Provides events in response to a discovery request.
     */
    public final class DiscoveryCallback {
        private final ClientRecord mRecord;

        DiscoveryCallback(ClientRecord record) {
            mRecord = record;
        }

        /**
         * Called by the service when a destination is found that
         * offers one or more routes that satisfy the discovery request.
         * <p>
         * This method should be called whenever the list of available routes
         * at a destination changes or whenever the properties of the destination
         * itself change.
         * </p>
         *
         * @param destination The destination that was found.
         * @param routes The list of that destination's routes that satisfy the
         * discovery request.
         */
        public void onDestinationFound(final @NonNull DestinationInfo destination,
                final @NonNull List<RouteInfo> routes) {
            if (destination == null) {
                throw new IllegalArgumentException("destination must not be null");
            }
            if (routes == null) {
                throw new IllegalArgumentException("routes must not be null");
            }
            for (int i = 0; i < routes.size(); i++) {
                if (routes.get(i).getDestination() != destination) {
                    throw new IllegalArgumentException("routes must refer to the "
                            + "destination");
                }
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mRecord.dispatchDestinationFound(DiscoveryCallback.this,
                            destination, routes);
                }
            });
        }

        /**
         * Called by the service when a destination is no longer
         * reachable or is no longer offering any routes that satisfy
         * the discovery request.
         *
         * @param destination The destination that went away.
         */
        public void onDestinationLost(final @NonNull DestinationInfo destination) {
            if (destination == null) {
                throw new IllegalArgumentException("destination must not be null");
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mRecord.dispatchDestinationLost(DiscoveryCallback.this, destination);
                }
            });
        }

        /**
         * Called by the service when a discovery has failed in a non-recoverable manner.
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
        public void onDiscoveryFailed(final @DiscoveryError int error,
                final @Nullable CharSequence message, final @Nullable Bundle extras) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mRecord.dispatchDiscoveryFailed(DiscoveryCallback.this,
                            error, message, extras);
                }
            });
        }
    }

    /**
     * Provides events in response to a connection request.
     */
    public final class ConnectionCallback {
        private final ClientRecord mRecord;

        ConnectionCallback(ClientRecord record) {
            mRecord = record;
        }

        /**
         * Called by the service when the connection succeeds.
         *
         * @param connection Immutable information about the connection.
         */
        public void onConnected(final @NonNull ConnectionInfo connection) {
            if (connection == null) {
                throw new IllegalArgumentException("connection must not be null");
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mRecord.dispatchConnected(ConnectionCallback.this, connection);
                }
            });
        }

        /**
         * Called by the service when the connection is terminated normally.
         * <p>
         * Abnormal termination is reported via {@link #onConnectionFailed}.
         * </p>
         */
        public void onDisconnected() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mRecord.dispatchDisconnected(ConnectionCallback.this);
                }
            });
        }

        /**
         * Called by the service when a connection attempt or connection in
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
        public void onConnectionFailed(final @ConnectionError int error,
                final @Nullable CharSequence message, final @Nullable Bundle extras) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mRecord.dispatchConnectionFailed(ConnectionCallback.this,
                            error, message, extras);
                }
            });
        }
    }

    /**
     * Identifies a client of the media route service.
     */
    public static final class ClientInfo {
        private final int mUid;
        private final String mPackageName;

        ClientInfo(int uid, String packageName) {
            mUid = uid;
            mPackageName = packageName;
        }

        /**
         * Gets the UID of the client application.
         */
        public int getUid() {
            return mUid;
        }

        /**
         * Gets the package name of the client application.
         */
        public @NonNull String getPackageName() {
            return mPackageName;
        }

        @Override
        public @NonNull String toString() {
            return "ClientInfo{ uid=" + mUid + ", package=" + mPackageName + " }";
        }
    }

    private final class BinderService extends IMediaRouteService.Stub {
        @Override
        public void registerClient(final int clientUid, final String clientPackageName,
                final IMediaRouteClientCallback callback) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ClientInfo client = new ClientInfo(clientUid, clientPackageName);
                    if (DEBUG) {
                        Log.d(TAG, "registerClient: client=" + client);
                    }

                    ClientSession session = onCreateClientSession(client);
                    if (session == null) {
                        // request refused by service
                        Log.w(TAG, "Media route service refused to create session for client: "
                                + "client=" + client);
                        return;
                    }

                    ClientRecord record = new ClientRecord(callback, client, session);
                    try {
                        callback.asBinder().linkToDeath(record, 0);
                    } catch (RemoteException ex) {
                        // client died prematurely
                        Log.w(TAG, "Client died prematurely while creating session: "
                                + "client=" + client);
                        record.release();
                        return;
                    }

                    mClientRecords.put(callback.asBinder(), record);
                }
            });
        }

        @Override
        public void unregisterClient(IMediaRouteClientCallback callback) {
            unregisterClient(callback, false);
        }

        void unregisterClient(final IMediaRouteClientCallback callback,
                final boolean died) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ClientRecord record = mClientRecords.remove(callback.asBinder());
                    if (record == null) {
                        return; // spurious
                    }

                    if (DEBUG) {
                        Log.d(TAG, "unregisterClient: client=" + record.getClientInfo()
                                + ", died=" + died);
                    }

                    record.release();
                    callback.asBinder().unlinkToDeath(record, 0);
                }
            });
        }

        @Override
        public void startDiscovery(final IMediaRouteClientCallback callback,
                final int seq, final List<MediaRouteSelector> selectors,
                final int flags) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ClientRecord record = mClientRecords.get(callback.asBinder());
                    if (record == null) {
                        return; // spurious
                    }

                    if (DEBUG) {
                        Log.d(TAG, "startDiscovery: client=" + record.getClientInfo()
                                + ", seq=" + seq + ", selectors=" + selectors
                                + ", flags=0x" + Integer.toHexString(flags));
                    }
                    record.startDiscovery(seq, selectors, flags);
                }
            });
        }

        @Override
        public void stopDiscovery(final IMediaRouteClientCallback callback) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ClientRecord record = mClientRecords.get(callback.asBinder());
                    if (record == null) {
                        return; // spurious
                    }

                    if (DEBUG) {
                        Log.d(TAG, "stopDiscovery: client=" + record.getClientInfo());
                    }
                    record.stopDiscovery();
                }
            });
        }

        @Override
        public void connect(final IMediaRouteClientCallback callback,
                final int seq, final String destinationId, final String routeId,
                final int flags, final Bundle extras) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ClientRecord record = mClientRecords.get(callback.asBinder());
                    if (record == null) {
                        return; // spurious
                    }

                    if (DEBUG) {
                        Log.d(TAG, "connect: client=" + record.getClientInfo()
                                + ", seq=" + seq + ", destinationId=" + destinationId
                                + ", routeId=" + routeId
                                + ", flags=0x" + Integer.toHexString(flags)
                                + ", extras=" + extras);
                    }
                    record.connect(seq, destinationId, routeId, flags, extras);
                }
            });
        }

        @Override
        public void disconnect(final IMediaRouteClientCallback callback) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ClientRecord record = mClientRecords.get(callback.asBinder());
                    if (record == null) {
                        return; // spurious
                    }

                    if (DEBUG) {
                        Log.d(TAG, "disconnect: client=" + record.getClientInfo());
                    }
                    record.disconnect();
                }
            });
        }

        @Override
        public void pauseStream(final IMediaRouteClientCallback callback) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ClientRecord record = mClientRecords.get(callback.asBinder());
                    if (record == null) {
                        return; // spurious
                    }

                    if (DEBUG) {
                        Log.d(TAG, "pauseStream: client=" + record.getClientInfo());
                    }
                    record.pauseStream();
                }
            });
        }

        @Override
        public void resumeStream(final IMediaRouteClientCallback callback) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ClientRecord record = mClientRecords.get(callback.asBinder());
                    if (record == null) {
                        return; // spurious
                    }

                    if (DEBUG) {
                        Log.d(TAG, "resumeStream: client=" + record.getClientInfo());
                    }
                    record.resumeStream();
                }
            });
        }
    }

    // Must be accessed on handler
    private final class ClientRecord implements IBinder.DeathRecipient {
        private final IMediaRouteClientCallback mClientCallback;
        private final ClientInfo mClient;
        private final ClientSession mSession;

        private int mDiscoverySeq;
        private DiscoveryRequest mDiscoveryRequest;
        private DiscoveryCallback mDiscoveryCallback;
        private final ArrayMap<String, DestinationRecord> mDestinations =
                new ArrayMap<String, DestinationRecord>();

        private int mConnectionSeq;
        private ConnectionRequest mConnectionRequest;
        private ConnectionCallback mConnectionCallback;
        private ConnectionInfo mConnection;
        private boolean mConnectionPaused;

        public ClientRecord(IMediaRouteClientCallback callback,
                ClientInfo client, ClientSession session) {
            mClientCallback = callback;
            mClient = client;
            mSession = session;
        }

        // Invoked on binder thread unlike all other methods in this class.
        @Override
        public void binderDied() {
            mService.unregisterClient(mClientCallback, true);
        }

        public ClientInfo getClientInfo() {
            return mClient;
        }

        public void release() {
            stopDiscovery();
            disconnect();
        }

        public void startDiscovery(int seq, List<MediaRouteSelector> selectors,
                int flags) {
            stopDiscovery();

            mDiscoverySeq = seq;
            mDiscoveryRequest = new DiscoveryRequest(selectors);
            mDiscoveryRequest.setFlags(flags);
            mDiscoveryCallback = new DiscoveryCallback(this);
            boolean started = mSession.onStartDiscovery(mDiscoveryRequest, mDiscoveryCallback);
            if (!started) {
                dispatchDiscoveryFailed(mDiscoveryCallback,
                        MediaRouter.DISCOVERY_ERROR_ABORTED, null, null);
                clearDiscovery();
            }
        }

        public void stopDiscovery() {
            if (mDiscoveryRequest != null) {
                mSession.onStopDiscovery();
                clearDiscovery();
            }
        }

        private void clearDiscovery() {
            mDestinations.clear();
            mDiscoveryRequest = null;
            mDiscoveryCallback = null;
        }

        public void connect(int seq, String destinationId, String routeId,
                int flags, Bundle extras) {
            disconnect();

            mConnectionSeq = seq;
            mConnectionCallback = new ConnectionCallback(this);

            DestinationRecord destinationRecord = mDestinations.get(destinationId);
            if (destinationRecord == null) {
                Log.w(TAG, "Aborting connection to route since no matching destination "
                        + "was found in the list of known destinations: "
                        + "destinationId=" + destinationId);
                dispatchConnectionFailed(mConnectionCallback,
                        MediaRouter.CONNECTION_ERROR_ABORTED, null, null);
                clearConnection();
                return;
            }

            RouteInfo route = destinationRecord.getRoute(routeId);
            if (route == null) {
                Log.w(TAG, "Aborting connection to route since no matching route "
                        + "was found in the list of known routes: "
                        + "destination=" + destinationRecord.destination
                        + ", routeId=" + routeId);
                dispatchConnectionFailed(mConnectionCallback,
                        MediaRouter.CONNECTION_ERROR_ABORTED, null, null);
                clearConnection();
                return;
            }

            mConnectionRequest = new ConnectionRequest(route);
            mConnectionRequest.setFlags(flags);
            mConnectionRequest.setExtras(extras);
            boolean started = mSession.onConnect(mConnectionRequest, mConnectionCallback);
            if (!started) {
                dispatchConnectionFailed(mConnectionCallback,
                        MediaRouter.CONNECTION_ERROR_ABORTED, null, null);
                clearConnection();
            }
        }

        public void disconnect() {
            if (mConnectionRequest != null) {
                mSession.onDisconnect();
                clearConnection();
            }
        }

        private void clearConnection() {
            mConnectionRequest = null;
            mConnectionCallback = null;
            if (mConnection != null) {
                mConnection.close();
                mConnection = null;
            }
            mConnectionPaused = false;
        }

        public void pauseStream() {
            if (mConnectionRequest != null && !mConnectionPaused) {
                mConnectionPaused = true;
                mSession.onPauseStream();
            }
        }

        public void resumeStream() {
            if (mConnectionRequest != null && mConnectionPaused) {
                mConnectionPaused = false;
                mSession.onResumeStream();
            }
        }

        public void dispatchDestinationFound(DiscoveryCallback callback,
                DestinationInfo destination, List<RouteInfo> routes) {
            if (callback == mDiscoveryCallback) {
                if (DEBUG) {
                    Log.d(TAG, "destinationFound: destination=" + destination
                            + ", routes=" + routes);
                }
                mDestinations.put(destination.getId(),
                        new DestinationRecord(destination, routes));

                ParcelableDestinationInfo pdi = new ParcelableDestinationInfo();
                pdi.id = destination.getId();
                pdi.name = destination.getName();
                pdi.description = destination.getDescription();
                pdi.iconResourceId = destination.getIconResourceId();
                pdi.extras = destination.getExtras();
                ArrayList<ParcelableRouteInfo> pris = new ArrayList<ParcelableRouteInfo>();
                for (RouteInfo route : routes) {
                    int selectorIndex = mDiscoveryRequest.getSelectors().indexOf(
                            route.getSelector());
                    if (selectorIndex < 0) {
                        Log.w(TAG, "Ignoring route because the selector does not match "
                                + "any of those that were originally supplied by the "
                                + "client's discovery request: destination=" + destination
                                + ", route=" + route);
                        continue;
                    }

                    ParcelableRouteInfo pri = new ParcelableRouteInfo();
                    pri.id = route.getId();
                    pri.selectorIndex = selectorIndex;
                    pri.features = route.getFeatures();
                    pri.protocols = route.getProtocols().toArray(
                            new String[route.getProtocols().size()]);
                    pri.extras = route.getExtras();
                    pris.add(pri);
                }
                try {
                    mClientCallback.onDestinationFound(mDiscoverySeq, pdi,
                            pris.toArray(new ParcelableRouteInfo[pris.size()]));
                } catch (RemoteException ex) {
                    // binder death handled elsewhere
                }
            }
        }

        public void dispatchDestinationLost(DiscoveryCallback callback,
                DestinationInfo destination) {
            if (callback == mDiscoveryCallback) {
                if (DEBUG) {
                    Log.d(TAG, "destinationLost: destination=" + destination);
                }

                if (mDestinations.get(destination.getId()).destination == destination) {
                    mDestinations.remove(destination.getId());
                    try {
                        mClientCallback.onDestinationLost(mDiscoverySeq, destination.getId());
                    } catch (RemoteException ex) {
                        // binder death handled elsewhere
                    }
                }
            }
        }

        public void dispatchDiscoveryFailed(DiscoveryCallback callback,
                int error, CharSequence message, Bundle extras) {
            if (callback == mDiscoveryCallback) {
                if (DEBUG) {
                    Log.d(TAG, "discoveryFailed: error=" + error + ", message=" + message
                            + ", extras=" + extras);
                }

                try {
                    mClientCallback.onDiscoveryFailed(mDiscoverySeq, error, message, extras);
                } catch (RemoteException ex) {
                    // binder death handled elsewhere
                }
            }
        }

        public void dispatchConnected(ConnectionCallback callback, ConnectionInfo connection) {
            if (callback == mConnectionCallback) {
                if (DEBUG) {
                    Log.d(TAG, "connected: connection=" + connection);
                }
                if (mConnection == null) {
                    mConnection = connection;

                    ParcelableConnectionInfo pci = new ParcelableConnectionInfo();
                    pci.audioAttributes = connection.getAudioAttributes();
                    pci.presentationDisplayId = connection.getPresentationDisplay() != null ?
                            connection.getPresentationDisplay().getDisplayId() : -1;
                    pci.protocolBinders = new IBinder[connection.getProtocols().size()];
                    for (int i = 0; i < pci.protocolBinders.length; i++) {
                        pci.protocolBinders[i] = connection.getProtocolBinder(i);
                    }
                    pci.extras = connection.getExtras();
                    try {
                        mClientCallback.onConnected(mConnectionSeq, pci);
                    } catch (RemoteException ex) {
                        // binder death handled elsewhere
                    }
                } else {
                    Log.w(TAG, "Media route service called onConnected() while already "
                            + "connected.");
                }
            }
        }

        public void dispatchDisconnected(ConnectionCallback callback) {
            if (callback == mConnectionCallback) {
                if (DEBUG) {
                    Log.d(TAG, "disconnected");
                }

                if (mConnection != null) {
                    mConnection.close();
                    mConnection = null;

                    try {
                        mClientCallback.onDisconnected(mConnectionSeq);
                    } catch (RemoteException ex) {
                        // binder death handled elsewhere
                    }
                }
            }
        }

        public void dispatchConnectionFailed(ConnectionCallback callback,
                int error, CharSequence message, Bundle extras) {
            if (callback == mConnectionCallback) {
                if (DEBUG) {
                    Log.d(TAG, "connectionFailed: error=" + error + ", message=" + message
                            + ", extras=" + extras);
                }

                try {
                    mClientCallback.onConnectionFailed(mConnectionSeq, error, message, extras);
                } catch (RemoteException ex) {
                    // binder death handled elsewhere
                }
            }
        }
    }

    private static final class DestinationRecord {
        public final DestinationInfo destination;
        public final List<RouteInfo> routes;

        public DestinationRecord(DestinationInfo destination, List<RouteInfo> routes) {
            this.destination = destination;
            this.routes = routes;
        }

        public RouteInfo getRoute(String routeId) {
            final int count = routes.size();
            for (int i = 0; i < count; i++) {
                RouteInfo route = routes.get(i);
                if (route.getId().equals(routeId)) {
                    return route;
                }
            }
            return null;
        }
    }
}
