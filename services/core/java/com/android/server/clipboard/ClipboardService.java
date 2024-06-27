/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.clipboard;

import static android.app.ActivityManagerInternal.ALLOW_FULL_ONLY;
import static android.companion.virtual.VirtualDeviceManager.ACTION_VIRTUAL_DEVICE_REMOVED;
import static android.companion.virtual.VirtualDeviceManager.EXTRA_VIRTUAL_DEVICE_ID;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_CLIPBOARD;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.content.Context.DEVICE_ID_INVALID;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.IUriGrantsManager;
import android.app.KeyguardManager;
import android.app.UriGrantsManager;
import android.companion.virtual.VirtualDeviceManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IClipboard;
import android.content.IOnPrimaryClipChangedListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SafetyProtectionUtils;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.autofill.AutofillManagerInternal;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextClassifierEvent;
import android.view.textclassifier.TextLinks;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiThread;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Implementation of the clipboard for copy and paste.
 * <p>
 * Caution: exception for clipboard data and isInternalSysWindowAppWithWindowFocus, any of data
 * is accessed by userId or uid should be in * the try segment between
 * Binder.clearCallingIdentity and Binder.restoreCallingIdentity.
 * </p>
 */
public class ClipboardService extends SystemService {

    private static final String TAG = "ClipboardService";

    @VisibleForTesting
    public static final long DEFAULT_CLIPBOARD_TIMEOUT_MILLIS = 3600000;

    /**
     * Device config property for whether clipboard auto clear is enabled on the device
     **/
    public static final String PROPERTY_AUTO_CLEAR_ENABLED =
            "auto_clear_enabled";

    /**
     * Device config property for time period in milliseconds after which clipboard is auto
     * cleared
     **/
    public static final String PROPERTY_AUTO_CLEAR_TIMEOUT =
            "auto_clear_timeout";

    // DeviceConfig properties
    private static final String PROPERTY_MAX_CLASSIFICATION_LENGTH = "max_classification_length";
    private static final int DEFAULT_MAX_CLASSIFICATION_LENGTH = 400;

    private final ActivityManagerInternal mAmInternal;
    private final IUriGrantsManager mUgm;
    private final UriGrantsManagerInternal mUgmInternal;
    private final WindowManagerInternal mWm;
    private final VirtualDeviceManagerInternal mVdmInternal;
    private final VirtualDeviceManager mVdm;
    private BroadcastReceiver mVirtualDeviceRemovedReceiver;
    private VirtualDeviceManager.VirtualDeviceListener mVirtualDeviceListener;
    private final IUserManager mUm;
    private final PackageManager mPm;
    private final AppOpsManager mAppOps;
    private final ContentCaptureManagerInternal mContentCaptureInternal;
    private final AutofillManagerInternal mAutofillInternal;
    private final IBinder mPermissionOwner;
    private final Consumer<ClipData> mClipboardMonitor;
    private final Handler mWorkerHandler;

    @GuardedBy("mLock")
    // Maps (userId, deviceId) to Clipboard.
    private final SparseArrayMap<Integer, Clipboard> mClipboards = new SparseArrayMap<>();

    @GuardedBy("mLock")
    private boolean mShowAccessNotifications =
            ClipboardManager.DEVICE_CONFIG_DEFAULT_SHOW_ACCESS_NOTIFICATIONS;
    @GuardedBy("mLock")
    private boolean mAllowVirtualDeviceSilos =
            ClipboardManager.DEVICE_CONFIG_DEFAULT_ALLOW_VIRTUALDEVICE_SILOS;

    @GuardedBy("mLock")
    private int mMaxClassificationLength = DEFAULT_MAX_CLASSIFICATION_LENGTH;

    private final Object mLock = new Object();

    /**
     * Instantiates the clipboard.
     */
    public ClipboardService(Context context) {
        super(context);

        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        mUgm = UriGrantsManager.getService();
        mUgmInternal = LocalServices.getService(UriGrantsManagerInternal.class);
        mWm = LocalServices.getService(WindowManagerInternal.class);
        // Can be null; not all products have CDM + VirtualDeviceManager
        mVdmInternal = LocalServices.getService(VirtualDeviceManagerInternal.class);
        mVdm = (mVdmInternal == null) ? null : getContext().getSystemService(
                VirtualDeviceManager.class);
        mPm = getContext().getPackageManager();
        mUm = (IUserManager) ServiceManager.getService(Context.USER_SERVICE);
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mContentCaptureInternal = LocalServices.getService(ContentCaptureManagerInternal.class);
        mAutofillInternal = LocalServices.getService(AutofillManagerInternal.class);
        final IBinder permOwner = mUgmInternal.newUriPermissionOwner("clipboard");
        mPermissionOwner = permOwner;
        if (Build.IS_EMULATOR) {
            mClipboardMonitor = new EmulatorClipboardMonitor((clip) -> {
                synchronized (mLock) {
                    Clipboard clipboard = getClipboardLocked(0, DEVICE_ID_DEFAULT);
                    if (clipboard != null) {
                        setPrimaryClipInternalLocked(clipboard, clip, android.os.Process.SYSTEM_UID,
                                null);
                    }
                }
            });
        } else if (Build.IS_ARC) {
            mClipboardMonitor = new ArcClipboardMonitor((clip, uid) -> {
                setPrimaryClipInternal(clip, uid);
            });
        } else {
            mClipboardMonitor = (clip) -> {};
        }

        updateConfig();
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_CLIPBOARD,
                getContext().getMainExecutor(), properties -> updateConfig());

