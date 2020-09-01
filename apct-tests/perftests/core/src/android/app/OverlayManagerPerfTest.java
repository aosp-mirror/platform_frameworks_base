/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.om.OverlayManager;
import android.os.UserHandle;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.TestPackageInstaller;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import com.android.perftests.core.R;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks for {@link android.content.om.OverlayManager}.
 */
@LargeTest
public class OverlayManagerPerfTest {
    private static final int OVERLAY_PKG_COUNT = 10;
    private static Context sContext;
    private static OverlayManager sOverlayManager;
    private static Executor sExecutor;
    private static ArrayList<TestPackageInstaller.InstalledPackage> sSmallOverlays =
            new ArrayList<>();
    private static ArrayList<TestPackageInstaller.InstalledPackage> sLargeOverlays =
            new ArrayList<>();

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @BeforeClass
    public static void classSetUp() throws Exception {
        sContext = InstrumentationRegistry.getTargetContext();
        sOverlayManager = new OverlayManager(sContext);
        sExecutor = (command) -> new Thread(command).start();

        // Install all of the test overlays.
        TestPackageInstaller installer = new TestPackageInstaller(sContext);
        for (int i = 0; i < OVERLAY_PKG_COUNT; i++) {
            sSmallOverlays.add(installer.installPackage("Overlay" + i +".apk"));
            sLargeOverlays.add(installer.installPackage("LargeOverlay" + i +".apk"));
        }
    }

    @AfterClass
    public static void classTearDown() throws Exception {
        for (TestPackageInstaller.InstalledPackage overlay : sSmallOverlays) {
            overlay.uninstall();
        }

        for (TestPackageInstaller.InstalledPackage overlay : sLargeOverlays) {
            overlay.uninstall();
        }
    }

    @After
    public void tearDown() throws Exception {
        // Disable all test overlays after each test.
        for (TestPackageInstaller.InstalledPackage overlay : sSmallOverlays) {
            assertSetEnabled(sContext, overlay.getPackageName(), false);
        }

        for (TestPackageInstaller.InstalledPackage overlay : sLargeOverlays) {
            assertSetEnabled(sContext, overlay.getPackageName(), false);
        }
    }

    /**
     * Enables the overlay and waits for the APK path change sto be propagated to the context
     * AssetManager.
     */
    private void assertSetEnabled(Context context, String overlayPackage, boolean eanabled)
            throws Exception {
        sOverlayManager.setEnabled(overlayPackage, true, UserHandle.SYSTEM);

        // Wait for the overlay changes to propagate
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            while (true) {
                for (String path : context.getAssets().getApkPaths()) {
                    if (eanabled == path.contains(overlayPackage)) {
                        return true;
                    }
                }
            }
        });

        sExecutor.execute(task);
        assertTrue("Failed to load overlay " + overlayPackage,
                task.get(20, TimeUnit.SECONDS));
    }

    @Test
    public void setEnabledWarmCache() throws Exception {
        String packageName = sSmallOverlays.get(0).getPackageName();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            assertSetEnabled(sContext, packageName, true);

            // Disable the overlay for the next iteration of the test
            state.pauseTiming();
            assertSetEnabled(sContext, packageName, false);
            state.resumeTiming();
        }
    }

    @Test
    public void setEnabledColdCacheSmallOverlay() throws Exception {
        String packageName = sSmallOverlays.get(0).getPackageName();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            assertSetEnabled(sContext, packageName, true);

            // Disable the overlay and remove the idmap for the next iteration of the test
            state.pauseTiming();
            assertSetEnabled(sContext, packageName, false);
            sOverlayManager.invalidateCachesForOverlay(packageName, UserHandle.SYSTEM);
            state.resumeTiming();
        }
    }

    @Test
    public void setEnabledColdCacheLargeOverlay() throws Exception {
        String packageName = sLargeOverlays.get(0).getPackageName();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            assertSetEnabled(sContext, packageName, true);

            // Disable the overlay and remove the idmap for the next iteration of the test
            state.pauseTiming();
            assertSetEnabled(sContext, packageName, false);
            sOverlayManager.invalidateCachesForOverlay(packageName, UserHandle.SYSTEM);
            state.resumeTiming();
        }
    }

    @Test
    public void setEnabledDisable() throws Exception {
        String packageName = sSmallOverlays.get(0).getPackageName();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            assertSetEnabled(sContext, packageName, true);
            state.resumeTiming();

            assertSetEnabled(sContext, packageName, false);
        }
    }

    @Test
    public void getStringOneSmallOverlay() throws Exception {
        String packageName = sSmallOverlays.get(0).getPackageName();
        assertSetEnabled(sContext, packageName, true);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            sContext.getString(R.string.short_text);
        }

        assertSetEnabled(sContext, packageName, false);
    }

    @Test
    public void getStringOneLargeOverlay() throws Exception {
        String packageName = sLargeOverlays.get(0).getPackageName();
        assertSetEnabled(sContext, packageName, true);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int resId = R.string.short_text000; resId < R.string.short_text255; resId++) {
                sContext.getString(resId);
            }
        }

        assertSetEnabled(sContext, packageName, false);
    }

    @Test
    public void getStringTenOverlays() throws Exception {
        // Enable all test overlays
        for (TestPackageInstaller.InstalledPackage overlay : sSmallOverlays) {
            assertSetEnabled(sContext, overlay.getPackageName(), true);
        }

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            sContext.getString(R.string.short_text);
        }
    }

    @Test
    public void getStringLargeTenOverlays() throws Exception {
        // Enable all test overlays
        for (TestPackageInstaller.InstalledPackage overlay : sLargeOverlays) {
            assertSetEnabled(sContext, overlay.getPackageName(), true);
        }

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int resId = R.string.short_text000; resId < R.string.short_text255; resId++) {
                sContext.getString(resId);
            }
        }
    }
}
