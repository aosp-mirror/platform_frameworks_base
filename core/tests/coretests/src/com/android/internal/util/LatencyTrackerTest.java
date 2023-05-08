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

package com.android.internal.util;

import static android.provider.DeviceConfig.NAMESPACE_LATENCY_TRACKER;
import static android.text.TextUtils.formatSimple;

import static com.android.internal.util.FrameworkStatsLog.UI_ACTION_LATENCY_REPORTED;
import static com.android.internal.util.LatencyTracker.STATSD_ACTION;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.provider.DeviceConfig;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.util.LatencyTracker.ActionProperties;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class LatencyTrackerTest {
    private static final String ENUM_NAME_PREFIX = "UIACTION_LATENCY_REPORTED__ACTION__";

    @Rule
    public final Expect mExpect = Expect.create();

    // Fake is used because it tests the real logic of LatencyTracker, and it only fakes the
    // outcomes (PerfettoTrigger and FrameworkStatsLog).
    private FakeLatencyTracker mLatencyTracker;

    @Before
    public void setUp() throws Exception {
        mLatencyTracker = FakeLatencyTracker.create();
    }

    @After
    public void tearDown() {
        mLatencyTracker.stopListeningForLatencyTrackerConfigChanges();
    }

    @Test
    public void testCujsMapToEnumsCorrectly() {
        List<Field> actions = getAllActionFields();
        Map<Integer, String> enumsMap = Arrays.stream(FrameworkStatsLog.class.getDeclaredFields())
                .filter(f -> f.getName().startsWith(ENUM_NAME_PREFIX)
                        && Modifier.isStatic(f.getModifiers())
                        && f.getType() == int.class)
                .collect(Collectors.toMap(this::getIntFieldChecked, Field::getName));

        assertThat(enumsMap.size() - 1).isEqualTo(actions.size());

        actions.forEach(f -> {
            final int action = getIntFieldChecked(f);
            final String actionName = f.getName();
            final String expectedEnumName = formatSimple("%s%s", ENUM_NAME_PREFIX, actionName);
            final int enumKey = STATSD_ACTION[action];
            final String enumName = enumsMap.get(enumKey);
            final String expectedActionName = LatencyTracker.getNameOfAction(enumKey);
            mExpect
                    .withMessage(formatSimple(
                            "%s (%d) not matches %s (%d)", actionName, action, enumName, enumKey))
                    .that(expectedEnumName.equals(enumName))
                    .isTrue();
            mExpect
                    .withMessage(
                            formatSimple("getNameOfAction(%d) not matches: %s, expected=%s",
                                    enumKey, actionName, expectedActionName))
                    .that(actionName.equals(expectedActionName))
                    .isTrue();
        });
    }

    @Test
    public void testCujTypeEnumCorrectlyDefined() throws Exception {
        List<Field> cujEnumFields = getAllActionFields();
        HashSet<Integer> allValues = new HashSet<>();
        for (Field field : cujEnumFields) {
            int fieldValue = field.getInt(null);
            assertWithMessage(
                    "Field %s must have a mapping to a value in STATSD_ACTION",
                    field.getName())
                    .that(fieldValue < STATSD_ACTION.length)
                    .isTrue();
            assertWithMessage("All CujType values must be unique. Field %s repeats existing value.",
                    field.getName())
                    .that(allValues.add(fieldValue))
                    .isTrue();
        }
    }

    @Test
    public void testIsEnabled_trueWhenGlobalEnabled() throws Exception {
        DeviceConfig.setProperty(NAMESPACE_LATENCY_TRACKER,
                LatencyTracker.SETTINGS_ENABLED_KEY, "true", false);
        mLatencyTracker.waitForGlobalEnabledState(true);
        mLatencyTracker.waitForAllPropertiesEnableState(true);

        //noinspection deprecation
        assertThat(mLatencyTracker.isEnabled()).isTrue();
    }

    @Test
    public void testIsEnabled_falseWhenGlobalDisabled() throws Exception {
        DeviceConfig.setProperty(NAMESPACE_LATENCY_TRACKER,
                LatencyTracker.SETTINGS_ENABLED_KEY, "false", false);
        mLatencyTracker.waitForGlobalEnabledState(false);
        mLatencyTracker.waitForAllPropertiesEnableState(false);

        //noinspection deprecation
        assertThat(mLatencyTracker.isEnabled()).isFalse();
    }

    @Test
    public void testIsEnabledAction_useGlobalValueWhenActionEnableIsNotSet()
            throws Exception {
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        DeviceConfig.deleteProperty(NAMESPACE_LATENCY_TRACKER,
                "action_show_voice_interaction_enable");
        mLatencyTracker.waitForAllPropertiesEnableState(false);
        DeviceConfig.setProperty(NAMESPACE_LATENCY_TRACKER,
                LatencyTracker.SETTINGS_ENABLED_KEY, "true", false);
        mLatencyTracker.waitForGlobalEnabledState(true);
        mLatencyTracker.waitForAllPropertiesEnableState(true);

        assertThat(mLatencyTracker.isEnabled(action)).isTrue();
    }

    @Test
    public void testIsEnabledAction_actionPropertyOverridesGlobalProperty()
            throws Exception {
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        DeviceConfig.setProperty(NAMESPACE_LATENCY_TRACKER,
                LatencyTracker.SETTINGS_ENABLED_KEY, "false", false);
        mLatencyTracker.waitForGlobalEnabledState(false);

        Map<String, String> deviceConfigProperties = new HashMap<>();
        deviceConfigProperties.put("action_show_voice_interaction_enable", "true");
        deviceConfigProperties.put("action_show_voice_interaction_sample_interval", "1");
        deviceConfigProperties.put("action_show_voice_interaction_trace_threshold", "-1");
        DeviceConfig.setProperties(
                new DeviceConfig.Properties(NAMESPACE_LATENCY_TRACKER,
                        deviceConfigProperties));

        mLatencyTracker.waitForMatchingActionProperties(
                new ActionProperties(action, true /* enabled */, 1 /* samplingInterval */,
                        -1 /* traceThreshold */));

        assertThat(mLatencyTracker.isEnabled(action)).isTrue();
    }

    @Test
    public void testLogsWhenEnabled() throws Exception {
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        Map<String, String> deviceConfigProperties = new HashMap<>();
        deviceConfigProperties.put("action_show_voice_interaction_enable", "true");
        deviceConfigProperties.put("action_show_voice_interaction_sample_interval", "1");
        deviceConfigProperties.put("action_show_voice_interaction_trace_threshold", "-1");
        DeviceConfig.setProperties(
                new DeviceConfig.Properties(NAMESPACE_LATENCY_TRACKER,
                        deviceConfigProperties));
        mLatencyTracker.waitForMatchingActionProperties(
                new ActionProperties(action, true /* enabled */, 1 /* samplingInterval */,
                        -1 /* traceThreshold */));

        mLatencyTracker.logAction(action, 1234);
        assertThat(mLatencyTracker.getEventsWrittenToFrameworkStats(action)).hasSize(1);
        LatencyTracker.FrameworkStatsLogEvent frameworkStatsLog =
                mLatencyTracker.getEventsWrittenToFrameworkStats(action).get(0);
        assertThat(frameworkStatsLog.logCode).isEqualTo(UI_ACTION_LATENCY_REPORTED);
        assertThat(frameworkStatsLog.statsdAction).isEqualTo(STATSD_ACTION[action]);
        assertThat(frameworkStatsLog.durationMillis).isEqualTo(1234);

        mLatencyTracker.clearEvents();

        mLatencyTracker.onActionStart(action);
        mLatencyTracker.onActionEnd(action);
        // assert that action was logged, but we cannot confirm duration logged
        assertThat(mLatencyTracker.getEventsWrittenToFrameworkStats(action)).hasSize(1);
        frameworkStatsLog = mLatencyTracker.getEventsWrittenToFrameworkStats(action).get(0);
        assertThat(frameworkStatsLog.logCode).isEqualTo(UI_ACTION_LATENCY_REPORTED);
        assertThat(frameworkStatsLog.statsdAction).isEqualTo(STATSD_ACTION[action]);
    }

    @Test
    public void testDoesNotLogWhenDisabled() throws Exception {
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        DeviceConfig.setProperty(NAMESPACE_LATENCY_TRACKER, "action_show_voice_interaction_enable",
                "false", false);
        mLatencyTracker.waitForActionEnabledState(action, false);
        assertThat(mLatencyTracker.isEnabled(action)).isFalse();

        mLatencyTracker.logAction(action, 1234);
        assertThat(mLatencyTracker.getEventsWrittenToFrameworkStats(action)).isEmpty();

        mLatencyTracker.onActionStart(action);
        mLatencyTracker.onActionEnd(action);
        assertThat(mLatencyTracker.getEventsWrittenToFrameworkStats(action)).isEmpty();
    }

    @Test
    public void testOnActionEndDoesNotLogWithoutOnActionStart()
            throws Exception {
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        DeviceConfig.setProperty(NAMESPACE_LATENCY_TRACKER, "action_show_voice_interaction_enable",
                "true", false);
        mLatencyTracker.waitForActionEnabledState(action, true);
        assertThat(mLatencyTracker.isEnabled(action)).isTrue();

        mLatencyTracker.onActionEnd(action);
        assertThat(mLatencyTracker.getEventsWrittenToFrameworkStats(action)).isEmpty();
    }

    @Test
    public void testOnActionEndDoesNotLogWhenCanceled()
            throws Exception {
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        DeviceConfig.setProperty(NAMESPACE_LATENCY_TRACKER, "action_show_voice_interaction_enable",
                "true", false);
        mLatencyTracker.waitForActionEnabledState(action, true);
        assertThat(mLatencyTracker.isEnabled(action)).isTrue();

        mLatencyTracker.onActionStart(action);
        mLatencyTracker.onActionCancel(action);
        mLatencyTracker.onActionEnd(action);
        assertThat(mLatencyTracker.getEventsWrittenToFrameworkStats(action)).isEmpty();
    }

    @Test
    public void testNeverTriggersPerfettoWhenThresholdNegative()
            throws Exception {
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        Map<String, String> deviceConfigProperties = new HashMap<>();
        deviceConfigProperties.put("action_show_voice_interaction_enable", "true");
        deviceConfigProperties.put("action_show_voice_interaction_sample_interval", "1");
        deviceConfigProperties.put("action_show_voice_interaction_trace_threshold", "-1");
        DeviceConfig.setProperties(
                new DeviceConfig.Properties(NAMESPACE_LATENCY_TRACKER,
                        deviceConfigProperties));
        mLatencyTracker.waitForMatchingActionProperties(
                new ActionProperties(action, true /* enabled */, 1 /* samplingInterval */,
                        -1 /* traceThreshold */));

        mLatencyTracker.onActionStart(action);
        mLatencyTracker.onActionEnd(action);
        assertThat(mLatencyTracker.getTriggeredPerfettoTraceNames()).isEmpty();
    }

    @Test
    public void testNeverTriggersPerfettoWhenDisabled()
            throws Exception {
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        Map<String, String> deviceConfigProperties = new HashMap<>();
        deviceConfigProperties.put("action_show_voice_interaction_enable", "false");
        deviceConfigProperties.put("action_show_voice_interaction_sample_interval", "1");
        deviceConfigProperties.put("action_show_voice_interaction_trace_threshold", "1");
        DeviceConfig.setProperties(
                new DeviceConfig.Properties(NAMESPACE_LATENCY_TRACKER,
                        deviceConfigProperties));
        mLatencyTracker.waitForMatchingActionProperties(
                new ActionProperties(action, false /* enabled */, 1 /* samplingInterval */,
                        1 /* traceThreshold */));

        mLatencyTracker.onActionStart(action);
        mLatencyTracker.onActionEnd(action);
        assertThat(mLatencyTracker.getTriggeredPerfettoTraceNames()).isEmpty();
    }

    @Test
    public void testTriggersPerfettoWhenAboveThreshold()
            throws Exception {
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        Map<String, String> deviceConfigProperties = new HashMap<>();
        deviceConfigProperties.put("action_show_voice_interaction_enable", "true");
        deviceConfigProperties.put("action_show_voice_interaction_sample_interval", "1");
        deviceConfigProperties.put("action_show_voice_interaction_trace_threshold", "1");
        DeviceConfig.setProperties(
                new DeviceConfig.Properties(NAMESPACE_LATENCY_TRACKER,
                        deviceConfigProperties));
        mLatencyTracker.waitForMatchingActionProperties(
                new ActionProperties(action, true /* enabled */, 1 /* samplingInterval */,
                        1 /* traceThreshold */));

        mLatencyTracker.onActionStart(action);
        // We need to sleep here to ensure that the end call is past the set trace threshold (1ms)
        Thread.sleep(5 /* millis */);
        mLatencyTracker.onActionEnd(action);
        assertThat(mLatencyTracker.getTriggeredPerfettoTraceNames()).hasSize(1);
        assertThat(mLatencyTracker.getTriggeredPerfettoTraceNames().get(0)).isEqualTo(
                "com.android.telemetry.latency-tracker-ACTION_SHOW_VOICE_INTERACTION");
    }

    @Test
    public void testNeverTriggersPerfettoWhenBelowThreshold()
            throws Exception {
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        Map<String, String> deviceConfigProperties = new HashMap<>();
        deviceConfigProperties.put("action_show_voice_interaction_enable", "true");
        deviceConfigProperties.put("action_show_voice_interaction_sample_interval", "1");
        deviceConfigProperties.put("action_show_voice_interaction_trace_threshold", "1000");
        DeviceConfig.setProperties(
                new DeviceConfig.Properties(NAMESPACE_LATENCY_TRACKER,
                        deviceConfigProperties));
        mLatencyTracker.waitForMatchingActionProperties(
                new ActionProperties(action, true /* enabled */, 1 /* samplingInterval */,
                        1000 /* traceThreshold */));

        mLatencyTracker.onActionStart(action);
        // No sleep here to ensure that end call comes before 1000ms threshold
        mLatencyTracker.onActionEnd(action);
        assertThat(mLatencyTracker.getTriggeredPerfettoTraceNames()).isEmpty();
    }

    private List<Field> getAllActionFields() {
        return Arrays.stream(LatencyTracker.class.getDeclaredFields()).filter(
                field -> field.getName().startsWith("ACTION_") && Modifier.isStatic(
                        field.getModifiers()) && field.getType() == int.class).collect(
                Collectors.toList());
    }

    private int getIntFieldChecked(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }
}
