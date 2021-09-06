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
package com.android.wm.shell.startingsurface;

import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SPLASH_SCREEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.testing.TestableContext;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.window.StartingWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.IntSupplier;

/**
 * Tests for the starting surface drawer.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class StartingSurfaceDrawerTests {
    @Mock
    private IBinder mBinder;
    @Mock
    private WindowManager mMockWindowManager;
    @Mock
    private TransactionPool mTransactionPool;

    private final Handler mTestHandler = new Handler(Looper.getMainLooper());
    private final TestableContext mTestContext = new TestContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext());
    TestStartingSurfaceDrawer mStartingSurfaceDrawer;

    static final class TestStartingSurfaceDrawer extends StartingSurfaceDrawer{
        int mAddWindowForTask = 0;

        TestStartingSurfaceDrawer(Context context, ShellExecutor splashScreenExecutor,
                TransactionPool pool) {
            super(context, splashScreenExecutor, pool);
        }

        @Override
        protected boolean addWindow(int taskId, IBinder appToken, View view, Display display,
                WindowManager.LayoutParams params, int suggestType) {
            // listen for addView
            mAddWindowForTask = taskId;
            // Do not wait for background color
            return false;
        }

        @Override
        protected void removeWindowSynced(int taskId, SurfaceControl leash, Rect frame,
                boolean playRevealAnimation) {
            // listen for removeView
            if (mAddWindowForTask == taskId) {
                mAddWindowForTask = 0;
            }
        }
    }

    private static class TestContext extends TestableContext {
        TestContext(Context context) {
            super(context);
        }

        @Override
        public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
                throws PackageManager.NameNotFoundException {
            return this;
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            return null;
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        final WindowManager realWindowManager = mTestContext.getSystemService(WindowManager.class);
        final WindowMetrics metrics = realWindowManager.getMaximumWindowMetrics();
        mTestContext.addMockSystemService(WindowManager.class, mMockWindowManager);

        doReturn(metrics).when(mMockWindowManager).getMaximumWindowMetrics();
        doNothing().when(mMockWindowManager).addView(any(), any());

        mStartingSurfaceDrawer = spy(new TestStartingSurfaceDrawer(mTestContext,
                new HandlerExecutor(mTestHandler), mTransactionPool));
    }

    @Test
    public void testAddSplashScreenSurface() {
        final int taskId = 1;
        final StartingWindowInfo windowInfo =
                createWindowInfo(taskId, android.R.style.Theme);
        mStartingSurfaceDrawer.addSplashScreenStartingWindow(windowInfo, mBinder,
                STARTING_WINDOW_TYPE_SPLASH_SCREEN);
        waitHandlerIdle(mTestHandler);
        verify(mStartingSurfaceDrawer).addWindow(eq(taskId), eq(mBinder), any(), any(), any(),
                eq(STARTING_WINDOW_TYPE_SPLASH_SCREEN));
        assertEquals(mStartingSurfaceDrawer.mAddWindowForTask, taskId);

        mStartingSurfaceDrawer.removeStartingWindow(windowInfo.taskInfo.taskId, null, null, false);
        waitHandlerIdle(mTestHandler);
        verify(mStartingSurfaceDrawer).removeWindowSynced(eq(taskId), any(), any(), eq(false));
        assertEquals(mStartingSurfaceDrawer.mAddWindowForTask, 0);
    }

    @Test
    public void testFallbackDefaultTheme() {
        final int taskId = 1;
        final StartingWindowInfo windowInfo =
                createWindowInfo(taskId, 0);
        final int[] theme = new int[1];
        doAnswer(invocation -> theme[0] = (Integer) invocation.callRealMethod())
                .when(mStartingSurfaceDrawer).getSplashScreenTheme(eq(0), any());

        mStartingSurfaceDrawer.addSplashScreenStartingWindow(windowInfo, mBinder,
                STARTING_WINDOW_TYPE_SPLASH_SCREEN);
        waitHandlerIdle(mTestHandler);
        verify(mStartingSurfaceDrawer).getSplashScreenTheme(eq(0), any());
        assertNotEquals(theme[0], 0);
    }

    @Test
    public void testColorCache() {
        final String packageName = mTestContext.getPackageName();
        final int configHash = 1;
        final int windowBgColor = 0xff000000;
        final int windowBgResId = 1;
        final IntSupplier windowBgColorSupplier = () -> windowBgColor;
        final SplashscreenContentDrawer.ColorCache colorCache =
                mStartingSurfaceDrawer.mSplashscreenContentDrawer.mColorCache;
        final SplashscreenContentDrawer.ColorCache.WindowColor windowColor1 =
                colorCache.getWindowColor(packageName, configHash, windowBgColor, windowBgResId,
                        windowBgColorSupplier);
        assertEquals(windowBgColor, windowColor1.mBgColor);
        assertEquals(0, windowColor1.mReuseCount);

        final SplashscreenContentDrawer.ColorCache.WindowColor windowColor2 =
                colorCache.getWindowColor(packageName, configHash, windowBgColor, windowBgResId,
                        windowBgColorSupplier);
        assertEquals(windowColor1, windowColor2);
        assertEquals(1, windowColor1.mReuseCount);

        final Intent packageRemoved = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        packageRemoved.setData(Uri.parse("package:" + packageName));
        colorCache.onReceive(mTestContext, packageRemoved);

        final SplashscreenContentDrawer.ColorCache.WindowColor windowColor3 =
                colorCache.getWindowColor(packageName, configHash, windowBgColor, windowBgResId,
                        windowBgColorSupplier);
        assertEquals(0, windowColor3.mReuseCount);
    }

    private StartingWindowInfo createWindowInfo(int taskId, int themeResId) {
        StartingWindowInfo windowInfo = new StartingWindowInfo();
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.packageName = "test";
        info.theme = themeResId;
        final ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.topActivityInfo = info;
        taskInfo.taskId = taskId;
        windowInfo.targetActivityInfo = info;
        windowInfo.taskInfo = taskInfo;
        return windowInfo;
    }

    private static void waitHandlerIdle(Handler handler) {
        handler.runWithScissors(() -> { }, 0 /* timeout */);
    }
}
