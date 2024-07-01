/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.sensors;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.os.Handler;
import android.os.IInterface;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Container for all BiometricServiceProvider implementations.
 *
 * @param <T> The service provider type.
 * @param <P> The internal properties type.
 * @param <C> The registration callback for {@link #invokeRegisteredCallback(IInterface, List)}.
 */
public abstract class BiometricServiceRegistry<T extends BiometricServiceProvider<P>,
        P extends SensorPropertiesInternal,
        C extends IInterface> {

    private static final String TAG = "BiometricServiceRegistry";

    // Volatile so they can be read without a lock once all services are registered.
    // But, ideally remove this and provide immutable copies via the callback instead.
    @Nullable
    private volatile List<T> mServiceProviders;
    @Nullable
    private volatile List<P> mAllProps;

    @NonNull
    private final Supplier<IBiometricService> mBiometricServiceSupplier;
    @NonNull
    private final RemoteCallbackList<C> mRegisteredCallbacks = new RemoteCallbackList<>();

    public BiometricServiceRegistry(@NonNull Supplier<IBiometricService> biometricSupplier) {
        mBiometricServiceSupplier = biometricSupplier;
    }

    /**
     * Register an implementation by creating a new authenticator and initializing it via
     * {@link IBiometricService#registerAuthenticator(int, int, int, IBiometricAuthenticator)}
     * using the given properties.
     *
     * @param service service to register with
     * @param props   internal properties to initialize the authenticator
     */
    protected abstract void registerService(@NonNull IBiometricService service, @NonNull P props);

    /**
     * Invoke the callback to notify clients that all authenticators have been registered.
     *
     * @param callback callback to invoke
     * @param allProps properties of all authenticators
     */
    protected abstract void invokeRegisteredCallback(@NonNull C callback,
            @NonNull List<P> allProps) throws RemoteException;

    /**
     * Register all authenticators in a background thread.
     *
     * @param serviceProvider Supplier function that will be invoked on the background thread.
     */
    public void registerAll(Supplier<List<T>> serviceProvider) {
        // Some HAL might not be started before the system service and will cause the code below
        // to wait, and some of the operations below might take a significant amount of time to
        // complete (calls to the HALs). To avoid blocking the rest of system server we put
        // this on a background thread.
        final ServiceThread thread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                true /* allowIo */);
        thread.start();
        final Handler handler = new Handler(thread.getLooper());
        handler.post(() -> registerAllInBackground(serviceProvider));
        thread.quitSafely();
    }

    /** Register authenticators now, only called by {@link #registerAll(Supplier).} */
    @VisibleForTesting
    public void registerAllInBackground(Supplier<List<T>> serviceProvider) {
        List<T> providers = serviceProvider.get();
        if (providers == null) {
            providers = new ArrayList<>();
        }

        final IBiometricService biometricService = mBiometricServiceSupplier.get();
        if (biometricService == null) {
            throw new IllegalStateException("biometric service cannot be null");
        }

        // Register each sensor individually with BiometricService
        final List<P> allProps = new ArrayList<>();
        for (T provider : providers) {
            if(provider != null) {
                final List<P> props = provider.getSensorProperties();
                for (P prop : props) {
                    registerService(biometricService, prop);
                }
                allProps.addAll(props);
            }
        }

        finishRegistration(providers, allProps);
    }

    private synchronized void finishRegistration(
            @NonNull List<T> providers, @NonNull List<P> allProps) {
        mServiceProviders = Collections.unmodifiableList(providers);
        mAllProps = Collections.unmodifiableList(allProps);
        broadcastAllAuthenticatorsRegistered();
    }

    /**
     * Add a callback that will be invoked once the work from {@link #registerAll(Supplier)}
     * has finished registering all providers (executes immediately if already done).
     *
     * @param callback registration callback
     */
    public synchronized void addAllRegisteredCallback(@Nullable C callback) {
        if (callback == null) {
            Slog.e(TAG, "addAllRegisteredCallback, callback is null");
            return;
        }

        final boolean registered = mRegisteredCallbacks.register(callback);
        final boolean allRegistered = mServiceProviders != null;
        if (registered && allRegistered) {
            broadcastAllAuthenticatorsRegistered();
        } else if (!registered) {
            Slog.e(TAG, "addAllRegisteredCallback failed to register callback");
        }
    }

    private synchronized void broadcastAllAuthenticatorsRegistered() {
        final int n = mRegisteredCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            final C cb = mRegisteredCallbacks.getBroadcastItem(i);
            try {
                invokeRegisteredCallback(cb, mAllProps);
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception in broadcastAllAuthenticatorsRegistered", e);
            } finally {
                mRegisteredCallbacks.unregister(cb);
            }
        }
        mRegisteredCallbacks.finishBroadcast();
    }

    /**
     * Get a list of registered providers.
     *
     * Undefined until {@link #registerAll(Supplier)} has fired the completion callback.
     */
    @NonNull
    public List<T> getProviders() {
        return mServiceProviders != null ? mServiceProviders : Collections.emptyList();
    }

    /**
     * Gets the provider for given sensor id or null if not registered.
     *
     * Undefined until {@link #registerAll(Supplier)} has fired the completion callback.
     */
    @Nullable
    public T getProviderForSensor(int sensorId) {
        if (mServiceProviders != null) {
            for (T provider : mServiceProviders) {
                if (provider.containsSensor(sensorId)) {
                    return provider;
                }
            }
        }
        return null;
    }

    /**
     * Finds the provider for devices with only a single sensor.
     *
     * If no providers returns null. If multiple sensors are present this method
     * will return the first one that is found (this is a legacy for test devices that
     * use aidl/hidl concurrently and should not occur on real devices).
     *
     * Undefined until {@link #registerAll(Supplier)} has fired the completion callback.
     */
    @Nullable
    public Pair<Integer, T> getSingleProvider() {
        if (mAllProps == null || mAllProps.isEmpty()) {
            Slog.e(TAG, "No sensors found");
            return null;
        }

        // TODO(b/242837110): remove the try-catch once the bug is fixed.
        try {
            if (mAllProps.size() > 1) {
                Slog.e(TAG, "getSingleProvider() called but multiple sensors present: "
                        + mAllProps.size());
            }

            final int sensorId = mAllProps.get(0).sensorId;
            final T provider = getProviderForSensor(sensorId);
            if (provider != null) {
                return new Pair<>(sensorId, provider);
            }

            Slog.e(TAG, "Single sensor: " + sensorId + ", but provider not found");
            return null;
        } catch (NullPointerException e) {
            final String extra;
            if (mAllProps == null) {
                extra = "mAllProps: null";
            } else {
                extra = "mAllProps.size(): " + mAllProps.size();
            }
            Slog.e(TAG, "This shouldn't happen. " + extra, e);
            throw e;
        }
    }

    /**
     * Get the properties for all providers.
     *
     * Undefined until {@link #registerAll(Supplier)} has fired the completion callback.
     */
    @NonNull
    public List<P> getAllProperties() {
        return mAllProps != null ? mAllProps : Collections.emptyList();
    }
}
