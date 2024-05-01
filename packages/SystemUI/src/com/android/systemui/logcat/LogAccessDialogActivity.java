/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.logcat;

import android.annotation.StyleRes;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.Slog;
import android.view.ContextThemeWrapper;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ILogAccessDialogCallback;
import com.android.systemui.res.R;

/**
 * Dialog responsible for obtaining user consent per-use log access
 */
public class LogAccessDialogActivity extends Activity implements
        View.OnClickListener {
    private static final String TAG = LogAccessDialogActivity.class.getSimpleName();
    public static final String EXTRA_CALLBACK = "EXTRA_CALLBACK";


    private static final int DIALOG_TIME_OUT = Build.IS_DEBUGGABLE ? 60000 : 300000;
    private static final int MSG_DISMISS_DIALOG = 0;

    private String mPackageName;
    private int mUid;
    private ILogAccessDialogCallback mCallback;

    private String mAlertTitle;
    private String mAlertBody;

    private boolean mAlertLearnMoreLink;
    private SpannableString mAlertLearnMore;

    private AlertDialog.Builder mAlertDialog;
    private AlertDialog mAlert;
    @VisibleForTesting
    protected View mAlertView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // retrieve Intent extra information
        if (!readIntentInfo(getIntent())) {
            Slog.e(TAG, "Invalid Intent extras, finishing");
            finish();
            return;
        }

        // retrieve the title string from passed intent extra
        try {
            mAlertTitle = getTitleString(this, mPackageName, mUid);
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "Unable to fetch label of package " + mPackageName, e);
            declineLogAccess();
            finish();
            return;
        }

        mAlertBody = getString(R.string.log_access_confirmation_body);
        mAlertLearnMoreLink = this.getResources()
            .getBoolean(R.bool.log_access_confirmation_learn_more_as_link);
        if (mAlertLearnMoreLink) {
            mAlertLearnMore = new SpannableString(
                    getString(R.string.log_access_confirmation_learn_more));
            mAlertLearnMore.setSpan(new URLSpan(
                    getString(R.string.log_access_confirmation_learn_more_url)),
                    0, mAlertLearnMore.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            mAlertLearnMore = new SpannableString(
                getString(R.string.log_access_confirmation_learn_more_at,
                getString(R.string.log_access_confirmation_learn_more_url)));
        }

        // create View
        int themeId = R.style.LogAccessDialogTheme;
        mAlertView = createView(themeId);

        // create AlertDialog
        mAlertDialog = new AlertDialog.Builder(this, themeId);
        mAlertDialog.setView(mAlertView);
        mAlertDialog.setOnCancelListener(dialog -> declineLogAccess());
        mAlertDialog.setOnDismissListener(dialog -> {
            mAlert = null;
            finish();
        });

        // show Alert
        mAlert = mAlertDialog.create();
        mAlert.getWindow().setHideOverlayWindows(true);
        mAlert.show();

        // set Alert Timeout
        mHandler.sendEmptyMessageDelayed(MSG_DISMISS_DIALOG, DIALOG_TIME_OUT);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && mAlert != null) {
            mAlert.cancel();
        }
    }

    private boolean readIntentInfo(Intent intent) {
        if (intent == null) {
            Slog.e(TAG, "Intent is null");
            return false;
        }

        mCallback = ILogAccessDialogCallback.Stub.asInterface(
                intent.getExtras().getBinder(EXTRA_CALLBACK));
        if (mCallback == null) {
            Slog.e(TAG, "Missing callback");
            return false;
        }

        mPackageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (mPackageName == null || mPackageName.length() == 0) {
            Slog.e(TAG, "Missing package name extra");
            return false;
        }

        if (!intent.hasExtra(Intent.EXTRA_UID)) {
            Slog.e(TAG, "Missing EXTRA_UID");
            return false;
        }

        mUid = intent.getIntExtra(Intent.EXTRA_UID, 0);

        return true;
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_DISMISS_DIALOG:
                    if (mAlert != null) {
                        mAlert.dismiss();
                        declineLogAccess();
                    }
                    break;

                default:
                    break;
            }
        }
    };

    protected String getTitleString(Context context, String callingPackage, int uid)
            throws NameNotFoundException {
        PackageManager pm = context.getPackageManager();

        CharSequence appLabel = pm.getApplicationInfoAsUser(callingPackage,
                PackageManager.MATCH_DIRECT_BOOT_AUTO,
                UserHandle.getUserId(uid)).loadLabel(pm);

        String titleString = context.getString(R.string.log_access_confirmation_title, appLabel);

        return titleString;
    }

    /**
     * Returns the dialog view.
     * If we cannot retrieve the package name, it returns null and we decline the full device log
     * access
     */
    private View createView(@StyleRes int themeId) {
        Context themedContext = new ContextThemeWrapper(this, themeId);
        final View view = LayoutInflater.from(themedContext).inflate(
                R.layout.log_access_user_consent_dialog_permission, null /*root*/);

        if (view == null) {
            throw new InflateException();
        }

        ((TextView) view.findViewById(R.id.log_access_dialog_title))
            .setText(mAlertTitle);

        if (!TextUtils.isEmpty(mAlertLearnMore)) {
            ((TextView) view.findViewById(R.id.log_access_dialog_body))
                .setText(TextUtils.concat(mAlertBody, "\n\n", mAlertLearnMore));
            if (mAlertLearnMoreLink) {
                ((TextView) view.findViewById(R.id.log_access_dialog_body))
                    .setMovementMethod(LinkMovementMethod.getInstance());
            }
        } else {
            ((TextView) view.findViewById(R.id.log_access_dialog_body))
                    .setText(mAlertBody);
        }

        Button button_allow = (Button) view.findViewById(R.id.log_access_dialog_allow_button);
        button_allow.setOnClickListener(this);

        Button button_deny = (Button) view.findViewById(R.id.log_access_dialog_deny_button);
        button_deny.setOnClickListener(this);

        return view;

    }

    @Override
    public void onClick(View view) {
        try {
            if (view.getId() == R.id.log_access_dialog_allow_button) {
                mCallback.approveAccessForClient(mUid, mPackageName);
            } else if (view.getId() == R.id.log_access_dialog_deny_button) {
                declineLogAccess();
            }
        } catch (RemoteException ignored) {
            // Do nothing.
        } finally {
            mAlert.dismiss();
        }
    }

    private void declineLogAccess() {
        try {
            mCallback.declineAccessForClient(mUid, mPackageName);
        } catch (RemoteException e) {
            finish();
        }
    }
}
