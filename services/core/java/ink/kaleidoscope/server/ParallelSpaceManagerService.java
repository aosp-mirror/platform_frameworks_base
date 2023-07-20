/*
 * Copyright (C) 2022 Project Kaleidoscope
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

package ink.kaleidoscope.server;

import static android.os.Process.THREAD_PRIORITY_DEFAULT;
import static android.os.UserManager.USER_TYPE_PARALLEL_DEFAULT;
import static android.os.UserManager.USER_TYPE_PARALLEL_SHARE;
import static android.provider.Settings.Secure.USER_SETUP_COMPLETE;

import static ink.kaleidoscope.ParallelSpaceManager.SERVICE_NAME;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import ink.kaleidoscope.IParallelSpaceManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.InterruptedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * All in one service for managing the lifecycle of parallel spaces.
 */
public final class ParallelSpaceManagerService extends SystemService {

    private static final String TAG = "ParallelSpaceManagerService";

    /**
     * By default, only non-launchable system apps will be initially installed in
     * a new space. Here you can explicitly configure for this.
     */
    private static final List<String> SPACE_WHITELIST_PACKAGES = Arrays.asList(
        // For granting permissions.
        "com.android.settings",
        // For managing files.
        "com.android.documentsui",
        "com.android.google.documentsui"
    );

    private static final List<String> SPACE_BLOCKLIST_PACKAGES = Arrays.asList(
        // To avoid third party apps starting it accidentally.
        "com.android.fmradio",
        "com.android.launcher3",
        "com.caf.fmradio",
        "com.android.server.telecom",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.pixel.setupwizard",
        "com.google.android.projection.gearhead",
        "com.google.android.setupwizard"
    );

    /**
     * Components should be disabled on space setup.
     */
    private static final List<String> SPACE_BLACKLIST_COMPONENTS = Arrays.asList(
        // Remove settings icon from launcher.
        "com.android.settings/com.android.settings.Settings"
    );

    private static final int MSG_UPDATE_PARALLEL_USER_LIST = 0;
    private static final int MSG_START_SPACES = 1;
    private static final int MSG_SETUP_SPACE = 2;

    private static final int RESTART_USER_DELAY = 3000;

    private Handler mHandler;
    private Interface mInterface;
    private IUserManager mUserManager;
    private IActivityManager mActivityManager;
    private PackageManager mPackageManager;
    private IPackageManager mPackageManagerService;
    private IPackageInstaller mPackageInstaller;

    /*
     * Static variables that must be protected by mLock are below.
     * It will be much cleaner to use LocalServices instead. But
     * making it static is the quickest way to go through any
     * sequential dependence problem as the callers are mostly
     * early-start system services.
     */
    private static Object mLock = new Object();
    @GuardedBy("mLock")
    private static int mCurrentUserId;
    @GuardedBy("mLock")
    private static UserInfo mCurrentUser;
    // Full parallel user information in case you are curious
    @GuardedBy("mLock")
    private static List<UserInfo> mCurrentParallelUsers;
    // Parallel user ids only for a quick search
    @GuardedBy("mLock")
    private static List<Integer> mCurrentParallelUserIds;
    // Profile users for interaction check
    @GuardedBy("mLock")
    private static List<UserInfo> mCurrentProfileUsers;
    @GuardedBy("mLock")
    private static List<Integer> mCurrentProfileUserIds;
    // end of static variables

    /**
     * Static methods that act as system server internal api. They can only be
     * accessed within system server process. For the reason why we are using
     * **static** methods, see the comments above static variables.
     */

    // Return parallel owner id if userId is a parallel user.
    public static int convertToParallelOwnerIfPossible(int userId) {
        synchronized (mLock) {
            if (mCurrentParallelUserIds != null && mCurrentParallelUserIds.contains(userId))
                return mCurrentUserId;
            return userId;
        }
    }

    // Check whether target user is a parallel user.
    public static boolean isCurrentParallelUser(int userId) {
        synchronized (mLock) {
            if (mCurrentParallelUserIds != null)
                return mCurrentParallelUserIds.contains(userId);
            return false;
        }
    }

    // Get user id of currently foreground parallel space owner.
    public static int getCurrentParallelOwnerId() {
        synchronized (mLock) {
            return mCurrentUserId;
        }
    }

