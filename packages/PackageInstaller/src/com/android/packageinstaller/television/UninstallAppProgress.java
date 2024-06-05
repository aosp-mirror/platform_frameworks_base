/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package com.android.packageinstaller.television;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.widget.Toast;
import androidx.annotation.Nullable;
import com.android.packageinstaller.PackageUtil;
import com.android.packageinstaller.R;
import com.android.packageinstaller.common.EventResultPersister;
import com.android.packageinstaller.common.UninstallEventReceiver;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * This activity corresponds to a download progress screen that is displayed
 * when an application is uninstalled. The result of the application uninstall
 * is indicated in the result code that gets set to 0 or 1. The application gets launched
 * by an intent with the intent's class name explicitly set to UninstallAppProgress and expects
 * the application object of the application to uninstall.
 */
public class UninstallAppProgress extends Activity implements
        EventResultPersister.EventResultObserver {
    private static final String TAG = "UninstallAppProgress";

    private static final String FRAGMENT_TAG = "progress_fragment";
    private static final String BROADCAST_ACTION =
            "com.android.packageinstaller.ACTION_UNINSTALL_COMMIT";

    private ApplicationInfo mAppInfo;
    private boolean mAllUsers;
    private PackageManager.UninstallCompleteCallback mCallback;

    private volatile int mResultCode = -1;

    /**
     * If initView was called. We delay this call to not have to call it at all if the uninstall is
     * quick
     */
    private boolean mIsViewInitialized;

    /** Amount of time to wait until we show the UI */
    private static final int QUICK_INSTALL_DELAY_MILLIS = 500;

    private static final int UNINSTALL_COMPLETE = 1;
    private static final int UNINSTALL_IS_SLOW = 2;

    private Handler mHandler = new MessageHandler(this);

    private static class MessageHandler extends Handler {
        private final WeakReference<UninstallAppProgress> mActivity;

        public MessageHandler(UninstallAppProgress activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            UninstallAppProgress activity = mActivity.get();
            if (activity != null) {
                activity.handleMessage(msg);
            }
        }
    }

    private void handleMessage(Message msg) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        switch (msg.what) {
            case UNINSTALL_IS_SLOW:
                initView();
                break;
            case UNINSTALL_COMPLETE:
                mHandler.removeMessages(UNINSTALL_IS_SLOW);

                if (msg.arg1 != PackageManager.DELETE_SUCCEEDED) {
                    initView();
                }

                mResultCode = msg.arg1;
                final String packageName = (String) msg.obj;

                if (mCallback != null) {
                    mCallback.onUninstallComplete(mAppInfo.packageName, mResultCode, packageName);
                    finish();
                    return;
                }

                if (getIntent().getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)) {
                    Intent result = new Intent();
                    result.putExtra(Intent.EXTRA_INSTALL_RESULT, mResultCode);
                    setResult(mResultCode == PackageManager.DELETE_SUCCEEDED
                            ? Activity.RESULT_OK : Activity.RESULT_FIRST_USER,
                            result);
                    finish();
                    return;
                }

                // Update the status text
                final String statusText;
                Context ctx = getBaseContext();
                switch (msg.arg1) {
                    case PackageManager.DELETE_SUCCEEDED:
                        statusText = getString(R.string.uninstall_done);
                        // Show a Toast and finish the activity
                        Toast.makeText(ctx, statusText, Toast.LENGTH_LONG).show();
                        setResultAndFinish();
                        return;
                    case PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER: {
                        UserManager userManager =
                                (UserManager) getSystemService(Context.USER_SERVICE);
                        // Find out if the package is an active admin for some non-current user.
                        UserHandle myUserHandle =  Process.myUserHandle();
                        UserHandle otherBlockingUserHandle = null;
                        for (UserHandle otherUserHandle : userManager.getUserHandles(true)) {
                            // We only catch the case when the user in question is neither the
                            // current user nor its profile.
                            if (isProfileOfOrSame(userManager, myUserHandle, otherUserHandle)) {
                                continue;
                            }
                            DevicePolicyManager dpm = ctx.createContextAsUser(otherUserHandle, 0)
                                    .getSystemService(DevicePolicyManager.class);
                            if (dpm.packageHasActiveAdmins(packageName)) {
                                otherBlockingUserHandle = otherUserHandle;
                                break;
                            }
                        }
                        if (otherBlockingUserHandle == null) {
                            Log.d(TAG, "Uninstall failed because " + packageName
                                    + " is a device admin");
                            getProgressFragment().setDeviceManagerButtonVisible(true);
                            statusText = getString(
                                    R.string.uninstall_failed_device_policy_manager);
                        } else {
                            Log.d(TAG, "Uninstall failed because " + packageName
                                    + " is a device admin of user " + otherBlockingUserHandle);
                            getProgressFragment().setDeviceManagerButtonVisible(false);
                            String userName = ctx.createContextAsUser(otherBlockingUserHandle, 0)
                                    .getSystemService(UserManager.class).getUserName();
                            statusText = String.format(
                                    getString(R.string.uninstall_failed_device_policy_manager_of_user),
                                    userName);
                        }
                        break;
                    }
                    case PackageManager.DELETE_FAILED_OWNER_BLOCKED: {
                        UserManager userManager =
                                (UserManager) getSystemService(Context.USER_SERVICE);
                        PackageManager packageManager = ctx.getPackageManager();
                        List<UserHandle> userHandles = userManager.getUserHandles(true);
                        UserHandle otherBlockingUserHandle = null;
                        for (int i = 0; i < userHandles.size(); ++i) {
                            final UserHandle handle = userHandles.get(i);
                            if (packageManager.canUserUninstall(packageName, handle)) {
                                otherBlockingUserHandle = handle;
                                break;
                            }
                        }
                        UserHandle myUserHandle = Process.myUserHandle();
                        if (isProfileOfOrSame(userManager, myUserHandle, otherBlockingUserHandle)) {
                            getProgressFragment().setDeviceManagerButtonVisible(true);
                        } else {
                            getProgressFragment().setDeviceManagerButtonVisible(false);
                            getProgressFragment().setUsersButtonVisible(true);
                        }
                        // TODO: b/25442806
                        if (otherBlockingUserHandle == UserHandle.SYSTEM) {
                            statusText = getString(R.string.uninstall_blocked_device_owner);
                        } else if (otherBlockingUserHandle == null) {
                            Log.d(TAG, "Uninstall failed for " + packageName + " with code "
                                    + msg.arg1 + " no blocking user");
                            statusText = getString(R.string.uninstall_failed);
                        } else {
                            statusText = mAllUsers
                                    ? getString(R.string.uninstall_all_blocked_profile_owner) :
                                    getString(R.string.uninstall_blocked_profile_owner);
                        }
                        break;
                    }
                    default:
                        Log.d(TAG, "Uninstall failed for " + packageName + " with code "
                                + msg.arg1);
                        statusText = getString(R.string.uninstall_failed);
                        break;
                }
                getProgressFragment().showCompletion(statusText);
                break;
            default:
                break;
        }
    }

    private boolean isProfileOfOrSame(UserManager userManager, UserHandle userHandle,
            UserHandle profileHandle) {
        if (userHandle.equals(profileHandle)) {
            return true;
        }
        return userManager.getProfileParent(profileHandle) != null
                && userManager.getProfileParent(profileHandle).equals(userHandle);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Intent intent = getIntent();
        mAppInfo = intent.getParcelableExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO);
        mCallback = intent.getParcelableExtra(PackageInstaller.EXTRA_CALLBACK,
                PackageManager.UninstallCompleteCallback.class);

        // This currently does not support going through a onDestroy->onCreate cycle. Hence if that
        // happened, just fail the operation for mysterious reasons.
        if (icicle != null) {
            mResultCode = PackageManager.DELETE_FAILED_INTERNAL_ERROR;

            if (mCallback != null) {
                mCallback.onUninstallComplete(mAppInfo.packageName, mResultCode, null);
                finish();
            } else {
                setResultAndFinish();
            }

            return;
        }

        mAllUsers = intent.getBooleanExtra(Intent.EXTRA_UNINSTALL_ALL_USERS, false);
        UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);
        if (user == null) {
            user = Process.myUserHandle();
        }


        // Make window transparent until initView is called. In many cases we can avoid showing the
        // UI at all as the app is uninstalled very quickly. If we show the UI and instantly remove
        // it, it just looks like a flicker.
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        try {
            int uninstallId = UninstallEventReceiver.addObserver(this,
                    EventResultPersister.GENERATE_NEW_ID, this);

            Intent broadcastIntent = new Intent(BROADCAST_ACTION);
            broadcastIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            broadcastIntent.putExtra(EventResultPersister.EXTRA_ID, uninstallId);
            broadcastIntent.setPackage(getPackageName());

            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, uninstallId,
                    broadcastIntent, PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_MUTABLE);

            createContextAsUser(user, 0).getPackageManager().getPackageInstaller().uninstall(
                    new VersionedPackage(mAppInfo.packageName, PackageManager.VERSION_CODE_HIGHEST),
                    mAllUsers ? PackageManager.DELETE_ALL_USERS : 0,
                    pendingIntent.getIntentSender());
        } catch (IllegalArgumentException e) {
            // Couldn't find the package, no need to call uninstall.
            Log.w(TAG, "Could not find package, not deleting " + mAppInfo.packageName, e);
        } catch (EventResultPersister.OutOfIdsException e) {
            Log.e(TAG, "Fails to start uninstall", e);
            onResult(PackageInstaller.STATUS_FAILURE, PackageManager.DELETE_FAILED_INTERNAL_ERROR,
                    null, 0);
        }

        mHandler.sendMessageDelayed(mHandler.obtainMessage(UNINSTALL_IS_SLOW),
                QUICK_INSTALL_DELAY_MILLIS);
    }

    public ApplicationInfo getAppInfo() {
        return mAppInfo;
    }

    @Override
    public void onResult(int status, int legacyStatus, @Nullable String message, int serviceId) {
        Message msg = mHandler.obtainMessage(UNINSTALL_COMPLETE);
        msg.arg1 = legacyStatus;
        msg.obj = mAppInfo.packageName;
        mHandler.sendMessage(msg);
    }

    public void setResultAndFinish() {
        setResult(mResultCode);
        finish();
    }

    private void initView() {
        if (mIsViewInitialized) {
            return;
        }
        mIsViewInitialized = true;

        // We set the window background to translucent in constructor, revert this
        TypedValue attribute = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.windowBackground, attribute, true);
        if (attribute.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                attribute.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            getWindow().setBackgroundDrawable(new ColorDrawable(attribute.data));
        } else {
            getWindow().setBackgroundDrawable(getResources().getDrawable(attribute.resourceId,
                    getTheme()));
        }

        getTheme().resolveAttribute(android.R.attr.navigationBarColor, attribute, true);
        getWindow().setNavigationBarColor(attribute.data);

        getTheme().resolveAttribute(android.R.attr.statusBarColor, attribute, true);
        getWindow().setStatusBarColor(attribute.data);

        boolean isUpdate = ((mAppInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
        setTitle(isUpdate ? R.string.uninstall_update_title : R.string.uninstall_application_title);

        getFragmentManager().beginTransaction()
                .add(android.R.id.content, new UninstallAppProgressFragment(), FRAGMENT_TAG)
                .commitNowAllowingStateLoss();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (mResultCode == -1) {
                // Ignore back key when installation is in progress
                return true;
            } else {
                // If installation is done, just set the result code
                setResult(mResultCode);
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    private ProgressFragment getProgressFragment() {
        return (ProgressFragment) getFragmentManager().findFragmentByTag(FRAGMENT_TAG);
    }

    public interface ProgressFragment {
        void setUsersButtonVisible(boolean visible);
        void setDeviceManagerButtonVisible(boolean visible);
        void showCompletion(CharSequence statusText);
    }
}
