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

package com.android.server.pm;

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.QUERY_ALL_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IBackgroundInstallControlService;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.SparseSetArray;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.TreeSet;

/**
 * @hide
 */
public class BackgroundInstallControlService extends SystemService {
    private static final String TAG = "BackgroundInstallControlService";

    private static final String DISK_FILE_NAME = "states";
    private static final String DISK_DIR_NAME = "bic";

    private static final String ENFORCE_PERMISSION_ERROR_MSG =
            "User is not permitted to call service: ";

    private static final int MAX_FOREGROUND_TIME_FRAMES_SIZE = 10;
    private static final int MSG_USAGE_EVENT_RECEIVED = 0;
    private static final int MSG_PACKAGE_ADDED = 1;
    private static final int MSG_PACKAGE_REMOVED = 2;

    private final Context mContext;
    private final BinderService mBinderService;
    private final PackageManager mPackageManager;
    // TODO migrate all internal PackageManager calls to PackageManagerInternal where possible.
    // b/310983905
    private final PackageManagerInternal mPackageManagerInternal;
    private final UsageStatsManagerInternal mUsageStatsManagerInternal;
    private final PermissionManagerServiceInternal mPermissionManager;
    private final Handler mHandler;
    private final File mDiskFile;

    private SparseSetArray<String> mBackgroundInstalledPackages = null;
    private final BackgroundInstallControlCallbackHelper mCallbackHelper;

    // User ID -> package name -> set of foreground time frame
    private final SparseArrayMap<String, TreeSet<ForegroundTimeFrame>>
            mInstallerForegroundTimeFrames = new SparseArrayMap<>();

    public BackgroundInstallControlService(@NonNull Context context) {
        this(new InjectorImpl(context));
    }

    @VisibleForTesting
    BackgroundInstallControlService(@NonNull Injector injector) {
        super(injector.getContext());
        mContext = injector.getContext();
        mPackageManager = injector.getPackageManager();
        mPackageManagerInternal = injector.getPackageManagerInternal();
        mPermissionManager = injector.getPermissionManager();
        mHandler = new EventHandler(injector.getLooper(), this);
        mDiskFile = injector.getDiskFile();
        mUsageStatsManagerInternal = injector.getUsageStatsManagerInternal();
        mCallbackHelper = injector.getBackgroundInstallControlCallbackHelper();
        mUsageStatsManagerInternal.registerListener(
                (userId, event) ->
                        mHandler.obtainMessage(MSG_USAGE_EVENT_RECEIVED, userId, 0, event)
                                .sendToTarget());
        mBinderService = new BinderService(this);
    }

    private static final class BinderService extends IBackgroundInstallControlService.Stub {
        final BackgroundInstallControlService mService;

        BinderService(BackgroundInstallControlService service) {
            mService = service;
        }

        @Override
        public ParceledListSlice<PackageInfo> getBackgroundInstalledPackages(
                @PackageManager.PackageInfoFlagsBits long flags, int userId) {
            mService.enforceCallerQueryPackagesPermissions();
            if (!Build.IS_DEBUGGABLE) {
                return mService.getBackgroundInstalledPackages(flags, userId);
            }
            // The debug.transparency.bg-install-apps (only works for debuggable builds)
            // is used to set mock list of background installed apps for testing.
            // The list of apps' names is delimited by ",".
            // TODO: Remove after migrating test to new background install method using
            // {@link BackgroundInstallControlCallbackHelperTest}.installPackage b/310983905
            String propertyString = SystemProperties.get("debug.transparency.bg-install-apps");
            if (TextUtils.isEmpty(propertyString)) {
                return mService.getBackgroundInstalledPackages(flags, userId);
            } else {
                return mService.getMockBackgroundInstalledPackages(propertyString);
            }
        }

        @Override
        public void registerBackgroundInstallCallback(IRemoteCallback callback) {
            mService.enforceCallerQueryPackagesPermissions();
            mService.enforceCallerInteractCrossUserPermissions();
            mService.mCallbackHelper.registerBackgroundInstallCallback(callback);
        }

