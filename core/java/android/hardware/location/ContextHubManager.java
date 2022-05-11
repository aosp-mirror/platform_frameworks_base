/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.hardware.location;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.ActivityThread;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A class that exposes the Context hubs on a device to applications.
 *
 * Please note that this class is not expected to be used by unbundled applications. Also, calling
 * applications are expected to have the ACCESS_CONTEXT_HUB permission to use this class.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.CONTEXTHUB_SERVICE)
@RequiresFeature(PackageManager.FEATURE_CONTEXT_HUB)
public final class ContextHubManager {
    private static final String TAG = "ContextHubManager";

    /**
     * An extra containing one of the {@code AUTHORIZATION_*} constants such as
     * {@link #AUTHORIZATION_GRANTED} describing the client's authorization state.
     */
    public static final String EXTRA_CLIENT_AUTHORIZATION_STATE =
            "android.hardware.location.extra.CLIENT_AUTHORIZATION_STATE";

    /**
     * An extra of type {@link ContextHubInfo} describing the source of the event.
     */
    public static final String EXTRA_CONTEXT_HUB_INFO =
            "android.hardware.location.extra.CONTEXT_HUB_INFO";

    /**
     * An extra of type {@link ContextHubManager.Event} describing the event type.
     */
    public static final String EXTRA_EVENT_TYPE = "android.hardware.location.extra.EVENT_TYPE";

    /**
     * An extra of type long describing the ID of the nanoapp an event is for.
     */
    public static final String EXTRA_NANOAPP_ID = "android.hardware.location.extra.NANOAPP_ID";

    /**
     * An extra of type int describing the nanoapp-specific abort code.
     */
    public static final String EXTRA_NANOAPP_ABORT_CODE =
            "android.hardware.location.extra.NANOAPP_ABORT_CODE";

    /**
     * An extra of type {@link NanoAppMessage} describing contents of a message from a nanoapp.
     */
    public static final String EXTRA_MESSAGE = "android.hardware.location.extra.MESSAGE";

    /**
     * Constants describing if a {@link ContextHubClient} and a {@link NanoApp} are authorized to
     * communicate.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "AUTHORIZATION_" }, value = {
        AUTHORIZATION_DENIED,
        AUTHORIZATION_DENIED_GRACE_PERIOD,
        AUTHORIZATION_GRANTED,
    })
    public @interface AuthorizationState { }

    /**
     * Indicates that the {@link ContextHubClient} can no longer communicate with a nanoapp. If the
     * {@link ContextHubClient} attempts to send messages to the nanoapp, it will continue to
     * receive this authorization state if the connection is still closed.
     */
    public static final int AUTHORIZATION_DENIED = 0;

    /**
     * Indicates the {@link ContextHubClient} will soon lose its authorization to communicate with a
     * nanoapp. After receiving this state event, the {@link ContextHubClient} has one minute to
     * perform any cleanup with the nanoapp such that the nanoapp is no longer performing work on
     * behalf of the {@link ContextHubClient}.
     */
    public static final int AUTHORIZATION_DENIED_GRACE_PERIOD = 1;

    /**
     * The {@link ContextHubClient} is authorized to communicate with the nanoapp.
     */
    public static final int AUTHORIZATION_GRANTED = 2;

    /**
     * Constants describing the type of events from a Context Hub, as defined in
     * {@link ContextHubClientCallback}.
     * {@hide}
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "EVENT_" }, value = {
        EVENT_NANOAPP_LOADED,
        EVENT_NANOAPP_UNLOADED,
        EVENT_NANOAPP_ENABLED,
        EVENT_NANOAPP_DISABLED,
        EVENT_NANOAPP_ABORTED,
        EVENT_NANOAPP_MESSAGE,
        EVENT_HUB_RESET,
        EVENT_CLIENT_AUTHORIZATION,
    })
    public @interface Event { }

    /**
     * An event describing that a nanoapp has been loaded. Contains the EXTRA_NANOAPP_ID extra.
     */
    public static final int EVENT_NANOAPP_LOADED = 0;

