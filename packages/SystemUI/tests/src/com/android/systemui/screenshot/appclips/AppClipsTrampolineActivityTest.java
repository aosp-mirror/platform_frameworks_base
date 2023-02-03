/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.screenshot.appclips;

import static android.app.Instrumentation.ActivityResult;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_SUCCESS;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_USER_CANCELED;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED;
import static android.content.Intent.EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE;

import static com.android.systemui.flags.Flags.SCREENSHOT_APP_CLIPS;
import static com.android.systemui.screenshot.AppClipsTrampolineActivity.ACTION_FINISH_FROM_TRAMPOLINE;
import static com.android.systemui.screenshot.AppClipsTrampolineActivity.EXTRA_SCREENSHOT_URI;
import static com.android.systemui.screenshot.AppClipsTrampolineActivity.PERMISSION_SELF;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.testing.AndroidTestingRunner;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.intercepting.SingleActivityFactory;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.notetask.NoteTaskController;
import com.android.systemui.screenshot.AppClipsTrampolineActivity;
import com.android.wm.shell.bubbles.Bubbles;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@RunWith(AndroidTestingRunner.class)
public final class AppClipsTrampolineActivityTest extends SysuiTestCase {

    private static final String TEST_URI_STRING = "www.test-uri.com";
    private static final Uri TEST_URI = Uri.parse(TEST_URI_STRING);
    private static final int TIME_OUT = 5000;

    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private FeatureFlags mFeatureFlags;
    @Mock
    private Optional<Bubbles> mOptionalBubbles;
    @Mock
    private Bubbles mBubbles;
    @Mock
    private NoteTaskController mNoteTaskController;
    @Main
    private Handler mMainHandler;

    // Using the deprecated ActivityTestRule and SingleActivityFactory to help with injecting mocks
    // and getting result from activity both of which are difficult to do in newer APIs.
    private final SingleActivityFactory<AppClipsTrampolineActivityTestable> mFactory =
            new SingleActivityFactory<>(AppClipsTrampolineActivityTestable.class) {
                @Override
                protected AppClipsTrampolineActivityTestable create(Intent unUsed) {
                    return new AppClipsTrampolineActivityTestable(mDevicePolicyManager,
                            mFeatureFlags, mOptionalBubbles, mNoteTaskController, mMainHandler);
                }
            };

    @Rule
    public final ActivityTestRule<AppClipsTrampolineActivityTestable> mActivityRule =
            new ActivityTestRule<>(mFactory, false, false);

    private Context mContext;
    private Intent mActivityIntent;
    private ComponentName mExpectedComponentName;
    private Intent mKillAppClipsActivityBroadcast;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = getContext();
        mMainHandler = mContext.getMainThreadHandler();

