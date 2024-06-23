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

import static android.content.Intent.ACTION_MAIN;
import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED;
import static android.content.Intent.CATEGORY_LAUNCHER;

import static com.google.common.util.concurrent.Futures.withTimeout;

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.IActivityTaskManager;
import android.app.WindowConfiguration;
import android.app.assist.AssistContent;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.screenshot.AssistContentRequester;
import com.android.systemui.screenshot.ImageExporter;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

/** A {@link ViewModel} to help with the App Clips screenshot flow. */
final class AppClipsViewModel extends ViewModel {

    private static final String TAG = AppClipsViewModel.class.getSimpleName();

    private final AppClipsCrossProcessHelper mAppClipsCrossProcessHelper;
    private final ImageExporter mImageExporter;
    private final IActivityTaskManager mAtmService;
    private final AssistContentRequester mAssistContentRequester;
    private final PackageManager mPackageManager;

    @Main
    private final Executor mMainExecutor;
    @Background
    private final Executor mBgExecutor;

    private final MutableLiveData<Bitmap> mScreenshotLiveData;
    private final MutableLiveData<Uri> mResultLiveData;
    private final MutableLiveData<Integer> mErrorLiveData;
    private final MutableLiveData<ClipData> mBacklinksLiveData;

    private AppClipsViewModel(AppClipsCrossProcessHelper appClipsCrossProcessHelper,
            ImageExporter imageExporter, IActivityTaskManager atmService,
            AssistContentRequester assistContentRequester, PackageManager packageManager,
            @Main Executor mainExecutor, @Background Executor bgExecutor) {
        mAppClipsCrossProcessHelper = appClipsCrossProcessHelper;
        mImageExporter = imageExporter;
        mAtmService = atmService;
        mAssistContentRequester = assistContentRequester;
        mPackageManager = packageManager;
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;

        mScreenshotLiveData = new MutableLiveData<>();
        mResultLiveData = new MutableLiveData<>();
        mErrorLiveData = new MutableLiveData<>();
        mBacklinksLiveData = new MutableLiveData<>();
    }

    /**
     * Grabs a screenshot and updates the {@link Bitmap} set in screenshot {@link #getScreenshot()}.
     *
     * @param displayId id of the {@link Display} to capture screenshot.
     */
    void performScreenshot(int displayId) {
        mBgExecutor.execute(() -> {
            Bitmap screenshot = mAppClipsCrossProcessHelper.takeScreenshot(displayId);
            mMainExecutor.execute(() -> {
                if (screenshot == null) {
                    mErrorLiveData.setValue(CAPTURE_CONTENT_FOR_NOTE_FAILED);
                } else {
                    mScreenshotLiveData.setValue(screenshot);
                }
            });
        });
    }

