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

import androidx.test.filters.SmallTest;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SmallTest
public class LatencyTrackerTest {
    private static final String ENUM_NAME_PREFIX = "UIACTION_LATENCY_REPORTED__ACTION__";

    @Rule
    public final Expect mExpect = Expect.create();

    @Test
    public void testCujsMapToEnumsCorrectly() {
        List<Field> actions = Arrays.stream(LatencyTracker.class.getDeclaredFields())
                .filter(f -> f.getName().startsWith("ACTION_")
                        && Modifier.isStatic(f.getModifiers())
                        && f.getType() == int.class)
                .collect(Collectors.toList());

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
        List<Field> cujEnumFields =
                Arrays.stream(LatencyTracker.class.getDeclaredFields())
                        .filter(field -> field.getName().startsWith("ACTION_")
                                && Modifier.isStatic(field.getModifiers())
                                && field.getType() == int.class)
                        .collect(Collectors.toList());

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

    private int getIntFieldChecked(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }
}
