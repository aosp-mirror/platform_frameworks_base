/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class InterfaceParamsTest {
    @Test
    public void testNullInterfaceReturnsNull() {
        assertNull(InterfaceParams.getByName(null));
    }

    @Test
    public void testNonExistentInterfaceReturnsNull() {
        assertNull(InterfaceParams.getByName("doesnotexist0"));
    }

    @Test
    public void testLoopback() {
        final InterfaceParams ifParams = InterfaceParams.getByName("lo");
        assertNotNull(ifParams);
        assertEquals("lo", ifParams.name);
        assertTrue(ifParams.index > 0);
        assertNotNull(ifParams.macAddr);
        assertTrue(ifParams.defaultMtu >= NetworkConstants.ETHER_MTU);
    }
}
