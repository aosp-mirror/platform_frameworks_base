/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.net;

import junit.framework.TestCase;

public class RssiCurveTest extends TestCase {
    public void testLookupScore_constantCurve() {
        RssiCurve curve = new RssiCurve(-100, 200, new byte[] { 10 });
        assertEquals(10, curve.lookupScore(-200));
        assertEquals(10, curve.lookupScore(-100));
        assertEquals(10, curve.lookupScore(0));
        assertEquals(10, curve.lookupScore(100));
        assertEquals(10, curve.lookupScore(200));
    }

    public void testLookupScore_changingCurve() {
        RssiCurve curve = new RssiCurve(-100, 100, new byte[] { -10, 10 });
        assertEquals(-10, curve.lookupScore(-200));
        assertEquals(-10, curve.lookupScore(-100));
        assertEquals(-10, curve.lookupScore(-50));
        assertEquals(10, curve.lookupScore(0));
        assertEquals(10, curve.lookupScore(50));
        assertEquals(10, curve.lookupScore(100));
        assertEquals(10, curve.lookupScore(200));
    }
}