    // Check whether target user is the parallel owner.
    public static boolean isCurrentParallelOwner(int userId) {
            return userId == getCurrentParallelOwnerId();
    }

    // Return a list of current parallel user ids.
    public static List<Integer> getCurrentParallelUserIds() {
        // Must make a copy here because it can be dangerous to
        // pass the reference.
        synchronized (mLock) {
            if (mCurrentParallelUserIds != null)
                return new ArrayList<Integer>(mCurrentParallelUserIds);
            return Collections.emptyList();
        }
    }

    // Whether a userId in in range of {owner, profiles, parallelSpaces}.
    public static boolean isInteractive(int userId) {
        synchronized (mLock) {
            if (mCurrentParallelUserIds == null || mCurrentProfileUserIds == null)
                return false;

            return userId == mCurrentUserId || mCurrentParallelUserIds.contains(userId) ||
                    mCurrentProfileUserIds.contains(userId);
        }
    }

    // Owner user, profiles and parallel spaces should be able to
    // interact with each other.
    public static boolean canInteract(int userId1, int userId2) {
        return isInteractive(userId1) && isInteractive(userId2);
    }

    // Interactive users = owner + profiles + parallel spaces.
    public static List<UserInfo> getInteractiveUsers() {
        ArrayList<UserInfo> result = new ArrayList<>();
        synchronized (mLock) {
            result.add(mCurrentUser);
            result.addAll(mCurrentProfileUsers);
            result.addAll(mCurrentParallelUsers);
        }
        return result;
    }

    /*
     * All internal methods should be either synchronized or be
     * called on the service thread to avoid racing.
     */
    private void handleMessageInternal(Message msg) {
        switch (msg.what) {
            case MSG_UPDATE_PARALLEL_USER_LIST: {
                updateParallelUserListInternal();
            } break;
            case MSG_START_SPACES: {
                startSpacesIfNeededInternal();
            } break;
            case MSG_SETUP_SPACE: {
                setupSpaceIfNeededInternal(msg.arg1);
            } break;
        }
    }

    private void setupSpaceIfNeededInternal(int userId) {
        synchronized (mLock) {
            if (mCurrentParallelUserIds == null || !mCurrentParallelUserIds.contains(userId))
                return;
        }

        ContentResolver cr = getContext().getContentResolver();
        if (Settings.Secure.getIntForUser(cr, USER_SETUP_COMPLETE, 0, userId) == 1)
            return;

        for (String name : SPACE_BLACKLIST_COMPONENTS) {
            String[] splittedName = name.split("/");
            if (splittedName.length != 2) {
                Slog.e(TAG, "Failed when resolving SPACE_BLACKLIST_COMPONENTS: " + name);
            }

            ComponentName componentName = new ComponentName(splittedName[0], splittedName[1]);
            try {
                mPackageManagerService.setComponentEnabledSetting(componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP, userId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed when disabling component: " + name);
            }
        }

        Settings.Secure.putIntForUser(cr, USER_SETUP_COMPLETE, 1, userId);

        // We are done. Notify the update.
        broadcastChange();
        Slog.i(TAG, "User setup done for " + userId);
    }

    private void startSpacesIfNeededInternal() {
        List<Integer> users;
        synchronized (mLock) {
            if (mCurrentParallelUserIds == null)
                return;
            users = new ArrayList<>(mCurrentParallelUserIds);
        }

        try {
            for (int userId : users) {
                if (!mActivityManager.isUserRunning(userId, 0)) {
                    mActivityManager.startUserInBackground(userId);
                    Slog.i(TAG, "User started for " + userId);
                }
            }
        } catch (RemoteException e) {
        }
    }

    private synchronized int removeSpaceInternal(int userId) {
        synchronized (mLock) {
            if (mCurrentParallelUserIds == null ||
                    !mCurrentParallelUserIds.contains(userId)) {
                // Fail fast. Although this shouldn't happen at all.
                return -1;
            }
        }

        boolean success = false;
        try {
            success = mUserManager.removeUser(userId);
        } catch (RemoteException e) {
        }

        if (!success) {
            Slog.e(TAG, "Failed when removing space: " + userId);
            return -1;
        }

        Slog.i(TAG, "Parallel space removed: " + userId);
        // User removed. Update the list.
        updateParallelUserListInternal();

        return 0;
    }

    private void killExternalStorageProvider() {
        List<Integer> victimUsers;
        synchronized (mLock) {
            victimUsers = new ArrayList<>(mCurrentParallelUserIds);
        }
        victimUsers.add(mCurrentUserId);
        for (int userId : victimUsers) {
            try {
                mActivityManager.forceStopPackage("com.android.externalstorage", userId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed when killing ExternalStorageProvider for user " + userId);
            }
        }
    }

    private synchronized int createSpaceInternal(String name, boolean shareMedia) {
        final int userId;
        // Make a copy here because I don't want to call into
        // other services with the global data lock held.
        synchronized (mLock) {
            userId = mCurrentUserId;
        }

        String[] nonRequiredApps = getNonRequiredApps(userId).toArray(new String[0]);
        UserInfo result = null;

        try {
            if (shareMedia) {
                result = mUserManager.createProfileForUserWithThrow(
                        name, USER_TYPE_PARALLEL_SHARE, 0, userId, nonRequiredApps);
            } else {
                result = mUserManager.createProfileForUserWithThrow(
                        name, USER_TYPE_PARALLEL_DEFAULT, 0, userId, nonRequiredApps);
            }
        } catch (RemoteException e) {
        }

        if (result == null) {
            Slog.e(TAG, "Failed when creating a new space");
            return -1;
        }

        Slog.i(TAG, "New parallel space created: " + result.toFullString());
        // New user added. Update the list.
        updateParallelUserListInternal();
        // List of users has been updated. Start them.
        mHandler.removeMessages(MSG_START_SPACES);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_START_SPACES));

        // HACK: For legacy devices running fuse above sdcardfs.
        // Kill ExternalStorageProvider to make it restart with new gids.
        killExternalStorageProvider();

        return result.id;
    }

