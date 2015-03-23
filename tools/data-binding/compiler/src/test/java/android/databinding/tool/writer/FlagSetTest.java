/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.databinding.tool.writer;

import org.junit.Test;

import java.util.BitSet;

import static org.junit.Assert.assertEquals;

public class FlagSetTest {
    @Test
    public void testSimple1Level() {
        BitSet bs = new BitSet();
        bs.set(7);
        FlagSet flagSet = new FlagSet(bs, 3);
        assertEquals(3, flagSet.buckets.length);
        assertEquals(1 << 7, flagSet.buckets[0]);
        assertEquals(0, flagSet.buckets[1]);
        assertEquals(0, flagSet.buckets[2]);
    }

    @Test
    public void testSimple2Level() {
        BitSet bs = new BitSet();
        bs.set(FlagSet.sBucketSize + 2);
        FlagSet flagSet = new FlagSet(bs, 3);
        assertEquals(3, flagSet.buckets.length);
        assertEquals(0, flagSet.buckets[0]);
        assertEquals(1 << 2, flagSet.buckets[1]);
        assertEquals(0, flagSet.buckets[2]);
    }

    @Test
    public void testSimple3Level() {
        BitSet bs = new BitSet();
        bs.set(5);
        bs.set(FlagSet.sBucketSize + 2);
        bs.set(FlagSet.sBucketSize * 2 + 10);
        FlagSet flagSet = new FlagSet(bs, 3);
        assertEquals(3, flagSet.buckets.length);
        assertEquals(1 << 5, flagSet.buckets[0]);
        assertEquals(1 << 2, flagSet.buckets[1]);
        assertEquals(1 << 10, flagSet.buckets[2]);
    }
}
