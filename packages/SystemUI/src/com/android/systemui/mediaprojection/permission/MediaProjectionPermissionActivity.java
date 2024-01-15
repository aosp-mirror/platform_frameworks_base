/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.mediaprojection.permission;

import static android.Manifest.permission.LOG_COMPAT_CHANGE;
import static android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG;
import static android.media.projection.IMediaProjectionManager.EXTRA_PACKAGE_REUSING_GRANTED_CONSENT;
import static android.media.projection.IMediaProjectionManager.EXTRA_USER_REVIEW_GRANTED_CONSENT;
import static android.media.projection.ReviewGrantedConsentResult.RECORD_CANCEL;
import static android.media.projection.ReviewGrantedConsentResult.RECORD_CONTENT_DISPLAY;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.android.systemui.mediaprojection.MediaProjectionServiceHelper.OVERRIDE_DISABLE_MEDIA_PROJECTION_SINGLE_APP_OPTION;
import static com.android.systemui.mediaprojection.permission.ScreenShareOptionKt.ENTIRE_SCREEN;
import static com.android.systemui.mediaprojection.permission.ScreenShareOptionKt.SINGLE_APP;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions.LaunchCookie;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.IMediaProjection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.media.projection.ReviewGrantedConsentResult;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.BidiFormatter;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Window;

import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger;
import com.android.systemui.mediaprojection.MediaProjectionServiceHelper;
import com.android.systemui.mediaprojection.SessionCreationSource;
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorActivity;
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDevicePolicyResolver;
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDisabledDialog;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.AlertDialogWithDelegate;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.util.Utils;

import dagger.Lazy;

import javax.inject.Inject;

