/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.content.pm.UserInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseLongArray;

import com.android.internal.app.IMediaContainerService;
import com.android.internal.util.ArrayUtils;
import com.google.android.collect.Sets;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Utility for measuring the disk usage of internal storage or a physical
 * {@link StorageVolume}. Connects with a remote {@link IMediaContainerService}
 * and delivers results to {@link MeasurementReceiver}.
 */
public class StorageMeasurement {
    private static final String TAG = "StorageMeasurement";

    private static final boolean LOCAL_LOGV = false;
    static final boolean LOGV = LOCAL_LOGV && Log.isLoggable(TAG, Log.VERBOSE);

    private static final String DEFAULT_CONTAINER_PACKAGE = "com.android.defcontainer";

    public static final ComponentName DEFAULT_CONTAINER_COMPONENT = new ComponentName(
            DEFAULT_CONTAINER_PACKAGE, "com.android.defcontainer.DefaultContainerService");

    /** Media types to measure on external storage. */
    private static final Set<String> sMeasureMediaTypes = Sets.newHashSet(
            Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_ALARMS, Environment.DIRECTORY_NOTIFICATIONS,
            Environment.DIRECTORY_RINGTONES, Environment.DIRECTORY_PODCASTS,
            Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_ANDROID);

    public static class MeasurementDetails {
        public long totalSize;
        public long availSize;

        /**
         * Total apps disk usage per profiles of the current user.
         * <p>
         * When measuring internal storage, this value includes the code size of
         * all apps (regardless of install status for the given profile), and
         * internal disk used by the profile's apps. When the device
         * emulates external storage, this value also includes emulated storage
         * used by the profile's apps.
         * <p>
         * When measuring a physical {@link StorageVolume}, this value includes
         * usage by all apps on that volume and only for the primary profile.
         * <p>
         * Key is {@link UserHandle}.
         */
        public SparseLongArray appsSize = new SparseLongArray();

        /**
         * Total cache disk usage by apps (over all users and profiles).
         */
        public long cacheSize;

        /**
         * Total media disk usage, categorized by types such as
         * {@link Environment#DIRECTORY_MUSIC} for every user profile of the current user.
         * <p>
         * When measuring internal storage, this reflects media on emulated
         * storage for the respective profile.
         * <p>
         * When measuring a physical {@link StorageVolume}, this reflects media
         * on that volume.
         * <p>
         * Key of the {@link SparseArray} is {@link UserHandle}.
         */
        public SparseArray<HashMap<String, Long>> mediaSize = new SparseArray<>();

        /**
         * Misc external disk usage for the current user's profiles, unaccounted in
         * {@link #mediaSize}. Key is {@link UserHandle}.
         */
        public SparseLongArray miscSize = new SparseLongArray();

        /**
         * Total disk usage for users, which is only meaningful for emulated
         * internal storage. Key is {@link UserHandle}.
         */
        public SparseLongArray usersSize = new SparseLongArray();
    }

    public interface MeasurementReceiver {
        void onDetailsChanged(MeasurementDetails details);
    }

    private WeakReference<MeasurementReceiver> mReceiver;

    private final Context mContext;

    private final VolumeInfo mVolume;
    private final VolumeInfo mSharedVolume;

    private final MainHandler mMainHandler;
    private final MeasurementHandler mMeasurementHandler;

    public StorageMeasurement(Context context, VolumeInfo volume, VolumeInfo sharedVolume) {
        mContext = context.getApplicationContext();

        mVolume = volume;
        mSharedVolume = sharedVolume;

        // Start the thread that will measure the disk usage.
        final HandlerThread handlerThread = new HandlerThread("MemoryMeasurement");
        handlerThread.start();

        mMainHandler = new MainHandler();
        mMeasurementHandler = new MeasurementHandler(handlerThread.getLooper());
    }

    public void setReceiver(MeasurementReceiver receiver) {
        if (mReceiver == null || mReceiver.get() == null) {
            mReceiver = new WeakReference<MeasurementReceiver>(receiver);
        }
    }

    public void forceMeasure() {
        invalidate();
        measure();
    }

    public void measure() {
        if (!mMeasurementHandler.hasMessages(MeasurementHandler.MSG_MEASURE)) {
            mMeasurementHandler.sendEmptyMessage(MeasurementHandler.MSG_MEASURE);
        }
    }

    public void onDestroy() {
        mReceiver = null;
        mMeasurementHandler.removeMessages(MeasurementHandler.MSG_MEASURE);
        mMeasurementHandler.sendEmptyMessage(MeasurementHandler.MSG_DISCONNECT);
    }