    /**
     * An event describing that a nanoapp has been unloaded. Contains the EXTRA_NANOAPP_ID extra.
     */
    public static final int EVENT_NANOAPP_UNLOADED = 1;

    /**
     * An event describing that a nanoapp has been enabled. Contains the EXTRA_NANOAPP_ID extra.
     */
    public static final int EVENT_NANOAPP_ENABLED = 2;

    /**
     * An event describing that a nanoapp has been disabled. Contains the EXTRA_NANOAPP_ID extra.
     */
    public static final int EVENT_NANOAPP_DISABLED = 3;

    /**
     * An event describing that a nanoapp has aborted. Contains the EXTRA_NANOAPP_ID and
     * EXTRA_NANOAPP_ABORT_CODE extras.
     */
    public static final int EVENT_NANOAPP_ABORTED = 4;

    /**
     * An event containing a message sent from a nanoapp. Contains the EXTRA_NANOAPP_ID and
     * EXTRA_NANOAPP_MESSAGE extras.
     */
    public static final int EVENT_NANOAPP_MESSAGE = 5;

    /**
     * An event describing that the Context Hub has reset.
     */
    public static final int EVENT_HUB_RESET = 6;

    /**
     * An event describing a client authorization state change. See
     * {@link ContextHubClientCallback#onClientAuthorizationChanged} for more details on when this
     * event will be sent. Contains the EXTRA_NANOAPP_ID and EXTRA_CLIENT_AUTHORIZATION_STATE
     * extras.
     */
    public static final int EVENT_CLIENT_AUTHORIZATION = 7;

    private final Looper mMainLooper;
    private final IContextHubService mService;
    private Callback mCallback;
    private Handler mCallbackHandler;

    /**
     * @deprecated Use {@code mCallback} instead.
     */
    @Deprecated
    private ICallback mLocalCallback;

    /**
     * An interface to receive asynchronous communication from the context hub.
     *
     * @deprecated Use the more refined {@link android.hardware.location.ContextHubClientCallback}
     *             instead for notification callbacks.
     */
    @Deprecated
    public abstract static class Callback {
        protected Callback() {}

        /**
         * Callback function called on message receipt from context hub.
         *
         * @param hubHandle Handle (system-wide unique identifier) of the hub of the message.
         * @param nanoAppHandle Handle (unique identifier) for app instance that sent the message.
         * @param message The context hub message.
         *
         * @see ContextHubMessage
         */
        public abstract void onMessageReceipt(
                int hubHandle,
                int nanoAppHandle,
                @NonNull ContextHubMessage message);
    }

    /**
     * @deprecated Use {@link Callback} instead.
     * @hide
     */
    @Deprecated
    public interface ICallback {
        /**
         * Callback function called on message receipt from context hub.
         *
         * @param hubHandle Handle (system-wide unique identifier) of the hub of the message.
         * @param nanoAppHandle Handle (unique identifier) for app instance that sent the message.
         * @param message The context hub message.
         *
         * @see ContextHubMessage
         */
        void onMessageReceipt(int hubHandle, int nanoAppHandle, ContextHubMessage message);
    }

