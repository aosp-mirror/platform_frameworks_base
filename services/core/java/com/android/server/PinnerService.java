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

import android.annotation.IntDef;
import android.annotation.Nullable;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.app.IUidObserver;
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
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.ResolverActivity;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.function.pooled.PooledLambda;

import com.android.server.wm.ActivityTaskManagerInternal;
import dalvik.system.DexFile;
import dalvik.system.VMRuntime;

import java.io.FileDescriptor;
import java.io.Closeable;
import java.io.InputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.ArrayList;

import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

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

    private static final int MAX_CAMERA_PIN_SIZE = 80 * (1 << 20); // 80MB max for camera app.
    private static final int MAX_HOME_PIN_SIZE = 6 * (1 << 20); // 6MB max for home app.

    @IntDef({KEY_CAMERA, KEY_HOME})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AppKey {}

    private final Context mContext;
    private final ActivityTaskManagerInternal mAtmInternal;
    private final ActivityManagerInternal mAmInternal;
    private final IActivityManager mAm;
    private final UserManager mUserManager;

    /** The list of the statically pinned files. */
    @GuardedBy("this")
    private final ArrayList<PinnedFile> mPinnedFiles = new ArrayList<>();

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
    private final ArraySet<Integer> mPinKeys = new ArraySet<>();

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

    public PinnerService(Context context) {
        super(context);

        mContext = context;
        boolean shouldPinCamera = context.getResources().getBoolean(
                com.android.internal.R.bool.config_pinnerCameraApp);
        boolean shouldPinHome = context.getResources().getBoolean(
                com.android.internal.R.bool.config_pinnerHomeApp);
        if (shouldPinCamera) {
            mPinKeys.add(KEY_CAMERA);
        }
        if (shouldPinHome) {
            mPinKeys.add(KEY_HOME);
        }
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
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.i(TAG, "Starting PinnerService");
        }
        mBinderService = new BinderService();
        publishBinderService("pinner", mBinderService);
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
    public void onSwitchUser(int userHandle) {
        if (!mUserManager.isManagedProfile(userHandle)) {
            sendPinAppsMessage(userHandle);
        }
    }

    @Override
    public void onUnlockUser(int userHandle) {
        if (!mUserManager.isManagedProfile(userHandle)) {
            sendPinAppsMessage(userHandle);
        }
    }

    /**
     * Update the currently pinned files.
     * Specifically, this only updates pinning for the apps that need to be pinned.
     * The other files pinned in onStart will not need to be updated.
     */
    public void update(ArraySet<String> updatedPackages, boolean force) {
        int currentUser = ActivityManager.getCurrentUser();
        for (int i = mPinKeys.size() - 1; i >= 0; i--) {
            int key = mPinKeys.valueAt(i);
            ApplicationInfo info = getInfoForKey(key, currentUser);
            if (info != null && updatedPackages.contains(info.packageName)) {
                Slog.i(TAG, "Updating pinned files for " + info.packageName + " force=" + force);
                sendPinAppMessage(key, currentUser, force);
            }
        }
    }

    /**
     * Handler for on start pinning message
     */
    private void handlePinOnStart() {
        final String bootImage = SystemProperties.get("dalvik.vm.boot-image", "");
        String[] filesToPin = null;
        if (bootImage.endsWith("apex.art")) {
            // Use the files listed for that specific boot image
            filesToPin = mContext.getResources().getStringArray(
                  com.android.internal.R.array.config_apexBootImagePinnerServiceFiles);
        } else {
            // Files to pin come from the overlay and can be specified per-device config
            filesToPin = mContext.getResources().getStringArray(
                  com.android.internal.R.array.config_defaultPinnerServiceFiles);
        }
        // Continue trying to pin each file even if we fail to pin some of them
        for (String fileToPin : filesToPin) {
            PinnedFile pf = pinFile(fileToPin,
                                    Integer.MAX_VALUE,
                                    /*attemptPinIntrospection=*/false);
            if (pf == null) {
                Slog.e(TAG, "Failed to pin file = " + fileToPin);
                continue;
            }

            synchronized (this) {
                mPinnedFiles.add(pf);
            }
        }
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
                            sendPinAppMessage(KEY_HOME, ActivityManager.getCurrentUser(),
                                    true /* force */);
                        }
                    }
                }, UserHandle.USER_ALL);
    }

    private void registerUidListener() {
        try {
            mAm.registerUidObserver(new IUidObserver.Stub() {
                @Override
                public void onUidGone(int uid, boolean disabled) throws RemoteException {
                    mPinnerHandler.sendMessage(PooledLambda.obtainMessage(
                            PinnerService::handleUidGone, PinnerService.this, uid));
                }

                @Override
                public void onUidActive(int uid) throws RemoteException {
                    mPinnerHandler.sendMessage(PooledLambda.obtainMessage(
                            PinnerService::handleUidActive, PinnerService.this, uid));
                }

                @Override
                public void onUidIdle(int uid, boolean disabled) throws RemoteException {
                }

                @Override
                public void onUidStateChanged(int uid, int procState, long procStateSeq)
                        throws RemoteException {
                }

                @Override
                public void onUidCachedChanged(int uid, boolean cached) throws RemoteException {
                }
            }, UID_OBSERVER_GONE | UID_OBSERVER_ACTIVE, 0, "system");
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
            pinnedFile.close();
        }
    }

    private boolean isResolverActivity(ActivityInfo info) {
        return ResolverActivity.class.getName().equals(info.name);
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

    private void pinApps(int userHandle) {
        for (int i = mPinKeys.size() - 1; i >= 0; i--) {
            int key = mPinKeys.valueAt(i);
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
            default:
                return null;
        }
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
        int pinSizeLimit = getSizeLimitForKey(key);
        String apk = appInfo.sourceDir;
        PinnedFile pf = pinFile(apk, pinSizeLimit, /*attemptPinIntrospection=*/true);
        if (pf == null) {
            Slog.e(TAG, "Failed to pin " + apk);
            return;
        }
        if (DEBUG) {
            Slog.i(TAG, "Pinned " + pf.fileName);
        }
        synchronized (this) {
            pinnedApp.mFiles.add(pf);
        }

        // determine the ABI from either ApplicationInfo or Build
        String arch = "arm";
        if (appInfo.primaryCpuAbi != null) {
            if (VMRuntime.is64BitAbi(appInfo.primaryCpuAbi)) {
                arch = arch + "64";
            }
        } else {
            if (VMRuntime.is64BitAbi(Build.SUPPORTED_ABIS[0])) {
                arch = arch + "64";
            }
        }

        // get the path to the odex or oat file
        String baseCodePath = appInfo.getBaseCodePath();
        String[] files = null;
        try {
            files = DexFile.getDexFileOutputPaths(baseCodePath, arch);
        } catch (IOException ioe) {}
        if (files == null) {
            return;
        }

        //not pinning the oat/odex is not a fatal error
        for (String file : files) {
            pf = pinFile(file, pinSizeLimit, /*attemptPinIntrospection=*/false);
            if (pf != null) {
                synchronized (this) {
                    pinnedApp.mFiles.add(pf);
                }
                if (DEBUG) {
                    Slog.i(TAG, "Pinned " + pf.fileName);
                }
            }
        }
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
    private static PinnedFile pinFile(String fileToPin,
                                      int maxBytesToPin,
                                      boolean attemptPinIntrospection) {
        ZipFile fileAsZip = null;
        InputStream pinRangeStream = null;
        try {
            if (attemptPinIntrospection) {
                fileAsZip = maybeOpenZip(fileToPin);
            }

            if (fileAsZip != null) {
                pinRangeStream = maybeOpenPinMetaInZip(fileAsZip, fileToPin);
            }

            Slog.d(TAG, "pinRangeStream: " + pinRangeStream);

            PinRangeSource pinRangeSource = (pinRangeStream != null)
                ? new PinRangeSourceStream(pinRangeStream)
                : new PinRangeSourceStatic(0, Integer.MAX_VALUE /* will be clipped */);
            return pinFileRanges(fileToPin, maxBytesToPin, pinRangeSource);
        } finally {
            safeClose(pinRangeStream);
            safeClose(fileAsZip);  // Also closes any streams we've opened
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
        ZipEntry pinMetaEntry = zipFile.getEntry(PIN_META_FILENAME);
        InputStream pinMetaStream = null;
        if (pinMetaEntry != null) {
            try {
                pinMetaStream = zipFile.getInputStream(pinMetaEntry);
            } catch (IOException ex) {
                Slog.w(TAG,
                       String.format("error reading pin metadata \"%s\": pinning as blob",
                                     fileName),
                       ex);
            }
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
            int openFlags = (OsConstants.O_RDONLY |
                             OsConstants.O_CLOEXEC |
                             OsConstants.O_NOFOLLOW);
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
            synchronized (PinnerService.this) {
                long totalSize = 0;
                for (PinnedFile pinnedFile : mPinnedFiles) {
                    pw.format("%s %s\n", pinnedFile.fileName, pinnedFile.bytesPinned);
                    totalSize += pinnedFile.bytesPinned;
                }
                pw.println();
                for (int key : mPinnedApps.keySet()) {
                    PinnedApp app = mPinnedApps.get(key);
                    pw.print(getNameForKey(key));
                    pw.print(" uid="); pw.print(app.uid);
                    pw.print(" active="); pw.print(app.active);
                    pw.println();
                    for (PinnedFile pf : mPinnedApps.get(key).mFiles) {
                        pw.print("  "); pw.format("%s %s\n", pf.fileName, pf.bytesPinned);
                        totalSize += pf.bytesPinned;
                    }
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
    }

    private static final class PinnedFile implements AutoCloseable {
        private long mAddress;
        final int mapSize;
        final String fileName;
        final int bytesPinned;

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
