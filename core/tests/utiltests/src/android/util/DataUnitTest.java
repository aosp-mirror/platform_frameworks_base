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

package android.util;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DataUnitTest {
    @Test
    public void testSi() throws Exception {
        assertEquals(12_000L, DataUnit.KILOBYTES.toBytes(12));
        assertEquals(12_000_000L, DataUnit.MEGABYTES.toBytes(12));
        assertEquals(12_000_000_000L, DataUnit.GIGABYTES.toBytes(12));
        assertEquals(12_000_000_000_000L, DataUnit.TERABYTES.toBytes(12));
    }

    @Test
    public void testIec() throws Exception {
        assertEquals(12_288L, DataUnit.KIBIBYTES.toBytes(12));
        assertEquals(12_582_912L, DataUnit.MEBIBYTES.toBytes(12));
        assertEquals(12_884_901_888L, DataUnit.GIBIBYTES.toBytes(12));
        assertEquals(13_194_139_533_312L, DataUnit.TEBIBYTES.toBytes(12));
    }
}
