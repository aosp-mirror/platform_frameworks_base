/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicestate;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Dumpable;

import com.android.server.policy.DeviceStatePolicyImpl;

/**
 * Interface for the component responsible for supplying the current device state as well as
 * configuring the state of the system in response to device state changes.
 *
 * @see DeviceStateManagerService
 */
public abstract class DeviceStatePolicy implements Dumpable {
    protected final Context mContext;

    protected DeviceStatePolicy(@NonNull Context context) {
        mContext = context;
    }

    /** Returns the provider of device states. */
    public abstract DeviceStateProvider getDeviceStateProvider();

    /**
     * Configures the system into the provided state. Guaranteed not to be called again until the
     * {@code onComplete} callback has been executed.
     *
     * @param state the state the system should be configured for.
     * @param onComplete a callback that must be triggered once the system has been properly
     *                   configured to match the supplied state.
     */
    public abstract void configureDeviceForState(int state, @NonNull Runnable onComplete);

    /** Provider for platform-default device state policy. */
    static final class DefaultProvider implements DeviceStatePolicy.Provider {
        @Override
        public DeviceStatePolicy instantiate(@NonNull Context context) {
            return new DeviceStatePolicyImpl(context);
        }
    }

    /**
     * Provider for {@link DeviceStatePolicy} instances.
     *
     * <p>By implementing this interface and overriding the
     * {@code config_deviceSpecificDeviceStatePolicyProvider}, a device-specific implementations
     * of {@link DeviceStatePolicy} can be supplied.
     */
    public interface Provider {
        /**
         * Instantiates a new {@link DeviceStatePolicy}.
         *
         * @see DeviceStatePolicy#DeviceStatePolicy
         */
        DeviceStatePolicy instantiate(@NonNull Context context);

        /**
         * Instantiates the device-specific {@link DeviceStatePolicy.Provider}.
         *
         * Checks the {@code config_deviceSpecificDeviceStatePolicyProvider} resource to see if
         * a device specific policy provider has been supplied. If so, returns an instance of that
         * provider. If there is no value provided then the method returns the
         * {@link DeviceStatePolicy.DefaultProvider}.
         *
         * An {@link IllegalStateException} is thrown if there is a value provided for that
         * resource, but it doesn't correspond to a class that is found.
         */
        static Provider fromResources(@NonNull Resources res) {
            final String name = res.getString(
                    com.android.internal.R.string.config_deviceSpecificDeviceStatePolicyProvider);
            if (TextUtils.isEmpty(name)) {
                return new DeviceStatePolicy.DefaultProvider();
            }

            try {
                return (DeviceStatePolicy.Provider) Class.forName(name).newInstance();
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw new IllegalStateException("Couldn't instantiate class " + name
                        + " for config_deviceSpecificDeviceStatePolicyProvider:"
                        + " make sure it has a public zero-argument constructor"
                        + " and implements DeviceStatePolicy.Provider", e);
            }
        }
    }

}
