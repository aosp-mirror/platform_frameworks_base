/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import android.annotation.NonNull;
import android.os.Binder;
import android.provider.DeviceConfig;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract base class to encapsulates the feature flags for magnification features.
 * {@see DeviceConfig}
 *
 * @hide
 */
abstract class MagnificationFeatureFlagBase {

    abstract String getNamespace();
    abstract String getFeatureName();
    abstract boolean getDefaultValue();

    private void clearCallingIdentifyAndTryCatch(Runnable tryBlock, Runnable catchBlock) {
        try {
            Binder.withCleanCallingIdentity(() -> tryBlock.run());
        } catch (Throwable throwable) {
            catchBlock.run();
        }
    }

    /** Returns true iff the feature flag is readable and enabled */
    public boolean isFeatureFlagEnabled() {
        AtomicBoolean isEnabled = new AtomicBoolean(getDefaultValue());

        clearCallingIdentifyAndTryCatch(
                () -> isEnabled.set(DeviceConfig.getBoolean(
                        getNamespace(),
                        getFeatureName(),
                        getDefaultValue())),
                () -> isEnabled.set(getDefaultValue()));

        return isEnabled.get();
    }

    /** Sets the feature flag. Only used for testing; requires shell permissions. */
    @VisibleForTesting
    public boolean setFeatureFlagEnabled(boolean isEnabled) {
        AtomicBoolean success = new AtomicBoolean(getDefaultValue());

        clearCallingIdentifyAndTryCatch(
                () -> success.set(DeviceConfig.setProperty(
                        getNamespace(),
                        getFeatureName(),
                        Boolean.toString(isEnabled),
                        /* makeDefault= */ false)),
                () -> success.set(getDefaultValue()));

        return success.get();
    }

    /**
     * Adds a listener for when the feature flag changes.
     *
     * <p>{@see DeviceConfig#addOnPropertiesChangedListener(
     * String, Executor, DeviceConfig.OnPropertiesChangedListener)}
     *
     * <p>Note: be weary of using a DIRECT_EXECUTOR here. You may run into deadlocks! (see
     * b/281132229)
     */
    @NonNull
    public DeviceConfig.OnPropertiesChangedListener addOnChangedListener(
            @NonNull Executor executor, @NonNull Runnable listener) {
        DeviceConfig.OnPropertiesChangedListener onChangedListener =
                properties -> {
                    if (properties.getKeyset().contains(
                            getFeatureName())) {
                        listener.run();
                    }
                };

        clearCallingIdentifyAndTryCatch(
                () -> DeviceConfig.addOnPropertiesChangedListener(
                        getNamespace(),
                        executor,
                        onChangedListener),
                () -> {});

        return onChangedListener;
    }

    /**
     * Remove a listener for when the feature flag changes.
     *
     * <p>{@see DeviceConfig#addOnPropertiesChangedListener(String, Executor,
     * DeviceConfig.OnPropertiesChangedListener)}
     */
    public void removeOnChangedListener(
            @NonNull DeviceConfig.OnPropertiesChangedListener onChangedListener) {
        DeviceConfig.removeOnPropertiesChangedListener(onChangedListener);
    }
}
