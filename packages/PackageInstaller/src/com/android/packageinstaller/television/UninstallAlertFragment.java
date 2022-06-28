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

package com.android.packageinstaller.television;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.leanback.app.GuidedStepFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import com.android.packageinstaller.R;
import com.android.packageinstaller.UninstallerActivity;

import java.util.List;

public class UninstallAlertFragment extends GuidedStepFragment {
    @Override
    public int onProvideTheme() {
        return R.style.Theme_Leanback_GuidedStep;
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        final PackageManager pm = getActivity().getPackageManager();
        final UninstallerActivity.DialogInfo dialogInfo =
                ((UninstallerActivity) getActivity()).getDialogInfo();
        final CharSequence appLabel = dialogInfo.appInfo.loadSafeLabel(pm);

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
        final UserHandle myUserHandle = Process.myUserHandle();
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
            } else if (!dialogInfo.user.equals(myUserHandle)) {
                UserInfo userInfo = userManager.getUserInfo(dialogInfo.user.getIdentifier());
                if (userInfo.isManagedProfile()
                        && userInfo.profileGroupId == myUserHandle.getIdentifier()) {
                    messageBuilder.append(
                            getString(R.string.uninstall_application_text_current_user_work_profile,
                                    userInfo.name));
                } else {
                    messageBuilder.append(
                            getString(R.string.uninstall_application_text_user, userInfo.name));
                }
            } else {
                messageBuilder.append(getString(R.string.uninstall_application_text));
            }
        }

        return new GuidanceStylist.Guidance(
                appLabel.toString(),
                messageBuilder.toString(),
                null,
                dialogInfo.appInfo.loadIcon(pm));
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(getContext())
                .clickAction(GuidedAction.ACTION_ID_OK)
                .build());
        actions.add(new GuidedAction.Builder(getContext())
                .clickAction(GuidedAction.ACTION_ID_CANCEL)
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (isAdded()) {
            if (action.getId() == GuidedAction.ACTION_ID_OK) {
                ((UninstallerActivity) getActivity()).startUninstallProgress(false);
                getActivity().finish();
            } else {
                ((UninstallerActivity) getActivity()).dispatchAborted();
                getActivity().setResult(Activity.RESULT_FIRST_USER);
                getActivity().finish();
            }
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
