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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.content.ClipDescription.MIMETYPE_TEXT_INTENT;
import static android.content.ClipDescription.MIMETYPE_TEXT_URILIST;
import static android.content.Intent.ACTION_MAIN;
import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED;
import static android.content.Intent.CATEGORY_LAUNCHER;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityTaskManager;
import android.app.assist.AssistContent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.net.Uri;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.screenshot.AssistContentRequester;
import com.android.systemui.screenshot.ImageExporter;

import com.google.common.util.concurrent.Futures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
public final class AppClipsViewModelTest extends SysuiTestCase {

    private static final Bitmap FAKE_BITMAP = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    private static final Drawable FAKE_DRAWABLE = new ShapeDrawable();
    private static final Rect FAKE_RECT = new Rect();
    private static final Uri FAKE_URI = Uri.parse("www.test-uri.com");
    private static final UserHandle USER_HANDLE = Process.myUserHandle();
    private static final int BACKLINKS_TASK_ID = 42;
    private static final String BACKLINKS_TASK_APP_NAME = "Ultimate question app";
    private static final String BACKLINKS_TASK_PACKAGE_NAME = "backlinksTaskPackageName";
    private static final AssistContent EMPTY_ASSIST_CONTENT = new AssistContent();

    @Mock private AppClipsCrossProcessHelper mAppClipsCrossProcessHelper;
    @Mock private ImageExporter mImageExporter;
    @Mock private IActivityTaskManager mAtmService;
    @Mock private AssistContentRequester mAssistContentRequester;
    @Mock
    private PackageManager mPackageManager;
    private ArgumentCaptor<Intent> mPackageManagerIntentCaptor;
    private AppClipsViewModel mViewModel;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mPackageManagerIntentCaptor = ArgumentCaptor.forClass(Intent.class);

        // Set up mocking for backlinks.
        when(mAtmService.getAllRootTaskInfosOnDisplay(DEFAULT_DISPLAY))
                .thenReturn(List.of(createTaskInfoForBacklinksTask()));
        when(mPackageManager.resolveActivity(mPackageManagerIntentCaptor.capture(), anyInt()))
                .thenReturn(createBacklinksTaskResolveInfo());

