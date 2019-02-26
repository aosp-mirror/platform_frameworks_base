/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.util.leak;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ReferenceTestUtilsTest extends SysuiTestCase {

    @Test
    public void testCollectionWaiter_doesntBlockIndefinitely() {
        ReferenceTestUtils.createCollectionWaiter(new Object()).waitForCollection();
    }

    @Test
    public void testConditionWaiter_doesntBlockIndefinitely() {
        ReferenceTestUtils.waitForCondition(() -> true);
    }

    @Test
    public void testConditionWaiter_waitsUntilConditionIsTrue() {
        int[] countHolder = new int[]{0};

        ReferenceTestUtils.waitForCondition(() -> {
            countHolder[0] += 1;
            return countHolder[0] >= 5;
        });

        assertEquals(5, countHolder[0]);
    }
}
