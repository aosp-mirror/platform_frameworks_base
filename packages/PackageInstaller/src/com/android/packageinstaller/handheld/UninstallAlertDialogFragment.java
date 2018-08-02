/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.packageinstaller.handheld;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserManager;

import com.android.packageinstaller.R;
import com.android.packageinstaller.UninstallerActivity;

public class UninstallAlertDialogFragment extends DialogFragment implements
        DialogInterface.OnClickListener {

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final PackageManager pm = getActivity().getPackageManager();
        final UninstallerActivity.DialogInfo dialogInfo =
                ((UninstallerActivity) getActivity()).getDialogInfo();
        final CharSequence appLabel = dialogInfo.appInfo.loadSafeLabel(pm);
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
        StringBuilder messageBuilder = new StringBuilder();

        // If the Activity label differs from the App label, then make sure the user
        // knows the Activity belongs to the App being uninstalled.
        if (dialogInfo.activityInfo != null) {
            final CharSequence activityLabel = dialogInfo.activityInfo.loadSafeLabel(pm);
            if (!activityLabel.equals(appLabel)) {
                messageBuilder.append(
                        getString(R.string.uninstall_activity_text, activityLabel));
                messageBuilder.append(" ").append(appLabel).append(".\n\n");
            }
        }

        final boolean isUpdate =
                ((dialogInfo.appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
        UserManager userManager = UserManager.get(getActivity());
        if (isUpdate) {
            if (isSingleUser(userManager)) {
                messageBuilder.append(getString(R.string.uninstall_update_text));
            } else {
                messageBuilder.append(getString(R.string.uninstall_update_text_multiuser));
            }
        } else {
            if (dialogInfo.allUsers && !isSingleUser(userManager)) {
                messageBuilder.append(getString(R.string.uninstall_application_text_all_users));
            } else if (!dialogInfo.user.equals(android.os.Process.myUserHandle())) {
                UserInfo userInfo = userManager.getUserInfo(dialogInfo.user.getIdentifier());
                messageBuilder.append(
                        getString(R.string.uninstall_application_text_user, userInfo.name));
            } else {
                messageBuilder.append(getString(R.string.uninstall_application_text));
            }
        }

        dialogBuilder.setTitle(appLabel);
        dialogBuilder.setPositiveButton(android.R.string.ok, this);
        dialogBuilder.setNegativeButton(android.R.string.cancel, this);
        dialogBuilder.setMessage(messageBuilder.toString());
        return dialogBuilder.create();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == Dialog.BUTTON_POSITIVE) {
            ((UninstallerActivity) getActivity()).startUninstallProgress();
        } else {
            ((UninstallerActivity) getActivity()).dispatchAborted();
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        if (isAdded()) {
            getActivity().finish();
        }
    }

    /**
     * Returns whether there is only one user on this device, not including
     * the system-only user.
     */
    private boolean isSingleUser(UserManager userManager) {
        final int userCount = userManager.getUserCount();
        return userCount == 1
                || (UserManager.isSplitSystemUser() && userCount == 2);
    }
}
