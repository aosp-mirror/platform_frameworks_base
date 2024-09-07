/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;
import static android.os.UserManager.isHeadlessSystemUserMode;
import static android.os.UserManager.isVisibleBackgroundUsersEnabled;

import android.annotation.NonNull;
import android.annotation.UiThread;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages warning dialogs shown during application lifecycle.
 */
class AppWarnings {
    private static final String TAG = "AppWarnings";
    private static final String CONFIG_FILE_NAME = "packages-warnings.xml";

    public static final int FLAG_HIDE_DISPLAY_SIZE = 0x01;
    public static final int FLAG_HIDE_COMPILE_SDK = 0x02;
    public static final int FLAG_HIDE_DEPRECATED_SDK = 0x04;
    public static final int FLAG_HIDE_DEPRECATED_ABI = 0x08;

    /**
     * Map of package flags for each user.
     * Key: {@literal Pair<userId, packageName>}
     * Value: Flags
     */
    @GuardedBy("mPackageFlags")
    private final ArrayMap<Pair<Integer, String>, Integer> mPackageFlags = new ArrayMap<>();

    private final ActivityTaskManagerService mAtm;
    private final WriteConfigTask mWriteConfigTask;
    private final UiHandler mUiHandler;
    private final AtomicFile mConfigFile;

    private UserManagerInternal mUserManagerInternal;

    /**
     * Maps of app warning dialogs for each user.
     * Key: userId
     * Value: The warning dialog for specific user
     */
    private SparseArray<UnsupportedDisplaySizeDialog> mUnsupportedDisplaySizeDialogs;
    private SparseArray<UnsupportedCompileSdkDialog> mUnsupportedCompileSdkDialogs;
    private SparseArray<DeprecatedTargetSdkVersionDialog> mDeprecatedTargetSdkVersionDialogs;
    private SparseArray<DeprecatedAbiDialog> mDeprecatedAbiDialogs;

    /** @see android.app.ActivityManager#alwaysShowUnsupportedCompileSdkWarning */
    private final ArraySet<ComponentName> mAlwaysShowUnsupportedCompileSdkWarningActivities =
            new ArraySet<>();

    /** @see android.app.ActivityManager#alwaysShowUnsupportedCompileSdkWarning */
    void alwaysShowUnsupportedCompileSdkWarning(ComponentName activity) {
        mAlwaysShowUnsupportedCompileSdkWarningActivities.add(activity);
    }

    /** Creates a new warning dialog manager. */
    public AppWarnings(ActivityTaskManagerService atm, Context uiContext, Handler handler,
            Handler uiHandler, File systemDir) {
        mAtm = atm;
        mWriteConfigTask = new WriteConfigTask();
        mUiHandler = new UiHandler(uiHandler.getLooper());
        mConfigFile = new AtomicFile(new File(systemDir, CONFIG_FILE_NAME), "warnings-config");
    }

    /**
     * Called when ActivityManagerService receives its systemReady call during boot.
     */
    void onSystemReady() {
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        readConfigFromFileAmsThread();

        if (!isVisibleBackgroundUsersEnabled()) {
            return;
        }

        mUserManagerInternal.addUserLifecycleListener(
                new UserManagerInternal.UserLifecycleListener() {
                    @Override
                    public void onUserRemoved(UserInfo user) {
                        // Ignore profile user.
                        if (!user.isFull()) {
                            return;
                        }
                        // Dismiss all warnings and clear all package flags for the user.
                        mUiHandler.hideDialogsForPackage(/* name= */ null, user.id);
                        clearAllPackageFlagsForUser(user.id);
                    }
                });
    }

    /**
     * Shows the "unsupported display size" warning, if necessary.
     *
     * @param r activity record for which the warning may be displayed
     */
    public void showUnsupportedDisplaySizeDialogIfNeeded(ActivityRecord r) {
        final DisplayContent dc = r.getDisplayContent();
        final Configuration config = dc == null
                ? mAtm.getGlobalConfiguration() : dc.getConfiguration();
        if (config.densityDpi != DisplayMetrics.DENSITY_DEVICE_STABLE
                && r.info.applicationInfo.requiresSmallestWidthDp
                > config.smallestScreenWidthDp) {
            mUiHandler.showUnsupportedDisplaySizeDialog(r);
        }
    }