    /**
     * Get a handle to all the context hubs in the system
     *
     * @return array of context hub handles
     *
     * @deprecated Use {@link #getContextHubs()} instead. The use of handles are deprecated in the
     *             new APIs.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public int[] getContextHubHandles() {
        try {
            return mService.getContextHubHandles();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get more information about a specific hub.
     *
     * @param hubHandle Handle (system-wide unique identifier) of a context hub.
     * @return ContextHubInfo Information about the requested context hub.
     *
     * @see ContextHubInfo
     *
     * @deprecated Use {@link #getContextHubs()} instead. The use of handles are deprecated in the
     *             new APIs.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public ContextHubInfo getContextHubInfo(int hubHandle) {
        try {
            return mService.getContextHubInfo(hubHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Load a nano app on a specified context hub.
     *
     * Note that loading is asynchronous.  When we return from this method,
     * the nano app (probably) hasn't loaded yet.  Assuming a return of 0
     * from this method, then the final success/failure for the load, along
     * with the "handle" for the nanoapp, is all delivered in a byte
     * string via a call to Callback.onMessageReceipt.
     *
     * TODO(b/30784270): Provide a better success/failure and "handle" delivery.
     *
     * @param hubHandle handle of context hub to load the app on.
     * @param app the nanoApp to load on the hub
     *
     * @return 0 if the command for loading was sent to the context hub;
     *         -1 otherwise
     *
     * @see NanoApp
     *
     * @deprecated Use {@link #loadNanoApp(ContextHubInfo, NanoAppBinary)} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public int loadNanoApp(int hubHandle, @NonNull NanoApp app) {
        try {
            return mService.loadNanoApp(hubHandle, app);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unload a specified nanoApp
     *
     * Note that unloading is asynchronous.  When we return from this method,
     * the nano app (probably) hasn't unloaded yet.  Assuming a return of 0
     * from this method, then the final success/failure for the unload is
     * delivered in a byte string via a call to Callback.onMessageReceipt.
     *
     * TODO(b/30784270): Provide a better success/failure delivery.
     *
     * @param nanoAppHandle handle of the nanoApp to unload
     *
     * @return 0 if the command for unloading was sent to the context hub;
     *         -1 otherwise
     *
     * @deprecated Use {@link #unloadNanoApp(ContextHubInfo, long)} instead.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public int unloadNanoApp(int nanoAppHandle) {
        try {
            return mService.unloadNanoApp(nanoAppHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * get information about the nano app instance
     *
     * NOTE: The returned NanoAppInstanceInfo does _not_ contain correct
     * information for several fields, specifically:
     * - getName()
     * - getPublisher()
     * - getNeededExecMemBytes()
     * - getNeededReadMemBytes()
     * - getNeededWriteMemBytes()
     *
     * For example, say you call loadNanoApp() with a NanoApp that has
     * getName() returning "My Name".  Later, if you call getNanoAppInstanceInfo
     * for that nanoapp, the returned NanoAppInstanceInfo's getName()
     * method will claim "Preloaded app, unknown", even though you would
     * have expected "My Name".  For now, as the user, you'll need to
     * separately track the above fields if they are of interest to you.
     *
     * TODO(b/30943489): Have the returned NanoAppInstanceInfo contain the
     *     correct information.
     *
     * @param nanoAppHandle handle of the nanoapp instance
     * @return NanoAppInstanceInfo the NanoAppInstanceInfo of the nanoapp, or null if the nanoapp
     *                             does not exist
     *
     * @see NanoAppInstanceInfo
     *
     * @deprecated Use {@link #queryNanoApps(ContextHubInfo)} instead to explicitly query the hub
     *             for loaded nanoapps.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @Nullable public NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppHandle) {
        try {
            return mService.getNanoAppInstanceInfo(nanoAppHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Find a specified nano app on the system
     *
     * @param hubHandle handle of hub to search for nano app
     * @param filter filter specifying the search criteria for app
     *
     * @see NanoAppFilter
     *
     * @return int[] Array of handles to any found nano apps
     *
     * @deprecated Use {@link #queryNanoApps(ContextHubInfo)} instead to explicitly query the hub
     *             for loaded nanoapps.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public int[] findNanoAppOnHub(int hubHandle, @NonNull NanoAppFilter filter) {
        try {
            return mService.findNanoAppOnHub(hubHandle, filter);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Send a message to a specific nano app instance on a context hub.
     *
     * Note that the return value of this method only speaks of success
     * up to the point of sending this to the Context Hub.  It is not
     * an assurance that the Context Hub successfully sent this message
     * on to the nanoapp.  If assurance is desired, a protocol should be
     * established between your code and the nanoapp, with the nanoapp
     * sending a confirmation message (which will be reported via
     * Callback.onMessageReceipt).
     *
     * @param hubHandle handle of the hub to send the message to
     * @param nanoAppHandle  handle of the nano app to send to
     * @param message Message to be sent
     *
     * @see ContextHubMessage
     *
     * @return int 0 on success, -1 otherwise
     *
     * @deprecated Use {@link android.hardware.location.ContextHubClient#sendMessageToNanoApp(
     *             NanoAppMessage)} instead, after creating a
     *             {@link android.hardware.location.ContextHubClient} with
     *             {@link #createClient(ContextHubInfo, ContextHubClientCallback, Executor)}
     *             or {@link #createClient(ContextHubInfo, ContextHubClientCallback)}.
     */
    @Deprecated
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public int sendMessage(int hubHandle, int nanoAppHandle, @NonNull ContextHubMessage message) {
        try {
            return mService.sendMessage(hubHandle, nanoAppHandle, message);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of ContextHubInfo objects describing the available Context Hubs.
     *
     * @return the list of ContextHubInfo objects
     *
     * @see ContextHubInfo
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public List<ContextHubInfo> getContextHubs() {
        try {
            return mService.getContextHubs();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Helper function to generate a stub for a non-query transaction callback.
     *
     * @param transaction the transaction to unblock when complete
     *
     * @return the callback
     *
     * @hide
     */
    private IContextHubTransactionCallback createTransactionCallback(
            ContextHubTransaction<Void> transaction) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoappList) {
                Log.e(TAG, "Received a query callback on a non-query request");
                transaction.setResponse(new ContextHubTransaction.Response<Void>(
                        ContextHubTransaction.RESULT_FAILED_SERVICE_INTERNAL_FAILURE, null));
            }

            @Override
            public void onTransactionComplete(int result) {
                transaction.setResponse(new ContextHubTransaction.Response<Void>(result, null));
            }
        };
    }

   /**
    * Helper function to generate a stub for a query transaction callback.
    *
    * @param transaction the transaction to unblock when complete
    *
    * @return the callback
    *
    * @hide
    */
    private IContextHubTransactionCallback createQueryCallback(
            ContextHubTransaction<List<NanoAppState>> transaction) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoappList) {
                transaction.setResponse(new ContextHubTransaction.Response<List<NanoAppState>>(
                        result, nanoappList));
            }

