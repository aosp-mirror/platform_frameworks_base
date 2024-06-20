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

package com.android.server.notification;

import static com.google.common.truth.Truth.assertThat;

import android.app.AutomaticZenRule;
import android.provider.Settings;
import android.service.notification.ZenPolicy;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.os.dnd.ActiveRuleType;
import com.android.os.dnd.ChannelPolicy;
import com.android.os.dnd.ConversationType;
import com.android.os.dnd.PeopleType;
import com.android.os.dnd.State;
import com.android.os.dnd.ZenMode;

import com.google.protobuf.Internal;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** Test to validate that logging enums used in Zen classes match their API definitions. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ZenEnumTest {

    @Test
    public void testEnum_zenMode() {
        testEnum(Settings.Global.class, "ZEN_MODE", ZenMode.class, "ZEN_MODE");
    }

    @Test
    public void testEnum_activeRuleType() {
        testEnum(AutomaticZenRule.class, "TYPE", ActiveRuleType.class, "TYPE");
    }

    @Test
    public void testEnum_zenPolicyState() {
        testEnum(ZenPolicy.class, "STATE", State.class, "STATE");
    }

    @Test
    public void testEnum_zenPolicyChannelPolicy() {
        testEnum(ZenPolicy.class, "CHANNEL_POLICY", ChannelPolicy.class, "CHANNEL_POLICY");
    }

    @Test
    public void testEnum_zenPolicyConversationType() {
        testEnum(ZenPolicy.class, "CONVERSATION_SENDERS", ConversationType.class, "CONV");
    }

    @Test
    public void testEnum_zenPolicyPeopleType() {
        testEnum(ZenPolicy.class, "PEOPLE_TYPE", PeopleType.class, "PEOPLE");
    }

    /**
     * Verifies that any constants (i.e. {@code public static final int} fields) named {@code
     * <apiPrefix>_SOMETHING} in {@code apiClass} are present and have the same numerical value
     * in the enum values defined in {@code loggingProtoEnumClass}.
     *
     * <p>Note that <em>extra</em> values in the logging enum are accepted (since we have one of
     * those, and the main goal of this test is that we don't forget to update the logging enum
     * if new API enum values are added).
     */
    private static void testEnum(Class<?> apiClass, String apiPrefix,
            Class<? extends Internal.EnumLite> loggingProtoEnumClass,
            String loggingPrefix) {
        Map<String, Integer> apiConstants =
                Arrays.stream(apiClass.getDeclaredFields())
                        .filter(f -> Modifier.isPublic(f.getModifiers()))
                        .filter(f -> Modifier.isStatic(f.getModifiers()))
                        .filter(f -> Modifier.isFinal(f.getModifiers()))
                        .filter(f -> f.getType().equals(int.class))
                        .filter(f -> f.getName().startsWith(apiPrefix + "_"))
                        .collect(Collectors.toMap(
                                Field::getName,
                                ZenEnumTest::getStaticFieldIntValue));

        Map<String, Integer> loggingConstants =
                Arrays.stream(loggingProtoEnumClass.getEnumConstants())
                        .collect(Collectors.toMap(
                                v -> v.toString(),
                                v -> v.getNumber()));

        Map<String, Integer> renamedApiConstants = apiConstants.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().replace(apiPrefix + "_", loggingPrefix + "_"),
                        Map.Entry::getValue));

        assertThat(loggingConstants).containsAtLeastEntriesIn(renamedApiConstants);
    }

    private static int getStaticFieldIntValue(Field f) {
        try {
            return f.getInt(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
