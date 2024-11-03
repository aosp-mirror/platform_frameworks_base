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

package android.security.advancedprotection;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.security.Flags;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * <p>Advanced Protection is a mode that users can enroll their device into, that enhances security
 * by enabling features and restrictions across both the platform and user apps.
 *
 * <p>This class provides methods to query and control the advanced protection mode
 * for the device.
 */
@FlaggedApi(Flags.FLAG_AAPM_API)
@SystemService(Context.ADVANCED_PROTECTION_SERVICE)
public final class AdvancedProtectionManager {
    private static final String TAG = "AdvancedProtectionMgr";

    private final ConcurrentHashMap<Callback, IAdvancedProtectionCallback>
            mCallbackMap = new ConcurrentHashMap<>();

    @NonNull
    private final IAdvancedProtectionService mService;

    /** @hide */
    public AdvancedProtectionManager(@NonNull IAdvancedProtectionService service) {
        mService = service;
    }

    /**
     * Checks if advanced protection is enabled on the device.
     *
     * @return {@code true} if advanced protection is enabled, {@code false} otherwise.
     */
    @RequiresPermission(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE)
    public boolean isAdvancedProtectionEnabled() {
        try {
            return mService.isAdvancedProtectionEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link Callback} to be notified of changes to the Advanced Protection state.
     *
     * <p>The provided callback will be called on the specified executor with the updated
     * state. Methods are called when the state changes, as well as once
     * on initial registration.
     *
     * @param executor The executor of where the callback will execute.
     * @param callback The {@link Callback} object to register..
     */
    @RequiresPermission(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE)
    public void registerAdvancedProtectionCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback) {
        if (mCallbackMap.get(callback) != null) {
            Log.d(TAG, "registerAdvancedProtectionCallback callback already present");
            return;
        }

        IAdvancedProtectionCallback delegate = new IAdvancedProtectionCallback.Stub() {
            @Override
            public void onAdvancedProtectionChanged(boolean enabled) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> callback.onAdvancedProtectionChanged(enabled));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        };

        try {
            mService.registerAdvancedProtectionCallback(delegate);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mCallbackMap.put(callback, delegate);
    }

    /**
     * Unregister an existing {@link Callback}.
     *
     * @param callback The {@link Callback} object to unregister.
     */
    @RequiresPermission(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE)
    public void unregisterAdvancedProtectionCallback(@NonNull Callback callback) {
        IAdvancedProtectionCallback delegate = mCallbackMap.get(callback);
        if (delegate == null) {
            Log.d(TAG, "unregisterAdvancedProtectionCallback callback not present");
            return;
        }

        try {
            mService.unregisterAdvancedProtectionCallback(delegate);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mCallbackMap.remove(callback);
    }

    /**
     * Enables or disables advanced protection on the device.
     *
     * @param enabled {@code true} to enable advanced protection, {@code false} to disable it.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SET_ADVANCED_PROTECTION_MODE)
    public void setAdvancedProtectionEnabled(boolean enabled) {
        try {
            mService.setAdvancedProtectionEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of advanced protection features which are available on this device.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(Manifest.permission.SET_ADVANCED_PROTECTION_MODE)
    public List<AdvancedProtectionFeature> getAdvancedProtectionFeatures() {
        try {
            return mService.getAdvancedProtectionFeatures();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * A callback class for monitoring changes to Advanced Protection state
     *
     * <p>To register a callback, implement this interface, and register it with
     * {@link AdvancedProtectionManager#registerAdvancedProtectionCallback(Executor, Callback)}.
     * Methods are called when the state changes, as well as once on initial registration.
     */
    @FlaggedApi(Flags.FLAG_AAPM_API)
    public interface Callback {
        /**
         * Called when advanced protection state changes
         * @param enabled the new state
         */
        void onAdvancedProtectionChanged(boolean enabled);
    }
}