        mActivityIntent = new Intent(mContext, AppClipsTrampolineActivityTestable.class);
        mExpectedComponentName = ComponentName.unflattenFromString(
                mContext.getString(
                        R.string.config_screenshotAppClipsActivityComponent));
        mKillAppClipsActivityBroadcast = new Intent(ACTION_FINISH_FROM_TRAMPOLINE)
                .setComponent(mExpectedComponentName)
                .setPackage(mExpectedComponentName.getPackageName());
    }

    @After
    public void tearDown() {
        mContext.sendBroadcast(mKillAppClipsActivityBroadcast, PERMISSION_SELF);
        mActivityRule.finishActivity();
    }

    @Test
    public void configComponentName_shouldResolve() {
        // Verify component name is setup - has package and class name.
        assertThat(mExpectedComponentName).isNotNull();
        assertThat(mExpectedComponentName.getPackageName()).isNotEmpty();
        assertThat(mExpectedComponentName.getClassName()).isNotEmpty();

        // Verify an intent when launched with above component resolves to the same component to
        // confirm that component from above is available in framework.
        Intent appClipsActivityIntent = new Intent();
        appClipsActivityIntent.setComponent(mExpectedComponentName);
        ResolveInfo resolveInfo = getContext().getPackageManager().resolveActivity(
                appClipsActivityIntent, PackageManager.ResolveInfoFlags.of(0));
        ActivityInfo activityInfo = resolveInfo.activityInfo;

        assertThat(activityInfo.packageName).isEqualTo(
                mExpectedComponentName.getPackageName());
        assertThat(activityInfo.name).isEqualTo(mExpectedComponentName.getClassName());
    }

    @Test
    public void flagOff_shouldFinishWithResultCancel() {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(false);

        mActivityRule.launchActivity(mActivityIntent);

        assertThat(mActivityRule.getActivityResult().getResultCode())
                .isEqualTo(Activity.RESULT_CANCELED);
    }

    @Test
    public void bubblesEmpty_shouldFinishWithFailed() {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(true);
        when(mOptionalBubbles.isEmpty()).thenReturn(true);

        mActivityRule.launchActivity(mActivityIntent);

        ActivityResult actualResult = mActivityRule.getActivityResult();
        assertThat(actualResult.getResultCode()).isEqualTo(Activity.RESULT_OK);
        assertThat(getStatusCodeExtra(actualResult.getResultData()))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
    }

    @Test
    public void taskIdNotAppBubble_shouldFinishWithWindowModeUnsupported() {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(true);
        when(mOptionalBubbles.isEmpty()).thenReturn(false);
        when(mOptionalBubbles.get()).thenReturn(mBubbles);
        when(mBubbles.isAppBubbleTaskId(anyInt())).thenReturn(false);

        mActivityRule.launchActivity(mActivityIntent);

        ActivityResult actualResult = mActivityRule.getActivityResult();
        assertThat(actualResult.getResultCode()).isEqualTo(Activity.RESULT_OK);
        assertThat(getStatusCodeExtra(actualResult.getResultData()))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED);
    }

    @Test
    public void dpmScreenshotBlocked_shouldFinishWithBlockedByAdmin() {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(true);
        when(mOptionalBubbles.isEmpty()).thenReturn(false);
        when(mOptionalBubbles.get()).thenReturn(mBubbles);
        when(mBubbles.isAppBubbleTaskId(anyInt())).thenReturn(true);
        when(mDevicePolicyManager.getScreenCaptureDisabled(eq(null))).thenReturn(true);

        mActivityRule.launchActivity(mActivityIntent);

        ActivityResult actualResult = mActivityRule.getActivityResult();
        assertThat(actualResult.getResultCode()).isEqualTo(Activity.RESULT_OK);
        assertThat(getStatusCodeExtra(actualResult.getResultData()))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN);
    }

    @Test
    public void startAppClipsActivity_userCanceled_shouldReturnUserCanceled() {
        mockToSatisfyAllPrerequisites();

        AppClipsTrampolineActivityTestable activity = mActivityRule.launchActivity(mActivityIntent);
        waitForIdleSync();

        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE,
                CAPTURE_CONTENT_FOR_NOTE_USER_CANCELED);
        activity.getResultReceiverForTest().send(Activity.RESULT_OK, bundle);
        waitForIdleSync();

        ActivityResult actualResult = mActivityRule.getActivityResult();
        assertThat(actualResult.getResultCode()).isEqualTo(Activity.RESULT_OK);
        assertThat(getStatusCodeExtra(actualResult.getResultData()))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_USER_CANCELED);
    }

    @Test
    public void startAppClipsActivity_shouldReturnSuccess() {
        mockToSatisfyAllPrerequisites();

        AppClipsTrampolineActivityTestable activity = mActivityRule.launchActivity(mActivityIntent);
        waitForIdleSync();

        Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA_SCREENSHOT_URI, TEST_URI);
        bundle.putInt(EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE, CAPTURE_CONTENT_FOR_NOTE_SUCCESS);
        activity.getResultReceiverForTest().send(Activity.RESULT_OK, bundle);
        waitForIdleSync();

        ActivityResult actualResult = mActivityRule.getActivityResult();
        assertThat(actualResult.getResultCode()).isEqualTo(Activity.RESULT_OK);
        assertThat(getStatusCodeExtra(actualResult.getResultData()))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_SUCCESS);
        assertThat(actualResult.getResultData().getData()).isEqualTo(TEST_URI);
    }

    private void mockToSatisfyAllPrerequisites() {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(true);
        when(mOptionalBubbles.isEmpty()).thenReturn(false);
        when(mOptionalBubbles.get()).thenReturn(mBubbles);
        when(mBubbles.isAppBubbleTaskId(anyInt())).thenReturn(true);
        when(mDevicePolicyManager.getScreenCaptureDisabled(eq(null))).thenReturn(false);
    }

    public static final class AppClipsTrampolineActivityTestable extends
            AppClipsTrampolineActivity {

        public AppClipsTrampolineActivityTestable(DevicePolicyManager devicePolicyManager,
                FeatureFlags flags,
                Optional<Bubbles> optionalBubbles,
                NoteTaskController noteTaskController,
                @Main Handler mainHandler) {
            super(devicePolicyManager, flags, optionalBubbles, noteTaskController, mainHandler);
        }
    }

    private static int getStatusCodeExtra(Intent intent) {
        return intent.getIntExtra(EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE, -100);
    }
}
