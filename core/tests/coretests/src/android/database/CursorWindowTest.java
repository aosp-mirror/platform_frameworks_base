/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.database;

import android.test.suitebuilder.annotation.SmallTest;
import android.database.CursorWindow;
import android.test.PerformanceTestCase;

import java.util.Arrays;

import junit.framework.TestCase;

public class CursorWindowTest extends TestCase implements PerformanceTestCase {
    public boolean isPerformanceOnly() {
        return false;
    }

    // These test can only be run once.
    public int startPerformance(Intermediates intermediates) {
        return 1;
    }

    @SmallTest
    public void testValuesLocalWindow() {
        doTestValues(new CursorWindow(true));
    }
    
    @SmallTest
    public void testValuesRemoteWindow() {
        doTestValues(new CursorWindow(false));
    }
    
    private void doTestValues(CursorWindow window) {
        assertTrue(window.setNumColumns(7));
        assertTrue(window.allocRow());
        double db1 = 1.26;
        assertTrue(window.putDouble(db1, 0, 0));
        double db2 = window.getDouble(0, 0);
        assertEquals(db1, db2);

        long int1 = Long.MAX_VALUE;
        assertTrue(window.putLong(int1, 0, 1));
        long int2 = window.getLong(0, 1);
        assertEquals(int1, int2);

        assertTrue(window.putString("1198032740000", 0, 3));
        assertEquals("1198032740000", window.getString(0, 3));
        assertEquals(1198032740000L, window.getLong(0, 3));

        assertTrue(window.putString(Long.toString(1198032740000L), 0, 3));
        assertEquals(Long.toString(1198032740000L), window.getString(0, 3));
        assertEquals(1198032740000L, window.getLong(0, 3));
        
        assertTrue(window.putString(Double.toString(42.0), 0, 4));
        assertEquals(Double.toString(42.0), window.getString(0, 4));
        assertEquals(42.0, window.getDouble(0, 4));
        
        // put blob
        byte[] blob = new byte[1000];
        byte value = 99;
        Arrays.fill(blob, value);
        assertTrue(window.putBlob(blob, 0, 6));
        assertTrue(Arrays.equals(blob, window.getBlob(0, 6)));
    }
}
