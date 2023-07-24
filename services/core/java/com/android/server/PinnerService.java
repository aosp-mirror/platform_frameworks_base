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
 * limitations under the License.
 */

package com.android.server;

import static android.app.ActivityManager.UID_OBSERVER_ACTIVE;
import static android.app.ActivityManager.UID_OBSERVER_GONE;
import static android.os.Process.SYSTEM_UID;

import static com.android.server.flags.Flags.pinWebview;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.app.SearchManager;
import android.app.UidObserver;
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
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemProperties;
import android.os.Trace;
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
import com.android.server.wm.ActivityTaskManagerInternal;

import dalvik.system.DexFile;
import dalvik.system.VMRuntime;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
 */
public final class PinnerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "PinnerService";

    private static final String PIN_META_FILENAME = "pinlist.meta";
    private static final int PAGE_SIZE = (int) Os.sysconf(OsConstants._SC_PAGESIZE);
    private static final int MATCH_FLAGS = PackageManager.MATCH_DEFAULT_ONLY
            | PackageManager.MATCH_DIRECT_BOOT_AWARE
            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

    private static final int KEY_CAMERA = 0;
    private static final int KEY_HOME = 1;
    private static final int KEY_ASSISTANT = 2;

    // Pin using pinlist.meta when pinning apps.
    private static boolean PROP_PIN_PINLIST =
            SystemProperties.getBoolean("pinner.use_pinlist", true);

    private static final int MAX_CAMERA_PIN_SIZE = 80 * (1 << 20); // 80MB max for camera app.
    private static final int MAX_HOME_PIN_SIZE = 6 * (1 << 20); // 6MB max for home app.
    private static final int MAX_ASSISTANT_PIN_SIZE = 60 * (1 << 20); // 60MB max for assistant app.

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
    private SearchManager mSearchManager;

    /** The list of the statically pinned files. */
    @GuardedBy("this") private final ArrayMap<String, PinnedFile> mPinnedFiles = new ArrayMap<>();

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
    @GuardedBy("this")
    private ArraySet<Integer> mPinKeys;

    private static final String DEVICE_CONFIG_KEY_ANON_SIZE = "pin_shared_anon_size";
    private static final long DEFAULT_ANON_SIZE =
            SystemProperties.getLong("pinner.pin_shared_anon_size", 0);
    private static final long MAX_ANON_SIZE = 2L * (1L << 30); // 2GB
    private long mPinAnonSize;
    private long mPinAnonAddress;
    private long mCurrentlyPinnedAnonSize;

    // Resource-configured pinner flags;
    private final boolean mConfiguredToPinCamera;
    private final boolean mConfiguredToPinHome;
    private final boolean mConfiguredToPinAssistant;
    private final int mConfiguredWebviewPinBytes;

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

    private DeviceConfig.OnPropertiesChangedListener mDeviceConfigListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    if (DeviceConfig.NAMESPACE_RUNTIME_NATIVE_BOOT.equals(properties.getNamespace())
                            && properties.getKeyset().contains(DEVICE_CONFIG_KEY_ANON_SIZE)) {
                        refreshPinAnonConfig();
                    }
                }
            };

    /** Utility class for testing. */
    @VisibleForTesting
    static class Injector {
        protected DeviceConfigInterface getDeviceConfigInterface() {
            return DeviceConfigInterface.REAL;
        }

        protected void publishBinderService(PinnerService service, Binder binderService) {
            service.publishBinderService("pinner", binderService);
        }
    }

    public PinnerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    PinnerService(Context context, Injector injector) {
        super(context);

        mContext = context;
        mInjector = injector;
        mDeviceConfigInterface = mInjector.getDeviceConfigInterface();
        mConfiguredToPinCamera = context.getResources().getBoolean(
                com.android.internal.R.bool.config_pinnerCameraApp);
        mConfiguredToPinHome = context.getResources().getBoolean(
                com.android.internal.R.bool.config_pinnerHomeApp);
        mConfiguredToPinAssistant = context.getResources().getBoolean(
                com.android.internal.R.bool.config_pinnerAssistantApp);
        mConfiguredWebviewPinBytes = context.getResources().getInteger(
                com.android.internal.R.integer.config_pinnerWebviewPinBytes);
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

        mDeviceConfigInterface.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_RUNTIME_NATIVE_BOOT,
                new HandlerExecutor(mPinnerHandler),
                mDeviceConfigListener);
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.i(TAG, "Starting PinnerService");
        }
        mBinderService = new BinderService();
        mInjector.publishBinderService(this, mBinderService);
        publishLocalService(PinnerService.class, this);

        mPinnerHandler.obtainMessage(PinnerHandler.PIN_ONSTART_MSG).sendToTarget();
        sendPinAppsMessage(UserHandle.USER_SYSTEM);
    }

    @Override
    public void onBootPhase(int phase) {
        // SearchManagerService is started after PinnerService, wait for PHASE_SYSTEM_SERVICES_READY
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mSearchManager = (SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE);
            sendPinAppsMessage(UserHandle.USER_SYSTEM);
        }
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
        int userId = user.getUserIdentifier();
        if (!mUserManager.isManagedProfile(userId)) {
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
            this.sizeKb = file.bytesPinned / 1024;
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
            PinnedFile pf = pinFileInternal(fileToPin, Integer.MAX_VALUE,
                    /*attemptPinIntrospection=*/false);
            if (pf == null) {
                Slog.e(TAG, "Failed to pin file = " + fileToPin);
                continue;
            }
            synchronized (this) {
                mPinnedFiles.put(pf.fileName, pf);
            }
            pf.groupName = "xml-config";
            pinOptimizedDexDependencies(pf, Integer.MAX_VALUE, null);
        }

        refreshPinAnonConfig();
    }

    /**
     * Registers a listener to repin the home app when user setup is complete, as the home intent
     * initially resolves to setup wizard, but once setup is complete, it will resolve to the
     * regular home app.
     */
    private void registerUserSetupCompleteListener() {
        Uri userSetupCompleteUri = Settings.Secure.getUriFor(
                Settings.Secure.USER_SETUP_COMPLETE);
        mContext.getContentResolver().registerContentObserver(userSetupCompleteUri,
                false, new ContentObserver(null) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (userSetupCompleteUri.equals(uri)) {
                            if (mConfiguredToPinHome) {
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
                public void onUidActive(int uid)  {
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
        ApplicationInfo info = getApplicationInfoForIntent(cameraIntent, userHandle,
            false /* defaultToSystemApp */);

        // If the STILL_IMAGE_CAMERA intent doesn't resolve, try the _SECURE intent.
        // We don't use _SECURE first because it will never get set on a device
        // without File-based Encryption. But if the user has only set the intent
        // before unlocking their device, we may still be able to identify their
        // preference using this intent.
        if (info == null) {
            cameraIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
            info = getApplicationInfoForIntent(cameraIntent, userHandle,
                false /* defaultToSystemApp */);
        }

        // If the _SECURE intent doesn't resolve, try the original intent but request
        // the system app for camera if there was more than one result.
        if (info == null) {
            cameraIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            info = getApplicationInfoForIntent(cameraIntent, userHandle,
                true /* defaultToSystemApp */);
        }
        return info;
    }

    private ApplicationInfo getHomeInfo(int userHandle) {
        Intent intent = mAtmInternal.getHomeIntent();
        return getApplicationInfoForIntent(intent, userHandle, false);
    }

    private ApplicationInfo getAssistantInfo(int userHandle) {
        if (mSearchManager != null) {
            Intent intent = mSearchManager.getAssistIntent(false);
            return getApplicationInfoForIntent(intent, userHandle, true);
        }
        return null;
    }

    private ApplicationInfo getApplicationInfoForIntent(Intent intent, int userHandle,
            boolean defaultToSystemApp) {
        if (intent == null) {
            return null;
        }

        ResolveInfo resolveInfo = mContext.getPackageManager().resolveActivityAsUser(intent,
                MATCH_FLAGS, userHandle);

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
            List<ResolveInfo> infoList = mContext.getPackageManager()
                .queryIntentActivitiesAsUser(intent, MATCH_FLAGS, userHandle);
            ApplicationInfo systemAppInfo = null;
            for (ResolveInfo info : infoList) {
                if ((info.activityInfo.applicationInfo.flags
                      & ApplicationInfo.FLAG_SYSTEM) != 0) {
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
        mPinnerHandler.sendMessage(PooledLambda.obtainMessage(PinnerService::pinApps, this,
                userHandle));
    }

    private void sendPinAppsWithUpdatedKeysMessage(int userHandle) {
        mPinnerHandler.sendMessage(PooledLambda.obtainMessage(PinnerService::pinAppsWithUpdatedKeys,
                this, userHandle));
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
                        "pin_camera",
                        SystemProperties.getBoolean("pinner.pin_camera", true));
        if (shouldPinCamera) {
            pinKeys.add(KEY_CAMERA);
        } else if (DEBUG) {
            Slog.i(TAG, "Pinner - skip pinning camera app");
        }

        if (mConfiguredToPinHome) {
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
                    Slog.e(TAG, "Attempted to update a list of apps, "
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
        mPinnerHandler.sendMessage(PooledLambda.obtainMessage(PinnerService::pinApp, this,
                key, userHandle, force));
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
        unpinApp(key);
        ApplicationInfo info = getInfoForKey(key, userHandle);
        if (info != null) {
            pinApp(key, info);
        }
    }

    /**
     * Checks whether the pinned package with {@code key} is active or not.

     * @return The uid of the pinned app, or {@code -1} otherwise.
     */
    private int getUidForKey(@AppKey int key) {
        synchronized (this) {
            PinnedApp existing = mPinnedApps.get(key);
            return existing != null && existing.active
                    ? existing.uid
                    : -1;
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
                return null;
        }
    }

    /**
     * Handle any changes in the anon region pinner config.
     */
    private void refreshPinAnonConfig() {
        long newPinAnonSize =
                mDeviceConfigInterface.getLong(
                        DeviceConfig.NAMESPACE_RUNTIME_NATIVE_BOOT,
                        DEVICE_CONFIG_KEY_ANON_SIZE,
                        DEFAULT_ANON_SIZE);
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
            address = Os.mmap(0, alignedPinSize,
                    OsConstants.PROT_READ | OsConstants.PROT_WRITE,
                    OsConstants.MAP_SHARED | OsConstants.MAP_ANONYMOUS,
                    new FileDescriptor(), /*offset=*/0);

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
                safeMunmap(address, alignedPinSize);
            }
        }
    }

    private void unpinAnonRegion() {
        if (mPinAnonAddress != 0) {
            safeMunmap(mPinAnonAddress, mCurrentlyPinnedAnonSize);
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
                return MAX_CAMERA_PIN_SIZE;
            case KEY_HOME:
                return MAX_HOME_PIN_SIZE;
            case KEY_ASSISTANT:
                return MAX_ASSISTANT_PIN_SIZE;
            default:
                return 0;
        }
    }

    /**
     * Pins an application.
     *
     * @param key The key of the app to pin.
     * @param appInfo The corresponding app info.
     */
    private void pinApp(@AppKey int key, @Nullable ApplicationInfo appInfo) {
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

        int apkPinSizeLimit = pinSizeLimit;
        for (String apk: apks) {
            if (apkPinSizeLimit <= 0) {
                Slog.w(TAG, "Reached to the pin size limit. Skipping: " + apk);
                // Continue instead of break to print all skipped APK names.
                continue;
            }

            PinnedFile pf = pinFileInternal(apk, apkPinSizeLimit, /*attemptPinIntrospection=*/true);
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
            if (apk.equals(appInfo.sourceDir)) {
                pinOptimizedDexDependencies(pf, apkPinSizeLimit, appInfo);
                for (PinnedFile dep : pf.pinnedDeps) {
                    pinnedApp.mFiles.add(dep);
                }
            }
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
     * @param maxBytesToPin maximum quota allowed for pinning
     * @return total bytes that were pinned.
     */
    public int pinFile(String fileToPin, int maxBytesToPin, @Nullable ApplicationInfo appInfo,
            @Nullable String groupName) {
        PinnedFile existingPin;
        synchronized(this) {
            existingPin = mPinnedFiles.get(fileToPin);
        }
        if (existingPin != null) {
            if (existingPin.bytesPinned == maxBytesToPin) {
                // Duplicate pin requesting same amount of bytes, lets just bail out.
                return 0;
            } else {
                // User decided to pin a different amount of bytes than currently pinned
                // so this is a valid pin request. Unpin the previous version before repining.
                if (DEBUG) {
                    Slog.d(TAG, "Unpinning file prior to repin: " + fileToPin);
                }
                unpinFile(fileToPin);
            }
        }

        boolean isApk = fileToPin.endsWith(".apk");
        int bytesPinned = 0;
        PinnedFile pf = pinFileInternal(fileToPin, maxBytesToPin,
                /*attemptPinIntrospection=*/isApk);
        if (pf == null) {
            Slog.e(TAG, "Failed to pin file = " + fileToPin);
            return 0;
        }
        pf.groupName = groupName != null ? groupName : "";

        maxBytesToPin -= bytesPinned;
        bytesPinned += pf.bytesPinned;

        synchronized (this) {
            mPinnedFiles.put(pf.fileName, pf);
        }
        if (maxBytesToPin > 0) {
            pinOptimizedDexDependencies(pf, maxBytesToPin, appInfo);
        }
        return bytesPinned;
    }

    /**
     * Pin any dependency optimized files generated by ART.
     * @param pinnedFile An already pinned file whose dependencies we want pinned.
     * @param maxBytesToPin Maximum amount of bytes to pin.
     * @param appInfo Used to determine the ABI in case the application has one custom set, when set
     *                to null it will use the default supported ABI by the device.
     * @return total bytes pinned.
     */
    private int pinOptimizedDexDependencies(
            PinnedFile pinnedFile, int maxBytesToPin, @Nullable ApplicationInfo appInfo) {
        if (pinnedFile == null) {
            return 0;
        }

        int bytesPinned = 0;
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

                PinnedFile df = pinFileInternal(file, Integer.MAX_VALUE,
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

    /** mlock length bytes of fileToPin in memory
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
    private static PinnedFile pinFileInternal(
            String fileToPin, int maxBytesToPin, boolean attemptPinIntrospection) {
        if (DEBUG) {
            Slog.d(TAG, "pin file: " + fileToPin + " use-pinlist: " + attemptPinIntrospection);
        }
        Trace.beginSection("pinFile:" + fileToPin);
        ZipFile fileAsZip = null;
        InputStream pinRangeStream = null;
        try {
            if (attemptPinIntrospection) {
                fileAsZip = maybeOpenZip(fileToPin);
            }

            if (fileAsZip != null) {
                pinRangeStream = maybeOpenPinMetaInZip(fileAsZip, fileToPin);
            }

            PinRangeSource pinRangeSource = (pinRangeStream != null)
                ? new PinRangeSourceStream(pinRangeStream)
                : new PinRangeSourceStatic(0, Integer.MAX_VALUE /* will be clipped */);
            return pinFileRanges(fileToPin, maxBytesToPin, pinRangeSource);
        } finally {
            safeClose(pinRangeStream);
            safeClose(fileAsZip);  // Also closes any streams we've opened
            Trace.endSection();
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
            Slog.w(TAG,
                   String.format(
                       "could not open \"%s\" as zip: pinning as blob",
                                 fileName),
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
                       String.format("error reading pin metadata \"%s\": pinning as blob",
                                     fileName),
                       ex);
            }
        } else {
            Slog.w(TAG,
                    String.format(
                            "Could not find pinlist.meta for \"%s\": pinning as blob", fileName));
        }
        return pinMetaStream;
    }

    private static abstract class PinRangeSource {
        /** Retrive a range to pin.
         *
         * @param outPinRange Receives the pin region
         * @return True if we filled in outPinRange or false if we're out of pin entries
         */
        abstract boolean read(PinRange outPinRange);
    }

    private static final class PinRangeSourceStatic extends PinRangeSource {
        private final int mPinStart;
        private final int mPinLength;
        private boolean mDone = false;

        PinRangeSourceStatic(int pinStart, int pinLength) {
            mPinStart = pinStart;
            mPinLength = pinLength;
        }

        @Override
        boolean read(PinRange outPinRange) {
            outPinRange.start = mPinStart;
            outPinRange.length = mPinLength;
            boolean done = mDone;
            mDone = true;
            return !done;
        }
    }

    private static final class PinRangeSourceStream extends PinRangeSource {
        private final DataInputStream mStream;
        private boolean mDone = false;

        PinRangeSourceStream(InputStream stream) {
            mStream = new DataInputStream(stream);
        }

        @Override
        boolean read(PinRange outPinRange) {
            if (!mDone) {
                try {
                    outPinRange.start = mStream.readInt();
                    outPinRange.length = mStream.readInt();
                } catch (IOException ex) {
                    mDone = true;
                }
            }
            return !mDone;
        }
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
        String fileToPin,
        int maxBytesToPin,
        PinRangeSource pinRangeSource)
    {
        FileDescriptor fd = new FileDescriptor();
        long address = -1;
        int mapSize = 0;

        try {
            int openFlags = (OsConstants.O_RDONLY | OsConstants.O_CLOEXEC);
            fd = Os.open(fileToPin, openFlags, 0);
            mapSize = (int) Math.min(Os.fstat(fd).st_size, Integer.MAX_VALUE);
            address = Os.mmap(0, mapSize,
                              OsConstants.PROT_READ,
                              OsConstants.MAP_SHARED,
                              fd, /*offset=*/0);

            PinRange pinRange = new PinRange();
            int bytesPinned = 0;

            // We pin at page granularity, so make sure the limit is page-aligned
            if (maxBytesToPin % PAGE_SIZE != 0) {
                maxBytesToPin -= maxBytesToPin % PAGE_SIZE;
            }

            while (bytesPinned < maxBytesToPin && pinRangeSource.read(pinRange)) {
                int pinStart = pinRange.start;
                int pinLength = pinRange.length;
                pinStart = clamp(0, pinStart, mapSize);
                pinLength = clamp(0, pinLength, mapSize - pinStart);
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
                pinLength = clamp(0, pinLength, maxBytesToPin - bytesPinned);

                if (pinLength > 0) {
                    if (DEBUG) {
                        Slog.d(TAG,
                               String.format(
                                   "pinning at %s %s bytes of %s",
                                   pinStart, pinLength, fileToPin));
                    }
                    Os.mlock(address + pinStart, pinLength);
                }
                bytesPinned += pinLength;
            }

            PinnedFile pinnedFile = new PinnedFile(address, mapSize, fileToPin, bytesPinned);
            address = -1;  // Ownership transferred
            return pinnedFile;
        } catch (ErrnoException ex) {
            Slog.e(TAG, "Could not pin file " + fileToPin, ex);
            return null;
        } finally {
            safeClose(fd);
            if (address >= 0) {
                safeMunmap(address, mapSize);
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

    public void unpinFile(String filename) {
        PinnedFile pinnedFile;
        synchronized (this) {
            pinnedFile = mPinnedFiles.get(filename);
        }
        if (pinnedFile == null) {
            // File not pinned, nothing to do.
            return;
        }
        pinnedFile.close();
        synchronized (this) {
            if (DEBUG) {
                Slog.d(TAG, "Unpinned file: " + filename);
            }
            mPinnedFiles.remove(pinnedFile.fileName);
            for (PinnedFile dep : pinnedFile.pinnedDeps) {
                if (dep == null) {
                    continue;
                }
                mPinnedFiles.remove(dep.fileName);
                if (DEBUG) {
                    Slog.d(TAG, "Unpinned dependency: " + dep.fileName);
                }
            }
        }
    }

    private static int clamp(int min, int value, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static void safeMunmap(long address, long mapSize) {
        try {
            Os.munmap(address, mapSize);
        } catch (ErrnoException ex) {
            Slog.w(TAG, "ignoring error in unmap", ex);
        }
    }

    /**
     * Close FD, swallowing irrelevant errors.
     */
    private static void safeClose(@Nullable FileDescriptor fd) {
        if (fd != null && fd.valid()) {
            try {
                Os.close(fd);
            } catch (ErrnoException ex) {
                // Swallow the exception: non-EBADF errors in close(2)
                // indicate deferred paging write errors, which we
                // don't care about here. The underlying file
                // descriptor is always closed.
                if (ex.errno == OsConstants.EBADF) {
                    throw new AssertionError(ex);
                }
            }
        }
    }

    /**
     * Close closeable thing, swallowing errors.
     */
    private static void safeClose(@Nullable Closeable thing) {
        if (thing != null) {
            try {
                thing.close();
            } catch (IOException ex) {
                Slog.w(TAG, "ignoring error closing resource: " + thing, ex);
            }
        }
    }

    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            HashSet<PinnedFile> shownPins = new HashSet<>();
            HashSet<String> groups = new HashSet<>();

            synchronized (PinnerService.this) {
                long totalSize = 0;
                for (int key : mPinnedApps.keySet()) {
                    PinnedApp app = mPinnedApps.get(key);
                    pw.print(getNameForKey(key));
                    pw.print(" uid="); pw.print(app.uid);
                    pw.print(" active="); pw.print(app.active);
                    pw.println();
                    for (PinnedFile pf : mPinnedApps.get(key).mFiles) {
                        pw.print("  "); pw.format("%s %s\n", pf.fileName, pf.bytesPinned);
                        totalSize += pf.bytesPinned;
                        shownPins.add(pf);
                    }
                }
                pw.println();
                for (PinnedFile pinnedFile : mPinnedFiles.values()) {
                    if (!groups.contains(pinnedFile.groupName)) {
                        groups.add(pinnedFile.groupName);
                    }
                }
                for (String group : groups) {
                    pw.print("Group:" + group);
                    pw.println();
                    List<PinnedFile> groupPins = mPinnedFiles.values()
                                                         .stream()
                                                         .filter(f -> f.groupName.equals(group))
                                                         .toList();
                    for (PinnedFile pinnedFile : groupPins) {
                        if (shownPins.contains(pinnedFile)) {
                            // Already showed in the dump and accounted for, skip.
                            continue;
                        }
                        pw.format(" %s %s\n", pinnedFile.fileName, pinnedFile.bytesPinned);
                        totalSize += pinnedFile.bytesPinned;
                    }
                }
                pw.println();
                if (mPinAnonAddress != 0) {
                    pw.format("Pinned anon region: %s\n", mCurrentlyPinnedAnonSize);
                }
                pw.format("Total size: %s\n", totalSize);
                pw.println();
                if (!mPendingRepin.isEmpty()) {
                    pw.print("Pending repin: ");
                    for (int key : mPendingRepin.values()) {
                        pw.print(getNameForKey(key)); pw.print(' ');
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
                    printError(out, String.format(
                            "Unknown pinner command: %s. Supported commands: repin", command));
                    resultReceiver.send(-1, null);
                    return;
            }

            resultReceiver.send(0, null);
        }
    }

    private static final class PinnedFile implements AutoCloseable {
        private long mAddress;
        final int mapSize;
        final String fileName;
        final int bytesPinned;

        // User defined group name for pinner accounting
        String groupName = "";
        ArrayList<PinnedFile> pinnedDeps = new ArrayList<>();

        PinnedFile(long address, int mapSize, String fileName, int bytesPinned) {
             mAddress = address;
             this.mapSize = mapSize;
             this.fileName = fileName;
             this.bytesPinned = bytesPinned;
        }

        @Override
        public void close() {
            if (mAddress >= 0) {
                safeMunmap(mAddress, mapSize);
                mAddress = -1;
            }
            for (PinnedFile dep : pinnedDeps) {
                if (dep != null) {
                    dep.close();
                }
            }
        }

        @Override
        public void finalize() {
            close();
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
                case PIN_ONSTART_MSG:
                {
                    handlePinOnStart();
                }
                break;

                default:
                    super.handleMessage(msg);
            }
        }
    }

}