    /**
     * Shows the "unsupported compile SDK" warning, if necessary.
     *
     * @param r activity record for which the warning may be displayed
     */
    public void showUnsupportedCompileSdkDialogIfNeeded(ActivityRecord r) {
        if (r.info.applicationInfo.compileSdkVersion == 0
                || r.info.applicationInfo.compileSdkVersionCodename == null) {
            // We don't know enough about this package. Abort!
            return;
        }

        // TODO(b/75318890): Need to move this to when the app actually crashes.
        if (/*ActivityManager.isRunningInTestHarness()
                &&*/ !mAlwaysShowUnsupportedCompileSdkWarningActivities.contains(
                        r.mActivityComponent)) {
            // Don't show warning if we are running in a test harness and we don't have to always
            // show for this activity.
            return;
        }

        // If the application was built against an pre-release SDK that's older than the current
        // platform OR if the current platform is pre-release and older than the SDK against which
        // the application was built OR both are pre-release with the same SDK_INT but different
        // codenames (e.g. simultaneous pre-release development), then we're likely to run into
        // compatibility issues. Warn the user and offer to check for an update.
        final int compileSdk = r.info.applicationInfo.compileSdkVersion;
        final int platformSdk = Build.VERSION.SDK_INT;
        final boolean isCompileSdkPreview =
                !"REL".equals(r.info.applicationInfo.compileSdkVersionCodename);
        final boolean isPlatformSdkPreview = !"REL".equals(Build.VERSION.CODENAME);
        if ((isCompileSdkPreview && compileSdk < platformSdk)
                || (isPlatformSdkPreview && platformSdk < compileSdk)
                || (isCompileSdkPreview && isPlatformSdkPreview && platformSdk == compileSdk
                    && !Build.VERSION.CODENAME.equals(
                            r.info.applicationInfo.compileSdkVersionCodename))) {
            mUiHandler.showUnsupportedCompileSdkDialog(r);
        }
    }

    /**
     * Shows the "deprecated target sdk" warning, if necessary.
     *
     * @param r activity record for which the warning may be displayed
     */
    public void showDeprecatedTargetDialogIfNeeded(ActivityRecord r) {
        // The warning dialog can be disabled for debugging or testing purposes
        final boolean disableDeprecatedTargetSdkDialog = SystemProperties.getBoolean(
                "debug.wm.disable_deprecated_target_sdk_dialog", false);
        if (r.info.applicationInfo.targetSdkVersion < Build.VERSION.MIN_SUPPORTED_TARGET_SDK_INT
                && !disableDeprecatedTargetSdkDialog) {
            mUiHandler.showDeprecatedTargetDialog(r);
        }
    }

    /**
     * Shows the "deprecated abi" warning, if necessary. This can only happen is the device
     * supports both 64-bit and 32-bit ABIs, and the app only contains 32-bit libraries. The app
     * cannot be installed if the device only supports 64-bit ABI while the app contains only 32-bit
     * libraries.
     *
     * @param r activity record for which the warning may be displayed
     */
    public void showDeprecatedAbiDialogIfNeeded(ActivityRecord r) {
        final boolean isUsingAbiOverride = (r.info.applicationInfo.privateFlagsExt
                & ApplicationInfo.PRIVATE_FLAG_EXT_CPU_OVERRIDE) != 0;
        if (isUsingAbiOverride) {
            // The abiOverride flag was specified during installation, which means that if the app
            // is currently running in 32-bit mode, it is intended. Do not show the warning dialog.
            return;
        }
        // The warning dialog can also be disabled for debugging purpose
        final boolean disableDeprecatedAbiDialog = SystemProperties.getBoolean(
                "debug.wm.disable_deprecated_abi_dialog", false);
        if (disableDeprecatedAbiDialog) {
            return;
        }
        final String appPrimaryAbi = r.info.applicationInfo.primaryCpuAbi;
        final String appSecondaryAbi = r.info.applicationInfo.secondaryCpuAbi;
        final boolean appContainsOnly32bitLibraries =
                (appPrimaryAbi != null && appSecondaryAbi == null && !appPrimaryAbi.contains("64"));
        final boolean is64BitDevice =
                ArrayUtils.find(Build.SUPPORTED_ABIS, abi -> abi.contains("64")) != null;
        if (is64BitDevice && appContainsOnly32bitLibraries) {
            mUiHandler.showDeprecatedAbiDialog(r);
        }
    }