        HandlerThread workerThread = new HandlerThread(TAG);
        workerThread.start();
        mWorkerHandler = workerThread.getThreadHandler();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.CLIPBOARD_SERVICE, new ClipboardImpl());
        if (!android.companion.virtual.flags.Flags.vdmPublicApis() && mVdmInternal != null) {
            registerVirtualDeviceBroadcastReceiver();
        } else if (android.companion.virtual.flags.Flags.vdmPublicApis() && mVdm != null) {
            registerVirtualDeviceListener();
        }
    }

    private void registerVirtualDeviceBroadcastReceiver() {
        if (mVirtualDeviceRemovedReceiver != null) {
            return;
        }
        mVirtualDeviceRemovedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!intent.getAction().equals(ACTION_VIRTUAL_DEVICE_REMOVED)) {
                    return;
                }
                final int removedDeviceId =
                        intent.getIntExtra(EXTRA_VIRTUAL_DEVICE_ID, DEVICE_ID_INVALID);
                synchronized (mLock) {
                    for (int i = mClipboards.numMaps() - 1; i >= 0; i--) {
                        mClipboards.delete(mClipboards.keyAt(i), removedDeviceId);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_VIRTUAL_DEVICE_REMOVED);
        getContext().registerReceiver(mVirtualDeviceRemovedReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
    }

    private void registerVirtualDeviceListener() {
        if (mVdm == null || mVirtualDeviceListener != null) {
            return;
        }
        mVirtualDeviceListener = new VirtualDeviceManager.VirtualDeviceListener() {
            @Override
            public void onVirtualDeviceClosed(int deviceId) {
                synchronized (mLock) {
                    for (int i = mClipboards.numMaps() - 1; i >= 0; i--) {
                        mClipboards.delete(mClipboards.keyAt(i), deviceId);
                    }
                }
            }
        };
        mVdm.registerVirtualDeviceListener(getContext().getMainExecutor(), mVirtualDeviceListener);
    }

    @Override
    public void onUserStopped(@NonNull TargetUser user) {
        synchronized (mLock) {
            mClipboards.delete(user.getUserIdentifier());
        }
    }

    private void updateConfig() {
        synchronized (mLock) {
            mShowAccessNotifications = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_CLIPBOARD,
                    ClipboardManager.DEVICE_CONFIG_SHOW_ACCESS_NOTIFICATIONS,
                    ClipboardManager.DEVICE_CONFIG_DEFAULT_SHOW_ACCESS_NOTIFICATIONS);
            mAllowVirtualDeviceSilos = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_CLIPBOARD,
                    ClipboardManager.DEVICE_CONFIG_ALLOW_VIRTUALDEVICE_SILOS,
                    ClipboardManager.DEVICE_CONFIG_DEFAULT_ALLOW_VIRTUALDEVICE_SILOS);
            mMaxClassificationLength = DeviceConfig.getInt(DeviceConfig.NAMESPACE_CLIPBOARD,
                    PROPERTY_MAX_CLASSIFICATION_LENGTH, DEFAULT_MAX_CLASSIFICATION_LENGTH);
        }
    }

    private class ListenerInfo {
        final int mUid;
        final String mPackageName;
        final String mAttributionTag;

        ListenerInfo(int uid, String packageName, String attributionTag) {
            mUid = uid;
            mPackageName = packageName;
            mAttributionTag = attributionTag;
        }
    }

    private static class Clipboard {
        public final int userId;
        public final int deviceId;

        final RemoteCallbackList<IOnPrimaryClipChangedListener> primaryClipListeners
                = new RemoteCallbackList<IOnPrimaryClipChangedListener>();

        /** Current primary clip. */
        ClipData primaryClip;
        /** UID that set {@link #primaryClip}. */
        int primaryClipUid = android.os.Process.NOBODY_UID;
        /** Package of the app that set {@link #primaryClip}. */
        String mPrimaryClipPackage;

        /** Uids that have already triggered a toast notification for {@link #primaryClip} */
        final SparseBooleanArray mNotifiedUids = new SparseBooleanArray();

        /**
         * Uids that have already triggered a notification to text classifier for
         * {@link #primaryClip}.
         */
        final SparseBooleanArray mNotifiedTextClassifierUids = new SparseBooleanArray();

        final HashSet<String> activePermissionOwners
                = new HashSet<String>();

        /** The text classifier session that is used to annotate the text in the primary clip. */
        TextClassifier mTextClassifier;

        Clipboard(int userId, int deviceId) {
            this.userId = userId;
            this.deviceId = deviceId;
        }
    }

    /**
     * To check if the application has granted the INTERNAL_SYSTEM_WINDOW permission and window
     * focus.
     * <p>
     * All of applications granted INTERNAL_SYSTEM_WINDOW has the risk to leak clip information to
     * the other user because INTERNAL_SYSTEM_WINDOW is signature level. i.e. platform key. Because
     * some of applications have both of INTERNAL_SYSTEM_WINDOW and INTERACT_ACROSS_USERS_FULL at
     * the same time, that means they show the same window to all of users.
     * </p><p>
     * Unfortunately, all of applications with INTERNAL_SYSTEM_WINDOW starts very early and then
     * the real window show is belong to user 0 rather user X. The result of
     * WindowManager.isUidFocused checking user X window is false.
     * </p>
     * @return true if the app granted INTERNAL_SYSTEM_WINDOW permission.
     */
    private boolean isInternalSysWindowAppWithWindowFocus(String callingPackage) {
        // Shell can access the clipboard for testing purposes.
        if (mPm.checkPermission(Manifest.permission.INTERNAL_SYSTEM_WINDOW,
                    callingPackage) == PackageManager.PERMISSION_GRANTED) {
            if (mWm.isUidFocused(Binder.getCallingUid())) {
                return true;
            }
        }

        return false;
    }

    /**
     * To get the validate current userId.
     * <p>
     * The intending userId needs to be validated by ActivityManagerInternal.handleIncomingUser.
     * To check if the uid of the process have the permission to run as the userId.
     * e.x. INTERACT_ACROSS_USERS_FULL or INTERACT_ACROSS_USERS permission granted.
     * </p>
     * <p>
     * The application with the granted INTERNAL_SYSTEM_WINDOW permission should run as the output
     * of ActivityManagerInternal.handleIncomingUser rather the userId of Binder.getCAllingUid().
     * To use the userId of Binder.getCallingUid() is the root cause that leaks the information
     * comes from user 0 to user X.
     * </p>
     *
     * @param packageName the package name of the calling side
     * @param userId the userId passed by the calling side
     * @return return the intending userId that has been validated by ActivityManagerInternal.
     */
    @UserIdInt
    private int getIntendingUserId(String packageName, @UserIdInt int userId) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (!UserManager.supportsMultipleUsers() || callingUserId == userId) {
            return callingUserId;
        }

        int intendingUserId = callingUserId;
        intendingUserId = mAmInternal.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false /* allow all */, ALLOW_FULL_ONLY,
                "checkClipboardServiceCallingUser", packageName);

        return intendingUserId;
    }

    /**
     * To get the current running uid who is intend to run as.
     * In ording to distinguish the nameing and reducing the confusing names, the client client
     * side pass userId that is intend to run as,
     * @return return IntentingUid = validated intenting userId +
     *         UserHandle.getAppId(Binder.getCallingUid())
     */
    private int getIntendingUid(String packageName, @UserIdInt int userId) {
        return UserHandle.getUid(getIntendingUserId(packageName, userId),
                UserHandle.getAppId(Binder.getCallingUid()));
    }

    /**
     * Determines which deviceId to use for selecting a Clipboard, depending on where a given app
     * is running and the device's clipboard policy.
     *
     * @param requestedDeviceId the requested deviceId passed in from the client side
     * @param uid the intended app uid
     * @return a deviceId to use in selecting the appropriate clipboard, or
     * DEVICE_ID_INVALID if this uid should not be allowed access. A value of DEVICE_ID_DEFAULT
     * means just use the "regular" clipboard.
     */
    private int getIntendingDeviceId(int requestedDeviceId, int uid) {
        if (mVdmInternal == null) {
            return DEVICE_ID_DEFAULT;
        }

        ArraySet<Integer> virtualDeviceIds = mVdmInternal.getDeviceIdsForUid(uid);

        synchronized (mLock) {
            if (!mAllowVirtualDeviceSilos
                    && (!virtualDeviceIds.isEmpty() || requestedDeviceId != DEVICE_ID_DEFAULT)) {
                return DEVICE_ID_INVALID;
            }
        }

        // If an app is running on any VirtualDevice, it isn't clear which clipboard they
        // should use, unless all of the devices share the default device's clipboard.
        boolean allDevicesHaveDefaultClipboard = true;
        for (int deviceId : virtualDeviceIds) {
            if (!deviceUsesDefaultClipboard(deviceId)) {
                allDevicesHaveDefaultClipboard = false;
                break;
            }
        }

        // Apps running on a virtual device may get the default clipboard if all the devices the app
        // runs on share that clipboard. Otherwise it's not clear which clipboard to use.
        if (requestedDeviceId == DEVICE_ID_DEFAULT) {
            return allDevicesHaveDefaultClipboard ? DEVICE_ID_DEFAULT : DEVICE_ID_INVALID;
        }

        // At this point the app wants to access a virtual device clipboard. It may do so if:
        //  1. The app owns the VirtualDevice
        //  2. The app is present on the VirtualDevice
        //  3. The VirtualDevice shares the default device clipboard and all virtual devices that
        //     the app is running on do the same.
        int clipboardDeviceId = deviceUsesDefaultClipboard(requestedDeviceId)
                ? DEVICE_ID_DEFAULT
                : requestedDeviceId;

        if (mVdmInternal.getDeviceOwnerUid(requestedDeviceId) == uid
                || virtualDeviceIds.contains(requestedDeviceId)
                || (clipboardDeviceId == DEVICE_ID_DEFAULT && allDevicesHaveDefaultClipboard)) {
            return clipboardDeviceId;
        }

        // Fallback to the device where the app is running, unless it uses the default clipboard.
        int fallbackDeviceId = virtualDeviceIds.valueAt(0);
        return deviceUsesDefaultClipboard(fallbackDeviceId) ? DEVICE_ID_DEFAULT : fallbackDeviceId;
    }

    private boolean deviceUsesDefaultClipboard(int deviceId) {
        if (deviceId == DEVICE_ID_DEFAULT || mVdm == null) {
            return true;
        }
        return mVdm.getDevicePolicy(deviceId, POLICY_TYPE_CLIPBOARD) == DEVICE_POLICY_CUSTOM;
    }

    /**
     * To handle the difference between userId and intendingUserId, uid and intendingUid.
     *
     * userId means that comes from the calling side and should be validated by
     * ActivityManagerInternal.handleIncomingUser.
     * After validation of ActivityManagerInternal.handleIncomingUser, the userId is called
     * 'intendingUserId' and the uid is called 'intendingUid'.
     */
    private class ClipboardImpl extends IClipboard.Stub {

        private final Handler mClipboardClearHandler = new ClipboardClearHandler(
                mWorkerHandler.getLooper());

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                if (!(e instanceof SecurityException)) {
                    Slog.wtf("clipboard", "Exception: ", e);
                }
                throw e;
            }

        }

        @Override
        public void setPrimaryClip(
                ClipData clip,
                String callingPackage,
                String attributionTag,
                @UserIdInt int userId,
                int deviceId) {
            checkAndSetPrimaryClip(clip, callingPackage, attributionTag, userId, deviceId,
                    callingPackage);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.SET_CLIP_SOURCE)
        @Override
        public void setPrimaryClipAsPackage(
                ClipData clip,
                String callingPackage,
                String attributionTag,
                @UserIdInt int userId,
                int deviceId,
                String sourcePackage) {
            setPrimaryClipAsPackage_enforcePermission();
            checkAndSetPrimaryClip(clip, callingPackage, attributionTag, userId, deviceId,
                    sourcePackage);
        }

        @Override
        public boolean areClipboardAccessNotificationsEnabledForUser(int userId) {
            int result = getContext().checkCallingOrSelfPermission(
                    Manifest.permission.MANAGE_CLIPBOARD_ACCESS_NOTIFICATION);
            if (result != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("areClipboardAccessNotificationsEnable requires "
                        + "permission MANAGE_CLIPBOARD_ACCESS_NOTIFICATION");
            }

            long callingId = Binder.clearCallingIdentity();
            try {
                return Settings.Secure.getIntForUser(getContext().getContentResolver(),
                        Settings.Secure.CLIPBOARD_SHOW_ACCESS_NOTIFICATIONS,
                        getDefaultClipboardAccessNotificationsSetting(), userId) != 0;
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }

        @Override
        public void setClipboardAccessNotificationsEnabledForUser(boolean enable, int userId) {
            int result = getContext().checkCallingOrSelfPermission(
                    Manifest.permission.MANAGE_CLIPBOARD_ACCESS_NOTIFICATION);
            if (result != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("areClipboardAccessNotificationsEnable requires "
                        + "permission MANAGE_CLIPBOARD_ACCESS_NOTIFICATION");
            }

            long callingId = Binder.clearCallingIdentity();
            try {
                ContentResolver resolver = getContext()
                        .createContextAsUser(UserHandle.of(userId), 0).getContentResolver();
                Settings.Secure.putInt(resolver,
                        Settings.Secure.CLIPBOARD_SHOW_ACCESS_NOTIFICATIONS, (enable ? 1 : 0));
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }

        private int getDefaultClipboardAccessNotificationsSetting() {
            return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_CLIPBOARD,
                    ClipboardManager.DEVICE_CONFIG_SHOW_ACCESS_NOTIFICATIONS,
                    ClipboardManager.DEVICE_CONFIG_DEFAULT_SHOW_ACCESS_NOTIFICATIONS) ? 1 : 0;
        }

        private void checkAndSetPrimaryClip(
                ClipData clip,
                String callingPackage,
                String attributionTag,
                @UserIdInt int userId,
                int deviceId,
                String sourcePackage) {
            if (clip == null || clip.getItemCount() <= 0) {
                throw new IllegalArgumentException("No items");
            }
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            final int intendingDeviceId = getIntendingDeviceId(deviceId, intendingUid);
            if (!clipboardAccessAllowed(
                    AppOpsManager.OP_WRITE_CLIPBOARD,
                    callingPackage,
                    attributionTag,
                    intendingUid,
                    intendingUserId,
                    intendingDeviceId)) {
                return;
            }
            checkDataOwner(clip, intendingUid);
            synchronized (mLock) {
                scheduleAutoClear(userId, intendingUid, intendingDeviceId);
                setPrimaryClipInternalLocked(clip, intendingUid, intendingDeviceId, sourcePackage);
            }
        }

        private void scheduleAutoClear(
                @UserIdInt int userId, int intendingUid, int intendingDeviceId) {
            final long oldIdentity = Binder.clearCallingIdentity();
            try {
                if (DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_CLIPBOARD,
                        PROPERTY_AUTO_CLEAR_ENABLED, true)) {
                    Pair<Integer, Integer> userIdDeviceId = new Pair<>(userId, intendingDeviceId);
                    mClipboardClearHandler.removeEqualMessages(ClipboardClearHandler.MSG_CLEAR,
                            userIdDeviceId);
                    Message clearMessage =
                            Message.obtain(
                                    mClipboardClearHandler,
                                    ClipboardClearHandler.MSG_CLEAR,
                                    userId,
                                    intendingUid,
                                    userIdDeviceId);
                    mClipboardClearHandler.sendMessageDelayed(clearMessage,
                            getTimeoutForAutoClear());
                }
            } finally {
                Binder.restoreCallingIdentity(oldIdentity);
            }
        }

        private long getTimeoutForAutoClear() {
            return DeviceConfig.getLong(DeviceConfig.NAMESPACE_CLIPBOARD,
                    PROPERTY_AUTO_CLEAR_TIMEOUT,
                    DEFAULT_CLIPBOARD_TIMEOUT_MILLIS);
        }

        @Override
        public void clearPrimaryClip(
                String callingPackage, String attributionTag, @UserIdInt int userId, int deviceId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            final int intendingDeviceId = getIntendingDeviceId(deviceId, intendingUid);
            if (!clipboardAccessAllowed(
                    AppOpsManager.OP_WRITE_CLIPBOARD,
                    callingPackage,
                    attributionTag,
                    intendingUid,
                    intendingUserId,
                    intendingDeviceId)) {
                return;
            }
            synchronized (mLock) {
                mClipboardClearHandler.removeEqualMessages(ClipboardClearHandler.MSG_CLEAR,
                        new Pair<>(userId, deviceId));
                setPrimaryClipInternalLocked(null, intendingUid, intendingDeviceId, callingPackage);
            }
        }

        @Override
        public ClipData getPrimaryClip(
                String pkg, String attributionTag, @UserIdInt int userId, int deviceId) {
            final int intendingUid = getIntendingUid(pkg, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            final int intendingDeviceId = getIntendingDeviceId(deviceId, intendingUid);
            if (!clipboardAccessAllowed(
                            AppOpsManager.OP_READ_CLIPBOARD,
                            pkg,
                            attributionTag,
                            intendingUid,
                            intendingUserId,
                            intendingDeviceId)
                    || isDeviceLocked(intendingUserId, deviceId)) {
                return null;
            }
            synchronized (mLock) {
                try {
                    addActiveOwnerLocked(intendingUid, intendingDeviceId, pkg);
                } catch (SecurityException e) {
                    // Permission could not be granted - URI may be invalid
                    Slog.i(TAG, "Could not grant permission to primary clip. Clearing clipboard.");
                    setPrimaryClipInternalLocked(null, intendingUid, intendingDeviceId, pkg);
                    return null;
                }

                Clipboard clipboard = getClipboardLocked(intendingUserId, intendingDeviceId);
                if (clipboard == null) {
                    return null;
                }
                showAccessNotificationLocked(pkg, intendingUid, intendingUserId, clipboard);
                notifyTextClassifierLocked(clipboard, pkg, intendingUid);
                if (clipboard.primaryClip != null) {
                    scheduleAutoClear(userId, intendingUid, intendingDeviceId);
                }
                return clipboard.primaryClip;
            }
        }

        @Override
        public ClipDescription getPrimaryClipDescription(
                String callingPackage, String attributionTag, @UserIdInt int userId, int deviceId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            final int intendingDeviceId = getIntendingDeviceId(deviceId, intendingUid);
            if (!clipboardAccessAllowed(
                            AppOpsManager.OP_READ_CLIPBOARD,
                            callingPackage,
                            attributionTag,
                            intendingUid,
                            intendingUserId,
                            intendingDeviceId,
                            false)
                    || isDeviceLocked(intendingUserId, deviceId)) {
                return null;
            }
            synchronized (mLock) {
                Clipboard clipboard = getClipboardLocked(intendingUserId, intendingDeviceId);
                return (clipboard != null && clipboard.primaryClip != null)
                        ? clipboard.primaryClip.getDescription() : null;
            }
        }

        @Override
        public boolean hasPrimaryClip(
                String callingPackage, String attributionTag, @UserIdInt int userId, int deviceId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            final int intendingDeviceId = getIntendingDeviceId(deviceId, intendingUid);
            if (!clipboardAccessAllowed(
                            AppOpsManager.OP_READ_CLIPBOARD,
                            callingPackage,
                            attributionTag,
                            intendingUid,
                            intendingUserId,
                            intendingDeviceId,
                            false)
                    || isDeviceLocked(intendingUserId, deviceId)) {
                return false;
            }
            synchronized (mLock) {
                Clipboard clipboard = getClipboardLocked(intendingUserId, intendingDeviceId);
                return clipboard != null && clipboard.primaryClip != null;
            }
        }

        @Override
        public void addPrimaryClipChangedListener(
                IOnPrimaryClipChangedListener listener,
                String callingPackage,
                String attributionTag,
                @UserIdInt int userId,
                int deviceId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            final int intendingDeviceId = getIntendingDeviceId(deviceId, intendingUid);
            if (intendingDeviceId == DEVICE_ID_INVALID) {
                Slog.i(TAG, "addPrimaryClipChangedListener invalid deviceId for userId:"
                        + userId + " uid:" + intendingUid + " callingPackage:" + callingPackage
                        + " requestedDeviceId:" + deviceId);
                return;
            }
            synchronized (mLock) {
                Clipboard clipboard = getClipboardLocked(intendingUserId, intendingDeviceId);
                if (clipboard == null) {
                    return;
                }
                clipboard.primaryClipListeners
                        .register(
                                listener,
                                new ListenerInfo(intendingUid, callingPackage, attributionTag));
            }
        }

        @Override
        public void removePrimaryClipChangedListener(
                IOnPrimaryClipChangedListener listener,
                String callingPackage,
                String attributionTag,
                @UserIdInt int userId,
                int deviceId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = getIntendingUserId(callingPackage, userId);
            final int intendingDeviceId = getIntendingDeviceId(deviceId, intendingUid);
            if (intendingDeviceId == DEVICE_ID_INVALID) {
                Slog.i(TAG, "removePrimaryClipChangedListener invalid deviceId for userId:"
                        + userId + " uid:" + intendingUid + " callingPackage:" + callingPackage);
                return;
            }
            synchronized (mLock) {
                Clipboard clipboard = getClipboardLocked(intendingUserId,
                                intendingDeviceId);
                if (clipboard != null) {
                    clipboard.primaryClipListeners.unregister(listener);
                }
            }
        }

        @Override
        public boolean hasClipboardText(
                String callingPackage, String attributionTag, int userId, int deviceId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            final int intendingDeviceId = getIntendingDeviceId(deviceId, intendingUid);
            if (!clipboardAccessAllowed(
                            AppOpsManager.OP_READ_CLIPBOARD,
                            callingPackage,
                            attributionTag,
                            intendingUid,
                            intendingUserId,
                            intendingDeviceId,
                            false)
                    || isDeviceLocked(intendingUserId, deviceId)) {
                return false;
            }
            synchronized (mLock) {
                Clipboard clipboard = getClipboardLocked(intendingUserId, intendingDeviceId);
                if (clipboard != null && clipboard.primaryClip != null) {
                    CharSequence text = clipboard.primaryClip.getItemAt(0).getText();
                    return text != null && text.length() > 0;
                }
                return false;
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.SET_CLIP_SOURCE)
        @Override
        public String getPrimaryClipSource(
                String callingPackage, String attributionTag, int userId, int deviceId) {
            getPrimaryClipSource_enforcePermission();
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            final int intendingDeviceId = getIntendingDeviceId(deviceId, intendingUid);
            if (!clipboardAccessAllowed(
                            AppOpsManager.OP_READ_CLIPBOARD,
                            callingPackage,
                            attributionTag,
                            intendingUid,
                            intendingUserId,
                            intendingDeviceId,
                            false)
                    || isDeviceLocked(intendingUserId, deviceId)) {
                return null;
            }
            synchronized (mLock) {
                Clipboard clipboard = getClipboardLocked(intendingUserId, intendingDeviceId);
                if (clipboard != null && clipboard.primaryClip != null) {
                    return clipboard.mPrimaryClipPackage;
                }
                return null;
            }
        }

        private class ClipboardClearHandler extends Handler {

            public static final int MSG_CLEAR = 101;

            ClipboardClearHandler(Looper looper) {
                super(looper);
            }

            public void handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_CLEAR:
                        final int userId = msg.arg1;
                        final int intendingUid = msg.arg2;
                        final int intendingDeviceId = ((Pair<Integer, Integer>) msg.obj).second;
                        synchronized (mLock) {
                            Clipboard clipboard = getClipboardLocked(userId, intendingDeviceId);
                            if (clipboard != null && clipboard.primaryClip != null) {
                                FrameworkStatsLog.write(FrameworkStatsLog.CLIPBOARD_CLEARED,
                                        FrameworkStatsLog.CLIPBOARD_CLEARED__SOURCE__AUTO_CLEAR);
                                setPrimaryClipInternalLocked(
                                        null, intendingUid, intendingDeviceId, null);
                            }
                        }
                        break;
                    default:
                        Slog.wtf(TAG, "ClipboardClearHandler received unknown message " + msg.what);
                }
            }
        }
    };

    @GuardedBy("mLock")
    private @Nullable Clipboard getClipboardLocked(@UserIdInt int userId, int deviceId) {
        Clipboard clipboard = mClipboards.get(userId, deviceId);
        if (clipboard == null) {
            try {
                if (!mUm.isUserRunning(userId)) {
                    Slog.w(TAG, "getClipboardLocked called with not running userId " + userId);
                    return null;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException calling UserManager: " + e);
                return null;
            }
            if (deviceId != DEVICE_ID_DEFAULT
                    && mVdm != null && !mVdm.isValidVirtualDeviceId(deviceId)) {
                Slog.w(TAG, "getClipboardLocked called with invalid (possibly released) deviceId "
                        + deviceId);
                return null;
            }
            clipboard = new Clipboard(userId, deviceId);
            mClipboards.add(userId, deviceId, clipboard);
        }
        return clipboard;
    }

    List<UserInfo> getRelatedProfiles(@UserIdInt int userId) {
        final List<UserInfo> related;
        final long origId = Binder.clearCallingIdentity();
        try {
            related = mUm.getProfiles(userId, true);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception calling UserManager: " + e);
            return null;
        } finally{
            Binder.restoreCallingIdentity(origId);
        }
        return related;
    }

    /** Check if the user has the given restriction set. Default to true if error occured during
     * calling UserManager, so it fails safe.
     */
    private boolean hasRestriction(String restriction, int userId) {
        try {
            return mUm.hasUserRestriction(restriction, userId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception calling UserManager.getUserRestrictions: ", e);
            // Fails safe
            return true;
        }
    }

    void setPrimaryClipInternal(@Nullable ClipData clip, int uid) {
        synchronized (mLock) {
            setPrimaryClipInternalLocked(clip, uid, DEVICE_ID_DEFAULT, null);
        }
    }

    @GuardedBy("mLock")
    private void setPrimaryClipInternalLocked(
            @Nullable ClipData clip, int uid, int deviceId, @Nullable String sourcePackage) {
        if (deviceId == DEVICE_ID_DEFAULT) {
            mClipboardMonitor.accept(clip);
        }

        final int userId = UserHandle.getUserId(uid);

        // Update this user
        Clipboard clipboard = getClipboardLocked(userId, deviceId);
        if (clipboard == null) {
            return;
        }
        setPrimaryClipInternalLocked(clipboard, clip, uid, sourcePackage);

        // Update related users
        List<UserInfo> related = getRelatedProfiles(userId);
        if (related != null) {
            int size = related.size();
            if (size > 1) { // Related profiles list include the current profile.
                final boolean canCopy = !hasRestriction(
                        UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE, userId);
                // Copy clip data to related users if allowed. If disallowed, then remove
                // primary clip in related users to prevent pasting stale content.
                if (!canCopy) {
                    clip = null;
                } else if (clip == null) {
                    // do nothing for canCopy == true and clip == null case
                    // To prevent from NPE happen in 'new ClipData(clip)' when run
                    // android.content.cts.ClipboardManagerTest#testClearPrimaryClip
                } else {
                    // We want to fix the uris of the related user's clip without changing the
                    // uris of the current user's clip.
                    // So, copy the ClipData, and then copy all the items, so that nothing
                    // is shared in memory.
                    clip = new ClipData(clip);
                    for (int i = clip.getItemCount() - 1; i >= 0; i--) {
                        clip.setItemAt(i, new ClipData.Item(clip.getItemAt(i)));
                    }
                    clip.fixUrisLight(userId);
                }
                for (int i = 0; i < size; i++) {
                    int id = related.get(i).id;
                    if (id != userId) {
                        final boolean canCopyIntoProfile = !hasRestriction(
                                UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE, id);
                        if (canCopyIntoProfile) {
                            Clipboard relatedClipboard = getClipboardLocked(id, deviceId);
                            if (relatedClipboard != null) {
                                setPrimaryClipInternalNoClassifyLocked(relatedClipboard, clip, uid,
                                        sourcePackage);
                            }
                        }
                    }
                }
            }
        }
    }

    void setPrimaryClipInternal(Clipboard clipboard, @Nullable ClipData clip,
            int uid) {
        synchronized (mLock) {
            setPrimaryClipInternalLocked(clipboard, clip, uid, null);
        }
    }

    @GuardedBy("mLock")
    private void setPrimaryClipInternalLocked(Clipboard clipboard, @Nullable ClipData clip,
            int uid, @Nullable String sourcePackage) {
        final int userId = UserHandle.getUserId(uid);
        if (clip != null) {
            startClassificationLocked(clip, userId, clipboard.deviceId);
        }

        setPrimaryClipInternalNoClassifyLocked(clipboard, clip, uid, sourcePackage);
    }

    @GuardedBy("mLock")
    private void setPrimaryClipInternalNoClassifyLocked(Clipboard clipboard,
            @Nullable ClipData clip, int uid, @Nullable String sourcePackage) {
        revokeUris(clipboard);
        clipboard.activePermissionOwners.clear();
        if (clip == null && clipboard.primaryClip == null) {
            return;
        }
        clipboard.primaryClip = clip;
        clipboard.mNotifiedUids.clear();
        clipboard.mNotifiedTextClassifierUids.clear();
        if (clip != null) {
            clipboard.primaryClipUid = uid;
            clipboard.mPrimaryClipPackage = sourcePackage;
        } else {
            clipboard.primaryClipUid = android.os.Process.NOBODY_UID;
            clipboard.mPrimaryClipPackage = null;
        }
        if (clip != null) {
            final ClipDescription description = clip.getDescription();
            if (description != null) {
                description.setTimestamp(System.currentTimeMillis());
            }
        }
        sendClipChangedBroadcast(clipboard);
    }

    private void sendClipChangedBroadcast(Clipboard clipboard) {
        final long ident = Binder.clearCallingIdentity();
        final int n = clipboard.primaryClipListeners.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    ListenerInfo li = (ListenerInfo)
                            clipboard.primaryClipListeners.getBroadcastCookie(i);

                    if (clipboardAccessAllowed(
                            AppOpsManager.OP_READ_CLIPBOARD,
                            li.mPackageName,
                            li.mAttributionTag,
                            li.mUid,
                            UserHandle.getUserId(li.mUid),
                            clipboard.deviceId)) {
                        clipboard.primaryClipListeners.getBroadcastItem(i)
                                .dispatchPrimaryClipChanged();
                    }
                } catch (RemoteException | SecurityException e) {
                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
        } finally {
            clipboard.primaryClipListeners.finishBroadcast();
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("mLock")
    private void startClassificationLocked(@NonNull ClipData clip, @UserIdInt int userId,
            int deviceId) {
        CharSequence text = (clip.getItemCount() == 0) ? null : clip.getItemAt(0).getText();
        if (TextUtils.isEmpty(text) || text.length() > mMaxClassificationLength) {
            clip.getDescription().setClassificationStatus(
                    ClipDescription.CLASSIFICATION_NOT_PERFORMED);
            return;
        }
        TextClassifier classifier;
        final long ident = Binder.clearCallingIdentity();
        try {
            classifier = createTextClassificationManagerAsUser(userId)
                    .createTextClassificationSession(
                            new TextClassificationContext.Builder(
                                    getContext().getPackageName(),
                                    TextClassifier.WIDGET_TYPE_CLIPBOARD
                            ).build()
                    );
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (text.length() > classifier.getMaxGenerateLinksTextLength()) {
            clip.getDescription().setClassificationStatus(
                    ClipDescription.CLASSIFICATION_NOT_PERFORMED);
            return;
        }
        mWorkerHandler.post(() -> doClassification(text, clip, classifier, userId, deviceId));
    }

    @WorkerThread
    private void doClassification(
            CharSequence text, ClipData clip, TextClassifier classifier, @UserIdInt int userId,
            int deviceId) {
        TextLinks.Request request = new TextLinks.Request.Builder(text).build();
        TextLinks links = classifier.generateLinks(request);

        // Find the highest confidence for each entity in the text.
        ArrayMap<String, Float> confidences = new ArrayMap<>();
        for (TextLinks.TextLink link : links.getLinks()) {
            for (int i = 0; i < link.getEntityCount(); i++) {
                String entity = link.getEntity(i);
                float conf = link.getConfidenceScore(entity);
                if (conf > confidences.getOrDefault(entity, 0f)) {
                    confidences.put(entity, conf);
                }
            }
        }

        synchronized (mLock) {
            Clipboard clipboard = getClipboardLocked(userId, deviceId);
            if (clipboard == null) {
                return;
            }
            if (clipboard.primaryClip == clip) {
                applyClassificationAndSendBroadcastLocked(
                        clipboard, confidences, links, classifier);

                // Also apply to related profiles if needed
                List<UserInfo> related = getRelatedProfiles(userId);
                if (related != null) {
                    int size = related.size();
                    for (int i = 0; i < size; i++) {
                        int id = related.get(i).id;
                        if (id != userId) {
                            final boolean canCopyIntoProfile = !hasRestriction(
                                    UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE, id);
                            if (canCopyIntoProfile) {
                                Clipboard relatedClipboard = getClipboardLocked(id, deviceId);
                                if (relatedClipboard != null
                                        && hasTextLocked(relatedClipboard, text)) {
                                    applyClassificationAndSendBroadcastLocked(
                                            relatedClipboard, confidences, links, classifier);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void applyClassificationAndSendBroadcastLocked(
            Clipboard clipboard, ArrayMap<String, Float> confidences, TextLinks links,
            TextClassifier classifier) {
        clipboard.mTextClassifier = classifier;
        clipboard.primaryClip.getDescription().setConfidenceScores(confidences);
        if (!links.getLinks().isEmpty()) {
            clipboard.primaryClip.getItemAt(0).setTextLinks(links);
        }
        sendClipChangedBroadcast(clipboard);
    }

    @GuardedBy("mLock")
    private boolean hasTextLocked(Clipboard clipboard, @NonNull CharSequence text) {
        return clipboard.primaryClip != null
                && clipboard.primaryClip.getItemCount() > 0
                && text.equals(clipboard.primaryClip.getItemAt(0).getText());
    }

    private boolean isDeviceLocked(@UserIdInt int userId, int deviceId) {
        final long token = Binder.clearCallingIdentity();
        try {
            final KeyguardManager keyguardManager = getContext().getSystemService(
                    KeyguardManager.class);
            return keyguardManager != null && keyguardManager.isDeviceLocked(userId, deviceId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void checkUriOwner(Uri uri, int sourceUid) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;

        final long ident = Binder.clearCallingIdentity();
        try {
            // This will throw SecurityException if caller can't grant
            mUgmInternal.checkGrantUriPermission(sourceUid, null,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void checkItemOwner(ClipData.Item item, int uid) {
        if (item.getUri() != null) {
            checkUriOwner(item.getUri(), uid);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            checkUriOwner(intent.getData(), uid);
        }
    }

    private void checkDataOwner(ClipData data, int uid) {
        final int N = data.getItemCount();
        for (int i=0; i<N; i++) {
            checkItemOwner(data.getItemAt(i), uid);
        }
    }

    private void grantUriPermission(Uri uri, int sourceUid, String targetPkg,
            int targetUserId) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;

        final long ident = Binder.clearCallingIdentity();
        try {
            mUgm.grantUriPermissionFromOwner(mPermissionOwner, sourceUid, targetPkg,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)),
                    targetUserId);
        } catch (RemoteException ignored) {
            // Ignored because we're in same process
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void grantItemPermission(ClipData.Item item, int sourceUid, String targetPkg,
            int targetUserId) {
        if (item.getUri() != null) {
            grantUriPermission(item.getUri(), sourceUid, targetPkg, targetUserId);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            grantUriPermission(intent.getData(), sourceUid, targetPkg, targetUserId);
        }
    }

    @GuardedBy("mLock")
    private void addActiveOwnerLocked(int uid, int deviceId, String pkg) {
        final PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        final int targetUserHandle = UserHandle.getCallingUserId();
        final long oldIdentity = Binder.clearCallingIdentity();
        try {
            if (!pm.isSameApp(pkg, 0, uid, targetUserHandle)) {
                throw new SecurityException("Calling uid " + uid + " does not own package " + pkg);
            }
        } finally {
            Binder.restoreCallingIdentity(oldIdentity);
        }
        Clipboard clipboard = getClipboardLocked(UserHandle.getUserId(uid), deviceId);
        if (clipboard != null && clipboard.primaryClip != null
                && !clipboard.activePermissionOwners.contains(pkg)) {
            final int N = clipboard.primaryClip.getItemCount();
            for (int i = 0; i < N; i++) {
                grantItemPermission(clipboard.primaryClip.getItemAt(i), clipboard.primaryClipUid,
                        pkg, UserHandle.getUserId(uid));
            }
            clipboard.activePermissionOwners.add(pkg);
        }
    }

    private void revokeUriPermission(Uri uri, int sourceUid) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;

        final long ident = Binder.clearCallingIdentity();
        try {
            mUgmInternal.revokeUriPermissionFromOwner(mPermissionOwner,
                    ContentProvider.getUriWithoutUserId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void revokeItemPermission(ClipData.Item item, int sourceUid) {
        if (item.getUri() != null) {
            revokeUriPermission(item.getUri(), sourceUid);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            revokeUriPermission(intent.getData(), sourceUid);
        }
    }

    private void revokeUris(Clipboard clipboard) {
        if (clipboard.primaryClip == null) {
            return;
        }
        final int N = clipboard.primaryClip.getItemCount();
        for (int i=0; i<N; i++) {
            revokeItemPermission(clipboard.primaryClip.getItemAt(i), clipboard.primaryClipUid);
        }
    }

    private boolean clipboardAccessAllowed(
            int op,
            String callingPackage,
            String attributionTag,
            int uid,
            @UserIdInt int userId,
            int intendingDeviceId) {
        return clipboardAccessAllowed(op, callingPackage, attributionTag, uid, userId,
                intendingDeviceId, true);
    }

    private boolean clipboardAccessAllowed(
            int op,
            String callingPackage,
            String attributionTag,
            int uid,
            @UserIdInt int userId,
            int intendingDeviceId,
            boolean shouldNoteOp) {

        boolean allowed;

        // First, verify package ownership to ensure use below is safe.
        mAppOps.checkPackage(uid, callingPackage);

        if (intendingDeviceId == DEVICE_ID_INVALID) {
            Slog.w(TAG, "Clipboard access denied to " + uid + "/" + callingPackage
                    + " due to invalid device id");
            return false;
        }

        // Shell can access the clipboard for testing purposes.
        if (mPm.checkPermission(android.Manifest.permission.READ_CLIPBOARD_IN_BACKGROUND,
                    callingPackage) == PackageManager.PERMISSION_GRANTED) {
            allowed = true;
        } else {
            // The default IME is always allowed to access the clipboard.
            allowed = isDefaultIme(userId, callingPackage);
        }

        switch (op) {
            case AppOpsManager.OP_READ_CLIPBOARD:
                // Clipboard can only be read by applications with focus..
                // or the application have the INTERNAL_SYSTEM_WINDOW and INTERACT_ACROSS_USERS_FULL
                // at the same time. e.x. SystemUI. It needs to check the window focus of
                // Binder.getCallingUid(). Without checking, the user X can't copy any thing from
                // INTERNAL_SYSTEM_WINDOW to the other applications.
                if (!allowed) {
                    allowed = isDefaultDeviceAndUidFocused(intendingDeviceId, uid)
                            || isVirtualDeviceAndUidFocused(intendingDeviceId, uid)
                            || isInternalSysWindowAppWithWindowFocus(callingPackage);
                }
                if (!allowed && mContentCaptureInternal != null) {
                    // ...or the Content Capture Service
                    // The uid parameter of mContentCaptureInternal.isContentCaptureServiceForUser
                    // is used to check if the uid has the permission BIND_CONTENT_CAPTURE_SERVICE.
                    // if the application has the permission, let it to access user's clipboard.
                    // To passed synthesized uid user#10_app#systemui may not tell the real uid.
                    // userId must pass intending userId. i.e. user#10.
                    allowed = mContentCaptureInternal.isContentCaptureServiceForUser(uid, userId);
                }
                if (!allowed && mAutofillInternal != null) {
                    // ...or the Augmented Autofill Service
                    // The uid parameter of mAutofillInternal.isAugmentedAutofillServiceForUser
                    // is used to check if the uid has the permission BIND_AUTOFILL_SERVICE.
                    // if the application has the permission, let it to access user's clipboard.
                    // To passed synthesized uid user#10_app#systemui may not tell the real uid.
                    // userId must pass intending userId. i.e. user#10.
                    allowed = mAutofillInternal.isAugmentedAutofillServiceForUser(uid, userId);
                }
                if (!allowed && intendingDeviceId != DEVICE_ID_DEFAULT) {
                    // Privileged apps which own a VirtualDevice are allowed to read its clipboard
                    // in the background.
                    allowed = (mVdmInternal != null)
                            && mVdmInternal.getDeviceOwnerUid(intendingDeviceId) == uid;
                }
                break;
            case AppOpsManager.OP_WRITE_CLIPBOARD:
                // Writing is allowed without focus.
                allowed = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown clipboard appop " + op);
        }
        if (!allowed) {
            Slog.e(TAG, "Denying clipboard access to " + callingPackage
                    + ", application is not in focus nor is it a system service for "
                    + "user " + userId);
            return false;
        }
        // Finally, check the app op.
        int appOpsResult;
        if (shouldNoteOp) {
            appOpsResult = mAppOps.noteOp(op, uid, callingPackage, attributionTag, null);
        } else {
            appOpsResult = mAppOps.checkOp(op, uid, callingPackage);
        }

        return appOpsResult == AppOpsManager.MODE_ALLOWED;
    }

    private boolean isDefaultDeviceAndUidFocused(int intendingDeviceId, int uid) {
        return intendingDeviceId == DEVICE_ID_DEFAULT && mWm.isUidFocused(uid);
    }

    private boolean isVirtualDeviceAndUidFocused(int intendingDeviceId, int uid) {
        if (intendingDeviceId == DEVICE_ID_DEFAULT || mVdm == null) {
            return false;
        }
        int topFocusedDisplayId = mWm.getTopFocusedDisplayId();
        int focusedDeviceId = mVdm.getDeviceIdForDisplayId(topFocusedDisplayId);
        return (focusedDeviceId == intendingDeviceId) && mWm.isUidFocused(uid);
    }

    private boolean isDefaultIme(int userId, String packageName) {
        String defaultIme = Settings.Secure.getStringForUser(getContext().getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD, userId);
        if (!TextUtils.isEmpty(defaultIme)) {
            final ComponentName imeComponent = ComponentName.unflattenFromString(defaultIme);
            if (imeComponent == null) {
                return false;
            }
            final String imePkg = imeComponent.getPackageName();
            return imePkg.equals(packageName);
        }
        return false;
    }

    /**
     * Shows a toast to inform the user that an app has accessed the clipboard. This is only done if
     * the setting is enabled, and if the accessing app is not the source of the data and is not the
     * IME, the content capture service, or the autofill service. The notification is also only
     * shown once per clip for each app.
     */
    @GuardedBy("mLock")
    private void showAccessNotificationLocked(String callingPackage, int uid, @UserIdInt int userId,
            Clipboard clipboard) {
        if (clipboard.primaryClip == null) {
            return;
        }
        if (Settings.Secure.getInt(getContext().getContentResolver(),
                Settings.Secure.CLIPBOARD_SHOW_ACCESS_NOTIFICATIONS,
                (mShowAccessNotifications ? 1 : 0)) == 0) {
            return;
        }
        // Don't notify if the app accessing the clipboard is the same as the current owner.
        if (UserHandle.isSameApp(uid, clipboard.primaryClipUid)) {
            return;
        }
        // Exclude special cases: IME, ContentCapture, Autofill.
        if (isDefaultIme(userId, callingPackage)) {
            return;
        }
        if (mContentCaptureInternal != null
                && mContentCaptureInternal.isContentCaptureServiceForUser(uid, userId)) {
            return;
        }
        if (mAutofillInternal != null
                && mAutofillInternal.isAugmentedAutofillServiceForUser(uid, userId)) {
            return;
        }
        if (mPm.checkPermission(Manifest.permission.SUPPRESS_CLIPBOARD_ACCESS_NOTIFICATION,
                callingPackage) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // Don't notify if this access is coming from the privileged app which owns the device.
        if (clipboard.deviceId != DEVICE_ID_DEFAULT && mVdmInternal != null
                && mVdmInternal.getDeviceOwnerUid(clipboard.deviceId) == uid) {
            return;
        }
        // Don't notify if already notified for this uid and clip.
        if (clipboard.mNotifiedUids.get(uid)) {
            return;
        }

        final ArraySet<Context> toastContexts = getToastContexts(clipboard);
        Binder.withCleanCallingIdentity(() -> {
            try {
                CharSequence callingAppLabel = mPm.getApplicationLabel(
                        mPm.getApplicationInfoAsUser(callingPackage, 0, userId));
                String message =
                        getContext().getString(R.string.pasted_from_clipboard, callingAppLabel);
                Slog.i(TAG, message);
                for (int i = 0; i < toastContexts.size(); i++) {
                    Context toastContext = toastContexts.valueAt(i);
                    Toast toastToShow;
                    if (SafetyProtectionUtils.shouldShowSafetyProtectionResources(getContext())) {
                        Drawable safetyProtectionIcon = getContext()
                                .getDrawable(R.drawable.ic_safety_protection);
                        toastToShow = Toast.makeCustomToastWithIcon(toastContext,
                                UiThread.get().getLooper(), message,
                                Toast.LENGTH_LONG, safetyProtectionIcon);
                    } else {
                        toastToShow = Toast.makeText(
                                toastContext, UiThread.get().getLooper(), message,
                                Toast.LENGTH_LONG);
                    }
                    toastToShow.show();
                }
            } catch (PackageManager.NameNotFoundException e) {
                // do nothing
            }
        });

        clipboard.mNotifiedUids.put(uid, true);
    }

    /**
     * Returns the context(s) to use for toasts related to this clipboard. Normally this will just
     * contain a single context referencing the default display.
     *
     * If the clipboard is for a VirtualDevice, we attempt to return the single DisplayContext for
     * the focused VirtualDisplay for that device, but might need to return the contexts for
     * multiple displays if the VirtualDevice has several but none of them were focused.
     */
    private ArraySet<Context> getToastContexts(Clipboard clipboard) throws IllegalStateException {
        ArraySet<Context> contexts = new ArraySet<>();

        if (mVdmInternal != null && clipboard.deviceId != DEVICE_ID_DEFAULT) {
            DisplayManager displayManager = getContext().getSystemService(DisplayManager.class);

            int topFocusedDisplayId = mWm.getTopFocusedDisplayId();
            ArraySet<Integer> displayIds = mVdmInternal.getDisplayIdsForDevice(clipboard.deviceId);

            if (displayIds.contains(topFocusedDisplayId)) {
                Display display = displayManager.getDisplay(topFocusedDisplayId);
                if (display != null) {
                    contexts.add(getContext().createDisplayContext(display));
                    return contexts;
                }
            }

            for (int i = 0; i < displayIds.size(); i++) {
                Display display = displayManager.getDisplay(displayIds.valueAt(i));
                if (display != null) {
                    contexts.add(getContext().createDisplayContext(display));
                }
            }
            if (!contexts.isEmpty()) {
                return contexts;
            }
            Slog.e(TAG, "getToastContexts Couldn't find any VirtualDisplays for VirtualDevice "
                    + clipboard.deviceId);
            // Since we couldn't find any VirtualDisplays to use at all, just fall through to using
            // the default display below.
        }

        contexts.add(getContext());
        return contexts;
    }

    /**
     * Returns true if the provided {@link ClipData} represents a single piece of text. That is, if
     * there is only on {@link ClipData.Item}, and that item contains a non-empty piece of text and
     * no URI or Intent. Note that HTML may be provided along with text so the presence of
     * HtmlText in the clip does not prevent this method returning true.
     */
    private static boolean isText(@NonNull ClipData data) {
        if (data.getItemCount() > 1) {
            return false;
        }
        ClipData.Item item = data.getItemAt(0);

        return !TextUtils.isEmpty(item.getText()) && item.getUri() == null
                && item.getIntent() == null;
    }

    /** Potentially notifies the text classifier that an app is accessing a text clip. */
    @GuardedBy("mLock")
    private void notifyTextClassifierLocked(
            Clipboard clipboard, String callingPackage, int callingUid) {
        if (clipboard.primaryClip == null) {
            return;
        }
        ClipData.Item item = clipboard.primaryClip.getItemAt(0);
        if (item == null) {
            return;
        }
        if (!isText(clipboard.primaryClip)) {
            return;
        }
        TextClassifier textClassifier = clipboard.mTextClassifier;
        // Don't notify text classifier if we haven't used it to annotate the text in the clip.
        if (textClassifier == null) {
            return;
        }
        // Don't notify text classifier if the app reading the clipboard does not have the focus.
        if (!mWm.isUidFocused(callingUid)) {
            return;
        }
        // Don't notify text classifier again if already notified for this uid and clip.
        if (clipboard.mNotifiedTextClassifierUids.get(callingUid)) {
            return;
        }
        clipboard.mNotifiedTextClassifierUids.put(callingUid, true);
        Binder.withCleanCallingIdentity(() -> {
            TextClassifierEvent.TextLinkifyEvent pasteEvent =
                    new TextClassifierEvent.TextLinkifyEvent.Builder(
                            TextClassifierEvent.TYPE_READ_CLIPBOARD)
                            .setEventContext(new TextClassificationContext.Builder(
                                    callingPackage, TextClassifier.WIDGET_TYPE_CLIPBOARD)
                                    .build())
                            .setExtras(
                                    Bundle.forPair("source_package", clipboard.mPrimaryClipPackage))
                            .build();
            textClassifier.onTextClassifierEvent(pasteEvent);
        });
    }

    private TextClassificationManager createTextClassificationManagerAsUser(@UserIdInt int userId) {
        Context context = getContext().createContextAsUser(UserHandle.of(userId), /* flags= */ 0);
        return context.getSystemService(TextClassificationManager.class);
    }
}
