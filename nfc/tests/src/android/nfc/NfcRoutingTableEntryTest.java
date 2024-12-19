/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.nfc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class NfcRoutingTableEntryTest {

    @Test
    public void testAidEntry_GetAid() {
        String expectedAid = "A00000061A02";
        RoutingTableAidEntry entry = new RoutingTableAidEntry(1, expectedAid, 0);

        assertEquals(expectedAid, entry.getAid());
    }

    @Test
    public void testProtocolEntry_GetProtocol() {
        RoutingTableProtocolEntry entry =
                new RoutingTableProtocolEntry(1, RoutingTableProtocolEntry.PROTOCOL_T1T, 0);

        assertEquals(RoutingTableProtocolEntry.PROTOCOL_T1T, entry.getProtocol());
    }

    @Test
    public void testSystemCodeEntry_GetSystemCode() {
        byte[] expectedSystemCode = {0x01, 0x02, 0x03};
        RoutingTableSystemCodeEntry entry =
                new RoutingTableSystemCodeEntry(1, expectedSystemCode, 0);

        assertArrayEquals(expectedSystemCode, entry.getSystemCode());
    }

    @Test
    public void testTechnologyEntry_GetTechnology_A() {
        RoutingTableTechnologyEntry entry =
                new RoutingTableTechnologyEntry(1, RoutingTableTechnologyEntry.TECHNOLOGY_A, 0);

        assertEquals(RoutingTableTechnologyEntry.TECHNOLOGY_A, entry.getTechnology());
    }
}