    /**
     * Triggers the Backlinks flow which:
     * <ul>
     *     <li>Evaluates the task to query.
     *     <li>Requests {@link AssistContent} from that task.
     *     <li>Transforms the {@link AssistContent} into {@link ClipData} for Backlinks.
     *     <li>The {@link ClipData} is reported to activity via {@link #getBacklinksLiveData()}.
     * </ul>
     *
     * @param taskIdsToIgnore id of the tasks to ignore when querying for {@link AssistContent}
     * @param displayId       id of the display to query tasks for Backlinks data
     */
    void triggerBacklinks(Set<Integer> taskIdsToIgnore, int displayId) {
        mBgExecutor.execute(() -> {
            ListenableFuture<ClipData> backlinksData = getBacklinksData(taskIdsToIgnore, displayId);
            Futures.addCallback(backlinksData, new FutureCallback<>() {
                @Override
                public void onSuccess(@Nullable ClipData result) {
                    if (result != null) {
                        mBacklinksLiveData.setValue(result);
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    Log.e(TAG, "Error querying for Backlinks data", t);
                }
            }, mMainExecutor);

        });
    }

    /** Returns a {@link LiveData} that holds the captured screenshot. */
    LiveData<Bitmap> getScreenshot() {
        return mScreenshotLiveData;
    }

    /** Returns a {@link LiveData} that holds the {@link Uri} where screenshot is saved. */
    LiveData<Uri> getResultLiveData() {
        return mResultLiveData;
    }

    /**
     * Returns a {@link LiveData} that holds the error codes for
     * {@link Intent#EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE}.
     */
    LiveData<Integer> getErrorLiveData() {
        return mErrorLiveData;
    }

    /** Returns a {@link LiveData} that holds the Backlinks data in {@link ClipData}. */
    LiveData<ClipData> getBacklinksLiveData() {
        return mBacklinksLiveData;
    }

    /**
     * Saves the provided {@link Drawable} to storage then informs the result {@link Uri} to
     * {@link LiveData}.
     */
    void saveScreenshotThenFinish(Drawable screenshotDrawable, Rect bounds, UserHandle user) {
        mBgExecutor.execute(() -> {
            // Render the screenshot bitmap in background.
            Bitmap screenshotBitmap = renderBitmap(screenshotDrawable, bounds);

            // Export and save the screenshot in background.
            ListenableFuture<ImageExporter.Result> exportFuture = mImageExporter.export(mBgExecutor,
                    UUID.randomUUID(), screenshotBitmap, user, Display.DEFAULT_DISPLAY);

            // Get the result and update state on main thread.
            exportFuture.addListener(() -> {
                try {
                    ImageExporter.Result result = exportFuture.get();
                    if (result.uri == null) {
                        mErrorLiveData.setValue(CAPTURE_CONTENT_FOR_NOTE_FAILED);
                        return;
                    }

                    mResultLiveData.setValue(result.uri);
                } catch (CancellationException | InterruptedException | ExecutionException e) {
                    mErrorLiveData.setValue(CAPTURE_CONTENT_FOR_NOTE_FAILED);
                }
            }, mMainExecutor);
        });
    }

    private static Bitmap renderBitmap(Drawable drawable, Rect bounds) {
        final RenderNode output = new RenderNode("Screenshot save");
        output.setPosition(0, 0, bounds.width(), bounds.height());
        RecordingCanvas canvas = output.beginRecording();
        canvas.translate(-bounds.left, -bounds.top);
        canvas.clipRect(bounds);
        drawable.draw(canvas);
        output.endRecording();
        return HardwareRenderer.createHardwareBitmap(output, bounds.width(), bounds.height());
    }

    private ListenableFuture<ClipData> getBacklinksData(Set<Integer> taskIdsToIgnore,
            int displayId) {
        return getAllRootTaskInfosOnDisplay(displayId)
                .stream()
                .filter(taskInfo -> shouldIncludeTask(taskInfo, taskIdsToIgnore))
                .findFirst()
                .map(this::getBacklinksDataForTaskId)
                .orElse(Futures.immediateFuture(null));
    }

    private List<RootTaskInfo> getAllRootTaskInfosOnDisplay(int displayId) {
        try {
            return mAtmService.getAllRootTaskInfosOnDisplay(displayId);
        } catch (RemoteException e) {
            Log.e(TAG, String.format("Error while querying for tasks on display %d", displayId), e);
            return Collections.emptyList();
        }
    }

    private boolean shouldIncludeTask(RootTaskInfo taskInfo, Set<Integer> taskIdsToIgnore) {
        // Only consider tasks that shouldn't be ignored, are visible, running, and have a launcher
        // icon. Furthermore, types such as launcher/home/dock/assistant are ignored.
        return !taskIdsToIgnore.contains(taskInfo.taskId)
                && taskInfo.isVisible
                && taskInfo.isRunning
                && taskInfo.numActivities > 0
                && taskInfo.topActivity != null
                && taskInfo.topActivityInfo != null
                && taskInfo.childTaskIds.length > 0
                && taskInfo.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_STANDARD
                && canAppStartThroughLauncher(taskInfo.topActivity.getPackageName());
    }

    private boolean canAppStartThroughLauncher(String packageName) {
        return getMainLauncherIntentForPackage(packageName).resolveActivity(mPackageManager)
                != null;
    }

    private ListenableFuture<ClipData> getBacklinksDataForTaskId(RootTaskInfo taskInfo) {
        SettableFuture<ClipData> backlinksData = SettableFuture.create();
        int taskId = taskInfo.taskId;
        mAssistContentRequester.requestAssistContent(taskId, assistContent ->
                backlinksData.set(getBacklinksDataFromAssistContent(taskInfo, assistContent)));
        return withTimeout(backlinksData, 5L, TimeUnit.SECONDS, newSingleThreadScheduledExecutor());
    }

    /**
     * A utility method to get {@link ClipData} to use for Backlinks functionality from
     * {@link AssistContent} received from the app whose screenshot is taken.
     *
     * <p>There are multiple ways an app can provide deep-linkable data via {@link AssistContent}
     * but Backlinks restricts to using only one way. The following is the ordered list based on
     * preference:
     * <ul>
     *     <li>{@link AssistContent#getWebUri()} is the most preferred way.
     *     <li>Second preference is given to {@link AssistContent#getIntent()} when the app provides
     *     the intent, see {@link AssistContent#isAppProvidedIntent()}.
     *     <li>The last preference is given to an {@link Intent} that is built using
     *     {@link Intent#ACTION_MAIN} and {@link Intent#CATEGORY_LAUNCHER}.
     * </ul>
     *
     * @param taskInfo {@link RootTaskInfo} of the task which provided the {@link AssistContent}.
     * @param content the {@link AssistContent} to map into Backlinks {@link ClipData}.
     * @return {@link ClipData} that represents the Backlinks data.
     */
    private ClipData getBacklinksDataFromAssistContent(RootTaskInfo taskInfo,
            @Nullable AssistContent content) {
        String appName = getAppNameOfTask(taskInfo);
        String packageName = taskInfo.topActivity.getPackageName();
        ClipData fallback = ClipData.newIntent(appName,
                getMainLauncherIntentForPackage(packageName));
        if (content == null) {
            return fallback;
        }

        // First preference is given to app provided uri.
        if (content.isAppProvidedWebUri()) {
            Uri uri = content.getWebUri();
            Intent backlinksIntent = new Intent(ACTION_VIEW).setData(uri);
            if (doesIntentResolveToSamePackage(backlinksIntent, packageName)) {
                return ClipData.newRawUri(appName, uri);
            }
        }

        // Second preference is given to app provided, hopefully deep-linking, intent.
        if (content.isAppProvidedIntent()) {
            Intent backlinksIntent = content.getIntent();
            if (doesIntentResolveToSamePackage(backlinksIntent, packageName)) {
                return ClipData.newIntent(appName, backlinksIntent);
            }
        }

        return fallback;
    }

    private boolean doesIntentResolveToSamePackage(Intent intentToResolve,
            String requiredPackageName) {
        ComponentName resolvedComponent = intentToResolve.resolveActivity(mPackageManager);
        if (resolvedComponent == null) {
            return false;
        }

        return resolvedComponent.getPackageName().equals(requiredPackageName);
    }

    private String getAppNameOfTask(RootTaskInfo taskInfo) {
        return taskInfo.topActivityInfo.loadLabel(mPackageManager).toString();
    }

    private Intent getMainLauncherIntentForPackage(String packageName) {
        return new Intent(ACTION_MAIN)
                .addCategory(CATEGORY_LAUNCHER)
                .setPackage(packageName);
    }

    /** Helper factory to help with injecting {@link AppClipsViewModel}. */
    static final class Factory implements ViewModelProvider.Factory {

        private final AppClipsCrossProcessHelper mAppClipsCrossProcessHelper;
        private final ImageExporter mImageExporter;
        private final IActivityTaskManager mAtmService;
        private final AssistContentRequester mAssistContentRequester;
        private final PackageManager mPackageManager;
        @Main
        private final Executor mMainExecutor;
        @Background
        private final Executor mBgExecutor;

        @Inject
        Factory(AppClipsCrossProcessHelper appClipsCrossProcessHelper, ImageExporter imageExporter,
                IActivityTaskManager atmService, AssistContentRequester assistContentRequester,
                PackageManager packageManager, @Main Executor mainExecutor,
                @Background Executor bgExecutor) {
            mAppClipsCrossProcessHelper = appClipsCrossProcessHelper;
            mImageExporter = imageExporter;
            mAtmService = atmService;
            mAssistContentRequester = assistContentRequester;
            mPackageManager = packageManager;
            mMainExecutor = mainExecutor;
            mBgExecutor = bgExecutor;
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (modelClass != AppClipsViewModel.class) {
                throw new IllegalArgumentException();
            }

            //noinspection unchecked
            return (T) new AppClipsViewModel(mAppClipsCrossProcessHelper, mImageExporter,
                    mAtmService, mAssistContentRequester, mPackageManager, mMainExecutor,
                    mBgExecutor);
        }
    }
}
