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

package android.view;

import static android.Manifest.permission.READ_FRAME_BUFFER;
import static android.content.pm.PackageManager.PERMISSION_DENIED;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.util.WeakHashMap;

/**
 * Class for testing {@link SurfaceControlRegistry}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:android.view.SurfaceControlRegistryTests
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SurfaceControlRegistryTests {

    @BeforeClass
    public static void setUpOnce() {
        SurfaceControlRegistry.createProcessInstance(getInstrumentation().getTargetContext());
    }

    @AfterClass
    public static void tearDownOnce() {
        SurfaceControlRegistry.destroyProcessInstance();
    }

    @Test
    public void testRequiresPermissionToCreateProcessInstance() {
        try {
            Context ctx = mock(Context.class);
            doReturn(PERMISSION_DENIED).when(ctx).checkSelfPermission(eq(READ_FRAME_BUFFER));
            SurfaceControlRegistry.createProcessInstance(ctx);
            fail("Expected SecurityException due to missing permission");
        } catch (SecurityException se) {
            // Expected failure
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
        }
    }

    @Test
    public void testCreateReleaseSurfaceControl() {
        int hash0 = SurfaceControlRegistry.getProcessInstance().hashCode();
        SurfaceControl sc = buildTestSurface();
        assertNotEquals(hash0, SurfaceControlRegistry.getProcessInstance().hashCode());
        sc.release();
        assertEquals(hash0, SurfaceControlRegistry.getProcessInstance().hashCode());
    }

    @Test
    public void testCreateReleaseMultipleSurfaceControls() {
        int hash0 = SurfaceControlRegistry.getProcessInstance().hashCode();
        SurfaceControl sc1 = buildTestSurface();
        int hash1 = SurfaceControlRegistry.getProcessInstance().hashCode();
        assertNotEquals(hash0, hash1);
        SurfaceControl sc2 = buildTestSurface();
        int hash1_2 = SurfaceControlRegistry.getProcessInstance().hashCode();
        assertNotEquals(hash0, hash1_2);
        assertNotEquals(hash1, hash1_2);
        // Release in inverse order to verify hashes still differ
        sc1.release();
        int hash2 = SurfaceControlRegistry.getProcessInstance().hashCode();
        assertNotEquals(hash0, hash2);
        sc2.release();
        assertEquals(hash0, SurfaceControlRegistry.getProcessInstance().hashCode());
    }

    @Test
    public void testInvalidSurfaceControlNotAddedToRegistry() {
        int hash0 = SurfaceControlRegistry.getProcessInstance().hashCode();
        // Verify no changes to the registry when dealing with invalid surface controls
        SurfaceControl sc0 = new SurfaceControl();
        SurfaceControl sc1 = new SurfaceControl(sc0, "test");
        assertEquals(hash0, SurfaceControlRegistry.getProcessInstance().hashCode());
        sc0.release();
        sc1.release();
        assertEquals(hash0, SurfaceControlRegistry.getProcessInstance().hashCode());
    }

    @Test
    public void testThresholds() {
        SurfaceControlRegistry registry = SurfaceControlRegistry.getProcessInstance();
        TestReporter reporter = new TestReporter();
        registry.setReportingThresholds(4 /* max */, 2 /* reset */, reporter);

        // Exceed the threshold ensure the callback is made
        SurfaceControl sc1 = buildTestSurface();
        SurfaceControl sc2 = buildTestSurface();
        SurfaceControl sc3 = buildTestSurface();
        SurfaceControl sc4 = buildTestSurface();
        reporter.assertMaxThresholdExceededCallCount(1);
        reporter.assertLastReportedSetEquals(sc1, sc2, sc3, sc4);

        // Create a few more, ensure we don't report again for the time being
        SurfaceControl sc5 = buildTestSurface();
        SurfaceControl sc6 = buildTestSurface();
        reporter.assertMaxThresholdExceededCallCount(1);
        reporter.assertLastReportedSetEquals(sc1, sc2, sc3, sc4);

        // Release down to the reset threshold
        sc1.release();
        sc2.release();
        sc3.release();
        sc4.release();

        // Create a few more to hit the max threshold again
        SurfaceControl sc7 = buildTestSurface();
        SurfaceControl sc8 = buildTestSurface();
        reporter.assertMaxThresholdExceededCallCount(2);
        reporter.assertLastReportedSetEquals(sc5, sc6, sc7, sc8);
    }

    @Test
    public void testCallStackDebugging_matchesFilters() {
        SurfaceControlRegistry registry = SurfaceControlRegistry.getProcessInstance();

        // Specific name, any call
        registry.setCallStackDebuggingParams("com.android.app1", "");
        assertFalse(registry.matchesForCallStackDebugging("com.android.noMatchApp", "setAlpha"));
        assertTrue(registry.matchesForCallStackDebugging("com.android.app1", "setAlpha"));

        // Any name, specific call
        registry.setCallStackDebuggingParams("", "setAlpha");
        assertFalse(registry.matchesForCallStackDebugging("com.android.app1", "setLayer"));
        assertTrue(registry.matchesForCallStackDebugging("com.android.app1", "setAlpha"));
        assertTrue(registry.matchesForCallStackDebugging("com.android.app2", "setAlpha"));

        // Specific name, specific call
        registry.setCallStackDebuggingParams("com.android.app1", "setAlpha");
        assertFalse(registry.matchesForCallStackDebugging("com.android.app1", "setLayer"));
        assertFalse(registry.matchesForCallStackDebugging("com.android.app2", "setAlpha"));
        assertTrue(registry.matchesForCallStackDebugging("com.android.app1", "setAlpha"));
    }

    private SurfaceControl buildTestSurface() {
        return new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("SurfaceControlRegistryTests")
                .setCallsite("SurfaceControlRegistryTests")
                .build();
    }

    private static class TestReporter implements SurfaceControlRegistry.Reporter {
        WeakHashMap<SurfaceControl, Long> lastSurfaceControls = new WeakHashMap<>();
        int callCount = 0;

        @Override
        public void onMaxLayersExceeded(WeakHashMap<SurfaceControl, Long> surfaceControls,
                int limit, PrintWriter pw) {
            lastSurfaceControls.clear();
            lastSurfaceControls.putAll(surfaceControls);
            callCount++;
        }

        public void assertMaxThresholdExceededCallCount(int count) {
            assertTrue("Expected " + count + " got " + callCount, count == callCount);
        }

        public void assertLastReportedSetEquals(SurfaceControl... surfaces) {
            WeakHashMap<SurfaceControl, Long> last = new WeakHashMap<>(lastSurfaceControls);
            for (int i = 0; i < surfaces.length; i++) {
                last.remove(surfaces[i]);
            }
            if (!last.isEmpty()) {
                fail("Sets differ");
            }
        }
    }
}
