/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.apf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.net.shared.ParcelableTestUtil;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.TestUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ApfCapabilitiesTest {
    @Test
    public void testParcelUnparcel() {
        final ApfCapabilities caps = new ApfCapabilities(123, 456, 789);
        ParcelableTestUtil.assertFieldCountEquals(3, ApfCapabilities.class);

        TestUtils.assertParcelingIsLossless(caps, ApfCapabilities.CREATOR);
    }

    @Test
    public void testEquals() {
        assertEquals(new ApfCapabilities(1, 2, 3), new ApfCapabilities(1, 2, 3));
        assertNotEquals(new ApfCapabilities(2, 2, 3), new ApfCapabilities(1, 2, 3));
        assertNotEquals(new ApfCapabilities(1, 3, 3), new ApfCapabilities(1, 2, 3));
        assertNotEquals(new ApfCapabilities(1, 2, 4), new ApfCapabilities(1, 2, 3));
    }
}