    /**
     * Called when an activity is being started.
     *
     * @param r record for the activity being started
     */
    public void onStartActivity(ActivityRecord r) {
        showUnsupportedCompileSdkDialogIfNeeded(r);
        showUnsupportedDisplaySizeDialogIfNeeded(r);
        showDeprecatedTargetDialogIfNeeded(r);
        showDeprecatedAbiDialogIfNeeded(r);
    }

    /**
     * Called when an activity was previously started and is being resumed.
     *
     * @param r record for the activity being resumed
     */
    public void onResumeActivity(ActivityRecord r) {
        showUnsupportedDisplaySizeDialogIfNeeded(r);
    }

    /**
     * Called by ActivityManagerService when package data has been cleared.
     *
     * @param name the package whose data has been cleared
     * @param userId the user where the package resides.
     */
    public void onPackageDataCleared(String name, int userId) {
        removePackageAndHideDialogs(name, userId);
    }

    /**
     * Called by ActivityManagerService when a package has been uninstalled.
     *
     * @param name the package that has been uninstalled
     * @param userId the user where the package resides.
     */
    public void onPackageUninstalled(String name, int userId) {
        removePackageAndHideDialogs(name, userId);
    }

    /**
     * Called by ActivityManagerService when the default display density has changed.
     */
    public void onDensityChanged() {
        mUiHandler.hideUnsupportedDisplaySizeDialog();
    }

    /**
     * Does what it says on the tin.
     */
    private void removePackageAndHideDialogs(String name, int userId) {
        // Per-user AppWarnings only affects the behavior of the devices that enable the visible
        // background users.
        // To preserve existing behavior of the other devices, handle AppWarnings as a system user
        // regardless of the actual user.
        if (!isVisibleBackgroundUsersEnabled()) {
            userId = USER_SYSTEM;
        } else {
            // If the userId is of a profile, use the parent user ID,
            // since the warning dialogs and the flags for a package are handled per profile group.
            userId = mUserManagerInternal.getProfileParentId(userId);
        }

        mUiHandler.hideDialogsForPackage(name, userId);

        synchronized (mPackageFlags) {
            final Pair<Integer, String> packageKey = Pair.create(userId, name);
            if (mPackageFlags.remove(packageKey) != null) {
                mWriteConfigTask.schedule();
            }
        }
    }

    /**
     * Hides the "unsupported display size" warning.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     */
    @UiThread
    private void hideUnsupportedDisplaySizeDialogUiThread() {
        if (mUnsupportedDisplaySizeDialogs == null) {
            return;
        }

        for (int i = 0; i < mUnsupportedDisplaySizeDialogs.size(); i++) {
            mUnsupportedDisplaySizeDialogs.valueAt(i).dismiss();
        }
        mUnsupportedDisplaySizeDialogs.clear();
    }

    /**
     * Shows the "unsupported display size" warning for the given application.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     *
     * @param ar record for the activity that triggered the warning
     */
    @UiThread
    private void showUnsupportedDisplaySizeDialogUiThread(@NonNull ActivityRecord ar) {
        final int userId = getUserIdForActivity(ar);
        UnsupportedDisplaySizeDialog unsupportedDisplaySizeDialog;
        if (mUnsupportedDisplaySizeDialogs != null) {
            unsupportedDisplaySizeDialog = mUnsupportedDisplaySizeDialogs.get(userId);
            if (unsupportedDisplaySizeDialog != null) {
                unsupportedDisplaySizeDialog.dismiss();
                mUnsupportedDisplaySizeDialogs.remove(userId);
            }
        }
        if (!hasPackageFlag(userId, ar.packageName, FLAG_HIDE_DISPLAY_SIZE)) {
            unsupportedDisplaySizeDialog = new UnsupportedDisplaySizeDialog(
                    AppWarnings.this, getUiContextForActivity(ar), ar.info.applicationInfo, userId);
            unsupportedDisplaySizeDialog.show();
            if (mUnsupportedDisplaySizeDialogs == null) {
                mUnsupportedDisplaySizeDialogs = new SparseArray<>();
            }
            mUnsupportedDisplaySizeDialogs.put(userId, unsupportedDisplaySizeDialog);
        }
    }

