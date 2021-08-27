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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IUriGrantsManager;
import android.app.KeyguardManager;
import android.app.UriGrantsManager;
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
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.autofill.AutofillManagerInternal;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextClassifierEvent;
import android.view.textclassifier.TextLinks;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiThread;
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
    private static final boolean IS_EMULATOR =
            SystemProperties.getBoolean("ro.boot.qemu", false);

    // DeviceConfig properties
    private static final String PROPERTY_MAX_CLASSIFICATION_LENGTH = "max_classification_length";
    private static final int DEFAULT_MAX_CLASSIFICATION_LENGTH = 400;

    private final ActivityManagerInternal mAmInternal;
    private final IUriGrantsManager mUgm;
    private final UriGrantsManagerInternal mUgmInternal;
    private final WindowManagerInternal mWm;
    private final IUserManager mUm;
    private final PackageManager mPm;
    private final AppOpsManager mAppOps;
    private final ContentCaptureManagerInternal mContentCaptureInternal;
    private final AutofillManagerInternal mAutofillInternal;
    private final IBinder mPermissionOwner;
    private final Consumer<ClipData> mEmulatorClipboardMonitor;
    private final Handler mWorkerHandler;

    @GuardedBy("mLock")
    private final SparseArray<PerUserClipboard> mClipboards = new SparseArray<>();

    @GuardedBy("mLock")
    private boolean mShowAccessNotifications =
            ClipboardManager.DEVICE_CONFIG_DEFAULT_SHOW_ACCESS_NOTIFICATIONS;

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
        mPm = getContext().getPackageManager();
        mUm = (IUserManager) ServiceManager.getService(Context.USER_SERVICE);
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mContentCaptureInternal = LocalServices.getService(ContentCaptureManagerInternal.class);
        mAutofillInternal = LocalServices.getService(AutofillManagerInternal.class);
        final IBinder permOwner = mUgmInternal.newUriPermissionOwner("clipboard");
        mPermissionOwner = permOwner;
        if (IS_EMULATOR) {
            mEmulatorClipboardMonitor = new EmulatorClipboardMonitor((clip) -> {
                synchronized (mLock) {
                    setPrimaryClipInternalLocked(getClipboardLocked(0), clip,
                            android.os.Process.SYSTEM_UID, null);
                }
            });
        } else {
            mEmulatorClipboardMonitor = (clip) -> {};
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
    }

    @Override
    public void onUserStopped(@NonNull TargetUser user) {
        synchronized (mLock) {
            mClipboards.remove(user.getUserIdentifier());
        }
    }

    private void updateConfig() {
        synchronized (mLock) {
            mShowAccessNotifications = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_CLIPBOARD,
                    ClipboardManager.DEVICE_CONFIG_SHOW_ACCESS_NOTIFICATIONS,
                    ClipboardManager.DEVICE_CONFIG_DEFAULT_SHOW_ACCESS_NOTIFICATIONS);
            mMaxClassificationLength = DeviceConfig.getInt(DeviceConfig.NAMESPACE_CLIPBOARD,
                    PROPERTY_MAX_CLASSIFICATION_LENGTH, DEFAULT_MAX_CLASSIFICATION_LENGTH);
        }
    }

    private class ListenerInfo {
        final int mUid;
        final String mPackageName;
        ListenerInfo(int uid, String packageName) {
            mUid = uid;
            mPackageName = packageName;
        }
    }

    private class PerUserClipboard {
        final int userId;

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

        PerUserClipboard(int userId) {
            this.userId = userId;
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
     * To handle the difference between userId and intendingUserId, uid and intendingUid.
     *
     * userId means that comes from the calling side and should be validated by
     * ActivityManagerInternal.handleIncomingUser.
     * After validation of ActivityManagerInternal.handleIncomingUser, the userId is called
     * 'intendingUserId' and the uid is called 'intendingUid'.
     */
    private class ClipboardImpl extends IClipboard.Stub {
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
        public void setPrimaryClip(ClipData clip, String callingPackage, @UserIdInt int userId) {
            checkAndSetPrimaryClip(clip, callingPackage, userId, callingPackage);
        }

        @Override
        public void setPrimaryClipAsPackage(
                ClipData clip, String callingPackage, @UserIdInt int userId, String sourcePackage) {
            getContext().enforceCallingOrSelfPermission(Manifest.permission.SET_CLIP_SOURCE,
                    "Requires SET_CLIP_SOURCE permission");
            checkAndSetPrimaryClip(clip, callingPackage, userId, sourcePackage);
        }

        private void checkAndSetPrimaryClip(
                ClipData clip, String callingPackage, @UserIdInt int userId, String sourcePackage) {
            if (clip == null || clip.getItemCount() <= 0) {
                throw new IllegalArgumentException("No items");
            }
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            if (!clipboardAccessAllowed(AppOpsManager.OP_WRITE_CLIPBOARD, callingPackage,
                    intendingUid, intendingUserId)) {
                return;
            }
            checkDataOwner(clip, intendingUid);
            synchronized (mLock) {
                setPrimaryClipInternalLocked(clip, intendingUid, sourcePackage);
            }
        }

        @Override
        public void clearPrimaryClip(String callingPackage, @UserIdInt int userId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            if (!clipboardAccessAllowed(AppOpsManager.OP_WRITE_CLIPBOARD, callingPackage,
                    intendingUid, intendingUserId)) {
                return;
            }
            synchronized (mLock) {
                setPrimaryClipInternalLocked(null, intendingUid, callingPackage);
            }
        }

        @Override
        public ClipData getPrimaryClip(String pkg, @UserIdInt int userId) {
            final int intendingUid = getIntendingUid(pkg, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, pkg,
                    intendingUid, intendingUserId)
                    || isDeviceLocked(intendingUserId)) {
                return null;
            }
            synchronized (mLock) {
                try {
                    addActiveOwnerLocked(intendingUid, pkg);
                } catch (SecurityException e) {
                    // Permission could not be granted - URI may be invalid
                    Slog.i(TAG, "Could not grant permission to primary clip. Clearing clipboard.");
                    setPrimaryClipInternalLocked(null, intendingUid, pkg);
                    return null;
                }

                PerUserClipboard clipboard = getClipboardLocked(intendingUserId);
                showAccessNotificationLocked(pkg, intendingUid, intendingUserId, clipboard);
                notifyTextClassifierLocked(clipboard, pkg, intendingUid);
                return clipboard.primaryClip;
            }
        }

        @Override
        public ClipDescription getPrimaryClipDescription(String callingPackage,
                @UserIdInt int userId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, callingPackage,
                    intendingUid, intendingUserId, false)
                    || isDeviceLocked(intendingUserId)) {
                return null;
            }
            synchronized (mLock) {
                PerUserClipboard clipboard = getClipboardLocked(intendingUserId);
                return clipboard.primaryClip != null
                        ? clipboard.primaryClip.getDescription() : null;
            }
        }

        @Override
        public boolean hasPrimaryClip(String callingPackage, @UserIdInt int userId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, callingPackage,
                    intendingUid, intendingUserId, false)
                    || isDeviceLocked(intendingUserId)) {
                return false;
            }
            synchronized (mLock) {
                return getClipboardLocked(intendingUserId).primaryClip != null;
            }
        }

        @Override
        public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener,
                String callingPackage, @UserIdInt int userId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            synchronized (mLock) {
                getClipboardLocked(intendingUserId).primaryClipListeners.register(listener,
                        new ListenerInfo(intendingUid, callingPackage));
            }
        }

        @Override
        public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener,
                String callingPackage, @UserIdInt int userId) {
            final int intendingUserId = getIntendingUserId(callingPackage, userId);
            synchronized (mLock) {
                getClipboardLocked(intendingUserId).primaryClipListeners.unregister(listener);
            }
        }

        @Override
        public boolean hasClipboardText(String callingPackage, int userId) {
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, callingPackage,
                    intendingUid, intendingUserId, false)
                    || isDeviceLocked(intendingUserId)) {
                return false;
            }
            synchronized (mLock) {
                PerUserClipboard clipboard = getClipboardLocked(intendingUserId);
                if (clipboard.primaryClip != null) {
                    CharSequence text = clipboard.primaryClip.getItemAt(0).getText();
                    return text != null && text.length() > 0;
                }
                return false;
            }
        }

        @Override
        public String getPrimaryClipSource(String callingPackage, int userId) {
            getContext().enforceCallingOrSelfPermission(Manifest.permission.SET_CLIP_SOURCE,
                    "Requires SET_CLIP_SOURCE permission");
            final int intendingUid = getIntendingUid(callingPackage, userId);
            final int intendingUserId = UserHandle.getUserId(intendingUid);
            if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, callingPackage,
                    intendingUid, intendingUserId, false)
                    || isDeviceLocked(intendingUserId)) {
                return null;
            }
            synchronized (mLock) {
                PerUserClipboard clipboard = getClipboardLocked(intendingUserId);
                if (clipboard.primaryClip != null) {
                    return clipboard.mPrimaryClipPackage;
                }
                return null;
            }
        }
    };

    @GuardedBy("mLock")
    private PerUserClipboard getClipboardLocked(@UserIdInt int userId) {
        PerUserClipboard puc = mClipboards.get(userId);
        if (puc == null) {
            puc = new PerUserClipboard(userId);
            mClipboards.put(userId, puc);
        }
        return puc;
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
            setPrimaryClipInternalLocked(clip, uid, null);
        }
    }

    @GuardedBy("mLock")
    private void setPrimaryClipInternalLocked(
            @Nullable ClipData clip, int uid, @Nullable String sourcePackage) {
        mEmulatorClipboardMonitor.accept(clip);

        final int userId = UserHandle.getUserId(uid);
        if (clip != null) {
            startClassificationLocked(clip, userId);
        }

        // Update this user
        setPrimaryClipInternalLocked(getClipboardLocked(userId), clip, uid, sourcePackage);

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
                            setPrimaryClipInternalLocked(
                                    getClipboardLocked(id), clip, uid, sourcePackage);
                        }
                    }
                }
            }
        }
    }

    void setPrimaryClipInternal(PerUserClipboard clipboard, @Nullable ClipData clip,
            int uid) {
        synchronized ("mLock") {
            setPrimaryClipInternalLocked(clipboard, clip, uid, null);
        }
    }

    @GuardedBy("mLock")
    private void setPrimaryClipInternalLocked(PerUserClipboard clipboard, @Nullable ClipData clip,
            int uid, @Nullable String sourcePackage) {
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

    private void sendClipChangedBroadcast(PerUserClipboard clipboard) {
        final long ident = Binder.clearCallingIdentity();
        final int n = clipboard.primaryClipListeners.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    ListenerInfo li = (ListenerInfo)
                            clipboard.primaryClipListeners.getBroadcastCookie(i);

                    if (clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, li.mPackageName,
                            li.mUid, UserHandle.getUserId(li.mUid))) {
                        clipboard.primaryClipListeners.getBroadcastItem(i)
                                .dispatchPrimaryClipChanged();
                    }
                } catch (RemoteException e) {
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
    private void startClassificationLocked(@NonNull ClipData clip, @UserIdInt int userId) {
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
        mWorkerHandler.post(() -> doClassification(text, clip, classifier, userId));
    }

    @WorkerThread
    private void doClassification(
            CharSequence text, ClipData clip, TextClassifier classifier, @UserIdInt int userId) {
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
            PerUserClipboard clipboard = getClipboardLocked(userId);
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
                                PerUserClipboard relatedClipboard = getClipboardLocked(id);
                                if (hasTextLocked(relatedClipboard, text)) {
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
            PerUserClipboard clipboard, ArrayMap<String, Float> confidences, TextLinks links,
            TextClassifier classifier) {
        clipboard.mTextClassifier = classifier;
        clipboard.primaryClip.getDescription().setConfidenceScores(confidences);
        if (!links.getLinks().isEmpty()) {
            clipboard.primaryClip.getItemAt(0).setTextLinks(links);
        }
        sendClipChangedBroadcast(clipboard);
    }

    @GuardedBy("mLock")
    private boolean hasTextLocked(PerUserClipboard clipboard, @NonNull CharSequence text) {
        return clipboard.primaryClip != null
                && clipboard.primaryClip.getItemCount() > 0
                && text.equals(clipboard.primaryClip.getItemAt(0).getText());
    }

    private boolean isDeviceLocked(@UserIdInt int userId) {
        final long token = Binder.clearCallingIdentity();
        try {
            final KeyguardManager keyguardManager = getContext().getSystemService(
                    KeyguardManager.class);
            return keyguardManager != null && keyguardManager.isDeviceLocked(userId);
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
    private void addActiveOwnerLocked(int uid, String pkg) {
        final IPackageManager pm = AppGlobals.getPackageManager();
        final int targetUserHandle = UserHandle.getCallingUserId();
        final long oldIdentity = Binder.clearCallingIdentity();
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0, targetUserHandle);
            if (pi == null) {
                throw new IllegalArgumentException("Unknown package " + pkg);
            }
            if (!UserHandle.isSameApp(pi.applicationInfo.uid, uid)) {
                throw new SecurityException("Calling uid " + uid
                        + " does not own package " + pkg);
            }
        } catch (RemoteException e) {
            // Can't happen; the package manager is in the same process
        } finally {
            Binder.restoreCallingIdentity(oldIdentity);
        }
        PerUserClipboard clipboard = getClipboardLocked(UserHandle.getUserId(uid));
        if (clipboard.primaryClip != null && !clipboard.activePermissionOwners.contains(pkg)) {
            final int N = clipboard.primaryClip.getItemCount();
            for (int i=0; i<N; i++) {
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

    private void revokeUris(PerUserClipboard clipboard) {
        if (clipboard.primaryClip == null) {
            return;
        }
        final int N = clipboard.primaryClip.getItemCount();
        for (int i=0; i<N; i++) {
            revokeItemPermission(clipboard.primaryClip.getItemAt(i), clipboard.primaryClipUid);
        }
    }

    private boolean clipboardAccessAllowed(int op, String callingPackage, int uid,
            @UserIdInt int userId) {
        return clipboardAccessAllowed(op, callingPackage, uid, userId, true);
    }

    private boolean clipboardAccessAllowed(int op, String callingPackage, int uid,
            @UserIdInt int userId, boolean shouldNoteOp) {

        boolean allowed;

        // First, verify package ownership to ensure use below is safe.
        mAppOps.checkPackage(uid, callingPackage);

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
                    allowed = mWm.isUidFocused(uid)
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
            appOpsResult = mAppOps.noteOp(op, uid, callingPackage);
        } else {
            appOpsResult = mAppOps.checkOp(op, uid, callingPackage);
        }

        return appOpsResult == AppOpsManager.MODE_ALLOWED;
    }

    private boolean isDefaultIme(int userId, String packageName) {
        String defaultIme = Settings.Secure.getStringForUser(getContext().getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD, userId);
        if (!TextUtils.isEmpty(defaultIme)) {
            final String imePkg = ComponentName.unflattenFromString(defaultIme).getPackageName();
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
            PerUserClipboard clipboard) {
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
        // Don't notify if already notified for this uid and clip.
        if (clipboard.mNotifiedUids.get(uid)) {
            return;
        }
        clipboard.mNotifiedUids.put(uid, true);

        Binder.withCleanCallingIdentity(() -> {
            try {
                CharSequence callingAppLabel = mPm.getApplicationLabel(
                        mPm.getApplicationInfoAsUser(callingPackage, 0, userId));
                String message =
                        getContext().getString(R.string.pasted_from_clipboard, callingAppLabel);
                Slog.i(TAG, message);
                Toast.makeText(
                        getContext(), UiThread.get().getLooper(), message, Toast.LENGTH_SHORT)
                        .show();
            } catch (PackageManager.NameNotFoundException e) {
                // do nothing
            }
        });
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
            PerUserClipboard clipboard, String callingPackage, int callingUid) {
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