    private void invalidate() {
        mMeasurementHandler.sendEmptyMessage(MeasurementHandler.MSG_INVALIDATE);
    }

    private static class StatsObserver extends IPackageStatsObserver.Stub {
        private final boolean mIsPrivate;
        private final MeasurementDetails mDetails;
        private final int mCurrentUser;
        private final Message mFinished;

        private int mRemaining;

        public StatsObserver(boolean isPrivate, MeasurementDetails details, int currentUser,
                List<UserInfo> profiles, Message finished, int remaining) {
            mIsPrivate = isPrivate;
            mDetails = details;
            mCurrentUser = currentUser;
            if (isPrivate) {
                // Add the profile ids as keys to detail's app sizes.
                for (UserInfo userInfo : profiles) {
                    mDetails.appsSize.put(userInfo.id, 0);
                }
            }
            mFinished = finished;
            mRemaining = remaining;
        }

        @Override
        public void onGetStatsCompleted(PackageStats stats, boolean succeeded) {
            synchronized (mDetails) {
                if (succeeded) {
                    addStatsLocked(stats);
                }
                if (--mRemaining == 0) {
                    mFinished.sendToTarget();
                }
            }
        }

        private void addStatsLocked(PackageStats stats) {
            if (mIsPrivate) {
                long codeSize = stats.codeSize;
                long dataSize = stats.dataSize;
                long cacheSize = stats.cacheSize;
                if (Environment.isExternalStorageEmulated()) {
                    // Include emulated storage when measuring internal. OBB is
                    // shared on emulated storage, so treat as code.
                    codeSize += stats.externalCodeSize + stats.externalObbSize;
                    dataSize += stats.externalDataSize + stats.externalMediaSize;
                    cacheSize += stats.externalCacheSize;
                }

                // Count code and data for current user's profiles (keys prepared in constructor)
                addValueIfKeyExists(mDetails.appsSize, stats.userHandle, codeSize + dataSize);

                // User summary only includes data (code is only counted once
                // for the current user)
                addValue(mDetails.usersSize, stats.userHandle, dataSize);

                // Include cache for all users
                mDetails.cacheSize += cacheSize;

            } else {
                // Physical storage; only count external sizes
                addValue(mDetails.appsSize, mCurrentUser,
                        stats.externalCodeSize + stats.externalDataSize
                        + stats.externalMediaSize + stats.externalObbSize);
                mDetails.cacheSize += stats.externalCacheSize;
            }
        }
    }

