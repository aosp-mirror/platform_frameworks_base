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

package com.android.systemui.flags;

import static com.google.common.truth.Truth.assertWithMessage;

import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SmallTest
public class FlagsTest extends SysuiTestCase {

    @Test
    public void testDuplicateFlagIdCheckWorks() {
        List<Pair<String, Flag<?>>> flags = collectFlags(DuplicateFlagContainer.class);
        Map<Integer, List<String>> duplicates = groupDuplicateFlags(flags);

        assertWithMessage(generateAssertionMessage(duplicates))
                .that(duplicates.size()).isEqualTo(2);
    }

    @Test
    public void testNoDuplicateFlagIds() {
        List<Pair<String, Flag<?>>> flags = collectFlags(Flags.class);
        Map<Integer, List<String>> duplicates = groupDuplicateFlags(flags);

        assertWithMessage(generateAssertionMessage(duplicates))
                .that(duplicates.size()).isEqualTo(0);
    }

    private String generateAssertionMessage(Map<Integer, List<String>> duplicates) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Duplicate flag keys found: {");
        for (int id : duplicates.keySet()) {
            stringBuilder
                    .append(" ")
                    .append(id)
                    .append(": [")
                    .append(String.join(", ", duplicates.get(id)))
                    .append("]");
        }
        stringBuilder.append(" }");

        return stringBuilder.toString();
    }

    private List<Pair<String, Flag<?>>> collectFlags(Class<?> clz) {
        List<Pair<String, Flag<?>>> flags = new ArrayList<>();

        Field[] fields = clz.getFields();

        for (Field field : fields) {
            Class<?> t = field.getType();
            if (Flag.class.isAssignableFrom(t)) {
                try {
                    flags.add(Pair.create(field.getName(), (Flag<?>) field.get(null)));
                } catch (IllegalAccessException e) {
                    // no-op
                }
            }
        }

        return flags;
    }

    private Map<Integer, List<String>> groupDuplicateFlags(List<Pair<String, Flag<?>>> flags) {
        Map<Integer, List<String>> grouping = new HashMap<>();

        for (Pair<String, Flag<?>> flag : flags) {
            grouping.putIfAbsent(flag.second.getId(), new ArrayList<>());
            grouping.get(flag.second.getId()).add(flag.first);
        }

        Map<Integer, List<String>> result = new HashMap<>();
        for (Integer id : grouping.keySet()) {
            if (grouping.get(id).size() > 1) {
                result.put(id, grouping.get(id));
            }
        }

        return result;
    }

    private static class DuplicateFlagContainer {
        public static final BooleanFlag A_FLAG = new BooleanFlag(0);
        public static final BooleanFlag B_FLAG = new BooleanFlag(0);
        public static final StringFlag C_FLAG = new StringFlag(0);

        public static final BooleanFlag D_FLAG = new BooleanFlag(1);

        public static final DoubleFlag E_FLAG = new DoubleFlag(3);
        public static final DoubleFlag F_FLAG = new DoubleFlag(3);
    }
}
