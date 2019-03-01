/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.view.InputChannel;

import androidx.test.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.input.InputManagerService;
import com.android.server.policy.WindowManagerPolicy;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.invocation.InvocationOnMock;

/**
 * A test rule that sets up a fresh WindowManagerService instance before each test and makes sure
 * to properly tear it down after.
 *
 * <p>
 * Usage:
 * <pre>
 * {@literal @}Rule
 *  public final WindowManagerServiceRule mWmRule = new WindowManagerServiceRule();
 * </pre>
 */
public class WindowManagerServiceRule implements TestRule {

    private WindowManagerService mService;
    private TestWindowManagerPolicy mPolicy;

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                runWithDexmakerShareClassLoader(this::setUp);
                try {
                    base.evaluate();
                } finally {
                    tearDown();
                }
            }

            private void setUp() {
                final Context context = InstrumentationRegistry.getTargetContext();

                removeServices();

                LocalServices.addService(DisplayManagerInternal.class,
                        mock(DisplayManagerInternal.class));

                LocalServices.addService(PowerManagerInternal.class,
                        mock(PowerManagerInternal.class));
                final PowerManagerInternal pm =
                        LocalServices.getService(PowerManagerInternal.class);
                PowerSaveState state = new PowerSaveState.Builder().build();
                doReturn(state).when(pm).getLowPowerState(anyInt());

                LocalServices.addService(ActivityManagerInternal.class,
                        mock(ActivityManagerInternal.class));
                final ActivityManagerInternal am =
                        LocalServices.getService(ActivityManagerInternal.class);
                doAnswer((InvocationOnMock invocationOnMock) -> {
                    final Runnable runnable = invocationOnMock.<Runnable>getArgument(0);
                    if (runnable != null) {
                        runnable.run();
                    }
                    return null;
                }).when(am).notifyKeyguardFlagsChanged(any());

                InputManagerService ims = mock(InputManagerService.class);
                // InputChannel is final and can't be mocked.
                InputChannel[] input = InputChannel.openInputChannelPair(TAG_WM);
                if (input != null && input.length > 1) {
                    doReturn(input[1]).when(ims).monitorInput(anyString());
                }

                mService = WindowManagerService.main(context, ims, true, false,
                        false, mPolicy = new TestWindowManagerPolicy(
                                WindowManagerServiceRule.this::getWindowManagerService));

                mService.onInitReady();

                // Display creation is driven by the ActivityManagerService via ActivityStackSupervisor.
                // We emulate those steps here.
                mService.mRoot.createDisplayContent(
                        mService.mDisplayManager.getDisplay(DEFAULT_DISPLAY),
                        mock(DisplayWindowController.class));
            }

            private void removeServices() {
                LocalServices.removeServiceForTest(DisplayManagerInternal.class);
                LocalServices.removeServiceForTest(PowerManagerInternal.class);
                LocalServices.removeServiceForTest(ActivityManagerInternal.class);
                LocalServices.removeServiceForTest(WindowManagerInternal.class);
                LocalServices.removeServiceForTest(WindowManagerPolicy.class);
            }

            private void tearDown() {
                waitUntilWindowManagerHandlersIdle();
                removeServices();
                mService = null;
                mPolicy = null;
            }
        };
    }

    public WindowManagerService getWindowManagerService() {
        return mService;
    }

    public TestWindowManagerPolicy getWindowManagerPolicy() {
        return mPolicy;
    }

    public void waitUntilWindowManagerHandlersIdle() {
        final WindowManagerService wm = getWindowManagerService();
        if (wm != null) {
            wm.mH.runWithScissors(() -> { }, 0);
            wm.mAnimationHandler.runWithScissors(() -> { }, 0);
            SurfaceAnimationThread.getHandler().runWithScissors(() -> { }, 0);
        }
    }
}
