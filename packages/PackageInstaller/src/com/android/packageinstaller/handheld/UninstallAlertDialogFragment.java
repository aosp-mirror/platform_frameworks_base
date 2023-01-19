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

import static android.os.UserManager.USER_TYPE_PROFILE_CLONE;
import static android.os.UserManager.USER_TYPE_PROFILE_MANAGED;
import static android.text.format.Formatter.formatFileSize;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.packageinstaller.R;
import com.android.packageinstaller.UninstallerActivity;

import java.io.IOException;
import java.util.List;

public class UninstallAlertDialogFragment extends DialogFragment implements
        DialogInterface.OnClickListener {
    private static final String LOG_TAG = UninstallAlertDialogFragment.class.getSimpleName();

    private @Nullable CheckBox mKeepData;

    /**
     * Get number of bytes of the app data of the package.
     *
     * @param pkg The package that might have app data.
     * @param user The user the package belongs to
     *
     * @return The number of bytes.
     */
    private long getAppDataSizeForUser(@NonNull String pkg, @NonNull UserHandle user) {
        StorageStatsManager storageStatsManager =
                getContext().getSystemService(StorageStatsManager.class);
        try {
            StorageStats stats = storageStatsManager.queryStatsForPackage(
                    getContext().getPackageManager().getApplicationInfo(pkg, 0).storageUuid,
                    pkg, user);
            return stats.getDataBytes();
        } catch (PackageManager.NameNotFoundException | IOException | SecurityException e) {
            Log.e(LOG_TAG, "Cannot determine amount of app data for " + pkg, e);
        }

        return 0;
    }

    /**
     * Get number of bytes of the app data of the package.
     *
     * @param pkg The package that might have app data.
     * @param user The user the package belongs to or {@code null} if files of all users should be
     *             counted.
     *
     * @return The number of bytes.
     */
    private long getAppDataSize(@NonNull String pkg, @Nullable UserHandle user) {
        UserManager userManager = getContext().getSystemService(UserManager.class);

        long appDataSize = 0;

        if (user == null) {
            List<UserHandle> userHandles = userManager.getUserHandles(true);

            int numUsers = userHandles.size();
            for (int i = 0; i < numUsers; i++) {
                appDataSize += getAppDataSizeForUser(pkg, userHandles.get(i));
            }
        } else {
            appDataSize = getAppDataSizeForUser(pkg, user);
        }

        return appDataSize;
    }

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
        boolean isClonedApp = false;

        final boolean isUpdate =
                ((dialogInfo.appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
        final UserHandle myUserHandle = Process.myUserHandle();
        UserManager userManager = getContext().getSystemService(UserManager.class);
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
                int userId = dialogInfo.user.getIdentifier();
                UserManager customUserManager = getContext()
                        .createContextAsUser(UserHandle.of(userId), 0)
                        .getSystemService(UserManager.class);
                String userName = customUserManager.getUserName();

                if (customUserManager.isUserOfType(USER_TYPE_PROFILE_MANAGED)
                        && customUserManager.isSameProfileGroup(dialogInfo.user, myUserHandle)) {
                    messageBuilder.append(
                            getString(R.string.uninstall_application_text_current_user_work_profile,
                                    userName));
                } else if (customUserManager.isUserOfType(USER_TYPE_PROFILE_CLONE)
                        && customUserManager.isSameProfileGroup(dialogInfo.user, myUserHandle)) {
                    isClonedApp = true;
                    messageBuilder.append(getString(
                            R.string.uninstall_application_text_current_user_clone_profile));
                } else {
                    messageBuilder.append(
                            getString(R.string.uninstall_application_text_user, userName));
                }
            } else if (isCloneProfile(myUserHandle)) {
                isClonedApp = true;
                messageBuilder.append(getString(
                        R.string.uninstall_application_text_current_user_clone_profile));
            } else {
                if (Process.myUserHandle().equals(UserHandle.SYSTEM)
                        && hasClonedInstance(dialogInfo.appInfo.packageName)) {
                    messageBuilder.append(getString(
                            R.string.uninstall_application_text_with_clone_instance,
                            appLabel));
                } else {
                    messageBuilder.append(getString(R.string.uninstall_application_text));
                }
            }
        }

        if (isClonedApp) {
            dialogBuilder.setTitle(getString(R.string.cloned_app_label, appLabel));
        } else {
            dialogBuilder.setTitle(appLabel);
        }
        dialogBuilder.setPositiveButton(android.R.string.ok, this);
        dialogBuilder.setNegativeButton(android.R.string.cancel, this);

        String pkg = dialogInfo.appInfo.packageName;

        boolean suggestToKeepAppData;
        try {
            PackageInfo pkgInfo = pm.getPackageInfo(pkg, 0);

            suggestToKeepAppData = pkgInfo.applicationInfo.hasFragileUserData();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Cannot check hasFragileUserData for " + pkg, e);
            suggestToKeepAppData = false;
        }

        long appDataSize = 0;
        if (suggestToKeepAppData) {
            appDataSize = getAppDataSize(pkg, dialogInfo.allUsers ? null : dialogInfo.user);
        }

        if (appDataSize == 0) {
            dialogBuilder.setMessage(messageBuilder.toString());
        } else {
            LayoutInflater inflater = getContext().getSystemService(LayoutInflater.class);
            ViewGroup content = (ViewGroup) inflater.inflate(R.layout.uninstall_content_view, null);

            ((TextView) content.requireViewById(R.id.message)).setText(messageBuilder.toString());
            mKeepData = content.requireViewById(R.id.keepData);
            mKeepData.setVisibility(View.VISIBLE);
            mKeepData.setText(getString(R.string.uninstall_keep_data,
                    formatFileSize(getContext(), appDataSize)));

            dialogBuilder.setView(content);
        }

        return dialogBuilder.create();
    }

    private boolean isCloneProfile(UserHandle userHandle) {
        UserManager customUserManager = getContext()
                .createContextAsUser(UserHandle.of(userHandle.getIdentifier()), 0)
                .getSystemService(UserManager.class);
        if (customUserManager.isUserOfType(UserManager.USER_TYPE_PROFILE_CLONE)) {
            return true;
        }
        return false;
    }

    private boolean hasClonedInstance(String packageName) {
        // Check if clone user is present on the device.
        UserHandle cloneUser = null;
        UserManager userManager = getContext().getSystemService(UserManager.class);
        List<UserHandle> profiles = userManager.getUserProfiles();
        for (UserHandle userHandle : profiles) {
            if (!Process.myUserHandle().equals(UserHandle.SYSTEM) && isCloneProfile(userHandle)) {
                cloneUser = userHandle;
                break;
            }
        }

        // Check if another instance of given package exists in clone user profile.
        if (cloneUser != null) {
            try {
                if (getContext().getPackageManager().getPackageUidAsUser(packageName,
                        PackageManager.PackageInfoFlags.of(0), cloneUser.getIdentifier()) > 0) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == Dialog.BUTTON_POSITIVE) {
            ((UninstallerActivity) getActivity()).startUninstallProgress(
                    mKeepData != null && mKeepData.isChecked());
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
     * Returns whether there is only one "full" user on this device.
     *
     * <p><b>Note:</b> on devices that use {@link android.os.UserManager#isHeadlessSystemUserMode()
     * headless system user mode}, the system user is not "full", so it's not be considered in the
     * calculation.
     */
    private boolean isSingleUser(UserManager userManager) {
        final int userCount = userManager.getUserCount();
        return userCount == 1 || (UserManager.isHeadlessSystemUserMode() && userCount == 2);
    }
}
