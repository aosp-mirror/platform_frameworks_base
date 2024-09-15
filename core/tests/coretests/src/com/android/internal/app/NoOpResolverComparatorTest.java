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

package com.android.internal.app;

import static com.google.common.truth.Truth.assertThat;

import android.content.Intent;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Unit tests for the behavior of {@link NoOpResolverComparator}. */
@RunWith(AndroidJUnit4.class)
public class NoOpResolverComparatorTest {

    private static final UserHandle PERSONAL_USER_HANDLE = InstrumentationRegistry
            .getInstrumentation().getTargetContext().getUser();

    public final ResolvedComponentInfo resolution1 =
            ResolverDataProvider.createResolvedComponentInfo(1, PERSONAL_USER_HANDLE);
    public final ResolvedComponentInfo resolution2 =
            ResolverDataProvider.createResolvedComponentInfo(2, PERSONAL_USER_HANDLE);
    public final ResolvedComponentInfo resolution3 =
            ResolverDataProvider.createResolvedComponentInfo(3, PERSONAL_USER_HANDLE);
    public final ResolvedComponentInfo resolution4 =
            ResolverDataProvider.createResolvedComponentInfo(4, PERSONAL_USER_HANDLE);

    private NoOpResolverComparator mComparator;

    @Before
    public void setUp() {
        mComparator = new NoOpResolverComparator(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                new Intent(),
                List.of(PERSONAL_USER_HANDLE));
    }

    @Test
    public void testKnownItemsSortInOriginalOrder() {
        List<ResolvedComponentInfo> originalOrder = List.of(resolution1, resolution2, resolution3);
        mComparator.doCompute(originalOrder);

        List<ResolvedComponentInfo> queryOrder = new ArrayList<>(
                List.of(resolution2, resolution3, resolution1));

        Collections.sort(queryOrder, mComparator);
        assertThat(queryOrder).isEqualTo(originalOrder);
    }

    @Test
    public void testUnknownItemsSortAfterKnownItems() {
        List<ResolvedComponentInfo> originalOrder = List.of(resolution1, resolution2);
        mComparator.doCompute(originalOrder);

        // Query includes the unknown `resolution4`.
        List<ResolvedComponentInfo> queryOrder = new ArrayList<>(
                List.of(resolution2, resolution4, resolution1));
        Collections.sort(queryOrder, mComparator);

        assertThat(queryOrder).isEqualTo(List.of(resolution1, resolution2, resolution4));
    }

    @Test
    public void testKnownItemsGetNonZeroScoresInOrder() {
        List<ResolvedComponentInfo> originalOrder = List.of(resolution1, resolution2);
        mComparator.doCompute(originalOrder);

        float score1 = mComparator.getScore(resolution1.getResolveInfoAt(0));
        float score2 = mComparator.getScore(resolution2.getResolveInfoAt(0));

        assertThat(score1).isEqualTo(1.0f);
        assertThat(score2).isLessThan(score1);
        assertThat(score2).isGreaterThan(0.0f);
    }

    @Test
    public void testUnknownItemsGetZeroScore() {
        List<ResolvedComponentInfo> originalOrder = List.of(resolution1, resolution2);
        mComparator.doCompute(originalOrder);

        assertThat(mComparator.getScore(resolution3.getResolveInfoAt(0))).isEqualTo(0.0f);
    }
}
