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

package com.android.packageinstaller.v2.ui;

import static android.os.Process.INVALID_UID;
import static com.android.packageinstaller.v2.model.installstagedata.InstallAborted.ABORT_REASON_INTERNAL_ERROR;
import static com.android.packageinstaller.v2.model.installstagedata.InstallAborted.ABORT_REASON_POLICY;

import android.content.Intent;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.Window;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import com.android.packageinstaller.R;
import com.android.packageinstaller.v2.model.InstallRepository;
import com.android.packageinstaller.v2.model.InstallRepository.CallerInfo;
import com.android.packageinstaller.v2.model.installstagedata.InstallAborted;
import com.android.packageinstaller.v2.model.installstagedata.InstallStage;
import com.android.packageinstaller.v2.ui.fragments.InstallStagingFragment;
import com.android.packageinstaller.v2.ui.fragments.SimpleErrorFragment;
import com.android.packageinstaller.v2.viewmodel.InstallViewModel;
import com.android.packageinstaller.v2.viewmodel.InstallViewModelFactory;

public class InstallLaunch extends FragmentActivity {

    public static final String EXTRA_CALLING_PKG_UID =
            InstallLaunch.class.getPackageName() + ".callingPkgUid";
    public static final String EXTRA_CALLING_PKG_NAME =
            InstallLaunch.class.getPackageName() + ".callingPkgName";
    private static final String TAG = InstallLaunch.class.getSimpleName();
    private static final String TAG_DIALOG = "dialog";
    private final boolean mLocalLOGV = false;
    private InstallViewModel mInstallViewModel;
    private InstallRepository mInstallRepository;

    private FragmentManager mFragmentManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.requestWindowFeature(Window.FEATURE_NO_TITLE);

        mFragmentManager = getSupportFragmentManager();
        mInstallRepository = new InstallRepository(getApplicationContext());
        mInstallViewModel = new ViewModelProvider(this,
                new InstallViewModelFactory(this.getApplication(), mInstallRepository)).get(
                InstallViewModel.class);

        Intent intent = getIntent();
        CallerInfo info = new CallerInfo(
                intent.getStringExtra(EXTRA_CALLING_PKG_NAME),
                intent.getIntExtra(EXTRA_CALLING_PKG_UID, INVALID_UID));
        mInstallViewModel.preprocessIntent(intent, info);

        mInstallViewModel.getCurrentInstallStage().observe(this, this::onInstallStageChange);
    }

    /**
     * Main controller of the UI. This method shows relevant dialogs based on the install stage
     */
    private void onInstallStageChange(InstallStage installStage) {
        if (installStage.getStageCode() == InstallStage.STAGE_STAGING) {
            InstallStagingFragment stagingDialog = new InstallStagingFragment();
            showDialogInner(stagingDialog);
        } else if (installStage.getStageCode() == InstallStage.STAGE_ABORTED) {
            InstallAborted aborted = (InstallAborted) installStage;
            switch (aborted.getAbortReason()) {
                // TODO: check if any dialog is to be shown for ABORT_REASON_INTERNAL_ERROR
                case ABORT_REASON_INTERNAL_ERROR -> setResult(RESULT_CANCELED, true);
                case ABORT_REASON_POLICY -> showPolicyRestrictionDialog(aborted);
                default -> setResult(RESULT_CANCELED, true);
            }
        } else {
            Log.d(TAG, "Unimplemented stage: " + installStage.getStageCode());
            showDialogInner(null);
        }
    }

    private void showPolicyRestrictionDialog(InstallAborted aborted) {
        String restriction = aborted.getMessage();
        Intent adminSupportIntent = aborted.getResultIntent();
        boolean shouldFinish;

        // If the given restriction is set by an admin, display information about the
        // admin enforcing the restriction for the affected user. If not enforced by the admin,
        // show the system dialog.
        if (adminSupportIntent != null) {
            if (mLocalLOGV) {
                Log.i(TAG, "Restriction set by admin, starting " + adminSupportIntent);
            }
            startActivity(adminSupportIntent);
            // Finish the package installer app since the next dialog will not be shown by this app
            shouldFinish = true;
        } else {
            if (mLocalLOGV) {
                Log.i(TAG, "Restriction set by system: " + restriction);
            }
            DialogFragment blockedByPolicyDialog = createDevicePolicyRestrictionDialog(restriction);
            // Don't finish the package installer app since the next dialog
            // will be shown by this app
            shouldFinish = false;
            showDialogInner(blockedByPolicyDialog);
        }
        setResult(RESULT_CANCELED, shouldFinish);
    }

    /**
     * Create a new dialog based on the install restriction enforced.
     *
     * @param restriction The restriction to create the dialog for
     * @return The dialog
     */
    private DialogFragment createDevicePolicyRestrictionDialog(String restriction) {
        if (mLocalLOGV) {
            Log.i(TAG, "createDialog(" + restriction + ")");
        }
        return switch (restriction) {
            case UserManager.DISALLOW_INSTALL_APPS ->
                new SimpleErrorFragment(R.string.install_apps_user_restriction_dlg_text);
            case UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY ->
                new SimpleErrorFragment(R.string.unknown_apps_user_restriction_dlg_text);
            default -> null;
        };
    }

    /**
     * Replace any visible dialog by the dialog returned by InstallRepository
     *
     * @param newDialog The new dialog to display
     */
    private void showDialogInner(@Nullable DialogFragment newDialog) {
        DialogFragment currentDialog = (DialogFragment) mFragmentManager.findFragmentByTag(
            TAG_DIALOG);
        if (currentDialog != null) {
            currentDialog.dismissAllowingStateLoss();
        }
        if (newDialog != null) {
            newDialog.show(mFragmentManager, TAG_DIALOG);
        }
    }

    public void setResult(int resultCode, boolean shouldFinish) {
        // TODO: This is incomplete. We need to send RESULT_FIRST_USER, RESULT_OK etc
        //  for relevant use cases. Investigate when to send what result.
        super.setResult(resultCode);
        if (shouldFinish) {
            finish();
        }
    }
}
