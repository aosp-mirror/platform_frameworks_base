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

import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_SUCCESS;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED;
import static android.content.Intent.EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE;

import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_TRIGGERED;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.Intent.CaptureContentForNoteStatusCodes;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IAppClipsService;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.notetask.NoteTaskController;
import com.android.systemui.notetask.NoteTaskEntryPoint;
import com.android.systemui.res.R;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * A trampoline activity that is responsible for:
 * <ul>
 *     <li>Performing precondition checks before starting the actual screenshot activity.
 *     <li>Communicating with the screenshot activity and the calling activity.
 * </ul>
 *
 * <p>As this activity is started in a bubble app, the windowing for this activity is restricted
 * to the parent bubble app. The screenshot editing activity, see {@link AppClipsActivity}, is
 * started in a regular activity window using {@link Intent#FLAG_ACTIVITY_NEW_TASK}. However,
 * {@link Activity#startActivityForResult(Intent, int)} is not compatible with
 * {@link Intent#FLAG_ACTIVITY_NEW_TASK}. So, this activity acts as a trampoline activity to
 * abstract the complexity of communication with the screenshot editing activity for a simpler
 * developer experience.
 *
 * TODO(b/267309532): Polish UI and animations.
 */
public class AppClipsTrampolineActivity extends Activity {

    private static final String TAG = AppClipsTrampolineActivity.class.getSimpleName();
    static final String PERMISSION_SELF = "com.android.systemui.permission.SELF";
    static final String EXTRA_SCREENSHOT_URI = TAG + "SCREENSHOT_URI";
    static final String ACTION_FINISH_FROM_TRAMPOLINE = TAG + "FINISH_FROM_TRAMPOLINE";
    static final String EXTRA_RESULT_RECEIVER = TAG + "RESULT_RECEIVER";
    static final String EXTRA_CALLING_PACKAGE_NAME = TAG + "CALLING_PACKAGE_NAME";
    private static final ApplicationInfoFlags APPLICATION_INFO_FLAGS = ApplicationInfoFlags.of(0);

    private final NoteTaskController mNoteTaskController;
    private final PackageManager mPackageManager;
    private final UiEventLogger mUiEventLogger;
    private final BroadcastSender mBroadcastSender;
    @Background
    private final Executor mBgExecutor;
    @Main
    private final Executor mMainExecutor;
    private final ResultReceiver mResultReceiver;

    private final ServiceConnector<IAppClipsService> mAppClipsServiceConnector;

    private UserHandle mUserHandle;
    private Intent mKillAppClipsBroadcastIntent;

    @Inject
    public AppClipsTrampolineActivity(@Application Context context,
            NoteTaskController noteTaskController, PackageManager packageManager,
            UiEventLogger uiEventLogger, BroadcastSender broadcastSender,
            @Background Executor bgExecutor, @Main Executor mainExecutor,
            @Main Handler mainHandler) {
        mNoteTaskController = noteTaskController;
        mPackageManager = packageManager;
        mUiEventLogger = uiEventLogger;
        mBroadcastSender = broadcastSender;
        mBgExecutor = bgExecutor;
        mMainExecutor = mainExecutor;

        mResultReceiver = createResultReceiver(mainHandler);
        mAppClipsServiceConnector = createServiceConnector(context);
    }

    /** A constructor used only for testing to verify interactions with {@link ServiceConnector}. */
    @VisibleForTesting
    AppClipsTrampolineActivity(ServiceConnector<IAppClipsService> appClipsServiceConnector,
            NoteTaskController noteTaskController, PackageManager packageManager,
            UiEventLogger uiEventLogger, BroadcastSender broadcastSender,
            @Background Executor bgExecutor, @Main Executor mainExecutor,
            @Main Handler mainHandler) {
        mAppClipsServiceConnector = appClipsServiceConnector;
        mNoteTaskController = noteTaskController;
        mPackageManager = packageManager;
        mUiEventLogger = uiEventLogger;
        mBroadcastSender = broadcastSender;
        mBgExecutor = bgExecutor;
        mMainExecutor = mainExecutor;

        mResultReceiver = createResultReceiver(mainHandler);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            return;
        }

        mUserHandle = getUser();

        mBgExecutor.execute(() -> {
            AndroidFuture<Integer> statusCodeFuture = mAppClipsServiceConnector.postForResult(
                    service -> service.canLaunchCaptureContentActivityForNoteInternal(getTaskId()));
            statusCodeFuture.whenCompleteAsync(this::handleAppClipsStatusCode, mMainExecutor);
        });
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && mKillAppClipsBroadcastIntent != null) {
            mBroadcastSender.sendBroadcast(mKillAppClipsBroadcastIntent, PERMISSION_SELF);
        }

        super.onDestroy();
    }

    private void handleAppClipsStatusCode(@CaptureContentForNoteStatusCodes int statusCode,
            Throwable error) {
        if (isFinishing()) {
            // It's too late, trampoline activity is finishing or already finished. Return early.
            return;
        }

        if (error != null) {
            Log.d(TAG, "Error querying app clips service", error);
            setErrorResultAndFinish(statusCode);
            return;
        }

        switch (statusCode) {
            case CAPTURE_CONTENT_FOR_NOTE_SUCCESS:
                launchAppClipsActivity();
                break;

            case CAPTURE_CONTENT_FOR_NOTE_FAILED:
            case CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED:
            case CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN:
            default:
                setErrorResultAndFinish(statusCode);
        }
    }

    private void launchAppClipsActivity() {
        ComponentName componentName = ComponentName.unflattenFromString(
                    getString(R.string.config_screenshotAppClipsActivityComponent));
        String callingPackageName = getCallingPackage();

        Intent intent = new Intent()
                .setComponent(componentName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RESULT_RECEIVER, mResultReceiver)
                .putExtra(EXTRA_CALLING_PACKAGE_NAME, callingPackageName);
        try {
            startActivity(intent);

            // Set up the broadcast intent that will inform the above App Clips activity to finish
            // when this trampoline activity is finished.
            mKillAppClipsBroadcastIntent =
                    new Intent(ACTION_FINISH_FROM_TRAMPOLINE)
                            .setComponent(componentName)
                            .setPackage(componentName.getPackageName());

            // Log successful triggering of screenshot for notes.
            logScreenshotTriggeredUiEvent(callingPackageName);
        } catch (ActivityNotFoundException e) {
            setErrorResultAndFinish(CAPTURE_CONTENT_FOR_NOTE_FAILED);
        }
    }

    private void setErrorResultAndFinish(int errorCode) {
        setResult(RESULT_OK,
                new Intent().putExtra(EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE, errorCode));
        finish();
    }

    private void logScreenshotTriggeredUiEvent(@Nullable String callingPackageName) {
        int callingPackageUid = 0;
        try {
            callingPackageUid = mPackageManager.getApplicationInfoAsUser(callingPackageName,
                    APPLICATION_INFO_FLAGS, mUserHandle.getIdentifier()).uid;
        } catch (NameNotFoundException e) {
            Log.d(TAG, "Couldn't find notes app UID " + e);
        }

        mUiEventLogger.log(SCREENSHOT_FOR_NOTE_TRIGGERED, callingPackageUid, callingPackageName);
    }

    private class AppClipsResultReceiver extends ResultReceiver {

        AppClipsResultReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (isFinishing()) {
                // It's too late, trampoline activity is finishing or already finished.
                // Return early.
                return;
            }

            // Package the response that should be sent to the calling activity.
            Intent convertedData = new Intent();
            int statusCode = CAPTURE_CONTENT_FOR_NOTE_FAILED;
            if (resultData != null) {
                statusCode = resultData.getInt(EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE,
                        CAPTURE_CONTENT_FOR_NOTE_FAILED);
            }
            convertedData.putExtra(EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE, statusCode);

            if (statusCode == CAPTURE_CONTENT_FOR_NOTE_SUCCESS) {
                Uri uri = resultData.getParcelable(EXTRA_SCREENSHOT_URI, Uri.class);
                convertedData.setData(uri);
            }

            // Broadcast no longer required, setting it to null.
            mKillAppClipsBroadcastIntent = null;

            // Expand the note bubble before returning the result.
            mNoteTaskController.showNoteTaskAsUser(NoteTaskEntryPoint.APP_CLIPS, mUserHandle);
            setResult(RESULT_OK, convertedData);
            finish();
        }
    }

    /**
     * @return a {@link ResultReceiver} by initializing an {@link AppClipsResultReceiver} and
     * converting it into a generic {@link ResultReceiver} to pass across a different but trusted
     * process.
     */
    private ResultReceiver createResultReceiver(@Main Handler handler) {
        AppClipsResultReceiver appClipsResultReceiver = new AppClipsResultReceiver(handler);
        Parcel parcel = Parcel.obtain();
        appClipsResultReceiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        ResultReceiver resultReceiver = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return resultReceiver;
    }

    private ServiceConnector<IAppClipsService> createServiceConnector(
            @Application Context context) {
        return new ServiceConnector.Impl<>(context, new Intent(context, AppClipsService.class),
                Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY | Context.BIND_NOT_VISIBLE,
                UserHandle.USER_SYSTEM, IAppClipsService.Stub::asInterface);
    }

    /** This is a test only API for mocking response from {@link AppClipsActivity}. */
    @VisibleForTesting
    public ResultReceiver getResultReceiverForTest() {
        return mResultReceiver;
    }
}
