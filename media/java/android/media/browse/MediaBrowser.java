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

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Browses media content offered by a link MediaBrowserService.
 * <p>
 * This object is not thread-safe. All calls should happen on the thread on which the browser
 * was constructed.
 * </p>
 */
public final class MediaBrowser {
    private static final String TAG = "MediaBrowser";
    private static final boolean DBG = false;

    private static final int CONNECT_STATE_DISCONNECTED = 0;
    private static final int CONNECT_STATE_CONNECTING = 1;
    private static final int CONNECT_STATE_CONNECTED = 2;
    private static final int CONNECT_STATE_SUSPENDED = 3;

    private final Context mContext;
    private final ComponentName mServiceComponent;
    private final ConnectionCallback mCallback;
    private final Bundle mRootHints;
    private final Handler mHandler = new Handler();
    private final ArrayMap<Uri,Subscription> mSubscriptions =
            new ArrayMap<Uri, MediaBrowser.Subscription>();

    private int mState = CONNECT_STATE_DISCONNECTED;
    private MediaServiceConnection mServiceConnection;
    private IMediaBrowserService mServiceBinder;
    private IMediaBrowserServiceCallbacks mServiceCallbacks;
    private Uri mRootUri;
    private MediaSession.Token mMediaSessionToken;

    /**
     * Creates a media browser for the specified media browse service.
     *
     * @param context The context.
     * @param serviceComponent The component name of the media browse service.
     * @param callback The connection callback.
     * @param rootHints An optional bundle of service-specific arguments to send
     * to the media browse service when connecting and retrieving the root uri
     * for browsing, or null if none.  The contents of this bundle may affect
     * the information returned when browsing.
     */
    public MediaBrowser(Context context, ComponentName serviceComponent,
            ConnectionCallback callback, Bundle rootHints) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (serviceComponent == null) {
            throw new IllegalArgumentException("service component must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("connection callback must not be null");
        }
        mContext = context;
        mServiceComponent = serviceComponent;
        mCallback = callback;
        mRootHints = rootHints;
    }