    private class MainHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            final MeasurementDetails details = (MeasurementDetails) msg.obj;
            final MeasurementReceiver receiver = (mReceiver != null) ? mReceiver.get() : null;
            if (receiver != null) {
                receiver.onDetailsChanged(details);
            }
        }
    }

    private class MeasurementHandler extends Handler {
        public static final int MSG_MEASURE = 1;
        public static final int MSG_CONNECTED = 2;
        public static final int MSG_DISCONNECT = 3;
        public static final int MSG_COMPLETED = 4;
        public static final int MSG_INVALIDATE = 5;

        private Object mLock = new Object();

        private IMediaContainerService mDefaultContainer;

        private volatile boolean mBound = false;

        private MeasurementDetails mCached;

        private final ServiceConnection mDefContainerConn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final IMediaContainerService imcs = IMediaContainerService.Stub.asInterface(
                        service);
                mDefaultContainer = imcs;
                mBound = true;
                sendMessage(obtainMessage(MSG_CONNECTED, imcs));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mBound = false;
                removeMessages(MSG_CONNECTED);
            }
        };

        public MeasurementHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_MEASURE: {
                    if (mCached != null) {
                        mMainHandler.obtainMessage(0, mCached).sendToTarget();
                        break;
                    }

                    synchronized (mLock) {
                        if (mBound) {
                            removeMessages(MSG_DISCONNECT);
                            sendMessage(obtainMessage(MSG_CONNECTED, mDefaultContainer));
                        } else {
                            Intent service = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
                            mContext.bindServiceAsUser(service, mDefContainerConn,
                                    Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
                        }
                    }
                    break;
                }
                case MSG_CONNECTED: {
                    final IMediaContainerService imcs = (IMediaContainerService) msg.obj;
                    measureExactStorage(imcs);
                    break;
                }
                case MSG_DISCONNECT: {
                    synchronized (mLock) {
                        if (mBound) {
                            mBound = false;
                            mContext.unbindService(mDefContainerConn);
                        }
                    }
                    break;
                }
                case MSG_COMPLETED: {
                    mCached = (MeasurementDetails) msg.obj;
                    mMainHandler.obtainMessage(0, mCached).sendToTarget();
                    break;
                }
                case MSG_INVALIDATE: {
                    mCached = null;
                    break;
                }
            }
        }
    }

    private void measureExactStorage(IMediaContainerService imcs) {
        final UserManager userManager = mContext.getSystemService(UserManager.class);
        final PackageManager packageManager = mContext.getPackageManager();

        final List<UserInfo> users = userManager.getUsers();
        final List<UserInfo> currentProfiles = userManager.getEnabledProfiles(
                ActivityManager.getCurrentUser());

        final MeasurementDetails details = new MeasurementDetails();
        final Message finished = mMeasurementHandler.obtainMessage(MeasurementHandler.MSG_COMPLETED,
                details);

        if (mVolume == null || !mVolume.isMountedReadable()) {
            finished.sendToTarget();
            return;
        }

        if (mSharedVolume != null && mSharedVolume.isMountedReadable()) {
            for (UserInfo currentUserInfo : currentProfiles) {
                final int userId = currentUserInfo.id;
                final File basePath = mSharedVolume.getPathForUser(userId);
                HashMap<String, Long> mediaMap = new HashMap<>(sMeasureMediaTypes.size());
                details.mediaSize.put(userId, mediaMap);

                // Measure media types for emulated storage, or for primary physical
                // external volume
                for (String type : sMeasureMediaTypes) {
                    final File path = new File(basePath, type);
                    final long size = getDirectorySize(imcs, path);
                    mediaMap.put(type, size);
                }

                // Measure misc files not counted under media
                addValue(details.miscSize, userId, measureMisc(imcs, basePath));
            }

            if (mSharedVolume.getType() == VolumeInfo.TYPE_EMULATED) {
                // Measure total emulated storage of all users; internal apps data
                // will be spliced in later
                for (UserInfo user : users) {
                    final File userPath = mSharedVolume.getPathForUser(user.id);
                    final long size = getDirectorySize(imcs, userPath);
                    addValue(details.usersSize, user.id, size);
                }
            }
        }

        final File file = mVolume.getPath();
        if (file != null) {
            details.totalSize = file.getTotalSpace();
            details.availSize = file.getFreeSpace();
        }

        // Measure all apps hosted on this volume for all users
        if (mVolume.getType() == VolumeInfo.TYPE_PRIVATE) {
            final List<ApplicationInfo> apps = packageManager.getInstalledApplications(
                    PackageManager.GET_UNINSTALLED_PACKAGES
                    | PackageManager.GET_DISABLED_COMPONENTS);

            final List<ApplicationInfo> volumeApps = new ArrayList<>();
            for (ApplicationInfo app : apps) {
                if (Objects.equals(app.volumeUuid, mVolume.getFsUuid())) {
                    volumeApps.add(app);
                }
            }

            final int count = users.size() * volumeApps.size();
            if (count == 0) {
                finished.sendToTarget();
                return;
            }

            final StatsObserver observer = new StatsObserver(true, details,
                    ActivityManager.getCurrentUser(), currentProfiles, finished, count);
            for (UserInfo user : users) {
                for (ApplicationInfo app : volumeApps) {
                    packageManager.getPackageSizeInfoAsUser(app.packageName, user.id, observer);
                }
            }

        } else {
            finished.sendToTarget();
            return;
        }
    }

    private static long getDirectorySize(IMediaContainerService imcs, File path) {
        try {
            final long size = imcs.calculateDirectorySize(path.toString());
            Log.d(TAG, "getDirectorySize(" + path + ") returned " + size);
            return size;
        } catch (Exception e) {
            Log.w(TAG, "Could not read memory from default container service for " + path, e);
            return 0;
        }
    }

    private long measureMisc(IMediaContainerService imcs, File dir) {
        final File[] files = dir.listFiles();
        if (ArrayUtils.isEmpty(files)) return 0;

        // Get sizes of all top level nodes except the ones already computed
        long miscSize = 0;
        for (File file : files) {
            final String name = file.getName();
            if (sMeasureMediaTypes.contains(name)) {
                continue;
            }

            if (file.isFile()) {
                miscSize += file.length();
            } else if (file.isDirectory()) {
                miscSize += getDirectorySize(imcs, file);
            }
        }
        return miscSize;
    }

    private static void addValue(SparseLongArray array, int key, long value) {
        array.put(key, array.get(key) + value);
    }

    private static void addValueIfKeyExists(SparseLongArray array, int key, long value) {
        final int index = array.indexOfKey(key);
        if (index >= 0) {
            array.put(key, array.valueAt(index) + value);
        }
    }
}
