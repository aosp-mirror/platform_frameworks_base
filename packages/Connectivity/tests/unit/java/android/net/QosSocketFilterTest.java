/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.net.InetSocketAddress;

@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SmallTest
public class QosSocketFilterTest {

    @Test
    public void testPortExactMatch() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("1.2.3.4");
        assertTrue(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 10), addressB, 10, 10));

    }

    @Test
    public void testPortLessThanStart() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("1.2.3.4");
        assertFalse(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 8), addressB, 10, 10));
    }

    @Test
    public void testPortGreaterThanEnd() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("1.2.3.4");
        assertFalse(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 18), addressB, 10, 10));
    }

    @Test
    public void testPortBetweenStartAndEnd() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("1.2.3.4");
        assertTrue(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 10), addressB, 8, 18));
    }

    @Test
    public void testAddressesDontMatch() {
        final InetAddress addressA = InetAddresses.parseNumericAddress("1.2.3.4");
        final InetAddress addressB = InetAddresses.parseNumericAddress("1.2.3.5");
        assertFalse(QosSocketFilter.matchesAddress(
                new InetSocketAddress(addressA, 10), addressB, 10, 10));
    }
}