    /**
     * Connects to the media browse service.
     * <p>
     * The connection callback specified in the constructor will be invoked
     * when the connection completes or fails.
     * </p>
     */
    public void connect() {
        if (mState != CONNECT_STATE_DISCONNECTED) {
            throw new IllegalStateException("connect() called while not disconnected (state="
                    + getStateLabel(mState) + ")");
        }
        // TODO: remove this extra check.
        if (DBG) {
            if (mServiceConnection != null) {
                throw new RuntimeException("mServiceConnection should be null. Instead it is "
                        + mServiceConnection);
            }
        }
        if (mServiceBinder != null) {
            throw new RuntimeException("mServiceBinder should be null. Instead it is "
                    + mServiceBinder);
        }
        if (mServiceCallbacks != null) {
            throw new RuntimeException("mServiceCallbacks should be null. Instead it is "
                    + mServiceCallbacks);
        }

        mState = CONNECT_STATE_CONNECTING;

        final Intent intent = new Intent(MediaBrowserService.SERVICE_ACTION);
        intent.setComponent(mServiceComponent);

        final ServiceConnection thisConnection = mServiceConnection = new MediaServiceConnection();

        try {
            mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception ex) {
            Log.e(TAG, "Failed binding to service " + mServiceComponent);

            // Tell them that it didn't work.  We are already on the main thread,
            // but we don't want to do callbacks inside of connect().  So post it,
            // and then check that we are on the same ServiceConnection.  We know
            // we won't also get an onServiceConnected or onServiceDisconnected,
            // so we won't be doing double callbacks.
            mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Ensure that nobody else came in or tried to connect again.
                        if (thisConnection == mServiceConnection) {
                            forceCloseConnection();
                            mCallback.onConnectionFailed();
                        }
                    }
                });
        }

        if (DBG) {
            Log.d(TAG, "connect...");
            dump();
        }
    }

    /**
     * Disconnects from the media browse service.
     * @more
     * After this, no more callbacks will be received.
     */
    public void disconnect() {
        // It's ok to call this any state, because allowing this lets apps not have
        // to check isConnected() unnecessarily.  They won't appreciate the extra
        // assertions for this.  We do everything we can here to go back to a sane state.
        if (mServiceCallbacks != null) {
            try {
                mServiceBinder.disconnect(mServiceCallbacks);
            } catch (RemoteException ex) {
                // We are disconnecting anyway.  Log, just for posterity but it's not
                // a big problem.
                Log.w(TAG, "RemoteException during connect for " + mServiceComponent);
            }
        }
        forceCloseConnection();

        if (DBG) {
            Log.d(TAG, "disconnect...");
            dump();
        }
    }

    /**
     * Null out the variables and unbind from the service.  This doesn't include
     * calling disconnect on the service, because we only try to do that in the
     * clean shutdown cases.
     * <p>
     * Everywhere that calls this EXCEPT for disconnect() should follow it with
     * a call to mCallback.onConnectionFailed().  Disconnect doesn't do that callback
     * for a clean shutdown, but everywhere else is a dirty shutdown and should
     * notify the app.
     */
    private void forceCloseConnection() {
        if (mServiceConnection != null) {
            mContext.unbindService(mServiceConnection);
        }
        mState = CONNECT_STATE_DISCONNECTED;
        mServiceConnection = null;
        mServiceBinder = null;
        mServiceCallbacks = null;
        mRootUri = null;
        mMediaSessionToken = null;
    }

    /**
     * Returns whether the browser is connected to the service.
     */
    public boolean isConnected() {
        return mState == CONNECT_STATE_CONNECTED;
    }

    /**
     * Gets the root Uri.
     * <p>
     * Note that the root uri may become invalid or change when when the
     * browser is disconnected.
     * </p>
     *
     * @throws IllegalStateException if not connected.
      */
    public @NonNull Uri getRoot() {
        if (mState != CONNECT_STATE_CONNECTED) {
            throw new IllegalStateException("getSessionToken() called while not connected (state="
                    + getStateLabel(mState) + ")");
        }
        return mRootUri;
    }

    /**
     * Gets the media session token associated with the media browser.
     * <p>
     * Note that the session token may become invalid or change when when the
     * browser is disconnected.
     * </p>
     *
     * @return The session token for the browser, never null.
     *
     * @throws IllegalStateException if not connected.
     */
     public @NonNull MediaSession.Token getSessionToken() {
        if (mState != CONNECT_STATE_CONNECTED) {
            throw new IllegalStateException("getSessionToken() called while not connected (state="
                    + mState + ")");
        }
        return mMediaSessionToken;
    }

    /**
     * Queries for information about the media items that are contained within
     * the specified Uri and subscribes to receive updates when they change.
     * <p>
     * The list of subscriptions is maintained even when not connected and is
     * restored after reconnection.  It is ok to subscribe while not connected
     * but the results will not be returned until the connection completes.
     * </p><p>
     * If the uri is already subscribed with a different callback then the new
     * callback will replace the previous one.
     * </p>
     *
     * @param parentUri The uri of the parent media item whose list of children
     * will be subscribed.
     * @param callback The callback to receive the list of children.
     */
    public void subscribe(@NonNull Uri parentUri, @NonNull SubscriptionCallback callback) {
        // Check arguments.
        if (parentUri == null) {
            throw new IllegalArgumentException("parentUri is null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback is null");
        }

        // Update or create the subscription.
        Subscription sub = mSubscriptions.get(parentUri);
        boolean newSubscription = sub == null;
        if (newSubscription) {
            sub = new Subscription(parentUri);
            mSubscriptions.put(parentUri, sub);
        }
        sub.callback = callback;

        // If we are connected, tell the service that we are watching.  If we aren't
        // connected, the service will be told when we connect.
        if (mState == CONNECT_STATE_CONNECTED && newSubscription) {
            try {
                mServiceBinder.addSubscription(parentUri, mServiceCallbacks);
            } catch (RemoteException ex) {
                // Process is crashing.  We will disconnect, and upon reconnect we will
                // automatically reregister. So nothing to do here.
                Log.d(TAG, "addSubscription failed with RemoteException parentUri=" + parentUri);
            }
        }
    }

    /**
     * Unsubscribes for changes to the children of the specified Uri.
     * <p>
     * The query callback will no longer be invoked for results associated with
     * this Uri once this method returns.
     * </p>
     *
     * @param parentUri The uri of the parent media item whose list of children
     * will be unsubscribed.
     */
    public void unsubscribe(@NonNull Uri parentUri) {
        // Check arguments.
        if (parentUri == null) {
            throw new IllegalArgumentException("parentUri is null");
        }

        // Remove from our list.
        final Subscription sub = mSubscriptions.remove(parentUri);

        // Tell the service if necessary.
        if (mState == CONNECT_STATE_CONNECTED && sub != null) {
            try {
                mServiceBinder.removeSubscription(parentUri, mServiceCallbacks);
            } catch (RemoteException ex) {
                // Process is crashing.  We will disconnect, and upon reconnect we will
                // automatically reregister. So nothing to do here.
                Log.d(TAG, "removeSubscription failed with RemoteException parentUri=" + parentUri);
            }
        }
    }

    /**
     * Loads the thumbnail of a media item.
     *
     * @param uri The uri of the media item.
     * @param width The preferred width of the icon in dp.
     * @param height The preferred width of the icon in dp.
     * @param density The preferred density of the icon. Must be one of the android
     *      density buckets.
     * @param callback The callback to receive the thumbnail.
     *
     * @throws IllegalStateException if not connected. TODO: Is this restriction necessary?
     */
    public void loadThumbnail(@NonNull Uri uri, int width, int height, int density,
            @NonNull ThumbnailCallback callback) {
        throw new RuntimeException("implement me");
    }

    /**
     * For debugging.
     */
    private static String getStateLabel(int state) {
        switch (state) {
            case CONNECT_STATE_DISCONNECTED:
                return "CONNECT_STATE_DISCONNECTED";
            case CONNECT_STATE_CONNECTING:
                return "CONNECT_STATE_CONNECTING";
            case CONNECT_STATE_CONNECTED:
                return "CONNECT_STATE_CONNECTED";
            case CONNECT_STATE_SUSPENDED:
                return "CONNECT_STATE_SUSPENDED";
            default:
                return "UNKNOWN/" + state;
        }
    }

    private final void onServiceConnected(final IMediaBrowserServiceCallbacks callback,
            final Uri root, final MediaSession.Token session) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // Check to make sure there hasn't been a disconnect or a different
                // ServiceConnection.
                if (!isCurrent(callback, "onConnect")) {
                    return;
                }
                // Don't allow them to call us twice.
                if (mState != CONNECT_STATE_CONNECTING) {
                    Log.w(TAG, "onConnect from service while mState="
                            + getStateLabel(mState) + "... ignoring");
                    return;
                }
                mRootUri = root;
                mMediaSessionToken = session;
                mState = CONNECT_STATE_CONNECTED;

                if (DBG) {
                    Log.d(TAG, "ServiceCallbacks.onConnect...");
                    dump();
                }
                mCallback.onConnected();

                // we may receive some subscriptions before we are connected, so re-subscribe
                // everything now
                for (Uri uri : mSubscriptions.keySet()) {
                    try {
                        mServiceBinder.addSubscription(uri, mServiceCallbacks);
                    } catch (RemoteException ex) {
                        // Process is crashing.  We will disconnect, and upon reconnect we will
                        // automatically reregister. So nothing to do here.
                        Log.d(TAG, "addSubscription failed with RemoteException parentUri=" + uri);
                    }
                }

            }
        });
    }

    private final void onConnectionFailed(final IMediaBrowserServiceCallbacks callback) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, "onConnectFailed for " + mServiceComponent);

                // Check to make sure there hasn't been a disconnect or a different
                // ServiceConnection.
                if (!isCurrent(callback, "onConnectFailed")) {
                    return;
                }
                // Don't allow them to call us twice.
                if (mState != CONNECT_STATE_CONNECTING) {
                    Log.w(TAG, "onConnect from service while mState="
                            + getStateLabel(mState) + "... ignoring");
                    return;
                }

                // Clean up
                forceCloseConnection();

                // Tell the app.
                mCallback.onConnectionFailed();
            }
        });
    }

    private final void onLoadChildren(final IMediaBrowserServiceCallbacks callback, final Uri uri,
            final ParceledListSlice list) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // Check that there hasn't been a disconnect or a different
                // ServiceConnection.
                if (!isCurrent(callback, "onLoadChildren")) {
                    return;
                }

                List<MediaBrowserItem> data = list.getList();
                if (DBG) {
                    Log.d(TAG, "onLoadChildren for " + mServiceComponent + " uri=" + uri);
                }
                if (data == null) {
                    data = Collections.emptyList();
                }

                // Check that the subscription is still subscribed.
                final Subscription subscription = mSubscriptions.get(uri);
                if (subscription == null) {
                    if (DBG) {
                        Log.d(TAG, "onLoadChildren for uri that isn't subscribed uri="
                                + uri);
                    }
                    return;
                }

                // Tell the app.
                subscription.callback.onChildrenLoaded(uri, data);
            }
        });
    }


    /**
     * Return true if {@code callback} is the current ServiceCallbacks.  Also logs if it's not.
     */
    private boolean isCurrent(IMediaBrowserServiceCallbacks callback, String funcName) {
        if (mServiceCallbacks != callback) {
            if (mState != CONNECT_STATE_DISCONNECTED) {
                Log.i(TAG, funcName + " for " + mServiceComponent + " with mServiceConnection="
                        + mServiceCallbacks + " this=" + this);
            }
            return false;
        }
        return true;
    }

    private ServiceCallbacks getNewServiceCallbacks() {
        return new ServiceCallbacks(this);
    }

    /**
     * Log internal state.
     * @hide
     */
    void dump() {
        Log.d(TAG, "MediaBrowser...");
        Log.d(TAG, "  mServiceComponent=" + mServiceComponent);
        Log.d(TAG, "  mCallback=" + mCallback);
        Log.d(TAG, "  mRootHints=" + mRootHints);
        Log.d(TAG, "  mState=" + getStateLabel(mState));
        Log.d(TAG, "  mServiceConnection=" + mServiceConnection);
        Log.d(TAG, "  mServiceBinder=" + mServiceBinder);
        Log.d(TAG, "  mServiceCallbacks=" + mServiceCallbacks);
        Log.d(TAG, "  mRootUri=" + mRootUri);
        Log.d(TAG, "  mMediaSessionToken=" + mMediaSessionToken);
    }


    /**
     * Callbacks for connection related events.
     */
    public static class ConnectionCallback {
        /**
         * Invoked after {@link MediaBrowser#connect()} when the request has successfully completed.
         */
        public void onConnected() {
        }

        /**
         * Invoked when the client is disconnected from the media browser.
         */
        public void onConnectionSuspended() {
        }

        /**
         * Invoked when the connection to the media browser failed.
         */
        public void onConnectionFailed() {
        }
    }

    /**
     * Callbacks for subscription related events.
     */
    public static abstract class SubscriptionCallback {
        /**
         * Called when the list of children is loaded or updated.
         */
        public void onChildrenLoaded(@NonNull Uri parentUri,
                                     @NonNull List<MediaBrowserItem> children) {
        }

        /**
         * Called when the Uri doesn't exist or other errors in subscribing.
         * <p>
         * If this is called, the subscription remains until {@link MediaBrowser#unsubscribe}
         * called, because some errors may heal themselves.
         * </p>
         */
        public void onError(@NonNull Uri uri) {
        }
    }

    /**
     * Callbacks for thumbnail loading.
     */
    public static abstract class ThumbnailCallback {
        /**
         * Called when the thumbnail is loaded.
         */
        public void onThumbnailLoaded(@NonNull Uri uri, @NonNull Bitmap bitmap) {
        }

        /**
         * Called when the Uri doesn’t exist or the bitmap cannot be loaded.
         */
        public void onError(@NonNull Uri uri) {
        }
    }

    /**
     * ServiceConnection to the other app.
     */
    private class MediaServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (DBG) {
                Log.d(TAG, "MediaServiceConnection.onServiceConnected name=" + name
                        + " binder=" + binder);
                dump();
            }

            // Make sure we are still the current connection, and that they haven't called
            // disconnect().
            if (!isCurrent("onServiceConnected")) {
                return;
            }

            // Save their binder
            mServiceBinder = IMediaBrowserService.Stub.asInterface(binder);

            // We make a new mServiceCallbacks each time we connect so that we can drop
            // responses from previous connections.
            mServiceCallbacks = getNewServiceCallbacks();

            // Call connect, which is async. When we get a response from that we will
            // say that we're connected.
            try {
                if (DBG) {
                    Log.d(TAG, "ServiceCallbacks.onConnect...");
                    dump();
                }
                mServiceBinder.connect(mContext.getPackageName(), mRootHints, mServiceCallbacks);
            } catch (RemoteException ex) {
                // Connect failed, which isn't good. But the auto-reconnect on the service
                // will take over and we will come back.  We will also get the
                // onServiceDisconnected, which has all the cleanup code.  So let that do it.
                Log.w(TAG, "RemoteException during connect for " + mServiceComponent);
                if (DBG) {
                    Log.d(TAG, "ServiceCallbacks.onConnect...");
                    dump();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DBG) {
                Log.d(TAG, "MediaServiceConnection.onServiceDisconnected name=" + name
                        + " this=" + this + " mServiceConnection=" + mServiceConnection);
                dump();
            }

            // Make sure we are still the current connection, and that they haven't called
            // disconnect().
            if (!isCurrent("onServiceDisconnected")) {
                return;
            }

            // Clear out what we set in onServiceConnected
            mServiceBinder = null;
            mServiceCallbacks = null;

            // And tell the app that it's suspended.
            mState = CONNECT_STATE_SUSPENDED;
            mCallback.onConnectionSuspended();
        }

        /**
         * Return true if this is the current ServiceConnection.  Also logs if it's not.
         */
        private boolean isCurrent(String funcName) {
            if (mServiceConnection != this) {
                if (mState != CONNECT_STATE_DISCONNECTED) {
                    // Check mState, because otherwise this log is noisy.
                    Log.i(TAG, funcName + " for " + mServiceComponent + " with mServiceConnection="
                            + mServiceConnection + " this=" + this);
                }
                return false;
            }
            return true;
        }
    };

    /**
     * Callbacks from the service.
     */
    private static class ServiceCallbacks extends IMediaBrowserServiceCallbacks.Stub {
        private WeakReference<MediaBrowser> mMediaBrowser;

        public ServiceCallbacks(MediaBrowser mediaBrowser) {
            mMediaBrowser = new WeakReference<MediaBrowser>(mediaBrowser);
        }

        /**
         * The other side has acknowledged our connection.  The parameters to this function
         * are the initial data as requested.
         */
        @Override
        public void onConnect(final Uri root, final MediaSession.Token session) {
            MediaBrowser mediaBrowser = mMediaBrowser.get();
            if (mediaBrowser != null) {
                mediaBrowser.onServiceConnected(this, root, session);
            }
        }

        /**
         * The other side does not like us.  Tell the app via onConnectionFailed.
         */
        @Override
        public void onConnectFailed() {
            MediaBrowser mediaBrowser = mMediaBrowser.get();
            if (mediaBrowser != null) {
                mediaBrowser.onConnectionFailed(this);
            }
        }

        @Override
        public void onLoadChildren(final Uri uri, final ParceledListSlice list) {
            MediaBrowser mediaBrowser = mMediaBrowser.get();
            if (mediaBrowser != null) {
                mediaBrowser.onLoadChildren(this, uri, list);
            }
        }
    }

    private static class Subscription {
        final Uri uri;
        SubscriptionCallback callback;

        Subscription(Uri u) {
            this.uri = u;
        }
    }
}