    private synchronized void updateParallelUserListInternal() {
        updateParallelUserListInternalNoBroadcast();
        // Notify interested clients.
        broadcastChange();
    }

    private synchronized void updateParallelUserListInternalNoBroadcast() {
        ArrayList<UserInfo> parallelUsers = new ArrayList<>();
        ArrayList<UserInfo> profileUsers = new ArrayList<>();
        ArrayList<Integer> parallelUserIds = new ArrayList<>();
        ArrayList<Integer> profileUserIds = new ArrayList<>();
        List<UserInfo> users = null;
        int userId = -1;
        UserInfo curUser = null;

        try {
            userId = mActivityManager.getCurrentUserId();
            users = mUserManager.getUsers(true, true, true);
        } catch (RemoteException e) {
        }

        if (userId < 0 || users == null)
            return;

        for (UserInfo user : users) {
            if (user.isParallel() && user.parallelParentId == userId) {
                parallelUsers.add(user);
                parallelUserIds.add(user.id);
                continue;
            }
            if (user.isProfile() && user.profileGroupId == userId) {
                profileUsers.add(user);
                profileUserIds.add(user.id);
                continue;
            }
            if (user.id == userId) {
                curUser = user;
            }
        }

        synchronized (mLock) {
            mCurrentUserId = userId;
            mCurrentUser = curUser;
            mCurrentParallelUsers = parallelUsers;
            mCurrentParallelUserIds = parallelUserIds;
            mCurrentProfileUsers = profileUsers;
            mCurrentProfileUserIds = profileUserIds;
        }
    }

