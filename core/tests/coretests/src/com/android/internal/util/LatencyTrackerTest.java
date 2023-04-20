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

import static android.text.TextUtils.formatSimple;

import static com.android.internal.util.LatencyTracker.STATSD_ACTION;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.provider.DeviceConfig;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class LatencyTrackerTest {
    private static final String TAG = LatencyTrackerTest.class.getSimpleName();
    private static final String ENUM_NAME_PREFIX = "UIACTION_LATENCY_REPORTED__ACTION__";
    private static final String ACTION_ENABLE_SUFFIX = "_enable";
    private static final Duration TEST_TIMEOUT = Duration.ofMillis(500);

    @Rule
    public final Expect mExpect = Expect.create();

    @Before
    public void setUp() {
        DeviceConfig.deleteProperty(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                LatencyTracker.SETTINGS_ENABLED_KEY);
        getAllActions().forEach(action -> {
            DeviceConfig.deleteProperty(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                    action.getName().toLowerCase() + ACTION_ENABLE_SUFFIX);
        });
    }

    @Test
    public void testCujsMapToEnumsCorrectly() {
        List<Field> actions = getAllActions();
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
        List<Field> cujEnumFields = getAllActions();
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
    public void testIsEnabled_globalEnabled() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                LatencyTracker.SETTINGS_ENABLED_KEY, "true", false);
        LatencyTracker latencyTracker = new LatencyTracker();
        waitForLatencyTrackerToUpdateProperties(latencyTracker);
        assertThat(latencyTracker.isEnabled()).isTrue();
    }

    @Test
    public void testIsEnabled_globalDisabled() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                LatencyTracker.SETTINGS_ENABLED_KEY, "false", false);
        LatencyTracker latencyTracker = new LatencyTracker();
        waitForLatencyTrackerToUpdateProperties(latencyTracker);
        assertThat(latencyTracker.isEnabled()).isFalse();
    }

    @Test
    public void testIsEnabledAction_useGlobalValueWhenActionEnableIsNotSet() {
        LatencyTracker latencyTracker = new LatencyTracker();
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        Log.i(TAG, "setting property=" + LatencyTracker.SETTINGS_ENABLED_KEY + ", value=true");
        latencyTracker.mDeviceConfigPropertiesUpdated.close();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                LatencyTracker.SETTINGS_ENABLED_KEY, "true", false);
        waitForLatencyTrackerToUpdateProperties(latencyTracker);
        assertThat(
                latencyTracker.isEnabled(action)).isTrue();

        Log.i(TAG, "setting property=" + LatencyTracker.SETTINGS_ENABLED_KEY
                + ", value=false");
        latencyTracker.mDeviceConfigPropertiesUpdated.close();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                LatencyTracker.SETTINGS_ENABLED_KEY, "false", false);
        waitForLatencyTrackerToUpdateProperties(latencyTracker);
        assertThat(latencyTracker.isEnabled(action)).isFalse();
    }

    @Test
    public void testIsEnabledAction_actionPropertyOverridesGlobalProperty()
            throws DeviceConfig.BadConfigException {
        LatencyTracker latencyTracker = new LatencyTracker();
        // using a single test action, but this applies to all actions
        int action = LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;
        String actionEnableProperty = "action_show_voice_interaction" + ACTION_ENABLE_SUFFIX;
        Log.i(TAG, "setting property=" + actionEnableProperty + ", value=true");

        latencyTracker.mDeviceConfigPropertiesUpdated.close();
        Map<String, String> properties = new HashMap<String, String>() {{
            put(LatencyTracker.SETTINGS_ENABLED_KEY, "false");
            put(actionEnableProperty, "true");
        }};
        DeviceConfig.setProperties(
                new DeviceConfig.Properties(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                        properties));
        waitForLatencyTrackerToUpdateProperties(latencyTracker);
        assertThat(latencyTracker.isEnabled(action)).isTrue();

        latencyTracker.mDeviceConfigPropertiesUpdated.close();
        Log.i(TAG, "setting property=" + actionEnableProperty + ", value=false");
        properties.put(LatencyTracker.SETTINGS_ENABLED_KEY, "true");
        properties.put(actionEnableProperty, "false");
        DeviceConfig.setProperties(
                    new DeviceConfig.Properties(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                            properties));
        waitForLatencyTrackerToUpdateProperties(latencyTracker);
        assertThat(latencyTracker.isEnabled(action)).isFalse();
    }

    private void waitForLatencyTrackerToUpdateProperties(LatencyTracker latencyTracker) {
        try {
            Thread.sleep(TEST_TIMEOUT.toMillis());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        assertThat(latencyTracker.mDeviceConfigPropertiesUpdated.block(
                TEST_TIMEOUT.toMillis())).isTrue();
    }

    private List<Field> getAllActions() {
        return Arrays.stream(LatencyTracker.class.getDeclaredFields())
                .filter(field -> field.getName().startsWith("ACTION_")
                        && Modifier.isStatic(field.getModifiers())
                        && field.getType() == int.class)
                .collect(Collectors.toList());
    }

    private int getIntFieldChecked(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }
}
