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
package com.android.server.am;

import static android.content.IntentFilter.SYSTEM_HIGH_PRIORITY;
import static android.content.IntentFilter.SYSTEM_LOW_PRIORITY;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.server.compat.PlatformCompat;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class BroadcastFilterTest {
    private static final int TEST_APP_UID = Process.FIRST_APPLICATION_UID + 42;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    PlatformCompat mPlatformCompat;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        doReturn(true).when(mPlatformCompat).isChangeEnabledInternalNoLogging(
                eq(BroadcastFilter.RESTRICT_PRIORITY_VALUES), any(ApplicationInfo.class));
    }

    @Test
    @EnableFlags(Flags.FLAG_RESTRICT_PRIORITY_VALUES)
    public void testCalculateAdjustedPriority() {
        {
            // Pairs of {initial-priority, expected-adjusted-priority}
            final Pair<Integer, Integer>[] priorities = new Pair[] {
                    Pair.create(SYSTEM_HIGH_PRIORITY, SYSTEM_HIGH_PRIORITY),
                    Pair.create(SYSTEM_LOW_PRIORITY, SYSTEM_LOW_PRIORITY),
                    Pair.create(SYSTEM_HIGH_PRIORITY + 1, SYSTEM_HIGH_PRIORITY + 1),
                    Pair.create(SYSTEM_LOW_PRIORITY - 1, SYSTEM_LOW_PRIORITY - 1),
                    Pair.create(SYSTEM_HIGH_PRIORITY - 2, SYSTEM_HIGH_PRIORITY - 2),
                    Pair.create(SYSTEM_LOW_PRIORITY + 2, SYSTEM_LOW_PRIORITY + 2)
            };
            for (Pair<Integer, Integer> priorityPair : priorities) {
                assertAdjustedPriorityForSystemUid(priorityPair.first, priorityPair.second);
            }
        }

        {
            // Pairs of {initial-priority, expected-adjusted-priority}
            final Pair<Integer, Integer>[] priorities = new Pair[] {
                    Pair.create(SYSTEM_HIGH_PRIORITY, SYSTEM_HIGH_PRIORITY - 1),
                    Pair.create(SYSTEM_LOW_PRIORITY, SYSTEM_LOW_PRIORITY + 1),
                    Pair.create(SYSTEM_HIGH_PRIORITY + 1, SYSTEM_HIGH_PRIORITY - 1),
                    Pair.create(SYSTEM_LOW_PRIORITY - 1, SYSTEM_LOW_PRIORITY + 1),
                    Pair.create(SYSTEM_HIGH_PRIORITY - 2, SYSTEM_HIGH_PRIORITY - 2),
                    Pair.create(SYSTEM_LOW_PRIORITY + 2, SYSTEM_LOW_PRIORITY + 2)
            };
            for (Pair<Integer, Integer> priorityPair : priorities) {
                assertAdjustedPriorityForAppUid(priorityPair.first, priorityPair.second);
            }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_RESTRICT_PRIORITY_VALUES)
    public void testCalculateAdjustedPriority_withChangeIdDisabled() {
        doReturn(false).when(mPlatformCompat).isChangeEnabledInternalNoLogging(
                eq(BroadcastFilter.RESTRICT_PRIORITY_VALUES), any(ApplicationInfo.class));

        {
            // Pairs of {initial-priority, expected-adjusted-priority}
            final Pair<Integer, Integer>[] priorities = new Pair[] {
                    Pair.create(SYSTEM_HIGH_PRIORITY, SYSTEM_HIGH_PRIORITY),
                    Pair.create(SYSTEM_LOW_PRIORITY, SYSTEM_LOW_PRIORITY),
                    Pair.create(SYSTEM_HIGH_PRIORITY + 1, SYSTEM_HIGH_PRIORITY + 1),
                    Pair.create(SYSTEM_LOW_PRIORITY - 1, SYSTEM_LOW_PRIORITY - 1),
                    Pair.create(SYSTEM_HIGH_PRIORITY - 2, SYSTEM_HIGH_PRIORITY - 2),
                    Pair.create(SYSTEM_LOW_PRIORITY + 2, SYSTEM_LOW_PRIORITY + 2)
            };
            for (Pair<Integer, Integer> priorityPair : priorities) {
                assertAdjustedPriorityForSystemUid(priorityPair.first, priorityPair.second);
            }
        }

        {
            // Pairs of {initial-priority, expected-adjusted-priority}
            final Pair<Integer, Integer>[] priorities = new Pair[] {
                    Pair.create(SYSTEM_HIGH_PRIORITY, SYSTEM_HIGH_PRIORITY),
                    Pair.create(SYSTEM_LOW_PRIORITY, SYSTEM_LOW_PRIORITY),
                    Pair.create(SYSTEM_HIGH_PRIORITY + 1, SYSTEM_HIGH_PRIORITY + 1),
                    Pair.create(SYSTEM_LOW_PRIORITY - 1, SYSTEM_LOW_PRIORITY - 1),
                    Pair.create(SYSTEM_HIGH_PRIORITY - 2, SYSTEM_HIGH_PRIORITY - 2),
                    Pair.create(SYSTEM_LOW_PRIORITY + 2, SYSTEM_LOW_PRIORITY + 2)
            };
            for (Pair<Integer, Integer> priorityPair : priorities) {
                assertAdjustedPriorityForAppUid(priorityPair.first, priorityPair.second);
            }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_RESTRICT_PRIORITY_VALUES)
    public void testCalculateAdjustedPriority_withFlagDisabled() {
        {
            // Pairs of {initial-priority, expected-adjusted-priority}
            final Pair<Integer, Integer>[] priorities = new Pair[] {
                    Pair.create(SYSTEM_HIGH_PRIORITY, SYSTEM_HIGH_PRIORITY),
                    Pair.create(SYSTEM_LOW_PRIORITY, SYSTEM_LOW_PRIORITY),
                    Pair.create(SYSTEM_HIGH_PRIORITY + 1, SYSTEM_HIGH_PRIORITY + 1),
                    Pair.create(SYSTEM_LOW_PRIORITY - 1, SYSTEM_LOW_PRIORITY - 1),
                    Pair.create(SYSTEM_HIGH_PRIORITY - 2, SYSTEM_HIGH_PRIORITY - 2),
                    Pair.create(SYSTEM_LOW_PRIORITY + 2, SYSTEM_LOW_PRIORITY + 2)
            };
            for (Pair<Integer, Integer> priorityPair : priorities) {
                assertAdjustedPriorityForSystemUid(priorityPair.first, priorityPair.second);
            }
        }

        {
            // Pairs of {initial-priority, expected-adjusted-priority}
            final Pair<Integer, Integer>[] priorities = new Pair[] {
                    Pair.create(SYSTEM_HIGH_PRIORITY, SYSTEM_HIGH_PRIORITY),
                    Pair.create(SYSTEM_LOW_PRIORITY, SYSTEM_LOW_PRIORITY),
                    Pair.create(SYSTEM_HIGH_PRIORITY + 1, SYSTEM_HIGH_PRIORITY + 1),
                    Pair.create(SYSTEM_LOW_PRIORITY - 1, SYSTEM_LOW_PRIORITY - 1),
                    Pair.create(SYSTEM_HIGH_PRIORITY - 2, SYSTEM_HIGH_PRIORITY - 2),
                    Pair.create(SYSTEM_LOW_PRIORITY + 2, SYSTEM_LOW_PRIORITY + 2)
            };
            for (Pair<Integer, Integer> priorityPair : priorities) {
                assertAdjustedPriorityForAppUid(priorityPair.first, priorityPair.second);
            }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_RESTRICT_PRIORITY_VALUES)
    public void testCalculateAdjustedPriority_withFlagDisabled_withChangeIdDisabled() {
        doReturn(false).when(mPlatformCompat).isChangeEnabledByUidInternalNoLogging(
                eq(BroadcastFilter.RESTRICT_PRIORITY_VALUES), anyInt());

        {
            // Pairs of {initial-priority, expected-adjusted-priority}
            final Pair<Integer, Integer>[] priorities = new Pair[] {
                    Pair.create(SYSTEM_HIGH_PRIORITY, SYSTEM_HIGH_PRIORITY),
                    Pair.create(SYSTEM_LOW_PRIORITY, SYSTEM_LOW_PRIORITY),
                    Pair.create(SYSTEM_HIGH_PRIORITY + 1, SYSTEM_HIGH_PRIORITY + 1),
                    Pair.create(SYSTEM_LOW_PRIORITY - 1, SYSTEM_LOW_PRIORITY - 1),
                    Pair.create(SYSTEM_HIGH_PRIORITY - 2, SYSTEM_HIGH_PRIORITY - 2),
                    Pair.create(SYSTEM_LOW_PRIORITY + 2, SYSTEM_LOW_PRIORITY + 2)
            };
            for (Pair<Integer, Integer> priorityPair : priorities) {
                assertAdjustedPriorityForSystemUid(priorityPair.first, priorityPair.second);
            }
        }

        {
            // Pairs of {initial-priority, expected-adjusted-priority}
            final Pair<Integer, Integer>[] priorities = new Pair[] {
                    Pair.create(SYSTEM_HIGH_PRIORITY, SYSTEM_HIGH_PRIORITY),
                    Pair.create(SYSTEM_LOW_PRIORITY, SYSTEM_LOW_PRIORITY),
                    Pair.create(SYSTEM_HIGH_PRIORITY + 1, SYSTEM_HIGH_PRIORITY + 1),
                    Pair.create(SYSTEM_LOW_PRIORITY - 1, SYSTEM_LOW_PRIORITY - 1),
                    Pair.create(SYSTEM_HIGH_PRIORITY - 2, SYSTEM_HIGH_PRIORITY - 2),
                    Pair.create(SYSTEM_LOW_PRIORITY + 2, SYSTEM_LOW_PRIORITY + 2)
            };
            for (Pair<Integer, Integer> priorityPair : priorities) {
                assertAdjustedPriorityForAppUid(priorityPair.first, priorityPair.second);
            }
        }
    }

    private void assertAdjustedPriorityForSystemUid(int priority, int expectedAdjustedPriority) {
        assertAdjustedPriority(Process.SYSTEM_UID, priority, expectedAdjustedPriority);
    }

    private void assertAdjustedPriorityForAppUid(int priority, int expectedAdjustedPriority) {
        assertAdjustedPriority(TEST_APP_UID, priority, expectedAdjustedPriority);
    }

    private void assertAdjustedPriority(int owningUid, int priority, int expectedAdjustedPriority) {
        final String errorMsg = String.format("owner=%d; actualPriority=%d; expectedPriority=%d",
                owningUid, priority, expectedAdjustedPriority);
        assertWithMessage(errorMsg).that(BroadcastFilter.calculateAdjustedPriority(
                owningUid, priority, mock(ApplicationInfo.class), mPlatformCompat))
                        .isEqualTo(expectedAdjustedPriority);
    }
}
