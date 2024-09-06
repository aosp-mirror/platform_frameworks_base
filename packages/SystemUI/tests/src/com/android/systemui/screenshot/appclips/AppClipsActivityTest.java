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

import static android.app.Activity.RESULT_OK;
import static android.app.ActivityManager.RunningTaskInfo;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;

import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_ACCEPTED;
import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_CANCELLED;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.IActivityTaskManager;
import android.app.assist.AssistContent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.AndroidTestingRunner;
import android.view.Display;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.intercepting.SingleActivityFactory;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;
import com.android.systemui.screenshot.AssistContentRequester;
import com.android.systemui.screenshot.ImageExporter;
import com.android.systemui.settings.UserTracker;

import com.google.common.util.concurrent.Futures;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

@RunWith(AndroidTestingRunner.class)
public final class AppClipsActivityTest extends SysuiTestCase {

    private static final int TEST_UID = 42;
    private static final int TEST_USER_ID = 43;
    private static final Bitmap TEST_BITMAP = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    private static final String TEST_URI_STRING = "www.test-uri.com";
    private static final Uri TEST_URI = Uri.parse(TEST_URI_STRING);
    private static final BiConsumer<Integer, Bundle> FAKE_CONSUMER = (unUsed1, unUsed2) -> {
    };
    private static final String TEST_CALLING_PACKAGE = "test-calling-package";
    private static final int BACKLINKS_TASK_ID = 42;
    private static final String BACKLINKS_TASK_APP_NAME = "Backlinks app";
    private static final String BACKLINKS_TASK_PACKAGE_NAME = "backlinksTaskPackageName";

    private static final RunningTaskInfo TASK_THAT_SUPPORTS_BACKLINKS =
            createTaskInfoForBacklinksTask();
    private static final AssistContent ASSIST_CONTENT_FOR_BACKLINKS_TASK =
            createAssistContentForBacklinksTask();
    private static final Drawable FAKE_DRAWABLE = new ShapeDrawable();

    private ArgumentCaptor<Integer> mDisplayIdCaptor = ArgumentCaptor.forClass(Integer.class);

    @Mock
    private AppClipsCrossProcessHelper mAppClipsCrossProcessHelper;
    @Mock
    private ImageExporter mImageExporter;
    @Mock
    private IActivityTaskManager mAtmService;
    @Mock
    private AssistContentRequester mAssistContentRequester;
    @Mock
    private Context mMockedContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private UiEventLogger mUiEventLogger;

    private AppClipsActivity mActivity;
    private TextView mBacklinksDataTextView;
    private CheckBox mBacklinksIncludeDataCheckBox;
    private TextView mBacklinksCrossProfileErrorTextView;

    // Using the deprecated ActivityTestRule and SingleActivityFactory to help with injecting mocks.
    private final SingleActivityFactory<AppClipsActivityTestable> mFactory =
            new SingleActivityFactory<>(AppClipsActivityTestable.class) {
                @Override
                protected AppClipsActivityTestable create(Intent unUsed) {
                    return new AppClipsActivityTestable(
                            new AppClipsViewModel.Factory(mAppClipsCrossProcessHelper,
                                    mImageExporter, mAtmService, mAssistContentRequester,
                                    mMockedContext, getContext().getMainExecutor(),
                                    directExecutor()),
                            mPackageManager, mUserTracker, mUiEventLogger);
                }
            };

