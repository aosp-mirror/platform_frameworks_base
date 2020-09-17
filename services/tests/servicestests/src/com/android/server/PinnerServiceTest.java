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
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;

import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.BufferedReader;
import java.io.CharArrayWriter;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidTestingRunner.class)
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

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

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

        mContext = spy(mContext);

        // Get HOME (Launcher) package
        mHomePackageResolveInfo = mContext.getPackageManager().resolveActivityAsUser(homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, 0);
        mUpdatedPackages.add(mHomePackageResolveInfo.activityInfo.applicationInfo.packageName);
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
        // unpin all packages
        Method unpinAppMethod = PinnerService.class.getDeclaredMethod("unpinApp", int.class);
        unpinAppMethod.setAccessible(true);
        unpinAppMethod.invoke(pinnerService, KEY_HOME);
        unpinAppMethod.invoke(pinnerService, KEY_CAMERA);
        unpinAppMethod.invoke(pinnerService, KEY_ASSISTANT);
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

    private int getPinnedSize(PinnerService pinnerService) throws Exception {
        final String totalSizeToken = "Total size: ";
        String dumpOutput = getPinnerServiceDump(pinnerService);
        BufferedReader bufReader = new BufferedReader(new StringReader(dumpOutput));
        Optional<Integer> size = bufReader.lines().filter(s -> s.contains(totalSizeToken))
                .map(s -> Integer.valueOf(s.substring(totalSizeToken.length()))).findAny();
        return size.orElse(-1);
    }

    @Test
    public void testPinHomeApp() throws Exception {
        // Enable HOME app pinning
        Resources res = mock(Resources.class);
        doReturn(true).when(res).getBoolean(com.android.internal.R.bool.config_pinnerHomeApp);
        when(mContext.getResources()).thenReturn(res);
        PinnerService pinnerService = new PinnerService(mContext);

        ArraySet<Integer> pinKeys = getPinKeys(pinnerService);
        assertThat(pinKeys.valueAt(0)).isEqualTo(KEY_HOME);

        pinnerService.update(mUpdatedPackages, true);

        waitForPinnerService(pinnerService);

        ArrayMap<Integer, Object> pinnedApps = getPinnedApps(pinnerService);
        assertThat(pinnedApps.get(KEY_HOME)).isNotNull();

        // Check if dump() reports total pinned bytes
        int totalPinnedSizeBytes = getPinnedSize(pinnerService);
        assertThat(totalPinnedSizeBytes).isGreaterThan(0);

        // Make sure pinned files are unmapped
        unpinAll(pinnerService);
    }

    @Test
    public void testPinHomeAppOnBootCompleted() throws Exception {
        // Enable HOME app pinning
        Resources res = mock(Resources.class);
        doReturn(true).when(res).getBoolean(com.android.internal.R.bool.config_pinnerHomeApp);
        when(mContext.getResources()).thenReturn(res);
        PinnerService pinnerService = new PinnerService(mContext);

        ArraySet<Integer> pinKeys = getPinKeys(pinnerService);
        assertThat(pinKeys.valueAt(0)).isEqualTo(KEY_HOME);

        pinnerService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        waitForPinnerService(pinnerService);

        ArrayMap<Integer, Object> pinnedApps = getPinnedApps(pinnerService);
        assertThat(pinnedApps.get(KEY_HOME)).isNotNull();

        // Check if dump() reports total pinned bytes
        int totalPinnedSizeBytes = getPinnedSize(pinnerService);
        assertThat(totalPinnedSizeBytes).isGreaterThan(0);

        // Make sure pinned files are unmapped
        unpinAll(pinnerService);
    }

    @Test
    public void testNothingToPin() throws Exception {
        // No package enabled for pinning
        Resources res = mock(Resources.class);
        when(mContext.getResources()).thenReturn(res);
        PinnerService pinnerService = new PinnerService(mContext);

        ArraySet<Integer> pinKeys = getPinKeys(pinnerService);
        assertThat(pinKeys).isEmpty();

        pinnerService.update(mUpdatedPackages, true);

        waitForPinnerService(pinnerService);

        ArrayMap<Integer, Object> pinnedApps = getPinnedApps(pinnerService);
        assertThat(pinnedApps).isEmpty();

        // Check if dump() reports total pinned bytes
        int totalPinnedSizeBytes = getPinnedSize(pinnerService);
        assertThat(totalPinnedSizeBytes).isEqualTo(0);

        // Make sure pinned files are unmapped
        unpinAll(pinnerService);
    }

}