    private void broadcastChange() {
        int userId;
        synchronized (mLock) {
            userId = mCurrentUserId;
        }

        Intent intent = new Intent(Intent.ACTION_PARALLEL_SPACE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        getContext().sendBroadcastAsUser(intent, UserHandle.of(userId),
                android.Manifest.permission.MANAGE_PARALLEL_SPACES);
    }

    private Set<String> getNonRequiredApps(int userId) {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = mPackageManager.queryIntentActivitiesAsUser(
                launcherIntent,
                PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DISABLED_COMPONENTS,
                userId);
        Set<String> apps = new ArraySet<>();

        for (ResolveInfo resolveInfo : resolveInfos) {
            apps.add(resolveInfo.activityInfo.packageName);
        }
        apps.removeAll(SPACE_WHITELIST_PACKAGES);
        apps.removeAll(Arrays.asList(getContext().getResources().getStringArray(
                com.android.internal.R.array.config_parallelSpaceWhitelist)));
        // Those packages should be handled by GmsManagerService, always install them.
        apps.removeAll(Arrays.asList(GmsManagerService.GMS_PACKAGES));
        apps.addAll(SPACE_BLOCKLIST_PACKAGES);
        apps.addAll(Arrays.asList(getContext().getResources().getStringArray(
                com.android.internal.R.array.config_parallelSpaceBlocklist)));

        Slog.i(TAG, "Package installation skipped: " + apps);
        return apps;
    }

    @Override
    public void onUserStarting(TargetUser user) {
        synchronized (mLock) {
            // Not parallel user.
            if (mCurrentParallelUserIds == null ||
                    !mCurrentParallelUserIds.contains(user.getUserIdentifier()))
                return;
        }
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SETUP_SPACE, user.getUserIdentifier(), 0));
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_PARALLEL_USER_LIST));
        mHandler.removeMessages(MSG_START_SPACES);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_START_SPACES));
    }

    @Override
    public void onUserStopped(TargetUser user) {
        // Wake up spaces if stopped by incidents e.g. stopUserOnSwitch.
        // Delay it in case this is a stop before removing, so we need to wait for
        // the user removed broadcast first otherwise it will crash the system.
        mHandler.removeMessages(MSG_START_SPACES);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_START_SPACES), RESTART_USER_DELAY);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != PHASE_ACTIVITY_MANAGER_READY)
            return;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_PARALLEL_USER_LIST));
    }

    @Override
    public void onStart() {
        mUserManager = IUserManager.Stub.asInterface(
                ServiceManager.getService(Context.USER_SERVICE));
        mActivityManager = IActivityManager.Stub.asInterface(
                ServiceManager.getService(Context.ACTIVITY_SERVICE));
        mPackageManager = getContext().getPackageManager();
        mPackageManagerService = IPackageManager.Stub.asInterface(
                ServiceManager.getService("package"));
        try {
            mPackageInstaller = mPackageManagerService.getPackageInstaller();
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to get package installer");
        }

        ServiceThread st = new ServiceThread(TAG, THREAD_PRIORITY_DEFAULT, false);
        st.start();
        mHandler = new Handler(st.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                handleMessageInternal(msg);
            }
        };

        IntentFilter unlockFilter = new IntentFilter();
        unlockFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        getContext().registerReceiverForAllUsers(
                new FirstUnlockReceiver(), unlockFilter, null, mHandler);

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        getContext().registerReceiverForAllUsers(
                new UserReceiver(), userFilter, null, mHandler);

        mInterface = new Interface();
        publishBinderService(SERVICE_NAME, mInterface);
    }

    public ParallelSpaceManagerService(Context context) {
        super(context);
    }

    private void ensureParallelUser(int userId) {
        synchronized (mLock) {
            if (mCurrentParallelUserIds == null || !mCurrentParallelUserIds.contains(userId))
                throw new IllegalArgumentException(userId + " is not a parallel space");
        }
    }

    private boolean hasPermissionGranted(String permission, int uid) {
        return ActivityManager.checkComponentPermission(
                permission, uid, /* owningUid = */-1, /* exported = */ true) ==
                PackageManager.PERMISSION_GRANTED;
    }

    private void enforceCallingPermission(int uid) {
        if (uid == Process.SYSTEM_UID || uid == Process.SHELL_UID)
            return;
        if (!hasPermissionGranted(android.Manifest.permission.MANAGE_PARALLEL_SPACES, uid))
            throw new SecurityException("Caller does not have permission MANAGE_PARALLEL_SPACES");
    }

    private class Interface extends IParallelSpaceManager.Stub {
        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            (new DebugShellCommand()).exec(this, in, out, err, args, callback, resultReceiver);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
            synchronized (mLock) {
                fout.println("mCurrentUserId=" + mCurrentUserId);
                fout.println("mCurrentUser=" + mCurrentUser);
                fout.println("mCurrentParallelUsers=" + mCurrentParallelUsers);
                fout.println("mCurrentParallelUserIds=" + mCurrentParallelUserIds);
                fout.println("mCurrentProfileUsers=" + mCurrentProfileUsers);
                fout.println("mCurrentProfileUserIds=" + mCurrentProfileUserIds);
            }
        }

        @Override
        public int create(String name, boolean shareMedia) {
            enforceCallingPermission(Binder.getCallingUid());

            final long token = Binder.clearCallingIdentity();
            try {
                return createSpaceInternal(name, shareMedia);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int remove(int userId) {
            enforceCallingPermission(Binder.getCallingUid());
            ensureParallelUser(userId);

            final long token = Binder.clearCallingIdentity();
            try {
                return removeSpaceInternal(userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public UserInfo[] getUsers() {
            enforceCallingPermission(Binder.getCallingUid());
            synchronized (mLock) {
                if (mCurrentParallelUsers != null)
                    return mCurrentParallelUsers.toArray(new UserInfo[0]);
                return new UserInfo[0];
            }
        }

        @Override
        public UserInfo getOwner() {
            enforceCallingPermission(Binder.getCallingUid());
            synchronized (mLock) {
                return mCurrentUser;
            }
        }

        @Override
        public int duplicatePackage(String packageName, int userId) {
            enforceCallingPermission(Binder.getCallingUid());
            ensureParallelUser(userId);
            // Forward it to PM with full permission.
            final long token = Binder.clearCallingIdentity();
            try {
                int result = mPackageManager.installExistingPackageAsUser(packageName, userId);
                return result == PackageManager.INSTALL_SUCCEEDED ? 0 : -1;
            } catch (PackageManager.NameNotFoundException e) {
                return -1;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int removePackage(String packageName, int userId) {
            enforceCallingPermission(Binder.getCallingUid());
            ensureParallelUser(userId);
            final long token = Binder.clearCallingIdentity();
            try {
                // PM does not provide proper api, so use PMI instead.
                PackageInstallerReceiver receiver = new PackageInstallerReceiver();
                mPackageInstaller.uninstallExistingPackage(
                        new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                        getContext().getPackageName(), receiver.getIntentSender(), userId);
                // Can be a little bit tricky to get the result.
                return receiver.waitForIntResult() == PackageInstaller.STATUS_SUCCESS ? 0 : -1;
            } catch (RemoteException e) {
                return -1;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    private final class UserReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // No need for broadcast because this is only for work profile.
            updateParallelUserListInternalNoBroadcast();
        }
    }

    private final class FirstUnlockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Must wait until unlock otherwise it'll break storage session.
            mHandler.sendMessage(mHandler.obtainMessage(MSG_START_SPACES));
            // This should only be run once.
            getContext().unregisterReceiver(this);
        }
    }

    private class DebugShellCommand extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            boolean handled = false;
            int result = 0;
            PrintWriter pw = getOutPrintWriter();

            if ("create".equals(cmd)) {
                result = mInterface.create(getNextArg(), "share".equals(getNextArg()));
                handled = true;
            } else if ("remove".equals(cmd)) {
                result = mInterface.remove(Integer.parseInt(getNextArg()));
                handled = true;
            } else if ("list".equals(cmd)) {
                for (UserInfo user : mInterface.getUsers()) {
                    pw.println(user.toFullString());
                }
                handled = true;
            } else if ("dup".equals(cmd)) {
                result = mInterface.duplicatePackage(getNextArg(), Integer.parseInt(getNextArg()));
                handled = true;
            } else if ("rmdup".equals(cmd)) {
                result = mInterface.removePackage(getNextArg(), Integer.parseInt(getNextArg()));
                handled = true;
            }

            if (handled) {
                pw.println("Command done result = " + result);
                return 0;
            }
            return handleDefaultCommands(cmd);
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
        }
    }

    private static class PackageInstallerReceiver {
        private Object mLock = new Object();
        @GuardedBy("mLock")
        private Intent mResult;

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                synchronized (mLock) {
                    mResult = intent;
                    mLock.notifyAll();
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        private Intent waitForResult() {
            synchronized (mLock) {
                if (mResult != null)
                    return mResult;
                try {
                    mLock.wait();
                    return mResult;
                } catch (InterruptedException e) {
                    return null;
                }
            }
        }

        public int waitForIntResult() {
            Intent result = waitForResult();
            if (result == null)
                return PackageInstaller.STATUS_FAILURE;
            return result.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        }
    }
}
