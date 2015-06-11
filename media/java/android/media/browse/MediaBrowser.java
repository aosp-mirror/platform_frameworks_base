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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ParceledListSlice;
import android.media.MediaDescription;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.service.media.MediaBrowserService;
import android.service.media.IMediaBrowserService;
import android.service.media.IMediaBrowserServiceCallbacks;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Collections;
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
    private final ArrayMap<String,Subscription> mSubscriptions =
            new ArrayMap<String, MediaBrowser.Subscription>();

    private int mState = CONNECT_STATE_DISCONNECTED;
    private MediaServiceConnection mServiceConnection;
    private IMediaBrowserService mServiceBinder;
    private IMediaBrowserServiceCallbacks mServiceCallbacks;
    private String mRootId;
    private MediaSession.Token mMediaSessionToken;
    private Bundle mExtras;

    /**
     * Creates a media browser for the specified media browse service.
     *
     * @param context The context.
     * @param serviceComponent The component name of the media browse service.
     * @param callback The connection callback.
     * @param rootHints An optional bundle of service-specific arguments to send
     * to the media browse service when connecting and retrieving the root id
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

        final Intent intent = new Intent(MediaBrowserService.SERVICE_INTERFACE);
        intent.setComponent(mServiceComponent);

        final ServiceConnection thisConnection = mServiceConnection = new MediaServiceConnection();

        boolean bound = false;
        try {
            bound = mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception ex) {
            Log.e(TAG, "Failed binding to service " + mServiceComponent);
        }

        if (!bound) {
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
        mRootId = null;
        mMediaSessionToken = null;
    }

    /**
     * Returns whether the browser is connected to the service.
     */
    public boolean isConnected() {
        return mState == CONNECT_STATE_CONNECTED;
    }

    /**
     * Gets the service component that the media browser is connected to.
     */
    public @NonNull ComponentName getServiceComponent() {
        if (!isConnected()) {
            throw new IllegalStateException("getServiceComponent() called while not connected" +
                    " (state=" + mState + ")");
        }
        return mServiceComponent;
    }

    /**
     * Gets the root id.
     * <p>
     * Note that the root id may become invalid or change when when the
     * browser is disconnected.
     * </p>
     *
     * @throws IllegalStateException if not connected.
     */
    public @NonNull String getRoot() {
        if (!isConnected()) {
            throw new IllegalStateException("getSessionToken() called while not connected (state="
                    + getStateLabel(mState) + ")");
        }
        return mRootId;
    }

    /**
     * Gets any extras for the media service.
     *
     * @throws IllegalStateException if not connected.
     */
    public @Nullable Bundle getExtras() {
        if (!isConnected()) {
            throw new IllegalStateException("getExtras() called while not connected (state="
                    + getStateLabel(mState) + ")");
        }
        return mExtras;
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
        if (!isConnected()) {
            throw new IllegalStateException("getSessionToken() called while not connected (state="
                    + mState + ")");
        }
        return mMediaSessionToken;
    }

    /**
     * Queries for information about the media items that are contained within
     * the specified id and subscribes to receive updates when they change.
     * <p>
     * The list of subscriptions is maintained even when not connected and is
     * restored after reconnection. It is ok to subscribe while not connected
     * but the results will not be returned until the connection completes.
     * </p>
     * <p>
     * If the id is already subscribed with a different callback then the new
     * callback will replace the previous one and the child data will be
     * reloaded.
     * </p>
     *
     * @param parentId The id of the parent media item whose list of children
     *            will be subscribed.
     * @param callback The callback to receive the list of children.
     */
    public void subscribe(@NonNull String parentId, @NonNull SubscriptionCallback callback) {
        // Check arguments.
        if (parentId == null) {
            throw new IllegalArgumentException("parentId is null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback is null");
        }

        // Update or create the subscription.
        Subscription sub = mSubscriptions.get(parentId);
        boolean newSubscription = sub == null;
        if (newSubscription) {
            sub = new Subscription(parentId);
            mSubscriptions.put(parentId, sub);
        }
        sub.callback = callback;

        // If we are connected, tell the service that we are watching.  If we aren't
        // connected, the service will be told when we connect.
        if (mState == CONNECT_STATE_CONNECTED) {
            try {
                mServiceBinder.addSubscription(parentId, mServiceCallbacks);
            } catch (RemoteException ex) {
                // Process is crashing.  We will disconnect, and upon reconnect we will
                // automatically reregister. So nothing to do here.
                Log.d(TAG, "addSubscription failed with RemoteException parentId=" + parentId);
            }
        }
    }

    /**
     * Unsubscribes for changes to the children of the specified media id.
     * <p>
     * The query callback will no longer be invoked for results associated with
     * this id once this method returns.
     * </p>
     *
     * @param parentId The id of the parent media item whose list of children
     * will be unsubscribed.
     */
    public void unsubscribe(@NonNull String parentId) {
        // Check arguments.
        if (TextUtils.isEmpty(parentId)) {
            throw new IllegalArgumentException("parentId is empty.");
        }

        // Remove from our list.
        final Subscription sub = mSubscriptions.remove(parentId);

        // Tell the service if necessary.
        if (mState == CONNECT_STATE_CONNECTED && sub != null) {
            try {
                mServiceBinder.removeSubscription(parentId, mServiceCallbacks);
            } catch (RemoteException ex) {
                // Process is crashing.  We will disconnect, and upon reconnect we will
                // automatically reregister. So nothing to do here.
                Log.d(TAG, "removeSubscription failed with RemoteException parentId=" + parentId);
            }
        }
    }

    /**
     * Retrieves a specific {@link MediaItem} from the connected service. Not
     * all services may support this, so falling back to subscribing to the
     * parent's id should be used when unavailable.
     *
     * @param mediaId The id of the item to retrieve.
     * @param cb The callback to receive the result on.
     */
    public void getItem(final @NonNull String mediaId, @NonNull final ItemCallback cb) {
        if (TextUtils.isEmpty(mediaId)) {
            throw new IllegalArgumentException("mediaId is empty.");
        }
        if (cb == null) {
            throw new IllegalArgumentException("cb is null.");
        }
        if (mState != CONNECT_STATE_CONNECTED) {
            Log.i(TAG, "Not connected, unable to retrieve the MediaItem.");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    cb.onError(mediaId);
                }
            });
            return;
        }
        ResultReceiver receiver = new ResultReceiver(mHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode != 0 || resultData == null
                        || !resultData.containsKey(MediaBrowserService.KEY_MEDIA_ITEM)) {
                    cb.onError(mediaId);
                    return;
                }
                Parcelable item = resultData.getParcelable(MediaBrowserService.KEY_MEDIA_ITEM);
                if (!(item instanceof MediaItem)) {
                    cb.onError(mediaId);
                    return;
                }
                cb.onItemLoaded((MediaItem)item);
            }
        };
        try {
            mServiceBinder.getMediaItem(mediaId, receiver);
        } catch (RemoteException e) {
            Log.i(TAG, "Remote error getting media item.");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    cb.onError(mediaId);
                }
            });
        }
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
            final String root, final MediaSession.Token session, final Bundle extra) {
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
                mRootId = root;
                mMediaSessionToken = session;
                mExtras = extra;
                mState = CONNECT_STATE_CONNECTED;

                if (DBG) {
                    Log.d(TAG, "ServiceCallbacks.onConnect...");
                    dump();
                }
                mCallback.onConnected();

                // we may receive some subscriptions before we are connected, so re-subscribe
                // everything now
                for (String id : mSubscriptions.keySet()) {
                    try {
                        mServiceBinder.addSubscription(id, mServiceCallbacks);
                    } catch (RemoteException ex) {
                        // Process is crashing.  We will disconnect, and upon reconnect we will
                        // automatically reregister. So nothing to do here.
                        Log.d(TAG, "addSubscription failed with RemoteException parentId=" + id);
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

    private final void onLoadChildren(final IMediaBrowserServiceCallbacks callback,
            final String parentId, final ParceledListSlice list) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // Check that there hasn't been a disconnect or a different
                // ServiceConnection.
                if (!isCurrent(callback, "onLoadChildren")) {
                    return;
                }

                List<MediaItem> data = list.getList();
                if (DBG) {
                    Log.d(TAG, "onLoadChildren for " + mServiceComponent + " id=" + parentId);
                }
                if (data == null) {
                    data = Collections.emptyList();
                }

                // Check that the subscription is still subscribed.
                final Subscription subscription = mSubscriptions.get(parentId);
                if (subscription == null) {
                    if (DBG) {
                        Log.d(TAG, "onLoadChildren for id that isn't subscribed id="
                                + parentId);
                    }
                    return;
                }

                // Tell the app.
                subscription.callback.onChildrenLoaded(parentId, data);
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
        Log.d(TAG, "  mRootId=" + mRootId);
        Log.d(TAG, "  mMediaSessionToken=" + mMediaSessionToken);
    }

    public static class MediaItem implements Parcelable {
        private final int mFlags;
        private final MediaDescription mDescription;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag=true, value = { FLAG_BROWSABLE, FLAG_PLAYABLE })
        public @interface Flags { }

        /**
         * Flag: Indicates that the item has children of its own.
         */
        public static final int FLAG_BROWSABLE = 1 << 0;

        /**
         * Flag: Indicates that the item is playable.
         * <p>
         * The id of this item may be passed to
         * {@link MediaController.TransportControls#playFromMediaId(String, Bundle)}
         * to start playing it.
         * </p>
         */
        public static final int FLAG_PLAYABLE = 1 << 1;

        /**
         * Create a new MediaItem for use in browsing media.
         * @param description The description of the media, which must include a
         *            media id.
         * @param flags The flags for this item.
         */
        public MediaItem(@NonNull MediaDescription description, @Flags int flags) {
            if (description == null) {
                throw new IllegalArgumentException("description cannot be null");
            }
            if (TextUtils.isEmpty(description.getMediaId())) {
                throw new IllegalArgumentException("description must have a non-empty media id");
            }
            mFlags = flags;
            mDescription = description;
        }

        /**
         * Private constructor.
         */
        private MediaItem(Parcel in) {
            mFlags = in.readInt();
            mDescription = MediaDescription.CREATOR.createFromParcel(in);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(mFlags);
            mDescription.writeToParcel(out, flags);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("MediaItem{");
            sb.append("mFlags=").append(mFlags);
            sb.append(", mDescription=").append(mDescription);
            sb.append('}');
            return sb.toString();
        }

        public static final Parcelable.Creator<MediaItem> CREATOR =
                new Parcelable.Creator<MediaItem>() {
                    @Override
                    public MediaItem createFromParcel(Parcel in) {
                        return new MediaItem(in);
                    }

                    @Override
                    public MediaItem[] newArray(int size) {
                        return new MediaItem[size];
                    }
                };

        /**
         * Gets the flags of the item.
         */
        public @Flags int getFlags() {
            return mFlags;
        }

        /**
         * Returns whether this item is browsable.
         * @see #FLAG_BROWSABLE
         */
        public boolean isBrowsable() {
            return (mFlags & FLAG_BROWSABLE) != 0;
        }

        /**
         * Returns whether this item is playable.
         * @see #FLAG_PLAYABLE
         */
        public boolean isPlayable() {
            return (mFlags & FLAG_PLAYABLE) != 0;
        }

        /**
         * Returns the description of the media.
         */
        public @NonNull MediaDescription getDescription() {
            return mDescription;
        }

        /**
         * Returns the media id for this item.
         */
        public @NonNull String getMediaId() {
            return mDescription.getMediaId();
        }
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
         *
         * @param parentId The media id of the parent media item.
         * @param children The children which were loaded.
         */
        public void onChildrenLoaded(@NonNull String parentId,
                                     @NonNull List<MediaItem> children) {
        }

        /**
         * Called when the id doesn't exist or other errors in subscribing.
         * <p>
         * If this is called, the subscription remains until {@link MediaBrowser#unsubscribe}
         * called, because some errors may heal themselves.
         * </p>
         *
         * @param parentId The media id of the parent media item whose children could
         * not be loaded.
         */
        public void onError(@NonNull String parentId) {
        }
    }

    /**
     * Callback for receiving the result of {@link #getItem}.
     */
    public static abstract class ItemCallback {
        /**
         * Called when the item has been returned by the browser service.
         *
         * @param item The item that was returned or null if it doesn't exist.
         */
        public void onItemLoaded(MediaItem item) {
        }

        /**
         * Called when the item doesn't exist or there was an error retrieving it.
         *
         * @param itemId The media id of the media item which could not be loaded.
         */
        public void onError(@NonNull String itemId) {
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
            mState = CONNECT_STATE_CONNECTING;

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
    }

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
        public void onConnect(final String root, final MediaSession.Token session,
                final Bundle extras) {
            MediaBrowser mediaBrowser = mMediaBrowser.get();
            if (mediaBrowser != null) {
                mediaBrowser.onServiceConnected(this, root, session, extras);
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
        public void onLoadChildren(final String parentId, final ParceledListSlice list) {
            MediaBrowser mediaBrowser = mMediaBrowser.get();
            if (mediaBrowser != null) {
                mediaBrowser.onLoadChildren(this, parentId, list);
            }
        }
    }

    private static class Subscription {
        final String id;
        SubscriptionCallback callback;

        Subscription(String id) {
            this.id = id;
        }
    }
}
