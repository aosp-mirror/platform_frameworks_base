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

package android.util;


import static android.util.SequenceUtils.getInitSeq;
import static android.util.SequenceUtils.getNextSeq;
import static android.util.SequenceUtils.isIncomingSeqStale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.annotations.Presubmit;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for subtypes of {@link SequenceUtils}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:SequenceUtilsTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
@DisabledOnRavenwood(blockedBy = SequenceUtils.class)
public class SequenceUtilsTest {

    // This is needed to disable the test in Ravenwood test, because SequenceUtils hasn't opted in
    // for Ravenwood, which is still in experiment.
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void testNextSeq() {
        assertEquals(getInitSeq() + 1, getNextSeq(getInitSeq()));
        assertEquals(getInitSeq() + 1, getNextSeq(Integer.MAX_VALUE));
    }

    @Test
    public void testIsIncomingSeqStale() {
        assertFalse(isIncomingSeqStale(getInitSeq() + 1, getInitSeq() + 10));
        assertTrue(isIncomingSeqStale(getInitSeq() + 10, getInitSeq() + 1));
        assertFalse(isIncomingSeqStale(-100, 100));
        assertTrue(isIncomingSeqStale(100, -100));
        assertFalse(isIncomingSeqStale(1, 2));
        assertTrue(isIncomingSeqStale(2, 1));

        // Possible incoming seq are all newer than the initial seq.
        assertFalse(isIncomingSeqStale(getInitSeq(), getInitSeq()));
        assertFalse(isIncomingSeqStale(getInitSeq(), getInitSeq() + 1));
        assertFalse(isIncomingSeqStale(getInitSeq(), -100));
        assertFalse(isIncomingSeqStale(getInitSeq(), 0));
        assertFalse(isIncomingSeqStale(getInitSeq(), 100));
        assertFalse(isIncomingSeqStale(getInitSeq(), Integer.MAX_VALUE));
        assertFalse(isIncomingSeqStale(getInitSeq(), getNextSeq(Integer.MAX_VALUE)));

        // False for the same seq.
        assertFalse(isIncomingSeqStale(100, 100));
        assertFalse(isIncomingSeqStale(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // False when there is a large jump (overflow).
        assertFalse(isIncomingSeqStale(Integer.MAX_VALUE, getInitSeq() + 1));
        assertFalse(isIncomingSeqStale(Integer.MAX_VALUE, getInitSeq() + 100));
        assertFalse(isIncomingSeqStale(Integer.MAX_VALUE, getNextSeq(Integer.MAX_VALUE)));

        // True when the large jump is opposite (curSeq is newer).
        assertTrue(isIncomingSeqStale(getInitSeq() + 1, Integer.MAX_VALUE));
        assertTrue(isIncomingSeqStale(getInitSeq() + 100, Integer.MAX_VALUE));
        assertTrue(isIncomingSeqStale(getNextSeq(Integer.MAX_VALUE), Integer.MAX_VALUE));
    }
}