            @Override
            public void onTransactionComplete(int result) {
                Log.e(TAG, "Received a non-query callback on a query request");
                transaction.setResponse(new ContextHubTransaction.Response<List<NanoAppState>>(
                        ContextHubTransaction.RESULT_FAILED_SERVICE_INTERNAL_FAILURE, null));
            }
        };
    }

    /**
     * Loads a nanoapp at the specified Context Hub.
     *
     * After the nanoapp binary is successfully loaded at the specified hub, the nanoapp will be in
     * the enabled state.
     *
     * @param hubInfo the hub to load the nanoapp on
     * @param appBinary The app binary to load
     *
     * @return the ContextHubTransaction of the request
     *
     * @throws NullPointerException if hubInfo or NanoAppBinary is null
     *
     * @see NanoAppBinary
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public ContextHubTransaction<Void> loadNanoApp(
            @NonNull ContextHubInfo hubInfo, @NonNull NanoAppBinary appBinary) {
        Objects.requireNonNull(hubInfo, "ContextHubInfo cannot be null");
        Objects.requireNonNull(appBinary, "NanoAppBinary cannot be null");

        ContextHubTransaction<Void> transaction =
                new ContextHubTransaction<>(ContextHubTransaction.TYPE_LOAD_NANOAPP);
        IContextHubTransactionCallback callback = createTransactionCallback(transaction);

        try {
            mService.loadNanoAppOnHub(hubInfo.getId(), callback, appBinary);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        return transaction;
    }

    /**
     * Unloads a nanoapp at the specified Context Hub.
     *
     * @param hubInfo the hub to unload the nanoapp from
     * @param nanoAppId the app to unload
     *
     * @return the ContextHubTransaction of the request
     *
     * @throws NullPointerException if hubInfo is null
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public ContextHubTransaction<Void> unloadNanoApp(
            @NonNull ContextHubInfo hubInfo, long nanoAppId) {
        Objects.requireNonNull(hubInfo, "ContextHubInfo cannot be null");

        ContextHubTransaction<Void> transaction =
                new ContextHubTransaction<>(ContextHubTransaction.TYPE_UNLOAD_NANOAPP);
        IContextHubTransactionCallback callback = createTransactionCallback(transaction);

        try {
            mService.unloadNanoAppFromHub(hubInfo.getId(), callback, nanoAppId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        return transaction;
    }

    /**
     * Enables a nanoapp at the specified Context Hub.
     *
     * @param hubInfo the hub to enable the nanoapp on
     * @param nanoAppId the app to enable
     *
     * @return the ContextHubTransaction of the request
     *
     * @throws NullPointerException if hubInfo is null
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public ContextHubTransaction<Void> enableNanoApp(
            @NonNull ContextHubInfo hubInfo, long nanoAppId) {
        Objects.requireNonNull(hubInfo, "ContextHubInfo cannot be null");

        ContextHubTransaction<Void> transaction =
                new ContextHubTransaction<>(ContextHubTransaction.TYPE_ENABLE_NANOAPP);
        IContextHubTransactionCallback callback = createTransactionCallback(transaction);

        try {
            mService.enableNanoApp(hubInfo.getId(), callback, nanoAppId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        return transaction;
    }

    /**
     * Disables a nanoapp at the specified Context Hub.
     *
     * @param hubInfo the hub to disable the nanoapp on
     * @param nanoAppId the app to disable
     *
     * @return the ContextHubTransaction of the request
     *
     * @throws NullPointerException if hubInfo is null
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public ContextHubTransaction<Void> disableNanoApp(
            @NonNull ContextHubInfo hubInfo, long nanoAppId) {
        Objects.requireNonNull(hubInfo, "ContextHubInfo cannot be null");

        ContextHubTransaction<Void> transaction =
                new ContextHubTransaction<>(ContextHubTransaction.TYPE_DISABLE_NANOAPP);
        IContextHubTransactionCallback callback = createTransactionCallback(transaction);

        try {
            mService.disableNanoApp(hubInfo.getId(), callback, nanoAppId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        return transaction;
    }

    /**
     * Requests a query for nanoapps loaded at the specified Context Hub.
     *
     * @param hubInfo the hub to query a list of nanoapps from
     *
     * @return the ContextHubTransaction of the request
     *
     * @throws NullPointerException if hubInfo is null
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public ContextHubTransaction<List<NanoAppState>> queryNanoApps(
            @NonNull ContextHubInfo hubInfo) {
        Objects.requireNonNull(hubInfo, "ContextHubInfo cannot be null");

        ContextHubTransaction<List<NanoAppState>> transaction =
                new ContextHubTransaction<>(ContextHubTransaction.TYPE_QUERY_NANOAPPS);
        IContextHubTransactionCallback callback = createQueryCallback(transaction);

        try {
            mService.queryNanoApps(hubInfo.getId(), callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        return transaction;
    }

    /**
     * Set a callback to receive messages from the context hub
     *
     * @param callback Callback object
     *
     * @see Callback
     *
     * @return int 0 on success, -1 otherwise
     *
     * @deprecated Use {@link #createClient(ContextHubInfo, ContextHubClientCallback, Executor)}
     *             or {@link #createClient(ContextHubInfo, ContextHubClientCallback)} instead to
     *             register a {@link android.hardware.location.ContextHubClientCallback}.
     */
    @Deprecated
    @SuppressLint("RequiresPermission")
    public int registerCallback(@NonNull Callback callback) {
        return registerCallback(callback, null);
    }

    /**
     * @deprecated Use {@link #registerCallback(Callback)} instead.
     * @hide
     */
    @Deprecated
    public int registerCallback(ICallback callback) {
        if (mLocalCallback != null) {
            Log.w(TAG, "Max number of local callbacks reached!");
            return -1;
        }
        mLocalCallback = callback;
        return 0;
    }

    /**
     * Set a callback to receive messages from the context hub
     *
     * @param callback Callback object
     * @param handler Handler object, if null uses the Handler of the main Looper
     *
     * @see Callback
     *
     * @return int 0 on success, -1 otherwise
     *
     * @deprecated Use {@link #createClient(ContextHubInfo, ContextHubClientCallback, Executor)}
     *             or {@link #createClient(ContextHubInfo, ContextHubClientCallback)} instead to
     *             register a {@link android.hardware.location.ContextHubClientCallback}.
     */
    @Deprecated
    @SuppressLint("RequiresPermission")
    public int registerCallback(Callback callback, Handler handler) {
        synchronized(this) {
            if (mCallback != null) {
                Log.w(TAG, "Max number of callbacks reached!");
                return -1;
            }
            mCallback = callback;
            mCallbackHandler = (handler == null) ? new Handler(mMainLooper) : handler;
        }
        return 0;
    }

    /**
     * Creates an interface to the ContextHubClient to send down to the service.
     *
     * @param client the ContextHubClient object associated with this callback
     * @param callback the callback to invoke at the client process
     * @param executor the executor to invoke callbacks for this client
     *
     * @return the callback interface
     */
    private IContextHubClientCallback createClientCallback(
            ContextHubClient client, ContextHubClientCallback callback, Executor executor) {
        return new IContextHubClientCallback.Stub() {
            @Override
            public void onMessageFromNanoApp(NanoAppMessage message) {
                executor.execute(() -> callback.onMessageFromNanoApp(client, message));
            }

            @Override
            public void onHubReset() {
                executor.execute(() -> callback.onHubReset(client));
            }

            @Override
            public void onNanoAppAborted(long nanoAppId, int abortCode) {
                executor.execute(() -> callback.onNanoAppAborted(client, nanoAppId, abortCode));
            }

            @Override
            public void onNanoAppLoaded(long nanoAppId) {
                executor.execute(() -> callback.onNanoAppLoaded(client, nanoAppId));
            }

            @Override
            public void onNanoAppUnloaded(long nanoAppId) {
                executor.execute(() -> callback.onNanoAppUnloaded(client, nanoAppId));
            }

            @Override
            public void onNanoAppEnabled(long nanoAppId) {
                executor.execute(() -> callback.onNanoAppEnabled(client, nanoAppId));
            }

            @Override
            public void onNanoAppDisabled(long nanoAppId) {
                executor.execute(() -> callback.onNanoAppDisabled(client, nanoAppId));
            }

            @Override
            public void onClientAuthorizationChanged(
                    long nanoAppId, @ContextHubManager.AuthorizationState int authorization) {
                executor.execute(
                        () -> callback.onClientAuthorizationChanged(
                                client, nanoAppId, authorization));
            }
        };
    }

    /**
     * Creates and registers a client and its callback with the Context Hub Service.
     *
     * A client is registered with the Context Hub Service for a specified Context Hub. When the
     * registration succeeds, the client can send messages to nanoapps through the returned
     * {@link ContextHubClient} object, and receive notifications through the provided callback.
     *
     * @param context  the context of the application
     * @param hubInfo  the hub to attach this client to
     * @param executor the executor to invoke the callback
     * @param callback the notification callback to register
     * @return the registered client object
     *
     * @throws IllegalArgumentException if hubInfo does not represent a valid hub
     * @throws IllegalStateException    if there were too many registered clients at the service
     * @throws NullPointerException     if callback, hubInfo, or executor is null
     *
     * @see ContextHubClientCallback
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public ContextHubClient createClient(
            @Nullable Context context, @NonNull ContextHubInfo hubInfo,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ContextHubClientCallback callback) {
        Objects.requireNonNull(callback, "Callback cannot be null");
        Objects.requireNonNull(hubInfo, "ContextHubInfo cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");

        ContextHubClient client = new ContextHubClient(hubInfo, false /* persistent */);
        IContextHubClientCallback clientInterface = createClientCallback(
                client, callback, executor);

        String attributionTag = null;
        if (context != null) {
            attributionTag = context.getAttributionTag();
        }

        // Workaround for old APIs not providing a context
        String packageName;
        if (context != null) {
            packageName = context.getPackageName();
        } else {
            packageName = ActivityThread.currentPackageName();
        }

        IContextHubClient clientProxy;
        try {
            clientProxy = mService.createClient(
                    hubInfo.getId(), clientInterface, attributionTag, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        client.setClientProxy(clientProxy);
        return client;
    }


    /**
     * Equivalent to
     * {@link #createClient(ContextHubInfo, Executor, String, ContextHubClientCallback)}
     * with the {@link Context} being set to null.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public ContextHubClient createClient(
            @NonNull ContextHubInfo hubInfo, @NonNull ContextHubClientCallback callback,
            @NonNull @CallbackExecutor Executor executor) {
        return createClient(null /* context */, hubInfo, executor, callback);
    }

    /**
     * Equivalent to {@link #createClient(ContextHubInfo, ContextHubClientCallback, Executor)}
     * with the executor using the main thread's Looper.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public ContextHubClient createClient(
            @NonNull ContextHubInfo hubInfo, @NonNull ContextHubClientCallback callback) {
        return createClient(null /* context */, hubInfo, new HandlerExecutor(Handler.getMain()),
                            callback);
    }

    /**
     * Creates a ContextHubClient that will receive notifications based on Intent events.
     *
     * This method should be used instead of {@link #createClient(ContextHubInfo,
     * ContextHubClientCallback)} or {@link #createClient(ContextHubInfo, ContextHubClientCallback,
     * Executor)} if the caller wants to preserve the messaging endpoint of a ContextHubClient, even
     * after a process exits. If the PendingIntent with the provided nanoapp has already been
     * registered at the service, then the same ContextHubClient will be regenerated without
     * creating a new client connection at the service. Note that the PendingIntent, nanoapp, and
     * Context Hub must all match in identifying a previously registered ContextHubClient.
     * If a client is regenerated, the host endpoint identifier attached to messages sent to the
     * nanoapp remains consistent, even if the original process has exited.
     *
     * To avoid unintentionally spreading data from the Context Hub to external applications, it is
     * strongly recommended that the PendingIntent supplied to this API is an explicit intent.
     *
     * If registered successfully, intents will be delivered regarding events or messages from the
     * specified nanoapp from the attached Context Hub. The intent will have an extra
     * {@link ContextHubManager.EXTRA_CONTEXT_HUB_INFO} of type {@link ContextHubInfo}, which
     * describes the Context Hub the intent event was for. The intent will also have an extra
     * {@link ContextHubManager.EXTRA_EVENT_TYPE} of type {@link ContextHubManager.Event}, which
     * will contain the type of the event. See {@link ContextHubManager.Event} for description of
     * each event type, along with event-specific extra fields. The client can also use
     * {@link ContextHubIntentEvent.fromIntent(Intent)} to parse the Intent generated by the event.
     *
     * Intent events will be delivered until {@link ContextHubClient.close()} is called. Note that
     * the registration of this ContextHubClient at the Context Hub Service will be maintained until
     * {@link ContextHubClient.close()} is called. If {@link PendingIntent.cancel()} is called
     * on the provided PendingIntent, then the client will be automatically unregistered by the
     * service.
     *
     * Note that the {@link PendingIntent} supplied to this API must be mutable for Intent
     * notifications to work.
     *
     * @param context       the context of the application. If a PendingIntent client is recreated,
     * the latest state in the context will be used and old state will be discarded
     * @param hubInfo       the hub to attach this client to
     * @param pendingIntent the PendingIntent to register to the client
     * @param nanoAppId     the ID of the nanoapp that Intent events will be generated for
     * @return the registered client object
     *
     * @throws IllegalArgumentException if hubInfo does not represent a valid hub, or an immutable
     *                                  PendingIntent was supplied
     * @throws IllegalStateException    if there were too many registered clients at the service
     * @throws NullPointerException     if pendingIntent or hubInfo is null
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public ContextHubClient createClient(
            @Nullable Context context, @NonNull ContextHubInfo hubInfo,
            @NonNull PendingIntent pendingIntent, long nanoAppId) {
        Objects.requireNonNull(pendingIntent);
        Objects.requireNonNull(hubInfo);
        if (pendingIntent.isImmutable()) {
            throw new IllegalArgumentException("PendingIntent must be mutable");
        }

        ContextHubClient client = new ContextHubClient(hubInfo, true /* persistent */);

        String attributionTag = null;
        if (context != null) {
            attributionTag = context.getAttributionTag();
        }

        IContextHubClient clientProxy;
        try {
            clientProxy = mService.createPendingIntentClient(
                    hubInfo.getId(), pendingIntent, nanoAppId, attributionTag);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        client.setClientProxy(clientProxy);
        return client;
    }

    /**
     * Equivalent to {@link #createClient(ContextHubInfo, PendingIntent, long, String)}
     * with {@link Context} being set to null.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull public ContextHubClient createClient(
            @NonNull ContextHubInfo hubInfo, @NonNull PendingIntent pendingIntent, long nanoAppId) {
        return createClient(null /* context */, hubInfo, pendingIntent, nanoAppId);
    }

    /**
     * Unregister a callback for receive messages from the context hub.
     *
     * @see Callback
     *
     * @param callback method to deregister
     *
     * @return int 0 on success, -1 otherwise
     *
     * @deprecated Use {@link android.hardware.location.ContextHubClient#close()} to unregister
     *             a {@link android.hardware.location.ContextHubClientCallback}.
     */
    @SuppressLint("RequiresPermission")
    @Deprecated
    public int unregisterCallback(@NonNull Callback callback) {
      synchronized(this) {
          if (callback != mCallback) {
              Log.w(TAG, "Cannot recognize callback!");
              return -1;
          }

          mCallback = null;
          mCallbackHandler = null;
      }
      return 0;
    }

    /**
     * @deprecated Use {@link #unregisterCallback(Callback)} instead.
     * @hide
     */
    @Deprecated
    public synchronized int unregisterCallback(ICallback callback) {
        if (callback != mLocalCallback) {
            Log.w(TAG, "Cannot recognize local callback!");
            return -1;
        }
        mLocalCallback = null;
        return 0;
    }

    /**
     * Invokes the ContextHubManager.Callback callback registered with the ContextHubManager.
     *
     * @param hubId The ID of the Context Hub the message came from
     * @param nanoAppId The instance ID of the nanoapp the message came from
     * @param message The message to provide the callback
     */
    private synchronized void invokeOnMessageReceiptCallback(
            int hubId, int nanoAppId, ContextHubMessage message) {
        if (mCallback != null) {
            mCallback.onMessageReceipt(hubId, nanoAppId, message);
        }
    }

    private final IContextHubCallback.Stub mClientCallback = new IContextHubCallback.Stub() {
        @Override
        public void onMessageReceipt(
                final int hubId, final int nanoAppId, final ContextHubMessage message) {
            synchronized (ContextHubManager.this) {
                if (mCallback != null) {
                    mCallbackHandler.post(
                            () -> invokeOnMessageReceiptCallback(hubId, nanoAppId, message));
                } else if (mLocalCallback != null) {
                    // We always ensure that mCallback takes precedence, because mLocalCallback is
                    // only for internal compatibility
                    mLocalCallback.onMessageReceipt(hubId, nanoAppId, message);
                }
            }
        }
    };

    /** @throws ServiceNotFoundException
     * @hide */
    public ContextHubManager(Context context, Looper mainLooper) throws ServiceNotFoundException {
        mMainLooper = mainLooper;
        mService = IContextHubService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.CONTEXTHUB_SERVICE));
        try {
            mService.registerCallback(mClientCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
