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
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IUriGrantsManager;
import android.app.KeyguardManager;
import android.app.UriGrantsManager;
import android.content.ClipData;
import android.content.ClipDescription;
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
import android.os.IBinder;
import android.os.IUserManager;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutofillManagerInternal;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

// The following class is Android Emulator specific. It is used to read and
// write contents of the host system's clipboard.
class HostClipboardMonitor implements Runnable {
    public interface HostClipboardCallback {
        void onHostClipboardUpdated(String contents);
    }

    private RandomAccessFile mPipe = null;
    private HostClipboardCallback mHostClipboardCallback;
    private static final String PIPE_NAME = "pipe:clipboard";
    private static final String PIPE_DEVICE = "/dev/qemu_pipe";

    private static byte[] createOpenHandshake() {
        // String.getBytes doesn't include the null terminator,
        // but the QEMU pipe device requires the pipe service name
        // to be null-terminated.

        final byte[] bits = Arrays.copyOf(PIPE_NAME.getBytes(), PIPE_NAME.length() + 1);
        bits[PIPE_NAME.length()] = 0;
        return bits;
    }

    private boolean openPipe() {
        try {
            final RandomAccessFile pipe = new RandomAccessFile(PIPE_DEVICE, "rw");
            try {
                pipe.write(createOpenHandshake());
                mPipe = pipe;
                return true;
            } catch (IOException ignore) {
                pipe.close();
            }
        } catch (IOException ignore) {
        }
        return false;
    }

    private void closePipe() {
        try {
            final RandomAccessFile pipe = mPipe;
            mPipe = null;
            if (pipe != null) {
                pipe.close();
            }
        } catch (IOException ignore) {
        }
    }

    private byte[] receiveMessage() throws IOException {
        final int size = Integer.reverseBytes(mPipe.readInt());
        final byte[] receivedData = new byte[size];
        mPipe.readFully(receivedData);
        return receivedData;
    }

    private void sendMessage(byte[] message) throws IOException {
        mPipe.writeInt(Integer.reverseBytes(message.length));
        mPipe.write(message);
    }

    public HostClipboardMonitor(HostClipboardCallback cb) {
        mHostClipboardCallback = cb;
    }

    @Override
    public void run() {
        while(!Thread.interrupted()) {
            try {
                // There's no guarantee that QEMU pipes will be ready at the moment
                // this method is invoked. We simply try to get the pipe open and
                // retry on failure indefinitely.
                while ((mPipe == null) && !openPipe()) {
                    Thread.sleep(100);
                }

                final byte[] receivedData = receiveMessage();
                mHostClipboardCallback.onHostClipboardUpdated(
                    new String(receivedData));
            } catch (IOException e) {
                closePipe();
            } catch (InterruptedException e) {}
        }
    }

