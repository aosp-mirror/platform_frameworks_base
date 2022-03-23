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

package android.os;

import static com.android.internal.util.HexDump.hexStringToByteArray;

import android.bluetooth.BluetoothUuid;
import android.net.MacAddress;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SmallTest
public class BytesMatcherTest extends TestCase {
    @Test
    public void testEmpty() throws Exception {
        BytesMatcher matcher = BytesMatcher.decode("");
        assertFalse(matcher.test(hexStringToByteArray("cafe")));
        assertFalse(matcher.test(hexStringToByteArray("")));
    }

    @Test
    public void testExact() throws Exception {
        BytesMatcher matcher = BytesMatcher.decode("+cafe");
        assertTrue(matcher.test(hexStringToByteArray("cafe")));
        assertFalse(matcher.test(hexStringToByteArray("beef")));
        assertFalse(matcher.test(hexStringToByteArray("ca")));
        assertFalse(matcher.test(hexStringToByteArray("cafe00")));
    }

    @Test
    public void testMask() throws Exception {
        BytesMatcher matcher = BytesMatcher.decode("+cafe/ff00");
        assertTrue(matcher.test(hexStringToByteArray("cafe")));
        assertTrue(matcher.test(hexStringToByteArray("ca88")));
        assertFalse(matcher.test(hexStringToByteArray("beef")));
        assertFalse(matcher.test(hexStringToByteArray("ca")));
        assertFalse(matcher.test(hexStringToByteArray("cafe00")));
    }

    @Test
    public void testPrefix() throws Exception {
        BytesMatcher matcher = BytesMatcher.decode("⊆cafe,⊆beef/ff00");
        assertTrue(matcher.test(hexStringToByteArray("cafe")));
        assertFalse(matcher.test(hexStringToByteArray("caff")));
        assertTrue(matcher.test(hexStringToByteArray("cafecafe")));
        assertFalse(matcher.test(hexStringToByteArray("ca")));
        assertTrue(matcher.test(hexStringToByteArray("beef")));
        assertTrue(matcher.test(hexStringToByteArray("beff")));
        assertTrue(matcher.test(hexStringToByteArray("beffbeff")));
        assertFalse(matcher.test(hexStringToByteArray("be")));
    }

    @Test
    public void testMacAddress() throws Exception {
        BytesMatcher matcher = BytesMatcher.decode("+cafe00112233/ffffff000000");
        assertTrue(matcher.testMacAddress(
                MacAddress.fromString("ca:fe:00:00:00:00")));
        assertFalse(matcher.testMacAddress(
                MacAddress.fromString("f0:0d:00:00:00:00")));
    }

    @Test
    public void testBluetoothUuid() throws Exception {
        BytesMatcher matcher = BytesMatcher.decode("+cafe/ff00");
        assertTrue(matcher.testBluetoothUuid(
                BluetoothUuid.parseUuidFrom(hexStringToByteArray("cafe"))));
        assertFalse(matcher.testBluetoothUuid(
                BluetoothUuid.parseUuidFrom(hexStringToByteArray("beef"))));
    }

    /**
     * Verify that single matcher can be configured to match Bluetooth UUIDs of
     * varying lengths.
     */
    @Test
    public void testBluetoothUuid_Mixed() throws Exception {
        BytesMatcher matcher = BytesMatcher.decode("+aaaa/ff00,+bbbbbbbb/ffff0000");
        assertTrue(matcher.testBluetoothUuid(
                BluetoothUuid.parseUuidFrom(hexStringToByteArray("aaaa"))));
        assertFalse(matcher.testBluetoothUuid(
                BluetoothUuid.parseUuidFrom(hexStringToByteArray("bbbb"))));
        assertTrue(matcher.testBluetoothUuid(
                BluetoothUuid.parseUuidFrom(hexStringToByteArray("bbbbbbbb"))));
        assertFalse(matcher.testBluetoothUuid(
                BluetoothUuid.parseUuidFrom(hexStringToByteArray("aaaaaaaa"))));
    }

    @Test
    public void testSerialize_Empty() throws Exception {
        BytesMatcher matcher = new BytesMatcher();
        matcher = BytesMatcher.decode(BytesMatcher.encode(matcher));

        // Also very empty and null values
        BytesMatcher.decode("");
        BytesMatcher.decode(null);
    }

    @Test
    public void testSerialize_Exact() throws Exception {
        BytesMatcher matcher = new BytesMatcher();
        matcher.addExactRejectRule(hexStringToByteArray("cafe00112233"),
                hexStringToByteArray("ffffff000000"));
        matcher.addExactRejectRule(hexStringToByteArray("beef00112233"),
                null);
        matcher.addExactAcceptRule(hexStringToByteArray("000000000000"),
                hexStringToByteArray("000000000000"));

        assertFalse(matcher.test(hexStringToByteArray("cafe00ffffff")));
        assertFalse(matcher.test(hexStringToByteArray("beef00112233")));
        assertTrue(matcher.test(hexStringToByteArray("beef00ffffff")));

        // Bounce through serialization pass and confirm it still works
        matcher = BytesMatcher.decode(BytesMatcher.encode(matcher));

        assertFalse(matcher.test(hexStringToByteArray("cafe00ffffff")));
        assertFalse(matcher.test(hexStringToByteArray("beef00112233")));
        assertTrue(matcher.test(hexStringToByteArray("beef00ffffff")));
    }

    @Test
    public void testSerialize_Prefix() throws Exception {
        BytesMatcher matcher = new BytesMatcher();
        matcher.addExactRejectRule(hexStringToByteArray("aa"), null);
        matcher.addExactAcceptRule(hexStringToByteArray("bb"), null);
        matcher.addPrefixAcceptRule(hexStringToByteArray("aa"), null);
        matcher.addPrefixRejectRule(hexStringToByteArray("bb"), null);

        assertFalse(matcher.test(hexStringToByteArray("aa")));
        assertTrue(matcher.test(hexStringToByteArray("bb")));
        assertTrue(matcher.test(hexStringToByteArray("aaaa")));
        assertFalse(matcher.test(hexStringToByteArray("bbbb")));

        // Bounce through serialization pass and confirm it still works
        matcher = BytesMatcher.decode(BytesMatcher.encode(matcher));

        assertFalse(matcher.test(hexStringToByteArray("aa")));
        assertTrue(matcher.test(hexStringToByteArray("bb")));
        assertTrue(matcher.test(hexStringToByteArray("aaaa")));
        assertFalse(matcher.test(hexStringToByteArray("bbbb")));
    }

    @Test
    public void testOrdering_RejectFirst() throws Exception {
        BytesMatcher matcher = BytesMatcher.decode("-ff/0f,+ff/f0");
        assertFalse(matcher.test(hexStringToByteArray("ff")));
        assertTrue(matcher.test(hexStringToByteArray("f0")));
        assertFalse(matcher.test(hexStringToByteArray("0f")));
    }

    @Test
    public void testOrdering_AcceptFirst() throws Exception {
        BytesMatcher matcher = BytesMatcher.decode("+ff/f0,-ff/0f");
        assertTrue(matcher.test(hexStringToByteArray("ff")));
        assertTrue(matcher.test(hexStringToByteArray("f0")));
        assertFalse(matcher.test(hexStringToByteArray("0f")));
    }
}
