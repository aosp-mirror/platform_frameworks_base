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

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

import android.app.IActivityTaskManager;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.app.assist.AssistContent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.log.DebugLogger;
import com.android.systemui.screenshot.AssistContentRequester;
import com.android.systemui.screenshot.ImageExporter;
import com.android.systemui.screenshot.appclips.InternalBacklinksData.BacklinksData;
import com.android.systemui.screenshot.appclips.InternalBacklinksData.CrossProfileError;

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
    @Application private final Context mContext;

    @Main
    private final Executor mMainExecutor;
    @Background
    private final Executor mBgExecutor;

    private final MutableLiveData<Bitmap> mScreenshotLiveData;
    private final MutableLiveData<Uri> mResultLiveData;
    private final MutableLiveData<Integer> mErrorLiveData;
    private final MutableLiveData<List<InternalBacklinksData>> mBacklinksLiveData;
    final MutableLiveData<InternalBacklinksData> mSelectedBacklinksLiveData;

    private AppClipsViewModel(AppClipsCrossProcessHelper appClipsCrossProcessHelper,
            ImageExporter imageExporter, IActivityTaskManager atmService,
            AssistContentRequester assistContentRequester, @Application Context context,
            @Main Executor mainExecutor, @Background Executor bgExecutor) {
        mAppClipsCrossProcessHelper = appClipsCrossProcessHelper;
        mImageExporter = imageExporter;
        mAtmService = atmService;
        mAssistContentRequester = assistContentRequester;
        mContext = context;
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;

        mScreenshotLiveData = new MutableLiveData<>();
        mResultLiveData = new MutableLiveData<>();
        mErrorLiveData = new MutableLiveData<>();
        mBacklinksLiveData = new MutableLiveData<>();
        mSelectedBacklinksLiveData = new MutableLiveData<>();
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
     *     <li>Evaluates the tasks to query.
     *     <li>Requests {@link AssistContent} from all valid tasks.
     *     <li>Transforms {@link AssistContent} into {@link InternalBacklinksData} for Backlinks.
     *     <li>The {@link InternalBacklinksData}s are reported to activity via
     *     {@link #getBacklinksLiveData()}.
     * </ul>
     *
     * @param taskIdsToIgnore id of the tasks to ignore when querying for {@link AssistContent}
     * @param displayId       id of the display to query tasks for Backlinks data
     */
    void triggerBacklinks(Set<Integer> taskIdsToIgnore, int displayId) {
        DebugLogger.INSTANCE.logcatMessage(this, () -> "Backlinks triggered");
        ListenableFuture<List<InternalBacklinksData>> backlinksData = getAllAvailableBacklinks(
                taskIdsToIgnore, displayId);
        Futures.addCallback(backlinksData, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable List<InternalBacklinksData> result) {
                if (result != null && !result.isEmpty()) {
                    // Set the list of backlinks before setting the selected backlink as this is
                    // required when updating the backlink data text view.
                    mBacklinksLiveData.setValue(result);
                    mSelectedBacklinksLiveData.setValue(result.get(0));
                }
            }

            @Override
            public void onFailure(Throwable t) {
                Log.e(TAG, "Error querying for Backlinks data", t);
            }
        }, mMainExecutor);
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

    /**
     * Returns a {@link LiveData} that holds all the available Backlinks data and the currently
     * selected index for displaying the Backlinks in the UI.
     */
    LiveData<List<InternalBacklinksData>> getBacklinksLiveData() {
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

    private ListenableFuture<List<InternalBacklinksData>> getAllAvailableBacklinks(
            Set<Integer> taskIdsToIgnore, int displayId) {
        ListenableFuture<List<TaskInfo>> allTasksOnDisplayFuture = getAllTasksOnDisplay(displayId);

        ListenableFuture<List<ListenableFuture<InternalBacklinksData>>> backlinksNestedListFuture =
                Futures.transform(allTasksOnDisplayFuture, allTasksOnDisplay ->
                        allTasksOnDisplay
                                .stream()
                                .filter(taskInfo -> shouldIncludeTask(taskInfo, taskIdsToIgnore))
                                .map(taskInfo -> new InternalTaskInfo(taskInfo.topActivityInfo,
                                        taskInfo.taskId, taskInfo.userId,
                                        getPackageManagerForUser(taskInfo.userId)))
                                .map(this::getBacklinksDataForTaskInfo)
                                .toList(),
                        mBgExecutor);

        return Futures.transformAsync(backlinksNestedListFuture, Futures::allAsList, mBgExecutor);
    }

    private PackageManager getPackageManagerForUser(int userId) {
        // If app clips was launched as the same user, then reuse the available PM from mContext.
        if (mContext.getUserId() == userId) {
            return mContext.getPackageManager();
        }

        // PackageManager required for a different user, create its context and return its PM.
        UserHandle userHandle = UserHandle.of(userId);
        return mContext.createContextAsUser(userHandle, /* flags= */ 0).getPackageManager();
    }

    /**
     * Returns all tasks on a given display after querying {@link IActivityTaskManager} from the
     * {@link #mBgExecutor}.
     */
    private ListenableFuture<List<TaskInfo>> getAllTasksOnDisplay(int displayId) {
        SettableFuture<List<TaskInfo>> recentTasksFuture = SettableFuture.create();
        mBgExecutor.execute(() -> {
            try {
                // Directly call into ActivityTaskManagerService instead of going through WMShell
                // because WMShell is only available in the main SysUI process and App Clips runs
                // in its own separate process as it deals with bitmaps.
                List<TaskInfo> allTasksOnDisplay = mAtmService.getTasks(
                                /* maxNum= */ Integer.MAX_VALUE,
                                // PIP tasks are not visible in recents. So _not_ filtering for
                                // tasks that are only visible in recents.
                                /* filterOnlyVisibleRecents= */ false,
                                /* keepIntentExtra= */ false,
                                displayId)
                        .stream()
                        .map(runningTaskInfo -> (TaskInfo) runningTaskInfo)
                        .toList();
                recentTasksFuture.set(allTasksOnDisplay);
            } catch (Exception e) {
                Log.e(TAG, String.format("Error getting all tasks on displayId %d", displayId), e);
                recentTasksFuture.set(Collections.emptyList());
            }
        });

        return withTimeout(recentTasksFuture);
    }

    /**
     * Returns whether the app represented by the provided {@link TaskInfo} should be included for
     * querying for {@link AssistContent}.
     *
     * <p>This does not check whether the task has a launcher icon.
     */
    private boolean shouldIncludeTask(TaskInfo taskInfo, Set<Integer> taskIdsToIgnore) {
        DebugLogger.INSTANCE.logcatMessage(this,
                () -> String.format("shouldIncludeTask taskId %d; topActivity %s", taskInfo.taskId,
                        taskInfo.topActivity));

        // Only consider tasks that shouldn't be ignored, are visible, and running. Furthermore,
        // types such as launcher/home/dock/assistant are ignored.
        return !taskIdsToIgnore.contains(taskInfo.taskId)
                && taskInfo.isVisible
                && taskInfo.isRunning
                && taskInfo.numActivities > 0
                && taskInfo.topActivity != null
                && taskInfo.topActivityInfo != null
                && taskInfo.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_STANDARD;
    }

    /**
     * Returns an {@link InternalBacklinksData} that represents the Backlink data internally, which
     * is captured by querying the system using {@link TaskInfo#taskId}.
     */
    private ListenableFuture<InternalBacklinksData> getBacklinksDataForTaskInfo(
            InternalTaskInfo internalTaskInfo) {
        DebugLogger.INSTANCE.logcatMessage(this,
                () -> String.format("getBacklinksDataForTaskId for taskId %d; topActivity %s",
                        internalTaskInfo.getTaskId(),
                        internalTaskInfo.getTopActivityNameForDebugLogging()));

        // Unlike other SysUI components, App Clips is started by the notes app so it runs as the
        // same user as the notes app. That is, if the notes app was running as work profile user
        // then App Clips also runs as work profile user. This is why while checking for user of the
        // screenshotted app the check is performed using UserHandle.myUserId instead of using the
        // more complex UserTracker.
        if (internalTaskInfo.getUserId() != UserHandle.myUserId()) {
            return getCrossProfileErrorBacklinkForTask(internalTaskInfo);
        }

        SettableFuture<InternalBacklinksData> backlinksData = SettableFuture.create();
        int taskId = internalTaskInfo.getTaskId();
        mAssistContentRequester.requestAssistContent(taskId, assistContent -> backlinksData.set(
                getBacklinksDataFromAssistContent(internalTaskInfo, assistContent)));
        return withTimeout(backlinksData);
    }

    private ListenableFuture<InternalBacklinksData> getCrossProfileErrorBacklinkForTask(
            InternalTaskInfo internalTaskInfo) {
        String appName = internalTaskInfo.getTopActivityAppName();
        Drawable appIcon = internalTaskInfo.getTopActivityAppIcon();
        InternalBacklinksData errorData = new CrossProfileError(appIcon, appName);
        return Futures.immediateFuture(errorData);
    }

    /** Returns the same {@link ListenableFuture} but with a 5 {@link TimeUnit#SECONDS} timeout. */
    private static <V> ListenableFuture<V> withTimeout(ListenableFuture<V> future) {
        return Futures.withTimeout(future, 5L, TimeUnit.SECONDS,
                newSingleThreadScheduledExecutor());
    }

    /**
     * A utility method to get {@link InternalBacklinksData} to use for Backlinks functionality from
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
     * @param internalTaskInfo {@link InternalTaskInfo} of the task which provided the
     * {@link AssistContent}.
     * @param content the {@link AssistContent} to map into Backlinks {@link ClipData}.
     * @return {@link InternalBacklinksData} that represents the Backlinks data along with app icon.
     */
    private InternalBacklinksData getBacklinksDataFromAssistContent(
            InternalTaskInfo internalTaskInfo,
            @Nullable AssistContent content) {
        DebugLogger.INSTANCE.logcatMessage(this,
                () -> String.format("getBacklinksDataFromAssistContent taskId %d; topActivity %s",
                        internalTaskInfo.getTaskId(),
                        internalTaskInfo.getTopActivityNameForDebugLogging()));

        String screenshottedAppName = internalTaskInfo.getTopActivityAppName();
        Drawable screenshottedAppIcon = internalTaskInfo.getTopActivityAppIcon();
        Intent screenshottedAppMainLauncherIntent = getMainLauncherIntentForTask(
                internalTaskInfo.getTopActivityPackageName(), internalTaskInfo.getPackageManager());
        ClipData screenshottedAppMainLauncherClipData =
                ClipData.newIntent(screenshottedAppName, screenshottedAppMainLauncherIntent);
        InternalBacklinksData fallback =
                new BacklinksData(screenshottedAppMainLauncherClipData, screenshottedAppIcon);
        if (content == null) {
            return fallback;
        }

        // First preference is given to app provided uri.
        if (content.isAppProvidedWebUri()) {
            DebugLogger.INSTANCE.logcatMessage(this,
                    () -> "getBacklinksDataFromAssistContent: app has provided a uri");

            Uri uri = content.getWebUri();
            Intent backlinksIntent = new Intent(ACTION_VIEW).setData(uri);
            BacklinkDisplayInfo backlinkDisplayInfo = getInfoThatResolvesIntent(backlinksIntent,
                    internalTaskInfo);
            if (backlinkDisplayInfo != null) {
                DebugLogger.INSTANCE.logcatMessage(this,
                        () -> "getBacklinksDataFromAssistContent: using app provided uri");
                return new BacklinksData(
                        ClipData.newRawUri(backlinkDisplayInfo.getDisplayLabel(), uri),
                        backlinkDisplayInfo.getAppIcon());
            }
        }

        // Second preference is given to app provided, hopefully deep-linking, intent.
        if (content.isAppProvidedIntent()) {
            DebugLogger.INSTANCE.logcatMessage(this,
                    () -> "getBacklinksDataFromAssistContent: app has provided an intent");

            Intent backlinksIntent = content.getIntent();
            BacklinkDisplayInfo backlinkDisplayInfo = getInfoThatResolvesIntent(backlinksIntent,
                    internalTaskInfo);
            if (backlinkDisplayInfo != null) {
                DebugLogger.INSTANCE.logcatMessage(this,
                        () -> "getBacklinksDataFromAssistContent: using app provided intent");
                return new BacklinksData(
                        ClipData.newIntent(backlinkDisplayInfo.getDisplayLabel(), backlinksIntent),
                        backlinkDisplayInfo.getAppIcon());
            }
        }

        DebugLogger.INSTANCE.logcatMessage(this,
                () -> "getBacklinksDataFromAssistContent: using fallback");
        return fallback;
    }

    /**
     * Returns {@link BacklinkDisplayInfo} for the app that would resolve the provided backlink
     * {@link Intent}.
     *
     * <p>The method uses the {@link PackageManager} available in the provided
     * {@link InternalTaskInfo}.
     *
     * <p>This method returns {@code null} if Android is not able to resolve the backlink intent or
     * if the resolved app does not have an icon in the launcher.
     */
    @Nullable
    private BacklinkDisplayInfo getInfoThatResolvesIntent(Intent backlinkIntent,
            InternalTaskInfo internalTaskInfo) {
        PackageManager packageManager = internalTaskInfo.getPackageManager();

        // Query for all available activities as there is a chance that multiple apps could resolve
        // the intent. In such cases the normal `intent.resolveActivity` API returns the activity
        // resolver info which isn't helpful for further checks. Also, using MATCH_DEFAULT_ONLY flag
        // is required as that flag will be used when the notes app builds the intent and calls
        // startActivity with the intent.
        List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(backlinkIntent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfos.isEmpty()) {
            DebugLogger.INSTANCE.logcatMessage(this,
                    () -> "getInfoThatResolvesIntent: could not resolve backlink intent");
            return null;
        }

        // Only use the first result as the list is ordered from best match to worst and Android
        // will also use the best match with `intent.startActivity` API which notes app will use.
        ActivityInfo activityInfo = resolveInfos.get(0).activityInfo;
        if (activityInfo == null) {
            DebugLogger.INSTANCE.logcatMessage(this,
                    () -> "getInfoThatResolvesIntent: could not find activity info for backlink "
                            + "intent");
            return null;
        }

        // Ignore resolved backlink app if users cannot start it through all apps tray.
        if (!canAppStartThroughLauncher(activityInfo.packageName, packageManager)) {
            DebugLogger.INSTANCE.logcatMessage(this,
                    () -> "getInfoThatResolvesIntent: ignoring resolved backlink app as it cannot"
                            + " start through launcher");
            return null;
        }

        Drawable appIcon = InternalBacklinksDataKt.getAppIcon(activityInfo, packageManager);
        String appName = InternalBacklinksDataKt.getAppName(activityInfo, packageManager);
        return new BacklinkDisplayInfo(appIcon, appName);
    }

    /**
     * Returns whether the app represented by the provided {@code pkgName} can be launched through
     * the all apps tray by the user.
     */
    private static boolean canAppStartThroughLauncher(String pkgName, PackageManager pkgManager) {
        // Use Intent.resolveActivity API to check if the intent resolves as that is what Android
        // uses internally when apps use Context.startActivity.
        return getMainLauncherIntentForTask(pkgName, pkgManager)
                .resolveActivity(pkgManager) != null;
    }

    private static Intent getMainLauncherIntentForTask(String pkgName,
            PackageManager packageManager) {
        Intent intent = new Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(pkgName);

        // Not all apps use DEFAULT_CATEGORY for their main launcher activity so the exact component
        // needs to be queried and set on the Intent in order for note-taking apps to be able to
        // start this intent. When starting an activity with an implicit intent, Android adds the
        // DEFAULT_CATEGORY flag otherwise it fails to resolve the intent.
        ResolveInfo resolvedActivity = packageManager.resolveActivity(intent, /* flags= */ 0);
        if (resolvedActivity != null) {
            intent.setComponent(resolvedActivity.getComponentInfo().getComponentName());
        }

        return intent;
    }

    /** Helper factory to help with injecting {@link AppClipsViewModel}. */
    static final class Factory implements ViewModelProvider.Factory {

        private final AppClipsCrossProcessHelper mAppClipsCrossProcessHelper;
        private final ImageExporter mImageExporter;
        private final IActivityTaskManager mAtmService;
        private final AssistContentRequester mAssistContentRequester;
        @Application private final Context mContext;
        @Main
        private final Executor mMainExecutor;
        @Background
        private final Executor mBgExecutor;

        @Inject
        Factory(AppClipsCrossProcessHelper appClipsCrossProcessHelper, ImageExporter imageExporter,
                IActivityTaskManager atmService, AssistContentRequester assistContentRequester,
                @Application Context context, @Main Executor mainExecutor,
                @Background Executor bgExecutor) {
            mAppClipsCrossProcessHelper = appClipsCrossProcessHelper;
            mImageExporter = imageExporter;
            mAtmService = atmService;
            mAssistContentRequester = assistContentRequester;
            mContext = context;
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
                    mAtmService, mAssistContentRequester, mContext, mMainExecutor,
                    mBgExecutor);
        }
    }
}
