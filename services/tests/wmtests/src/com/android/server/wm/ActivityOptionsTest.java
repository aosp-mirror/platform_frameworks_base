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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

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
import android.os.IRemoteCallback;
import android.platform.test.annotations.Presubmit;
import android.util.Log;
import android.util.Rational;
import android.view.SurfaceControl;
import android.window.TaskOrganizer;

import androidx.test.filters.MediumTest;

import com.android.server.wm.utils.CommonUtils;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public void testAbortListenerCalled() {
        AtomicBoolean callbackCalled = new AtomicBoolean(false);

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setOnAnimationAbortListener(new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) {
                callbackCalled.set(true);
            }
        });

        // Verify that the callback is called on abort
        options.abort();
        assertTrue(callbackCalled.get());

        // Verify that the callback survives saving to bundle
        ActivityOptions optionsCopy = ActivityOptions.fromBundle(options.toBundle());
        callbackCalled.set(false);
        optionsCopy.abort();
        assertTrue(callbackCalled.get());
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
                CommonUtils.waitUntilActivityRemoved(mainActivity);
            }
        }
    }

    /**
     * Tests if any unknown key is being used in the ActivityOptions bundle. If so, please review
     * if the newly added bundle should be protected with permissions to avoid malicious attacks.
     *
     * @see SafeActivityOptionsTest#test_getOptions
     */
    @Test
    public void testActivityOptionsFromBundle() {
        // Spy on a bundle that is generated from a basic ActivityOptions.
        final ActivityOptions options = ActivityOptions.makeBasic();
        Bundle bundle = options.toBundle();
        spyOn(bundle);

        // Create a new ActivityOptions from the bundle
        new ActivityOptions(bundle);

        // Verify the keys that are being used.
        final ArgumentCaptor<String> stringCaptor =  ArgumentCaptor.forClass(String.class);
        verify(bundle, atLeastOnce()).getString(stringCaptor.capture());
        verify(bundle, atLeastOnce()).getBoolean(stringCaptor.capture());
        verify(bundle, atLeastOnce()).getParcelable(stringCaptor.capture(), any());
        verify(bundle, atLeastOnce()).getInt(stringCaptor.capture(), anyInt());
        verify(bundle, atLeastOnce()).getBinder(stringCaptor.capture());
        verify(bundle, atLeastOnce()).getBundle(stringCaptor.capture());
        final List<String> keys = stringCaptor.getAllValues();
        final List<String> unknownKeys = new ArrayList<>();
        for (String key : keys) {
            switch (key) {
                case ActivityOptions.KEY_PACKAGE_NAME:
                case ActivityOptions.KEY_LAUNCH_BOUNDS:
                case ActivityOptions.KEY_ANIM_TYPE:
                case ActivityOptions.KEY_ANIM_ENTER_RES_ID:
                case ActivityOptions.KEY_ANIM_EXIT_RES_ID:
                case ActivityOptions.KEY_ANIM_IN_PLACE_RES_ID:
                case ActivityOptions.KEY_ANIM_BACKGROUND_COLOR:
                case ActivityOptions.KEY_ANIM_THUMBNAIL:
                case ActivityOptions.KEY_ANIM_START_X:
                case ActivityOptions.KEY_ANIM_START_Y:
                case ActivityOptions.KEY_ANIM_WIDTH:
                case ActivityOptions.KEY_ANIM_HEIGHT:
                case ActivityOptions.KEY_ANIM_START_LISTENER:
                case ActivityOptions.KEY_SPLASH_SCREEN_THEME:
                case ActivityOptions.KEY_LEGACY_PERMISSION_PROMPT_ELIGIBLE:
                case ActivityOptions.KEY_LAUNCH_ROOT_TASK_TOKEN:
                case ActivityOptions.KEY_LAUNCH_TASK_FRAGMENT_TOKEN:
                case ActivityOptions.KEY_TRANSIENT_LAUNCH:
                case "android:activity.animationFinishedListener":
                    // KEY_ANIMATION_FINISHED_LISTENER
                case "android:activity.animSpecs": // KEY_ANIM_SPECS
                case "android:activity.lockTaskMode": // KEY_LOCK_TASK_MODE
                case "android:activity.shareIdentity": // KEY_SHARE_IDENTITY
                case "android.activity.launchDisplayId": // KEY_LAUNCH_DISPLAY_ID
                case "android.activity.callerDisplayId": // KEY_CALLER_DISPLAY_ID
                case "android.activity.launchTaskDisplayAreaToken":
                    // KEY_LAUNCH_TASK_DISPLAY_AREA_TOKEN
                case "android.activity.launchTaskDisplayAreaFeatureId":
                    // KEY_LAUNCH_TASK_DISPLAY_AREA_FEATURE_ID
                case "android.activity.windowingMode": // KEY_LAUNCH_WINDOWING_MODE
                case "android.activity.activityType": // KEY_LAUNCH_ACTIVITY_TYPE
                case "android.activity.launchTaskId": // KEY_LAUNCH_TASK_ID
                case "android.activity.disableStarting": // KEY_DISABLE_STARTING_WINDOW
                case "android.activity.pendingIntentLaunchFlags":
                    // KEY_PENDING_INTENT_LAUNCH_FLAGS
                case "android.activity.alwaysOnTop": // KEY_TASK_ALWAYS_ON_TOP
                case "android.activity.taskOverlay": // KEY_TASK_OVERLAY
                case "android.activity.taskOverlayCanResume": // KEY_TASK_OVERLAY_CAN_RESUME
                case "android.activity.avoidMoveToFront": // KEY_AVOID_MOVE_TO_FRONT
                case "android.activity.freezeRecentTasksReordering":
                    // KEY_FREEZE_RECENT_TASKS_REORDERING
                case "android:activity.disallowEnterPictureInPictureWhileLaunching":
                    // KEY_DISALLOW_ENTER_PICTURE_IN_PICTURE_WHILE_LAUNCHING
                case "android:activity.applyActivityFlagsForBubbles":
                    // KEY_APPLY_ACTIVITY_FLAGS_FOR_BUBBLES
                case "android:activity.applyMultipleTaskFlagForShortcut":
                    // KEY_APPLY_MULTIPLE_TASK_FLAG_FOR_SHORTCUT
                case "android:activity.applyNoUserActionFlagForShortcut":
                    // KEY_APPLY_NO_USER_ACTION_FLAG_FOR_SHORTCUT
                case "android:activity.transitionCompleteListener":
                    // KEY_TRANSITION_COMPLETE_LISTENER
                case "android:activity.transitionIsReturning": // KEY_TRANSITION_IS_RETURNING
                case "android:activity.sharedElementNames": // KEY_TRANSITION_SHARED_ELEMENTS
                case "android:activity.resultData": // KEY_RESULT_DATA
                case "android:activity.resultCode": // KEY_RESULT_CODE
                case "android:activity.exitCoordinatorIndex": // KEY_EXIT_COORDINATOR_INDEX
                case "android.activity.sourceInfo": // KEY_SOURCE_INFO
                case "android:activity.usageTimeReport": // KEY_USAGE_TIME_REPORT
                case "android:activity.rotationAnimationHint": // KEY_ROTATION_ANIMATION_HINT
                case "android:instantapps.installerbundle": // KEY_INSTANT_APP_VERIFICATION_BUNDLE
                case "android:activity.specsFuture": // KEY_SPECS_FUTURE
                case "android:activity.remoteAnimationAdapter": // KEY_REMOTE_ANIMATION_ADAPTER
                case "android:activity.remoteTransition": // KEY_REMOTE_TRANSITION
                case "android:activity.overrideTaskTransition": // KEY_OVERRIDE_TASK_TRANSITION
                case "android.activity.removeWithTaskOrganizer": // KEY_REMOVE_WITH_TASK_ORGANIZER
                case "android.activity.launchTypeBubble": // KEY_LAUNCHED_FROM_BUBBLE
                case "android.activity.splashScreenStyle": // KEY_SPLASH_SCREEN_STYLE
                case "android.activity.launchIntoPipParams": // KEY_LAUNCH_INTO_PIP_PARAMS
                case "android.activity.dismissKeyguardIfInsecure": // KEY_DISMISS_KEYGUARD_IF_INSECURE
                case "android.activity.pendingIntentCreatorBackgroundActivityStartMode":
                    // KEY_PENDING_INTENT_CREATOR_BACKGROUND_ACTIVITY_START_MODE
                case "android.activity.launchCookie": // KEY_LAUNCH_COOKIE
                case "android:activity.animAbortListener": // KEY_ANIM_ABORT_LISTENER
                    // Existing keys

                    break;
                default:
                    unknownKeys.add(key);
                    break;
            }
        }

        // Report if any unknown key exists.
        for (String key : unknownKeys) {
            Log.e("ActivityOptionsTests", "Unknown key " + key + " is found. "
                    + "Please review if the given bundle should be protected with permissions.");
        }
        assertTrue(unknownKeys.isEmpty());
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
