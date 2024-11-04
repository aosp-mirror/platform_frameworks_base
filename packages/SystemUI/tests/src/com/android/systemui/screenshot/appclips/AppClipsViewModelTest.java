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
import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.IActivityTaskManager;
import android.app.assist.AssistContent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
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
import com.android.systemui.screenshot.appclips.InternalBacklinksData.BacklinksData;
import com.android.systemui.screenshot.appclips.InternalBacklinksData.CrossProfileError;

import com.google.common.util.concurrent.Futures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
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
    private static final ResolveInfo BACKLINKS_TASK_RESOLVE_INFO =
            createBacklinksTaskResolveInfo();
    private static final RunningTaskInfo BACKLINKS_TASK_RUNNING_TASK_INFO =
            createTaskInfoForBacklinksTask();

    @Mock
    private AppClipsCrossProcessHelper mAppClipsCrossProcessHelper;
    @Mock
    private ImageExporter mImageExporter;
    @Mock
    private IActivityTaskManager mAtmService;
    @Mock
    private AssistContentRequester mAssistContentRequester;
    @Mock
    Context mMockedContext;
    @Mock
    private PackageManager mPackageManager;
    private AppClipsViewModel mViewModel;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        mViewModel = new AppClipsViewModel.Factory(mAppClipsCrossProcessHelper, mImageExporter,
                mAtmService, mAssistContentRequester, mMockedContext,
                getContext().getMainExecutor(), directExecutor()).create(AppClipsViewModel.class);

        when(mMockedContext.getPackageManager()).thenReturn(mPackageManager);
        when(mMockedContext.createContextAsUser(any(), anyInt())).thenReturn(mMockedContext);
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
    public void triggerBacklinks_shouldUpdateBacklinks_withUri() throws RemoteException {
        Uri expectedUri = Uri.parse("https://developers.android.com");
        AssistContent contentWithUri = new AssistContent();
        contentWithUri.setWebUri(expectedUri);
        mockForAssistContent(contentWithUri, BACKLINKS_TASK_ID);
        mockPackageManagerToResolveUri(expectedUri, BACKLINKS_TASK_RESOLVE_INFO);
        mockBacklinksTaskForMainLauncherIntent();
        mockAtmToReturnRunningTaskInfo(BACKLINKS_TASK_RUNNING_TASK_INFO);

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        BacklinksData result = (BacklinksData) mViewModel.mSelectedBacklinksLiveData.getValue();
        assertThat(result.getAppIcon()).isEqualTo(FAKE_DRAWABLE);
        ClipData clipData = result.getClipData();
        ClipDescription resultDescription = clipData.getDescription();
        assertThat(resultDescription.getLabel().toString()).isEqualTo(BACKLINKS_TASK_APP_NAME);
        assertThat(resultDescription.getMimeType(0)).isEqualTo(MIMETYPE_TEXT_URILIST);
        assertThat(clipData.getItemCount()).isEqualTo(1);
        assertThat(clipData.getItemAt(0).getUri()).isEqualTo(expectedUri);

        assertThat(result).isEqualTo(mViewModel.getBacklinksLiveData().getValue().get(0));
    }

    @Test
    public void triggerBacklinks_shouldUpdateBacklinks_withUriForDifferentApp()
            throws RemoteException {
        // Mock for the screenshotted app so that it can be used for fallback backlink.
        mockAtmToReturnRunningTaskInfo(BACKLINKS_TASK_RUNNING_TASK_INFO);
        mockBacklinksTaskForMainLauncherIntent();

        Uri expectedUri = Uri.parse("https://android.com");
        AssistContent contentWithUri = new AssistContent();
        contentWithUri.setWebUri(expectedUri);
        mockForAssistContent(contentWithUri, BACKLINKS_TASK_ID);

        String package2 = BACKLINKS_TASK_PACKAGE_NAME + 2;
        String appName2 = BACKLINKS_TASK_APP_NAME + 2;
        ResolveInfo resolveInfo2 = createBacklinksTaskResolveInfo();
        ActivityInfo activityInfo2 = resolveInfo2.activityInfo;
        activityInfo2.name = appName2;
        activityInfo2.packageName = package2;
        activityInfo2.applicationInfo.packageName = package2;

        // Mock the different app resolve info so that backlinks resolves to this different app.
        mockPackageManagerToResolveUri(expectedUri, resolveInfo2);
        mockPmToResolveForMainLauncherIntent(resolveInfo2);

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        BacklinksData result = (BacklinksData) mViewModel.mSelectedBacklinksLiveData.getValue();
        ClipData clipData = result.getClipData();
        ClipDescription resultDescription = clipData.getDescription();
        assertThat(resultDescription.getLabel().toString()).isEqualTo(appName2);
        assertThat(resultDescription.getMimeType(0)).isEqualTo(MIMETYPE_TEXT_URILIST);
        assertThat(clipData.getItemCount()).isEqualTo(1);
        assertThat(clipData.getItemAt(0).getUri()).isEqualTo(expectedUri);

        assertThat(mViewModel.getBacklinksLiveData().getValue().size()).isEqualTo(1);
    }

    @Test
    public void triggerBacklinks_withNonResolvableUri_usesMainLauncherIntent()
            throws RemoteException {
        Uri expectedUri = Uri.parse("https://developers.android.com");
        AssistContent contentWithUri = new AssistContent();
        contentWithUri.setWebUri(expectedUri);
        mockForAssistContent(contentWithUri, BACKLINKS_TASK_ID);
        mockBacklinksTaskForMainLauncherIntent();
        mockAtmToReturnRunningTaskInfo(BACKLINKS_TASK_RUNNING_TASK_INFO);

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        verifyMainLauncherBacklinksIntent();
    }

    @Test
    public void triggerBacklinks_shouldUpdateBacklinks_withAppProvidedIntent()
            throws RemoteException {
        Intent expectedIntent = new Intent().setPackage(BACKLINKS_TASK_PACKAGE_NAME);
        AssistContent contentWithAppProvidedIntent = new AssistContent();
        contentWithAppProvidedIntent.setIntent(expectedIntent);
        mockForAssistContent(contentWithAppProvidedIntent, BACKLINKS_TASK_ID);
        mockQueryIntentActivities(expectedIntent, BACKLINKS_TASK_RESOLVE_INFO);
        mockBacklinksTaskForMainLauncherIntent();
        mockAtmToReturnRunningTaskInfo(BACKLINKS_TASK_RUNNING_TASK_INFO);

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        BacklinksData result = (BacklinksData) mViewModel.mSelectedBacklinksLiveData.getValue();
        assertThat(result.getAppIcon()).isEqualTo(FAKE_DRAWABLE);
        ClipData clipData = result.getClipData();
        ClipDescription resultDescription = clipData.getDescription();
        assertThat(resultDescription.getLabel().toString()).isEqualTo(BACKLINKS_TASK_APP_NAME);
        assertThat(resultDescription.getMimeType(0)).isEqualTo(MIMETYPE_TEXT_INTENT);
        assertThat(clipData.getItemCount()).isEqualTo(1);
        assertThat(clipData.getItemAt(0).getIntent()).isEqualTo(expectedIntent);
    }

    @Test
    public void triggerBacklinks_withNonResolvableAppProvidedIntent_usesMainLauncherIntent()
            throws RemoteException {
        Intent expectedIntent = new Intent().setPackage(BACKLINKS_TASK_PACKAGE_NAME);
        AssistContent contentWithAppProvidedIntent = new AssistContent();
        contentWithAppProvidedIntent.setIntent(expectedIntent);
        mockForAssistContent(contentWithAppProvidedIntent, BACKLINKS_TASK_ID);
        mockBacklinksTaskForMainLauncherIntent();
        mockAtmToReturnRunningTaskInfo(BACKLINKS_TASK_RUNNING_TASK_INFO);

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        verifyMainLauncherBacklinksIntent();
    }

    @Test
    public void triggerBacklinks_shouldUpdateBacklinks_withMainLauncherIntent()
            throws RemoteException {
        mockForAssistContent(EMPTY_ASSIST_CONTENT, BACKLINKS_TASK_ID);
        mockBacklinksTaskForMainLauncherIntent();
        mockAtmToReturnRunningTaskInfo(BACKLINKS_TASK_RUNNING_TASK_INFO);

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        verifyMainLauncherBacklinksIntent();
    }

    @Test
    public void triggerBacklinks_withNonResolvableMainLauncherIntent_noBacklinksAvailable()
            throws RemoteException {
        mockForAssistContent(EMPTY_ASSIST_CONTENT, BACKLINKS_TASK_ID);

        // Mock ATM service so we return task info but don't mock PM to resolve the task intent.
        when(mAtmService.getTasks(Integer.MAX_VALUE, /* filterOnlyVisibleRecents= */
                false, /* keepIntentExtras= */ false, DEFAULT_DISPLAY)).thenReturn(
                List.of(BACKLINKS_TASK_RUNNING_TASK_INFO));

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        assertThat(mViewModel.mSelectedBacklinksLiveData.getValue()).isNull();
        assertThat(mViewModel.getBacklinksLiveData().getValue()).isNull();
    }

    @Test
    public void triggerBacklinks_nonStandardActivityIgnored_noBacklinkAvailable()
            throws RemoteException {
        RunningTaskInfo taskInfo = createTaskInfoForBacklinksTask();
        taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_HOME);
        mockAtmToReturnRunningTaskInfo(taskInfo);

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        assertThat(mViewModel.mSelectedBacklinksLiveData.getValue()).isNull();
        assertThat(mViewModel.getBacklinksLiveData().getValue()).isNull();
    }

    @Test
    public void triggerBacklinks_taskIdsToIgnoreConsidered_noBacklinkAvailable() {
        mockForAssistContent(EMPTY_ASSIST_CONTENT, BACKLINKS_TASK_ID);

        mViewModel.triggerBacklinks(Set.of(BACKLINKS_TASK_ID), DEFAULT_DISPLAY);
        waitForIdleSync();

        assertThat(mViewModel.mSelectedBacklinksLiveData.getValue()).isNull();
        assertThat(mViewModel.getBacklinksLiveData().getValue()).isNull();
    }

    @Test
    public void triggerBacklinks_multipleAppsOnScreen_multipleBacklinksAvailable()
            throws RemoteException {
        // Set up mocking for multiple backlinks.
        ResolveInfo resolveInfo1 = createBacklinksTaskResolveInfo();
        RunningTaskInfo runningTaskInfo1 = createTaskInfoForBacklinksTask();
        runningTaskInfo1.topActivityInfo = resolveInfo1.activityInfo;

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

        mockAtmToReturnRunningTaskInfo(runningTaskInfo1, runningTaskInfo2);
        mockPmToResolveForMainLauncherIntent(resolveInfo1);
        mockPmToResolveForMainLauncherIntent(resolveInfo2);

        // Using app provided web uri for the first backlink.
        Uri expectedUri = Uri.parse("https://developers.android.com");
        AssistContent contentWithUri = new AssistContent();
        contentWithUri.setWebUri(expectedUri);
        mockForAssistContent(contentWithUri, BACKLINKS_TASK_ID);
        mockPackageManagerToResolveUri(expectedUri, resolveInfo1);

        // Using app provided intent for the second backlink.
        Intent expectedIntent = new Intent().setPackage(package2);
        AssistContent contentWithAppProvidedIntent = new AssistContent();
        contentWithAppProvidedIntent.setIntent(expectedIntent);
        mockForAssistContent(contentWithAppProvidedIntent, taskId2);
        mockQueryIntentActivities(expectedIntent, resolveInfo2);

        // Set up complete, trigger the backlinks action.
        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        // Verify two backlinks are received and the first backlink is set as default selected.
        assertThat(
                ((BacklinksData) mViewModel.mSelectedBacklinksLiveData.getValue())
                        .getClipData().getItemAt(0).getUri())
                .isEqualTo(expectedUri);
        List<InternalBacklinksData> actualBacklinks = mViewModel.getBacklinksLiveData().getValue();
        assertThat(actualBacklinks).hasSize(2);
        assertThat(((BacklinksData) actualBacklinks.get(0)).getClipData().getItemAt(0).getUri())
                .isEqualTo(expectedUri);
        assertThat(((BacklinksData) actualBacklinks.get(1)).getClipData().getItemAt(0).getIntent())
                .isEqualTo(expectedIntent);
    }

    @Test
    public void triggerBacklinks_singleCrossProfileApp_shouldIndicateError()
            throws RemoteException {
        RunningTaskInfo taskInfo = createTaskInfoForBacklinksTask();
        taskInfo.userId = UserHandle.myUserId() + 1;
        when(mAtmService.getTasks(Integer.MAX_VALUE, /* filterOnlyVisibleRecents= */
                false, /* keepIntentExtra */ false, DEFAULT_DISPLAY)).thenReturn(List.of(taskInfo));
        when(mPackageManager.loadItemIcon(taskInfo.topActivityInfo,
                taskInfo.topActivityInfo.applicationInfo)).thenReturn(FAKE_DRAWABLE);

        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        assertThat(mViewModel.mSelectedBacklinksLiveData.getValue())
                .isInstanceOf(CrossProfileError.class);
    }

    @Test
    public void triggerBacklinks_multipleBacklinks_includesCrossProfileError()
            throws RemoteException {
        // Set up mocking for multiple backlinks.
        mockForAssistContent(EMPTY_ASSIST_CONTENT, BACKLINKS_TASK_ID);
        RunningTaskInfo runningTaskInfo2 = createTaskInfoForBacklinksTask();
        runningTaskInfo2.userId = UserHandle.myUserId() + 1;

        mockAtmToReturnRunningTaskInfo(BACKLINKS_TASK_RUNNING_TASK_INFO, runningTaskInfo2);
        when(mPackageManager.loadItemIcon(runningTaskInfo2.topActivityInfo,
                runningTaskInfo2.topActivityInfo.applicationInfo)).thenReturn(FAKE_DRAWABLE);
        mockBacklinksTaskForMainLauncherIntent();

        // Set up complete, trigger the backlinks action.
        mViewModel.triggerBacklinks(Collections.emptySet(), DEFAULT_DISPLAY);
        waitForIdleSync();

        // Verify two backlinks are received and only second has error.
        List<InternalBacklinksData> actualBacklinks = mViewModel.getBacklinksLiveData().getValue();
        assertThat(actualBacklinks).hasSize(2);
        assertThat(actualBacklinks.get(0)).isInstanceOf(BacklinksData.class);
        assertThat(actualBacklinks.get(1)).isInstanceOf(CrossProfileError.class);
    }

    private void verifyMainLauncherBacklinksIntent() {
        BacklinksData result = (BacklinksData) mViewModel.mSelectedBacklinksLiveData.getValue();
        assertThat(result.getAppIcon()).isEqualTo(FAKE_DRAWABLE);

        ClipData clipData = result.getClipData();
        assertThat(clipData.getItemCount()).isEqualTo(1);

        ClipDescription resultDescription = clipData.getDescription();
        assertThat(resultDescription.getLabel().toString()).isEqualTo(BACKLINKS_TASK_APP_NAME);
        assertThat(resultDescription.getMimeType(0)).isEqualTo(MIMETYPE_TEXT_INTENT);

        Intent actualBacklinksIntent = clipData.getItemAt(0).getIntent();
        assertThat(actualBacklinksIntent.getPackage()).isEqualTo(BACKLINKS_TASK_PACKAGE_NAME);
        assertThat(actualBacklinksIntent.getAction()).isEqualTo(ACTION_MAIN);
        assertThat(actualBacklinksIntent.getCategories()).containsExactly(CATEGORY_LAUNCHER);
        assertThat(actualBacklinksIntent.getComponent()).isEqualTo(
                new ComponentName(BACKLINKS_TASK_PACKAGE_NAME, BACKLINKS_TASK_APP_NAME));
    }

    private void mockForAssistContent(AssistContent expected, int taskId) {
        doAnswer(invocation -> {
            AssistContentRequester.Callback callback = invocation.getArgument(1);
            callback.onAssistContentAvailable(expected);
            return null;
        }).when(mAssistContentRequester).requestAssistContent(eq(taskId), any());
    }

    private void mockPackageManagerToResolveUri(Uri uriToResolve, ResolveInfo resolveInfoToReturn) {
        Intent uriIntent = new Intent(ACTION_VIEW).setData(uriToResolve);
        mockQueryIntentActivities(uriIntent, resolveInfoToReturn);
        mockPmToLoadAppIcon(resolveInfoToReturn);
    }

    private void mockQueryIntentActivities(Intent expectedIntent, ResolveInfo resolveInfoToReturn) {
        when(mPackageManager.queryIntentActivities(intentEquals(expectedIntent),
                eq(MATCH_DEFAULT_ONLY)))
                .thenReturn(List.of(resolveInfoToReturn));
    }

    private void mockBacklinksTaskForMainLauncherIntent() {
        mockPmToResolveForMainLauncherIntent(BACKLINKS_TASK_RESOLVE_INFO);
    }

    private void mockPmToResolveForMainLauncherIntent(ResolveInfo resolveInfo) {
        Intent intent = new Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(
                resolveInfo.activityInfo.packageName);
        when(mPackageManager.resolveActivity(intentEquals(intent), eq(/* flags= */ 0))).thenReturn(
                resolveInfo);
        mockPmToLoadAppIcon(resolveInfo);
    }

    private void mockPmToLoadAppIcon(ResolveInfo resolveInfo) {
        when(mPackageManager.loadItemIcon(resolveInfo.activityInfo,
                resolveInfo.activityInfo.applicationInfo)).thenReturn(FAKE_DRAWABLE);
    }

    private void mockAtmToReturnRunningTaskInfo(RunningTaskInfo... taskInfos)
            throws RemoteException {
        when(mAtmService.getTasks(Integer.MAX_VALUE, /* filterOnlyVisibleRecents= */
                false, /* keepIntentExtras= */ false, DEFAULT_DISPLAY)).thenReturn(
                List.of(taskInfos));
    }

    private static Intent intentEquals(Intent intent) {
        return argThat(new IntentMatcher(intent));
    }

    private static class IntentMatcher implements ArgumentMatcher<Intent> {
        private final Intent mExpectedIntent;

        IntentMatcher(Intent expectedIntent) {
            mExpectedIntent = expectedIntent;
        }

        @Override
        public boolean matches(Intent actualIntent) {
            return actualIntent != null && mExpectedIntent.filterEquals(actualIntent);
        }
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
        taskInfo.topActivityInfo = BACKLINKS_TASK_RESOLVE_INFO.activityInfo;
        taskInfo.baseIntent = new Intent().setComponent(taskInfo.topActivity);
        taskInfo.configuration.windowConfiguration.setActivityType(ACTIVITY_TYPE_STANDARD);
        taskInfo.userId = UserHandle.myUserId();
        return taskInfo;
    }
}
