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

import static com.android.internal.infra.AndroidFuture.completedFuture;
import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_TRIGGERED;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.EXTRA_SCREENSHOT_URI;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.intercepting.SingleActivityFactory;

import com.android.internal.infra.ServiceConnector;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IAppClipsService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.notetask.NoteTaskController;
import com.android.systemui.res.R;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

@RunWith(AndroidTestingRunner.class)
public final class AppClipsTrampolineActivityTest extends SysuiTestCase {

    private static final String TEST_URI_STRING = "www.test-uri.com";
    private static final Uri TEST_URI = Uri.parse(TEST_URI_STRING);
    private static final int TEST_UID = 42;
    private static final String TEST_CALLING_PACKAGE = "test-calling-package";

    @Mock private ServiceConnector<IAppClipsService> mServiceConnector;
    @Mock
    private NoteTaskController mNoteTaskController;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private BroadcastSender mBroadcastSender;
    @Background
    private Executor mBgExecutor;
    @Main
    private Executor mMainExecutor;
    @Main
    private Handler mMainHandler;

    // Using the deprecated ActivityTestRule and SingleActivityFactory to help with injecting mocks
    // and getting result from activity both of which are difficult to do in newer APIs.
    private final SingleActivityFactory<AppClipsTrampolineActivityTestable> mFactory =
            new SingleActivityFactory<>(AppClipsTrampolineActivityTestable.class) {
                @Override
                protected AppClipsTrampolineActivityTestable create(Intent unUsed) {
                    return new AppClipsTrampolineActivityTestable(mServiceConnector,
                            mNoteTaskController, mPackageManager, mUiEventLogger, mBroadcastSender,
                            mBgExecutor, mMainExecutor, mMainHandler);
                }
            };

    @Rule
    public final ActivityTestRule<AppClipsTrampolineActivityTestable> mActivityRule =
            new ActivityTestRule<>(mFactory, false, false);

    private Intent mActivityIntent;
    private ComponentName mExpectedComponentName;

    @Before
    public void setUp() {
        assumeFalse("Skip test: does not apply to watches",
            mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH));
        assumeFalse("Skip test: does not apply to TVs",
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK));

        MockitoAnnotations.initMocks(this);
        mBgExecutor = MoreExecutors.directExecutor();
        mMainExecutor = MoreExecutors.directExecutor();
        mMainHandler = mContext.getMainThreadHandler();

        mActivityIntent = new Intent(mContext, AppClipsTrampolineActivityTestable.class);
        mExpectedComponentName = ComponentName.unflattenFromString(
                mContext.getString(
                        R.string.config_screenshotAppClipsActivityComponent));
    }

    @After
    public void tearDown() {
        mActivityRule.finishActivity();
    }

    @Test
    public void appClipsActivityConfig_shouldBeConfigured() {
        // Verify component name is setup - has package and class name.
        assertThat(mExpectedComponentName).isNotNull();
        assertThat(mExpectedComponentName.getPackageName()).isNotEmpty();
        assertThat(mExpectedComponentName.getClassName()).isNotEmpty();
    }

    @Test
    public void configComponentName_shouldResolve() {
        // Verify an intent when launched with configured component resolves to activity.
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
    public void queryService_returnedFailed_shouldFinishWithFailed() {
        when(mServiceConnector.postForResult(any()))
                .thenReturn(completedFuture(CAPTURE_CONTENT_FOR_NOTE_FAILED));

        mActivityRule.launchActivity(mActivityIntent);

        ActivityResult actualResult = mActivityRule.getActivityResult();
        assertThat(actualResult.getResultCode()).isEqualTo(Activity.RESULT_OK);
        assertThat(getStatusCodeExtra(actualResult.getResultData()))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
        assertThat(mActivityRule.getActivity().isFinishing()).isTrue();
    }

    @Test
    public void queryService_returnedWindowModeUnsupported_shouldFinishWithWindowModeUnsupported() {
        when(mServiceConnector.postForResult(any()))
                .thenReturn(completedFuture(CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED));

        mActivityRule.launchActivity(mActivityIntent);

        ActivityResult actualResult = mActivityRule.getActivityResult();
        assertThat(actualResult.getResultCode()).isEqualTo(Activity.RESULT_OK);
        assertThat(getStatusCodeExtra(actualResult.getResultData()))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED);
        assertThat(mActivityRule.getActivity().isFinishing()).isTrue();
    }

    @Test
    public void queryService_returnedScreenshotBlocked_shouldFinishWithBlockedByAdmin() {
        when(mServiceConnector.postForResult(any()))
                .thenReturn(completedFuture(CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN));

        mActivityRule.launchActivity(mActivityIntent);

        ActivityResult actualResult = mActivityRule.getActivityResult();
        assertThat(actualResult.getResultCode()).isEqualTo(Activity.RESULT_OK);
        assertThat(getStatusCodeExtra(actualResult.getResultData()))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN);
        assertThat(mActivityRule.getActivity().isFinishing()).isTrue();
    }

    @Test
    public void startAppClipsActivity_userCanceled_shouldReturnUserCanceled()
            throws NameNotFoundException {
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
        assertThat(mActivityRule.getActivity().isFinishing()).isTrue();
    }

    @Test
    public void startAppClipsActivity_shouldReturnSuccess()
            throws NameNotFoundException {
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
        assertThat(mActivityRule.getActivity().isFinishing()).isTrue();
    }

    @Test
    public void startAppClipsActivity_shouldLogUiEvent()
            throws NameNotFoundException {
        mockToSatisfyAllPrerequisites();

        mActivityRule.launchActivity(mActivityIntent);
        waitForIdleSync();

        verify(mUiEventLogger).log(SCREENSHOT_FOR_NOTE_TRIGGERED, TEST_UID, TEST_CALLING_PACKAGE);
    }

    private void mockToSatisfyAllPrerequisites() throws NameNotFoundException {
        when(mServiceConnector.postForResult(any()))
                .thenReturn(completedFuture(CAPTURE_CONTENT_FOR_NOTE_SUCCESS));

        ApplicationInfo testApplicationInfo = new ApplicationInfo();
        testApplicationInfo.uid = TEST_UID;
        when(mPackageManager.getApplicationInfoAsUser(eq(TEST_CALLING_PACKAGE),
                any(ApplicationInfoFlags.class),
                eq(mContext.getUser().getIdentifier()))).thenReturn(testApplicationInfo);
    }

    public static final class AppClipsTrampolineActivityTestable extends
            AppClipsTrampolineActivity {

        Intent mStartedIntent;
        UserHandle mStartingUser;

        public AppClipsTrampolineActivityTestable(
                ServiceConnector<IAppClipsService> serviceServiceConnector,
                NoteTaskController noteTaskController, PackageManager packageManager,
                UiEventLogger uiEventLogger, BroadcastSender broadcastSender,
                @Background Executor bgExecutor, @Main Executor mainExecutor,
                @Main Handler mainHandler) {
            super(serviceServiceConnector, noteTaskController, packageManager, uiEventLogger,
                    broadcastSender, bgExecutor, mainExecutor, mainHandler);
        }

        @Override
        public String getCallingPackage() {
            return TEST_CALLING_PACKAGE;
        }

        @Override
        public void startActivity(Intent unUsed) {
            // Ignore this intent to avoid App Clips screenshot editing activity from starting.
        }

        @Override
        public void startActivityAsUser(Intent startedIntent, UserHandle startingUser) {
            mStartedIntent = startedIntent;
            mStartingUser = startingUser;
        }
    }

    private static int getStatusCodeExtra(Intent intent) {
        return intent.getIntExtra(EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE, -100);
    }
}
