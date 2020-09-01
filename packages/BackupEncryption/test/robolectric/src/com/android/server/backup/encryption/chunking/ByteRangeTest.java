/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.chunking;

import static org.junit.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link ByteRange}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class ByteRangeTest {
    @Test
    public void getLength_includesEnd() throws Exception {
        ByteRange byteRange = new ByteRange(5, 10);

        int length = byteRange.getLength();

        assertEquals(6, length);
    }

    @Test
    public void constructor_rejectsNegativeStart() {
        assertThrows(IllegalArgumentException.class, () -> new ByteRange(-1, 10));
    }

    @Test
    public void constructor_rejectsEndBeforeStart() {
        assertThrows(IllegalArgumentException.class, () -> new ByteRange(10, 9));
    }

    @Test
    public void extend_withZeroLength_throwsException() {
        ByteRange byteRange = new ByteRange(5, 10);

        assertThrows(IllegalArgumentException.class, () -> byteRange.extend(0));
    }
}
