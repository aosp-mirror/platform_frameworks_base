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

package android.net.vcn;

import static com.android.testutils.ParcelUtils.assertParcelSane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.net.TelephonyNetworkSpecifier;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnUnderlyingNetworkSpecifierTest {
    private static final int[] TEST_SUB_IDS = new int[] {1, 2, 3, 5};

    @Test
    public void testGetSubIds() {
        final VcnUnderlyingNetworkSpecifier specifier =
                new VcnUnderlyingNetworkSpecifier(TEST_SUB_IDS);

        assertEquals(TEST_SUB_IDS, specifier.getSubIds());
    }

    @Test
    public void testParceling() {
        final VcnUnderlyingNetworkSpecifier specifier =
                new VcnUnderlyingNetworkSpecifier(TEST_SUB_IDS);
        assertParcelSane(specifier, 1);
    }

    @Test
    public void testCanBeSatisfiedByTelephonyNetworkSpecifier() {
        final TelephonyNetworkSpecifier telSpecifier =
                new TelephonyNetworkSpecifier(TEST_SUB_IDS[0]);

        final VcnUnderlyingNetworkSpecifier specifier =
                new VcnUnderlyingNetworkSpecifier(TEST_SUB_IDS);
        assertTrue(specifier.canBeSatisfiedBy(telSpecifier));
    }
}
