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

package android.core;

import junit.framework.TestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests for basic functionality of floats and doubles.
 */
public class FloatDoubleTest extends TestCase {

    @SmallTest
    public void testFloatDouble() throws Exception {
        Double d = Double.valueOf(1.0);
        Float f = Float.valueOf(1.0f);
        Object o = new Object();

        assertFalse(f.equals(d));
        assertFalse(d.equals(f));
        assertFalse(f.equals(o));
        assertFalse(d.equals(o));
        assertFalse(f.equals(null));
        assertFalse(d.equals(null));
    }

    @SmallTest
    public void testFloat() throws Exception {
        float pz = 0.0f;
        float nz = -0.0f;

        float pzero = 1.0f / Float.POSITIVE_INFINITY;
        float nzero = 1.0f / Float.NEGATIVE_INFINITY;

        // Everything compares as '=='
        assertTrue(pz == pz);
        assertTrue(pz == nz);
        assertTrue(pz == pzero);
        assertTrue(pz == nzero);

        assertTrue(nz == pz);
        assertTrue(nz == nz);
        assertTrue(nz == pzero);
        assertTrue(nz == nzero);

        assertTrue(pzero == pz);
        assertTrue(pzero == nz);
        assertTrue(pzero == pzero);
        assertTrue(pzero == nzero);

        assertTrue(nzero == pz);
        assertTrue(nzero == nz);
        assertTrue(nzero == pzero);
        assertTrue(nzero == nzero);

        // +-0 are distinct as Floats
        assertEquals(Float.valueOf(pz), Float.valueOf(pz));
        assertTrue(!Float.valueOf(pz).equals(Float.valueOf(nz)));
        assertEquals(Float.valueOf(pz), Float.valueOf(pzero));
        assertTrue(!Float.valueOf(pz).equals(Float.valueOf(nzero)));

        assertTrue(!Float.valueOf(nz).equals(Float.valueOf(pz)));
        assertEquals(Float.valueOf(nz), Float.valueOf(nz));
        assertTrue(!Float.valueOf(nz).equals(Float.valueOf(pzero)));
        assertEquals(Float.valueOf(nz), Float.valueOf(nzero));

        assertEquals(Float.valueOf(pzero), Float.valueOf(pz));
        assertTrue(!Float.valueOf(pzero).equals(Float.valueOf(nz)));
        assertEquals(Float.valueOf(pzero), Float.valueOf(pzero));
        assertTrue(!Float.valueOf(pzero).equals(Float.valueOf(nzero)));

        assertTrue(!Float.valueOf(nzero).equals(Float.valueOf(pz)));
        assertEquals(Float.valueOf(nzero), Float.valueOf(nz));
        assertTrue(!Float.valueOf(nzero).equals(Float.valueOf(pzero)));
        assertEquals(Float.valueOf(nzero), Float.valueOf(nzero));

        // Nan's compare as equal
        Float sqrtm2 = Float.valueOf((float) Math.sqrt(-2.0f));
        Float sqrtm3 = Float.valueOf((float) Math.sqrt(-3.0f));
        assertEquals(sqrtm2, sqrtm3);
    }

    @SmallTest
    public void testDouble() throws Exception {
        double pz = 0.0;
        double nz = -0.0;

        double pzero = 1.0 / Double.POSITIVE_INFINITY;
        double nzero = 1.0 / Double.NEGATIVE_INFINITY;

        // Everything compares as '=='
        assertTrue(pz == pz);
        assertTrue(pz == nz);
        assertTrue(pz == pzero);
        assertTrue(pz == nzero);

        assertTrue(nz == pz);
        assertTrue(nz == nz);
        assertTrue(nz == pzero);
        assertTrue(nz == nzero);

        assertTrue(pzero == pz);
        assertTrue(pzero == nz);
        assertTrue(pzero == pzero);
        assertTrue(pzero == nzero);

        assertTrue(nzero == pz);
        assertTrue(nzero == nz);
        assertTrue(nzero == pzero);
        assertTrue(nzero == nzero);

        // +-0 are distinct as Doubles
        assertEquals(Double.valueOf(pz), Double.valueOf(pz));
        assertTrue(!Double.valueOf(pz).equals(Double.valueOf(nz)));
        assertEquals(Double.valueOf(pz), Double.valueOf(pzero));
        assertTrue(!Double.valueOf(pz).equals(Double.valueOf(nzero)));

        assertTrue(!Double.valueOf(nz).equals(Double.valueOf(pz)));
        assertEquals(Double.valueOf(nz), Double.valueOf(nz));
        assertTrue(!Double.valueOf(nz).equals(Double.valueOf(pzero)));
        assertEquals(Double.valueOf(nz), Double.valueOf(nzero));

        assertEquals(Double.valueOf(pzero), Double.valueOf(pz));
        assertTrue(!Double.valueOf(pzero).equals(Double.valueOf(nz)));
        assertEquals(Double.valueOf(pzero), Double.valueOf(pzero));
        assertTrue(!Double.valueOf(pzero).equals(Double.valueOf(nzero)));

        assertTrue(!Double.valueOf(nzero).equals(Double.valueOf(pz)));
        assertEquals(Double.valueOf(nzero), Double.valueOf(nz));
        assertTrue(!Double.valueOf(nzero).equals(Double.valueOf(pzero)));
        assertEquals(Double.valueOf(nzero), Double.valueOf(nzero));

        // Nan's compare as equal
        Double sqrtm2 = Double.valueOf(Math.sqrt(-2.0));
        Double sqrtm3 = Double.valueOf(Math.sqrt(-3.0));
        assertEquals(sqrtm2, sqrtm3);
    }
}
