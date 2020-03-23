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

import static com.android.testutils.ParcelUtilsKt.assertParcelSane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ApfCapabilitiesTest {
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void testConstructAndParcel() {
        final ApfCapabilities caps = new ApfCapabilities(123, 456, 789);
        assertEquals(123, caps.apfVersionSupported);
        assertEquals(456, caps.maximumApfProgramSize);
        assertEquals(789, caps.apfPacketFormat);

        assertParcelSane(caps, 3);
    }

    @Test
    public void testEquals() {
        assertEquals(new ApfCapabilities(1, 2, 3), new ApfCapabilities(1, 2, 3));
        assertNotEquals(new ApfCapabilities(2, 2, 3), new ApfCapabilities(1, 2, 3));
        assertNotEquals(new ApfCapabilities(1, 3, 3), new ApfCapabilities(1, 2, 3));
        assertNotEquals(new ApfCapabilities(1, 2, 4), new ApfCapabilities(1, 2, 3));
    }

    @Test
    public void testHasDataAccess() {
        //hasDataAccess is only supported starting at apf version 4.
        ApfCapabilities caps = new ApfCapabilities(1 /* apfVersionSupported */, 2, 3);
        assertFalse(caps.hasDataAccess());

        caps = new ApfCapabilities(4 /* apfVersionSupported */, 5, 6);
        assertTrue(caps.hasDataAccess());
    }

    @Test
    public void testGetApfDrop8023Frames() {
        // Get com.android.internal.R.bool.config_apfDrop802_3Frames. The test cannot directly
        // use R.bool.config_apfDrop802_3Frames because that is not a stable resource ID.
        final int resId = mContext.getResources().getIdentifier("config_apfDrop802_3Frames",
                "bool", "android");
        final boolean shouldDrop8023Frames = mContext.getResources().getBoolean(resId);
        final boolean actual = ApfCapabilities.getApfDrop8023Frames();
        assertEquals(shouldDrop8023Frames, actual);
    }

    @Test
    public void testGetApfEtherTypeBlackList() {
        // Get com.android.internal.R.array.config_apfEthTypeBlackList. The test cannot directly
        // use R.array.config_apfEthTypeBlackList because that is not a stable resource ID.
        final int resId = mContext.getResources().getIdentifier("config_apfEthTypeBlackList",
                "array", "android");
        final int[] blacklistedEtherTypeArray = mContext.getResources().getIntArray(resId);
        final int[] actual = ApfCapabilities.getApfEtherTypeBlackList();
        assertNotNull(actual);
        assertTrue(Arrays.equals(blacklistedEtherTypeArray, actual));
    }
}
