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

package com.android.server.net.ipmemorystore;

import static org.junit.Assert.assertEquals;

import android.net.ipmemorystore.NetworkAttributes;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.Arrays;

/** Unit tests for {@link NetworkAttributes}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class NetworkAttributesTest {
    private static final String WEIGHT_FIELD_NAME_PREFIX = "WEIGHT_";
    private static final float EPSILON = 0.0001f;

    // This is running two tests to make sure the total weight is the sum of all weights. To be
    // sure this is not fireproof, but you'd kind of need to do it on purpose to pass.
    @Test
    public void testTotalWeight() throws IllegalAccessException, UnknownHostException {
        // Make sure that TOTAL_WEIGHT is equal to the sum of the fields starting with WEIGHT_
        float sum = 0f;
        final Field[] fieldList = NetworkAttributes.class.getDeclaredFields();
        for (final Field field : fieldList) {
            if (!field.getName().startsWith(WEIGHT_FIELD_NAME_PREFIX)) continue;
            field.setAccessible(true);
            sum += (float) field.get(null);
        }
        assertEquals(sum, NetworkAttributes.TOTAL_WEIGHT, EPSILON);

        // Use directly the constructor with all attributes, and make sure that when compared
        // to itself the score is a clean 1.0f.
        final NetworkAttributes na =
                new NetworkAttributes(
                        (Inet4Address) Inet4Address.getByAddress(new byte[] {1, 2, 3, 4}),
                        "some hint",
                        Arrays.asList(Inet4Address.getByAddress(new byte[] {5, 6, 7, 8}),
                                Inet4Address.getByAddress(new byte[] {9, 0, 1, 2})),
                        98);
        assertEquals(1.0f, na.getNetworkGroupSamenessConfidence(na), EPSILON);
    }
}
