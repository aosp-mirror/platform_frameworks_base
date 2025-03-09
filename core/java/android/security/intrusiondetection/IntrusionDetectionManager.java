/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.security.intrusiondetection;

import static android.Manifest.permission.MANAGE_INTRUSION_DETECTION_STATE;
import static android.Manifest.permission.READ_INTRUSION_DETECTION_STATE;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.security.Flags;
import android.util.Log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * IntrusionDetectionManager manages the intrusion detection on Android devices.
 * Upon user consent, intrusion detection collects various device events for
 * off-device investigation of potential device compromise.
 * <p>
 * Intrusion detection logging can either be enabled ({@link #STATE_ENABLED}
 * or disabled ({@link #STATE_DISABLED}).
 * <p>
 * The intrusion detection logs will be transferred to
 * {@link android.security.intrusiondetection.IntrusionDetectionEventTransport}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_AFL_API)
@SystemService(Context.INTRUSION_DETECTION_SERVICE)
public class IntrusionDetectionManager {
    private static final String TAG = "IntrusionDetectionManager";

    /** @hide */
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "STATE_" }, value = {
            STATE_UNKNOWN,
            STATE_DISABLED,
            STATE_ENABLED
    })
    public @interface IntrusionDetectionState {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "ERROR_" }, value = {
            ERROR_UNKNOWN,
            ERROR_PERMISSION_DENIED,
            ERROR_TRANSPORT_UNAVAILABLE,
            ERROR_DATA_SOURCE_UNAVAILABLE
    })
    public @interface IntrusionDetectionError {}

    /**
     * Indicates an unknown state
     */
    public static final int STATE_UNKNOWN = IIntrusionDetectionServiceStateCallback.State.UNKNOWN;

    /**
     * Indicates an state that the intrusion detection is turned off.
     */
    public static final int STATE_DISABLED = IIntrusionDetectionServiceStateCallback.State.DISABLED;

    /**
     * Indicates an state that the intrusion detection is turned on.
     */
    public static final int STATE_ENABLED = IIntrusionDetectionServiceStateCallback.State.ENABLED;

    /**
     * Indicates an unknown error
     */
    public static final int ERROR_UNKNOWN =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.UNKNOWN;

    /**
     * Indicates an error due to insufficient access rights.
     */
    public static final int ERROR_PERMISSION_DENIED =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.PERMISSION_DENIED;

    /**
     * Indicates an error due to unavailability of the intrusion detection event transport.
     */
    public static final int ERROR_TRANSPORT_UNAVAILABLE =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.TRANSPORT_UNAVAILABLE;

    /**
     * Indicates an error due to unavailability of the data source.
     */
    public static final int ERROR_DATA_SOURCE_UNAVAILABLE =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.DATA_SOURCE_UNAVAILABLE;


    private final IIntrusionDetectionService mService;

    private final ConcurrentHashMap<Consumer<Integer>, IIntrusionDetectionServiceStateCallback>
            mStateCallbacks = new ConcurrentHashMap<>();

    /**
     * Constructor
     *
     * @param service A valid instance of IIntrusionDetectionService.
     * @hide
     */
    public IntrusionDetectionManager(IIntrusionDetectionService service) {
        mService = service;
    }

    /**
     * Add a callback to monitor the state of the IntrusionDetectionService.
     *
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback for state change.
     *                 Once the callback is registered, the callback will be called
     *                 to reflect the init state.
     *                 The callback can be registered only once.
     */
    @RequiresPermission(READ_INTRUSION_DETECTION_STATE)
    public void addStateCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull @IntrusionDetectionState Consumer<Integer> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        if (mStateCallbacks.get(callback) != null) {
            Log.d(TAG, "addStateCallback callback already present");
            return;
        }

        final IIntrusionDetectionServiceStateCallback wrappedCallback =
                new IIntrusionDetectionServiceStateCallback.Stub() {
                    @Override
                    public void onStateChange(int state) {
                        executor.execute(() -> callback.accept(state));
                    }
                };
        try {
            mService.addStateCallback(wrappedCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mStateCallbacks.put(callback, wrappedCallback);
    }

    /**
     * Remove a callback to monitor the state of the IntrusionDetectionService.
     *
     * @param callback The callback to remove.
     */
    @RequiresPermission(READ_INTRUSION_DETECTION_STATE)
    public void removeStateCallback(@NonNull Consumer<@IntrusionDetectionState Integer> callback) {
        Objects.requireNonNull(callback);
        if (!mStateCallbacks.containsKey(callback)) {
            Log.d(TAG, "removeStateCallback callback not present");
            return;
        }

        IIntrusionDetectionServiceStateCallback wrappedCallback = mStateCallbacks.get(callback);

        try {
            mService.removeStateCallback(wrappedCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mStateCallbacks.remove(callback);
    }

    /**
     * Enable intrusion detection.
     * If successful, IntrusionDetectionService will transition to {@link #STATE_ENABLED} state.
     * <p>
     * When intrusion detection is enabled, various device events will be collected and
     * sent over to the registered
     * {@link android.security.intrusiondetection.IntrusionDetectionEventTransport}.
     *
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback for the command result.
     */
    @RequiresPermission(MANAGE_INTRUSION_DETECTION_STATE)
    public void enable(@NonNull @CallbackExecutor Executor executor,
            @NonNull CommandCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.enable(new IIntrusionDetectionServiceCommandCallback.Stub() {
                @Override
                public void onSuccess() {
                    executor.execute(callback::onSuccess);
                }

                @Override
                public void onFailure(int error) {
                    executor.execute(() -> callback.onFailure(error));
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Disable intrusion detection.
     * If successful, IntrusionDetectionService will transition to {@link #STATE_DISABLED}.
     * <p>
     * When intrusion detection is disabled, device events will no longer be collected.
     * Any events that have been collected but not yet sent to IntrusionDetectionEventTransport
     * will be transferred as a final batch.
     *
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback for the command result.
     */
    @RequiresPermission(MANAGE_INTRUSION_DETECTION_STATE)
    public void disable(@NonNull @CallbackExecutor Executor executor,
            @NonNull CommandCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            mService.disable(new IIntrusionDetectionServiceCommandCallback.Stub() {
                @Override
                public void onSuccess() {
                    executor.execute(callback::onSuccess);
                }

                @Override
                public void onFailure(int error) {
                    executor.execute(() -> callback.onFailure(error));
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Callback used in {@link #enable} and {@link #disable} to indicate the result of the command.
     */
    public interface CommandCallback {
        /**
         * Called when command succeeds.
         */
        void onSuccess();

        /**
         * Called when command fails.
         * @param error The error number.
         */
        void onFailure(@IntrusionDetectionError int error);
    }
}
