/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net;

import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UidRangeTest {

  /*
   * UidRange is no longer passed to netd. UID ranges between the framework and netd are passed as
   * UidRangeParcel objects.
   */

    @Test
    public void testSingleItemUidRangeAllowed() {
        new UidRange(123, 123);
        new UidRange(0, 0);
        new UidRange(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Test
    public void testNegativeUidsDisallowed() {
        try {
            new UidRange(-2, 100);
            fail("Exception not thrown for negative start UID");
        } catch (IllegalArgumentException expected) {
        }

        try {
            new UidRange(-200, -100);
            fail("Exception not thrown for negative stop UID");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testStopLessThanStartDisallowed() {
        final int x = 4195000;
        try {
            new UidRange(x, x - 1);
            fail("Exception not thrown for negative-length UID range");
        } catch (IllegalArgumentException expected) {
        }
    }
}