public class MediaProjectionPermissionActivity extends Activity
        implements DialogInterface.OnClickListener {
    private static final String TAG = "MediaProjectionPermissionActivity";
    private static final float MAX_APP_NAME_SIZE_PX = 500f;
    private static final String ELLIPSIS = "\u2026";

    private final FeatureFlags mFeatureFlags;
    private final Lazy<ScreenCaptureDevicePolicyResolver> mScreenCaptureDevicePolicyResolver;
    private final StatusBarManager mStatusBarManager;
    private final MediaProjectionMetricsLogger mMediaProjectionMetricsLogger;

    private String mPackageName;
    private int mUid;

    private AlertDialog mDialog;

    // Indicates if user must review already-granted consent that the MediaProjection app is
    // attempting to re-use.
    private boolean mReviewGrantedConsentRequired = false;
    // Indicates if the user has consented to record, but is continuing in another activity to
    // select a particular task to capture.
    private boolean mUserSelectingTask = false;

    @Inject
    public MediaProjectionPermissionActivity(FeatureFlags featureFlags,
            Lazy<ScreenCaptureDevicePolicyResolver> screenCaptureDevicePolicyResolver,
            StatusBarManager statusBarManager,
            MediaProjectionMetricsLogger mediaProjectionMetricsLogger) {
        mFeatureFlags = featureFlags;
        mScreenCaptureDevicePolicyResolver = screenCaptureDevicePolicyResolver;
        mStatusBarManager = statusBarManager;
        mMediaProjectionMetricsLogger = mediaProjectionMetricsLogger;
    }

    @Override
    @RequiresPermission(allOf = {READ_COMPAT_CHANGE_CONFIG, LOG_COMPAT_CHANGE})
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent launchingIntent = getIntent();
        mReviewGrantedConsentRequired = launchingIntent.getBooleanExtra(
                EXTRA_USER_REVIEW_GRANTED_CONSENT, false);

        mPackageName = getCallingPackage();

        // This activity is launched directly by an app, or system server. System server provides
        // the package name through the intent if so.
        if (mPackageName == null) {
            if (launchingIntent.hasExtra(EXTRA_PACKAGE_REUSING_GRANTED_CONSENT)) {
                mPackageName = launchingIntent.getStringExtra(
                        EXTRA_PACKAGE_REUSING_GRANTED_CONSENT);
            } else {
                setResult(RESULT_CANCELED);
                finish(RECORD_CANCEL, /* projection= */ null);
                return;
            }
        }

        PackageManager packageManager = getPackageManager();
        ApplicationInfo aInfo;
        try {
            aInfo = packageManager.getApplicationInfo(mPackageName, 0);
            mUid = aInfo.uid;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to look up package name", e);
            setResult(RESULT_CANCELED);
            finish(RECORD_CANCEL, /* projection= */ null);
            return;
        }

        try {
            if (MediaProjectionServiceHelper.hasProjectionPermission(mUid, mPackageName)) {
                if (savedInstanceState == null) {
                    mMediaProjectionMetricsLogger.notifyProjectionInitiated(
                            mUid, SessionCreationSource.APP);
                }
                final IMediaProjection projection =
                        MediaProjectionServiceHelper.createOrReuseProjection(mUid, mPackageName,
                                mReviewGrantedConsentRequired);

                LaunchCookie launchCookie = launchingIntent.getParcelableExtra(
                        MediaProjectionManager.EXTRA_LAUNCH_COOKIE, LaunchCookie.class);
                if (launchCookie != null) {
                    projection.setLaunchCookie(launchCookie);
                }

                // Automatically grant consent if a system-privileged component is recording.
                final Intent intent = new Intent();
                intent.putExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION,
                        projection.asBinder());
                setResult(RESULT_OK, intent);
                finish(RECORD_CONTENT_DISPLAY, projection);
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error checking projection permissions", e);
            setResult(RESULT_CANCELED);
            finish(RECORD_CANCEL, /* projection= */ null);
            return;
        }

        if (mFeatureFlags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES)) {
            if (showScreenCaptureDisabledDialogIfNeeded()) {
                setResult(RESULT_CANCELED);
                finish(RECORD_CANCEL, /* projection= */ null);
                return;
            }
        }

        TextPaint paint = new TextPaint();
        paint.setTextSize(42);

        CharSequence dialogText = null;
        CharSequence dialogTitle = null;
        String appName = null;
        if (Utils.isHeadlessRemoteDisplayProvider(packageManager, mPackageName)) {
            dialogText = getString(R.string.media_projection_sys_service_dialog_warning);
            dialogTitle = getString(R.string.media_projection_sys_service_dialog_title);
        } else {
            String label = aInfo.loadLabel(packageManager).toString();

            // If the label contains new line characters it may push the security
            // message below the fold of the dialog. Labels shouldn't have new line
            // characters anyways, so just truncate the message the first time one
            // is seen.
            final int labelLength = label.length();
            int offset = 0;
            while (offset < labelLength) {
                final int codePoint = label.codePointAt(offset);
                final int type = Character.getType(codePoint);
                if (type == Character.LINE_SEPARATOR
                        || type == Character.CONTROL
                        || type == Character.PARAGRAPH_SEPARATOR) {
                    label = label.substring(0, offset) + ELLIPSIS;
                    break;
                }
                offset += Character.charCount(codePoint);
            }

            if (label.isEmpty()) {
                label = mPackageName;
            }

            String unsanitizedAppName = TextUtils.ellipsize(label,
                    paint, MAX_APP_NAME_SIZE_PX, TextUtils.TruncateAt.END).toString();
            appName = BidiFormatter.getInstance().unicodeWrap(unsanitizedAppName);

            String actionText = getString(R.string.media_projection_dialog_warning, appName);
            SpannableString message = new SpannableString(actionText);

            int appNameIndex = actionText.indexOf(appName);
            if (appNameIndex >= 0) {
                message.setSpan(new StyleSpan(Typeface.BOLD),
                        appNameIndex, appNameIndex + appName.length(), 0);
            }
            dialogText = message;
            dialogTitle = getString(R.string.media_projection_dialog_title, appName);
        }

        // Using application context for the dialog, instead of the activity context, so we get
        // the correct screen width when in split screen.
        Context dialogContext = getApplicationContext();
        if (isPartialScreenSharingEnabled()) {
            final boolean overrideDisableSingleAppOption = CompatChanges.isChangeEnabled(
                    OVERRIDE_DISABLE_MEDIA_PROJECTION_SINGLE_APP_OPTION,
                    mPackageName, getHostUserHandle());
            MediaProjectionPermissionDialogDelegate delegate =
                    new MediaProjectionPermissionDialogDelegate(
                            dialogContext,
                            getMediaProjectionConfig(),
                            dialog -> {
                                ScreenShareOption selectedOption =
                                        dialog.getSelectedScreenShareOption();
                                grantMediaProjectionPermission(selectedOption.getMode());
                            },
                            () -> finish(RECORD_CANCEL, /* projection= */ null),
                            appName,
                            overrideDisableSingleAppOption,
                            mUid,
                            mMediaProjectionMetricsLogger);
            mDialog =
                    new AlertDialogWithDelegate(
                            dialogContext, R.style.Theme_SystemUI_Dialog, delegate);
        } else {
            AlertDialog.Builder dialogBuilder =
                    new AlertDialog.Builder(dialogContext, R.style.Theme_SystemUI_Dialog)
                            .setTitle(dialogTitle)
                            .setIcon(R.drawable.ic_media_projection_permission)
                            .setMessage(dialogText)
                            .setPositiveButton(R.string.media_projection_action_text, this)
                            .setNeutralButton(android.R.string.cancel, this);
            mDialog = dialogBuilder.create();
        }

        if (savedInstanceState == null) {
            mMediaProjectionMetricsLogger.notifyProjectionInitiated(
                    mUid,
                    appName == null
                            ? SessionCreationSource.CAST
                            : SessionCreationSource.APP);
        }

        setUpDialog(mDialog);
        mDialog.show();

        if (savedInstanceState == null) {
            mMediaProjectionMetricsLogger.notifyPermissionRequestDisplayed(mUid);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDialog != null) {
            mDialog.setOnDismissListener(null);
            mDialog.setOnCancelListener(null);
            mDialog.dismiss();
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == AlertDialog.BUTTON_POSITIVE) {
            grantMediaProjectionPermission(ENTIRE_SCREEN);
        } else {
            if (mDialog != null) {
                mDialog.dismiss();
            }
            setResult(RESULT_CANCELED);
            finish(RECORD_CANCEL, /* projection= */ null);
        }
    }

    private void setUpDialog(AlertDialog dialog) {
        SystemUIDialog.registerDismissListener(dialog);
        SystemUIDialog.applyFlags(dialog);
        SystemUIDialog.setDialogSize(dialog);

        dialog.setOnCancelListener(this::onDialogDismissedOrCancelled);
        dialog.setOnDismissListener(this::onDialogDismissedOrCancelled);
        dialog.create();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);

        final Window w = dialog.getWindow();
        w.addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
    }

    private boolean showScreenCaptureDisabledDialogIfNeeded() {
        final UserHandle hostUserHandle = getHostUserHandle();
        if (mScreenCaptureDevicePolicyResolver.get()
                .isScreenCaptureCompletelyDisabled(hostUserHandle)) {
            // Using application context for the dialog, instead of the activity context, so we get
            // the correct screen width when in split screen.
            Context dialogContext = getApplicationContext();
            AlertDialog dialog = new ScreenCaptureDisabledDialog(dialogContext);
            setUpDialog(dialog);
            dialog.show();
            return true;
        }

        return false;
    }

    private void grantMediaProjectionPermission(int screenShareMode) {
        try {
            if (screenShareMode == ENTIRE_SCREEN) {
                final IMediaProjection projection =
                        MediaProjectionServiceHelper.createOrReuseProjection(mUid, mPackageName,
                                mReviewGrantedConsentRequired);
                final Intent intent = new Intent();
                intent.putExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION,
                        projection.asBinder());
                setResult(RESULT_OK, intent);
                finish(RECORD_CONTENT_DISPLAY, projection);
            }
            if (isPartialScreenSharingEnabled() && screenShareMode == SINGLE_APP) {
                IMediaProjection projection = MediaProjectionServiceHelper.createOrReuseProjection(
                        mUid, mPackageName, mReviewGrantedConsentRequired);
                final Intent intent = new Intent(this,
                        MediaProjectionAppSelectorActivity.class);
                intent.putExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION,
                        projection.asBinder());
                intent.putExtra(MediaProjectionAppSelectorActivity.EXTRA_HOST_APP_USER_HANDLE,
                        getHostUserHandle());
                intent.putExtra(
                        MediaProjectionAppSelectorActivity.EXTRA_HOST_APP_UID,
                        getLaunchedFromUid());
                intent.putExtra(EXTRA_USER_REVIEW_GRANTED_CONSENT, mReviewGrantedConsentRequired);
                intent.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);

                // Start activity from the current foreground user to avoid creating a separate
                // SystemUI process without access to recent tasks because it won't have
                // WM Shell running inside.
                mUserSelectingTask = true;
                startActivityAsUser(intent, UserHandle.of(ActivityManager.getCurrentUser()));
                // close shade if it's open
                mStatusBarManager.collapsePanels();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error granting projection permission", e);
            setResult(RESULT_CANCELED);
            finish(RECORD_CANCEL, /* projection= */ null);
        } finally {
            if (mDialog != null) {
                mDialog.dismiss();
            }
        }
    }

    private UserHandle getHostUserHandle() {
        return UserHandle.getUserHandleForUid(getLaunchedFromUid());
    }

    @Override
    public void finish() {
        // Default to cancelling recording when user needs to review consent.
        // Don't send cancel if the user has moved on to the next activity.
        if (!mUserSelectingTask) {
            finish(RECORD_CANCEL, /* projection= */ null);
        } else {
            super.finish();
        }
    }

    private void finish(@ReviewGrantedConsentResult int consentResult,
            @Nullable IMediaProjection projection) {
        MediaProjectionServiceHelper.setReviewedConsentIfNeeded(
                consentResult, mReviewGrantedConsentRequired, projection);
        super.finish();
    }

    private void onDialogDismissedOrCancelled(DialogInterface dialogInterface) {
        if (!isFinishing()) {
            finish();
        }
    }

    @Nullable
    private MediaProjectionConfig getMediaProjectionConfig() {
        Intent intent = getIntent();
        if (intent == null) {
            return null;
        }
        return intent.getParcelableExtra(
                MediaProjectionManager.EXTRA_MEDIA_PROJECTION_CONFIG);
    }

    private boolean isPartialScreenSharingEnabled() {
        return mFeatureFlags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING);
    }
}
