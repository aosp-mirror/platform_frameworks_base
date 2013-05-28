/*
 * Copyright (C) 2007 The Android Open Source Project
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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.ObbInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.IMountService;
import android.os.storage.IMountServiceListener;
import android.os.storage.IMountShutdownObserver;
import android.os.storage.IObbActionListener;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageResultCode;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IMediaContainerService;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.NativeDaemonConnector.Command;
import com.android.server.NativeDaemonConnector.SensitiveArg;
import com.android.server.am.ActivityManagerService;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerService;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * MountService implements back-end services for platform storage
 * management.
 * @hide - Applications should use android.os.storage.StorageManager
 * to access the MountService.
 */
class MountService extends IMountService.Stub
        implements INativeDaemonConnectorCallbacks, Watchdog.Monitor {

    // TODO: listen for user creation/deletion

    private static final boolean LOCAL_LOGD = false;
    private static final boolean DEBUG_UNMOUNT = false;
    private static final boolean DEBUG_EVENTS = false;
    private static final boolean DEBUG_OBB = false;

    // Disable this since it messes up long-running cryptfs operations.
    private static final boolean WATCHDOG_ENABLE = false;

    private static final String TAG = "MountService";

    private static final String VOLD_TAG = "VoldConnector";

    /** Maximum number of ASEC containers allowed to be mounted. */
    private static final int MAX_CONTAINERS = 250;

    /*
     * Internal vold volume state constants
     */
    class VolumeState {
        public static final int Init       = -1;
        public static final int NoMedia    = 0;
        public static final int Idle       = 1;
        public static final int Pending    = 2;
        public static final int Checking   = 3;
        public static final int Mounted    = 4;
        public static final int Unmounting = 5;
        public static final int Formatting = 6;
        public static final int Shared     = 7;
        public static final int SharedMnt  = 8;
    }

    /*
     * Internal vold response code constants
     */
    class VoldResponseCode {
        /*
         * 100 series - Requestion action was initiated; expect another reply
         *              before proceeding with a new command.
         */
        public static final int VolumeListResult               = 110;
        public static final int AsecListResult                 = 111;
        public static final int StorageUsersListResult         = 112;

        /*
         * 200 series - Requestion action has been successfully completed.
         */
        public static final int ShareStatusResult              = 210;
        public static final int AsecPathResult                 = 211;
        public static final int ShareEnabledResult             = 212;

        /*
         * 400 series - Command was accepted, but the requested action
         *              did not take place.
         */
        public static final int OpFailedNoMedia                = 401;
        public static final int OpFailedMediaBlank             = 402;
        public static final int OpFailedMediaCorrupt           = 403;
        public static final int OpFailedVolNotMounted          = 404;
        public static final int OpFailedStorageBusy            = 405;
        public static final int OpFailedStorageNotFound        = 406;

        /*
         * 600 series - Unsolicited broadcasts.
         */
        public static final int VolumeStateChange              = 605;
        public static final int VolumeDiskInserted             = 630;
        public static final int VolumeDiskRemoved              = 631;
        public static final int VolumeBadRemoval               = 632;

        /*
         * 700 series - fstrim
         */
        public static final int FstrimCompleted                = 700;
    }

    private Context mContext;
    private NativeDaemonConnector mConnector;

    private final Object mVolumesLock = new Object();

    /** When defined, base template for user-specific {@link StorageVolume}. */
    private StorageVolume mEmulatedTemplate;

    // TODO: separate storage volumes on per-user basis

    @GuardedBy("mVolumesLock")
    private final ArrayList<StorageVolume> mVolumes = Lists.newArrayList();
    /** Map from path to {@link StorageVolume} */
    @GuardedBy("mVolumesLock")
    private final HashMap<String, StorageVolume> mVolumesByPath = Maps.newHashMap();
    /** Map from path to state */
    @GuardedBy("mVolumesLock")
    private final HashMap<String, String> mVolumeStates = Maps.newHashMap();

    private volatile boolean mSystemReady = false;

    private PackageManagerService                 mPms;
    private boolean                               mUmsEnabling;
    private boolean                               mUmsAvailable = false;
    // Used as a lock for methods that register/unregister listeners.
    final private ArrayList<MountServiceBinderListener> mListeners =
            new ArrayList<MountServiceBinderListener>();
    private final CountDownLatch mConnectedSignal = new CountDownLatch(1);
    private final CountDownLatch mAsecsScanned = new CountDownLatch(1);
    private boolean                               mSendUmsConnectedOnBoot = false;

    /**
     * Private hash of currently mounted secure containers.
     * Used as a lock in methods to manipulate secure containers.
     */
    final private HashSet<String> mAsecMountSet = new HashSet<String>();

    /**
     * The size of the crypto algorithm key in bits for OBB files. Currently
     * Twofish is used which takes 128-bit keys.
     */
    private static final int CRYPTO_ALGORITHM_KEY_SIZE = 128;

    /**
     * The number of times to run SHA1 in the PBKDF2 function for OBB files.
     * 1024 is reasonably secure and not too slow.
     */
    private static final int PBKDF2_HASH_ROUNDS = 1024;

    /**
     * Mounted OBB tracking information. Used to track the current state of all
     * OBBs.
     */
    final private Map<IBinder, List<ObbState>> mObbMounts = new HashMap<IBinder, List<ObbState>>();

    /** Map from raw paths to {@link ObbState}. */
    final private Map<String, ObbState> mObbPathToStateMap = new HashMap<String, ObbState>();

    class ObbState implements IBinder.DeathRecipient {
        public ObbState(String rawPath, String canonicalPath, int callingUid,
                IObbActionListener token, int nonce) {
            this.rawPath = rawPath;
            this.canonicalPath = canonicalPath.toString();

            final int userId = UserHandle.getUserId(callingUid);
            this.ownerPath = buildObbPath(canonicalPath, userId, false);
            this.voldPath = buildObbPath(canonicalPath, userId, true);

            this.ownerGid = UserHandle.getSharedAppGid(callingUid);
            this.token = token;
            this.nonce = nonce;
        }

        final String rawPath;
        final String canonicalPath;
        final String ownerPath;
        final String voldPath;

        final int ownerGid;

        // Token of remote Binder caller
        final IObbActionListener token;

        // Identifier to pass back to the token
        final int nonce;

        public IBinder getBinder() {
            return token.asBinder();
        }

        @Override
        public void binderDied() {
            ObbAction action = new UnmountObbAction(this, true);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));
        }

        public void link() throws RemoteException {
            getBinder().linkToDeath(this, 0);
        }

        public void unlink() {
            getBinder().unlinkToDeath(this, 0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ObbState{");
            sb.append("rawPath=").append(rawPath);
            sb.append(",canonicalPath=").append(canonicalPath);
            sb.append(",ownerPath=").append(ownerPath);
            sb.append(",voldPath=").append(voldPath);
            sb.append(",ownerGid=").append(ownerGid);
            sb.append(",token=").append(token);
            sb.append(",binder=").append(getBinder());
            sb.append('}');
            return sb.toString();
        }
    }

    // OBB Action Handler
    final private ObbActionHandler mObbActionHandler;

    // OBB action handler messages
    private static final int OBB_RUN_ACTION = 1;
    private static final int OBB_MCS_BOUND = 2;
    private static final int OBB_MCS_UNBIND = 3;
    private static final int OBB_MCS_RECONNECT = 4;
    private static final int OBB_FLUSH_MOUNT_STATE = 5;

    /*
     * Default Container Service information
     */
    static final ComponentName DEFAULT_CONTAINER_COMPONENT = new ComponentName(
            "com.android.defcontainer", "com.android.defcontainer.DefaultContainerService");

    final private DefaultContainerConnection mDefContainerConn = new DefaultContainerConnection();

    class DefaultContainerConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG_OBB)
                Slog.i(TAG, "onServiceConnected");
            IMediaContainerService imcs = IMediaContainerService.Stub.asInterface(service);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_MCS_BOUND, imcs));
        }

        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG_OBB)
                Slog.i(TAG, "onServiceDisconnected");
        }
    };

    // Used in the ObbActionHandler
    private IMediaContainerService mContainerService = null;

    // Handler messages
    private static final int H_UNMOUNT_PM_UPDATE = 1;
    private static final int H_UNMOUNT_PM_DONE = 2;
    private static final int H_UNMOUNT_MS = 3;
    private static final int H_SYSTEM_READY = 4;

    private static final int RETRY_UNMOUNT_DELAY = 30; // in ms
    private static final int MAX_UNMOUNT_RETRIES = 4;

    class UnmountCallBack {
        final String path;
        final boolean force;
        final boolean removeEncryption;
        int retries;

        UnmountCallBack(String path, boolean force, boolean removeEncryption) {
            retries = 0;
            this.path = path;
            this.force = force;
            this.removeEncryption = removeEncryption;
        }

        void handleFinished() {
            if (DEBUG_UNMOUNT) Slog.i(TAG, "Unmounting " + path);
            doUnmountVolume(path, true, removeEncryption);
        }
    }

    class UmsEnableCallBack extends UnmountCallBack {
        final String method;

        UmsEnableCallBack(String path, String method, boolean force) {
            super(path, force, false);
            this.method = method;
        }

        @Override
        void handleFinished() {
            super.handleFinished();
            doShareUnshareVolume(path, method, true);
        }
    }

    class ShutdownCallBack extends UnmountCallBack {
        IMountShutdownObserver observer;
        ShutdownCallBack(String path, IMountShutdownObserver observer) {
            super(path, true, false);
            this.observer = observer;
        }

        @Override
        void handleFinished() {
            int ret = doUnmountVolume(path, true, removeEncryption);
            if (observer != null) {
                try {
                    observer.onShutDownComplete(ret);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException when shutting down");
                }
            }
        }
    }

    class MountServiceHandler extends Handler {
        ArrayList<UnmountCallBack> mForceUnmounts = new ArrayList<UnmountCallBack>();
        boolean mUpdatingStatus = false;

        MountServiceHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case H_UNMOUNT_PM_UPDATE: {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_UNMOUNT_PM_UPDATE");
                    UnmountCallBack ucb = (UnmountCallBack) msg.obj;
                    if (!mUpdatingStatus && !isPrimaryStorage(ucb.path)) {
                        // If PM isn't already updating, and this isn't an ASEC
                        // mount, then go ahead and do the unmount immediately.
                        if (DEBUG_UNMOUNT) Slog.i(TAG, " skipping PackageManager for " + ucb.path);
                        ucb.handleFinished();
                        break;
                    }
                    mForceUnmounts.add(ucb);
                    if (DEBUG_UNMOUNT) Slog.i(TAG, " registered = " + mUpdatingStatus);
                    // Register only if needed.
                    if (!mUpdatingStatus) {
                        if (DEBUG_UNMOUNT) Slog.i(TAG, "Updating external media status on PackageManager");
                        mUpdatingStatus = true;
                        mPms.updateExternalMediaStatus(false, true);
                    }
                    break;
                }
                case H_UNMOUNT_PM_DONE: {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_UNMOUNT_PM_DONE");
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "Updated status. Processing requests");
                    mUpdatingStatus = false;
                    int size = mForceUnmounts.size();
                    int sizeArr[] = new int[size];
                    int sizeArrN = 0;
                    // Kill processes holding references first
                    ActivityManagerService ams = (ActivityManagerService)
                    ServiceManager.getService("activity");
                    for (int i = 0; i < size; i++) {
                        UnmountCallBack ucb = mForceUnmounts.get(i);
                        String path = ucb.path;
                        boolean done = false;
                        if (!ucb.force) {
                            done = true;
                        } else {
                            int pids[] = getStorageUsers(path);
                            if (pids == null || pids.length == 0) {
                                done = true;
                            } else {
                                // Eliminate system process here?
                                ams.killPids(pids, "unmount media", true);
                                // Confirm if file references have been freed.
                                pids = getStorageUsers(path);
                                if (pids == null || pids.length == 0) {
                                    done = true;
                                }
                            }
                        }
                        if (!done && (ucb.retries < MAX_UNMOUNT_RETRIES)) {
                            // Retry again
                            Slog.i(TAG, "Retrying to kill storage users again");
                            mHandler.sendMessageDelayed(
                                    mHandler.obtainMessage(H_UNMOUNT_PM_DONE,
                                            ucb.retries++),
                                    RETRY_UNMOUNT_DELAY);
                        } else {
                            if (ucb.retries >= MAX_UNMOUNT_RETRIES) {
                                Slog.i(TAG, "Failed to unmount media inspite of " +
                                        MAX_UNMOUNT_RETRIES + " retries. Forcibly killing processes now");
                            }
                            sizeArr[sizeArrN++] = i;
                            mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_MS,
                                    ucb));
                        }
                    }
                    // Remove already processed elements from list.
                    for (int i = (sizeArrN-1); i >= 0; i--) {
                        mForceUnmounts.remove(sizeArr[i]);
                    }
                    break;
                }
                case H_UNMOUNT_MS: {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_UNMOUNT_MS");
                    UnmountCallBack ucb = (UnmountCallBack) msg.obj;
                    ucb.handleFinished();
                    break;
                }
                case H_SYSTEM_READY: {
                    try {
                        handleSystemReady();
                    } catch (Exception ex) {
                        Slog.e(TAG, "Boot-time mount exception", ex);
                    }
                    break;
                }
            }
        }
    };

    private final Handler mHandler;

    void waitForAsecScan() {
        waitForLatch(mAsecsScanned);
    }

    private void waitForReady() {
        waitForLatch(mConnectedSignal);
    }

    private void waitForLatch(CountDownLatch latch) {
        for (;;) {
            try {
                if (latch.await(5000, TimeUnit.MILLISECONDS)) {
                    return;
                } else {
                    Slog.w(TAG, "Thread " + Thread.currentThread().getName()
                            + " still waiting for MountService ready...");
                }
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupt while waiting for MountService to be ready.");
            }
        }
    }

    private void handleSystemReady() {
        // Snapshot current volume states since it's not safe to call into vold
        // while holding locks.
        final HashMap<String, String> snapshot;
        synchronized (mVolumesLock) {
            snapshot = new HashMap<String, String>(mVolumeStates);
        }

        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            final String path = entry.getKey();
            final String state = entry.getValue();

            if (state.equals(Environment.MEDIA_UNMOUNTED)) {
                int rc = doMountVolume(path);
                if (rc != StorageResultCode.OperationSucceeded) {
                    Slog.e(TAG, String.format("Boot-time mount failed (%d)",
                            rc));
                }
            } else if (state.equals(Environment.MEDIA_SHARED)) {
                /*
                 * Bootstrap UMS enabled state since vold indicates
                 * the volume is shared (runtime restart while ums enabled)
                 */
                notifyVolumeStateChange(null, path, VolumeState.NoMedia,
                        VolumeState.Shared);
            }
        }

        // Push mounted state for all emulated storage
        synchronized (mVolumesLock) {
            for (StorageVolume volume : mVolumes) {
                if (volume.isEmulated()) {
                    updatePublicVolumeState(volume, Environment.MEDIA_MOUNTED);
                }
            }
        }

        /*
         * If UMS was connected on boot, send the connected event
         * now that we're up.
         */
        if (mSendUmsConnectedOnBoot) {
            sendUmsIntent(true);
            mSendUmsConnectedOnBoot = false;
        }
    }

    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            if (userId == -1) return;
            final UserHandle user = new UserHandle(userId);

            final String action = intent.getAction();
            if (Intent.ACTION_USER_ADDED.equals(action)) {
                synchronized (mVolumesLock) {
                    createEmulatedVolumeForUserLocked(user);
                }

            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                synchronized (mVolumesLock) {
                    final List<StorageVolume> toRemove = Lists.newArrayList();
                    for (StorageVolume volume : mVolumes) {
                        if (user.equals(volume.getOwner())) {
                            toRemove.add(volume);
                        }
                    }
                    for (StorageVolume volume : toRemove) {
                        removeVolumeLocked(volume);
                    }
                }
            }
        }
    };

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean available = (intent.getBooleanExtra(UsbManager.USB_CONNECTED, false) &&
                    intent.getBooleanExtra(UsbManager.USB_FUNCTION_MASS_STORAGE, false));
            notifyShareAvailabilityChange(available);
        }
    };

    private final BroadcastReceiver mIdleMaintenanceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            waitForReady();
            String action = intent.getAction();
            // Since fstrim will be run on a daily basis we do not expect
            // fstrim to be too long, so it is not interruptible. We will
            // implement interruption only in case we see issues.
            if (Intent.ACTION_IDLE_MAINTENANCE_START.equals(action)) {
                try {
                    // This method runs on the handler thread,
                    // so it is safe to directly call into vold.
                    mConnector.execute("fstrim", "dotrim");
                    EventLogTags.writeFstrimStart(SystemClock.elapsedRealtime());
                } catch (NativeDaemonConnectorException ndce) {
                    Slog.e(TAG, "Failed to run fstrim!");
                }
            }
        }
    };

    private final class MountServiceBinderListener implements IBinder.DeathRecipient {
        final IMountServiceListener mListener;

        MountServiceBinderListener(IMountServiceListener listener) {
            mListener = listener;

        }

        public void binderDied() {
            if (LOCAL_LOGD) Slog.d(TAG, "An IMountServiceListener has died!");
            synchronized (mListeners) {
                mListeners.remove(this);
                mListener.asBinder().unlinkToDeath(this, 0);
            }
        }
    }

    private void doShareUnshareVolume(String path, String method, boolean enable) {
        // TODO: Add support for multiple share methods
        if (!method.equals("ums")) {
            throw new IllegalArgumentException(String.format("Method %s not supported", method));
        }

        try {
            mConnector.execute("volume", enable ? "share" : "unshare", path, method);
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to share/unshare", e);
        }
    }

    private void updatePublicVolumeState(StorageVolume volume, String state) {
        final String path = volume.getPath();
        final String oldState;
        synchronized (mVolumesLock) {
            oldState = mVolumeStates.put(path, state);
        }

        if (state.equals(oldState)) {
            Slog.w(TAG, String.format("Duplicate state transition (%s -> %s) for %s",
                    state, state, path));
            return;
        }

        Slog.d(TAG, "volume state changed for " + path + " (" + oldState + " -> " + state + ")");

        // Tell PackageManager about changes to primary volume state, but only
        // when not emulated.
        if (volume.isPrimary() && !volume.isEmulated()) {
            if (Environment.MEDIA_UNMOUNTED.equals(state)) {
                mPms.updateExternalMediaStatus(false, false);

                /*
                 * Some OBBs might have been unmounted when this volume was
                 * unmounted, so send a message to the handler to let it know to
                 * remove those from the list of mounted OBBS.
                 */
                mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(
                        OBB_FLUSH_MOUNT_STATE, path));
            } else if (Environment.MEDIA_MOUNTED.equals(state)) {
                mPms.updateExternalMediaStatus(true, false);
            }
        }

        synchronized (mListeners) {
            for (int i = mListeners.size() -1; i >= 0; i--) {
                MountServiceBinderListener bl = mListeners.get(i);
                try {
                    bl.mListener.onStorageStateChanged(path, oldState, state);
                } catch (RemoteException rex) {
                    Slog.e(TAG, "Listener dead");
                    mListeners.remove(i);
                } catch (Exception ex) {
                    Slog.e(TAG, "Listener failed", ex);
                }
            }
        }
    }

    /**
     * Callback from NativeDaemonConnector
     */
    public void onDaemonConnected() {
        /*
         * Since we'll be calling back into the NativeDaemonConnector,
         * we need to do our work in a new thread.
         */
        new Thread("MountService#onDaemonConnected") {
            @Override
            public void run() {
                /**
                 * Determine media state and UMS detection status
                 */
                try {
                    final String[] vols = NativeDaemonEvent.filterMessageList(
                            mConnector.executeForList("volume", "list"),
                            VoldResponseCode.VolumeListResult);
                    for (String volstr : vols) {
                        String[] tok = volstr.split(" ");
                        // FMT: <label> <mountpoint> <state>
                        String path = tok[1];
                        String state = Environment.MEDIA_REMOVED;

                        final StorageVolume volume;
                        synchronized (mVolumesLock) {
                            volume = mVolumesByPath.get(path);
                        }

                        int st = Integer.parseInt(tok[2]);
                        if (st == VolumeState.NoMedia) {
                            state = Environment.MEDIA_REMOVED;
                        } else if (st == VolumeState.Idle) {
                            state = Environment.MEDIA_UNMOUNTED;
                        } else if (st == VolumeState.Mounted) {
                            state = Environment.MEDIA_MOUNTED;
                            Slog.i(TAG, "Media already mounted on daemon connection");
                        } else if (st == VolumeState.Shared) {
                            state = Environment.MEDIA_SHARED;
                            Slog.i(TAG, "Media shared on daemon connection");
                        } else {
                            throw new Exception(String.format("Unexpected state %d", st));
                        }

                        if (state != null) {
                            if (DEBUG_EVENTS) Slog.i(TAG, "Updating valid state " + state);
                            updatePublicVolumeState(volume, state);
                        }
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Error processing initial volume state", e);
                    final StorageVolume primary = getPrimaryPhysicalVolume();
                    if (primary != null) {
                        updatePublicVolumeState(primary, Environment.MEDIA_REMOVED);
                    }
                }

                /*
                 * Now that we've done our initialization, release
                 * the hounds!
                 */
                mConnectedSignal.countDown();

                // Let package manager load internal ASECs.
                mPms.scanAvailableAsecs();

                // Notify people waiting for ASECs to be scanned that it's done.
                mAsecsScanned.countDown();
            }
        }.start();
    }

    /**
     * Callback from NativeDaemonConnector
     */
    public boolean onEvent(int code, String raw, String[] cooked) {
        if (DEBUG_EVENTS) {
            StringBuilder builder = new StringBuilder();
            builder.append("onEvent::");
            builder.append(" raw= " + raw);
            if (cooked != null) {
                builder.append(" cooked = " );
                for (String str : cooked) {
                    builder.append(" " + str);
                }
            }
            Slog.i(TAG, builder.toString());
        }
        if (code == VoldResponseCode.VolumeStateChange) {
            /*
             * One of the volumes we're managing has changed state.
             * Format: "NNN Volume <label> <path> state changed
             * from <old_#> (<old_str>) to <new_#> (<new_str>)"
             */
            notifyVolumeStateChange(
                    cooked[2], cooked[3], Integer.parseInt(cooked[7]),
                            Integer.parseInt(cooked[10]));
        } else if ((code == VoldResponseCode.VolumeDiskInserted) ||
                   (code == VoldResponseCode.VolumeDiskRemoved) ||
                   (code == VoldResponseCode.VolumeBadRemoval)) {
            // FMT: NNN Volume <label> <mountpoint> disk inserted (<major>:<minor>)
            // FMT: NNN Volume <label> <mountpoint> disk removed (<major>:<minor>)
            // FMT: NNN Volume <label> <mountpoint> bad removal (<major>:<minor>)
            String action = null;
            final String label = cooked[2];
            final String path = cooked[3];
            int major = -1;
            int minor = -1;

            try {
                String devComp = cooked[6].substring(1, cooked[6].length() -1);
                String[] devTok = devComp.split(":");
                major = Integer.parseInt(devTok[0]);
                minor = Integer.parseInt(devTok[1]);
            } catch (Exception ex) {
                Slog.e(TAG, "Failed to parse major/minor", ex);
            }

            final StorageVolume volume;
            final String state;
            synchronized (mVolumesLock) {
                volume = mVolumesByPath.get(path);
                state = mVolumeStates.get(path);
            }

            if (code == VoldResponseCode.VolumeDiskInserted) {
                new Thread("MountService#VolumeDiskInserted") {
                    @Override
                    public void run() {
                        try {
                            int rc;
                            if ((rc = doMountVolume(path)) != StorageResultCode.OperationSucceeded) {
                                Slog.w(TAG, String.format("Insertion mount failed (%d)", rc));
                            }
                        } catch (Exception ex) {
                            Slog.w(TAG, "Failed to mount media on insertion", ex);
                        }
                    }
                }.start();
            } else if (code == VoldResponseCode.VolumeDiskRemoved) {
                /*
                 * This event gets trumped if we're already in BAD_REMOVAL state
                 */
                if (getVolumeState(path).equals(Environment.MEDIA_BAD_REMOVAL)) {
                    return true;
                }
                /* Send the media unmounted event first */
                if (DEBUG_EVENTS) Slog.i(TAG, "Sending unmounted event first");
                updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTED);
                sendStorageIntent(Environment.MEDIA_UNMOUNTED, volume, UserHandle.ALL);

                if (DEBUG_EVENTS) Slog.i(TAG, "Sending media removed");
                updatePublicVolumeState(volume, Environment.MEDIA_REMOVED);
                action = Intent.ACTION_MEDIA_REMOVED;
            } else if (code == VoldResponseCode.VolumeBadRemoval) {
                if (DEBUG_EVENTS) Slog.i(TAG, "Sending unmounted event first");
                /* Send the media unmounted event first */
                updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTED);
                sendStorageIntent(Intent.ACTION_MEDIA_UNMOUNTED, volume, UserHandle.ALL);

                if (DEBUG_EVENTS) Slog.i(TAG, "Sending media bad removal");
                updatePublicVolumeState(volume, Environment.MEDIA_BAD_REMOVAL);
                action = Intent.ACTION_MEDIA_BAD_REMOVAL;
            } else if (code == VoldResponseCode.FstrimCompleted) {
                EventLogTags.writeFstrimFinish(SystemClock.elapsedRealtime());
            } else {
                Slog.e(TAG, String.format("Unknown code {%d}", code));
            }

            if (action != null) {
                sendStorageIntent(action, volume, UserHandle.ALL);
            }
        } else {
            return false;
        }

        return true;
    }

    private void notifyVolumeStateChange(String label, String path, int oldState, int newState) {
        final StorageVolume volume;
        final String state;
        synchronized (mVolumesLock) {
            volume = mVolumesByPath.get(path);
            state = getVolumeState(path);
        }

        if (DEBUG_EVENTS) Slog.i(TAG, "notifyVolumeStateChange::" + state);

        String action = null;

        if (oldState == VolumeState.Shared && newState != oldState) {
            if (LOCAL_LOGD) Slog.d(TAG, "Sending ACTION_MEDIA_UNSHARED intent");
            sendStorageIntent(Intent.ACTION_MEDIA_UNSHARED, volume, UserHandle.ALL);
        }

        if (newState == VolumeState.Init) {
        } else if (newState == VolumeState.NoMedia) {
            // NoMedia is handled via Disk Remove events
        } else if (newState == VolumeState.Idle) {
            /*
             * Don't notify if we're in BAD_REMOVAL, NOFS, UNMOUNTABLE, or
             * if we're in the process of enabling UMS
             */
            if (!state.equals(
                    Environment.MEDIA_BAD_REMOVAL) && !state.equals(
                            Environment.MEDIA_NOFS) && !state.equals(
                                    Environment.MEDIA_UNMOUNTABLE) && !getUmsEnabling()) {
                if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state for media bad removal nofs and unmountable");
                updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTED);
                action = Intent.ACTION_MEDIA_UNMOUNTED;
            }
        } else if (newState == VolumeState.Pending) {
        } else if (newState == VolumeState.Checking) {
            if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state checking");
            updatePublicVolumeState(volume, Environment.MEDIA_CHECKING);
            action = Intent.ACTION_MEDIA_CHECKING;
        } else if (newState == VolumeState.Mounted) {
            if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state mounted");
            updatePublicVolumeState(volume, Environment.MEDIA_MOUNTED);
            action = Intent.ACTION_MEDIA_MOUNTED;
        } else if (newState == VolumeState.Unmounting) {
            action = Intent.ACTION_MEDIA_EJECT;
        } else if (newState == VolumeState.Formatting) {
        } else if (newState == VolumeState.Shared) {
            if (DEBUG_EVENTS) Slog.i(TAG, "Updating volume state media mounted");
            /* Send the media unmounted event first */
            updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTED);
            sendStorageIntent(Intent.ACTION_MEDIA_UNMOUNTED, volume, UserHandle.ALL);

            if (DEBUG_EVENTS) Slog.i(TAG, "Updating media shared");
            updatePublicVolumeState(volume, Environment.MEDIA_SHARED);
            action = Intent.ACTION_MEDIA_SHARED;
            if (LOCAL_LOGD) Slog.d(TAG, "Sending ACTION_MEDIA_SHARED intent");
        } else if (newState == VolumeState.SharedMnt) {
            Slog.e(TAG, "Live shared mounts not supported yet!");
            return;
        } else {
            Slog.e(TAG, "Unhandled VolumeState {" + newState + "}");
        }

        if (action != null) {
            sendStorageIntent(action, volume, UserHandle.ALL);
        }
    }

    private int doMountVolume(String path) {
        int rc = StorageResultCode.OperationSucceeded;

        final StorageVolume volume;
        synchronized (mVolumesLock) {
            volume = mVolumesByPath.get(path);
        }

        if (DEBUG_EVENTS) Slog.i(TAG, "doMountVolume: Mouting " + path);
        try {
            mConnector.execute("volume", "mount", path);
        } catch (NativeDaemonConnectorException e) {
            /*
             * Mount failed for some reason
             */
            String action = null;
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedNoMedia) {
                /*
                 * Attempt to mount but no media inserted
                 */
                rc = StorageResultCode.OperationFailedNoMedia;
            } else if (code == VoldResponseCode.OpFailedMediaBlank) {
                if (DEBUG_EVENTS) Slog.i(TAG, " updating volume state :: media nofs");
                /*
                 * Media is blank or does not contain a supported filesystem
                 */
                updatePublicVolumeState(volume, Environment.MEDIA_NOFS);
                action = Intent.ACTION_MEDIA_NOFS;
                rc = StorageResultCode.OperationFailedMediaBlank;
            } else if (code == VoldResponseCode.OpFailedMediaCorrupt) {
                if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state media corrupt");
                /*
                 * Volume consistency check failed
                 */
                updatePublicVolumeState(volume, Environment.MEDIA_UNMOUNTABLE);
                action = Intent.ACTION_MEDIA_UNMOUNTABLE;
                rc = StorageResultCode.OperationFailedMediaCorrupt;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }

            /*
             * Send broadcast intent (if required for the failure)
             */
            if (action != null) {
                sendStorageIntent(action, volume, UserHandle.ALL);
            }
        }

        return rc;
    }

    /*
     * If force is not set, we do not unmount if there are
     * processes holding references to the volume about to be unmounted.
     * If force is set, all the processes holding references need to be
     * killed via the ActivityManager before actually unmounting the volume.
     * This might even take a while and might be retried after timed delays
     * to make sure we dont end up in an instable state and kill some core
     * processes.
     * If removeEncryption is set, force is implied, and the system will remove any encryption
     * mapping set on the volume when unmounting.
     */
    private int doUnmountVolume(String path, boolean force, boolean removeEncryption) {
        if (!getVolumeState(path).equals(Environment.MEDIA_MOUNTED)) {
            return VoldResponseCode.OpFailedVolNotMounted;
        }

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();

        // Redundant probably. But no harm in updating state again.
        if (isPrimaryStorage(path)) {
            mPms.updateExternalMediaStatus(false, false);
        }
        try {
            final Command cmd = new Command("volume", "unmount", path);
            if (removeEncryption) {
                cmd.appendArg("force_and_revert");
            } else if (force) {
                cmd.appendArg("force");
            }
            mConnector.execute(cmd);
            // We unmounted the volume. None of the asec containers are available now.
            synchronized (mAsecMountSet) {
                mAsecMountSet.clear();
            }
            return StorageResultCode.OperationSucceeded;
        } catch (NativeDaemonConnectorException e) {
            // Don't worry about mismatch in PackageManager since the
            // call back will handle the status changes any way.
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedVolNotMounted) {
                return StorageResultCode.OperationFailedStorageNotMounted;
            } else if (code == VoldResponseCode.OpFailedStorageBusy) {
                return StorageResultCode.OperationFailedStorageBusy;
            } else {
                return StorageResultCode.OperationFailedInternalError;
            }
        }
    }

    private int doFormatVolume(String path) {
        try {
            mConnector.execute("volume", "format", path);
            return StorageResultCode.OperationSucceeded;
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedNoMedia) {
                return StorageResultCode.OperationFailedNoMedia;
            } else if (code == VoldResponseCode.OpFailedMediaCorrupt) {
                return StorageResultCode.OperationFailedMediaCorrupt;
            } else {
                return StorageResultCode.OperationFailedInternalError;
            }
        }
    }

    private boolean doGetVolumeShared(String path, String method) {
        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("volume", "shared", path, method);
        } catch (NativeDaemonConnectorException ex) {
            Slog.e(TAG, "Failed to read response to volume shared " + path + " " + method);
            return false;
        }

        if (event.getCode() == VoldResponseCode.ShareEnabledResult) {
            return event.getMessage().endsWith("enabled");
        } else {
            return false;
        }
    }

    private void notifyShareAvailabilityChange(final boolean avail) {
        synchronized (mListeners) {
            mUmsAvailable = avail;
            for (int i = mListeners.size() -1; i >= 0; i--) {
                MountServiceBinderListener bl = mListeners.get(i);
                try {
                    bl.mListener.onUsbMassStorageConnectionChanged(avail);
                } catch (RemoteException rex) {
                    Slog.e(TAG, "Listener dead");
                    mListeners.remove(i);
                } catch (Exception ex) {
                    Slog.e(TAG, "Listener failed", ex);
                }
            }
        }

        if (mSystemReady == true) {
            sendUmsIntent(avail);
        } else {
            mSendUmsConnectedOnBoot = avail;
        }

        final ArrayList<String> volumes = getShareableVolumes();
        boolean mediaShared = false;
        for (String path : volumes) {
            if (getVolumeState(path).equals(Environment.MEDIA_SHARED))
                mediaShared = true;
        }
        if (avail == false && mediaShared) {
            /*
             * USB mass storage disconnected while enabled
             */
            new Thread("MountService#AvailabilityChange") {
                @Override
                public void run() {
                    try {
                        int rc;
                        Slog.w(TAG, "Disabling UMS after cable disconnect");
                        for (String path : volumes) {
                            if (getVolumeState(path).equals(Environment.MEDIA_SHARED)) {
                                doShareUnshareVolume(path, "ums", false);
                                if ((rc = doMountVolume(path)) != StorageResultCode.OperationSucceeded) {
                                    Slog.e(TAG, String.format(
                                            "Failed to remount {%s} on UMS enabled-disconnect (%d)",
                                                    path, rc));
                                }
                            }
                        }
                    } catch (Exception ex) {
                        Slog.w(TAG, "Failed to mount media on UMS enabled-disconnect", ex);
                    }
                }
            }.start();
        }
    }

    private void sendStorageIntent(String action, StorageVolume volume, UserHandle user) {
        final Intent intent = new Intent(action, Uri.parse("file://" + volume.getPath()));
        intent.putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, volume);
        Slog.d(TAG, "sendStorageIntent " + intent + " to " + user);
        mContext.sendBroadcastAsUser(intent, user);
    }

    private void sendUmsIntent(boolean c) {
        mContext.sendBroadcastAsUser(
                new Intent((c ? Intent.ACTION_UMS_CONNECTED : Intent.ACTION_UMS_DISCONNECTED)),
                UserHandle.ALL);
    }

    private void validatePermission(String perm) {
        if (mContext.checkCallingOrSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(String.format("Requires %s permission", perm));
        }
    }

    // Storage list XML tags
    private static final String TAG_STORAGE_LIST = "StorageList";
    private static final String TAG_STORAGE = "storage";

    private void readStorageListLocked() {
        mVolumes.clear();
        mVolumeStates.clear();

        Resources resources = mContext.getResources();

        int id = com.android.internal.R.xml.storage_list;
        XmlResourceParser parser = resources.getXml(id);
        AttributeSet attrs = Xml.asAttributeSet(parser);

        try {
            XmlUtils.beginDocument(parser, TAG_STORAGE_LIST);
            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName();
                if (element == null) break;

                if (TAG_STORAGE.equals(element)) {
                    TypedArray a = resources.obtainAttributes(attrs,
                            com.android.internal.R.styleable.Storage);

                    String path = a.getString(
                            com.android.internal.R.styleable.Storage_mountPoint);
                    int descriptionId = a.getResourceId(
                            com.android.internal.R.styleable.Storage_storageDescription, -1);
                    CharSequence description = a.getText(
                            com.android.internal.R.styleable.Storage_storageDescription);
                    boolean primary = a.getBoolean(
                            com.android.internal.R.styleable.Storage_primary, false);
                    boolean removable = a.getBoolean(
                            com.android.internal.R.styleable.Storage_removable, false);
                    boolean emulated = a.getBoolean(
                            com.android.internal.R.styleable.Storage_emulated, false);
                    int mtpReserve = a.getInt(
                            com.android.internal.R.styleable.Storage_mtpReserve, 0);
                    boolean allowMassStorage = a.getBoolean(
                            com.android.internal.R.styleable.Storage_allowMassStorage, false);
                    // resource parser does not support longs, so XML value is in megabytes
                    long maxFileSize = a.getInt(
                            com.android.internal.R.styleable.Storage_maxFileSize, 0) * 1024L * 1024L;

                    Slog.d(TAG, "got storage path: " + path + " description: " + description +
                            " primary: " + primary + " removable: " + removable +
                            " emulated: " + emulated +  " mtpReserve: " + mtpReserve +
                            " allowMassStorage: " + allowMassStorage +
                            " maxFileSize: " + maxFileSize);

                    if (emulated && primary) {
                        // For devices with emulated primary storage,
                        // we create separate volumes for each known user.
                        mEmulatedTemplate = new StorageVolume(null, descriptionId, true, false,
                                true, mtpReserve, false, maxFileSize, null);

                        final UserManagerService userManager = UserManagerService.getInstance();
                        for (UserInfo user : userManager.getUsers(false)) {
                            createEmulatedVolumeForUserLocked(user.getUserHandle());
                        }

                    } else {
                        if (path == null || description == null) {
                            Slog.e(TAG, "Missing storage path or description in readStorageList");
                        } else {
                            final StorageVolume volume = new StorageVolume(new File(path),
                                    descriptionId, primary, removable, emulated, mtpReserve,
                                    allowMassStorage, maxFileSize, null);
                            addVolumeLocked(volume);

                            // Until we hear otherwise, treat as unmounted
                            mVolumeStates.put(volume.getPath(), Environment.MEDIA_UNMOUNTED);
                        }
                    }

                    a.recycle();
                }
            }
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            // Compute storage ID for each physical volume;
            // Primary emulated storage is always 0 when defined.
            int index = isExternalStorageEmulated() ? 1 : 0;
            for (StorageVolume volume : mVolumes) {
                if (!(volume.isEmulated() && volume.isPrimary())) {
                    volume.setStorageId(index++);
                }
            }
            parser.close();
        }
    }

    /**
     * Create and add new {@link StorageVolume} for given {@link UserHandle}
     * using {@link #mEmulatedTemplate} as template.
     */
    private void createEmulatedVolumeForUserLocked(UserHandle user) {
        if (mEmulatedTemplate == null) {
            throw new IllegalStateException("Missing emulated volume multi-user template");
        }

        final UserEnvironment userEnv = new UserEnvironment(user.getIdentifier());
        final File path = userEnv.getExternalStorageDirectory();
        final StorageVolume volume = StorageVolume.fromTemplate(mEmulatedTemplate, path, user);
        volume.setStorageId(0);
        addVolumeLocked(volume);

        if (mSystemReady) {
            updatePublicVolumeState(volume, Environment.MEDIA_MOUNTED);
        } else {
            // Place stub status for early callers to find
            mVolumeStates.put(volume.getPath(), Environment.MEDIA_MOUNTED);
        }
    }

    private void addVolumeLocked(StorageVolume volume) {
        Slog.d(TAG, "addVolumeLocked() " + volume);
        mVolumes.add(volume);
        final StorageVolume existing = mVolumesByPath.put(volume.getPath(), volume);
        if (existing != null) {
            throw new IllegalStateException(
                    "Volume at " + volume.getPath() + " already exists: " + existing);
        }
    }

    private void removeVolumeLocked(StorageVolume volume) {
        Slog.d(TAG, "removeVolumeLocked() " + volume);
        mVolumes.remove(volume);
        mVolumesByPath.remove(volume.getPath());
        mVolumeStates.remove(volume.getPath());
    }

    private StorageVolume getPrimaryPhysicalVolume() {
        synchronized (mVolumesLock) {
            for (StorageVolume volume : mVolumes) {
                if (volume.isPrimary() && !volume.isEmulated()) {
                    return volume;
                }
            }
        }
        return null;
    }

    /**
     * Constructs a new MountService instance
     *
     * @param context  Binder context for this service
     */
    public MountService(Context context) {
        mContext = context;

        synchronized (mVolumesLock) {
            readStorageListLocked();
        }

        // XXX: This will go away soon in favor of IMountServiceObserver
        mPms = (PackageManagerService) ServiceManager.getService("package");

        HandlerThread hthread = new HandlerThread(TAG);
        hthread.start();
        mHandler = new MountServiceHandler(hthread.getLooper());

        // Watch for user changes
        final IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(mUserReceiver, userFilter, null, mHandler);

        // Watch for USB changes on primary volume
        final StorageVolume primary = getPrimaryPhysicalVolume();
        if (primary != null && primary.allowMassStorage()) {
            mContext.registerReceiver(
                    mUsbReceiver, new IntentFilter(UsbManager.ACTION_USB_STATE), null, mHandler);
        }

        // Watch for idle maintenance changes
        IntentFilter idleMaintenanceFilter = new IntentFilter();
        idleMaintenanceFilter.addAction(Intent.ACTION_IDLE_MAINTENANCE_START);
        mContext.registerReceiverAsUser(mIdleMaintenanceReceiver, UserHandle.ALL,
                idleMaintenanceFilter, null, mHandler);

        // Add OBB Action Handler to MountService thread.
        mObbActionHandler = new ObbActionHandler(IoThread.get().getLooper());

        /*
         * Create the connection to vold with a maximum queue of twice the
         * amount of containers we'd ever expect to have. This keeps an
         * "asec list" from blocking a thread repeatedly.
         */
        mConnector = new NativeDaemonConnector(this, "vold", MAX_CONTAINERS * 2, VOLD_TAG, 25);

        Thread thread = new Thread(mConnector, VOLD_TAG);
        thread.start();

        // Add ourself to the Watchdog monitors if enabled.
        if (WATCHDOG_ENABLE) {
            Watchdog.getInstance().addMonitor(this);
        }
    }

    public void systemReady() {
        mSystemReady = true;
        mHandler.obtainMessage(H_SYSTEM_READY).sendToTarget();
    }

    /**
     * Exposed API calls below here
     */

    public void registerListener(IMountServiceListener listener) {
        synchronized (mListeners) {
            MountServiceBinderListener bl = new MountServiceBinderListener(listener);
            try {
                listener.asBinder().linkToDeath(bl, 0);
                mListeners.add(bl);
            } catch (RemoteException rex) {
                Slog.e(TAG, "Failed to link to listener death");
            }
        }
    }

    public void unregisterListener(IMountServiceListener listener) {
        synchronized (mListeners) {
            for(MountServiceBinderListener bl : mListeners) {
                if (bl.mListener == listener) {
                    mListeners.remove(mListeners.indexOf(bl));
                    listener.asBinder().unlinkToDeath(bl, 0);
                    return;
                }
            }
        }
    }

    public void shutdown(final IMountShutdownObserver observer) {
        validatePermission(android.Manifest.permission.SHUTDOWN);

        Slog.i(TAG, "Shutting down");
        synchronized (mVolumesLock) {
            for (String path : mVolumeStates.keySet()) {
                String state = mVolumeStates.get(path);

                if (state.equals(Environment.MEDIA_SHARED)) {
                    /*
                     * If the media is currently shared, unshare it.
                     * XXX: This is still dangerous!. We should not
                     * be rebooting at *all* if UMS is enabled, since
                     * the UMS host could have dirty FAT cache entries
                     * yet to flush.
                     */
                    setUsbMassStorageEnabled(false);
                } else if (state.equals(Environment.MEDIA_CHECKING)) {
                    /*
                     * If the media is being checked, then we need to wait for
                     * it to complete before being able to proceed.
                     */
                    // XXX: @hackbod - Should we disable the ANR timer here?
                    int retries = 30;
                    while (state.equals(Environment.MEDIA_CHECKING) && (retries-- >=0)) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException iex) {
                            Slog.e(TAG, "Interrupted while waiting for media", iex);
                            break;
                        }
                        state = Environment.getExternalStorageState();
                    }
                    if (retries == 0) {
                        Slog.e(TAG, "Timed out waiting for media to check");
                    }
                }

                if (state.equals(Environment.MEDIA_MOUNTED)) {
                    // Post a unmount message.
                    ShutdownCallBack ucb = new ShutdownCallBack(path, observer);
                    mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, ucb));
                } else if (observer != null) {
                    /*
                     * Observer is waiting for onShutDownComplete when we are done.
                     * Since nothing will be done send notification directly so shutdown
                     * sequence can continue.
                     */
                    try {
                        observer.onShutDownComplete(StorageResultCode.OperationSucceeded);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoteException when shutting down");
                    }
                }
            }
        }
    }

    private boolean getUmsEnabling() {
        synchronized (mListeners) {
            return mUmsEnabling;
        }
    }

    private void setUmsEnabling(boolean enable) {
        synchronized (mListeners) {
            mUmsEnabling = enable;
        }
    }

    public boolean isUsbMassStorageConnected() {
        waitForReady();

        if (getUmsEnabling()) {
            return true;
        }
        synchronized (mListeners) {
            return mUmsAvailable;
        }
    }

    private boolean isPrimaryStorage(String path) {
        synchronized (mVolumesLock) {
            for (StorageVolume volume : mVolumes) {
                if (volume.isPrimary() && volume.getPath().equals(path)) {
                    return true;
                }
            }
            return false;
        }
    }

    private ArrayList<String> getShareableVolumes() {
        // Sharable volumes have android:allowMassStorage="true" in storage_list.xml
        ArrayList<String> volumesToMount = new ArrayList<String>();
        synchronized (mVolumesLock) {
            for (StorageVolume v : mVolumes) {
                if (v.allowMassStorage()) {
                    volumesToMount.add(v.getPath());
                }
            }
        }
        return volumesToMount;
    }

    public void setUsbMassStorageEnabled(boolean enable) {
        waitForReady();
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        final StorageVolume primary = getPrimaryPhysicalVolume();
        if (primary == null) return;

        // TODO: Add support for multiple share methods

        for (String path : getShareableVolumes()) {
            /*
             * If the volume is mounted and we're enabling then unmount it
             */
            String vs = getVolumeState(path);
            String method = "ums";
            if (enable && vs.equals(Environment.MEDIA_MOUNTED)) {
                // Override for isUsbMassStorageEnabled()
                setUmsEnabling(enable);
                UmsEnableCallBack umscb = new UmsEnableCallBack(path, method, true);
                mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, umscb));
                // Clear override
                setUmsEnabling(false);
            }
            /*
             * If we disabled UMS then mount the volume
             */
            if (!enable) {
                doShareUnshareVolume(path, method, enable);
                if (doMountVolume(path) != StorageResultCode.OperationSucceeded) {
                    Slog.e(TAG, "Failed to remount " + path +
                            " after disabling share method " + method);
                    /*
                     * Even though the mount failed, the unshare didn't so don't indicate an error.
                     * The mountVolume() call will have set the storage state and sent the necessary
                     * broadcasts.
                     */
                }
            }
        }
    }

    public boolean isUsbMassStorageEnabled() {
        waitForReady();

        for (String path : getShareableVolumes()) {
            if (doGetVolumeShared(path, "ums"))
                return true;
        }
        // no volume is shared
        return false;
    }

    /**
     * @return state of the volume at the specified mount point
     */
    public String getVolumeState(String mountPoint) {
        synchronized (mVolumesLock) {
            String state = mVolumeStates.get(mountPoint);
            if (state == null) {
                Slog.w(TAG, "getVolumeState(" + mountPoint + "): Unknown volume");
                if (SystemProperties.get("vold.encrypt_progress").length() != 0) {
                    state = Environment.MEDIA_REMOVED;
                } else {
                    throw new IllegalArgumentException();
                }
            }

            return state;
        }
    }

    @Override
    public boolean isExternalStorageEmulated() {
        return mEmulatedTemplate != null;
    }

    public int mountVolume(String path) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        waitForReady();
        return doMountVolume(path);
    }

    public void unmountVolume(String path, boolean force, boolean removeEncryption) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        String volState = getVolumeState(path);
        if (DEBUG_UNMOUNT) {
            Slog.i(TAG, "Unmounting " + path
                    + " force = " + force
                    + " removeEncryption = " + removeEncryption);
        }
        if (Environment.MEDIA_UNMOUNTED.equals(volState) ||
                Environment.MEDIA_REMOVED.equals(volState) ||
                Environment.MEDIA_SHARED.equals(volState) ||
                Environment.MEDIA_UNMOUNTABLE.equals(volState)) {
            // Media already unmounted or cannot be unmounted.
            // TODO return valid return code when adding observer call back.
            return;
        }
        UnmountCallBack ucb = new UnmountCallBack(path, force, removeEncryption);
        mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, ucb));
    }

    public int formatVolume(String path) {
        validatePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        return doFormatVolume(path);
    }

    public int[] getStorageUsers(String path) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();
        try {
            final String[] r = NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("storage", "users", path),
                    VoldResponseCode.StorageUsersListResult);

            // FMT: <pid> <process name>
            int[] data = new int[r.length];
            for (int i = 0; i < r.length; i++) {
                String[] tok = r[i].split(" ");
                try {
                    data[i] = Integer.parseInt(tok[0]);
                } catch (NumberFormatException nfe) {
                    Slog.e(TAG, String.format("Error parsing pid %s", tok[0]));
                    return new int[0];
                }
            }
            return data;
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to retrieve storage users list", e);
            return new int[0];
        }
    }

    private void warnOnNotMounted() {
        final StorageVolume primary = getPrimaryPhysicalVolume();
        if (primary != null) {
            boolean mounted = false;
            try {
                mounted = Environment.MEDIA_MOUNTED.equals(getVolumeState(primary.getPath()));
            } catch (IllegalArgumentException e) {
            }

            if (!mounted) {
                Slog.w(TAG, "getSecureContainerList() called when storage not mounted");
            }
        }
    }

    public String[] getSecureContainerList() {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        try {
            return NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("asec", "list"), VoldResponseCode.AsecListResult);
        } catch (NativeDaemonConnectorException e) {
            return new String[0];
        }
    }

    public int createSecureContainer(String id, int sizeMb, String fstype, String key,
            int ownerUid, boolean external) {
        validatePermission(android.Manifest.permission.ASEC_CREATE);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "create", id, sizeMb, fstype, new SensitiveArg(key),
                    ownerUid, external ? "1" : "0");
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.add(id);
            }
        }
        return rc;
    }

    public int finalizeSecureContainer(String id) {
        validatePermission(android.Manifest.permission.ASEC_CREATE);
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "finalize", id);
            /*
             * Finalization does a remount, so no need
             * to update mAsecMountSet
             */
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int fixPermissionsSecureContainer(String id, int gid, String filename) {
        validatePermission(android.Manifest.permission.ASEC_CREATE);
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "fixperms", id, gid, filename);
            /*
             * Fix permissions does a remount, so no need to update
             * mAsecMountSet
             */
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int destroySecureContainer(String id, boolean force) {
        validatePermission(android.Manifest.permission.ASEC_DESTROY);
        waitForReady();
        warnOnNotMounted();

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            final Command cmd = new Command("asec", "destroy", id);
            if (force) {
                cmd.appendArg("force");
            }
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedStorageBusy;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                if (mAsecMountSet.contains(id)) {
                    mAsecMountSet.remove(id);
                }
            }
        }

        return rc;
    }

    public int mountSecureContainer(String id, String key, int ownerUid) {
        validatePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            if (mAsecMountSet.contains(id)) {
                return StorageResultCode.OperationFailedStorageMounted;
            }
        }

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "mount", id, new SensitiveArg(key), ownerUid);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code != VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.add(id);
            }
        }
        return rc;
    }

    public int unmountSecureContainer(String id, boolean force) {
        validatePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            if (!mAsecMountSet.contains(id)) {
                return StorageResultCode.OperationFailedStorageNotMounted;
            }
         }

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            final Command cmd = new Command("asec", "unmount", id);
            if (force) {
                cmd.appendArg("force");
            }
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedStorageBusy;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.remove(id);
            }
        }
        return rc;
    }

    public boolean isSecureContainerMounted(String id) {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            return mAsecMountSet.contains(id);
        }
    }

    public int renameSecureContainer(String oldId, String newId) {
        validatePermission(android.Manifest.permission.ASEC_RENAME);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            /*
             * Because a mounted container has active internal state which cannot be
             * changed while active, we must ensure both ids are not currently mounted.
             */
            if (mAsecMountSet.contains(oldId) || mAsecMountSet.contains(newId)) {
                return StorageResultCode.OperationFailedStorageMounted;
            }
        }

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "rename", oldId, newId);
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }

        return rc;
    }

    public String getSecureContainerPath(String id) {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("asec", "path", id);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                Slog.i(TAG, String.format("Container '%s' not found", id));
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    public String getSecureContainerFilesystemPath(String id) {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("asec", "fspath", id);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                Slog.i(TAG, String.format("Container '%s' not found", id));
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    public void finishMediaUpdate() {
        mHandler.sendEmptyMessage(H_UNMOUNT_PM_DONE);
    }

    private boolean isUidOwnerOfPackageOrSystem(String packageName, int callerUid) {
        if (callerUid == android.os.Process.SYSTEM_UID) {
            return true;
        }

        if (packageName == null) {
            return false;
        }

        final int packageUid = mPms.getPackageUid(packageName, UserHandle.getUserId(callerUid));

        if (DEBUG_OBB) {
            Slog.d(TAG, "packageName = " + packageName + ", packageUid = " +
                    packageUid + ", callerUid = " + callerUid);
        }

        return callerUid == packageUid;
    }

    public String getMountedObbPath(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        waitForReady();
        warnOnNotMounted();

        final ObbState state;
        synchronized (mObbPathToStateMap) {
            state = mObbPathToStateMap.get(rawPath);
        }
        if (state == null) {
            Slog.w(TAG, "Failed to find OBB mounted at " + rawPath);
            return null;
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("obb", "path", state.voldPath);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    @Override
    public boolean isObbMounted(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        synchronized (mObbMounts) {
            return mObbPathToStateMap.containsKey(rawPath);
        }
    }

    @Override
    public void mountObb(
            String rawPath, String canonicalPath, String key, IObbActionListener token, int nonce) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        Preconditions.checkNotNull(canonicalPath, "canonicalPath cannot be null");
        Preconditions.checkNotNull(token, "token cannot be null");

        final int callingUid = Binder.getCallingUid();
        final ObbState obbState = new ObbState(rawPath, canonicalPath, callingUid, token, nonce);
        final ObbAction action = new MountObbAction(obbState, key, callingUid);
        mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));

        if (DEBUG_OBB)
            Slog.i(TAG, "Send to OBB handler: " + action.toString());
    }

    @Override
    public void unmountObb(String rawPath, boolean force, IObbActionListener token, int nonce) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        final ObbState existingState;
        synchronized (mObbPathToStateMap) {
            existingState = mObbPathToStateMap.get(rawPath);
        }

        if (existingState != null) {
            // TODO: separate state object from request data
            final int callingUid = Binder.getCallingUid();
            final ObbState newState = new ObbState(
                    rawPath, existingState.canonicalPath, callingUid, token, nonce);
            final ObbAction action = new UnmountObbAction(newState, force);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));

            if (DEBUG_OBB)
                Slog.i(TAG, "Send to OBB handler: " + action.toString());
        } else {
            Slog.w(TAG, "Unknown OBB mount at " + rawPath);
        }
    }

    @Override
    public int getEncryptionState() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "no permission to access the crypt keeper");

        waitForReady();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "cryptocomplete");
            return Integer.parseInt(event.getMessage());
        } catch (NumberFormatException e) {
            // Bad result - unexpected.
            Slog.w(TAG, "Unable to parse result from cryptfs cryptocomplete");
            return ENCRYPTION_STATE_ERROR_UNKNOWN;
        } catch (NativeDaemonConnectorException e) {
            // Something bad happened.
            Slog.w(TAG, "Error in communicating with cryptfs in validating");
            return ENCRYPTION_STATE_ERROR_UNKNOWN;
        }
    }

    @Override
    public int decryptStorage(String password) {
        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "decrypting storage...");
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "checkpw", new SensitiveArg(password));

            final int code = Integer.parseInt(event.getMessage());
            if (code == 0) {
                // Decrypt was successful. Post a delayed message before restarting in order
                // to let the UI to clear itself
                mHandler.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            mConnector.execute("cryptfs", "restart");
                        } catch (NativeDaemonConnectorException e) {
                            Slog.e(TAG, "problem executing in background", e);
                        }
                    }
                }, 1000); // 1 second
            }

            return code;
        } catch (NativeDaemonConnectorException e) {
            // Decryption failed
            return e.getCode();
        }
    }

    public int encryptStorage(String password) {
        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "encrypting storage...");
        }

        try {
            mConnector.execute("cryptfs", "enablecrypto", "inplace", new SensitiveArg(password));
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }

        return 0;
    }

    public int changeEncryptionPassword(String password) {
        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "changing encryption password...");
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "changepw", new SensitiveArg(password));
            return Integer.parseInt(event.getMessage());
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }
    }

    /**
     * Validate a user-supplied password string with cryptfs
     */
    @Override
    public int verifyEncryptionPassword(String password) throws RemoteException {
        // Only the system process is permitted to validate passwords
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new SecurityException("no permission to access the crypt keeper");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "validating encryption password...");
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("cryptfs", "verifypw", new SensitiveArg(password));
            Slog.i(TAG, "cryptfs verifypw => " + event.getMessage());
            return Integer.parseInt(event.getMessage());
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }
    }

    @Override
    public int mkdirs(String callingPkg, String appPath) {
        final int userId = UserHandle.getUserId(Binder.getCallingUid());
        final UserEnvironment userEnv = new UserEnvironment(userId);

        // Validate that reported package name belongs to caller
        final AppOpsManager appOps = (AppOpsManager) mContext.getSystemService(
                Context.APP_OPS_SERVICE);
        appOps.checkPackage(Binder.getCallingUid(), callingPkg);

        try {
            appPath = new File(appPath).getCanonicalPath();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to resolve " + appPath + ": " + e);
            return -1;
        }

        if (!appPath.endsWith("/")) {
            appPath = appPath + "/";
        }

        // Try translating the app path into a vold path, but require that it
        // belong to the calling package.
        String voldPath = maybeTranslatePathForVold(appPath,
                userEnv.buildExternalStorageAppDataDirs(callingPkg),
                userEnv.buildExternalStorageAppDataDirsForVold(callingPkg));
        if (voldPath != null) {
            try {
                mConnector.execute("volume", "mkdirs", voldPath);
                return 0;
            } catch (NativeDaemonConnectorException e) {
                return e.getCode();
            }
        }

        voldPath = maybeTranslatePathForVold(appPath,
                userEnv.buildExternalStorageAppObbDirs(callingPkg),
                userEnv.buildExternalStorageAppObbDirsForVold(callingPkg));
        if (voldPath != null) {
            try {
                mConnector.execute("volume", "mkdirs", voldPath);
                return 0;
            } catch (NativeDaemonConnectorException e) {
                return e.getCode();
            }
        }

        throw new SecurityException("Invalid mkdirs path: " + appPath);
    }

    /**
     * Translate the given path from an app-visible path to a vold-visible path,
     * but only if it's under the given whitelisted paths.
     *
     * @param path a canonicalized app-visible path.
     * @param appPaths list of app-visible paths that are allowed.
     * @param voldPaths list of vold-visible paths directly corresponding to the
     *            allowed app-visible paths argument.
     * @return a vold-visible path representing the original path, or
     *         {@code null} if the given path didn't have an app-to-vold
     *         mapping.
     */
    @VisibleForTesting
    public static String maybeTranslatePathForVold(
            String path, File[] appPaths, File[] voldPaths) {
        if (appPaths.length != voldPaths.length) {
            throw new IllegalStateException("Paths must be 1:1 mapping");
        }

        for (int i = 0; i < appPaths.length; i++) {
            final String appPath = appPaths[i].getAbsolutePath() + "/";
            if (path.startsWith(appPath)) {
                path = new File(voldPaths[i], path.substring(appPath.length()))
                        .getAbsolutePath();
                if (!path.endsWith("/")) {
                    path = path + "/";
                }
                return path;
            }
        }
        return null;
    }

    @Override
    public StorageVolume[] getVolumeList() {
        final int callingUserId = UserHandle.getCallingUserId();
        final boolean accessAll = (mContext.checkPermission(
                android.Manifest.permission.ACCESS_ALL_EXTERNAL_STORAGE,
                Binder.getCallingPid(), Binder.getCallingUid()) == PERMISSION_GRANTED);

        synchronized (mVolumesLock) {
            final ArrayList<StorageVolume> filtered = Lists.newArrayList();
            for (StorageVolume volume : mVolumes) {
                final UserHandle owner = volume.getOwner();
                final boolean ownerMatch = owner == null || owner.getIdentifier() == callingUserId;
                if (accessAll || ownerMatch) {
                    filtered.add(volume);
                }
            }
            return filtered.toArray(new StorageVolume[filtered.size()]);
        }
    }

    private void addObbStateLocked(ObbState obbState) throws RemoteException {
        final IBinder binder = obbState.getBinder();
        List<ObbState> obbStates = mObbMounts.get(binder);

        if (obbStates == null) {
            obbStates = new ArrayList<ObbState>();
            mObbMounts.put(binder, obbStates);
        } else {
            for (final ObbState o : obbStates) {
                if (o.rawPath.equals(obbState.rawPath)) {
                    throw new IllegalStateException("Attempt to add ObbState twice. "
                            + "This indicates an error in the MountService logic.");
                }
            }
        }

        obbStates.add(obbState);
        try {
            obbState.link();
        } catch (RemoteException e) {
            /*
             * The binder died before we could link it, so clean up our state
             * and return failure.
             */
            obbStates.remove(obbState);
            if (obbStates.isEmpty()) {
                mObbMounts.remove(binder);
            }

            // Rethrow the error so mountObb can get it
            throw e;
        }

        mObbPathToStateMap.put(obbState.rawPath, obbState);
    }

    private void removeObbStateLocked(ObbState obbState) {
        final IBinder binder = obbState.getBinder();
        final List<ObbState> obbStates = mObbMounts.get(binder);
        if (obbStates != null) {
            if (obbStates.remove(obbState)) {
                obbState.unlink();
            }
            if (obbStates.isEmpty()) {
                mObbMounts.remove(binder);
            }
        }

        mObbPathToStateMap.remove(obbState.rawPath);
    }

    private class ObbActionHandler extends Handler {
        private boolean mBound = false;
        private final List<ObbAction> mActions = new LinkedList<ObbAction>();

        ObbActionHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case OBB_RUN_ACTION: {
                    final ObbAction action = (ObbAction) msg.obj;

                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_RUN_ACTION: " + action.toString());

                    // If a bind was already initiated we don't really
                    // need to do anything. The pending install
                    // will be processed later on.
                    if (!mBound) {
                        // If this is the only one pending we might
                        // have to bind to the service again.
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            action.handleError();
                            return;
                        }
                    }

                    mActions.add(action);
                    break;
                }
                case OBB_MCS_BOUND: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_BOUND");
                    if (msg.obj != null) {
                        mContainerService = (IMediaContainerService) msg.obj;
                    }
                    if (mContainerService == null) {
                        // Something seriously wrong. Bail out
                        Slog.e(TAG, "Cannot bind to media container service");
                        for (ObbAction action : mActions) {
                            // Indicate service bind error
                            action.handleError();
                        }
                        mActions.clear();
                    } else if (mActions.size() > 0) {
                        final ObbAction action = mActions.get(0);
                        if (action != null) {
                            action.execute(this);
                        }
                    } else {
                        // Should never happen ideally.
                        Slog.w(TAG, "Empty queue");
                    }
                    break;
                }
                case OBB_MCS_RECONNECT: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_RECONNECT");
                    if (mActions.size() > 0) {
                        if (mBound) {
                            disconnectService();
                        }
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            for (ObbAction action : mActions) {
                                // Indicate service bind error
                                action.handleError();
                            }
                            mActions.clear();
                        }
                    }
                    break;
                }
                case OBB_MCS_UNBIND: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_UNBIND");

                    // Delete pending install
                    if (mActions.size() > 0) {
                        mActions.remove(0);
                    }
                    if (mActions.size() == 0) {
                        if (mBound) {
                            disconnectService();
                        }
                    } else {
                        // There are more pending requests in queue.
                        // Just post MCS_BOUND message to trigger processing
                        // of next pending install.
                        mObbActionHandler.sendEmptyMessage(OBB_MCS_BOUND);
                    }
                    break;
                }
                case OBB_FLUSH_MOUNT_STATE: {
                    final String path = (String) msg.obj;

                    if (DEBUG_OBB)
                        Slog.i(TAG, "Flushing all OBB state for path " + path);

                    synchronized (mObbMounts) {
                        final List<ObbState> obbStatesToRemove = new LinkedList<ObbState>();

                        final Iterator<ObbState> i = mObbPathToStateMap.values().iterator();
                        while (i.hasNext()) {
                            final ObbState state = i.next();

                            /*
                             * If this entry's source file is in the volume path
                             * that got unmounted, remove it because it's no
                             * longer valid.
                             */
                            if (state.canonicalPath.startsWith(path)) {
                                obbStatesToRemove.add(state);
                            }
                        }

                        for (final ObbState obbState : obbStatesToRemove) {
                            if (DEBUG_OBB)
                                Slog.i(TAG, "Removing state for " + obbState.rawPath);

                            removeObbStateLocked(obbState);

                            try {
                                obbState.token.onObbResult(obbState.rawPath, obbState.nonce,
                                        OnObbStateChangeListener.UNMOUNTED);
                            } catch (RemoteException e) {
                                Slog.i(TAG, "Couldn't send unmount notification for  OBB: "
                                        + obbState.rawPath);
                            }
                        }
                    }
                    break;
                }
            }
        }

        private boolean connectToService() {
            if (DEBUG_OBB)
                Slog.i(TAG, "Trying to bind to DefaultContainerService");

            Intent service = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
            if (mContext.bindService(service, mDefContainerConn, Context.BIND_AUTO_CREATE)) {
                mBound = true;
                return true;
            }
            return false;
        }

        private void disconnectService() {
            mContainerService = null;
            mBound = false;
            mContext.unbindService(mDefContainerConn);
        }
    }

    abstract class ObbAction {
        private static final int MAX_RETRIES = 3;
        private int mRetries;

        ObbState mObbState;

        ObbAction(ObbState obbState) {
            mObbState = obbState;
        }

        public void execute(ObbActionHandler handler) {
            try {
                if (DEBUG_OBB)
                    Slog.i(TAG, "Starting to execute action: " + toString());
                mRetries++;
                if (mRetries > MAX_RETRIES) {
                    Slog.w(TAG, "Failed to invoke remote methods on default container service. Giving up");
                    mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
                    handleError();
                    return;
                } else {
                    handleExecute();
                    if (DEBUG_OBB)
                        Slog.i(TAG, "Posting install MCS_UNBIND");
                    mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
                }
            } catch (RemoteException e) {
                if (DEBUG_OBB)
                    Slog.i(TAG, "Posting install MCS_RECONNECT");
                mObbActionHandler.sendEmptyMessage(OBB_MCS_RECONNECT);
            } catch (Exception e) {
                if (DEBUG_OBB)
                    Slog.d(TAG, "Error handling OBB action", e);
                handleError();
                mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
            }
        }

        abstract void handleExecute() throws RemoteException, IOException;
        abstract void handleError();

        protected ObbInfo getObbInfo() throws IOException {
            ObbInfo obbInfo;
            try {
                obbInfo = mContainerService.getObbInfo(mObbState.ownerPath);
            } catch (RemoteException e) {
                Slog.d(TAG, "Couldn't call DefaultContainerService to fetch OBB info for "
                        + mObbState.ownerPath);
                obbInfo = null;
            }
            if (obbInfo == null) {
                throw new IOException("Couldn't read OBB file: " + mObbState.ownerPath);
            }
            return obbInfo;
        }

        protected void sendNewStatusOrIgnore(int status) {
            if (mObbState == null || mObbState.token == null) {
                return;
            }

            try {
                mObbState.token.onObbResult(mObbState.rawPath, mObbState.nonce, status);
            } catch (RemoteException e) {
                Slog.w(TAG, "MountServiceListener went away while calling onObbStateChanged");
            }
        }
    }

    class MountObbAction extends ObbAction {
        private final String mKey;
        private final int mCallingUid;

        MountObbAction(ObbState obbState, String key, int callingUid) {
            super(obbState);
            mKey = key;
            mCallingUid = callingUid;
        }

        @Override
        public void handleExecute() throws IOException, RemoteException {
            waitForReady();
            warnOnNotMounted();

            final ObbInfo obbInfo = getObbInfo();

            if (!isUidOwnerOfPackageOrSystem(obbInfo.packageName, mCallingUid)) {
                Slog.w(TAG, "Denied attempt to mount OBB " + obbInfo.filename
                        + " which is owned by " + obbInfo.packageName);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_PERMISSION_DENIED);
                return;
            }

            final boolean isMounted;
            synchronized (mObbMounts) {
                isMounted = mObbPathToStateMap.containsKey(mObbState.rawPath);
            }
            if (isMounted) {
                Slog.w(TAG, "Attempt to mount OBB which is already mounted: " + obbInfo.filename);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_ALREADY_MOUNTED);
                return;
            }

            final String hashedKey;
            if (mKey == null) {
                hashedKey = "none";
            } else {
                try {
                    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");

                    KeySpec ks = new PBEKeySpec(mKey.toCharArray(), obbInfo.salt,
                            PBKDF2_HASH_ROUNDS, CRYPTO_ALGORITHM_KEY_SIZE);
                    SecretKey key = factory.generateSecret(ks);
                    BigInteger bi = new BigInteger(key.getEncoded());
                    hashedKey = bi.toString(16);
                } catch (NoSuchAlgorithmException e) {
                    Slog.e(TAG, "Could not load PBKDF2 algorithm", e);
                    sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
                    return;
                } catch (InvalidKeySpecException e) {
                    Slog.e(TAG, "Invalid key spec when loading PBKDF2 algorithm", e);
                    sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
                    return;
                }
            }

            int rc = StorageResultCode.OperationSucceeded;
            try {
                mConnector.execute("obb", "mount", mObbState.voldPath, new SensitiveArg(hashedKey),
                        mObbState.ownerGid);
            } catch (NativeDaemonConnectorException e) {
                int code = e.getCode();
                if (code != VoldResponseCode.OpFailedStorageBusy) {
                    rc = StorageResultCode.OperationFailedInternalError;
                }
            }

            if (rc == StorageResultCode.OperationSucceeded) {
                if (DEBUG_OBB)
                    Slog.d(TAG, "Successfully mounted OBB " + mObbState.voldPath);

                synchronized (mObbMounts) {
                    addObbStateLocked(mObbState);
                }

                sendNewStatusOrIgnore(OnObbStateChangeListener.MOUNTED);
            } else {
                Slog.e(TAG, "Couldn't mount OBB file: " + rc);

                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT);
            }
        }

        @Override
        public void handleError() {
            sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("MountObbAction{");
            sb.append(mObbState);
            sb.append('}');
            return sb.toString();
        }
    }

    class UnmountObbAction extends ObbAction {
        private final boolean mForceUnmount;

        UnmountObbAction(ObbState obbState, boolean force) {
            super(obbState);
            mForceUnmount = force;
        }

        @Override
        public void handleExecute() throws IOException {
            waitForReady();
            warnOnNotMounted();

            final ObbInfo obbInfo = getObbInfo();

            final ObbState existingState;
            synchronized (mObbMounts) {
                existingState = mObbPathToStateMap.get(mObbState.rawPath);
            }

            if (existingState == null) {
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_NOT_MOUNTED);
                return;
            }

            if (existingState.ownerGid != mObbState.ownerGid) {
                Slog.w(TAG, "Permission denied attempting to unmount OBB " + existingState.rawPath
                        + " (owned by GID " + existingState.ownerGid + ")");
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_PERMISSION_DENIED);
                return;
            }

            int rc = StorageResultCode.OperationSucceeded;
            try {
                final Command cmd = new Command("obb", "unmount", mObbState.voldPath);
                if (mForceUnmount) {
                    cmd.appendArg("force");
                }
                mConnector.execute(cmd);
            } catch (NativeDaemonConnectorException e) {
                int code = e.getCode();
                if (code == VoldResponseCode.OpFailedStorageBusy) {
                    rc = StorageResultCode.OperationFailedStorageBusy;
                } else if (code == VoldResponseCode.OpFailedStorageNotFound) {
                    // If it's not mounted then we've already won.
                    rc = StorageResultCode.OperationSucceeded;
                } else {
                    rc = StorageResultCode.OperationFailedInternalError;
                }
            }

            if (rc == StorageResultCode.OperationSucceeded) {
                synchronized (mObbMounts) {
                    removeObbStateLocked(existingState);
                }

                sendNewStatusOrIgnore(OnObbStateChangeListener.UNMOUNTED);
            } else {
                Slog.w(TAG, "Could not unmount OBB: " + existingState);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_COULD_NOT_UNMOUNT);
            }
        }

        @Override
        public void handleError() {
            sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("UnmountObbAction{");
            sb.append(mObbState);
            sb.append(",force=");
            sb.append(mForceUnmount);
            sb.append('}');
            return sb.toString();
        }
    }

    @VisibleForTesting
    public static String buildObbPath(final String canonicalPath, int userId, boolean forVold) {
        // TODO: allow caller to provide Environment for full testing
        // TODO: extend to support OBB mounts on secondary external storage

        // Only adjust paths when storage is emulated
        if (!Environment.isExternalStorageEmulated()) {
            return canonicalPath;
        }

        String path = canonicalPath.toString();

        // First trim off any external storage prefix
        final UserEnvironment userEnv = new UserEnvironment(userId);

        // /storage/emulated/0
        final String externalPath = userEnv.getExternalStorageDirectory().getAbsolutePath();
        // /storage/emulated_legacy
        final String legacyExternalPath = Environment.getLegacyExternalStorageDirectory()
                .getAbsolutePath();

        if (path.startsWith(externalPath)) {
            path = path.substring(externalPath.length() + 1);
        } else if (path.startsWith(legacyExternalPath)) {
            path = path.substring(legacyExternalPath.length() + 1);
        } else {
            return canonicalPath;
        }

        // Handle special OBB paths on emulated storage
        final String obbPath = "Android/obb";
        if (path.startsWith(obbPath)) {
            path = path.substring(obbPath.length() + 1);

            if (forVold) {
                return new File(Environment.getEmulatedStorageObbSource(), path).getAbsolutePath();
            } else {
                final UserEnvironment ownerEnv = new UserEnvironment(UserHandle.USER_OWNER);
                return new File(ownerEnv.buildExternalStorageAndroidObbDirs()[0], path)
                        .getAbsolutePath();
            }
        }

        // Handle normal external storage paths
        if (forVold) {
            return new File(Environment.getEmulatedStorageSource(userId), path).getAbsolutePath();
        } else {
            return new File(userEnv.getExternalDirsForApp()[0], path).getAbsolutePath();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ActivityManager from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }

        synchronized (mObbMounts) {
            pw.println("  mObbMounts:");

            final Iterator<Entry<IBinder, List<ObbState>>> binders = mObbMounts.entrySet().iterator();
            while (binders.hasNext()) {
                Entry<IBinder, List<ObbState>> e = binders.next();
                pw.print("    Key="); pw.println(e.getKey().toString());
                final List<ObbState> obbStates = e.getValue();
                for (final ObbState obbState : obbStates) {
                    pw.print("      "); pw.println(obbState.toString());
                }
            }

            pw.println("");
            pw.println("  mObbPathToStateMap:");
            final Iterator<Entry<String, ObbState>> maps = mObbPathToStateMap.entrySet().iterator();
            while (maps.hasNext()) {
                final Entry<String, ObbState> e = maps.next();
                pw.print("    "); pw.print(e.getKey());
                pw.print(" -> "); pw.println(e.getValue().toString());
            }
        }

        pw.println("");

        synchronized (mVolumesLock) {
            pw.println("  mVolumes:");

            final int N = mVolumes.size();
            for (int i = 0; i < N; i++) {
                final StorageVolume v = mVolumes.get(i);
                pw.print("    ");
                pw.println(v.toString());
                pw.println("      state=" + mVolumeStates.get(v.getPath()));
            }
        }

        pw.println();
        pw.println("  mConnection:");
        mConnector.dump(fd, pw, args);
    }

    /** {@inheritDoc} */
    public void monitor() {
        if (mConnector != null) {
            mConnector.monitor();
        }
    }
}
