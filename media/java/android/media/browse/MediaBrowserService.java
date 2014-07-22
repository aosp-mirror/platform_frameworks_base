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

package android.media.browse;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Base class for media browse services.
 * <p>
 * Media browse services enable applications to browse media content provided by an application
 * and ask the application to start playing it.  They may also be used to control content that
 * is already playing by way of a {@link MediaSession}.
 * </p>
 *
 * To extend this class, you must declare the service in your manifest file with
 * an intent filter with the {@link #SERVICE_ACTION} action.
 *
 * For example:
 * </p><pre>
 * &lt;service android:name=".MyMediaBrowserService"
 *          android:label="&#64;string/service_name" >
 *     &lt;intent-filter>
 *         &lt;action android:name="android.media.browse.MediaBrowserService" />
 *     &lt;/intent-filter>
 * &lt;/service>
 * </pre>
 *
 */
public abstract class MediaBrowserService extends Service {
    private static final String TAG = "MediaBrowserService";

    private final ArrayMap<IBinder, ConnectionRecord> mConnections = new ArrayMap();
    private final Handler mHandler = new Handler();
    private ServiceBinder mBinder;
    MediaSession.Token mSession;

    /**
     * All the info about a connection.
     */
    private class ConnectionRecord {
        String pkg;
        Bundle rootHints;
        IMediaBrowserServiceCallbacks callbacks;
        Uri root;
        HashSet<Uri> subscriptions = new HashSet();
    }

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_ACTION = "android.media.browse.MediaBrowserService";

    private class ServiceBinder extends IMediaBrowserService.Stub {
        @Override
        public void connect(final String pkg, final Bundle rootHints,
                final IMediaBrowserServiceCallbacks callbacks) {

            final int uid = Binder.getCallingUid();
            if (!isValidPackage(pkg, uid)) {
                throw new IllegalArgumentException("Package/uid mismatch: uid=" + uid
                        + " package=" + pkg);
            }

            mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        final IBinder b = callbacks.asBinder();

                        // Clear out the old subscriptions.  We are getting new ones.
                        mConnections.remove(b);

                        final ConnectionRecord connection = new ConnectionRecord();
                        connection.pkg = pkg;
                        connection.rootHints = rootHints;
                        connection.callbacks = callbacks;

                        connection.root = MediaBrowserService.this.onGetRoot(pkg, uid, rootHints);

                        // If they didn't return something, don't allow this client.
                        if (connection.root == null) {
                            Log.i(TAG, "No root for client " + pkg + " from service "
                                    + getClass().getName());
                            try {
                                callbacks.onConnectFailed();
                            } catch (RemoteException ex) {
                                Log.w(TAG, "Calling onConnectFailed() failed. Ignoring. "
                                        + "pkg=" + pkg);
                            }
                        } else {
                            try {
                                mConnections.put(b, connection);
                                callbacks.onConnect(connection.root, mSession);
                            } catch (RemoteException ex) {
                                Log.w(TAG, "Calling onConnect() failed. Dropping client. "
                                        + "pkg=" + pkg);
                                mConnections.remove(b);
                            }
                        }
                    }
                });
        }

        @Override
        public void disconnect(final IMediaBrowserServiceCallbacks callbacks) {
            mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        final IBinder b = callbacks.asBinder();

                        // Clear out the old subscriptions.  We are getting new ones.
                        final ConnectionRecord old = mConnections.remove(b);
                        if (old != null) {
                            // TODO
                        }
                    }
                });
        }


        @Override
        public void addSubscription(final Uri uri, final IMediaBrowserServiceCallbacks callbacks) {
            mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        final IBinder b = callbacks.asBinder();

                        // Get the record for the connection
                        final ConnectionRecord connection = mConnections.get(b);
                        if (connection == null) {
                            Log.w(TAG, "addSubscription for callback that isn't registered uri="
                                + uri);
                            return;
                        }

                        MediaBrowserService.this.addSubscription(uri, connection);
                    }
                });
        }

        @Override
        public void removeSubscription(final Uri uri,
                final IMediaBrowserServiceCallbacks callbacks) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    final IBinder b = callbacks.asBinder();

                    ConnectionRecord connection = mConnections.get(b);
                    if (connection == null) {
                        Log.w(TAG, "removeSubscription for callback that isn't registered uri="
                                + uri);
                        return;
                    }
                    if (!connection.subscriptions.remove(uri)) {
                        Log.w(TAG, "removeSubscription called for " + uri
                                + " which is not subscribed");
                    }
                }
            });
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mBinder = new ServiceBinder();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_ACTION.equals(intent.getAction())) {
            return mBinder;
        }
        return null;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
    }

    /**
     * Called to get the root uri for browsing by a particular client.
     * <p>
     * The implementation should verify that the client package has
     * permission to access browse media information before returning
     * the root uri; it should return null if the client is not
     * allowed to access this information.
     * </p>
     *
     * @param clientPackageName The package name of the application
     * which is requesting access to browse media.
     * @param clientUid The uid of the application which is requesting
     * access to browse media.
     * @param rootHints An optional bundle of service-specific arguments to send
     * to the media browse service when connecting and retrieving the root uri
     * for browsing, or null if none.  The contents of this bundle may affect
     * the information returned when browsing.
     */
    public abstract @Nullable Uri onGetRoot(@NonNull String clientPackageName, int clientUid,
            @Nullable Bundle rootHints);

    /**
     * Called to get information about the children of a media item.
     *
     * @param parentUri The uri of the parent media item whose
     * children are to be queried.
     * @return The list of children, or null if the uri is invalid.
     */
    public abstract @Nullable List<MediaBrowserItem> onLoadChildren(@NonNull Uri parentUri);

    /**
     * Called to get the thumbnail of a particular media item.
     *
     * @param uri The uri of the media item.
     * @param width The requested width of the icon in dp.
     * @param height The requested height of the icon in dp.
     * @param density The requested density of the icon. This is the approximate density of the
     *              screen on which the icon will be displayed.  This density will be one of
     *              the android density buckets.
     * @return The file descriptor of the thumbnail, which may then be loaded
     *          using a bitmap factory, or null if the item does not have a thumbnail.
     */
    public abstract @Nullable Bitmap onGetThumbnail(@NonNull Uri uri,
            int width, int height, int density);

    /**
     * Call to set the media session.
     * <p>
     * This must be called before onCreate returns.
     *
     * @return The media session token, must not be null.
     */
    public void setSessionToken(MediaSession.Token token) {
        if (token == null) {
            throw new IllegalStateException(this.getClass().getName()
                    + ".onCreateSession() set invalid MediaSession.Token");
        }
        mSession = token;
    }

    /**
     * Gets the session token, or null if it has not yet been created
     * or if it has been destroyed.
     */
    public @Nullable MediaSession.Token getSessionToken() {
        return mSession;
    }

    /**
     * Notifies all connected media browsers that the content of
     * the browse service has changed in some way.
     * This will cause browsers to fetch subscribed content again.
     */
    public void notifyChange() {
        throw new RuntimeException("implement me");
    }

    /**
     * Notifies all connected media browsers that the children of
     * the specified Uri have changed in some way.
     * This will cause browsers to fetch subscribed content again.
     *
     * @param parentUri The uri of the parent media item whose
     * children changed.
     */
    public void notifyChildrenChanged(@NonNull Uri parentUri) {
        throw new RuntimeException("implement me");
    }

    /**
     * Return whether the given package is one of the ones that is owned by the uid.
     */
    private boolean isValidPackage(String pkg, int uid) {
        if (pkg == null) {
            return false;
        }
        final PackageManager pm = getPackageManager();
        final String[] packages = pm.getPackagesForUid(uid);
        final int N = packages.length;
        for (int i=0; i<N; i++) {
            if (packages[i].equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Save the subscription and if it is a new subscription send the results.
     */
    private void addSubscription(Uri uri, ConnectionRecord connection) {
        // Save the subscription
        final boolean added = connection.subscriptions.add(uri);

        // If this is a new subscription, send the results
        if (added) {
            performLoadChildren(uri, connection);
        }
    }

    /**
     * Call onLoadChildren and then send the results back to the connection.
     * <p>
     * Callers must make sure that this connection is still connected.
     * <p>
     * TODO: Think about caching and combining these calls.
     */
    private void performLoadChildren(Uri uri, ConnectionRecord connection) {
        final List<MediaBrowserItem> list = onLoadChildren(uri);
        if (list == null) {
            throw new IllegalStateException("onLoadChildren returned null for uri " + uri);
        }
        final ParceledListSlice<MediaBrowserItem> pls = new ParceledListSlice(list);
        try {
            connection.callbacks.onLoadChildren(uri, pls);
        } catch (RemoteException ex) {
            // The other side is in the process of crashing.
            Log.w(TAG, "Calling onLoadChildren() failed for uri=" + uri
                    + " package=" + connection.pkg);
        }
    }
}