        mViewModel = new AppClipsViewModel.Factory(mAppClipsCrossProcessHelper, mImageExporter,
                mAtmService, mAssistContentRequester, mPackageManager,
                getContext().getMainExecutor(), directExecutor()).create(AppClipsViewModel.class);
    }

    @Test
    public void performScreenshot_fails_shouldUpdateErrorWithFailed() {
        when(mAppClipsCrossProcessHelper.takeScreenshot(anyInt())).thenReturn(null);

        mViewModel.performScreenshot(DEFAULT_DISPLAY);
        waitForIdleSync();

        verify(mAppClipsCrossProcessHelper).takeScreenshot(DEFAULT_DISPLAY);
        assertThat(mViewModel.getErrorLiveData().getValue())
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
        assertThat(mViewModel.getResultLiveData().getValue()).isNull();
    }

    @Test
    public void performScreenshot_succeeds_shouldUpdateScreenshotWithBitmap() {
        when(mAppClipsCrossProcessHelper.takeScreenshot(DEFAULT_DISPLAY)).thenReturn(FAKE_BITMAP);

        mViewModel.performScreenshot(DEFAULT_DISPLAY);
        waitForIdleSync();

        verify(mAppClipsCrossProcessHelper).takeScreenshot(DEFAULT_DISPLAY);
        assertThat(mViewModel.getErrorLiveData().getValue()).isNull();
        assertThat(mViewModel.getScreenshot().getValue()).isEqualTo(FAKE_BITMAP);
    }

    @Test
    public void saveScreenshot_throwsError_shouldUpdateErrorWithFailed() {
        when(mImageExporter.export(any(Executor.class), any(UUID.class), eq(null), eq(USER_HANDLE),
                eq(DEFAULT_DISPLAY))).thenReturn(
                Futures.immediateFailedFuture(new ExecutionException(new Throwable())));

        mViewModel.saveScreenshotThenFinish(FAKE_DRAWABLE, FAKE_RECT, USER_HANDLE);
        waitForIdleSync();

        assertThat(mViewModel.getErrorLiveData().getValue())
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
        assertThat(mViewModel.getResultLiveData().getValue()).isNull();
    }

    @Test
    public void saveScreenshot_failsSilently_shouldUpdateErrorWithFailed() {
        when(mImageExporter.export(any(Executor.class), any(UUID.class), eq(null), eq(USER_HANDLE),
                eq(DEFAULT_DISPLAY))).thenReturn(
                Futures.immediateFuture(new ImageExporter.Result()));

        mViewModel.saveScreenshotThenFinish(FAKE_DRAWABLE, FAKE_RECT, USER_HANDLE);
        waitForIdleSync();

        assertThat(mViewModel.getErrorLiveData().getValue())
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
        assertThat(mViewModel.getResultLiveData().getValue()).isNull();
    }

    @Test
    public void saveScreenshot_succeeds_shouldUpdateResultWithUri() {
        ImageExporter.Result result = new ImageExporter.Result();
        result.uri = FAKE_URI;
        when(mImageExporter.export(any(Executor.class), any(UUID.class), eq(null), eq(USER_HANDLE),
                eq(DEFAULT_DISPLAY))).thenReturn(Futures.immediateFuture(result));

        mViewModel.saveScreenshotThenFinish(FAKE_DRAWABLE, FAKE_RECT, USER_HANDLE);
        waitForIdleSync();

        assertThat(mViewModel.getErrorLiveData().getValue()).isNull();
        assertThat(mViewModel.getResultLiveData().getValue()).isEqualTo(FAKE_URI);
    }

    @Test
    public void triggerBacklinks_shouldUpdateBacklinks_withUri() {
        Uri expectedUri = Uri.parse("https://developers.android.com");
        AssistContent contentWithUri = new AssistContent();
        contentWithUri.setWebUri(expectedUri);
        doAnswer(invocation -> {
            AssistContentRequester.Callback callback = invocation.getArgument(1);
            callback.onAssistContentAvailable(contentWithUri);
            return null;
        }).when(mAssistContentRequester).requestAssistContent(eq(BACKLINKS_TASK_ID), any());

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        Intent queriedIntent = mPackageManagerIntentCaptor.getValue();
        assertThat(queriedIntent.getData()).isEqualTo(expectedUri);
        assertThat(queriedIntent.getAction()).isEqualTo(ACTION_VIEW);

        ClipData result = mViewModel.getBacklinksLiveData().getValue();
        ClipDescription resultDescription = result.getDescription();
        assertThat(resultDescription.getLabel().toString()).isEqualTo(BACKLINKS_TASK_APP_NAME);
        assertThat(resultDescription.getMimeType(0)).isEqualTo(MIMETYPE_TEXT_URILIST);
        assertThat(result.getItemCount()).isEqualTo(1);
        assertThat(result.getItemAt(0).getUri()).isEqualTo(expectedUri);
    }

    @Test
    public void triggerBacklinks_withNonResolvableUri_usesMainLauncherIntent() {
        Uri expectedUri = Uri.parse("https://developers.android.com");
        AssistContent contentWithUri = new AssistContent();
        contentWithUri.setWebUri(expectedUri);
        resetPackageManagerMockingForUsingFallbackBacklinks();
        doAnswer(invocation -> {
            AssistContentRequester.Callback callback = invocation.getArgument(1);
            callback.onAssistContentAvailable(contentWithUri);
            return null;
        }).when(mAssistContentRequester).requestAssistContent(eq(BACKLINKS_TASK_ID), any());

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        verifyMainLauncherBacklinksIntent();
    }

    @Test
    public void triggerBacklinks_shouldUpdateBacklinks_withAppProvidedIntent() {
        Intent expectedIntent = new Intent().setPackage(BACKLINKS_TASK_PACKAGE_NAME);
        AssistContent contentWithAppProvidedIntent = new AssistContent();
        contentWithAppProvidedIntent.setIntent(expectedIntent);
        doAnswer(invocation -> {
            AssistContentRequester.Callback callback = invocation.getArgument(1);
            callback.onAssistContentAvailable(contentWithAppProvidedIntent);
            return null;
        }).when(mAssistContentRequester).requestAssistContent(eq(BACKLINKS_TASK_ID), any());

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        Intent queriedIntent = mPackageManagerIntentCaptor.getValue();
        assertThat(queriedIntent.getPackage()).isEqualTo(expectedIntent.getPackage());

        ClipData result = mViewModel.getBacklinksLiveData().getValue();
        ClipDescription resultDescription = result.getDescription();
        assertThat(resultDescription.getLabel().toString()).isEqualTo(BACKLINKS_TASK_APP_NAME);
        assertThat(resultDescription.getMimeType(0)).isEqualTo(MIMETYPE_TEXT_INTENT);
        assertThat(result.getItemCount()).isEqualTo(1);
        assertThat(result.getItemAt(0).getIntent()).isEqualTo(expectedIntent);
    }

    @Test
    public void triggerBacklinks_withNonResolvableAppProvidedIntent_usesMainLauncherIntent() {
        Intent expectedIntent = new Intent().setPackage(BACKLINKS_TASK_PACKAGE_NAME);
        AssistContent contentWithAppProvidedIntent = new AssistContent();
        contentWithAppProvidedIntent.setIntent(expectedIntent);
        resetPackageManagerMockingForUsingFallbackBacklinks();
        doAnswer(invocation -> {
            AssistContentRequester.Callback callback = invocation.getArgument(1);
            callback.onAssistContentAvailable(contentWithAppProvidedIntent);
            return null;
        }).when(mAssistContentRequester).requestAssistContent(eq(BACKLINKS_TASK_ID), any());

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        verifyMainLauncherBacklinksIntent();
    }

    @Test
    public void triggerBacklinks_shouldUpdateBacklinks_withMainLauncherIntent() {
        doAnswer(invocation -> {
            AssistContentRequester.Callback callback = invocation.getArgument(1);
            callback.onAssistContentAvailable(EMPTY_ASSIST_CONTENT);
            return null;
        }).when(mAssistContentRequester).requestAssistContent(eq(BACKLINKS_TASK_ID), any());

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        Intent queriedIntent = mPackageManagerIntentCaptor.getValue();
        assertThat(queriedIntent.getPackage()).isEqualTo(BACKLINKS_TASK_PACKAGE_NAME);
        assertThat(queriedIntent.getAction()).isEqualTo(ACTION_MAIN);
        assertThat(queriedIntent.getCategories()).containsExactly(CATEGORY_LAUNCHER);

        verifyMainLauncherBacklinksIntent();
    }

    @Test
    public void triggerBacklinks_withNonResolvableMainLauncherIntent_noBacklinksAvailable() {
        reset(mPackageManager);
        doAnswer(invocation -> {
            AssistContentRequester.Callback callback = invocation.getArgument(1);
            callback.onAssistContentAvailable(EMPTY_ASSIST_CONTENT);
            return null;
        }).when(mAssistContentRequester).requestAssistContent(eq(BACKLINKS_TASK_ID), any());

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        assertThat(mViewModel.getBacklinksLiveData().getValue()).isNull();
    }

    @Test
    public void triggerBacklinks_nonStandardActivityIgnored_noBacklinkAvailable()
            throws RemoteException {
        reset(mAtmService);
        RootTaskInfo taskInfo = createTaskInfoForBacklinksTask();
        taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_HOME);
        when(mAtmService.getAllRootTaskInfosOnDisplay(DEFAULT_DISPLAY))
                .thenReturn(List.of(taskInfo));

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        assertThat(mViewModel.getBacklinksLiveData().getValue()).isNull();
    }

    @Test
    public void triggerBacklinks_taskIdsToIgnoreConsidered_noBacklinkAvailable() {
        mViewModel.triggerBacklinks(Set.of(BACKLINKS_TASK_ID), DEFAULT_DISPLAY);
        waitForIdleSync();

        assertThat(mViewModel.getBacklinksLiveData().getValue()).isNull();
    }

    private void resetPackageManagerMockingForUsingFallbackBacklinks() {
        reset(mPackageManager);
        when(mPackageManager.resolveActivity(any(Intent.class), anyInt()))
                // First the logic queries whether a package has a launcher activity, this should
                // resolve otherwise the logic filters out the task.
                .thenReturn(createBacklinksTaskResolveInfo())
                // Then logic queries with the backlinks intent, this should not resolve for the
                // logic to use the fallback intent.
                .thenReturn(null);
    }

    private void verifyMainLauncherBacklinksIntent() {
        ClipData result = mViewModel.getBacklinksLiveData().getValue();
        assertThat(result.getItemCount()).isEqualTo(1);

        ClipDescription resultDescription = result.getDescription();
        assertThat(resultDescription.getLabel().toString()).isEqualTo(BACKLINKS_TASK_APP_NAME);
        assertThat(resultDescription.getMimeType(0)).isEqualTo(MIMETYPE_TEXT_INTENT);

        Intent actualBacklinksIntent = result.getItemAt(0).getIntent();
        assertThat(actualBacklinksIntent.getPackage()).isEqualTo(BACKLINKS_TASK_PACKAGE_NAME);
        assertThat(actualBacklinksIntent.getAction()).isEqualTo(ACTION_MAIN);
        assertThat(actualBacklinksIntent.getCategories()).containsExactly(CATEGORY_LAUNCHER);
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

    private static RootTaskInfo createTaskInfoForBacklinksTask() {
        RootTaskInfo taskInfo = new RootTaskInfo();
        taskInfo.taskId = BACKLINKS_TASK_ID;
        taskInfo.isVisible = true;
        taskInfo.isRunning = true;
        taskInfo.numActivities = 1;
        taskInfo.topActivity = new ComponentName(BACKLINKS_TASK_PACKAGE_NAME, "backlinksClass");
        taskInfo.topActivityInfo = createBacklinksTaskResolveInfo().activityInfo;
        taskInfo.baseIntent = new Intent().setComponent(taskInfo.topActivity);
        taskInfo.childTaskIds = new int[]{BACKLINKS_TASK_ID + 1};
        taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_STANDARD);
        return taskInfo;
    }
}
