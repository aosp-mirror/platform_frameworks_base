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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.testing.TestableContext;
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

    TestStartingSurfaceDrawer mStartingSurfaceDrawer;

    static final class TestStartingSurfaceDrawer extends StartingSurfaceDrawer{
        int mAddWindowForTask = 0;
        int mViewThemeResId;

        TestStartingSurfaceDrawer(Context context, ShellExecutor animExecutor,
                TransactionPool pool) {
            super(context, animExecutor, pool);
        }

        @Override
        protected void postAddWindow(int taskId, IBinder appToken,
                View view, WindowManager wm, WindowManager.LayoutParams params) {
            // listen for addView
            mAddWindowForTask = taskId;
            mViewThemeResId = view.getContext().getThemeResId();
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

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        final TestableContext context = new TestableContext(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
        final WindowManager realWindowManager = context.getSystemService(WindowManager.class);
        final WindowMetrics metrics = realWindowManager.getMaximumWindowMetrics();
        context.addMockSystemService(WindowManager.class, mMockWindowManager);

        spyOn(context);
        spyOn(realWindowManager);
        try {
            doReturn(context).when(context)
                    .createPackageContextAsUser(anyString(), anyInt(), any());
        } catch (PackageManager.NameNotFoundException e) {
            //
        }
        doReturn(metrics).when(mMockWindowManager).getMaximumWindowMetrics();
        doNothing().when(mMockWindowManager).addView(any(), any());

        mStartingSurfaceDrawer = spy(new TestStartingSurfaceDrawer(context,
                new HandlerExecutor(new Handler(Looper.getMainLooper())),
                mTransactionPool));
    }

    @Test
    public void testAddSplashScreenSurface() {
        final int taskId = 1;
        final Handler mainLoop = new Handler(Looper.getMainLooper());
        final StartingWindowInfo windowInfo =
                createWindowInfo(taskId, android.R.style.Theme);
        mStartingSurfaceDrawer.addSplashScreenStartingWindow(windowInfo, mBinder);
        waitHandlerIdle(mainLoop);
        verify(mStartingSurfaceDrawer).postAddWindow(eq(taskId), eq(mBinder), any(), any(), any());
        assertEquals(mStartingSurfaceDrawer.mAddWindowForTask, taskId);

        mStartingSurfaceDrawer.removeStartingWindow(windowInfo.taskInfo.taskId, null, null, false);
        waitHandlerIdle(mainLoop);
        verify(mStartingSurfaceDrawer).removeWindowSynced(eq(taskId), any(), any(), eq(false));
        assertEquals(mStartingSurfaceDrawer.mAddWindowForTask, 0);
    }

    @Test
    public void testFallbackDefaultTheme() {
        final int taskId = 1;
        final Handler mainLoop = new Handler(Looper.getMainLooper());
        final StartingWindowInfo windowInfo =
                createWindowInfo(taskId, 0);
        mStartingSurfaceDrawer.addSplashScreenStartingWindow(windowInfo, mBinder);
        waitHandlerIdle(mainLoop);
        verify(mStartingSurfaceDrawer).postAddWindow(eq(taskId), eq(mBinder), any(), any(), any());
        assertNotEquals(mStartingSurfaceDrawer.mViewThemeResId, 0);
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
        windowInfo.taskInfo = taskInfo;
        return windowInfo;
    }

    private static void waitHandlerIdle(Handler handler) {
        handler.runWithScissors(() -> { }, 0 /* timeout */);
    }
}