    /**
     * Shows the "unsupported compile SDK" warning for the given application.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     *
     * @param ar record for the activity that triggered the warning
     */
    @UiThread
    private void showUnsupportedCompileSdkDialogUiThread(@NonNull ActivityRecord ar) {
        final int userId = getUserIdForActivity(ar);
        UnsupportedCompileSdkDialog unsupportedCompileSdkDialog;
        if (mUnsupportedCompileSdkDialogs != null) {
            unsupportedCompileSdkDialog = mUnsupportedCompileSdkDialogs.get(userId);
            if (unsupportedCompileSdkDialog != null) {
                unsupportedCompileSdkDialog.dismiss();
                mUnsupportedCompileSdkDialogs.remove(userId);
            }
        }
        if (!hasPackageFlag(userId, ar.packageName, FLAG_HIDE_COMPILE_SDK)) {
            unsupportedCompileSdkDialog = new UnsupportedCompileSdkDialog(
                    AppWarnings.this, getUiContextForActivity(ar), ar.info.applicationInfo, userId);
            unsupportedCompileSdkDialog.show();
            if (mUnsupportedCompileSdkDialogs == null) {
                mUnsupportedCompileSdkDialogs = new SparseArray<>();
            }
            mUnsupportedCompileSdkDialogs.put(userId, unsupportedCompileSdkDialog);
        }
    }

    /**
     * Shows the "deprecated target sdk version" warning for the given application.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     *
     * @param ar record for the activity that triggered the warning
     */
    @UiThread
    private void showDeprecatedTargetSdkDialogUiThread(@NonNull ActivityRecord ar) {
        final int userId = getUserIdForActivity(ar);
        DeprecatedTargetSdkVersionDialog deprecatedTargetSdkVersionDialog;
        if (mDeprecatedTargetSdkVersionDialogs != null) {
            deprecatedTargetSdkVersionDialog = mDeprecatedTargetSdkVersionDialogs.get(userId);
            if (deprecatedTargetSdkVersionDialog != null) {
                deprecatedTargetSdkVersionDialog.dismiss();
                mDeprecatedTargetSdkVersionDialogs.remove(userId);
            }
        }
        if (!hasPackageFlag(userId, ar.packageName, FLAG_HIDE_DEPRECATED_SDK)) {
            deprecatedTargetSdkVersionDialog = new DeprecatedTargetSdkVersionDialog(
                    AppWarnings.this, getUiContextForActivity(ar), ar.info.applicationInfo, userId);
            deprecatedTargetSdkVersionDialog.show();
            if (mDeprecatedTargetSdkVersionDialogs == null) {
                mDeprecatedTargetSdkVersionDialogs = new SparseArray<>();
            }
            mDeprecatedTargetSdkVersionDialogs.put(userId, deprecatedTargetSdkVersionDialog);
        }
    }

    /**
     * Shows the "deprecated abi" warning for the given application.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     *
     * @param ar record for the activity that triggered the warning
     */
    @UiThread
    private void showDeprecatedAbiDialogUiThread(@NonNull ActivityRecord ar) {
        final int userId = getUserIdForActivity(ar);
        DeprecatedAbiDialog deprecatedAbiDialog;
        if (mDeprecatedAbiDialogs != null) {
            deprecatedAbiDialog = mDeprecatedAbiDialogs.get(userId);
            if (deprecatedAbiDialog != null) {
                deprecatedAbiDialog.dismiss();
                mDeprecatedAbiDialogs.remove(userId);
            }
        }
        if (!hasPackageFlag(userId, ar.packageName, FLAG_HIDE_DEPRECATED_ABI)) {
            deprecatedAbiDialog = new DeprecatedAbiDialog(
                    AppWarnings.this, getUiContextForActivity(ar), ar.info.applicationInfo, userId);
            deprecatedAbiDialog.show();
            if (mDeprecatedAbiDialogs == null) {
                mDeprecatedAbiDialogs = new SparseArray<>();
            }
            mDeprecatedAbiDialogs.put(userId, deprecatedAbiDialog);
        }
    }

