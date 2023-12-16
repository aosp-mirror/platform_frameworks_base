/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.ui;

import static android.os.Process.INVALID_UID;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import com.android.packageinstaller.v2.model.UninstallRepository;
import com.android.packageinstaller.v2.model.UninstallRepository.CallerInfo;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallAborted;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallFailed;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallStage;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallSuccess;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallUninstalling;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallUserActionRequired;
import com.android.packageinstaller.v2.ui.fragments.UninstallConfirmationFragment;
import com.android.packageinstaller.v2.ui.fragments.UninstallErrorFragment;
import com.android.packageinstaller.v2.ui.fragments.UninstallUninstallingFragment;
import com.android.packageinstaller.v2.viewmodel.UninstallViewModel;
import com.android.packageinstaller.v2.viewmodel.UninstallViewModelFactory;

public class UninstallLaunch extends FragmentActivity implements UninstallActionListener {

    public static final String EXTRA_CALLING_PKG_UID =
        UninstallLaunch.class.getPackageName() + ".callingPkgUid";
    public static final String EXTRA_CALLING_ACTIVITY_NAME =
        UninstallLaunch.class.getPackageName() + ".callingActivityName";
    public static final String TAG = UninstallLaunch.class.getSimpleName();
    private static final String TAG_DIALOG = "dialog";

    private UninstallViewModel mUninstallViewModel;
    private UninstallRepository mUninstallRepository;
    private FragmentManager mFragmentManager;
    private NotificationManager mNotificationManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        // Never restore any state, esp. never create any fragments. The data in the fragment might
        // be stale, if e.g. the app was uninstalled while the activity was destroyed.
        super.onCreate(null);

        mFragmentManager = getSupportFragmentManager();
        mNotificationManager = getSystemService(NotificationManager.class);

        mUninstallRepository = new UninstallRepository(getApplicationContext());
        mUninstallViewModel = new ViewModelProvider(this,
            new UninstallViewModelFactory(this.getApplication(), mUninstallRepository)).get(
            UninstallViewModel.class);

        Intent intent = getIntent();
        CallerInfo callerInfo = new CallerInfo(
            intent.getStringExtra(EXTRA_CALLING_ACTIVITY_NAME),
            intent.getIntExtra(EXTRA_CALLING_PKG_UID, INVALID_UID));
        mUninstallViewModel.preprocessIntent(intent, callerInfo);

        mUninstallViewModel.getCurrentUninstallStage().observe(this,
            this::onUninstallStageChange);
    }

    /**
     * Main controller of the UI. This method shows relevant dialogs / fragments based on the
     * uninstall stage
     */
    private void onUninstallStageChange(UninstallStage uninstallStage) {
        if (uninstallStage.getStageCode() == UninstallStage.STAGE_ABORTED) {
            UninstallAborted aborted = (UninstallAborted) uninstallStage;
            if (aborted.getAbortReason() == UninstallAborted.ABORT_REASON_APP_UNAVAILABLE ||
                aborted.getAbortReason() == UninstallAborted.ABORT_REASON_USER_NOT_ALLOWED) {
                UninstallErrorFragment errorDialog = new UninstallErrorFragment(aborted);
                showDialogInner(errorDialog);
            } else {
                setResult(aborted.getActivityResultCode(), null, true);
            }
        } else if (uninstallStage.getStageCode() == UninstallStage.STAGE_USER_ACTION_REQUIRED) {
            UninstallUserActionRequired uar = (UninstallUserActionRequired) uninstallStage;
            UninstallConfirmationFragment confirmationDialog = new UninstallConfirmationFragment(
                uar);
            showDialogInner(confirmationDialog);
        } else if (uninstallStage.getStageCode() == UninstallStage.STAGE_UNINSTALLING) {
            // TODO: This shows a fragment whether or not user requests a result or not.
            //  Originally, if the user does not request a result, we used to show a notification.
            //  And a fragment if the user requests a result back. Should we consolidate and
            //  show a fragment always?
            UninstallUninstalling uninstalling = (UninstallUninstalling) uninstallStage;
            UninstallUninstallingFragment uninstallingDialog = new UninstallUninstallingFragment(
                uninstalling);
            showDialogInner(uninstallingDialog);
        } else if (uninstallStage.getStageCode() == UninstallStage.STAGE_FAILED) {
            UninstallFailed failed = (UninstallFailed) uninstallStage;
            if (!failed.returnResult()) {
                mNotificationManager.notify(failed.getUninstallId(),
                    failed.getUninstallNotification());
            }
            setResult(failed.getActivityResultCode(), failed.getResultIntent(), true);
        } else if (uninstallStage.getStageCode() == UninstallStage.STAGE_SUCCESS) {
            UninstallSuccess success = (UninstallSuccess) uninstallStage;
            if (success.getMessage() != null) {
                Toast.makeText(this, success.getMessage(), Toast.LENGTH_LONG).show();
            }
            setResult(success.getActivityResultCode(), success.getResultIntent(), true);
        } else {
            Log.e(TAG, "Invalid stage: " + uninstallStage.getStageCode());
            showDialogInner(null);
        }
    }

    /**
     * Replace any visible dialog by the dialog returned by InstallRepository
     *
     * @param newDialog The new dialog to display
     */
    private void showDialogInner(DialogFragment newDialog) {
        DialogFragment currentDialog = (DialogFragment) mFragmentManager.findFragmentByTag(
            TAG_DIALOG);
        if (currentDialog != null) {
            currentDialog.dismissAllowingStateLoss();
        }
        if (newDialog != null) {
            newDialog.show(mFragmentManager, TAG_DIALOG);
        }
    }

    public void setResult(int resultCode, Intent data, boolean shouldFinish) {
        super.setResult(resultCode, data);
        if (shouldFinish) {
            finish();
        }
    }

    @Override
    public void onPositiveResponse(boolean keepData) {
        mUninstallViewModel.initiateUninstall(keepData);
    }

    @Override
    public void onNegativeResponse() {
        mUninstallViewModel.cancelInstall();
        setResult(Activity.RESULT_FIRST_USER, null, true);
    }
}
