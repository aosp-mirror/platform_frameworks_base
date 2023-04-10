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
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;

import static com.android.systemui.flags.Flags.SCREENSHOT_APP_CLIPS;
import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_TRIGGERED;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.notetask.NoteTaskController;
import com.android.systemui.notetask.NoteTaskEntryPoint;
import com.android.systemui.settings.UserTracker;
import com.android.wm.shell.bubbles.Bubbles;

import java.util.Optional;

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

    private final DevicePolicyManager mDevicePolicyManager;
    private final FeatureFlags mFeatureFlags;
    private final Optional<Bubbles> mOptionalBubbles;
    private final NoteTaskController mNoteTaskController;
    private final PackageManager mPackageManager;
    private final UserTracker mUserTracker;
    private final UiEventLogger mUiEventLogger;
    private final UserManager mUserManager;
    private final ResultReceiver mResultReceiver;

    private Intent mKillAppClipsBroadcastIntent;

    @Inject
    public AppClipsTrampolineActivity(DevicePolicyManager devicePolicyManager, FeatureFlags flags,
            Optional<Bubbles> optionalBubbles, NoteTaskController noteTaskController,
            PackageManager packageManager, UserTracker userTracker, UiEventLogger uiEventLogger,
            UserManager userManager, @Main Handler mainHandler) {
        mDevicePolicyManager = devicePolicyManager;
        mFeatureFlags = flags;
        mOptionalBubbles = optionalBubbles;
        mNoteTaskController = noteTaskController;
        mPackageManager = packageManager;
        mUserTracker = userTracker;
        mUiEventLogger = uiEventLogger;
        mUserManager = userManager;

        mResultReceiver = createResultReceiver(mainHandler);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            return;
        }

        if (mUserManager.isManagedProfile()) {
            maybeStartActivityForWPUser();
            finish();
            return;
        }

        if (!mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)) {
            finish();
            return;
        }

        if (mOptionalBubbles.isEmpty()) {
            setErrorResultAndFinish(CAPTURE_CONTENT_FOR_NOTE_FAILED);
            return;
        }

        if (!mOptionalBubbles.get().isAppBubbleTaskId(getTaskId())) {
            setErrorResultAndFinish(CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED);
            return;
        }

        if (mDevicePolicyManager.getScreenCaptureDisabled(null)) {
            setErrorResultAndFinish(CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN);
            return;
        }

        ComponentName componentName;
        try {
            componentName = ComponentName.unflattenFromString(
                    getString(R.string.config_screenshotAppClipsActivityComponent));
        } catch (Resources.NotFoundException e) {
            setErrorResultAndFinish(CAPTURE_CONTENT_FOR_NOTE_FAILED);
            return;
        }

        if (componentName == null || componentName.getPackageName().isEmpty()
                || componentName.getClassName().isEmpty()) {
            setErrorResultAndFinish(CAPTURE_CONTENT_FOR_NOTE_FAILED);
            return;
        }

        String callingPackageName = getCallingPackage();
        Intent intent = new Intent().setComponent(componentName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RESULT_RECEIVER, mResultReceiver)
                .putExtra(EXTRA_CALLING_PACKAGE_NAME, callingPackageName);

        try {
            // Start the App Clips activity.
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

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (isFinishing() && mKillAppClipsBroadcastIntent != null) {
            sendBroadcast(mKillAppClipsBroadcastIntent, PERMISSION_SELF);
        }
    }

    private void maybeStartActivityForWPUser() {
        UserHandle mainUser = mUserManager.getMainUser();
        if (mainUser == null) {
            setErrorResultAndFinish(CAPTURE_CONTENT_FOR_NOTE_FAILED);
            return;
        }

        // Start the activity as the main user with activity result forwarding.
        startActivityAsUser(
                new Intent(this, AppClipsTrampolineActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT), mainUser);
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
                    APPLICATION_INFO_FLAGS, mUserTracker.getUserId()).uid;
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
                convertedData.setData(uri).addFlags(FLAG_GRANT_READ_URI_PERMISSION);
            }

            // Broadcast no longer required, setting it to null.
            mKillAppClipsBroadcastIntent = null;

            // Expand the note bubble before returning the result.
            mNoteTaskController.showNoteTask(NoteTaskEntryPoint.APP_CLIPS);
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

        ResultReceiver resultReceiver  = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return resultReceiver;
    }

    /** This is a test only API for mocking response from {@link AppClipsActivity}. */
    @VisibleForTesting
    public ResultReceiver getResultReceiverForTest() {
        return mResultReceiver;
    }
}
