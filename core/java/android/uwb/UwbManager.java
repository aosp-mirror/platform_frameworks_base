/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.Manifest.permission;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextParams;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * This class provides a way to perform Ultra Wideband (UWB) operations such as querying the
 * device's capabilities and determining the distance and angle between the local device and a
 * remote device.
 *
 * <p>To get a {@link UwbManager}, call the <code>Context.getSystemService(UwbManager.class)</code>.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.UWB_SERVICE)
public final class UwbManager {
    private static final String SERVICE_NAME = "uwb";

    private final Context mContext;
    private final IUwbAdapter mUwbAdapter;
    private final AdapterStateListener mAdapterStateListener;
    private final RangingManager mRangingManager;

    /**
     * Interface for receiving UWB adapter state changes
     */
    public interface AdapterStateCallback {
        /**
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                STATE_CHANGED_REASON_SESSION_STARTED,
                STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED,
                STATE_CHANGED_REASON_SYSTEM_POLICY,
                STATE_CHANGED_REASON_SYSTEM_BOOT,
                STATE_CHANGED_REASON_ERROR_UNKNOWN})
        @interface StateChangedReason {}

        /**
         * Indicates that the state change was due to opening of first UWB session
         */
        int STATE_CHANGED_REASON_SESSION_STARTED = 0;

        /**
         * Indicates that the state change was due to closure of all UWB sessions
         */
        int STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED = 1;

        /**
         * Indicates that the state change was due to changes in system policy
         */
        int STATE_CHANGED_REASON_SYSTEM_POLICY = 2;

        /**
         * Indicates that the current state is due to a system boot
         */
        int STATE_CHANGED_REASON_SYSTEM_BOOT = 3;

        /**
         * Indicates that the state change was due to some unknown error
         */
        int STATE_CHANGED_REASON_ERROR_UNKNOWN = 4;

        /**
         * Invoked when underlying UWB adapter's state is changed
         * <p>Invoked with the adapter's current state after registering an
         * {@link AdapterStateCallback} using
         * {@link UwbManager#registerAdapterStateCallback(Executor, AdapterStateCallback)}.
         *
         * <p>Possible values for the state to change are
         * {@link #STATE_CHANGED_REASON_SESSION_STARTED},
         * {@link #STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED},
         * {@link #STATE_CHANGED_REASON_SYSTEM_POLICY},
         * {@link #STATE_CHANGED_REASON_SYSTEM_BOOT},
         * {@link #STATE_CHANGED_REASON_ERROR_UNKNOWN}.
         *
         * @param isEnabled true when UWB adapter is enabled, false when it is disabled
         * @param reason the reason for the state change
         */
        void onStateChanged(boolean isEnabled, @StateChangedReason int reason);
    }

    /**
     * Use <code>Context.getSystemService(UwbManager.class)</code> to get an instance.
     *
     * @param ctx Context of the client.
     * @param adapter an instance of an {@link android.uwb.IUwbAdapter}
     */
    private UwbManager(@NonNull Context ctx, @NonNull IUwbAdapter adapter) {
        mContext = ctx;
        mUwbAdapter = adapter;
        mAdapterStateListener = new AdapterStateListener(adapter);
        mRangingManager = new RangingManager(adapter);
    }

    /**
     * @hide
     */
    public static UwbManager getInstance(@NonNull Context ctx) {
        IBinder b = ServiceManager.getService(SERVICE_NAME);
        if (b == null) {
            return null;
        }

        IUwbAdapter adapter = IUwbAdapter.Stub.asInterface(b);
        if (adapter == null) {
            return null;
        }

        return new UwbManager(ctx, adapter);
    }

    /**
     * Register an {@link AdapterStateCallback} to listen for UWB adapter state changes
     * <p>The provided callback will be invoked by the given {@link Executor}.
     *
     * <p>When first registering a callback, the callbacks's
     * {@link AdapterStateCallback#onStateChanged(boolean, int)} is immediately invoked to indicate
     * the current state of the underlying UWB adapter with the most recent
     * {@link AdapterStateCallback.StateChangedReason} that caused the change.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link AdapterStateCallback}
     */
    @RequiresPermission(permission.UWB_PRIVILEGED)
    public void registerAdapterStateCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull AdapterStateCallback callback) {
        mAdapterStateListener.register(executor, callback);
    }

    /**
     * Unregister the specified {@link AdapterStateCallback}
     * <p>The same {@link AdapterStateCallback} object used when calling
     * {@link #registerAdapterStateCallback(Executor, AdapterStateCallback)} must be used.
     *
     * <p>Callbacks are automatically unregistered when application process goes away
     *
     * @param callback user implementation of the {@link AdapterStateCallback}
     */
    @RequiresPermission(permission.UWB_PRIVILEGED)
    public void unregisterAdapterStateCallback(@NonNull AdapterStateCallback callback) {
        mAdapterStateListener.unregister(callback);
    }

    /**
     * Get a {@link PersistableBundle} with the supported UWB protocols and parameters.
     * <p>The {@link PersistableBundle} should be parsed using a support library
     *
     * <p>Android reserves the '^android.*' namespace</p>
     *
     * @return {@link PersistableBundle} of the device's supported UWB protocols and parameters
     */
    @NonNull
    @RequiresPermission(permission.UWB_PRIVILEGED)
    public PersistableBundle getSpecificationInfo() {
        try {
            return mUwbAdapter.getSpecificationInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the timestamp resolution for events in nanoseconds
     * <p>This value defines the maximum error of all timestamps for events reported to
     * {@link RangingSession.Callback}.
     *
     * @return the timestamp resolution in nanoseconds
     */
    @SuppressLint("MethodNameUnits")
    @RequiresPermission(permission.UWB_PRIVILEGED)
    public long elapsedRealtimeResolutionNanos() {
        try {
            return mUwbAdapter.getTimestampResolutionNanos();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Open a {@link RangingSession} with the given parameters
     * <p>The {@link RangingSession.Callback#onOpened(RangingSession)} function is called with a
     * {@link RangingSession} object used to control ranging when the session is successfully
     * opened.
     *
     * <p>If a session cannot be opened, then
     * {@link RangingSession.Callback#onClosed(int, PersistableBundle)} will be invoked with the
     * appropriate {@link RangingSession.Callback.Reason}.
     *
     * <p>An open {@link RangingSession} will be automatically closed if client application process
     * dies.
     *
     * <p>A UWB support library must be used in order to construct the {@code parameter}
     * {@link PersistableBundle}.
     *
     * @param parameters the parameters that define the ranging session
     * @param executor {@link Executor} to run callbacks
     * @param callbacks {@link RangingSession.Callback} to associate with the
     *                  {@link RangingSession} that is being opened.
     *
     * @return an {@link CancellationSignal} that is able to be used to cancel the opening of a
     *         {@link RangingSession} that has been requested through {@link #openRangingSession}
     *         but has not yet been made available by
     *         {@link RangingSession.Callback#onOpened(RangingSession)}.
     */
    @NonNull
    @RequiresPermission(allOf = {
            permission.UWB_PRIVILEGED,
            permission.UWB_RANGING
    })
    public CancellationSignal openRangingSession(@NonNull PersistableBundle parameters,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull RangingSession.Callback callbacks) {
        return mRangingManager.openSession(
                mContext.getAttributionSource(), parameters, executor, callbacks);
    }
}
