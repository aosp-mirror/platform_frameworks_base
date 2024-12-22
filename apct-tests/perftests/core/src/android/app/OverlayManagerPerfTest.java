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
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Benchmarks for {@link android.content.om.OverlayManager}.
 */
@LargeTest
public class OverlayManagerPerfTest {
    private static final int OVERLAY_PKG_COUNT = 10;
    private static Context sContext;
    private static OverlayManager sOverlayManager;
    private static ArrayList<TestPackageInstaller.InstalledPackage> sSmallOverlays =
            new ArrayList<>();
    private static ArrayList<TestPackageInstaller.InstalledPackage> sLargeOverlays =
            new ArrayList<>();

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    // Uncheck the checked exceptions in a callable for convenient stream usage.
    // Any exception will fail the test anyway.
    private static <T> T uncheck(Callable<T> c) {
        try {
            return c.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeClass
    public static void classSetUp() throws Exception {
        sContext = InstrumentationRegistry.getTargetContext();
        sOverlayManager = new OverlayManager(sContext);

        // Install all of the test overlays. Play Protect likes to block these for 10 sec each
        // so let's install them in parallel to speed up the wait.
        final var installer = new TestPackageInstaller(sContext);
        final var es = Executors.newFixedThreadPool(2 * OVERLAY_PKG_COUNT);
        final var smallFutures = new ArrayList<Future<TestPackageInstaller.InstalledPackage>>(
                OVERLAY_PKG_COUNT);
        final var largeFutures = new ArrayList<Future<TestPackageInstaller.InstalledPackage>>(
                OVERLAY_PKG_COUNT);
        for (int i = 0; i < OVERLAY_PKG_COUNT; i++) {
            final var index = i;
            smallFutures.add(es.submit(() -> installer.installPackage("Overlay" + index + ".apk")));
            largeFutures.add(
                    es.submit(() -> installer.installPackage("LargeOverlay" + index + ".apk")));
        }
        es.shutdown();
        assertTrue(es.awaitTermination(15 * 2 * OVERLAY_PKG_COUNT, TimeUnit.SECONDS));
        sSmallOverlays.addAll(smallFutures.stream().map(f -> uncheck(f::get)).sorted(
                Comparator.comparing(
                        TestPackageInstaller.InstalledPackage::getPackageName)).toList());
        sLargeOverlays.addAll(largeFutures.stream().map(f -> uncheck(f::get)).sorted(
                Comparator.comparing(
                        TestPackageInstaller.InstalledPackage::getPackageName)).toList());
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
        assertSetEnabled(false, sContext,
                Stream.concat(sSmallOverlays.stream(), sLargeOverlays.stream()).map(
                        p -> p.getPackageName()));
    }

    /**
     * Enables the overlay and waits for the APK path changes to be propagated to the context
     * AssetManager.
     */
    private void assertSetEnabled(boolean enabled, Context context, Stream<String> packagesStream) {
        final var overlayPackages = packagesStream.toList();
        overlayPackages.forEach(
                name -> sOverlayManager.setEnabled(name, enabled, UserHandle.SYSTEM));

        // Wait for the overlay changes to propagate
        final var endTime = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        final var expectedPackagesFound = enabled ? overlayPackages.size() : 0;
        boolean assetsUpdated = false;
        do {
            final var packagesFound = Arrays.stream(context.getAssets().getApkPaths()).filter(
                    assetPath -> overlayPackages.stream().anyMatch(assetPath::contains)).count();
            if (packagesFound == expectedPackagesFound) {
                assetsUpdated = true;
                break;
            }
            Thread.yield();
        } while (System.nanoTime() < endTime);
        assertTrue("Failed to set state to " + enabled + " for overlays " + overlayPackages,
                assetsUpdated);
    }

    private void assertSetEnabled(boolean enabled, Context context, String overlayPackage) {
        assertSetEnabled(enabled, context, Stream.of(overlayPackage));
    }

    @Test
    public void setEnabledWarmCache() throws Exception {
        String packageName = sSmallOverlays.get(0).getPackageName();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            assertSetEnabled(true, sContext, packageName);

            // Disable the overlay for the next iteration of the test
            state.pauseTiming();
            assertSetEnabled(false, sContext, packageName);
            state.resumeTiming();
        }
    }

    @Test
    public void setEnabledColdCacheSmallOverlay() throws Exception {
        String packageName = sSmallOverlays.get(0).getPackageName();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            assertSetEnabled(true, sContext, packageName);

            // Disable the overlay and remove the idmap for the next iteration of the test
            state.pauseTiming();
            assertSetEnabled(false, sContext, packageName);
            sOverlayManager.invalidateCachesForOverlay(packageName, UserHandle.SYSTEM);
            state.resumeTiming();
        }
    }

    @Test
    public void setEnabledColdCacheLargeOverlay() throws Exception {
        String packageName = sLargeOverlays.get(0).getPackageName();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            assertSetEnabled(true, sContext, packageName);

            // Disable the overlay and remove the idmap for the next iteration of the test
            state.pauseTiming();
            assertSetEnabled(false, sContext, packageName);
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
            assertSetEnabled(true, sContext, packageName);
            state.resumeTiming();

            assertSetEnabled(false, sContext, packageName);
        }
    }

    @Test
    public void getStringOneSmallOverlay() throws Exception {
        String packageName = sSmallOverlays.get(0).getPackageName();
        assertSetEnabled(true, sContext, packageName);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            sContext.getString(R.string.short_text);
        }
    }

    @Test
    public void getStringOneLargeOverlay() throws Exception {
        String packageName = sLargeOverlays.get(0).getPackageName();
        assertSetEnabled(true, sContext, packageName);

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int resId = R.string.short_text000; resId < R.string.short_text255; resId++) {
                sContext.getString(resId);
            }
        }
    }

    @Test
    public void getStringTenOverlays() throws Exception {
        // Enable all small test overlays
        assertSetEnabled(true, sContext, sSmallOverlays.stream().map(p -> p.getPackageName()));

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            sContext.getString(R.string.short_text);
        }
    }

    @Test
    public void getStringLargeTenOverlays() throws Exception {
        // Enable all large test overlays
        assertSetEnabled(true, sContext, sLargeOverlays.stream().map(p -> p.getPackageName()));

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int resId = R.string.short_text000; resId < R.string.short_text255; resId++) {
                sContext.getString(resId);
            }
        }
    }
}
