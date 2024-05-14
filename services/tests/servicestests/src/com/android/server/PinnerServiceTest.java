/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.app.ActivityManagerInternal;
import android.app.pinner.PinnedFileStat;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.testutils.FakeDeviceConfigInterface;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.CharArrayWriter;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class PinnerServiceTest {
    private static final int KEY_CAMERA = 0;
    private static final int KEY_HOME = 1;
    private static final int KEY_ASSISTANT = 2;

    private static final long WAIT_FOR_PINNER_TIMEOUT = TimeUnit.SECONDS.toMillis(2);

    @Rule
    public TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext(), null);

    private final ArraySet<String> mUpdatedPackages = new ArraySet<>();
    private ResolveInfo mHomePackageResolveInfo;
    private FakeDeviceConfigInterface mFakeDeviceConfigInterface;
    private PinnerService.Injector mInjector;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        // PinnerService.onStart will add itself as a local service, remove to avoid conflicts.
        LocalServices.removeServiceForTest(PinnerService.class);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);

        ActivityTaskManagerInternal mockActivityTaskManagerInternal = mock(
                ActivityTaskManagerInternal.class);
        Intent homeIntent = getHomeIntent();

        doReturn(homeIntent).when(mockActivityTaskManagerInternal).getHomeIntent();
        LocalServices.addService(ActivityTaskManagerInternal.class,
                mockActivityTaskManagerInternal);

        ActivityManagerInternal mockActivityManagerInternal = mock(ActivityManagerInternal.class);
        doReturn(true).when(mockActivityManagerInternal).isUidActive(anyInt());
        LocalServices.addService(ActivityManagerInternal.class, mockActivityManagerInternal);

        // Configure the default state to disable any pinning.
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(
                com.android.internal.R.array.config_defaultPinnerServiceFiles, new String[0]);
        resources.addOverride(com.android.internal.R.bool.config_pinnerCameraApp, false);
        resources.addOverride(com.android.internal.R.integer.config_pinnerHomePinBytes, 0);
        resources.addOverride(com.android.internal.R.bool.config_pinnerAssistantApp, false);

        mFakeDeviceConfigInterface = new FakeDeviceConfigInterface();
        setDeviceConfigPinnedAnonSize(0);

        mContext = spy(mContext);

        // Get HOME (Launcher) package
        mHomePackageResolveInfo = mContext.getPackageManager().resolveActivityAsUser(homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, 0);
        mUpdatedPackages.add(mHomePackageResolveInfo.activityInfo.applicationInfo.packageName);

        mInjector = new PinnerService.Injector() {
            @Override
            protected DeviceConfigInterface getDeviceConfigInterface() {
                return mFakeDeviceConfigInterface;
            }

            @Override
            protected void publishBinderService(PinnerService service, Binder binderService) {
                // Suppress this for testing, it's not needed and causes conflitcs.
            }

            @Override
            protected PinnerService.PinnedFile pinFileInternal(String fileToPin,
                    int maxBytesToPin, boolean attemptPinIntrospection) {
                return new PinnerService.PinnedFile(-1,
                        maxBytesToPin, fileToPin, maxBytesToPin);
            }
        };
    }

    @After
    public void tearDown() {
        Mockito.framework().clearInlineMocks();
    }

    private Intent getHomeIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        return intent;
    }

    private void unpinAll(PinnerService pinnerService) throws Exception {
        Method unpinAppsMethod = PinnerService.class.getDeclaredMethod("unpinApps");
        unpinAppsMethod.setAccessible(true);
        unpinAppsMethod.invoke(pinnerService);
        Method unpinAnonRegionMethod = PinnerService.class.getDeclaredMethod("unpinAnonRegion");
        unpinAnonRegionMethod.setAccessible(true);
        unpinAnonRegionMethod.invoke(pinnerService);
    }

    private void waitForPinnerService(PinnerService pinnerService)
            throws NoSuchFieldException, IllegalAccessException {
        // There's no notification/callback when pinning finished
        // Block until pinner handler is done pinning and runs this empty runnable
        Field pinnerHandlerField = PinnerService.class.getDeclaredField("mPinnerHandler");
        pinnerHandlerField.setAccessible(true);
        Handler pinnerServiceHandler = (Handler) pinnerHandlerField.get(pinnerService);
        pinnerServiceHandler.runWithScissors(() -> {
        }, WAIT_FOR_PINNER_TIMEOUT);
    }

    private ArraySet<Integer> getPinKeys(PinnerService pinnerService)
            throws NoSuchFieldException, IllegalAccessException {
        Field pinKeysArrayField = PinnerService.class.getDeclaredField("mPinKeys");
        pinKeysArrayField.setAccessible(true);
        return (ArraySet<Integer>) pinKeysArrayField.get(pinnerService);
    }

    private ArrayMap<Integer, Object> getPinnedApps(PinnerService pinnerService)
            throws NoSuchFieldException, IllegalAccessException {
        Field pinnedAppsField = PinnerService.class.getDeclaredField("mPinnedApps");
        pinnedAppsField.setAccessible(true);
        return (ArrayMap<Integer, Object>) pinnedAppsField.get(
                pinnerService);
    }

    private String getPinnerServiceDump(PinnerService pinnerService) throws Exception {
        Class<?> innerClass = Class.forName(PinnerService.class.getName() + "$BinderService");
        Constructor<?> ctor = innerClass.getDeclaredConstructor(PinnerService.class);
        ctor.setAccessible(true);
        Binder innerInstance = (Binder) ctor.newInstance(pinnerService);
        CharArrayWriter cw = new CharArrayWriter();
        PrintWriter pw = new PrintWriter(cw, true);
        Method dumpMethod = Binder.class.getDeclaredMethod("dump", FileDescriptor.class,
                PrintWriter.class, String[].class);
        dumpMethod.setAccessible(true);
        dumpMethod.invoke(innerInstance, null, pw, null);
        return cw.toString();
    }

    private long getPinnedSize(PinnerService pinnerService) {
        long totalBytesPinned = 0;
        for (PinnedFileStat stat : pinnerService.getPinnerStats()) {
            totalBytesPinned += stat.getBytesPinned();
        }
        return totalBytesPinned;
    }

    private int getPinnedAnonSize(PinnerService pinnerService) {
        List<PinnedFileStat> anonStats = pinnerService.getPinnerStats().stream()
                .filter(pf -> pf.getGroupName().equals(PinnerService.ANON_REGION_STAT_NAME))
                .toList();
        int totalAnon = 0;
        for (PinnedFileStat anonStat : anonStats) {
            totalAnon += anonStat.getBytesPinned();
        }
        return totalAnon;
    }

    private long getTotalPinnedFiles(PinnerService pinnerService) {
        return pinnerService.getPinnerStats().stream().count();
    }

    private void setDeviceConfigPinnedAnonSize(long size) {
        mFakeDeviceConfigInterface.setProperty(
                DeviceConfig.NAMESPACE_RUNTIME_NATIVE,
                "pin_shared_anon_size",
                String.valueOf(size),
                /*makeDefault=*/false);
    }

    @Test
    public void testPinHomeApp() throws Exception {
        // Enable HOME app pinning
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.internal.R.integer.config_pinnerHomePinBytes, 1024);
        PinnerService pinnerService = new PinnerService(mContext, mInjector);
        pinnerService.onStart();

        ArraySet<Integer> pinKeys = getPinKeys(pinnerService);
        assertThat(pinKeys.valueAt(0)).isEqualTo(KEY_HOME);

        pinnerService.update(mUpdatedPackages, true);

        waitForPinnerService(pinnerService);

        ArrayMap<Integer, Object> pinnedApps = getPinnedApps(pinnerService);
        assertThat(pinnedApps.get(KEY_HOME)).isNotNull();

        assertThat(getPinnedSize(pinnerService)).isGreaterThan(0);
        assertThat(getTotalPinnedFiles(pinnerService)).isGreaterThan(0);

        unpinAll(pinnerService);
    }

    @Test
    public void testPinHomeAppOnBootCompleted() throws Exception {
        // Enable HOME app pinning
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.internal.R.integer.config_pinnerHomePinBytes, 1024);
        PinnerService pinnerService = new PinnerService(mContext, mInjector);
        pinnerService.onStart();

        ArraySet<Integer> pinKeys = getPinKeys(pinnerService);
        assertThat(pinKeys.valueAt(0)).isEqualTo(KEY_HOME);

        pinnerService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        waitForPinnerService(pinnerService);

        ArrayMap<Integer, Object> pinnedApps = getPinnedApps(pinnerService);
        assertThat(pinnedApps.get(KEY_HOME)).isNotNull();

        assertThat(getPinnedSize(pinnerService)).isGreaterThan(0);

        unpinAll(pinnerService);
    }

    @Test
    public void testNothingToPin() throws Exception {
        // No package enabled for pinning
        PinnerService pinnerService = new PinnerService(mContext, mInjector);
        pinnerService.onStart();

        ArraySet<Integer> pinKeys = getPinKeys(pinnerService);
        assertThat(pinKeys).isEmpty();

        pinnerService.update(mUpdatedPackages, true);

        waitForPinnerService(pinnerService);

        ArrayMap<Integer, Object> pinnedApps = getPinnedApps(pinnerService);
        assertThat(pinnedApps).isEmpty();

        long totalPinnedSizeBytes = getPinnedSize(pinnerService);
        assertThat(totalPinnedSizeBytes).isEqualTo(0);

        int pinnedAnonSizeBytes = getPinnedAnonSize(pinnerService);
        assertThat(pinnedAnonSizeBytes).isEqualTo(0);

        unpinAll(pinnerService);
    }

    @Test
    public void testPinFile() throws Exception {
        PinnerService pinnerService = new PinnerService(mContext, mInjector);
        pinnerService.onStart();

        pinnerService.pinFile("test_file", 4096, null, "my_group");

        assertThat(getPinnedSize(pinnerService)).isGreaterThan(0);
        assertThat(getTotalPinnedFiles(pinnerService)).isGreaterThan(0);

        unpinAll(pinnerService);
    }

    @Test
    public void testPinAnonRegion() throws Exception {
        setDeviceConfigPinnedAnonSize(32768);

        PinnerService pinnerService = new PinnerService(mContext, mInjector);
        pinnerService.onStart();
        waitForPinnerService(pinnerService);

        // Ensure the dump reflects the requested anon region.
        int pinnedAnonSizeBytes = getPinnedAnonSize(pinnerService);
        assertThat(pinnedAnonSizeBytes).isEqualTo(32768);

        unpinAll(pinnerService);
    }

    @Test
    public void testPinAnonRegionUpdatesOnConfigChange() throws Exception {
        PinnerService pinnerService = new PinnerService(mContext, mInjector);
        pinnerService.onStart();
        waitForPinnerService(pinnerService);

        // Ensure the PinnerService updates itself when the associated DeviceConfig changes.
        setDeviceConfigPinnedAnonSize(65536);
        waitForPinnerService(pinnerService);
        int pinnedAnonSizeBytes = getPinnedAnonSize(pinnerService);
        assertThat(pinnedAnonSizeBytes).isEqualTo(65536);

        // Each update should be reflected in the reported status.
        setDeviceConfigPinnedAnonSize(32768);
        waitForPinnerService(pinnerService);
        pinnedAnonSizeBytes = getPinnedAnonSize(pinnerService);
        assertThat(pinnedAnonSizeBytes).isEqualTo(32768);

        setDeviceConfigPinnedAnonSize(0);
        waitForPinnerService(pinnerService);
        // An empty anon region should clear the associated status entry.
        pinnedAnonSizeBytes = getPinnedAnonSize(pinnerService);
        assertThat(pinnedAnonSizeBytes).isEqualTo(0);

        unpinAll(pinnerService);
    }
}
