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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.IMediaProjection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.BidiFormatter;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

public class MediaProjectionPermissionActivity extends Activity
        implements DialogInterface.OnClickListener, CheckBox.OnCheckedChangeListener,
        DialogInterface.OnCancelListener {
    private static final String TAG = "MediaProjectionPermissionActivity";
    private static final float MAX_APP_NAME_SIZE_PX = 500f;
    private static final String ELLIPSIS = "\u2026";

    private boolean mPermanentGrant;
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

        try {
            if (mService.hasProjectionPermission(mUid, mPackageName)) {
                setResult(RESULT_OK, getMediaProjectionIntent(mUid, mPackageName,
                        false /*permanentGrant*/));
                finish();
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error checking projection permissions", e);
            finish();
            return;
        }

        TextPaint paint = new TextPaint();
        paint.setTextSize(42);

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

        mDialog = new AlertDialog.Builder(this)
                .setIcon(aInfo.loadIcon(packageManager))
                .setMessage(message)
                .setPositiveButton(R.string.media_projection_action_text, this)
                .setNegativeButton(android.R.string.cancel, this)
                .setView(R.layout.remember_permission_checkbox)
                .setOnCancelListener(this)
                .create();

        mDialog.create();

        Button btn = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        btn.getRootView().setFilterTouchesWhenObscured(true);

        ((CheckBox) mDialog.findViewById(R.id.remember)).setOnCheckedChangeListener(this);
        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);

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
                setResult(RESULT_OK, getMediaProjectionIntent(
                        mUid, mPackageName, mPermanentGrant));
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

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        mPermanentGrant = isChecked;
    }

    private Intent getMediaProjectionIntent(int uid, String packageName, boolean permanentGrant)
            throws RemoteException {
        IMediaProjection projection = mService.createProjection(uid, packageName,
                 MediaProjectionManager.TYPE_SCREEN_CAPTURE, permanentGrant);
        Intent intent = new Intent();
        intent.putExtra(MediaProjectionManager.EXTRA_MEDIA_PROJECTION, projection.asBinder());
        return intent;
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        finish();
    }
}
