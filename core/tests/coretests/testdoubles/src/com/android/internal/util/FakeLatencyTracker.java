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

package com.android.internal.util;

import static com.android.internal.util.LatencyTracker.ActionProperties.ENABLE_SUFFIX;
import static com.android.internal.util.LatencyTracker.ActionProperties.SAMPLE_INTERVAL_SUFFIX;
import static com.android.internal.util.LatencyTracker.ActionProperties.TRACE_THRESHOLD_SUFFIX;

import static com.google.common.truth.Truth.assertThat;

import android.os.ConditionVariable;
import android.provider.DeviceConfig;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;

import com.google.common.collect.ImmutableMap;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

public final class FakeLatencyTracker extends LatencyTracker {

    private static final String TAG = "FakeLatencyTracker";
    private static final Duration FORCE_UPDATE_TIMEOUT = Duration.ofSeconds(1);

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<Integer, List<FrameworkStatsLogEvent>> mLatenciesLogged;
    @GuardedBy("mLock")
    private final List<String> mPerfettoTraceNamesTriggered;
    private final AtomicReference<SparseArray<ActionProperties>> mLastPropertiesUpdate =
            new AtomicReference<>();
    @Nullable
    @GuardedBy("mLock")
    private Callable<Boolean> mShouldClosePropertiesUpdatedCallable = null;
    private final ConditionVariable mDeviceConfigPropertiesUpdated = new ConditionVariable();

    public static FakeLatencyTracker create() throws Exception {
        Log.i(TAG, "create");
        disableForAllActions();
        FakeLatencyTracker fakeLatencyTracker = new FakeLatencyTracker();
        // always return the fake in the disabled state and let the client control the desired state
        fakeLatencyTracker.waitForGlobalEnabledState(false);
        fakeLatencyTracker.waitForAllPropertiesEnableState(false);
        return fakeLatencyTracker;
    }

    FakeLatencyTracker() {
        super();
        mLatenciesLogged = new HashMap<>();
        mPerfettoTraceNamesTriggered = new ArrayList<>();
    }

    private static void disableForAllActions() throws DeviceConfig.BadConfigException {
        Map<String, String> properties = new HashMap<>();
        properties.put(LatencyTracker.SETTINGS_ENABLED_KEY, "false");
        for (int action : STATSD_ACTION) {
            Log.d(TAG, "disabling action=" + action + ", property=" + getNameOfAction(
                    action).toLowerCase(Locale.ROOT) + ENABLE_SUFFIX);
            properties.put(getNameOfAction(action).toLowerCase(Locale.ROOT) + ENABLE_SUFFIX,
                    "false");
        }

        DeviceConfig.setProperties(
                new DeviceConfig.Properties(DeviceConfig.NAMESPACE_LATENCY_TRACKER, properties));
    }

    public void forceEnabled(int action, int traceThresholdMillis)
            throws Exception {
        String actionName = getNameOfAction(STATSD_ACTION[action]).toLowerCase(Locale.ROOT);
        String actionEnableProperty = actionName + ENABLE_SUFFIX;
        String actionSampleProperty = actionName + SAMPLE_INTERVAL_SUFFIX;
        String actionTraceProperty = actionName + TRACE_THRESHOLD_SUFFIX;
        Log.i(TAG, "setting property=" + actionTraceProperty + ", value=" + traceThresholdMillis);
        Log.i(TAG, "setting property=" + actionEnableProperty + ", value=true");

        Map<String, String> properties = new HashMap<>(ImmutableMap.of(
                actionEnableProperty, "true",
                // Fake forces to sample every event
                actionSampleProperty, String.valueOf(1),
                actionTraceProperty, String.valueOf(traceThresholdMillis)
        ));
        DeviceConfig.setProperties(
                new DeviceConfig.Properties(DeviceConfig.NAMESPACE_LATENCY_TRACKER, properties));
        waitForMatchingActionProperties(
                new ActionProperties(action, true /* enabled */, 1 /* samplingInterval */,
                        traceThresholdMillis));
    }

    public List<FrameworkStatsLogEvent> getEventsWrittenToFrameworkStats(@Action int action) {
        synchronized (mLock) {
            Log.i(TAG, "getEventsWrittenToFrameworkStats: mLatenciesLogged=" + mLatenciesLogged);
            return mLatenciesLogged.getOrDefault(action, Collections.emptyList());
        }
    }

    public List<String> getTriggeredPerfettoTraceNames() {
        synchronized (mLock) {
            return mPerfettoTraceNamesTriggered;
        }
    }

    public void clearEvents() {
        synchronized (mLock) {
            mLatenciesLogged.clear();
            mPerfettoTraceNamesTriggered.clear();
        }
    }

    @Override
    public void onDeviceConfigPropertiesUpdated(SparseArray<ActionProperties> actionProperties) {
        Log.d(TAG, "onDeviceConfigPropertiesUpdated: " + actionProperties);
        mLastPropertiesUpdate.set(actionProperties);
        synchronized (mLock) {
            if (mShouldClosePropertiesUpdatedCallable != null) {
                try {
                    boolean shouldClosePropertiesUpdated =
                            mShouldClosePropertiesUpdatedCallable.call();
                    Log.i(TAG, "shouldClosePropertiesUpdatedCallable callable result="
                            + shouldClosePropertiesUpdated);
                    if (shouldClosePropertiesUpdated) {
                        Log.i(TAG, "shouldClosePropertiesUpdatedCallable=true, opening condition");
                        mShouldClosePropertiesUpdatedCallable = null;
                        mDeviceConfigPropertiesUpdated.open();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "exception when calling callable", e);
                    throw new RuntimeException(e);
                }
            } else {
                Log.i(TAG, "no conditional callable set, opening condition");
                mDeviceConfigPropertiesUpdated.open();
            }
        }
    }

