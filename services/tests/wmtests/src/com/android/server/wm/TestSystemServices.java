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

package com.android.server.wm;

import static android.testing.DexmakerShareClassLoaderRule.runWithDexmakerShareClassLoader;
import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.nullable;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.DisplayManagerInternal;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.UserHandle;
import android.view.Display;
import android.view.InputChannel;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.Watchdog;
import com.android.server.input.InputManagerService;
import com.android.server.policy.WindowManagerPolicy;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;

/**
 * A Test utility class to create a mock {@link WindowManagerService} instance for tests.
 */
class TestSystemServices {
    private static StaticMockitoSession sMockitoSession;
    private static WindowManagerService sService;
    private static TestWindowManagerPolicy sPolicy;

    static void setUpWindowManagerService() {
        sMockitoSession = mockitoSession()
                .spyStatic(LockGuard.class)
                .spyStatic(Watchdog.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        runWithDexmakerShareClassLoader(TestSystemServices::setUpTestWindowService);
    }

    static void tearDownWindowManagerService() {
        waitUntilWindowManagerHandlersIdle();
        removeLocalServices();
        sService = null;
        sPolicy = null;

        sMockitoSession.finishMocking();
    }

    private static void setUpTestWindowService() {
        doReturn(null).when(() -> LockGuard.installLock(any(), anyInt()));
        doReturn(mock(Watchdog.class)).when(Watchdog::getInstance);

        final Context context = getInstrumentation().getTargetContext();
        spyOn(context);

        doReturn(null).when(context)
                .registerReceiver(nullable(BroadcastReceiver.class), any(IntentFilter.class));
        doReturn(null).when(context)
                .registerReceiverAsUser(any(BroadcastReceiver.class), any(UserHandle.class),
                        any(IntentFilter.class), nullable(String.class), nullable(Handler.class));

        final ContentResolver contentResolver = context.getContentResolver();
        spyOn(contentResolver);
        doNothing().when(contentResolver)
                .registerContentObserver(any(Uri.class), anyBoolean(), any(ContentObserver.class),
                        anyInt());

        final AppOpsManager appOpsManager = mock(AppOpsManager.class);
        doReturn(appOpsManager).when(context)
                .getSystemService(eq(Context.APP_OPS_SERVICE));

        removeLocalServices();

        final DisplayManagerInternal dmi = mock(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, dmi);

        final PowerManagerInternal pmi = mock(PowerManagerInternal.class);
        LocalServices.addService(PowerManagerInternal.class, pmi);
        final PowerSaveState state = new PowerSaveState.Builder().build();
        doReturn(state).when(pmi).getLowPowerState(anyInt());

        final ActivityManagerInternal ami = mock(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, ami);

        final ActivityTaskManagerInternal atmi = mock(ActivityTaskManagerInternal.class);
        LocalServices.addService(ActivityTaskManagerInternal.class, atmi);
        doAnswer((InvocationOnMock invocationOnMock) -> {
            final Runnable runnable = invocationOnMock.getArgument(0);
            if (runnable != null) {
                runnable.run();
            }
            return null;
        }).when(atmi).notifyKeyguardFlagsChanged(nullable(Runnable.class), anyInt());

        final InputManagerService ims = mock(InputManagerService.class);
        // InputChannel is final and can't be mocked.
        final InputChannel[] input = InputChannel.openInputChannelPair(TAG_WM);
        if (input != null && input.length > 1) {
            doReturn(input[1]).when(ims).monitorInput(anyString(), anyInt());
        }

        final ActivityTaskManagerService atms = mock(ActivityTaskManagerService.class);
        final WindowManagerGlobalLock wmLock = new WindowManagerGlobalLock();
        doReturn(wmLock).when(atms).getGlobalLock();

        sPolicy = new TestWindowManagerPolicy(TestSystemServices::getWindowManagerService);
        sService = WindowManagerService.main(context, ims, false, false, sPolicy, atms);

        sService.onInitReady();

        final Display display = sService.mDisplayManager.getDisplay(DEFAULT_DISPLAY);
        // Display creation is driven by the ActivityManagerService via
        // ActivityStackSupervisor. We emulate those steps here.
        sService.mRoot.createDisplayContent(display, mock(ActivityDisplay.class));
    }

    private static void removeLocalServices() {
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerPolicy.class);
    }

    static WindowManagerService getWindowManagerService() {
        return sService;
    }

    static void cleanupWindowManagerHandlers() {
        final WindowManagerService wm = getWindowManagerService();
        if (wm == null) {
            return;
        }
        wm.mH.removeCallbacksAndMessages(null);
        wm.mAnimationHandler.removeCallbacksAndMessages(null);
        SurfaceAnimationThread.getHandler().removeCallbacksAndMessages(null);
    }

    static void waitUntilWindowManagerHandlersIdle() {
        final WindowManagerService wm = getWindowManagerService();
        if (wm == null) {
            return;
        }
        // Removing delayed FORCE_GC message decreases time for waiting idle.
        wm.mH.removeMessages(WindowManagerService.H.FORCE_GC);
        waitHandlerIdle(wm.mH);
        waitHandlerIdle(wm.mAnimationHandler);
        waitHandlerIdle(SurfaceAnimationThread.getHandler());
    }

    private static void waitHandlerIdle(Handler handler) {
        if (!handler.hasMessagesOrCallbacks()) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        // Wait for delayed messages are processed.
        handler.getLooper().getQueue().addIdleHandler(() -> {
            if (handler.hasMessagesOrCallbacks()) {
                return true; // keep idle handler.
            }
            latch.countDown();
            return false; // remove idle handler.
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
        }
    }
}
