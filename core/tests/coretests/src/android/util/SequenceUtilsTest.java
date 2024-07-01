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
import static android.util.SequenceUtils.isIncomingSeqNewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.annotations.Presubmit;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

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
    public void testIsIncomingSeqNewer() {
        assertTrue(isIncomingSeqNewer(getInitSeq() + 1, getInitSeq() + 10));
        assertFalse(isIncomingSeqNewer(getInitSeq() + 10, getInitSeq() + 1));
        assertTrue(isIncomingSeqNewer(-100, 100));
        assertFalse(isIncomingSeqNewer(100, -100));
        assertTrue(isIncomingSeqNewer(1, 2));
        assertFalse(isIncomingSeqNewer(2, 1));

        // Possible incoming seq are all newer than the initial seq.
        assertTrue(isIncomingSeqNewer(getInitSeq(), getInitSeq() + 1));
        assertTrue(isIncomingSeqNewer(getInitSeq(), -100));
        assertTrue(isIncomingSeqNewer(getInitSeq(), 0));
        assertTrue(isIncomingSeqNewer(getInitSeq(), 100));
        assertTrue(isIncomingSeqNewer(getInitSeq(), Integer.MAX_VALUE));
        assertTrue(isIncomingSeqNewer(getInitSeq(), getNextSeq(Integer.MAX_VALUE)));

        // False for the same seq.
        assertFalse(isIncomingSeqNewer(getInitSeq(), getInitSeq()));
        assertFalse(isIncomingSeqNewer(100, 100));
        assertFalse(isIncomingSeqNewer(Integer.MAX_VALUE, Integer.MAX_VALUE));

        // True when there is a large jump (overflow).
        assertTrue(isIncomingSeqNewer(Integer.MAX_VALUE, getInitSeq() + 1));
        assertTrue(isIncomingSeqNewer(Integer.MAX_VALUE, getInitSeq() + 100));
        assertTrue(isIncomingSeqNewer(Integer.MAX_VALUE, getNextSeq(Integer.MAX_VALUE)));
    }
}