        @Override
        public void unregisterBackgroundInstallCallback(IRemoteCallback callback) {
            mService.enforceCallerQueryPackagesPermissions();
            mService.enforceCallerInteractCrossUserPermissions();
            mService.mCallbackHelper.unregisterBackgroundInstallCallback(callback);
        }
    }

    @RequiresPermission(QUERY_ALL_PACKAGES)
    void enforceCallerQueryPackagesPermissions() throws SecurityException {
        mContext.enforceCallingPermission(QUERY_ALL_PACKAGES,
                ENFORCE_PERMISSION_ERROR_MSG + QUERY_ALL_PACKAGES);
    }

    @RequiresPermission(INTERACT_ACROSS_USERS_FULL)
    void enforceCallerInteractCrossUserPermissions() throws SecurityException {
        mContext.enforceCallingPermission(INTERACT_ACROSS_USERS_FULL,
                ENFORCE_PERMISSION_ERROR_MSG + INTERACT_ACROSS_USERS_FULL);
    }

    @VisibleForTesting
    ParceledListSlice<PackageInfo> getBackgroundInstalledPackages(
            @PackageManager.PackageInfoFlagsBits long flags, int userId) {
        List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(
                PackageManager.PackageInfoFlags.of(flags), userId);

        initBackgroundInstalledPackages();
        ListIterator<PackageInfo> iter = packages.listIterator();
        while (iter.hasNext()) {
            String packageName = iter.next().packageName;
            if (!mBackgroundInstalledPackages.contains(userId, packageName)) {
                iter.remove();
            }
        }

        return new ParceledListSlice<>(packages);
    }

