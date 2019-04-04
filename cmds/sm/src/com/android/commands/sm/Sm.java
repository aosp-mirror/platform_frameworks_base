/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.commands.sm;

import android.os.IVoldTaskListener;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.storage.DiskInfo;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import java.util.concurrent.CompletableFuture;

public final class Sm {
    private static final String TAG = "Sm";

    IStorageManager mSm;

    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    public static void main(String[] args) {
        boolean success = false;
        try {
            new Sm().run(args);
            success = true;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                showUsage();
                System.exit(1);
            }
            Log.e(TAG, "Error", e);
            System.err.println("Error: " + e);
        }
        System.exit(success ? 0 : 1);
    }

    public void run(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException();
        }

        mSm = IStorageManager.Stub.asInterface(ServiceManager.getService("mount"));
        if (mSm == null) {
            throw new RemoteException("Failed to find running mount service");
        }

        mArgs = args;
        String op = args[0];
        mNextArg = 1;

        if ("list-disks".equals(op)) {
            runListDisks();
        } else if ("list-volumes".equals(op)) {
            runListVolumes();
        } else if ("has-adoptable".equals(op)) {
            runHasAdoptable();
        } else if ("get-primary-storage-uuid".equals(op)) {
            runGetPrimaryStorageUuid();
        } else if ("set-force-adoptable".equals(op)) {
            runSetForceAdoptable();
        } else if ("set-sdcardfs".equals(op)) {
            runSetSdcardfs();
        } else if ("partition".equals(op)) {
            runPartition();
        } else if ("mount".equals(op)) {
            runMount();
        } else if ("unmount".equals(op)) {
            runUnmount();
        } else if ("format".equals(op)) {
            runFormat();
        } else if ("benchmark".equals(op)) {
            runBenchmark();
        } else if ("forget".equals(op)) {
            runForget();
        } else if ("set-emulate-fbe".equals(op)) {
            runSetEmulateFbe();
        } else if ("get-fbe-mode".equals(op)) {
            runGetFbeMode();
        } else if ("idle-maint".equals(op)) {
            runIdleMaint();
        } else if ("fstrim".equals(op)) {
            runFstrim();
        } else if ("set-virtual-disk".equals(op)) {
            runSetVirtualDisk();
        } else if ("set-isolated-storage".equals(op)) {
            runIsolatedStorage();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public void runListDisks() throws RemoteException {
        final boolean onlyAdoptable = "adoptable".equals(nextArg());
        final DiskInfo[] disks = mSm.getDisks();
        for (DiskInfo disk : disks) {
            if (!onlyAdoptable || disk.isAdoptable()) {
                System.out.println(disk.getId());
            }
        }
    }

    public void runListVolumes() throws RemoteException {
        final String filter = nextArg();
        final int filterType;
        if ("public".equals(filter)) {
            filterType = VolumeInfo.TYPE_PUBLIC;
        } else if ("private".equals(filter)) {
            filterType = VolumeInfo.TYPE_PRIVATE;
        } else if ("emulated".equals(filter)) {
            filterType = VolumeInfo.TYPE_EMULATED;
        } else if ("stub".equals(filter)) {
            filterType = VolumeInfo.TYPE_STUB;
        } else {
            filterType = -1;
        }

        final VolumeInfo[] vols = mSm.getVolumes(0);
        for (VolumeInfo vol : vols) {
            if (filterType == -1 || filterType == vol.getType()) {
                final String envState = VolumeInfo.getEnvironmentForState(vol.getState());
                System.out.println(vol.getId() + " " + envState + " " + vol.getFsUuid());
            }
        }
    }

    public void runHasAdoptable() {
        System.out.println(StorageManager.hasAdoptable());
    }

    public void runGetPrimaryStorageUuid() throws RemoteException {
        System.out.println(mSm.getPrimaryStorageUuid());
    }

    public void runSetForceAdoptable() throws RemoteException {
        final int mask = StorageManager.DEBUG_ADOPTABLE_FORCE_ON
                | StorageManager.DEBUG_ADOPTABLE_FORCE_OFF;
        switch (nextArg()) {
            case "on":
            case "true":
                mSm.setDebugFlags(StorageManager.DEBUG_ADOPTABLE_FORCE_ON, mask);
                break;
            case "off":
                mSm.setDebugFlags(StorageManager.DEBUG_ADOPTABLE_FORCE_OFF, mask);
                break;
            case "default":
            case "false":
                mSm.setDebugFlags(0, mask);
                break;
        }
    }

    public void runSetSdcardfs() throws RemoteException {
        final int mask = StorageManager.DEBUG_SDCARDFS_FORCE_ON
                | StorageManager.DEBUG_SDCARDFS_FORCE_OFF;
        switch (nextArg()) {
            case "on":
                mSm.setDebugFlags(StorageManager.DEBUG_SDCARDFS_FORCE_ON, mask);
                break;
            case "off":
                mSm.setDebugFlags(StorageManager.DEBUG_SDCARDFS_FORCE_OFF, mask);
                break;
            case "default":
                mSm.setDebugFlags(0, mask);
                break;
        }
    }

    public void runSetEmulateFbe() throws RemoteException {
        final boolean emulateFbe = Boolean.parseBoolean(nextArg());
        mSm.setDebugFlags(emulateFbe ? StorageManager.DEBUG_EMULATE_FBE : 0,
                StorageManager.DEBUG_EMULATE_FBE);
    }

    public void runGetFbeMode() {
        if (StorageManager.isFileEncryptedNativeOnly()) {
            System.out.println("native");
        } else if (StorageManager.isFileEncryptedEmulatedOnly()) {
            System.out.println("emulated");
        } else {
            System.out.println("none");
        }
    }

    public void runPartition() throws RemoteException {
        final String diskId = nextArg();
        final String type = nextArg();
        if ("public".equals(type)) {
            mSm.partitionPublic(diskId);
        } else if ("private".equals(type)) {
            mSm.partitionPrivate(diskId);
        } else if ("mixed".equals(type)) {
            final int ratio = Integer.parseInt(nextArg());
            mSm.partitionMixed(diskId, ratio);
        } else {
            throw new IllegalArgumentException("Unsupported partition type " + type);
        }
    }

    public void runMount() throws RemoteException {
        final String volId = nextArg();
        mSm.mount(volId);
    }

    public void runUnmount() throws RemoteException {
        final String volId = nextArg();
        mSm.unmount(volId);
    }

    public void runFormat() throws RemoteException {
        final String volId = nextArg();
        mSm.format(volId);
    }

    public void runBenchmark() throws Exception {
        final String volId = nextArg();
        final CompletableFuture<PersistableBundle> result = new CompletableFuture<>();
        mSm.benchmark(volId, new IVoldTaskListener.Stub() {
            @Override
            public void onStatus(int status, PersistableBundle extras) {
                // Ignored
            }

            @Override
            public void onFinished(int status, PersistableBundle extras) {
                // Touch to unparcel
                extras.size();
                result.complete(extras);
            }
        });
        System.out.println(result.get());
    }

    public void runForget() throws RemoteException {
        final String fsUuid = nextArg();
        if ("all".equals(fsUuid)) {
            mSm.forgetAllVolumes();
        } else {
            mSm.forgetVolume(fsUuid);
        }
    }

    public void runFstrim() throws Exception {
        final CompletableFuture<PersistableBundle> result = new CompletableFuture<>();
        mSm.fstrim(0, new IVoldTaskListener.Stub() {
            @Override
            public void onStatus(int status, PersistableBundle extras) {
                // Ignored
            }

            @Override
            public void onFinished(int status, PersistableBundle extras) {
                // Touch to unparcel
                extras.size();
                result.complete(extras);
            }
        });
        System.out.println(result.get());
    }

    public void runSetVirtualDisk() throws RemoteException {
        final boolean virtualDisk = Boolean.parseBoolean(nextArg());
        mSm.setDebugFlags(virtualDisk ? StorageManager.DEBUG_VIRTUAL_DISK : 0,
                StorageManager.DEBUG_VIRTUAL_DISK);
    }

    public void runIsolatedStorage() throws RemoteException {
        final int value;
        final int mask = StorageManager.DEBUG_ISOLATED_STORAGE_FORCE_ON
                | StorageManager.DEBUG_ISOLATED_STORAGE_FORCE_OFF;
        switch (nextArg()) {
            case "on":
            case "true":
                value = StorageManager.DEBUG_ISOLATED_STORAGE_FORCE_ON;
                break;
            case "off":
                value = StorageManager.DEBUG_ISOLATED_STORAGE_FORCE_OFF;
                break;
            case "default":
            case "false":
                value = 0;
                break;
            default:
                return;
        }
        mSm.setDebugFlags(value, mask);
    }

    public void runIdleMaint() throws RemoteException {
        final boolean im_run = "run".equals(nextArg());
        if (im_run) {
            mSm.runIdleMaintenance();
        } else {
            mSm.abortIdleMaintenance();
        }
    }

    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }

    private static int showUsage() {
        System.err.println("usage: sm list-disks [adoptable]");
        System.err.println("       sm list-volumes [public|private|emulated|stub|all]");
        System.err.println("       sm has-adoptable");
        System.err.println("       sm get-primary-storage-uuid");
        System.err.println("       sm set-force-adoptable [on|off|default]");
        System.err.println("       sm set-virtual-disk [true|false]");
        System.err.println("");
        System.err.println("       sm partition DISK [public|private|mixed] [ratio]");
        System.err.println("       sm mount VOLUME");
        System.err.println("       sm unmount VOLUME");
        System.err.println("       sm format VOLUME");
        System.err.println("       sm benchmark VOLUME");
        System.err.println("       sm idle-maint [run|abort]");
        System.err.println("       sm fstrim");
        System.err.println("");
        System.err.println("       sm forget [UUID|all]");
        System.err.println("");
        System.err.println("       sm set-emulate-fbe [true|false]");
        System.err.println("");
        System.err.println("       sm set-isolated-storage [on|off|default]");
        System.err.println("");
        return 1;
    }
}
