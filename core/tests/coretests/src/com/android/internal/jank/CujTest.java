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

package com.android.internal.jank;

import static android.text.TextUtils.formatSimple;

import static com.android.internal.jank.Cuj.getNameOfCuj;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import androidx.test.filters.SmallTest;

import com.android.internal.util.FrameworkStatsLog;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SmallTest
public class CujTest {
    private static final String ENUM_NAME_PREFIX =
            "UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__";
    private static final Set<String> DEPRECATED_VALUES = Set.of(
            ENUM_NAME_PREFIX + "IME_INSETS_ANIMATION"
    );
    private static final Map<Integer, String> ENUM_NAME_EXCEPTION_MAP = Map.ofEntries(
            Map.entry(Cuj.CUJ_NOTIFICATION_ADD, getEnumName("SHADE_NOTIFICATION_ADD")),
            Map.entry(Cuj.CUJ_NOTIFICATION_HEADS_UP_APPEAR, getEnumName("SHADE_HEADS_UP_APPEAR")),
            Map.entry(Cuj.CUJ_NOTIFICATION_APP_START, getEnumName("SHADE_APP_LAUNCH")),
            Map.entry(
                    Cuj.CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR,
                    getEnumName("SHADE_HEADS_UP_DISAPPEAR")),
            Map.entry(Cuj.CUJ_NOTIFICATION_REMOVE, getEnumName("SHADE_NOTIFICATION_REMOVE")),
            Map.entry(
                    Cuj.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE,
                    getEnumName("NOTIFICATION_SHADE_SWIPE")),
            Map.entry(
                        Cuj.CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE,
                        getEnumName("SHADE_QS_EXPAND_COLLAPSE")),
            Map.entry(
                    Cuj.CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE,
                    getEnumName("SHADE_QS_SCROLL_SWIPE")),
            Map.entry(Cuj.CUJ_NOTIFICATION_SHADE_ROW_EXPAND, getEnumName("SHADE_ROW_EXPAND")),
            Map.entry(Cuj.CUJ_NOTIFICATION_SHADE_ROW_SWIPE, getEnumName("SHADE_ROW_SWIPE")),
            Map.entry(Cuj.CUJ_NOTIFICATION_SHADE_SCROLL_FLING, getEnumName("SHADE_SCROLL_FLING"))
    );

    @Rule
    public final Expect mExpect = Expect.create();

    @Test
    public void testCujNameLimit() {
        getCujConstants().forEach(f -> {
            final int cuj = getIntFieldChecked(f);
            mExpect.withMessage(formatSimple("Too long CUJ(%d) name: %s", cuj, getNameOfCuj(cuj)))
                    .that(getNameOfCuj(cuj).length())
                    .isAtMost(Cuj.MAX_LENGTH_OF_CUJ_NAME);
        });
    }

    @Test
    public void testCujTypeEnumCorrectlyDefined() throws Exception {
        List<Field> cujEnumFields = getCujConstants().toList();

        HashSet<Integer> allValues = new HashSet<>();
        for (Field field : cujEnumFields) {
            int fieldValue = field.getInt(null);
            assertWithMessage("All CujType values must be unique. Field %s repeats existing value.",
                    field.getName())
                    .that(allValues.add(fieldValue))
                    .isTrue();
            assertWithMessage("Field %s must have a value <= LAST_CUJ", field.getName())
                    .that(fieldValue)
                    .isAtMost(Cuj.LAST_CUJ);
            assertWithMessage("Field %s must have a statsd mapping.", field.getName())
                    .that(Cuj.logToStatsd(fieldValue))
                    .isTrue();
        }
    }

    @Test
    public void testCujsMapToEnumsCorrectly() {
        List<Field> cujs = getCujConstants().toList();

        Map<Integer, String> enumsMap = Arrays.stream(FrameworkStatsLog.class.getDeclaredFields())
                .filter(f -> f.getName().startsWith(ENUM_NAME_PREFIX)
                        && !DEPRECATED_VALUES.contains(f.getName())
                        && Modifier.isStatic(f.getModifiers())
                        && f.getType() == int.class)
                .collect(Collectors.toMap(CujTest::getIntFieldChecked, Field::getName));

        assertThat(enumsMap.size() - 1).isEqualTo(cujs.size());

        cujs.forEach(f -> {
            final int cuj = getIntFieldChecked(f);
            final String cujName = f.getName();
            final String expectedEnumName =
                    ENUM_NAME_EXCEPTION_MAP.getOrDefault(cuj, getEnumName(cujName.substring(4)));
            final int enumKey = Cuj.getStatsdInteractionType(cuj);
            final String enumName = enumsMap.get(enumKey);
            final String expectedNameOfCuj = formatSimple("CUJ_%s", getNameOfCuj(cuj));

            mExpect.withMessage(
                    formatSimple("%s (%d) not matches %s (%d)", cujName, cuj, enumName, enumKey))
                    .that(expectedEnumName.equals(enumName))
                    .isTrue();
            mExpect.withMessage(
                    formatSimple("getNameOfCuj(%d) not matches: %s, expected=%s",
                            cuj, cujName, expectedNameOfCuj))
                    .that(cujName.equals(expectedNameOfCuj))
                    .isTrue();
        });
    }

    private static String getEnumName(String name) {
        return formatSimple("%s%s", ENUM_NAME_PREFIX, name);
    }

    private static int getIntFieldChecked(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Stream<Field> getCujConstants() {
        return Arrays.stream(Cuj.class.getDeclaredFields())
                .filter(f -> f.getName().startsWith("CUJ_")
                        && Modifier.isStatic(f.getModifiers())
                        && f.getType() == int.class);
    }
}