    public void setHostClipboard(String content) {
        try {
            if (mPipe != null) {
                sendMessage(content.getBytes());
            }
        } catch(IOException e) {
            Slog.e("HostClipboardMonitor",
                   "Failed to set host clipboard " + e.getMessage());
        }
    }
}

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
    private HostClipboardMonitor mHostClipboardMonitor = null;
    private Thread mHostMonitorThread = null;

    private final SparseArray<PerUserClipboard> mClipboards = new SparseArray<>();

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
            mHostClipboardMonitor = new HostClipboardMonitor(
                new HostClipboardMonitor.HostClipboardCallback() {
                    @Override
                    public void onHostClipboardUpdated(String contents){
                        ClipData clip =
                            new ClipData("host clipboard",
                                         new String[]{"text/plain"},
                                         new ClipData.Item(contents));
                        synchronized(mClipboards) {
                            setPrimaryClipInternal(getClipboard(0), clip,
                                    android.os.Process.SYSTEM_UID);
                        }
                    }
                });
            mHostMonitorThread = new Thread(mHostClipboardMonitor);
            mHostMonitorThread.start();
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.CLIPBOARD_SERVICE, new ClipboardImpl());
    }

    @Override
    public void onCleanupUser(int userId) {
        synchronized (mClipboards) {
            mClipboards.remove(userId);
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

        final HashSet<String> activePermissionOwners
                = new HashSet<String>();

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
            synchronized (this) {
                if (clip == null || clip.getItemCount() <= 0) {
                    throw new IllegalArgumentException("No items");
                }
                final int intendingUid = getIntendingUid(callingPackage, userId);
                final int intendingUserId = UserHandle.getUserId(intendingUid);
                if (!clipboardAccessAllowed(AppOpsManager.OP_WRITE_CLIPBOARD, callingPackage,
                            intendingUid, intendingUserId)) {
                    return;
                }
                checkDataOwnerLocked(clip, intendingUid);
                setPrimaryClipInternal(clip, intendingUid);
            }
        }

        @Override
        public void clearPrimaryClip(String callingPackage, @UserIdInt int userId) {
            synchronized (this) {
                final int intendingUid = getIntendingUid(callingPackage, userId);
                final int intendingUserId = UserHandle.getUserId(intendingUid);
                if (!clipboardAccessAllowed(AppOpsManager.OP_WRITE_CLIPBOARD, callingPackage,
                        intendingUid, intendingUserId)) {
                    return;
                }
                setPrimaryClipInternal(null, intendingUid);
            }
        }

        @Override
        public ClipData getPrimaryClip(String pkg, @UserIdInt int userId) {
            synchronized (this) {
                final int intendingUid = getIntendingUid(pkg, userId);
                final int intendingUserId = UserHandle.getUserId(intendingUid);
                if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, pkg,
                        intendingUid, intendingUserId)
                        || isDeviceLocked(intendingUserId)) {
                    return null;
                }
                addActiveOwnerLocked(intendingUid, pkg);
                return getClipboard(intendingUserId).primaryClip;
            }
        }

        @Override
        public ClipDescription getPrimaryClipDescription(String callingPackage,
                @UserIdInt int userId) {
            synchronized (this) {
                final int intendingUid = getIntendingUid(callingPackage, userId);
                final int intendingUserId = UserHandle.getUserId(intendingUid);
                if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, callingPackage,
                        intendingUid, intendingUserId, false)
                        || isDeviceLocked(intendingUserId)) {
                    return null;
                }
                PerUserClipboard clipboard = getClipboard(intendingUserId);
                return clipboard.primaryClip != null
                        ? clipboard.primaryClip.getDescription() : null;
            }
        }

        @Override
        public boolean hasPrimaryClip(String callingPackage, @UserIdInt int userId) {
            synchronized (this) {
                final int intendingUid = getIntendingUid(callingPackage, userId);
                final int intendingUserId = UserHandle.getUserId(intendingUid);
                if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, callingPackage,
                        intendingUid, intendingUserId, false)
                        || isDeviceLocked(intendingUserId)) {
                    return false;
                }
                return getClipboard(intendingUserId).primaryClip != null;
            }
        }

        @Override
        public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener,
                String callingPackage, @UserIdInt int userId) {
            synchronized (this) {
                final int intendingUid = getIntendingUid(callingPackage, userId);
                final int intendingUserId = UserHandle.getUserId(intendingUid);
                getClipboard(intendingUserId).primaryClipListeners.register(listener,
                        new ListenerInfo(intendingUid, callingPackage));
            }
        }

        @Override
        public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener,
                String callingPackage, @UserIdInt int userId) {
            synchronized (this) {
                final int intendingUserId = getIntendingUserId(callingPackage, userId);
                getClipboard(intendingUserId).primaryClipListeners.unregister(listener);
            }
        }

        @Override
        public boolean hasClipboardText(String callingPackage, int userId) {
            synchronized (this) {
                final int intendingUid = getIntendingUid(callingPackage, userId);
                final int intendingUserId = UserHandle.getUserId(intendingUid);
                if (!clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, callingPackage,
                        intendingUid, intendingUserId, false)
                        || isDeviceLocked(intendingUserId)) {
                    return false;
                }
                PerUserClipboard clipboard = getClipboard(intendingUserId);
                if (clipboard.primaryClip != null) {
                    CharSequence text = clipboard.primaryClip.getItemAt(0).getText();
                    return text != null && text.length() > 0;
                }
                return false;
            }
        }
    };

    private PerUserClipboard getClipboard(@UserIdInt int userId) {
        synchronized (mClipboards) {
            PerUserClipboard puc = mClipboards.get(userId);
            if (puc == null) {
                puc = new PerUserClipboard(userId);
                mClipboards.put(userId, puc);
            }
            return puc;
        }
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
        // Push clipboard to host, if any
        if (mHostClipboardMonitor != null) {
            if (clip == null) {
                // Someone really wants the clipboard cleared, so push empty
                mHostClipboardMonitor.setHostClipboard("");
            } else if (clip.getItemCount() > 0) {
                final CharSequence text = clip.getItemAt(0).getText();
                if (text != null) {
                    mHostClipboardMonitor.setHostClipboard(text.toString());
                }
            }
        }

        // Update this user
        final int userId = UserHandle.getUserId(uid);
        setPrimaryClipInternal(getClipboard(userId), clip, uid);

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
                            setPrimaryClipInternal(getClipboard(id), clip, uid);
                        }
                    }
                }
            }
        }
    }

    void setPrimaryClipInternal(PerUserClipboard clipboard, @Nullable ClipData clip,
            int uid) {
        revokeUris(clipboard);
        clipboard.activePermissionOwners.clear();
        if (clip == null && clipboard.primaryClip == null) {
            return;
        }
        clipboard.primaryClip = clip;
        if (clip != null) {
            clipboard.primaryClipUid = uid;
        } else {
            clipboard.primaryClipUid = android.os.Process.NOBODY_UID;
        }
        if (clip != null) {
            final ClipDescription description = clip.getDescription();
            if (description != null) {
                description.setTimestamp(System.currentTimeMillis());
            }
        }
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

    private final void checkUriOwnerLocked(Uri uri, int sourceUid) {
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

    private final void checkItemOwnerLocked(ClipData.Item item, int uid) {
        if (item.getUri() != null) {
            checkUriOwnerLocked(item.getUri(), uid);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            checkUriOwnerLocked(intent.getData(), uid);
        }
    }

    private final void checkDataOwnerLocked(ClipData data, int uid) {
        final int N = data.getItemCount();
        for (int i=0; i<N; i++) {
            checkItemOwnerLocked(data.getItemAt(i), uid);
        }
    }

    private final void grantUriLocked(Uri uri, int sourceUid, String targetPkg,
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

    private final void grantItemLocked(ClipData.Item item, int sourceUid, String targetPkg,
            int targetUserId) {
        if (item.getUri() != null) {
            grantUriLocked(item.getUri(), sourceUid, targetPkg, targetUserId);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            grantUriLocked(intent.getData(), sourceUid, targetPkg, targetUserId);
        }
    }

    private final void addActiveOwnerLocked(int uid, String pkg) {
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
        PerUserClipboard clipboard = getClipboard(UserHandle.getUserId(uid));
        if (clipboard.primaryClip != null && !clipboard.activePermissionOwners.contains(pkg)) {
            final int N = clipboard.primaryClip.getItemCount();
            for (int i=0; i<N; i++) {
                grantItemLocked(clipboard.primaryClip.getItemAt(i), clipboard.primaryClipUid, pkg,
                        UserHandle.getUserId(uid));
            }
            clipboard.activePermissionOwners.add(pkg);
        }
    }

    private final void revokeUriLocked(Uri uri, int sourceUid) {
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

    private final void revokeItemLocked(ClipData.Item item, int sourceUid) {
        if (item.getUri() != null) {
            revokeUriLocked(item.getUri(), sourceUid);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            revokeUriLocked(intent.getData(), sourceUid);
        }
    }

    private final void revokeUris(PerUserClipboard clipboard) {
        if (clipboard.primaryClip == null) {
            return;
        }
        final int N = clipboard.primaryClip.getItemCount();
        for (int i=0; i<N; i++) {
            revokeItemLocked(clipboard.primaryClip.getItemAt(i), clipboard.primaryClipUid);
        }
    }

    private boolean clipboardAccessAllowed(int op, String callingPackage, int uid,
            @UserIdInt int userId) {
        return clipboardAccessAllowed(op, callingPackage, uid, userId, true);
    }

    private boolean clipboardAccessAllowed(int op, String callingPackage, int uid,
            @UserIdInt int userId, boolean shouldNoteOp) {

        boolean allowed = false;

        // First, verify package ownership to ensure use below is safe.
        mAppOps.checkPackage(uid, callingPackage);

        // Shell can access the clipboard for testing purposes.
        if (mPm.checkPermission(android.Manifest.permission.READ_CLIPBOARD_IN_BACKGROUND,
                    callingPackage) == PackageManager.PERMISSION_GRANTED) {
            allowed = true;
        }
        // The default IME is always allowed to access the clipboard.
        String defaultIme = Settings.Secure.getStringForUser(getContext().getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD, userId);
        if (!TextUtils.isEmpty(defaultIme)) {
            final String imePkg = ComponentName.unflattenFromString(defaultIme).getPackageName();
            if (imePkg.equals(callingPackage)) {
                allowed = true;
            }
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
}