    @Override
    public void onTriggerPerfetto(String triggerName) {
        synchronized (mLock) {
            mPerfettoTraceNamesTriggered.add(triggerName);
        }
    }

    @Override
    public void onLogToFrameworkStats(FrameworkStatsLogEvent event) {
        synchronized (mLock) {
            Log.i(TAG, "onLogToFrameworkStats: event=" + event);
            List<FrameworkStatsLogEvent> eventList = mLatenciesLogged.getOrDefault(event.action,
                    new ArrayList<>());
            eventList.add(event);
            mLatenciesLogged.put(event.action, eventList);
        }
    }

    public void waitForAllPropertiesEnableState(boolean enabledState) throws Exception {
        Log.i(TAG, "waitForAllPropertiesEnableState: enabledState=" + enabledState);
        synchronized (mLock) {
            Log.i(TAG, "closing condition");
            mDeviceConfigPropertiesUpdated.close();
            // Update the callable to only close the properties updated condition when all the
            // desired properties have been updated. The DeviceConfig callbacks may happen multiple
            // times so testing the resulting updates is required.
            mShouldClosePropertiesUpdatedCallable = () -> {
                Log.i(TAG, "verifying if last properties update has all properties enable="
                        + enabledState);
                SparseArray<ActionProperties> newProperties = mLastPropertiesUpdate.get();
                if (newProperties != null) {
                    for (int i = 0; i < newProperties.size(); i++) {
                        if (newProperties.get(i).isEnabled() != enabledState) {
                            return false;
                        }
                    }
                }
                return true;
            };
            if (mShouldClosePropertiesUpdatedCallable.call()) {
                return;
            }
        }
        Log.i(TAG, "waiting for condition");
        assertThat(mDeviceConfigPropertiesUpdated.block(FORCE_UPDATE_TIMEOUT.toMillis())).isTrue();
    }

    public void waitForMatchingActionProperties(ActionProperties actionProperties)
            throws Exception {
        Log.i(TAG, "waitForMatchingActionProperties: actionProperties=" + actionProperties);
        synchronized (mLock) {
            Log.i(TAG, "closing condition");
            mDeviceConfigPropertiesUpdated.close();
            // Update the callable to only close the properties updated condition when all the
            // desired properties have been updated. The DeviceConfig callbacks may happen multiple
            // times so testing the resulting updates is required.
            mShouldClosePropertiesUpdatedCallable = () -> {
                Log.i(TAG, "verifying if last properties update contains matching property ="
                        + actionProperties);
                SparseArray<ActionProperties> newProperties = mLastPropertiesUpdate.get();
                if (newProperties != null) {
                    if (newProperties.size() > 0) {
                        return newProperties.get(actionProperties.getAction()).equals(
                                actionProperties);
                    }
                }
                return false;
            };
            if (mShouldClosePropertiesUpdatedCallable.call()) {
                return;
            }
        }
        Log.i(TAG, "waiting for condition");
        assertThat(mDeviceConfigPropertiesUpdated.block(FORCE_UPDATE_TIMEOUT.toMillis())).isTrue();
    }

    public void waitForActionEnabledState(int action, boolean enabledState) throws Exception {
        Log.i(TAG, "waitForActionEnabledState:"
                + " action=" + action + ", enabledState=" + enabledState);
        synchronized (mLock) {
            Log.i(TAG, "closing condition");
            mDeviceConfigPropertiesUpdated.close();
            // Update the callable to only close the properties updated condition when all the
            // desired properties have been updated. The DeviceConfig callbacks may happen multiple
            // times so testing the resulting updates is required.
            mShouldClosePropertiesUpdatedCallable = () -> {
                Log.i(TAG, "verifying if last properties update contains action=" + action
                        + ", enabledState=" + enabledState);
                SparseArray<ActionProperties> newProperties = mLastPropertiesUpdate.get();
                if (newProperties != null) {
                    if (newProperties.size() > 0) {
                        return newProperties.get(action).isEnabled() == enabledState;
                    }
                }
                return false;
            };
            if (mShouldClosePropertiesUpdatedCallable.call()) {
                return;
            }
        }
        Log.i(TAG, "waiting for condition");
        assertThat(mDeviceConfigPropertiesUpdated.block(FORCE_UPDATE_TIMEOUT.toMillis())).isTrue();
    }

    public void waitForGlobalEnabledState(boolean enabledState) throws Exception {
        Log.i(TAG, "waitForGlobalEnabledState: enabledState=" + enabledState);
        synchronized (mLock) {
            Log.i(TAG, "closing condition");
            mDeviceConfigPropertiesUpdated.close();
            // Update the callable to only close the properties updated condition when all the
            // desired properties have been updated. The DeviceConfig callbacks may happen multiple
            // times so testing the resulting updates is required.
            mShouldClosePropertiesUpdatedCallable = () -> {
                //noinspection deprecation
                return isEnabled() == enabledState;
            };
            if (mShouldClosePropertiesUpdatedCallable.call()) {
                return;
            }
        }
        Log.i(TAG, "waiting for condition");
        assertThat(mDeviceConfigPropertiesUpdated.block(FORCE_UPDATE_TIMEOUT.toMillis())).isTrue();
    }
}
