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

package com.android.systemui.media;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.BidiFormatter;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.util.Utils;

public class MediaProjectionPermissionActivity extends Activity
        implements DialogInterface.OnClickListener, DialogInterface.OnCancelListener {
    private static final String TAG = "MediaProjectionPermissionActivity";
    private static final float MAX_APP_NAME_SIZE_PX = 500f;
    private static final String ELLIPSIS = "\u2026";

    private String mPackageName;
    private int mUid;
    private IMediaProjectionManager mService;

    private AlertDialog mDialog;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mPackageName = getCallingPackage();
        IBinder b = ServiceManager.getService(MEDIA_PROJECTION_SERVICE);
        mService = IMediaProjectionManager.Stub.asInterface(b);

        if (mPackageName == null) {
            finish();
            return;
        }

        PackageManager packageManager = getPackageManager();
        ApplicationInfo aInfo;
        try {
            aInfo = packageManager.getApplicationInfo(mPackageName, 0);
            mUid = aInfo.uid;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "unable to look up package name", e);
            finish();
            return;
        }

        final boolean isSysUI = mPackageName.equals("com.android.systemui");
        final boolean isSysUIGranted = Settings.System.getInt(this.getContentResolver(),
                Settings.System.MEDIAPROJECTION_SYSUI_OK, 0) == 1;
        try {
            if (mService.hasProjectionPermission(mUid, mPackageName) ||
                    (isSysUI && isSysUIGranted)) {
                setResult(RESULT_OK, getMediaProjectionIntent(mUid, mPackageName));
                finish();
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error checking projection permissions", e);
            finish();
            return;
        }
        
        if (isSysUI) {
            // isSysUIGranted was false, let's remember we accepted the permission
            Settings.System.putInt(this.getContentResolver(),
                    Settings.System.MEDIAPROJECTION_SYSUI_OK, 1);
        }

        TextPaint paint = new TextPaint();
        paint.setTextSize(42);

        CharSequence dialogText = null;
        if (Utils.isHeadlessRemoteDisplayProvider(packageManager, mPackageName)) {
            dialogText = getString(R.string.media_projection_dialog_service_text);
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
            String appName = BidiFormatter.getInstance().unicodeWrap(unsanitizedAppName);

            String actionText = getString(R.string.media_projection_dialog_text, appName);
            SpannableString message = new SpannableString(actionText);

            int appNameIndex = actionText.indexOf(appName);
            if (appNameIndex >= 0) {
                message.setSpan(new StyleSpan(Typeface.BOLD),
                        appNameIndex, appNameIndex + appName.length(), 0);
            }
            dialogText = message;
        }

        String dialogTitle = getString(R.string.media_projection_dialog_title);

        View dialogTitleView = View.inflate(this, R.layout.media_projection_dialog_title, null);
        TextView titleText = (TextView) dialogTitleView.findViewById(R.id.dialog_title);
        titleText.setText(dialogTitle);

        mDialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogTitleView)
                .setMessage(dialogText)
                .setPositiveButton(R.string.media_projection_action_text, this)
                .setNegativeButton(android.R.string.cancel, this)
                .setOnCancelListener(this)
                .create();

        mDialog.create();
        mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);

        final Window w = mDialog.getWindow();
        w.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        w.addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        mDialog.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDialog != null) {
            mDialog.dismiss();
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        try {
            if (which == AlertDialog.BUTTON_POSITIVE) {
                setResult(RESULT_OK, getMediaProjectionIntent(mUid, mPackageName));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error granting projection permission", e);
            setResult(RESULT_CANCELED);
        } finally {
            if (mDialog != null) {
                mDialog.dismiss();
            }
            finish();
        }
    }

    private Intent getMediaProjectionIntent(int uid, String packageName)
            throws RemoteException {
        IMediaProjection projection = mService.createProjection(uid, packageName,
                 MediaProjectionManager.TYPE_SCREEN_CAPTURE, false /* permanentGrant */);
        Intent intent = new Intent();
        intent.putExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION, projection.asBinder());
        return intent;
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        finish();
    }
}