    @Rule
    public final ActivityTestRule<AppClipsActivityTestable> mActivityRule =
            new ActivityTestRule<>(mFactory, false, false);

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);

        when(mUserTracker.getUserId()).thenReturn(TEST_USER_ID);
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;
        when(mPackageManager.getApplicationInfoAsUser(eq(TEST_CALLING_PACKAGE),
                any(ApplicationInfoFlags.class), eq(TEST_USER_ID))).thenReturn(applicationInfo);

        when(mAppClipsCrossProcessHelper.takeScreenshot(anyInt())).thenReturn(TEST_BITMAP);
        ImageExporter.Result result = new ImageExporter.Result();
        result.uri = TEST_URI;
        when(mImageExporter.export(any(Executor.class), any(UUID.class), any(Bitmap.class),
                eq(Process.myUserHandle()), eq(Display.DEFAULT_DISPLAY)))
                .thenReturn(Futures.immediateFuture(result));

        when(mMockedContext.getPackageManager()).thenReturn(mPackageManager);
        when(mMockedContext.createContextAsUser(any(), anyInt())).thenReturn(mMockedContext);
    }

    @After
    public void tearDown() {
        mActivityRule.finishActivity();
    }

    @Test
    @DisableFlags(Flags.FLAG_APP_CLIPS_BACKLINKS)
    public void appClipsLaunched_screenshotDisplayed() {
        launchActivity();

        assertThat(((ImageView) mActivity.findViewById(R.id.preview)).getDrawable()).isNotNull();
        assertThat(mBacklinksDataTextView.getVisibility()).isEqualTo(View.GONE);
        assertThat(mBacklinksIncludeDataCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mBacklinksCrossProfileErrorTextView.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    @Ignore("b/315848285")
    public void screenshotDisplayed_userConsented_screenshotExportedSuccessfully() {
        ResultReceiver resultReceiver = createResultReceiver((resultCode, data) -> {
            assertThat(resultCode).isEqualTo(RESULT_OK);
            assertThat(
                    data.getParcelable(AppClipsTrampolineActivity.EXTRA_SCREENSHOT_URI, Uri.class))
                    .isEqualTo(TEST_URI);
            assertThat(data.getInt(Intent.EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE))
                    .isEqualTo(Intent.CAPTURE_CONTENT_FOR_NOTE_SUCCESS);
        });

        launchActivity(resultReceiver);
        runOnMainThread(() -> mActivity.findViewById(R.id.save).performClick());
        waitForIdleSync();

        assertThat(mActivity.isFinishing()).isTrue();
        verify(mUiEventLogger).log(SCREENSHOT_FOR_NOTE_ACCEPTED, TEST_UID, TEST_CALLING_PACKAGE);
    }

    @Test
    public void screenshotDisplayed_userDeclined() {
        ResultReceiver resultReceiver = createResultReceiver((resultCode, data) -> {
            assertThat(resultCode).isEqualTo(RESULT_OK);
            assertThat(data.getInt(Intent.EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE))
                    .isEqualTo(Intent.CAPTURE_CONTENT_FOR_NOTE_USER_CANCELED);
            assertThat(data.keySet().contains(AppClipsTrampolineActivity.EXTRA_SCREENSHOT_URI))
                    .isFalse();
        });

        launchActivity(resultReceiver);
        runOnMainThread(() -> mActivity.findViewById(R.id.cancel).performClick());
        waitForIdleSync();

        assertThat(mActivity.isFinishing()).isTrue();
        verify(mUiEventLogger).log(SCREENSHOT_FOR_NOTE_CANCELLED, TEST_UID, TEST_CALLING_PACKAGE);
    }

    @Test
    @EnableFlags(Flags.FLAG_APP_CLIPS_BACKLINKS)
    public void appClipsLaunched_backlinks_displayed() throws RemoteException {
        setUpMocksForBacklinks();

        launchActivity();
        waitForIdleSync();

        assertThat(mDisplayIdCaptor.getValue()).isEqualTo(mActivity.getDisplayId());
        assertThat(mBacklinksDataTextView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBacklinksDataTextView.getText().toString()).isEqualTo(BACKLINKS_TASK_APP_NAME);
        assertThat(mBacklinksDataTextView.getCompoundDrawablesRelative()[0])
                .isEqualTo(FAKE_DRAWABLE);

        // Verify dropdown icon is not shown and there are no click listeners on text view.
        assertThat(mBacklinksDataTextView.getCompoundDrawablesRelative()[2]).isNull();
        assertThat(mBacklinksDataTextView.hasOnClickListeners()).isFalse();

        assertThat(mBacklinksIncludeDataCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBacklinksIncludeDataCheckBox.getText().toString())
                .isEqualTo(mActivity.getString(R.string.backlinks_include_link));
        assertThat(mBacklinksIncludeDataCheckBox.isChecked()).isTrue();

        assertThat(mBacklinksIncludeDataCheckBox.getAlpha()).isEqualTo(1.0f);
        assertThat(mBacklinksIncludeDataCheckBox.isEnabled()).isTrue();
        assertThat(mBacklinksCrossProfileErrorTextView.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    @EnableFlags(Flags.FLAG_APP_CLIPS_BACKLINKS)
    public void appClipsLaunched_backlinks_doNotIncludeLink() throws RemoteException {
        setUpMocksForBacklinks();

        launchActivity();
        waitForIdleSync();
        CheckBox backlinksIncludeData = mActivity.findViewById(R.id.backlinks_include_data);
        runOnMainThread(() -> backlinksIncludeData.performClick());
        waitForIdleSync();

        assertThat(backlinksIncludeData.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(backlinksIncludeData.isChecked()).isFalse();

        assertThat(mBacklinksDataTextView.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    @EnableFlags(Flags.FLAG_APP_CLIPS_BACKLINKS)
    public void appClipsLaunched_backlinks_multipleBacklinksAvailable_defaultShown()
            throws RemoteException {
        // Set up mocking for multiple backlinks.
        ResolveInfo resolveInfo1 = createBacklinksTaskResolveInfo();

        int taskId2 = BACKLINKS_TASK_ID + 2;
        String package2 = BACKLINKS_TASK_PACKAGE_NAME + 2;
        String appName2 = BACKLINKS_TASK_APP_NAME + 2;

        ResolveInfo resolveInfo2 = createBacklinksTaskResolveInfo();
        ActivityInfo activityInfo2 = resolveInfo2.activityInfo;
        activityInfo2.name = appName2;
        activityInfo2.packageName = package2;
        activityInfo2.applicationInfo.packageName = package2;
        RunningTaskInfo runningTaskInfo2 = createTaskInfoForBacklinksTask();
        runningTaskInfo2.taskId = taskId2;
        runningTaskInfo2.topActivity = new ComponentName(package2, "backlinksClass");
        runningTaskInfo2.topActivityInfo = resolveInfo2.activityInfo;
        runningTaskInfo2.baseIntent = new Intent().setComponent(runningTaskInfo2.topActivity);

        when(mAtmService.getTasks(eq(Integer.MAX_VALUE), eq(false), eq(false),
                mDisplayIdCaptor.capture()))
                .thenReturn(List.of(TASK_THAT_SUPPORTS_BACKLINKS, runningTaskInfo2));
        when(mPackageManager.resolveActivity(any(Intent.class), anyInt())).thenReturn(resolveInfo1,
                resolveInfo1, resolveInfo1, resolveInfo2, resolveInfo2, resolveInfo2);
        when(mPackageManager.loadItemIcon(any(), any())).thenReturn(FAKE_DRAWABLE);

        // Using same AssistContent data for both tasks.
        mockForAssistContent(ASSIST_CONTENT_FOR_BACKLINKS_TASK, BACKLINKS_TASK_ID);
        mockForAssistContent(ASSIST_CONTENT_FOR_BACKLINKS_TASK, taskId2);

        // Mocking complete, trigger backlinks.
        launchActivity();
        waitForIdleSync();

        // Verify default backlink shown to user and text view has on click listener.
        assertThat(mBacklinksDataTextView.getText().toString()).isEqualTo(BACKLINKS_TASK_APP_NAME);
        assertThat(mBacklinksDataTextView.hasOnClickListeners()).isTrue();

        // Verify dropdown icon is not null.
        assertThat(mBacklinksDataTextView.getCompoundDrawablesRelative()[2]).isNotNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_APP_CLIPS_BACKLINKS)
    public void appClipsLaunched_backlinks_multipleBacklinksAvailable_duplicateName()
            throws RemoteException {
        // Set up mocking for multiple backlinks.
        ResolveInfo resolveInfo1 = createBacklinksTaskResolveInfo();

        ResolveInfo resolveInfo2 = createBacklinksTaskResolveInfo();
        RunningTaskInfo runningTaskInfo2 = createTaskInfoForBacklinksTask();
        int taskId2 = BACKLINKS_TASK_ID + 2;
        runningTaskInfo2.taskId = taskId2;

        when(mAtmService.getTasks(eq(Integer.MAX_VALUE), eq(false), eq(false),
                mDisplayIdCaptor.capture()))
                .thenReturn(List.of(TASK_THAT_SUPPORTS_BACKLINKS, runningTaskInfo2));
        when(mPackageManager.resolveActivity(any(Intent.class), anyInt())).thenReturn(resolveInfo1,
                resolveInfo1, resolveInfo1, resolveInfo2, resolveInfo2, resolveInfo2);
        when(mPackageManager.loadItemIcon(any(), any())).thenReturn(FAKE_DRAWABLE);

        // Using same AssistContent data for both tasks.
        mockForAssistContent(ASSIST_CONTENT_FOR_BACKLINKS_TASK, BACKLINKS_TASK_ID);
        mockForAssistContent(ASSIST_CONTENT_FOR_BACKLINKS_TASK, taskId2);

        // Mocking complete, trigger backlinks.
        launchActivity();
        waitForIdleSync();

        // Verify default backlink shown to user has the numerical suffix.
        assertThat(mBacklinksDataTextView.getText().toString()).isEqualTo(
                getContext().getString(R.string.backlinks_duplicate_label_format,
                        BACKLINKS_TASK_APP_NAME, 1));
    }

    @Test
    @EnableFlags(Flags.FLAG_APP_CLIPS_BACKLINKS)
    public void appClipsLaunched_backlinks_singleBacklink_crossProfileError()
            throws RemoteException {
        // Set up mocking for cross profile backlink.
        setUpMocksForBacklinks();
        ActivityManager.RunningTaskInfo crossProfileTaskInfo = createTaskInfoForBacklinksTask();
        crossProfileTaskInfo.userId = UserHandle.myUserId() + 1;
        reset(mAtmService);
        when(mAtmService.getTasks(eq(Integer.MAX_VALUE), eq(false), eq(false),
                mDisplayIdCaptor.capture())).thenReturn(List.of(crossProfileTaskInfo));

        // Trigger backlinks.
        launchActivity();
        waitForIdleSync();

        // Verify views for cross profile backlinks error.
        assertThat(mBacklinksIncludeDataCheckBox.getAlpha()).isLessThan(1.0f);
        assertThat(mBacklinksIncludeDataCheckBox.isEnabled()).isFalse();
        assertThat(mBacklinksIncludeDataCheckBox.isChecked()).isFalse();

        assertThat(mBacklinksCrossProfileErrorTextView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    private void setUpMocksForBacklinks() throws RemoteException {
        when(mAtmService.getTasks(eq(Integer.MAX_VALUE), eq(false), eq(false),
                mDisplayIdCaptor.capture()))
                .thenReturn(List.of(TASK_THAT_SUPPORTS_BACKLINKS));
        mockForAssistContent(ASSIST_CONTENT_FOR_BACKLINKS_TASK, BACKLINKS_TASK_ID);
        when(mPackageManager
                .resolveActivity(any(Intent.class), anyInt()))
                .thenReturn(createBacklinksTaskResolveInfo());
        when(mPackageManager.loadItemIcon(any(), any())).thenReturn(FAKE_DRAWABLE);
    }

    private void mockForAssistContent(AssistContent expected, int taskId) {
        doAnswer(invocation -> {
            AssistContentRequester.Callback callback = invocation.getArgument(1);
            callback.onAssistContentAvailable(expected);
            return null;
        }).when(mAssistContentRequester).requestAssistContent(eq(taskId), any());
    }

    private void launchActivity() {
        launchActivity(createResultReceiver(FAKE_CONSUMER));
    }

    private void launchActivity(ResultReceiver resultReceiver) {
        Intent intent = new Intent()
                .putExtra(AppClipsTrampolineActivity.EXTRA_RESULT_RECEIVER, resultReceiver)
                .putExtra(AppClipsTrampolineActivity.EXTRA_CALLING_PACKAGE_NAME,
                        TEST_CALLING_PACKAGE);

        mActivity = mActivityRule.launchActivity(intent);
        waitForIdleSync();
        mBacklinksDataTextView = mActivity.findViewById(R.id.backlinks_data);
        mBacklinksIncludeDataCheckBox = mActivity.findViewById(R.id.backlinks_include_data);
        mBacklinksCrossProfileErrorTextView = mActivity.findViewById(
                R.id.backlinks_cross_profile_error);
    }

    private ResultReceiver createResultReceiver(
            BiConsumer<Integer, Bundle> resultReceiverConsumer) {
        ResultReceiver testReceiver = new ResultReceiver(getContext().getMainThreadHandler()) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                resultReceiverConsumer.accept(resultCode, resultData);
            }
        };

        Parcel parcel = Parcel.obtain();
        testReceiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        testReceiver = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return testReceiver;
    }

    private void runOnMainThread(Runnable runnable) {
        getContext().getMainExecutor().execute(runnable);
    }

    private static ResolveInfo createBacklinksTaskResolveInfo() {
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.applicationInfo = new ApplicationInfo();
        activityInfo.name = BACKLINKS_TASK_APP_NAME;
        activityInfo.packageName = BACKLINKS_TASK_PACKAGE_NAME;
        activityInfo.applicationInfo.packageName = BACKLINKS_TASK_PACKAGE_NAME;
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = activityInfo;
        return resolveInfo;
    }

    private static RunningTaskInfo createTaskInfoForBacklinksTask() {
        RunningTaskInfo taskInfo = new RunningTaskInfo();
        taskInfo.taskId = BACKLINKS_TASK_ID;
        taskInfo.isVisible = true;
        taskInfo.isRunning = true;
        taskInfo.numActivities = 1;
        taskInfo.topActivity = new ComponentName(BACKLINKS_TASK_PACKAGE_NAME, "backlinksClass");
        taskInfo.topActivityInfo = createBacklinksTaskResolveInfo().activityInfo;
        taskInfo.baseIntent = new Intent().setComponent(taskInfo.topActivity);
        taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_STANDARD);
        taskInfo.userId = UserHandle.myUserId();
        return taskInfo;
    }

    private static AssistContent createAssistContentForBacklinksTask() {
        AssistContent content = new AssistContent();
        content.setWebUri(Uri.parse("https://developers.android.com"));
        return content;
    }

    public static class AppClipsActivityTestable extends AppClipsActivity {

        public AppClipsActivityTestable(AppClipsViewModel.Factory viewModelFactory,
                PackageManager packageManager,
                UserTracker userTracker,
                UiEventLogger uiEventLogger) {
            super(viewModelFactory, packageManager, userTracker, uiEventLogger);
        }
    }
}
