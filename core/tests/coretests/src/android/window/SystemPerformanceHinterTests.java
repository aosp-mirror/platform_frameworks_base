/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.window;

import static android.os.PerformanceHintManager.Session.CPU_LOAD_RESET;
import static android.os.PerformanceHintManager.Session.CPU_LOAD_UP;
import static android.view.Surface.FRAME_RATE_CATEGORY_DEFAULT;
import static android.view.Surface.FRAME_RATE_CATEGORY_HIGH;
import static android.view.SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN;
import static android.view.SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_PROPAGATE;
import static android.window.SystemPerformanceHinter.HINT_ADPF;
import static android.window.SystemPerformanceHinter.HINT_ALL;
import static android.window.SystemPerformanceHinter.HINT_SF_EARLY_WAKEUP;
import static android.window.SystemPerformanceHinter.HINT_SF_FRAME_RATE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.os.PerformanceHintManager;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;

/**
 * Class for testing {@link android.window.SystemPerformanceHinter}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:android.window.SystemPerformanceHinterTests
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SystemPerformanceHinterTests {

    private static final int DEFAULT_DISPLAY_ID = android.view.Display.DEFAULT_DISPLAY;
    private static final int SECONDARY_DISPLAY_ID = DEFAULT_DISPLAY_ID + 1;
    private static final int NO_ROOT_DISPLAY_ID = DEFAULT_DISPLAY_ID + 2;
    private static final String TEST_REASON = "test";
    private static final String TEST_OTHER_REASON = "test_other";

    private SystemPerformanceHinter mHinter;
    private SystemPerformanceHinterTests.RootProvider mRootProvider;

    @Mock
    private PerformanceHintManager.Session mAdpfSession;

    @Mock
    private SurfaceControl.Transaction mTransaction;
    private SurfaceControl mDefaultDisplayRoot;
    private SurfaceControl mSecondaryDisplayRoot;


    @Before
    public void setUpOnce() {
        MockitoAnnotations.initMocks(this);

        mDefaultDisplayRoot = new SurfaceControl();
        mSecondaryDisplayRoot = new SurfaceControl();
        mRootProvider = new SystemPerformanceHinterTests.RootProvider();
        mRootProvider.put(DEFAULT_DISPLAY_ID, mDefaultDisplayRoot);
        mRootProvider.put(SECONDARY_DISPLAY_ID, mSecondaryDisplayRoot);

        mHinter = new SystemPerformanceHinter(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                mRootProvider,
                () -> mTransaction);
    }

    @Test
    public void testADPFHintWithoutADPFSession_expectThrows() {
        assertThrows("Expected exception without ADPF session",
                IllegalArgumentException.class, () -> {
                    mHinter.startSession(HINT_ADPF, DEFAULT_DISPLAY_ID, TEST_REASON);
                });
    }

    @Test
    public void testSFVRRHintWithoutDisplayRootProvider_expectThrows() {
        assertThrows("Expected exception without display root",
                IllegalArgumentException.class, () -> {
                    SystemPerformanceHinter hinter = new SystemPerformanceHinter(
                            InstrumentationRegistry.getInstrumentation().getTargetContext(),
                            null /* displayRootProvider */,
                            () -> mTransaction);
                    hinter.startSession(HINT_SF_FRAME_RATE, DEFAULT_DISPLAY_ID, TEST_REASON);
                });
    }

    @Test
    public void testGetDefaultDisplayRoot() {
        mHinter.startSession(HINT_SF_FRAME_RATE, DEFAULT_DISPLAY_ID, TEST_REASON);
        assertEquals(DEFAULT_DISPLAY_ID, mRootProvider.lastRequestedDisplayId);
        assertEquals(mDefaultDisplayRoot, mRootProvider.lastReturnedRoot);
    }

    @Test
    public void testGetSecondaryDisplayRoot() {
        mHinter.startSession(HINT_SF_FRAME_RATE, SECONDARY_DISPLAY_ID, TEST_REASON);
        assertEquals(SECONDARY_DISPLAY_ID, mRootProvider.lastRequestedDisplayId);
        assertEquals(mSecondaryDisplayRoot, mRootProvider.lastReturnedRoot);
    }

    @Test
    public void testOnlyCacheDisplayRoots() {
        mHinter.startSession(HINT_SF_FRAME_RATE, DEFAULT_DISPLAY_ID, TEST_REASON);
        mHinter.startSession(HINT_SF_FRAME_RATE, DEFAULT_DISPLAY_ID, TEST_REASON);
        mHinter.startSession(HINT_SF_FRAME_RATE, DEFAULT_DISPLAY_ID, TEST_REASON);
        assertEquals(DEFAULT_DISPLAY_ID, mRootProvider.lastRequestedDisplayId);
        assertEquals(mDefaultDisplayRoot, mRootProvider.lastReturnedRoot);
    }

    @Test
    public void testVRRHint() {
        final SystemPerformanceHinter.HighPerfSession session =
                mHinter.startSession(HINT_SF_FRAME_RATE, DEFAULT_DISPLAY_ID, TEST_REASON);

        // Expect it to get a display root
        assertEquals(DEFAULT_DISPLAY_ID, mRootProvider.lastRequestedDisplayId);
        assertEquals(mDefaultDisplayRoot, mRootProvider.lastReturnedRoot);

        // Verify we call SF
        verify(mTransaction).setFrameRateSelectionStrategy(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN));
        verify(mTransaction).setFrameRateCategory(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_CATEGORY_HIGH),
                eq(false));
        verify(mTransaction).applyAsyncUnsafe();
    }

    @Test
    public void testVRRHintCloseSession() {
        final SystemPerformanceHinter.HighPerfSession session =
                mHinter.startSession(HINT_SF_FRAME_RATE, DEFAULT_DISPLAY_ID, TEST_REASON);
        reset(mTransaction);
        session.close();

        // Verify we call SF
        verify(mTransaction).setFrameRateSelectionStrategy(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_SELECTION_STRATEGY_PROPAGATE));
        verify(mTransaction).setFrameRateCategory(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_CATEGORY_DEFAULT),
                eq(false));
        verify(mTransaction).applyAsyncUnsafe();
    }

    @Test
    public void testEarlyWakeupHint() {
        final SystemPerformanceHinter.HighPerfSession session =
                mHinter.startSession(HINT_SF_EARLY_WAKEUP, DEFAULT_DISPLAY_ID, TEST_REASON);

        // Expect that this hint does not require a display root
        assertEquals(0, mRootProvider.getCount);

        // Verify we call SF
        verify(mTransaction).setEarlyWakeupStart();
        verify(mTransaction).applyAsyncUnsafe();
    }

    @Test
    public void testEarlyWakeupHintCloseSession() {
        final SystemPerformanceHinter.HighPerfSession session =
                mHinter.startSession(HINT_SF_EARLY_WAKEUP, DEFAULT_DISPLAY_ID, TEST_REASON);
        reset(mTransaction);
        session.close();

        // Verify we call SF
        verify(mTransaction).setEarlyWakeupEnd();
        verify(mTransaction).applyAsyncUnsafe();
    }

    @Test
    public void testADPFHint() {
        mHinter.setAdpfSession(mAdpfSession);
        final SystemPerformanceHinter.HighPerfSession session =
                mHinter.startSession(HINT_ADPF, DEFAULT_DISPLAY_ID, TEST_REASON);

        // Expect that this hint does not require a display root
        assertEquals(0, mRootProvider.getCount);

        // Verify we call the perf manager
        verify(mAdpfSession).sendHint(eq(CPU_LOAD_UP));
    }

    @Test
    public void testADPFHintCloseSession() {
        mHinter.setAdpfSession(mAdpfSession);
        final SystemPerformanceHinter.HighPerfSession session =
                mHinter.startSession(HINT_ADPF, DEFAULT_DISPLAY_ID, TEST_REASON);
        reset(mTransaction);
        session.close();

        // Verify we call the perf manager
        verify(mAdpfSession).sendHint(eq(CPU_LOAD_RESET));
    }

    @Test
    public void testAllHints() {
        mHinter.setAdpfSession(mAdpfSession);
        final SystemPerformanceHinter.HighPerfSession session =
                mHinter.startSession(HINT_ALL, DEFAULT_DISPLAY_ID, TEST_REASON);

        // Expect it to get a display root
        assertEquals(DEFAULT_DISPLAY_ID, mRootProvider.lastRequestedDisplayId);
        assertEquals(mDefaultDisplayRoot, mRootProvider.lastReturnedRoot);

        // Verify we call SF and perf manager
        verify(mTransaction).setFrameRateSelectionStrategy(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN));
        verify(mTransaction).setFrameRateCategory(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_CATEGORY_HIGH),
                eq(false));
        verify(mTransaction).setEarlyWakeupStart();
        verify(mTransaction).applyAsyncUnsafe();
        verify(mAdpfSession).sendHint(eq(CPU_LOAD_UP));
    }

    @Test
    public void testAllHintsCloseSession() {
        mHinter.setAdpfSession(mAdpfSession);
        final SystemPerformanceHinter.HighPerfSession session =
                mHinter.startSession(HINT_ALL, DEFAULT_DISPLAY_ID, TEST_REASON);
        reset(mTransaction);
        session.close();

        // Verify we call SF and perf manager to clean up
        verify(mTransaction).setFrameRateSelectionStrategy(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_SELECTION_STRATEGY_PROPAGATE));
        verify(mTransaction).setFrameRateCategory(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_CATEGORY_DEFAULT),
                eq(false));
        verify(mTransaction).setEarlyWakeupEnd();
        verify(mTransaction).applyAsyncUnsafe();
        verify(mAdpfSession).sendHint(eq(CPU_LOAD_RESET));
    }

    @Test
    public void testAutocloseable() {
        mHinter.setAdpfSession(mAdpfSession);
        try (final SystemPerformanceHinter.HighPerfSession session =
                     mHinter.startSession(HINT_ALL, DEFAULT_DISPLAY_ID, TEST_REASON)) {
            reset(mTransaction);
            reset(mAdpfSession);
        } finally {
            // Verify we call SF and perf manager to clean up
            verify(mTransaction).setFrameRateSelectionStrategy(
                    eq(mDefaultDisplayRoot),
                    eq(FRAME_RATE_SELECTION_STRATEGY_PROPAGATE));
            verify(mTransaction).setFrameRateCategory(
                    eq(mDefaultDisplayRoot),
                    eq(FRAME_RATE_CATEGORY_DEFAULT),
                    eq(false));
            verify(mTransaction).setEarlyWakeupEnd();
            verify(mTransaction).applyAsyncUnsafe();
            verify(mAdpfSession).sendHint(eq(CPU_LOAD_RESET));
        }
    }

    @Test
    public void testOverlappingHintsOnSameDisplay() {
        mHinter.setAdpfSession(mAdpfSession);
        final SystemPerformanceHinter.HighPerfSession session1 =
                mHinter.startSession(HINT_ALL, DEFAULT_DISPLAY_ID, TEST_REASON);
        // Verify we call SF and perf manager
        verify(mTransaction).setFrameRateSelectionStrategy(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN));
        verify(mTransaction).setFrameRateCategory(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_CATEGORY_HIGH),
                eq(false));
        verify(mTransaction).setEarlyWakeupStart();
        verify(mTransaction).applyAsyncUnsafe();
        verify(mAdpfSession).sendHint(eq(CPU_LOAD_UP));
        reset(mTransaction);
        reset(mAdpfSession);

        final SystemPerformanceHinter.HighPerfSession session2 =
                mHinter.startSession(HINT_ALL, DEFAULT_DISPLAY_ID, TEST_OTHER_REASON);
        // Verify we never call SF and perf manager since session1 is already running
        verify(mTransaction, never()).setFrameRateSelectionStrategy(any(), anyInt());
        verify(mTransaction, never()).setFrameRateCategory(any(), anyInt(), anyBoolean());
        verify(mTransaction, never()).setEarlyWakeupEnd();
        verify(mTransaction, never()).applyAsyncUnsafe();
        verify(mAdpfSession, never()).sendHint(anyInt());

        session2.close();
        // Verify we have not cleaned up because session1 is still running
        verify(mTransaction, never()).setFrameRateSelectionStrategy(any(), anyInt());
        verify(mTransaction, never()).setFrameRateCategory(any(), anyInt(), anyBoolean());
        verify(mTransaction, never()).setEarlyWakeupEnd();
        verify(mTransaction, never()).applyAsyncUnsafe();
        verify(mAdpfSession, never()).sendHint(anyInt());

        session1.close();
        // Verify we call SF and perf manager to clean up
        verify(mTransaction).setFrameRateSelectionStrategy(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_SELECTION_STRATEGY_PROPAGATE));
        verify(mTransaction).setFrameRateCategory(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_CATEGORY_DEFAULT),
                eq(false));
        verify(mTransaction).setEarlyWakeupEnd();
        verify(mTransaction).applyAsyncUnsafe();
        verify(mAdpfSession).sendHint(eq(CPU_LOAD_RESET));
    }

    @Test
    public void testOverlappingHintsOnDifferentDisplays() {
        mHinter.setAdpfSession(mAdpfSession);
        final SystemPerformanceHinter.HighPerfSession session1 =
                mHinter.startSession(HINT_ALL, DEFAULT_DISPLAY_ID, TEST_REASON);

        // Verify we call SF and perf manager
        verify(mTransaction).setFrameRateSelectionStrategy(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN));
        verify(mTransaction).setFrameRateCategory(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_CATEGORY_HIGH),
                eq(false));
        verify(mTransaction).setEarlyWakeupStart();
        verify(mTransaction).applyAsyncUnsafe();
        verify(mAdpfSession).sendHint(eq(CPU_LOAD_UP));
        reset(mTransaction);
        reset(mAdpfSession);

        // Create a new session and ensure only per-display flags are updated and not global flags
        final SystemPerformanceHinter.HighPerfSession session2 =
                mHinter.startSession(HINT_ALL, SECONDARY_DISPLAY_ID, TEST_REASON);
        verify(mTransaction).setFrameRateSelectionStrategy(
                eq(mSecondaryDisplayRoot),
                eq(FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN));
        verify(mTransaction).setFrameRateCategory(
                eq(mSecondaryDisplayRoot),
                eq(FRAME_RATE_CATEGORY_HIGH),
                eq(false));
        verify(mTransaction, never()).setEarlyWakeupStart();
        verify(mTransaction).applyAsyncUnsafe();
        verify(mAdpfSession, never()).sendHint(anyInt());
        reset(mTransaction);
        reset(mAdpfSession);

        // Close the primary display session and ensure it doesn't affect secondary display flags
        // or any global flags still requested by the secondary display session
        session1.close();
        verify(mTransaction).setFrameRateSelectionStrategy(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_SELECTION_STRATEGY_PROPAGATE));
        verify(mTransaction).setFrameRateCategory(
                eq(mDefaultDisplayRoot),
                eq(FRAME_RATE_CATEGORY_DEFAULT),
                eq(false));
        verify(mTransaction, never()).setFrameRateSelectionStrategy(
                eq(mSecondaryDisplayRoot),
                anyInt());
        verify(mTransaction, never()).setFrameRateCategory(
                eq(mSecondaryDisplayRoot),
                anyInt(),
                eq(false));
        verify(mTransaction, never()).setEarlyWakeupEnd();
        verify(mTransaction).applyAsyncUnsafe();
        verify(mAdpfSession, never()).sendHint(anyInt());
        reset(mTransaction);
        reset(mAdpfSession);

        // Close all sessions, ensure it cleans up all the flags
        session2.close();
        verify(mTransaction, never()).setFrameRateSelectionStrategy(
                eq(mDefaultDisplayRoot),
                anyInt());
        verify(mTransaction).setFrameRateSelectionStrategy(
                eq(mSecondaryDisplayRoot),
                eq(FRAME_RATE_SELECTION_STRATEGY_PROPAGATE));
        verify(mTransaction).setFrameRateCategory(
                eq(mSecondaryDisplayRoot),
                eq(FRAME_RATE_CATEGORY_DEFAULT),
                eq(false));
        verify(mTransaction).setEarlyWakeupEnd();
        verify(mTransaction).applyAsyncUnsafe();
        verify(mAdpfSession).sendHint(eq(CPU_LOAD_RESET));
    }

    private class RootProvider implements SystemPerformanceHinter.DisplayRootProvider {
        private HashMap<Integer, SurfaceControl> mRoots = new HashMap<>();
        public int getCount;
        public int lastRequestedDisplayId = -1;
        public SurfaceControl lastReturnedRoot;

        void put(int displayId, SurfaceControl root) {
            mRoots.put(displayId, root);
        }

        @NonNull
        @Override
        public SurfaceControl getRootForDisplay(int displayId) {
            getCount++;
            lastRequestedDisplayId = displayId;
            lastReturnedRoot = mRoots.get(displayId);
            return lastReturnedRoot;
        }
    }
}
