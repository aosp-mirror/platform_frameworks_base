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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityMonitor;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.Rational;
import android.view.SurfaceControl;
import android.window.TaskOrganizer;

import androidx.test.filters.MediumTest;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Build/Install/Run:
 *  atest WmTests:ActivityOptionsTest
 */
@MediumTest
@Presubmit
public class ActivityOptionsTest {

    @Test
    public void testMerge_NoClobber() {
        // Construct some options with set values
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(Integer.MAX_VALUE);
        opts.setLaunchActivityType(ACTIVITY_TYPE_STANDARD);
        opts.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
        opts.setAvoidMoveToFront();
        opts.setLaunchTaskId(Integer.MAX_VALUE);
        opts.setLockTaskEnabled(true);
        opts.setRotationAnimationHint(ROTATION_ANIMATION_ROTATE);
        opts.setTaskAlwaysOnTop(true);
        opts.setTaskOverlay(true, true);
        Bundle optsBundle = opts.toBundle();

        // Try and merge the constructed options with a new set of options
        optsBundle.putAll(ActivityOptions.makeBasic().toBundle());

        // Ensure the set values are not clobbered
        ActivityOptions restoredOpts = ActivityOptions.fromBundle(optsBundle);
        assertEquals(Integer.MAX_VALUE, restoredOpts.getLaunchDisplayId());
        assertEquals(ACTIVITY_TYPE_STANDARD, restoredOpts.getLaunchActivityType());
        assertEquals(WINDOWING_MODE_FULLSCREEN, restoredOpts.getLaunchWindowingMode());
        assertTrue(restoredOpts.getAvoidMoveToFront());
        assertEquals(Integer.MAX_VALUE, restoredOpts.getLaunchTaskId());
        assertTrue(restoredOpts.getLockTaskMode());
        assertEquals(ROTATION_ANIMATION_ROTATE, restoredOpts.getRotationAnimationHint());
        assertTrue(restoredOpts.getTaskAlwaysOnTop());
        assertTrue(restoredOpts.getTaskOverlay());
        assertTrue(restoredOpts.canTaskOverlayResume());
    }

    @Test
    public void testMakeLaunchIntoPip() {
        // Construct some params with set values
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(1, 1))
                .build();
        // Construct ActivityOptions via makeLaunchIntoPip
        ActivityOptions opts = ActivityOptions.makeLaunchIntoPip(params);

        // Verify the params in ActivityOptions has the right flag being turned on
        assertNotNull(opts.getLaunchIntoPipParams());
        assertTrue(opts.isLaunchIntoPip());
    }

    @Test
    public void testTransferLaunchCookie() {
        final Binder cookie = new Binder();
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchCookie(cookie);
        final Instrumentation instrumentation = getInstrumentation();
        final Context context = instrumentation.getContext();
        final ComponentName trampoline = new ComponentName(context, TrampolineActivity.class);
        final ComponentName main = new ComponentName(context, MainActivity.class);
        final Intent intent = new Intent().setComponent(trampoline)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityMonitor monitor = new ActivityMonitor(main.getClassName(),
                null /* result */, false /* block */);
        instrumentation.addMonitor(monitor);
        final CountDownLatch mainLatch = new CountDownLatch(1);
        final IBinder[] appearedCookies = new IBinder[2];
        final TaskOrganizer organizer = new TaskOrganizer() {
            @Override
            public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
                try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                    t.show(leash).apply();
                }
                int cookieIndex = -1;
                if (trampoline.equals(taskInfo.baseActivity)) {
                    cookieIndex = 0;
                } else if (main.equals(taskInfo.baseActivity)) {
                    cookieIndex = 1;
                }
                if (cookieIndex >= 0) {
                    appearedCookies[cookieIndex] = taskInfo.launchCookies.isEmpty()
                            ? null : taskInfo.launchCookies.get(0);
                    if (cookieIndex == 1) {
                        mainLatch.countDown();
                    }
                }
            }
        };
        Activity mainActivity = null;
        try {
            organizer.registerOrganizer();
            context.startActivity(intent, options.toBundle());
            try {
                mainLatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            mainActivity = monitor.getLastActivity();

            assertNotNull(mainActivity);
            assertNotEquals(TrampolineActivity.sTaskId, mainActivity.getTaskId());
            assertNull("Trampoline task must not have cookie", appearedCookies[0]);
            assertEquals("Main task must get the same cookie", cookie, appearedCookies[1]);
        } finally {
            organizer.unregisterOrganizer();
            instrumentation.removeMonitor(monitor);
            if (mainActivity != null) {
                mainActivity.finish();
            }
        }
    }

    public static class TrampolineActivity extends Activity {
        static int sTaskId;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            sTaskId = getTaskId();
            startActivity(new Intent(this, MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        }
    }

    public static class MainActivity extends Activity {
    }
}
