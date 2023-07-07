/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.timedetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.TimeCapabilities;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;

import com.android.server.timezonedetector.StateChangeListener;

import java.util.ArrayList;
import java.util.List;

/** A partially implemented, fake implementation of ServiceConfigAccessor for tests. */
public class FakeServiceConfigAccessor implements ServiceConfigAccessor {

    private final List<StateChangeListener> mConfigurationInternalChangeListeners =
            new ArrayList<>();
    private ConfigurationInternal mConfigurationInternal;

    @Override
    public void addConfigurationInternalChangeListener(StateChangeListener listener) {
        mConfigurationInternalChangeListeners.add(listener);
    }

    @Override
    public void removeConfigurationInternalChangeListener(StateChangeListener listener) {
        mConfigurationInternalChangeListeners.remove(listener);
    }

    @Override
    public ConfigurationInternal getCurrentUserConfigurationInternal() {
        return mConfigurationInternal;
    }

    @Override
    public boolean updateConfiguration(
            @UserIdInt int userID, @NonNull TimeConfiguration requestedChanges,
            boolean bypassUserPolicyChecks) {
        assertNotNull(mConfigurationInternal);
        assertNotNull(requestedChanges);

        // Simulate the real strategy's behavior: the new configuration will be updated to be the
        // old configuration merged with the new if the user has the capability to up the settings.
        // Then, if the configuration changed, the change listener is invoked.
        TimeCapabilitiesAndConfig capabilitiesAndConfig =
                mConfigurationInternal.createCapabilitiesAndConfig(bypassUserPolicyChecks);
        TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
        TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
        TimeConfiguration newConfiguration =
                capabilities.tryApplyConfigChanges(configuration, requestedChanges);
        if (newConfiguration == null) {
            return false;
        }

        if (!newConfiguration.equals(capabilitiesAndConfig.getConfiguration())) {
            mConfigurationInternal = mConfigurationInternal.merge(newConfiguration);

            // Note: Unlike the real strategy, the listeners are invoked synchronously.
            simulateConfigurationChangeForTests();
        }
        return true;
    }

    void initializeConfiguration(ConfigurationInternal configurationInternal) {
        mConfigurationInternal = configurationInternal;
    }

    void simulateConfigurationChangeForTests() {
        for (StateChangeListener listener : mConfigurationInternalChangeListeners) {
            listener.onChange();
        }
    }

    @Override
    public ConfigurationInternal getConfigurationInternal(int userId) {
        assertEquals("Multi-user testing not supported currently",
                userId, mConfigurationInternal.getUserId());
        return mConfigurationInternal;
    }
}
