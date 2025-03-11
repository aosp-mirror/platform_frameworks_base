/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.pinner;

import static android.app.ActivityManager.UID_OBSERVER_ACTIVE;
import static android.app.ActivityManager.UID_OBSERVER_GONE;
import static android.os.Process.SYSTEM_UID;

import static com.android.server.flags.Flags.pinGlobalQuota;
import static com.android.server.flags.Flags.pinWebview;

import android.annotation.EnforcePermission;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.app.UidObserver;
import android.app.pinner.IPinnerService;
import android.app.pinner.PinnedFileStat;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.provider.MediaStore;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;

import dalvik.system.DexFile;
import dalvik.system.VMRuntime;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import sun.misc.Unsafe;

/**
 * <p>PinnerService pins important files for key processes in memory.</p>
 * <p>Files to pin are specified in the config_defaultPinnerServiceFiles
 * overlay.</p>
 * <p>Pin the default camera application if specified in config_pinnerCameraApp.</p>
 * <p>(Optional) Pin experimental carveout regions based on DeviceConfig flags.</p>
 */
public final class PinnerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "PinnerService";

    private static final String PIN_META_FILENAME = "pinlist.meta";
    private static final int PAGE_SIZE = (int) Os.sysconf(OsConstants._SC_PAGESIZE);
    private static final int MATCH_FLAGS = PackageManager.MATCH_DEFAULT_ONLY
            | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

    private static final int KEY_CAMERA = 0;
    private static final int KEY_HOME = 1;
    private static final int KEY_ASSISTANT = 2;

    // Pin using pinlist.meta when pinning apps.
    private static boolean PROP_PIN_PINLIST =
            SystemProperties.getBoolean("pinner.use_pinlist", true);

    public static final String ANON_REGION_STAT_NAME = "[anon]";

    private static final String SYSTEM_GROUP_NAME = "system";

    @IntDef({KEY_CAMERA, KEY_HOME, KEY_ASSISTANT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AppKey {}

    private final Context mContext;
    private final Injector mInjector;
    private final DeviceConfigInterface mDeviceConfigInterface;
    private final ActivityTaskManagerInternal mAtmInternal;
    private final ActivityManagerInternal mAmInternal;
    private final IActivityManager mAm;
    private final UserManager mUserManager;

    /** The list of the statically pinned files. */
    @GuardedBy("this")
    private final ArrayMap<String, PinnedFile> mPinnedFiles = new ArrayMap<>();

    /** The list of the pinned apps. This is a map from {@link AppKey} to a pinned app. */
    @GuardedBy("this")
    private final ArrayMap<Integer, PinnedApp> mPinnedApps = new ArrayMap<>();

    /**
     * The list of the pinned apps that need to be repinned as soon as the all processes of a given
     * uid are no longer active. Note that with background dex opt, the new dex/vdex files are only
     * loaded into the processes once it restarts. So in case background dex opt recompiled these
     * files, we still need to keep the old ones pinned until the processes restart.
     * <p>
     * This is a map from uid to {@link AppKey}
     */
    @GuardedBy("this")
    private final ArrayMap<Integer, Integer> mPendingRepin = new ArrayMap<>();

    /**
     * A set of {@link AppKey} that are configured to be pinned.
     */
    @GuardedBy("this") private
    ArraySet<Integer> mPinKeys;

    // Note that we don't use the `_BOOT` namespace for anonymous pinnings, as we want
    // them to be responsive to dynamic flag changes for experimentation.
    private static final String DEVICE_CONFIG_NAMESPACE_ANON_SIZE =
            DeviceConfig.NAMESPACE_RUNTIME_NATIVE;
    private static final String DEVICE_CONFIG_KEY_ANON_SIZE = "pin_shared_anon_size";
    private static final long DEFAULT_ANON_SIZE =
            SystemProperties.getLong("pinner.pin_shared_anon_size", 0);
    private static final long MAX_ANON_SIZE = 2L * (1L << 30); // 2GB
    private long mPinAnonSize;
    private long mPinAnonAddress;
    private long mCurrentlyPinnedAnonSize;

    // Resource-configured pinner flags;
    private final boolean mConfiguredToPinCamera;
    private final int mConfiguredCameraPinBytes;
    private final int mConfiguredHomePinBytes;
    private final boolean mConfiguredToPinAssistant;
    private final int mConfiguredAssistantPinBytes;
    private final int mConfiguredWebviewPinBytes;

    // This is the percentage of total device memory that will be used to set the global quota.
    private final int mConfiguredMaxPinnedMemoryPercentage;

    // This is the global pinner quota that can be pinned.
    private long mConfiguredMaxPinnedMemory;

    // This is the currently pinned memory.
    private long mCurrentPinnedMemory = 0;

    private BinderService mBinderService;
    private PinnerHandler mPinnerHandler = null;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // If an app has updated, update pinned files accordingly.
            if (Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction())) {
                Uri packageUri = intent.getData();
                String packageName = packageUri.getSchemeSpecificPart();
                ArraySet<String> updatedPackages = new ArraySet<>();
                updatedPackages.add(packageName);
                update(updatedPackages, true /* force */);
            }
        }
    };

    private final DeviceConfig.OnPropertiesChangedListener mDeviceConfigAnonSizeListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    if (DEVICE_CONFIG_NAMESPACE_ANON_SIZE.equals(properties.getNamespace())
                            && properties.getKeyset().contains(DEVICE_CONFIG_KEY_ANON_SIZE)) {
                        refreshPinAnonConfig();
                    }
                }
            };

    /** Utility class for testing. */
    @VisibleForTesting
    public static class Injector {
        protected DeviceConfigInterface getDeviceConfigInterface() {
            return DeviceConfigInterface.REAL;
        }

        protected void publishBinderService(PinnerService service, Binder binderService) {
            service.publishBinderService("pinner", binderService);
        }

        protected PinnedFile pinFileInternal(PinnerService service, String fileToPin,
                long maxBytesToPin, boolean attemptPinIntrospection) {
            return service.pinFileInternal(fileToPin, maxBytesToPin, attemptPinIntrospection);
        }
    }

    public PinnerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    public PinnerService(Context context, Injector injector) {
        super(context);

        mContext = context;
        mInjector = injector;
        mDeviceConfigInterface = mInjector.getDeviceConfigInterface();
        mConfiguredToPinCamera = context.getResources().getBoolean(
                com.android.internal.R.bool.config_pinnerCameraApp);
        mConfiguredCameraPinBytes = context.getResources().getInteger(
                com.android.internal.R.integer.config_pinnerCameraPinBytes);
        mConfiguredAssistantPinBytes = context.getResources().getInteger(
                com.android.internal.R.integer.config_pinnerAssistantPinBytes);
        mConfiguredHomePinBytes = context.getResources().getInteger(
                com.android.internal.R.integer.config_pinnerHomePinBytes);
        mConfiguredToPinAssistant = context.getResources().getBoolean(
                com.android.internal.R.bool.config_pinnerAssistantApp);
        mConfiguredWebviewPinBytes = context.getResources().getInteger(
                com.android.internal.R.integer.config_pinnerWebviewPinBytes);
        mConfiguredMaxPinnedMemoryPercentage = context.getResources().getInteger(
                com.android.internal.R.integer.config_pinnerMaxPinnedMemoryPercentage);

        mPinKeys = createPinKeys();
        mPinnerHandler = new PinnerHandler(BackgroundThread.get().getLooper());

        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        mAm = ActivityManager.getService();

        mUserManager = mContext.getSystemService(UserManager.class);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);

        registerUidListener();
        registerUserSetupCompleteListener();

        mDeviceConfigInterface.addOnPropertiesChangedListener(DEVICE_CONFIG_NAMESPACE_ANON_SIZE,
                new HandlerExecutor(mPinnerHandler), mDeviceConfigAnonSizeListener);
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.i(TAG, "Starting PinnerService");
        }
        mConfiguredMaxPinnedMemory =
                (Process.getTotalMemory()
                        * Math.clamp(mConfiguredMaxPinnedMemoryPercentage, 0, 100))
                / 100;
        mBinderService = new BinderService();
        mInjector.publishBinderService(this, mBinderService);
        publishLocalService(PinnerService.class, this);

        mPinnerHandler.obtainMessage(PinnerHandler.PIN_ONSTART_MSG).sendToTarget();
        sendPinAppsMessage(UserHandle.USER_SYSTEM);
    }

    /**
     * Repin apps on user switch.
     * <p>
     * If more than one user is using the device each user may set a different preference for the
     * individual apps. Make sure that user's preference is pinned into memory.
     */
    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        int userId = to.getUserIdentifier();
        if (!mUserManager.isManagedProfile(userId)) {
            sendPinAppsMessage(userId);
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        final int userId = user.getUserIdentifier();
        if (userId != UserHandle.USER_SYSTEM && !mUserManager.isManagedProfile(userId)) {
            // App pinning for the system should have already been triggered from onStart().
            sendPinAppsMessage(userId);
        }
    }

    /**
     * Update the currently pinned files.
     * Specifically, this only updates pinning for the apps that need to be pinned.
     * The other files pinned in onStart will not need to be updated.
     */
    public void update(ArraySet<String> updatedPackages, boolean force) {
        ArraySet<Integer> pinKeys = getPinKeys();
        int currentUser = ActivityManager.getCurrentUser();
        for (int i = pinKeys.size() - 1; i >= 0; i--) {
            int key = pinKeys.valueAt(i);
            ApplicationInfo info = getInfoForKey(key, currentUser);
            if (info != null && updatedPackages.contains(info.packageName)) {
                Slog.i(TAG, "Updating pinned files for " + info.packageName + " force=" + force);
                sendPinAppMessage(key, currentUser, force);
            }
        }
    }

    /** Returns information about pinned files and sizes for StatsPullAtomService. */
    public List<PinnedFileStats> dumpDataForStatsd() {
        List<PinnedFileStats> pinnedFileStats = new ArrayList<>();
        synchronized (PinnerService.this) {
            for (PinnedFile pinnedFile : mPinnedFiles.values()) {
                pinnedFileStats.add(new PinnedFileStats(SYSTEM_UID, pinnedFile));
            }

            for (int key : mPinnedApps.keySet()) {
                PinnedApp app = mPinnedApps.get(key);
                for (PinnedFile pinnedFile : mPinnedApps.get(key).mFiles) {
                    pinnedFileStats.add(new PinnedFileStats(app.uid, pinnedFile));
                }
            }
        }
        return pinnedFileStats;
    }

    /** Wrapper class for statistics for a pinned file. */
    public static class PinnedFileStats {
        public final int uid;
        public final String filename;
        public final int sizeKb;

        protected PinnedFileStats(int uid, PinnedFile file) {
            this.uid = uid;
            this.filename = file.fileName.substring(file.fileName.lastIndexOf('/') + 1);
            this.sizeKb = (int) file.bytesPinned / 1024;
        }
    }

    /**
     * Handler for on start pinning message
     */
    private void handlePinOnStart() {
        // Files to pin come from the overlay and can be specified per-device config
        String[] filesToPin = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_defaultPinnerServiceFiles);
        // Continue trying to pin each file even if we fail to pin some of them
        for (String fileToPin : filesToPin) {
            pinFile(fileToPin, Integer.MAX_VALUE, /*appInfo=*/null, /*groupName=*/SYSTEM_GROUP_NAME,
                    true);
        }

        refreshPinAnonConfig();
    }

    /**
     * Registers a listener to repin the home app when user setup is complete, as the home intent
     * initially resolves to setup wizard, but once setup is complete, it will resolve to the
     * regular home app.
     */
    private void registerUserSetupCompleteListener() {
        Uri userSetupCompleteUri = Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE);
        mContext.getContentResolver().registerContentObserver(
                userSetupCompleteUri, false, new ContentObserver(null) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (userSetupCompleteUri.equals(uri)) {
                            if (mConfiguredHomePinBytes > 0) {
                                sendPinAppMessage(KEY_HOME, ActivityManager.getCurrentUser(),
                                        true /* force */);
                            }
                        }
                    }
                }, UserHandle.USER_ALL);
    }

    private void registerUidListener() {
        try {
            mAm.registerUidObserver(new UidObserver() {
                @Override
                public void onUidGone(int uid, boolean disabled) {
                    mPinnerHandler.sendMessage(PooledLambda.obtainMessage(
                            PinnerService::handleUidGone, PinnerService.this, uid));
                }

                @Override
                public void onUidActive(int uid) {
                    mPinnerHandler.sendMessage(PooledLambda.obtainMessage(
                            PinnerService::handleUidActive, PinnerService.this, uid));
                }
            }, UID_OBSERVER_GONE | UID_OBSERVER_ACTIVE, 0, null);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register uid observer", e);
        }
    }

    private void handleUidGone(int uid) {
        updateActiveState(uid, false /* active */);
        int key;
        synchronized (this) {
            // In case we have a pending repin, repin now. See mPendingRepin for more information.
            key = mPendingRepin.getOrDefault(uid, -1);
            if (key == -1) {
                return;
            }
            mPendingRepin.remove(uid);
        }
        pinApp(key, ActivityManager.getCurrentUser(), false /* force */);
    }

    private void handleUidActive(int uid) {
        updateActiveState(uid, true /* active */);
    }

    private void updateActiveState(int uid, boolean active) {
        synchronized (this) {
            for (int i = mPinnedApps.size() - 1; i >= 0; i--) {
                PinnedApp app = mPinnedApps.valueAt(i);
                if (app.uid == uid) {
                    app.active = active;
                }
            }
        }
    }

    private void unpinApps() {
        ArraySet<Integer> pinKeys = getPinKeys();
        for (int i = pinKeys.size() - 1; i >= 0; i--) {
            int key = pinKeys.valueAt(i);
            unpinApp(key);
        }
    }

    private void unpinApp(@AppKey int key) {
        ArrayList<PinnedFile> pinnedAppFiles;
        synchronized (this) {
            PinnedApp app = mPinnedApps.get(key);
            if (app == null) {
                return;
            }
            mPinnedApps.remove(key);
            pinnedAppFiles = new ArrayList<>(app.mFiles);
        }
        for (PinnedFile pinnedFile : pinnedAppFiles) {
            unpinFile(pinnedFile.fileName);
        }
    }

    private boolean isResolverActivity(ActivityInfo info) {
        return ResolverActivity.class.getName().equals(info.name);
    }

    public int getWebviewPinQuota() {
        if (!pinWebview()) {
            return 0;
        }
        int quota = mConfiguredWebviewPinBytes;
        int overrideQuota = SystemProperties.getInt("pinner.pin_webview_size", -1);
        if (overrideQuota != -1) {
            // Quota was overridden
            quota = overrideQuota;
        }
        return quota;
    }

    private ApplicationInfo getCameraInfo(int userHandle) {
        Intent cameraIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        ApplicationInfo info = getApplicationInfoForIntent(
                cameraIntent, userHandle, false /* defaultToSystemApp */);

        // If the STILL_IMAGE_CAMERA intent doesn't resolve, try the _SECURE intent.
        // We don't use _SECURE first because it will never get set on a device
        // without File-based Encryption. But if the user has only set the intent
        // before unlocking their device, we may still be able to identify their
        // preference using this intent.
        if (info == null) {
            cameraIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
            info = getApplicationInfoForIntent(
                    cameraIntent, userHandle, false /* defaultToSystemApp */);
        }

        // If the _SECURE intent doesn't resolve, try the original intent but request
        // the system app for camera if there was more than one result.
        if (info == null) {
            cameraIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            info = getApplicationInfoForIntent(
                    cameraIntent, userHandle, true /* defaultToSystemApp */);
        }
        return info;
    }

    private ApplicationInfo getHomeInfo(int userHandle) {
        Intent intent = mAtmInternal.getHomeIntent();
        return getApplicationInfoForIntent(intent, userHandle, false);
    }

    private ApplicationInfo getAssistantInfo(int userHandle) {
        Intent intent = new Intent(Intent.ACTION_ASSIST);
        return getApplicationInfoForIntent(intent, userHandle, true);
    }

    private ApplicationInfo getApplicationInfoForIntent(
            Intent intent, int userHandle, boolean defaultToSystemApp) {
        if (intent == null) {
            return null;
        }

        ResolveInfo resolveInfo =
                mContext.getPackageManager().resolveActivityAsUser(intent, MATCH_FLAGS, userHandle);

        // If this intent can resolve to only one app, choose that one.
        // Otherwise, if we've requested to default to the system app, return it;
        // if we have not requested that default, return null if there's more than one option.
        // If there's more than one system app, return null since we don't know which to pick.
        if (resolveInfo == null) {
            return null;
        }

        if (!isResolverActivity(resolveInfo.activityInfo)) {
            return resolveInfo.activityInfo.applicationInfo;
        }

        if (defaultToSystemApp) {
            List<ResolveInfo> infoList = mContext.getPackageManager().queryIntentActivitiesAsUser(
                    intent, MATCH_FLAGS, userHandle);
            ApplicationInfo systemAppInfo = null;
            for (ResolveInfo info : infoList) {
                if ((info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    if (systemAppInfo == null) {
                        systemAppInfo = info.activityInfo.applicationInfo;
                    } else {
                        // If there's more than one system app, return null due to ambiguity.
                        return null;
                    }
                }
            }
            return systemAppInfo;
        }

        return null;
    }

    private void sendPinAppsMessage(int userHandle) {
        mPinnerHandler.sendMessage(
                PooledLambda.obtainMessage(PinnerService::pinApps, this, userHandle));
    }

    private void sendPinAppsWithUpdatedKeysMessage(int userHandle) {
        mPinnerHandler.sendMessage(PooledLambda.obtainMessage(
                PinnerService::pinAppsWithUpdatedKeys, this, userHandle));
    }
    private void sendUnpinAppsMessage() {
        mPinnerHandler.sendMessage(PooledLambda.obtainMessage(PinnerService::unpinApps, this));
    }

    private ArraySet<Integer> createPinKeys() {
        ArraySet<Integer> pinKeys = new ArraySet<>();
        // Pin the camera application. Default to the system property only if the experiment
        // phenotype property is not set.
        boolean shouldPinCamera = mConfiguredToPinCamera
                && mDeviceConfigInterface.getBoolean(DeviceConfig.NAMESPACE_RUNTIME_NATIVE_BOOT,
                        "pin_camera", SystemProperties.getBoolean("pinner.pin_camera", true));
        if (shouldPinCamera) {
            pinKeys.add(KEY_CAMERA);
        } else if (DEBUG) {
            Slog.i(TAG, "Pinner - skip pinning camera app");
        }

        if (mConfiguredHomePinBytes > 0) {
            pinKeys.add(KEY_HOME);
        }
        if (mConfiguredToPinAssistant) {
            pinKeys.add(KEY_ASSISTANT);
        }

        return pinKeys;
    }

    private synchronized ArraySet<Integer> getPinKeys() {
        return mPinKeys;
    }

    private void pinApps(int userHandle) {
        pinAppsInternal(userHandle, false);
    }

    private void pinAppsWithUpdatedKeys(int userHandle) {
        pinAppsInternal(userHandle, true);
    }

    /**
     * @param updateKeys True if the pinned app list has to be updated. This is true only when
     *                   "pinner repin" shell command is requested.
     */
    private void pinAppsInternal(int userHandle, boolean updateKeys) {
        if (updateKeys) {
            ArraySet<Integer> newKeys = createPinKeys();
            synchronized (this) {
                // This code path demands preceding unpinApps() call.
                if (!mPinnedApps.isEmpty()) {
                    Slog.e(TAG,
                            "Attempted to update a list of apps, "
                                    + "but apps were already pinned. Skipping.");
                    return;
                }

                mPinKeys = newKeys;
            }
        }

        ArraySet<Integer> currentPinKeys = getPinKeys();
        for (int i = currentPinKeys.size() - 1; i >= 0; i--) {
            int key = currentPinKeys.valueAt(i);
            pinApp(key, userHandle, true /* force */);
        }
    }

    /**
     * @see #pinApp(int, int, boolean)
     */
    private void sendPinAppMessage(int key, int userHandle, boolean force) {
        mPinnerHandler.sendMessage(
                PooledLambda.obtainMessage(PinnerService::pinApp, this, key, userHandle, force));
    }

    /**
     * Pins an app of a specific type {@code key}.
     *
     * @param force If false, this will not repin the app if it's currently active. See
     *              {@link #mPendingRepin}.
     */
    private void pinApp(int key, int userHandle, boolean force) {
        int uid = getUidForKey(key);

        // In case the app is currently active, don't repin until next process restart. See
        // mPendingRepin for more information.
        if (!force && uid != -1) {
            synchronized (this) {
                mPendingRepin.put(uid, key);
            }
            return;
        }
        ApplicationInfo info = getInfoForKey(key, userHandle);
        unpinApp(key);
        if (info != null) {
            pinAppInternal(key, info);
        }
    }

    /**
     * Checks whether the pinned package with {@code key} is active or not.

     * @return The uid of the pinned app, or {@code -1} otherwise.
     */
    private int getUidForKey(@AppKey int key) {
        synchronized (this) {
            PinnedApp existing = mPinnedApps.get(key);
            return existing != null && existing.active ? existing.uid : -1;
        }
    }

    /**
     * Retrieves the current application info for the given app type.
     *
     * @param key The app type to retrieve the info for.
     * @param userHandle The user id of the current user.
     */
    private @Nullable ApplicationInfo getInfoForKey(@AppKey int key, int userHandle) {
        switch (key) {
            case KEY_CAMERA:
                return getCameraInfo(userHandle);
            case KEY_HOME:
                return getHomeInfo(userHandle);
            case KEY_ASSISTANT:
                return getAssistantInfo(userHandle);
            default:
                return null;
        }
    }

    /**
     * @return The app type name for {@code key}.
     */
    private String getNameForKey(@AppKey int key) {
        switch (key) {
            case KEY_CAMERA:
                return "Camera";
            case KEY_HOME:
                return "Home";
            case KEY_ASSISTANT:
                return "Assistant";
            default:
                return "";
        }
    }

    /**
     * Handle any changes in the anon region pinner config.
     */
    private void refreshPinAnonConfig() {
        long newPinAnonSize = mDeviceConfigInterface.getLong(
                DEVICE_CONFIG_NAMESPACE_ANON_SIZE, DEVICE_CONFIG_KEY_ANON_SIZE, DEFAULT_ANON_SIZE);
        newPinAnonSize = Math.max(0, Math.min(newPinAnonSize, MAX_ANON_SIZE));
        if (newPinAnonSize != mPinAnonSize) {
            mPinAnonSize = newPinAnonSize;
            pinAnonRegion();
        }
    }

    /**
     * Pin an empty anonymous region. This should only be used for ablation experiments.
     */
    private void pinAnonRegion() {
        if (mPinAnonSize == 0) {
            Slog.d(TAG, "pinAnonRegion: releasing pinned region");
            unpinAnonRegion();
            return;
        }
        long alignedPinSize = mPinAnonSize;
        if (alignedPinSize % PAGE_SIZE != 0) {
            alignedPinSize -= alignedPinSize % PAGE_SIZE;
            Slog.e(TAG, "pinAnonRegion: aligning size to " + alignedPinSize);
        }
        if (mPinAnonAddress != 0) {
            if (mCurrentlyPinnedAnonSize == alignedPinSize) {
                Slog.d(TAG, "pinAnonRegion: already pinned region of size " + alignedPinSize);
                return;
            }
            Slog.d(TAG, "pinAnonRegion: resetting pinned region for new size " + alignedPinSize);
            unpinAnonRegion();
        }
        long address = 0;
        try {
            // Map as SHARED to avoid changing rss.anon for system_server (per /proc/*/status).
            // The mapping is visible in other rss metrics, and as private dirty in smaps/meminfo.
            address = Os.mmap(0, alignedPinSize, OsConstants.PROT_READ | OsConstants.PROT_WRITE,
                    OsConstants.MAP_SHARED | OsConstants.MAP_ANONYMOUS, new FileDescriptor(),
                    /*offset=*/0);

            Unsafe tempUnsafe = null;
            Class<sun.misc.Unsafe> clazz = sun.misc.Unsafe.class;
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                Object obj = f.get(null);
                if (clazz.isInstance(obj)) {
                    tempUnsafe = clazz.cast(obj);
                }
            }
            if (tempUnsafe == null) {
                throw new Exception("Couldn't get Unsafe");
            }
            Method setMemory = clazz.getMethod("setMemory", long.class, long.class, byte.class);
            setMemory.invoke(tempUnsafe, address, alignedPinSize, (byte) 1);
            Os.mlock(address, alignedPinSize);
            mCurrentlyPinnedAnonSize = alignedPinSize;
            mPinAnonAddress = address;
            address = -1;
            Slog.w(TAG, "pinAnonRegion success, size=" + mCurrentlyPinnedAnonSize);
        } catch (Exception ex) {
            Slog.e(TAG, "Could not pin anon region of size " + alignedPinSize, ex);
            return;
        } finally {
            if (address >= 0) {
                PinnerUtils.safeMunmap(address, alignedPinSize);
            }
        }
    }

    private void unpinAnonRegion() {
        if (mPinAnonAddress != 0) {
            PinnerUtils.safeMunmap(mPinAnonAddress, mCurrentlyPinnedAnonSize);
        }
        mPinAnonAddress = 0;
        mCurrentlyPinnedAnonSize = 0;
    }

    /**
     * @return The maximum amount of bytes to be pinned for an app of type {@code key}.
     */
    private int getSizeLimitForKey(@AppKey int key) {
        switch (key) {
            case KEY_CAMERA:
                return mConfiguredCameraPinBytes;
            case KEY_HOME:
                return mConfiguredHomePinBytes;
            case KEY_ASSISTANT:
                return mConfiguredAssistantPinBytes;
            default:
                return 0;
        }
    }

    /**
     * Retrieves remaining quota for pinner service, once it reaches 0 it will no longer
     * pin any file.
     */
    private long getAvailableGlobalQuota() {
        return mConfiguredMaxPinnedMemory - mCurrentPinnedMemory;
    }

    /**
     * Pins an application.
     *
     * @param key The key of the app to pin.
     * @param appInfo The corresponding app info.
     */
    private void pinAppInternal(@AppKey int key, @Nullable ApplicationInfo appInfo) {
        if (appInfo == null) {
            return;
        }

        PinnedApp pinnedApp = new PinnedApp(appInfo);
        synchronized (this) {
            mPinnedApps.put(key, pinnedApp);
        }

        // pin APK
        final int pinSizeLimit = getSizeLimitForKey(key);
        List<String> apks = new ArrayList<>();
        apks.add(appInfo.sourceDir);

        if (appInfo.splitSourceDirs != null) {
            for (String splitApk : appInfo.splitSourceDirs) {
                apks.add(splitApk);
            }
        }

        long apkPinSizeLimit = pinSizeLimit;

        for (String apk : apks) {
            if (apkPinSizeLimit <= 0) {
                Slog.w(TAG, "Reached to the pin size limit. Skipping: " + apk);
                // Continue instead of break to print all skipped APK names.
                continue;
            }

            String pinGroup = getNameForKey(key);
            boolean shouldPinDeps = apk.equals(appInfo.sourceDir);
            PinnedFile pf = pinFile(apk, apkPinSizeLimit, appInfo, pinGroup, shouldPinDeps);
            if (pf == null) {
                Slog.e(TAG, "Failed to pin " + apk);
                continue;
            }

            if (DEBUG) {
                Slog.i(TAG, "Pinned " + pf.fileName);
            }
            synchronized (this) {
                pinnedApp.mFiles.add(pf);
            }

            apkPinSizeLimit -= pf.bytesPinned;
        }
    }

    /**
     * Pin file or apk to memory.
     *
     * Prefer to use this method instead of {@link #pinFileInternal(String, int, boolean)} as it
     * takes care of accounting and if pinning an apk, it also pins any extra optimized art files
     * that related to the file but not within itself.
     *
     * @param fileToPin File to pin
     * @param bytesRequestedToPin maximum bytes requested to pin for {@code fileToPin}.
     * @param pinOptimizedDeps whether optimized dependencies such as odex,vdex, etc be pinned.
     *                         Note: {@code bytesRequestedToPin} limit will not apply to optimized
     *                         dependencies pinned, only global quotas will apply instead.
     * @return pinned file
     */
    public PinnedFile pinFile(String fileToPin, long bytesRequestedToPin,
            @Nullable ApplicationInfo appInfo, @Nullable String groupName,
            boolean pinOptimizedDeps) {
        PinnedFile existingPin;
        synchronized (this) {
            existingPin = mPinnedFiles.get(fileToPin);
        }
        if (existingPin != null) {
            if (existingPin.bytesPinned == bytesRequestedToPin) {
                // Duplicate pin requesting same amount of bytes, lets just bail out.
                return null;
            } else {
                // User decided to pin a different amount of bytes than currently pinned
                // so this is a valid pin request. Unpin the previous version before repining.
                if (DEBUG) {
                    Slog.d(TAG, "Unpinning file prior to repin: " + fileToPin);
                }
                unpinFile(fileToPin);
            }
        }

        long remainingQuota = getAvailableGlobalQuota();

        if (pinGlobalQuota()) {
            if (remainingQuota <= 0) {
                Slog.w(TAG, "Reached pin quota, skipping file: " + fileToPin);
                return null;
            }
            bytesRequestedToPin = Math.min(bytesRequestedToPin, remainingQuota);
        }

        boolean isApk = fileToPin.endsWith(".apk");

        PinnedFile pf = mInjector.pinFileInternal(this, fileToPin, bytesRequestedToPin,
                /*attemptPinIntrospection=*/isApk);
        if (pf == null) {
            Slog.e(TAG, "Failed to pin file = " + fileToPin);
            return null;
        }
        pf.groupName = groupName != null ? groupName : "";

        mCurrentPinnedMemory += pf.bytesPinned;

        synchronized (this) {
            mPinnedFiles.put(pf.fileName, pf);
        }

        if (pinOptimizedDeps) {
            mCurrentPinnedMemory +=
                    pinOptimizedDexDependencies(pf, getAvailableGlobalQuota(), appInfo);
        }

        return pf;
    }

    /**
     * Pin any dependency optimized files generated by ART.
     * @param pinnedFile An already pinned file whose dependencies we want pinned.
     * @param maxBytesToPin Maximum amount of bytes to pin.
     * @param appInfo Used to determine the ABI in case the application has one custom set, when set
     *                to null it will use the default supported ABI by the device.
     * @return total bytes pinned.
     */
    private long pinOptimizedDexDependencies(
            PinnedFile pinnedFile, long maxBytesToPin, @Nullable ApplicationInfo appInfo) {
        if (pinnedFile == null) {
            return 0;
        }

        long bytesPinned = 0;
        if (pinnedFile.fileName.endsWith(".jar") | pinnedFile.fileName.endsWith(".apk")) {
            String abi = null;
            if (appInfo != null) {
                abi = appInfo.primaryCpuAbi;
            }
            if (abi == null) {
                abi = Build.SUPPORTED_ABIS[0];
            }
            // Check whether the runtime has compilation artifacts to pin.
            String arch = VMRuntime.getInstructionSet(abi);
            String[] files = null;
            try {
                files = DexFile.getDexFileOutputPaths(pinnedFile.fileName, arch);
            } catch (IOException ioe) {
            }
            if (files == null) {
                return bytesPinned;
            }
            for (String file : files) {
                // Unpin if it was already pinned prior to re-pinning.
                unpinFile(file);

                PinnedFile df = mInjector.pinFileInternal(this, file, maxBytesToPin,
                        /*attemptPinIntrospection=*/false);
                if (df == null) {
                    Slog.i(TAG, "Failed to pin ART file = " + file);
                    return bytesPinned;
                }
                df.groupName = pinnedFile.groupName;
                pinnedFile.pinnedDeps.add(df);
                maxBytesToPin -= df.bytesPinned;
                bytesPinned += df.bytesPinned;
                synchronized (this) {
                    mPinnedFiles.put(df.fileName, df);
                }
            }
        }
        return bytesPinned;
    }

    /**
     * mlock length bytes of fileToPin in memory
     *
     * If attemptPinIntrospection is true, then treat the file to pin as a zip file and
     * look for a "pinlist.meta" file in the archive root directory. The structure of this
     * file is a PINLIST_META as described below:
     *
     * <pre>
     *   PINLIST_META: PIN_RANGE*
     *   PIN_RANGE: PIN_START PIN_LENGTH
     *   PIN_START: big endian i32: offset in bytes of pin region from file start
     *   PIN_LENGTH: big endian i32: length of pin region in bytes
     * </pre>
     *
     * (We use big endian because that's what DataInputStream is hardcoded to use.)
     *
     * If attemptPinIntrospection is false, then we use a single implicit PIN_RANGE of (0,
     * maxBytesToPin); that is, we attempt to pin the first maxBytesToPin bytes of the file.
     *
     * After we open a file, we march through the list of pin ranges and attempt to pin
     * each one, stopping after we've pinned maxBytesToPin bytes. (We may truncate the last
     * pinned range to fit.)  In this way, by choosing to emit certain PIN_RANGE pairs
     * before others, file generators can express pins in priority order, making most
     * effective use of the pinned-page quota.
     *
     * N.B. Each PIN_RANGE is clamped to the actual bounds of the file; all inputs have a
     * meaningful interpretation. Also, a range locking a single byte of a page locks the
     * whole page. Any truncated PIN_RANGE at EOF is ignored. Overlapping pinned entries
     * are legal, but each pin of a byte counts toward the pin quota regardless of whether
     * that byte has already been pinned, so the generator of PINLIST_META ought to ensure
     * that ranges are non-overlapping.
     *
     * @param fileToPin Path to file to pin
     * @param maxBytesToPin Maximum number of bytes to pin
     * @param attemptPinIntrospection If true, try to open file as a
     *   zip in order to extract the
     * @return Pinned memory resource owner thing or null on error
     */
    private PinnedFile pinFileInternal(
            String fileToPin, long maxBytesToPin, boolean attemptPinIntrospection) {
        if (DEBUG) {
            Slog.d(TAG, "pin file: " + fileToPin + " use-pinlist: " + attemptPinIntrospection);
        }
        ZipFile fileAsZip = null;
        InputStream pinRangeStream = null;
        try {
            if (attemptPinIntrospection) {
                fileAsZip = maybeOpenZip(fileToPin);
            }

            if (fileAsZip != null) {
                pinRangeStream = maybeOpenPinMetaInZip(fileAsZip, fileToPin);
            }
            boolean use_pinlist = (pinRangeStream != null);
            PinRangeSource pinRangeSource = use_pinlist
                    ? new PinRangeSourceStream(pinRangeStream)
                    : new PinRangeSourceStatic(0, Integer.MAX_VALUE /* will be clipped */);
            PinnedFile pinnedFile = pinFileRanges(fileToPin, maxBytesToPin, pinRangeSource);
            if (pinnedFile != null) {
                pinnedFile.used_pinlist = use_pinlist;
            }
            return pinnedFile;
        } finally {
            PinnerUtils.safeClose(pinRangeStream);
            PinnerUtils.safeClose(fileAsZip); // Also closes any streams we've opened
        }
    }

    /**
     * Attempt to open a file as a zip file. On any sort of corruption, log, swallow the
     * error, and return null.
     */
    private static ZipFile maybeOpenZip(String fileName) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(fileName);
        } catch (IOException ex) {
            Slog.w(TAG, String.format("could not open \"%s\" as zip: pinning as blob", fileName),
                    ex);
        }
        return zip;
    }

    /**
     * Open a pin metadata file in the zip if one is present.
     *
     * @param zipFile Zip file to search
     * @return Open input stream or null on any error
     */
    private static InputStream maybeOpenPinMetaInZip(ZipFile zipFile, String fileName) {
        if (!PROP_PIN_PINLIST) {
            if (DEBUG) {
                Slog.i(TAG, "Pin - skip pinlist.meta in " + fileName);
            }
            return null;
        }

        // Looking at root directory is the old behavior but still some apps rely on it so keeping
        // for backward compatibility. As doing a single item lookup is cheap in the root.
        ZipEntry pinMetaEntry = zipFile.getEntry(PIN_META_FILENAME);

        if (pinMetaEntry == null) {
            // It is usually within an apk's control to include files in assets/ directory
            // so this would be the expected point to have the pinlist.meta coming from.
            // we explicitly avoid doing an exhaustive search because it may be expensive so
            // prefer to have a good known location to retrieve the file.
            pinMetaEntry = zipFile.getEntry("assets/" + PIN_META_FILENAME);
        }

        InputStream pinMetaStream = null;
        if (pinMetaEntry != null) {
            if (DEBUG) {
                Slog.d(TAG, "Found pinlist.meta for " + fileName);
            }
            try {
                pinMetaStream = zipFile.getInputStream(pinMetaEntry);
            } catch (IOException ex) {
                Slog.w(TAG,
                        String.format(
                                "error reading pin metadata \"%s\": pinning as blob", fileName),
                        ex);
            }
        } else {
            Slog.w(TAG,
                    String.format(
                            "Could not find pinlist.meta for \"%s\": pinning as blob", fileName));
        }
        return pinMetaStream;
    }

    /**
     * Helper for pinFile.
     *
     * @param fileToPin Name of file to pin
     * @param maxBytesToPin Maximum number of bytes to pin
     * @param pinRangeSource Read PIN_RANGE entries from this stream to tell us what bytes
     *   to pin.
     * @return PinnedFile or null on error
     */
    private static PinnedFile pinFileRanges(
            String fileToPin, long maxBytesToPin, PinRangeSource pinRangeSource) {
        FileDescriptor fd = new FileDescriptor();
        long address = -1;
        long mapSize = 0;

        try {
            int openFlags = (OsConstants.O_RDONLY | OsConstants.O_CLOEXEC);
            fd = Os.open(fileToPin, openFlags, 0);
            mapSize = (int) Math.min(Os.fstat(fd).st_size, Integer.MAX_VALUE);
            address = Os.mmap(
                    0, mapSize, OsConstants.PROT_READ, OsConstants.MAP_SHARED, fd, /*offset=*/0);

            PinRange pinRange = new PinRange();
            long bytesPinned = 0;

            // We pin at page granularity, so make sure the limit is page-aligned
            if (maxBytesToPin % PAGE_SIZE != 0) {
                maxBytesToPin -= maxBytesToPin % PAGE_SIZE;
            }

            while (bytesPinned < maxBytesToPin && pinRangeSource.read(pinRange)) {
                long pinStart = pinRange.start;
                long pinLength = pinRange.length;
                pinStart = PinnerUtils.clamp(0, pinStart, mapSize);
                pinLength = PinnerUtils.clamp(0, pinLength, mapSize - pinStart);
                pinLength = Math.min(maxBytesToPin - bytesPinned, pinLength);

                // mlock doesn't require the region to be page-aligned, but we snap the
                // lock region to page boundaries anyway so that we don't under-count
                // locking a single byte of a page as a charge of one byte even though the
                // OS will retain the whole page. Thanks to this adjustment, we slightly
                // over-count the pin charge of back-to-back pins touching the same page,
                // but better that than undercounting. Besides: nothing stops pin metafile
                // creators from making the actual regions page-aligned.
                pinLength += pinStart % PAGE_SIZE;
                pinStart -= pinStart % PAGE_SIZE;
                if (pinLength % PAGE_SIZE != 0) {
                    pinLength += PAGE_SIZE - pinLength % PAGE_SIZE;
                }
                pinLength = PinnerUtils.clamp(0, pinLength, maxBytesToPin - bytesPinned);

                if (pinLength > 0) {
                    if (DEBUG) {
                        Slog.d(TAG,
                                String.format("pinning at %s %s bytes of %s", pinStart, pinLength,
                                        fileToPin));
                    }
                    Os.mlock(address + pinStart, pinLength);
                }
                bytesPinned += pinLength;
            }

            PinnedFile pinnedFile = new PinnedFile(address, mapSize, fileToPin, bytesPinned);
            address = -1; // Ownership transferred
            return pinnedFile;
        } catch (ErrnoException ex) {
            Slog.e(TAG, "Could not pin file " + fileToPin, ex);
            return null;
        } finally {
            PinnerUtils.safeClose(fd);
            if (address >= 0) {
                PinnerUtils.safeMunmap(address, mapSize);
            }
        }
    }
    private List<PinnedFile> getAllPinsForGroup(String group) {
        List<PinnedFile> filesInGroup;
        synchronized (this) {
            filesInGroup = mPinnedFiles.values()
                                   .stream()
                                   .filter(pf -> pf.groupName.equals(group))
                                   .toList();
        }
        return filesInGroup;
    }
    public void unpinGroup(String group) {
        List<PinnedFile> pinnedFiles = getAllPinsForGroup(group);
        for (PinnedFile pf : pinnedFiles) {
            unpinFile(pf.fileName);
        }
    }

    /**
     * Unpin a file and its optimized dependencies.
     *
     * @param filename file to unpin.
     * @return number of bytes unpinned, 0 in case of failure or nothing to unpin.
     */
    public long unpinFile(String filename) {
        PinnedFile pinnedFile;
        synchronized (this) {
            pinnedFile = mPinnedFiles.get(filename);
        }
        if (pinnedFile == null) {
            // File not pinned, nothing to do.
            return 0;
        }
        long unpinnedBytes = pinnedFile.bytesPinned;
        pinnedFile.close();
        synchronized (this) {
            if (DEBUG) {
                Slog.d(TAG, "Unpinned file: " + filename);
            }
            mCurrentPinnedMemory -= pinnedFile.bytesPinned;

            mPinnedFiles.remove(pinnedFile.fileName);
            for (PinnedFile dep : pinnedFile.pinnedDeps) {
                if (dep == null) {
                    continue;
                }
                unpinnedBytes -= dep.bytesPinned;
                mCurrentPinnedMemory -= dep.bytesPinned;
                mPinnedFiles.remove(dep.fileName);
                if (DEBUG) {
                    Slog.d(TAG, "Unpinned dependency: " + dep.fileName);
                }
            }
        }

        return unpinnedBytes;
    }

    public List<PinnedFileStat> getPinnerStats() {
        ArrayList<PinnedFileStat> stats = new ArrayList<>();
        Collection<PinnedFile> pinnedFiles;
        synchronized (this) {
            pinnedFiles = mPinnedFiles.values();
        }
        for (PinnedFile pf : pinnedFiles) {
            PinnedFileStat stat = new PinnedFileStat(pf.fileName, pf.bytesPinned, pf.groupName);
            stats.add(stat);
        }
        if (mCurrentlyPinnedAnonSize > 0) {
            stats.add(new PinnedFileStat(
                    ANON_REGION_STAT_NAME, mCurrentlyPinnedAnonSize, ANON_REGION_STAT_NAME));
        }
        return stats;
    }

    public final class BinderService extends IPinnerService.Stub {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw))
                return;
            HashSet<PinnedFile> shownPins = new HashSet<>();
            HashSet<String> shownGroups = new HashSet<>();
            HashSet<String> groupsToPrint = new HashSet<>();
            final double bytesPerMB = 1024 * 1024;
            pw.format("Pinner Configs:\n");
            pw.format("   Total Pinner quota: %d%% of total device memory\n",
                    mConfiguredMaxPinnedMemoryPercentage);
            pw.format("   Maximum Pinner quota: %d bytes (%.2f MB)\n", mConfiguredMaxPinnedMemory,
                    mConfiguredMaxPinnedMemory / bytesPerMB);
            pw.format("   Max Home App Pin Bytes (without deps): %d\n", mConfiguredHomePinBytes);
            pw.format("   Max Assistant App Pin Bytes (without deps): %d\n",
                    mConfiguredAssistantPinBytes);
            pw.format(
                    "   Max Camera App Pin Bytes (without deps): %d\n", mConfiguredCameraPinBytes);
            pw.format("\nPinned Files:\n");
            synchronized (PinnerService.this) {
                long totalSize = 0;

                // We print apps separately from regular pins as they contain extra information that
                // other pins do not.
                for (int key : mPinnedApps.keySet()) {
                    PinnedApp app = mPinnedApps.get(key);
                    pw.print(getNameForKey(key));
                    pw.print(" uid=");
                    pw.print(app.uid);
                    pw.print(" active=");
                    pw.print(app.active);

                    if (!app.mFiles.isEmpty()) {
                        shownGroups.add(app.mFiles.getFirst().groupName);
                    }
                    pw.println();
                    long bytesPinnedForApp = 0;
                    long bytesPinnedForAppDeps = 0;
                    for (PinnedFile pf : mPinnedApps.get(key).mFiles) {
                        pw.print("  ");
                        pw.format("%s pinned:%d bytes (%.2f MB) pinlist:%b\n", pf.fileName,
                                pf.bytesPinned, pf.bytesPinned / bytesPerMB, pf.used_pinlist);
                        totalSize += pf.bytesPinned;
                        bytesPinnedForApp += pf.bytesPinned;
                        shownPins.add(pf);
                        for (PinnedFile dep : pf.pinnedDeps) {
                            pw.print("  ");
                            pw.format("%s pinned:%d bytes (%.2f MB) pinlist:%b (Dependency)\n",
                                    dep.fileName, dep.bytesPinned, dep.bytesPinned / bytesPerMB,
                                    dep.used_pinlist);
                            totalSize += dep.bytesPinned;
                            bytesPinnedForAppDeps += dep.bytesPinned;
                            shownPins.add(dep);
                        }
                    }
                    long bytesPinnedForAppAndDeps = bytesPinnedForApp + bytesPinnedForAppDeps;
                    pw.format("Total Pinned = %d (%.2f MB) [App=%d (%.2f MB), "
                                    + "Dependencies=%d (%.2f MB)]\n\n",
                            bytesPinnedForAppAndDeps, bytesPinnedForAppAndDeps / bytesPerMB,
                            bytesPinnedForApp, bytesPinnedForApp / bytesPerMB,
                            bytesPinnedForAppDeps, bytesPinnedForAppDeps / bytesPerMB);
                }
                pw.println();
                for (PinnedFile pinnedFile : mPinnedFiles.values()) {
                    if (!groupsToPrint.contains(pinnedFile.groupName)
                            && !shownGroups.contains(pinnedFile.groupName)) {
                        groupsToPrint.add(pinnedFile.groupName);
                    }
                }

                // Print all the non app groups.
                for (String group : groupsToPrint) {
                    List<PinnedFile> groupPins = getAllPinsForGroup(group);
                    pw.print("\nGroup:" + group);
                    long bytesPinnedForGroupNoDeps = 0;
                    long bytesPinnedForGroupDeps = 0;
                    pw.println();
                    for (PinnedFile pinnedFile : groupPins) {
                        if (shownPins.contains(pinnedFile)) {
                            // Already displayed and accounted for, skip.
                            continue;
                        }
                        pw.format("  %s pinned: %d bytes (%.2f MB) pinlist:%b\n",
                                pinnedFile.fileName, pinnedFile.bytesPinned,
                                pinnedFile.bytesPinned / bytesPerMB, pinnedFile.used_pinlist);
                        totalSize += pinnedFile.bytesPinned;
                        bytesPinnedForGroupNoDeps += pinnedFile.bytesPinned;
                        shownPins.add(pinnedFile);
                        for (PinnedFile dep : pinnedFile.pinnedDeps) {
                            if (shownPins.contains(dep)) {
                                // Already displayed and accounted for, skip.
                                continue;
                            }
                            pw.print("  ");
                            pw.format("%s pinned:%d bytes (%.2f MB) pinlist:%b (Dependency)\n",
                                    dep.fileName, dep.bytesPinned, dep.bytesPinned / bytesPerMB,
                                    dep.used_pinlist);
                            totalSize += dep.bytesPinned;
                            bytesPinnedForGroupDeps += dep.bytesPinned;
                            shownPins.add(dep);
                        }
                    }
                    long bytesPinnedForGroup = bytesPinnedForGroupNoDeps + bytesPinnedForGroupDeps;
                    pw.format("Total Pinned = %d (%.2f MB) [Main=%d (%.2f MB), "
                                    + "Dependencies=%d (%.2f MB)]\n\n",
                            bytesPinnedForGroup, bytesPinnedForGroup / bytesPerMB,
                            bytesPinnedForGroupNoDeps, bytesPinnedForGroupNoDeps / bytesPerMB,
                            bytesPinnedForGroupDeps, bytesPinnedForGroupDeps / bytesPerMB);
                }
                pw.println();
                if (mPinAnonAddress != 0) {
                    pw.format("Pinned anon region: %d (%.2f MB)\n", mCurrentlyPinnedAnonSize,
                            mCurrentlyPinnedAnonSize / bytesPerMB);
                    totalSize += mCurrentlyPinnedAnonSize;
                }
                pw.format("Total pinned: %d bytes (%.2f MB)\n", totalSize, totalSize / bytesPerMB);
                pw.format("Available Pinner quota: %d bytes (%.2f MB)\n", getAvailableGlobalQuota(),
                        getAvailableGlobalQuota() / bytesPerMB);
                pw.println();
                if (!mPendingRepin.isEmpty()) {
                    pw.print("Pending repin: ");
                    for (int key : mPendingRepin.values()) {
                        pw.print(getNameForKey(key));
                        pw.print(' ');
                    }
                    pw.println();
                }
            }
        }

        private void repin() {
            sendUnpinAppsMessage();
            // TODO(morrita): Consider supporting non-system user.
            sendPinAppsWithUpdatedKeysMessage(UserHandle.USER_SYSTEM);
        }

        private void printError(FileDescriptor out, String message) {
            PrintWriter writer = new PrintWriter(new FileOutputStream(out));
            writer.println(message);
            writer.flush();
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            if (args.length < 1) {
                printError(out, "Command is not given.");
                resultReceiver.send(-1, null);
                return;
            }

            String command = args[0];
            switch (command) {
                case "repin":
                    repin();
                    break;
                default:
                    printError(out,
                            String.format("Unknown pinner command: %s. Supported commands: repin",
                                    command));
                    resultReceiver.send(-1, null);
                    return;
            }

            resultReceiver.send(0, null);
        }

        @EnforcePermission(android.Manifest.permission.DUMP)
        @Override
        public List<PinnedFileStat> getPinnerStats() {
            getPinnerStats_enforcePermission();
            return PinnerService.this.getPinnerStats();
        }
    }

    final static class PinRange {
        int start;
        int length;
    }

    /**
     * Represents an app that was pinned.
     */
    private final class PinnedApp {
        /**
         * The uid of the package being pinned. This stays constant while the package stays
         * installed.
         */
        final int uid;

        /** Whether it is currently active, i.e. there is a running process from that package. */
        boolean active;

        /** List of pinned files. */
        final ArrayList<PinnedFile> mFiles = new ArrayList<>();

        private PinnedApp(ApplicationInfo appInfo) {
            uid = appInfo.uid;
            active = mAmInternal.isUidActive(uid);
        }
    }

    final class PinnerHandler extends Handler {
        static final int PIN_ONSTART_MSG = 4001;

        public PinnerHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PIN_ONSTART_MSG: {
                    handlePinOnStart();
                } break;

                default:
                    super.handleMessage(msg);
            }
        }
    }
}
