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
import com.android.server.wm.utils.MockTracker;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.quality.Strictness;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JUnit test rule to create a mock {@link WindowManagerService} instance for tests.
 */
public class SystemServicesTestRule implements TestRule {

    private static final String TAG = SystemServicesTestRule.class.getSimpleName();

    private final AtomicBoolean mCurrentMessagesProcessed = new AtomicBoolean(false);

    private MockTracker mMockTracker;
    private StaticMockitoSession mMockitoSession;
    private WindowManagerService mWindowManagerService;
    private TestWindowManagerPolicy mWindowManagerPolicy;

    /** {@link MockTracker} to track mocks created by {@link SystemServicesTestRule}. */
    private static class Tracker extends MockTracker {
        // This empty extended class is necessary since Mockito distinguishes a listener by it
        // class.
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    runWithDexmakerShareClassLoader(SystemServicesTestRule.this::setUp);
                    base.evaluate();
                } finally {
                    tearDown();
                }
            }
        };
    }

    private void setUp() {
        mMockTracker = new Tracker();

        mMockitoSession = mockitoSession()
                .spyStatic(LocalServices.class)
                .mockStatic(LockGuard.class)
                .mockStatic(Watchdog.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

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

        final DisplayManagerInternal dmi = mock(DisplayManagerInternal.class);
        doReturn(dmi).when(() -> LocalServices.getService(eq(DisplayManagerInternal.class)));

        final PowerManagerInternal pmi = mock(PowerManagerInternal.class);
        final PowerSaveState state = new PowerSaveState.Builder().build();
        doReturn(state).when(pmi).getLowPowerState(anyInt());
        doReturn(pmi).when(() -> LocalServices.getService(eq(PowerManagerInternal.class)));

        final ActivityManagerInternal ami = mock(ActivityManagerInternal.class);
        doReturn(ami).when(() -> LocalServices.getService(eq(ActivityManagerInternal.class)));

        final ActivityTaskManagerInternal atmi = mock(ActivityTaskManagerInternal.class);
        doAnswer((InvocationOnMock invocationOnMock) -> {
            final Runnable runnable = invocationOnMock.getArgument(0);
            if (runnable != null) {
                runnable.run();
            }
            return null;
        }).when(atmi).notifyKeyguardFlagsChanged(nullable(Runnable.class), anyInt());
        doReturn(atmi).when(() -> LocalServices.getService(eq(ActivityTaskManagerInternal.class)));

        final InputManagerService ims = mock(InputManagerService.class);
        // InputChannel is final and can't be mocked.
        final InputChannel[] input = InputChannel.openInputChannelPair(TAG_WM);
        if (input != null && input.length > 1) {
            doReturn(input[1]).when(ims).monitorInput(anyString(), anyInt());
        }

        final ActivityTaskManagerService atms = mock(ActivityTaskManagerService.class);
        final WindowManagerGlobalLock wmLock = new WindowManagerGlobalLock();
        doReturn(wmLock).when(atms).getGlobalLock();

        mWindowManagerPolicy = new TestWindowManagerPolicy(this::getWindowManagerService);
        mWindowManagerService = WindowManagerService.main(
                context, ims, false, false, mWindowManagerPolicy, atms, StubTransaction::new);

        mWindowManagerService.onInitReady();

        final Display display = mWindowManagerService.mDisplayManager.getDisplay(DEFAULT_DISPLAY);
        // Display creation is driven by the ActivityManagerService via
        // ActivityStackSupervisor. We emulate those steps here.
        mWindowManagerService.mRoot.createDisplayContent(display, mock(ActivityDisplay.class));

        mMockTracker.stopTracking();
    }

    private void tearDown() {
        waitUntilWindowManagerHandlersIdle();
        removeLocalServices();
        mWindowManagerService = null;
        mWindowManagerPolicy = null;
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
            mMockitoSession = null;
        }

        if (mMockTracker != null) {
            mMockTracker.close();
            mMockTracker = null;
        }
    }

    private static void removeLocalServices() {
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.removeServiceForTest(WindowManagerPolicy.class);
    }

    WindowManagerService getWindowManagerService() {
        return mWindowManagerService;
    }

    void cleanupWindowManagerHandlers() {
        final WindowManagerService wm = getWindowManagerService();
        if (wm == null) {
            return;
        }
        wm.mH.removeCallbacksAndMessages(null);
        wm.mAnimationHandler.removeCallbacksAndMessages(null);
        SurfaceAnimationThread.getHandler().removeCallbacksAndMessages(null);
    }

    void waitUntilWindowManagerHandlersIdle() {
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

    private void waitHandlerIdle(Handler handler) {
        synchronized (mCurrentMessagesProcessed) {
            // Add a message to the handler queue and make sure it is fully processed before we move
            // on. This makes sure all previous messages in the handler are fully processed vs. just
            // popping them from the message queue.
            mCurrentMessagesProcessed.set(false);
            handler.post(() -> {
                synchronized (mCurrentMessagesProcessed) {
                    mCurrentMessagesProcessed.set(true);
                    mCurrentMessagesProcessed.notifyAll();
                }
            });
            while (!mCurrentMessagesProcessed.get()) {
                try {
                    mCurrentMessagesProcessed.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }
}