    /**
     * Dismisses all warnings for the given package.
     * <p>
     * <strong>Note:</strong> Must be called on the UI thread.
     *
     * @param name the package for which warnings should be dismissed, or {@code null} to dismiss
     *             all warnings
     * @param userId the user where the package resides.
     */
    @UiThread
    private void hideDialogsForPackageUiThread(String name, int userId) {
        // Hides the "unsupported display" dialog if necessary.
        if (mUnsupportedDisplaySizeDialogs != null) {
            UnsupportedDisplaySizeDialog unsupportedDisplaySizeDialog =
                    mUnsupportedDisplaySizeDialogs.get(userId);
            if (unsupportedDisplaySizeDialog != null && (name == null || name.equals(
                    unsupportedDisplaySizeDialog.mPackageName))) {
                unsupportedDisplaySizeDialog.dismiss();
                mUnsupportedDisplaySizeDialogs.remove(userId);
            }
        }

        // Hides the "unsupported compile SDK" dialog if necessary.
        if (mUnsupportedCompileSdkDialogs != null) {
            UnsupportedCompileSdkDialog unsupportedCompileSdkDialog =
                    mUnsupportedCompileSdkDialogs.get(userId);
            if (unsupportedCompileSdkDialog != null && (name == null || name.equals(
                    unsupportedCompileSdkDialog.mPackageName))) {
                unsupportedCompileSdkDialog.dismiss();
                mUnsupportedCompileSdkDialogs.remove(userId);
            }
        }

        // Hides the "deprecated target sdk version" dialog if necessary.
        if (mDeprecatedTargetSdkVersionDialogs != null) {
            DeprecatedTargetSdkVersionDialog deprecatedTargetSdkVersionDialog =
                    mDeprecatedTargetSdkVersionDialogs.get(userId);
            if (deprecatedTargetSdkVersionDialog != null && (name == null || name.equals(
                    deprecatedTargetSdkVersionDialog.mPackageName))) {
                deprecatedTargetSdkVersionDialog.dismiss();
                mDeprecatedTargetSdkVersionDialogs.remove(userId);
            }
        }

        // Hides the "deprecated abi" dialog if necessary.
        if (mDeprecatedAbiDialogs != null) {
            DeprecatedAbiDialog deprecatedAbiDialog = mDeprecatedAbiDialogs.get(userId);
            if (deprecatedAbiDialog != null && (name == null || name.equals(
                    deprecatedAbiDialog.mPackageName))) {
                deprecatedAbiDialog.dismiss();
                mDeprecatedAbiDialogs.remove(userId);
            }
        }
    }

    /**
     * Returns the value of the flag for the given package.
     *
     * @param userId the user where the package resides.
     * @param name the package from which to retrieve the flag
     * @param flag the bitmask for the flag to retrieve
     * @return {@code true} if the flag is enabled, {@code false} otherwise
     */
    boolean hasPackageFlag(int userId, String name, int flag) {
        return (getPackageFlags(userId, name) & flag) == flag;
    }

    /**
     * Sets the flag for the given package to the specified value.
     *
     * @param userId the user where the package resides.
     * @param name the package on which to set the flag
     * @param flag the bitmask for flag to set
     * @param enabled the value to set for the flag
     */
    void setPackageFlag(int userId, String name, int flag, boolean enabled) {
        synchronized (mPackageFlags) {
            final int curFlags = getPackageFlags(userId, name);
            final int newFlags = enabled ? (curFlags | flag) : (curFlags & ~flag);
            if (curFlags != newFlags) {
                final Pair<Integer, String> packageKey = Pair.create(userId, name);
                if (newFlags != 0) {
                    mPackageFlags.put(packageKey, newFlags);
                } else {
                    mPackageFlags.remove(packageKey);
                }
                mWriteConfigTask.schedule();
            }
        }
    }

    /**
     * Returns the bitmask of flags set for the specified package.
     */
    private int getPackageFlags(int userId, String packageName) {
        synchronized (mPackageFlags) {
            final Pair<Integer, String> packageKey = Pair.create(userId, packageName);
            return mPackageFlags.getOrDefault(packageKey, 0);
        }
    }

