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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import android.os.PerformanceHintManager.Session;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class PerformanceHintManagerTest {
    private static final long RATE_1000 = 1000L;
    private static final long TARGET_166 = 166L;
    private static final long DEFAULT_TARGET_NS = 16666666L;
    private PerformanceHintManager mPerformanceHintManager;

    @Mock
    private IHintSession mIHintSessionMock;

    @Before
    public void setUp() {
        mPerformanceHintManager =
                InstrumentationRegistry.getInstrumentation().getContext().getSystemService(
                        PerformanceHintManager.class);
        MockitoAnnotations.initMocks(this);
    }

    private Session createSession() {
        return mPerformanceHintManager.createHintSession(
                new int[]{Process.myPid()}, DEFAULT_TARGET_NS);
    }

    @Test
    public void testCreateHintSession() {
        Session a = createSession();
        Session b = createSession();
        if (a == null) {
            assertNull(b);
        } else {
            assertNotEquals(a, b);
        }
    }

    @Test
    public void testGetPreferredUpdateRateNanos() {
        if (createSession() != null) {
            assertTrue(mPerformanceHintManager.getPreferredUpdateRateNanos() > 0);
        } else {
            assertEquals(-1, mPerformanceHintManager.getPreferredUpdateRateNanos());
        }
    }

    @Test
    public void testUpdateTargetWorkDuration() {
        Session s = createSession();
        assumeNotNull(s);
        s.updateTargetWorkDuration(100);
    }

    @Test
    public void testUpdateTargetWorkDurationWithNegativeDuration() {
        Session s = createSession();
        assumeNotNull(s);
        assertThrows(IllegalArgumentException.class, () -> {
            s.updateTargetWorkDuration(-1);
        });
    }

    @Test
    public void testReportActualWorkDuration() {
        Session s = createSession();
        assumeNotNull(s);
        s.updateTargetWorkDuration(100);
        s.reportActualWorkDuration(1);
        s.reportActualWorkDuration(100);
        s.reportActualWorkDuration(1000);
    }

    @Test
    public void testReportActualWorkDurationWithIllegalArgument() {
        Session s = createSession();
        assumeNotNull(s);
        s.updateTargetWorkDuration(100);
        assertThrows(IllegalArgumentException.class, () -> {
            s.reportActualWorkDuration(-1);
        });
    }

    @Test
    public void testCloseHintSession() {
        Session s = createSession();
        assumeNotNull(s);
        s.close();
    }
}