    /**
     * Mock a list of background installed packages based on the property string.
     */
    @NonNull
    ParceledListSlice<PackageInfo> getMockBackgroundInstalledPackages(
            @NonNull String propertyString) {
        String[] mockPackageNames = propertyString.split(",");
        List<PackageInfo> mockPackages = new ArrayList<>();
        for (String name : mockPackageNames) {
            try {
                PackageInfo packageInfo =
                        mPackageManager.getPackageInfo(
                                name, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ALL));
                mockPackages.add(packageInfo);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "Package's PackageInfo not found " + name);
                continue;
            }
        }
        return new ParceledListSlice<>(mockPackages);
    }

    private static class EventHandler extends Handler {
        private final BackgroundInstallControlService mService;

        EventHandler(Looper looper, BackgroundInstallControlService service) {
            super(looper);
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USAGE_EVENT_RECEIVED:
                    mService.handleUsageEvent(
                            (UsageEvents.Event) msg.obj, msg.arg1 /* userId */);
                    break;
                case MSG_PACKAGE_ADDED:
                    mService.handlePackageAdd((String) msg.obj, msg.arg1 /* userId */);
                    break;
                case MSG_PACKAGE_REMOVED:
                    mService.handlePackageRemove((String) msg.obj, msg.arg1 /* userId */);
                    break;
                default:
                    Slog.w(TAG, "Unknown message: " + msg.what);
            }
        }
    }

    void handlePackageAdd(String packageName, int userId) {
        ApplicationInfo appInfo = null;
        try {
            appInfo =
                    mPackageManager.getApplicationInfoAsUser(
                            packageName, PackageManager.ApplicationInfoFlags.of(0), userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Package's appInfo not found " + packageName);
            return;
        }

        String installerPackageName;
        String initiatingPackageName;
        try {
            final InstallSourceInfo installInfo = mPackageManager.getInstallSourceInfo(packageName);
            installerPackageName = installInfo.getInstallingPackageName();
            initiatingPackageName = installInfo.getInitiatingPackageName();
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Package's installer not found " + packageName);
            return;
        }

        // the installers without INSTALL_PACKAGES perm can't perform
        // the installation in background. So we can just filter out them.
        if (mPermissionManager.checkPermission(
                installerPackageName,
                android.Manifest.permission.INSTALL_PACKAGES,
                Context.DEVICE_ID_DEFAULT,
                userId)
                != PERMISSION_GRANTED) {
            return;
        }

        // convert up-time to current time.
        final long installTimestamp =
                System.currentTimeMillis() - (SystemClock.uptimeMillis() - appInfo.createTimestamp);

        if (installedByAdb(initiatingPackageName)
                || wasForegroundInstallation(installerPackageName, userId, installTimestamp)) {
            return;
        }

        initBackgroundInstalledPackages();
        mBackgroundInstalledPackages.add(userId, packageName);
        mCallbackHelper.notifyAllCallbacks(userId, packageName);
        writeBackgroundInstalledPackagesToDisk();
    }

    // ADB sets installerPackageName to null, this creates a loophole to bypass BIC which will be
    // addressed with b/265203007
    private boolean installedByAdb(String initiatingPackageName) {
        return PackageManagerServiceUtils.isInstalledByAdb(initiatingPackageName);
    }

    private boolean wasForegroundInstallation(
            String installerPackageName, int userId, long installTimestamp) {
        TreeSet<BackgroundInstallControlService.ForegroundTimeFrame> foregroundTimeFrames =
                mInstallerForegroundTimeFrames.get(userId, installerPackageName);

        // The installer never run in foreground.
        if (foregroundTimeFrames == null) {
            return false;
        }

        for (var foregroundTimeFrame : foregroundTimeFrames) {
            // the foreground time frame starts later than the installation.
            // so the installation is outside the foreground time frame.
            if (foregroundTimeFrame.startTimeStampMillis > installTimestamp) {
                continue;
            }

            // the foreground time frame is not over yet.
            // the installation is inside the foreground time frame.
            if (!foregroundTimeFrame.isDone()) {
                return true;
            }

            // the foreground time frame ends later than the installation.
            // the installation is inside the foreground time frame.
            if (installTimestamp <= foregroundTimeFrame.endTimeStampMillis) {
                return true;
            }
        }

        // the installation is not inside any of foreground time frames.
        // so it is not a foreground installation.
        return false;
    }

    void handlePackageRemove(String packageName, int userId) {
        initBackgroundInstalledPackages();
        mBackgroundInstalledPackages.remove(userId, packageName);
        writeBackgroundInstalledPackagesToDisk();
    }

    void handleUsageEvent(UsageEvents.Event event, int userId) {
        if (event.mEventType != UsageEvents.Event.ACTIVITY_RESUMED
                && event.mEventType != UsageEvents.Event.ACTIVITY_PAUSED
                && event.mEventType != UsageEvents.Event.ACTIVITY_STOPPED) {
            return;
        }

        if (!isInstaller(event.mPackage, userId)) {
            return;
        }

        if (!mInstallerForegroundTimeFrames.contains(userId, event.mPackage)) {
            mInstallerForegroundTimeFrames.add(userId, event.mPackage, new TreeSet<>());
        }

        TreeSet<BackgroundInstallControlService.ForegroundTimeFrame> foregroundTimeFrames =
                mInstallerForegroundTimeFrames.get(userId, event.mPackage);

        if ((foregroundTimeFrames.size() == 0) || foregroundTimeFrames.last().isDone()) {
            // ignore the other events if there is no open ForegroundTimeFrame.
            if (event.mEventType != UsageEvents.Event.ACTIVITY_RESUMED) {
                return;
            }
            foregroundTimeFrames.add(new ForegroundTimeFrame(event.mTimeStamp));
        }

        foregroundTimeFrames.last().addEvent(event);

        if (foregroundTimeFrames.size() > MAX_FOREGROUND_TIME_FRAMES_SIZE) {
            foregroundTimeFrames.pollFirst();
        }
    }

    @VisibleForTesting
    void writeBackgroundInstalledPackagesToDisk() {
        AtomicFile atomicFile = new AtomicFile(mDiskFile);
        FileOutputStream fileOutputStream;
        try {
            fileOutputStream = atomicFile.startWrite();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to start write to states protobuf.", e);
            return;
        }

        try {
            ProtoOutputStream protoOutputStream = new ProtoOutputStream(fileOutputStream);
            for (int i = 0; i < mBackgroundInstalledPackages.size(); i++) {
                int userId = mBackgroundInstalledPackages.keyAt(i);
                for (String packageName : mBackgroundInstalledPackages.get(userId)) {
                    long token =
                            protoOutputStream.start(
                                    BackgroundInstalledPackagesProto.BG_INSTALLED_PKG);
                    protoOutputStream.write(
                            BackgroundInstalledPackageProto.PACKAGE_NAME, packageName);
                    protoOutputStream.write(BackgroundInstalledPackageProto.USER_ID, userId + 1);
                    protoOutputStream.end(token);
                }
            }
            protoOutputStream.flush();
            atomicFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to finish write to states protobuf.", e);
            atomicFile.failWrite(fileOutputStream);
        }
    }

    @VisibleForTesting
    void initBackgroundInstalledPackages() {
        if (mBackgroundInstalledPackages != null) {
            return;
        }

        mBackgroundInstalledPackages = new SparseSetArray<>();

        if (!mDiskFile.exists()) {
            return;
        }

        AtomicFile atomicFile = new AtomicFile(mDiskFile);
        try (FileInputStream fileInputStream = atomicFile.openRead()) {
            ProtoInputStream protoInputStream = new ProtoInputStream(fileInputStream);

            while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                if (protoInputStream.getFieldNumber()
                        != (int) BackgroundInstalledPackagesProto.BG_INSTALLED_PKG) {
                    continue;
                }
                long token =
                        protoInputStream.start(BackgroundInstalledPackagesProto.BG_INSTALLED_PKG);
                String packageName = null;
                int userId = UserHandle.USER_NULL;
                while (protoInputStream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    switch (protoInputStream.getFieldNumber()) {
                        case (int) BackgroundInstalledPackageProto.PACKAGE_NAME:
                            packageName =
                                    protoInputStream.readString(
                                            BackgroundInstalledPackageProto.PACKAGE_NAME);
                            break;
                        case (int) BackgroundInstalledPackageProto.USER_ID:
                            userId =
                                    protoInputStream.readInt(
                                            BackgroundInstalledPackageProto.USER_ID)
                                            - 1;
                            break;
                        default:
                            Slog.w(
                                    TAG,
                                    "Undefined field in proto: "
                                            + protoInputStream.getFieldNumber());
                    }
                }
                protoInputStream.end(token);
                if (packageName != null && userId != UserHandle.USER_NULL) {
                    mBackgroundInstalledPackages.add(userId, packageName);
                } else {
                    Slog.w(TAG, "Fails to get packageName or UserId from proto file");
                }
            }
        } catch (IOException e) {
            Slog.w(TAG, "Error reading state from the disk", e);
        }
    }

    @VisibleForTesting
    SparseSetArray<String> getBackgroundInstalledPackages() {
        return mBackgroundInstalledPackages;
    }

    @VisibleForTesting
    SparseArrayMap<String, TreeSet<ForegroundTimeFrame>> getInstallerForegroundTimeFrames() {
        return mInstallerForegroundTimeFrames;
    }

    private boolean isInstaller(String pkgName, int userId) {
        if (mInstallerForegroundTimeFrames.contains(userId, pkgName)) {
            return true;
        }
        return mPermissionManager.checkPermission(
                pkgName,
                android.Manifest.permission.INSTALL_PACKAGES,
                Context.DEVICE_ID_DEFAULT,
                userId)
                == PERMISSION_GRANTED;
    }

    @Override
    public void onStart() {
        onStart(/* isForTesting= */ false);
    }

    @VisibleForTesting
    void onStart(boolean isForTesting) {
        if (!isForTesting) {
            publishBinderService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE, mBinderService);
        }

        mPackageManagerInternal.getPackageList(
                new PackageManagerInternal.PackageListObserver() {
                    @Override
                    public void onPackageAdded(String packageName, int uid) {
                        final int userId = UserHandle.getUserId(uid);
                        mHandler.obtainMessage(MSG_PACKAGE_ADDED, userId, 0, packageName)
                                .sendToTarget();
                    }

                    @Override
                    public void onPackageRemoved(String packageName, int uid) {
                        final int userId = UserHandle.getUserId(uid);
                        mHandler.obtainMessage(MSG_PACKAGE_REMOVED, userId, 0, packageName)
                                .sendToTarget();
                    }
                });
    }

    // The foreground time frame (ForegroundTimeFrame) represents the period
    // when a package's activities continuously occupy the foreground.
    // Each ForegroundTimeFrame starts with an ACTIVITY_RESUMED event,
    // and then ends with an ACTIVITY_PAUSED or ACTIVITY_STOPPED event.
    // The startTimeStampMillis stores the timestamp of the ACTIVITY_RESUMED event.
    // The endTimeStampMillis stores the timestamp of the ACTIVITY_PAUSED or ACTIVITY_STOPPED event
    // that wraps up the ForegroundTimeFrame.
    // The activities are designed to handle the edge case in which a package's one activity
    // seamlessly replace another activity of the same package. Thus, we count these activities
    // together as a ForegroundTimeFrame. For this scenario, only when all the activities terminate
    // shall consider the completion of the ForegroundTimeFrame.
    static final class ForegroundTimeFrame implements Comparable<ForegroundTimeFrame> {
        public final long startTimeStampMillis;
        public long endTimeStampMillis;
        public final Set<Integer> activities;

        public int compareTo(ForegroundTimeFrame o) {
            int comp = Long.compare(startTimeStampMillis, o.startTimeStampMillis);
            if (comp != 0) return comp;

            return Integer.compare(hashCode(), o.hashCode());
        }

        ForegroundTimeFrame(long startTimeStampMillis) {
            this.startTimeStampMillis = startTimeStampMillis;
            endTimeStampMillis = 0;
            activities = new ArraySet<>();
        }

        public boolean isDone() {
            return endTimeStampMillis != 0;
        }

        public void addEvent(UsageEvents.Event event) {
            switch (event.mEventType) {
                case UsageEvents.Event.ACTIVITY_RESUMED:
                    activities.add(event.mInstanceId);
                    break;
                case UsageEvents.Event.ACTIVITY_PAUSED:
                case UsageEvents.Event.ACTIVITY_STOPPED:
                    if (activities.contains(event.mInstanceId)) {
                        activities.remove(event.mInstanceId);
                        if (activities.size() == 0) {
                            endTimeStampMillis = event.mTimeStamp;
                        }
                    }
                    break;
                default:
            }
        }
    }

    /**
     * Dependency injector for {@link BackgroundInstallControlService}.
     */
    interface Injector {
        Context getContext();

        PackageManager getPackageManager();

        PackageManagerInternal getPackageManagerInternal();

        UsageStatsManagerInternal getUsageStatsManagerInternal();

        PermissionManagerServiceInternal getPermissionManager();

        Looper getLooper();

        File getDiskFile();

        BackgroundInstallControlCallbackHelper getBackgroundInstallControlCallbackHelper();
    }

    private static final class InjectorImpl implements Injector {
        private final Context mContext;

        InjectorImpl(Context context) {
            mContext = context;
        }

        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public PackageManager getPackageManager() {
            return mContext.getPackageManager();
        }

        @Override
        public PackageManagerInternal getPackageManagerInternal() {
            return LocalServices.getService(PackageManagerInternal.class);
        }

        @Override
        public UsageStatsManagerInternal getUsageStatsManagerInternal() {
            return LocalServices.getService(UsageStatsManagerInternal.class);
        }

        @Override
        public PermissionManagerServiceInternal getPermissionManager() {
            return LocalServices.getService(PermissionManagerServiceInternal.class);
        }

        @Override
        public Looper getLooper() {
            ServiceThread serviceThread =
                    new ServiceThread(
                            TAG, android.os.Process.THREAD_PRIORITY_FOREGROUND, true /* allowIo */);
            serviceThread.start();
            return serviceThread.getLooper();
        }

        @Override
        public File getDiskFile() {
            File dir = new File(Environment.getDataSystemDirectory(), DISK_DIR_NAME);
            File file = new File(dir, DISK_FILE_NAME);
            return file;
        }

        @Override
        public BackgroundInstallControlCallbackHelper getBackgroundInstallControlCallbackHelper() {
            return new BackgroundInstallControlCallbackHelper();
        }
    }
}