    /**
     * Clear all the package flags for given user.
     */
    private void clearAllPackageFlagsForUser(int userId) {
        synchronized (mPackageFlags) {
            boolean hasPackageFlagsForUser = false;
            for (int i = mPackageFlags.size() - 1; i >= 0; i--) {
                Pair<Integer, String> key = mPackageFlags.keyAt(i);
                if (key.first == userId) {
                    hasPackageFlagsForUser = true;
                    mPackageFlags.remove(key);
                }
            }

            if (hasPackageFlagsForUser) {
                mWriteConfigTask.schedule();
            }
        }
    }

    /**
     * Returns the user ID for handling AppWarnings per user.
     * Per-user AppWarnings only affects the behavior of the devices that enable
     * the visible background users.
     * If the device doesn't enable visible background users, it will return the system user ID
     * for handling AppWarnings as a system user regardless of the actual user
     * to preserve existing behavior of the device.
     * Otherwise, it will return the main user (i.e., not a profile) that is assigned to the display
     * where the activity is launched.
     */
    private @UserIdInt int getUserIdForActivity(@NonNull ActivityRecord ar) {
        if (!isVisibleBackgroundUsersEnabled()) {
            return USER_SYSTEM;
        }

        if (ar.mUserId == USER_SYSTEM) {
            return getUserAssignedToDisplay(ar.mDisplayContent.getDisplayId());
        }

        return mUserManagerInternal.getProfileParentId(ar.mUserId);
    }

    /**
     * Returns the UI context for handling AppWarnings per user.
     * Per-user AppWarnings only affects the behavior of the devices that enable
     * the visible background users.
     * If the device enables the visible background users, it will return the UI context associated
     * with the assigned user and the display where the activity is launched.
     * If the HSUM device doesn't enable the visible background users, it will return the UI context
     * associated with the current user and the default display.
     * Otherwise, it will return the UI context associated with the system user and the default
     * display.
     */
    private Context getUiContextForActivity(@NonNull ActivityRecord ar) {
        if (!isVisibleBackgroundUsersEnabled()) {
            if (!isHeadlessSystemUserMode()) {
                return mAtm.getUiContext();
            }

            Context uiContextForCurrentUser = mAtm.getUiContext().createContextAsUser(
                    new UserHandle(mAtm.getCurrentUserId()), /* flags= */ 0);
            return uiContextForCurrentUser;
        }

        DisplayContent dc = ar.mDisplayContent;
        Context systemUiContext = dc.getDisplayPolicy().getSystemUiContext();
        int assignedUser = getUserAssignedToDisplay(dc.getDisplayId());
        Context uiContextForUser = systemUiContext.createContextAsUser(
                new UserHandle(assignedUser), /* flags= */ 0);
        return uiContextForUser;
    }

    /**
     * Returns the main user that is assigned to the display.
     *
     * See {@link UserManagerInternal#getUserAssignedToDisplay(int)}.
     */
    private @UserIdInt int getUserAssignedToDisplay(int displayId) {
        return mUserManagerInternal.getUserAssignedToDisplay(displayId);
    }

    /**
     * Handles messages on the system process UI thread.
     */
    private final class UiHandler extends Handler {
        private static final int MSG_SHOW_UNSUPPORTED_DISPLAY_SIZE_DIALOG = 1;
        private static final int MSG_HIDE_UNSUPPORTED_DISPLAY_SIZE_DIALOG = 2;
        private static final int MSG_SHOW_UNSUPPORTED_COMPILE_SDK_DIALOG = 3;
        private static final int MSG_HIDE_DIALOGS_FOR_PACKAGE = 4;
        private static final int MSG_SHOW_DEPRECATED_TARGET_SDK_DIALOG = 5;
        private static final int MSG_SHOW_DEPRECATED_ABI_DIALOG = 6;

