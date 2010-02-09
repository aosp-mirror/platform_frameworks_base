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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.storage.IMountService;
import android.os.storage.IMountServiceListener;
import android.os.storage.StorageResultCode;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Environment;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import java.util.ArrayList;

import java.io.File;
import java.io.FileReader;

/**
 * MountService implements back-end services for platform storage
 * management.
 * @hide - Applications should use android.os.storage.StorageManager
 * to access the MountService.
 */
class MountService extends IMountService.Stub
        implements INativeDaemonConnectorCallbacks {
    private static final boolean LOCAL_LOGD = false;
    
    private static final String TAG = "MountService";

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
        public static final int OpFailedVolBusy                = 405;

        /*
         * 600 series - Unsolicited broadcasts.
         */
        public static final int VolumeStateChange              = 605;
        public static final int ShareAvailabilityChange        = 620;
        public static final int VolumeDiskInserted             = 630;
        public static final int VolumeDiskRemoved              = 631;
        public static final int VolumeBadRemoval               = 632;
    }

    private Context                               mContext;
    private NativeDaemonConnector                 mConnector;
    private String                                mLegacyState = Environment.MEDIA_REMOVED;
    private PackageManagerService                 mPms;
    private boolean                               mUmsEnabling;
    private ArrayList<MountServiceBinderListener> mListeners;
    private boolean                               mBooted;
    private boolean                               mReady;

    private void waitForReady() {
        while (mReady == false) {
            for (int retries = 5; retries > 0; retries--) {
                if (mReady) {
                    return;
                }
                SystemClock.sleep(1000);
            }
            Log.w(TAG, "Waiting too long for mReady!");
        }
    }
  
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                mBooted = true;

                String path = Environment.getExternalStorageDirectory().getPath();
                if (getVolumeState(path).equals(Environment.MEDIA_UNMOUNTED)) {
                    int rc = doMountVolume(path);
                    if (rc != StorageResultCode.OperationSucceeded) {
                        Log.e(TAG, String.format("Boot-time mount failed (%d)", rc));
                    }
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
            if (LOCAL_LOGD) Log.d(TAG, "An IMountServiceListener has died!");
            synchronized(mListeners) {
                mListeners.remove(this);
                mListener.asBinder().unlinkToDeath(this, 0);
            }
        }
    }

    private int doShareUnshareVolume(String path, String method, boolean enable) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        // TODO: Add support for multiple share methods
        if (!method.equals("ums")) {
            throw new IllegalArgumentException(String.format("Method %s not supported", method));
        }

        /*
         * If the volume is mounted and we're enabling then unmount it
         */
        String vs = getVolumeState(path);
        if (enable && vs.equals(Environment.MEDIA_MOUNTED)) {
            mUmsEnabling = enable; // Override for isUsbMassStorageEnabled()
            int rc = doUnmountVolume(path);
            mUmsEnabling = false; // Clear override
            if (rc != StorageResultCode.OperationSucceeded) {
                Log.e(TAG, String.format("Failed to unmount before enabling UMS (%d)", rc));
                return rc;
            }
        }

        try {
            mConnector.doCommand(String.format(
                    "volume %sshare %s %s", (enable ? "" : "un"), path, method));
        } catch (NativeDaemonConnectorException e) {
            Log.e(TAG, "Failed to share/unshare", e);
            return StorageResultCode.OperationFailedInternalError;
        }

        /*
         * If we disabled UMS then mount the volume
         */
        if (!enable) {
            if (doMountVolume(path) != StorageResultCode.OperationSucceeded) {
                Log.e(TAG, String.format(
                        "Failed to remount %s after disabling share method %s", path, method));
                /*
                 * Even though the mount failed, the unshare didn't so don't indicate an error.
                 * The mountVolume() call will have set the storage state and sent the necessary
                 * broadcasts.
                 */
            }
        }

        return StorageResultCode.OperationSucceeded;
    }

    private void updatePublicVolumeState(String path, String state) {
        if (!path.equals(Environment.getExternalStorageDirectory().getPath())) {
            Log.w(TAG, "Multiple volumes not currently supported");
            return;
        }

        if (mLegacyState.equals(state)) {
            Log.w(TAG, String.format("Duplicate state transition (%s -> %s)", mLegacyState, state));
            return;
        }

        String oldState = mLegacyState;
        mLegacyState = state;

        synchronized (mListeners) {
            for (int i = mListeners.size() -1; i >= 0; i--) {
                MountServiceBinderListener bl = mListeners.get(i);
                try {
                    bl.mListener.onStorageStateChanged(path, oldState, state);
                } catch (RemoteException rex) {
                    Log.e(TAG, "Listener dead");
                    mListeners.remove(i);
                } catch (Exception ex) {
                    Log.e(TAG, "Listener failed", ex);
                }
            }
        }
    }

    /**
     *
     * Callback from NativeDaemonConnector
     */
    public void onDaemonConnected() {
        /*
         * Since we'll be calling back into the NativeDaemonConnector,
         * we need to do our work in a new thread.
         */
        new Thread() {
            public void run() {
                /**
                 * Determine media state and UMS detection status
                 */
                String path = Environment.getExternalStorageDirectory().getPath();
                String state = Environment.MEDIA_REMOVED;

                try {
                    String[] vols = mConnector.doListCommand(
                        "volume list", VoldResponseCode.VolumeListResult);
                    for (String volstr : vols) {
                        String[] tok = volstr.split(" ");
                        // FMT: <label> <mountpoint> <state>
                        if (!tok[1].equals(path)) {
                            Log.w(TAG, String.format(
                                    "Skipping unknown volume '%s'",tok[1]));
                            continue;
                        }
                        int st = Integer.parseInt(tok[2]);
                        if (st == VolumeState.NoMedia) {
                            state = Environment.MEDIA_REMOVED;
                        } else if (st == VolumeState.Idle) {
                            state = Environment.MEDIA_UNMOUNTED;
                        } else if (st == VolumeState.Mounted) {
                            state = Environment.MEDIA_MOUNTED;
                            Log.i(TAG, "Media already mounted on daemon connection");
                        } else if (st == VolumeState.Shared) {
                            state = Environment.MEDIA_SHARED;
                            Log.i(TAG, "Media shared on daemon connection");
                        } else {
                            throw new Exception(String.format("Unexpected state %d", st));
                        }
                    }
                    if (state != null) {
                        updatePublicVolumeState(path, state);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing initial volume state", e);
                    updatePublicVolumeState(path, Environment.MEDIA_REMOVED);
                }

                try {
                    boolean avail = doGetShareMethodAvailable("ums");
                    notifyShareAvailabilityChange("ums", avail);
                } catch (Exception ex) {
                    Log.w(TAG, "Failed to get share availability");
                }
                /*
                 * Now that we've done our initialization, release 
                 * the hounds!
                 */
                mReady = true;
            }
        }.start();
    }

    /**
     * Callback from NativeDaemonConnector
     */
    public boolean onEvent(int code, String raw, String[] cooked) {
        Intent in = null;

        if (code == VoldResponseCode.VolumeStateChange) {
            /*
             * One of the volumes we're managing has changed state.
             * Format: "NNN Volume <label> <path> state changed
             * from <old_#> (<old_str>) to <new_#> (<new_str>)"
             */
            notifyVolumeStateChange(
                    cooked[2], cooked[3], Integer.parseInt(cooked[7]),
                            Integer.parseInt(cooked[10]));
        } else if (code == VoldResponseCode.ShareAvailabilityChange) {
            // FMT: NNN Share method <method> now <available|unavailable>
            boolean avail = false;
            if (cooked[5].equals("available")) {
                avail = true;
            }
            notifyShareAvailabilityChange(cooked[3], avail);
        } else if ((code == VoldResponseCode.VolumeDiskInserted) ||
                   (code == VoldResponseCode.VolumeDiskRemoved) ||
                   (code == VoldResponseCode.VolumeBadRemoval)) {
            // FMT: NNN Volume <label> <mountpoint> disk inserted (<major>:<minor>)
            // FMT: NNN Volume <label> <mountpoint> disk removed (<major>:<minor>)
            // FMT: NNN Volume <label> <mountpoint> bad removal (<major>:<minor>)
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
                Log.e(TAG, "Failed to parse major/minor", ex);
            }

            if (code == VoldResponseCode.VolumeDiskInserted) {
                new Thread() {
                    public void run() {
                        try {
                            int rc;
                            if ((rc = doMountVolume(path)) != StorageResultCode.OperationSucceeded) {
                                Log.w(TAG, String.format("Insertion mount failed (%d)", rc));
                            }
                        } catch (Exception ex) {
                            Log.w(TAG, "Failed to mount media on insertion", ex);
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
                updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTED);
                in = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.parse("file://" + path));
                mContext.sendBroadcast(in);

                updatePublicVolumeState(path, Environment.MEDIA_REMOVED);
                in = new Intent(Intent.ACTION_MEDIA_REMOVED, Uri.parse("file://" + path));
            } else if (code == VoldResponseCode.VolumeBadRemoval) {
                /* Send the media unmounted event first */
                updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTED);
                in = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.parse("file://" + path));
                mContext.sendBroadcast(in);

                updatePublicVolumeState(path, Environment.MEDIA_BAD_REMOVAL);
                in = new Intent(Intent.ACTION_MEDIA_BAD_REMOVAL, Uri.parse("file://" + path));
            } else {
                Log.e(TAG, String.format("Unknown code {%d}", code));
            }
        } else {
            return false;
        }

        if (in != null) {
            mContext.sendBroadcast(in);
	}
       return true;
    }

    private void notifyVolumeStateChange(String label, String path, int oldState, int newState) {
        String vs = getVolumeState(path);

        Intent in = null;

        if (newState == VolumeState.Init) {
        } else if (newState == VolumeState.NoMedia) {
            // NoMedia is handled via Disk Remove events
        } else if (newState == VolumeState.Idle) {
            /*
             * Don't notify if we're in BAD_REMOVAL, NOFS, UNMOUNTABLE, or
             * if we're in the process of enabling UMS
             */
            if (!vs.equals(
                    Environment.MEDIA_BAD_REMOVAL) && !vs.equals(
                            Environment.MEDIA_NOFS) && !vs.equals(
                                    Environment.MEDIA_UNMOUNTABLE) && !mUmsEnabling) {
                updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTED);
                in = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.parse("file://" + path));
            }
        } else if (newState == VolumeState.Pending) {
        } else if (newState == VolumeState.Checking) {
            updatePublicVolumeState(path, Environment.MEDIA_CHECKING);
            in = new Intent(Intent.ACTION_MEDIA_CHECKING, Uri.parse("file://" + path));
        } else if (newState == VolumeState.Mounted) {
            updatePublicVolumeState(path, Environment.MEDIA_MOUNTED);
            // Update media status on PackageManagerService to mount packages on sdcard
            mPms.updateExternalMediaStatus(true);
            in = new Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.parse("file://" + path));
            in.putExtra("read-only", false);
        } else if (newState == VolumeState.Unmounting) {
            mPms.updateExternalMediaStatus(false);
            in = new Intent(Intent.ACTION_MEDIA_EJECT, Uri.parse("file://" + path));
        } else if (newState == VolumeState.Formatting) {
        } else if (newState == VolumeState.Shared) {
            /* Send the media unmounted event first */
            updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTED);
            in = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.parse("file://" + path));
            mContext.sendBroadcast(in);

            updatePublicVolumeState(path, Environment.MEDIA_SHARED);
            in = new Intent(Intent.ACTION_MEDIA_SHARED, Uri.parse("file://" + path));
        } else if (newState == VolumeState.SharedMnt) {
            Log.e(TAG, "Live shared mounts not supported yet!");
            return;
        } else {
            Log.e(TAG, "Unhandled VolumeState {" + newState + "}");
        }

        if (in != null) {
            mContext.sendBroadcast(in);
        }
    }

    private boolean doGetShareMethodAvailable(String method) {
        ArrayList<String> rsp = mConnector.doCommand("share status " + method);

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code;
            try {
                code = Integer.parseInt(tok[0]);
            } catch (NumberFormatException nfe) {
                Log.e(TAG, String.format("Error parsing code %s", tok[0]));
                return false;
            }
            if (code == VoldResponseCode.ShareStatusResult) {
                if (tok[2].equals("available"))
                    return true;
                return false;
            } else {
                Log.e(TAG, String.format("Unexpected response code %d", code));
                return false;
            }
        }
        Log.e(TAG, "Got an empty response");
        return false;
    }

    private int doMountVolume(String path) {
        int rc = StorageResultCode.OperationSucceeded;

        try {
            mConnector.doCommand(String.format("volume mount %s", path));
        } catch (NativeDaemonConnectorException e) {
            /*
             * Mount failed for some reason
             */
            Intent in = null;
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedNoMedia) {
                /*
                 * Attempt to mount but no media inserted
                 */
                rc = StorageResultCode.OperationFailedNoMedia;
            } else if (code == VoldResponseCode.OpFailedMediaBlank) {
                /*
                 * Media is blank or does not contain a supported filesystem
                 */
                updatePublicVolumeState(path, Environment.MEDIA_NOFS);
                in = new Intent(Intent.ACTION_MEDIA_NOFS, Uri.parse("file://" + path));
                rc = StorageResultCode.OperationFailedMediaBlank;
            } else if (code == VoldResponseCode.OpFailedMediaCorrupt) {
                /*
                 * Volume consistency check failed
                 */
                updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTABLE);
                in = new Intent(Intent.ACTION_MEDIA_UNMOUNTABLE, Uri.parse("file://" + path));
                rc = StorageResultCode.OperationFailedMediaCorrupt;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }

            /*
             * Send broadcast intent (if required for the failure)
             */
            if (in != null) {
                mContext.sendBroadcast(in);
            }
        }

        return rc;
    }

    private int doUnmountVolume(String path) {
        if (!getVolumeState(path).equals(Environment.MEDIA_MOUNTED)) {
            return VoldResponseCode.OpFailedVolNotMounted;
        }

        // Notify PackageManager of potential media removal and deal with
        // return code later on. The caller of this api should be aware or have been
        // notified that the applications installed on the media will be killed.
        mPms.updateExternalMediaStatus(false);
        try {
            mConnector.doCommand(String.format("volume unmount %s", path));
            return StorageResultCode.OperationSucceeded;
        } catch (NativeDaemonConnectorException e) {
            // Don't worry about mismatch in PackageManager since the
            // call back will handle the status changes any way.
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedVolNotMounted) {
                return StorageResultCode.OperationFailedVolumeNotMounted;
            } else {
                return StorageResultCode.OperationFailedInternalError;
            }
        }
    }

    private int doFormatVolume(String path) {
        try {
            String cmd = String.format("volume format %s", path);
            mConnector.doCommand(cmd);
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
        String cmd = String.format("volume shared %s %s", path, method);
        ArrayList<String> rsp = mConnector.doCommand(cmd);

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code;
            try {
                code = Integer.parseInt(tok[0]);
            } catch (NumberFormatException nfe) {
                Log.e(TAG, String.format("Error parsing code %s", tok[0]));
                return false;
            }
            if (code == VoldResponseCode.ShareEnabledResult) {
                if (tok[2].equals("enabled"))
                    return true;
                return false;
            } else {
                Log.e(TAG, String.format("Unexpected response code %d", code));
                return false;
            }
        }
        Log.e(TAG, "Got an empty response");
        return false;
    }

    private void notifyShareAvailabilityChange(String method, final boolean avail) {
        if (!method.equals("ums")) {
           Log.w(TAG, "Ignoring unsupported share method {" + method + "}");
           return;
        }

        synchronized (mListeners) {
            for (int i = mListeners.size() -1; i >= 0; i--) {
                MountServiceBinderListener bl = mListeners.get(i);
                try {
                    bl.mListener.onUsbMassStorageConnectionChanged(avail);
                } catch (RemoteException rex) {
                    Log.e(TAG, "Listener dead");
                    mListeners.remove(i);
                } catch (Exception ex) {
                    Log.e(TAG, "Listener failed", ex);
                }
            }
        }

        if (mBooted == true) {
            Intent intent;
            if (avail) {
                intent = new Intent(Intent.ACTION_UMS_CONNECTED);
            } else {
                intent = new Intent(Intent.ACTION_UMS_DISCONNECTED);
            }
            mContext.sendBroadcast(intent);
        }
    }

    private void validatePermission(String perm) {
        if (mContext.checkCallingOrSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(String.format("Requires %s permission", perm));
        }
    }

    /**
     * Constructs a new MountService instance
     *
     * @param context  Binder context for this service
     */
    public MountService(Context context) {
        mContext = context;

        /*
         * Vold does not run in the simulator, so fake out a mounted
         * event to trigger MediaScanner
         */
        if ("simulator".equals(SystemProperties.get("ro.product.device"))) {
            updatePublicVolumeState("/sdcard", Environment.MEDIA_MOUNTED);
            return;
        }

        // XXX: This will go away soon in favor of IMountServiceObserver
        mPms = (PackageManagerService) ServiceManager.getService("package");

        mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);

        mListeners = new ArrayList<MountServiceBinderListener>();

        mConnector = new NativeDaemonConnector(this, "vold", 10, "VoldConnector");
        mReady = false;
        Thread thread = new Thread(mConnector, NativeDaemonConnector.class.getName());
        thread.start();
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
                Log.e(TAG, "Failed to link to listener death");
            }
        }
    }

    public void unregisterListener(IMountServiceListener listener) {
        synchronized (mListeners) {
            for(MountServiceBinderListener bl : mListeners) {
                if (bl.mListener == listener) {
                    mListeners.remove(mListeners.indexOf(bl));
                    return;
                }
            }
        }
    }

    public void shutdown() {
        validatePermission(android.Manifest.permission.SHUTDOWN);

        Log.i(TAG, "Shutting down");

        String path = Environment.getExternalStorageDirectory().getPath();
        String state = getVolumeState(path);

        if (state.equals(Environment.MEDIA_SHARED)) {
            /*
             * If the media is currently shared, unshare it.
             * XXX: This is still dangerous!. We should not
             * be rebooting at *all* if UMS is enabled, since
             * the UMS host could have dirty FAT cache entries
             * yet to flush.
             */
            if (setUsbMassStorageEnabled(false) != StorageResultCode.OperationSucceeded) {
                Log.e(TAG, "UMS disable on shutdown failed");
            }
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
                    Log.e(TAG, "Interrupted while waiting for media", iex);
                    break;
                }
                state = Environment.getExternalStorageState();
            }
            if (retries == 0) {
                Log.e(TAG, "Timed out waiting for media to check");
            }
        }

        if (state.equals(Environment.MEDIA_MOUNTED)) {
            /*
             * If the media is mounted, then gracefully unmount it.
             */
            if (doUnmountVolume(path) != StorageResultCode.OperationSucceeded) {
                Log.e(TAG, "Failed to unmount media for shutdown");
            }
        }
    }

    public boolean isUsbMassStorageConnected() {
        waitForReady();

        if (mUmsEnabling) {
            return true;
        }
        return doGetShareMethodAvailable("ums");
    }

    public int setUsbMassStorageEnabled(boolean enable) {
        waitForReady();

        return doShareUnshareVolume(Environment.getExternalStorageDirectory().getPath(), "ums", enable);
    }

    public boolean isUsbMassStorageEnabled() {
        waitForReady();
        return doGetVolumeShared(Environment.getExternalStorageDirectory().getPath(), "ums");
    }
    
    /**
     * @return state of the volume at the specified mount point
     */
    public String getVolumeState(String mountPoint) {
        /*
         * XXX: Until we have multiple volume discovery, just hardwire
         * this to /sdcard
         */
        if (!mountPoint.equals(Environment.getExternalStorageDirectory().getPath())) {
            Log.w(TAG, "getVolumeState(" + mountPoint + "): Unknown volume");
            throw new IllegalArgumentException();
        }

        return mLegacyState;
    }

    public int mountVolume(String path) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        waitForReady();
        return doMountVolume(path);
    }

    public int unmountVolume(String path) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        return doUnmountVolume(path);
    }

    public int formatVolume(String path) {
        validatePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        return doFormatVolume(path);
    }

    private void warnOnNotMounted() {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Log.w(TAG, "getSecureContainerList() called when storage not mounted");
        }
    }

    public String[] getSecureContainerList() {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        try {
            return mConnector.doListCommand("asec list", VoldResponseCode.AsecListResult);
        } catch (NativeDaemonConnectorException e) {
            return new String[0];
        }
    }

    public int createSecureContainer(String id, int sizeMb, String fstype,
                                    String key, int ownerUid) {
        validatePermission(android.Manifest.permission.ASEC_CREATE);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        String cmd = String.format("asec create %s %d %s %s %d", id, sizeMb, fstype, key, ownerUid);
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int finalizeSecureContainer(String id) {
        validatePermission(android.Manifest.permission.ASEC_CREATE);
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.doCommand(String.format("asec finalize %s", id));
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int destroySecureContainer(String id) {
        validatePermission(android.Manifest.permission.ASEC_DESTROY);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.doCommand(String.format("asec destroy %s", id));
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }
   
    public int mountSecureContainer(String id, String key, int ownerUid) {
        validatePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        String cmd = String.format("asec mount %s %s %d", id, key, ownerUid);
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int unmountSecureContainer(String id) {
        validatePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        String cmd = String.format("asec unmount %s", id);
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int renameSecureContainer(String oldId, String newId) {
        validatePermission(android.Manifest.permission.ASEC_RENAME);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        String cmd = String.format("asec rename %s %s", oldId, newId);
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public String getSecureContainerPath(String id) {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        ArrayList<String> rsp = mConnector.doCommand("asec path " + id);

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == VoldResponseCode.AsecPathResult) {
                return tok[1];
            } else {
                Log.e(TAG, String.format("Unexpected response code %d", code));
                return "";
            }
        }

        Log.e(TAG, "Got an empty response");
        return "";
    }
}