        public UiHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SHOW_UNSUPPORTED_DISPLAY_SIZE_DIALOG: {
                    final ActivityRecord ar = (ActivityRecord) msg.obj;
                    showUnsupportedDisplaySizeDialogUiThread(ar);
                } break;
                case MSG_HIDE_UNSUPPORTED_DISPLAY_SIZE_DIALOG: {
                    hideUnsupportedDisplaySizeDialogUiThread();
                } break;
                case MSG_SHOW_UNSUPPORTED_COMPILE_SDK_DIALOG: {
                    final ActivityRecord ar = (ActivityRecord) msg.obj;
                    showUnsupportedCompileSdkDialogUiThread(ar);
                } break;
                case MSG_HIDE_DIALOGS_FOR_PACKAGE: {
                    final String name = (String) msg.obj;
                    final int userId = (int) msg.arg1;
                    hideDialogsForPackageUiThread(name, userId);
                } break;
                case MSG_SHOW_DEPRECATED_TARGET_SDK_DIALOG: {
                    final ActivityRecord ar = (ActivityRecord) msg.obj;
                    showDeprecatedTargetSdkDialogUiThread(ar);
                } break;
                case MSG_SHOW_DEPRECATED_ABI_DIALOG: {
                    final ActivityRecord ar = (ActivityRecord) msg.obj;
                    showDeprecatedAbiDialogUiThread(ar);
                } break;
            }
        }

        public void showUnsupportedDisplaySizeDialog(ActivityRecord r) {
            removeMessages(MSG_SHOW_UNSUPPORTED_DISPLAY_SIZE_DIALOG);
            obtainMessage(MSG_SHOW_UNSUPPORTED_DISPLAY_SIZE_DIALOG, r).sendToTarget();
        }

        public void hideUnsupportedDisplaySizeDialog() {
            removeMessages(MSG_HIDE_UNSUPPORTED_DISPLAY_SIZE_DIALOG);
            sendEmptyMessage(MSG_HIDE_UNSUPPORTED_DISPLAY_SIZE_DIALOG);
        }

        public void showUnsupportedCompileSdkDialog(ActivityRecord r) {
            removeMessages(MSG_SHOW_UNSUPPORTED_COMPILE_SDK_DIALOG);
            obtainMessage(MSG_SHOW_UNSUPPORTED_COMPILE_SDK_DIALOG, r).sendToTarget();
        }

        public void showDeprecatedTargetDialog(ActivityRecord r) {
            removeMessages(MSG_SHOW_DEPRECATED_TARGET_SDK_DIALOG);
            obtainMessage(MSG_SHOW_DEPRECATED_TARGET_SDK_DIALOG, r).sendToTarget();
        }

        public void showDeprecatedAbiDialog(ActivityRecord r) {
            removeMessages(MSG_SHOW_DEPRECATED_ABI_DIALOG);
            obtainMessage(MSG_SHOW_DEPRECATED_ABI_DIALOG, r).sendToTarget();
        }

        public void hideDialogsForPackage(String name, int userId) {
            obtainMessage(MSG_HIDE_DIALOGS_FOR_PACKAGE, userId, 0, name).sendToTarget();
        }
    }

    static class BaseDialog {
        final AppWarnings mManager;
        final Context mUiContext;
        final String mPackageName;
        final int mUserId;
        AlertDialog mDialog;
        private BroadcastReceiver mCloseReceiver;

        BaseDialog(AppWarnings manager, Context uiContext, String packageName, int userId) {
            mManager = manager;
            mUiContext = uiContext;
            mPackageName = packageName;
            mUserId = userId;
        }

        @UiThread
        void show() {
            if (mDialog == null) return;
            if (mCloseReceiver == null) {
                mCloseReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                            mManager.mUiHandler.hideDialogsForPackage(mPackageName, mUserId);
                        }
                    }
                };
                mUiContext.registerReceiver(mCloseReceiver,
                        new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                        Context.RECEIVER_EXPORTED);
            }
            Slog.w(TAG, "Showing " + getClass().getSimpleName() + " for package " + mPackageName);
            mDialog.show();
        }

        @UiThread
        void dismiss() {
            if (mDialog == null) return;
            if (mCloseReceiver != null) {
                mUiContext.unregisterReceiver(mCloseReceiver);
                mCloseReceiver = null;
            }
            mDialog.dismiss();
            mDialog = null;
        }
    }

    private final class WriteConfigTask implements Runnable {
        private static final long WRITE_CONFIG_DELAY_MS = 10000;
        final AtomicReference<ArrayMap<Pair<Integer, String>, Integer>> mPendingPackageFlags =
                new AtomicReference<>();

        @Override
        public void run() {
            final ArrayMap<Pair<Integer, String>, Integer> packageFlags =
                    mPendingPackageFlags.getAndSet(null);
            if (packageFlags != null) {
                writeConfigToFile(packageFlags);
            }
        }

        @GuardedBy("mPackageFlags")
        void schedule() {
            if (mPendingPackageFlags.getAndSet(new ArrayMap<>(mPackageFlags)) == null) {
                IoThread.getHandler().postDelayed(this, WRITE_CONFIG_DELAY_MS);
            }
        }
    }

    /** Writes the configuration file. */
    @WorkerThread
    private void writeConfigToFile(@NonNull ArrayMap<Pair<Integer, String>, Integer> packageFlags) {
        FileOutputStream fos = null;
        try {
            fos = mConfigFile.startWrite();

            final TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            out.startTag(null, "packages");

            for (int i = 0; i < packageFlags.size(); i++) {
                final Pair<Integer, String> key = packageFlags.keyAt(i);
                final int userId = key.first;
                final String packageName = key.second;
                final int mode = packageFlags.valueAt(i);
                if (mode == 0) {
                    continue;
                }
                out.startTag(null, "package");
                out.attributeInt(null, "user", userId);
                out.attribute(null, "name", packageName);
                out.attributeInt(null, "flags", mode);
                out.endTag(null, "package");
            }

            out.endTag(null, "packages");
            out.endDocument();

            mConfigFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Slog.w(TAG, "Error writing package metadata", e1);
            if (fos != null) {
                mConfigFile.failWrite(fos);
            }
        }
    }

    /**
     * Reads the configuration file and populates the package flags.
     * <p>
     * <strong>Note:</strong> Must be called from #onSystemReady() (and thus on the
     * ActivityManagerService thread) since we don't synchronize on config.
     */
    private void readConfigFromFileAmsThread() {
        FileInputStream fis = null;

        try {
            fis = mConfigFile.openRead();

            final TypedXmlPullParser parser = Xml.resolvePullParser(fis);

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            if (eventType == XmlPullParser.END_DOCUMENT) {
                return;
            }

            String tagName = parser.getName();
            if ("packages".equals(tagName)) {
                eventType = parser.next();
                boolean writeConfigToFileNeeded = false;
                do {
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        if (parser.getDepth() == 2) {
                            if ("package".equals(tagName)) {
                                final int userId = parser.getAttributeInt(
                                        null, "user", USER_NULL);
                                final String name = parser.getAttributeValue(null, "name");
                                if (name != null) {
                                    int flagsInt = parser.getAttributeInt(null, "flags", 0);
                                    if (userId != USER_NULL) {
                                        final Pair<Integer, String> packageKey =
                                                Pair.create(userId, name);
                                        mPackageFlags.put(packageKey, flagsInt);
                                    } else {
                                        // This is for compatibility with existing configuration
                                        // file written from legacy logic(pre-V) which does not have
                                        // the flags per-user. (b/296334639)
                                        writeConfigToFileNeeded = true;
                                        if (!isVisibleBackgroundUsersEnabled()) {
                                            // To preserve existing behavior of the devices that
                                            // doesn't enable visible background users, populate
                                            // the flags for a package as the system user.
                                            final Pair<Integer, String> packageKey =
                                                    Pair.create(USER_SYSTEM, name);
                                            mPackageFlags.put(packageKey, flagsInt);
                                        } else {
                                            // To manage the flags per user in the device that
                                            // enable visible background users, populate the flags
                                            // for all existing non-profile human user.
                                            UserInfo[] users = mUserManagerInternal.getUserInfos();
                                            for (UserInfo userInfo : users) {
                                                if (!userInfo.isFull()) {
                                                    continue;
                                                }
                                                final Pair<Integer, String> packageKey =
                                                        Pair.create(userInfo.id, name);
                                                mPackageFlags.put(packageKey, flagsInt);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);

                if (writeConfigToFileNeeded) {
                    mWriteConfigTask.schedule();
                }
            }
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Error reading package metadata", e);
        } catch (java.io.IOException e) {
            if (fis != null) Slog.w(TAG, "Error reading package metadata", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (java.io.IOException e1) {
                }
            }
        }
    }
}
