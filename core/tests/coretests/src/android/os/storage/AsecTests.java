/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os.storage;

import android.content.Context;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.test.AndroidTestCase;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

public class AsecTests extends AndroidTestCase {
    private static final String SECURE_CONTAINER_PREFIX = "com.android.unittests.AsecTests.";
    private static final boolean localLOGV = true;
    public static final String TAG="AsecTests";

    private static final String FS_FAT = "fat";
    private static final String FS_EXT4 = "ext4";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (localLOGV) Log.i(TAG, "Cleaning out old test containers");
        cleanupContainers();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (localLOGV) Log.i(TAG, "Cleaning out old test containers");
        cleanupContainers();
    }

    private void cleanupContainers() throws RemoteException {
        IStorageManager sm = getSm();
        String[] containers = sm.getSecureContainerList();

        for (int i = 0; i < containers.length; i++) {
            if (containers[i].startsWith(SECURE_CONTAINER_PREFIX)) {
                if (localLOGV)
                    Log.i(TAG, "Cleaning: " + containers[i]);
                sm.destroySecureContainer(containers[i], true);
            }
        }
    }

    private boolean containerExists(String localId) throws RemoteException {
        IStorageManager sm = getSm();
        String[] containers = sm.getSecureContainerList();
        String fullId = SECURE_CONTAINER_PREFIX + localId;

        for (int i = 0; i < containers.length; i++) {
            if (containers[i].equals(fullId)) {
                return true;
            }
        }
        return false;
    }

    private int createContainer(String localId, int size, String key, String filesystem,
            boolean isExternal) throws Exception {
        assertTrue("Media should be mounted", isMediaMounted());
        String fullId = SECURE_CONTAINER_PREFIX + localId;

        IStorageManager sm = getSm();
        return sm.createSecureContainer(fullId, size, filesystem, key, android.os.Process.myUid(),
                isExternal);
    }

    private int mountContainer(String localId, String key) throws Exception {
        assertTrue("Media should be mounted", isMediaMounted());
        String fullId = SECURE_CONTAINER_PREFIX + localId;

        IStorageManager sm = getSm();
        return sm.mountSecureContainer(fullId, key, android.os.Process.myUid(), true);
    }

    private int renameContainer(String localId1, String localId2) throws Exception {
        assertTrue("Media should be mounted", isMediaMounted());
        String fullId1 = SECURE_CONTAINER_PREFIX + localId1;
        String fullId2 = SECURE_CONTAINER_PREFIX + localId2;

        IStorageManager sm = getSm();
        return sm.renameSecureContainer(fullId1, fullId2);
    }

    private int unmountContainer(String localId, boolean force) throws Exception {
        assertTrue("Media should be mounted", isMediaMounted());
        String fullId = SECURE_CONTAINER_PREFIX + localId;

        IStorageManager sm = getSm();
        return sm.unmountSecureContainer(fullId, force);
    }

    private int destroyContainer(String localId, boolean force) throws Exception {
        assertTrue("Media should be mounted", isMediaMounted());
        String fullId = SECURE_CONTAINER_PREFIX + localId;

        IStorageManager sm = getSm();
        return sm.destroySecureContainer(fullId, force);
    }

    private boolean isContainerMounted(String localId) throws Exception {
        assertTrue("Media should be mounted", isMediaMounted());
        String fullId = SECURE_CONTAINER_PREFIX + localId;

        IStorageManager sm = getSm();
        return sm.isSecureContainerMounted(fullId);
    }

    private IStorageManager getSm() {
        IBinder service = ServiceManager.getService("mount");
        if (service != null) {
            return IStorageManager.Stub.asInterface(service);
        } else {
            Log.e(TAG, "Can't get storagemanager service");
        }
        return null;
    }

    private boolean isMediaMounted() throws Exception {
        String mPath = Environment.getExternalStorageDirectory().toString();
        String state = getSm().getVolumeState(mPath);
        return Environment.MEDIA_MOUNTED.equals(state);
    }


    /*
     * CREATE
     */

    public void test_Fat_External_Create_Success() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateContainer", 4, "none", FS_FAT, true));
        assertTrue(containerExists("testCreateContainer"));
    }

    public void test_Ext4_External_Create_Success() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateContainer", 4, "none", FS_EXT4, true));
        assertTrue(containerExists("testCreateContainer"));
    }

    public void test_Fat_Internal_Create_Success() throws Exception {
        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateContainer", 4, "none", FS_FAT, false));
        assertTrue(containerExists("testCreateContainer"));
    }

    public void test_Ext4_Internal_Create_Success() throws Exception {
        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateContainer", 4, "none", FS_EXT4, false));
        assertTrue(containerExists("testCreateContainer"));
    }


    /*
     * CREATE MIN SIZE
     */

    public void test_Fat_External_CreateMinSize_Success() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateContainer", 1, "none", FS_FAT, true));
        assertTrue(containerExists("testCreateContainer"));
    }

    public void test_Ext4_External_CreateMinSize_Success() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateContainer", 1, "none", FS_EXT4, true));
        assertTrue(containerExists("testCreateContainer"));
    }

    public void test_Fat_Internal_CreateMinSize_Success() throws Exception {
        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateContainer", 1, "none", FS_FAT, false));
        assertTrue(containerExists("testCreateContainer"));
    }

    public void test_Ext4_Internal_CreateMinSize_Success() throws Exception {
        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateContainer", 1, "none", FS_EXT4, false));
        assertTrue(containerExists("testCreateContainer"));
    }


    /*
     * CREATE ZERO SIZE - FAIL CASE
     */

    public void test_Fat_External_CreateZeroSize_Failure() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationFailedInternalError,
                createContainer("testCreateZeroContainer", 0, "none", FS_FAT, true));
    }

    public void test_Ext4_External_CreateZeroSize_Failure() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationFailedInternalError,
                createContainer("testCreateZeroContainer", 0, "none", FS_EXT4, true));
    }

    public void test_Fat_Internal_CreateZeroSize_Failure() throws Exception {
        assertEquals(StorageResultCode.OperationFailedInternalError,
                createContainer("testCreateZeroContainer", 0, "none", FS_FAT, false));
    }

    public void test_Ext4_Internal_CreateZeroSize_Failure() throws Exception {
        assertEquals(StorageResultCode.OperationFailedInternalError,
                createContainer("testCreateZeroContainer", 0, "none", FS_EXT4, false));
    }


    /*
     * CREATE DUPLICATE - FAIL CASE
     */

    public void test_Fat_External_CreateDuplicate_Failure() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateDupContainer", 4, "none", FS_FAT, true));

        assertEquals(StorageResultCode.OperationFailedInternalError,
                createContainer("testCreateDupContainer", 4, "none", FS_FAT, true));
    }

    public void test_Ext4_External_CreateDuplicate_Failure() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateDupContainer", 4, "none", FS_EXT4, true));

        assertEquals(StorageResultCode.OperationFailedInternalError,
                createContainer("testCreateDupContainer", 4, "none", FS_EXT4, true));
    }

    public void test_Fat_Internal_CreateDuplicate_Failure() throws Exception {
        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateDupContainer", 4, "none", FS_FAT, false));

        assertEquals(StorageResultCode.OperationFailedInternalError,
                createContainer("testCreateDupContainer", 4, "none", FS_FAT, false));
    }

    public void test_Ext4_Internal_CreateDuplicate_Failure() throws Exception {
        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testCreateDupContainer", 4, "none", FS_EXT4, false));

        assertEquals(StorageResultCode.OperationFailedInternalError,
                createContainer("testCreateDupContainer", 4, "none", FS_EXT4, false));
    }


    /*
     * DESTROY
     */

    public void test_Fat_External_Destroy_Success() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testDestroyContainer", 4, "none", FS_FAT, true));
        assertEquals(StorageResultCode.OperationSucceeded,
                destroyContainer("testDestroyContainer", false));
    }

    public void test_Ext4_External_Destroy_Success() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testDestroyContainer", 4, "none", FS_EXT4, true));
        assertEquals(StorageResultCode.OperationSucceeded,
                destroyContainer("testDestroyContainer", false));
    }

    public void test_Fat_Internal_Destroy_Success() throws Exception {
        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testDestroyContainer", 4, "none", FS_FAT, false));
        assertEquals(StorageResultCode.OperationSucceeded,
                destroyContainer("testDestroyContainer", false));
    }

    public void test_Ext4_Internal_Destroy_Success() throws Exception {
        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testDestroyContainer", 4, "none", FS_EXT4, false));
        assertEquals(StorageResultCode.OperationSucceeded,
                destroyContainer("testDestroyContainer", false));
    }


    /*
     * MOUNT
     */

    public void test_Fat_External_Mount() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testMountContainer", 4, "none", FS_FAT, true));

        assertEquals(StorageResultCode.OperationSucceeded,
                unmountContainer("testMountContainer", false));

        assertEquals(StorageResultCode.OperationSucceeded,
                mountContainer("testMountContainer", "none"));
    }


    /*
     * MOUNT BAD KEY - FAIL CASE
     */

    public void test_Fat_External_MountBadKey_Failure() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testMountBadKey", 4, "00000000000000000000000000000000", FS_FAT,
                        true));

        assertEquals(StorageResultCode.OperationSucceeded,
                unmountContainer("testMountBadKey", false));

        assertEquals(StorageResultCode.OperationFailedInternalError,
                mountContainer("testMountContainer", "000000000000000000000000000000001"));

        assertEquals(StorageResultCode.OperationFailedInternalError,
                mountContainer("testMountContainer", "none"));
    }


    public void test_Fat_External_UnmountBusy_Success() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        IStorageManager sm = getSm();
        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testUnmountBusyContainer", 4, "none", FS_FAT, true));

        String path = sm.getSecureContainerPath(SECURE_CONTAINER_PREFIX
                + "testUnmountBusyContainer");

        File f = new File(path, "reference");
        FileOutputStream fos = new FileOutputStream(f);

        assertEquals(StorageResultCode.OperationFailedStorageBusy,
                unmountContainer("testUnmountBusyContainer", false));

        fos.close();
        assertEquals(StorageResultCode.OperationSucceeded,
                unmountContainer("testUnmountBusyContainer", false));
    }

    public void test_Fat_External_DestroyBusy() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        IStorageManager sm = getSm();

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testDestroyBusyContainer", 4, "none", FS_FAT, true));

        String path = sm.getSecureContainerPath(SECURE_CONTAINER_PREFIX
                + "testDestroyBusyContainer");

        File f = new File(path, "reference");
        FileOutputStream fos = new FileOutputStream(f);

        assertEquals(StorageResultCode.OperationFailedStorageBusy,
                destroyContainer("testDestroyBusyContainer", false));

        fos.close();
        assertEquals(StorageResultCode.OperationSucceeded,
                destroyContainer("testDestroyBusyContainer", false));
    }

    public void test_Fat_External_Rename_Success() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testRenameContainer.1", 4, "none", FS_FAT, true));

        assertEquals(StorageResultCode.OperationSucceeded,
                unmountContainer("testRenameContainer.1", false));

        assertEquals(StorageResultCode.OperationSucceeded,
                renameContainer("testRenameContainer.1", "testRenameContainer.2"));

        assertFalse(containerExists("testRenameContainer.1"));
        assertTrue(containerExists("testRenameContainer.2"));
    }

    public void test_Fat_External_RenameSrcMounted_Failure() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testRenameContainer.1", 4, "none", FS_FAT, true));

        assertEquals(StorageResultCode.OperationFailedStorageMounted,
                renameContainer("testRenameContainer.1", "testRenameContainer.2"));
    }

    public void test_Fat_External_RenameDstMounted_Failure() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testRenameContainer.1", 4, "none", FS_FAT, true));

        assertEquals(StorageResultCode.OperationSucceeded,
                unmountContainer("testRenameContainer.1", false));

        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testRenameContainer.2", 4, "none", FS_FAT, true));

        assertEquals(StorageResultCode.OperationFailedStorageMounted,
                renameContainer("testRenameContainer.1", "testRenameContainer.2"));
    }

    public void test_Fat_External_Size_Success() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        IStorageManager sm = getSm();
        assertEquals(StorageResultCode.OperationSucceeded,
                createContainer("testContainerSize", 1, "none", FS_FAT, true));
        String path = sm.getSecureContainerPath(SECURE_CONTAINER_PREFIX + "testContainerSize");

        byte[] buf = new byte[4096];
        File f = new File(path, "reference");
        FileOutputStream fos = new FileOutputStream(f);
        for (int i = 0; i < (1024 * 1024); i += buf.length) {
            fos.write(buf);
        }
        fos.close();
    }

    public void testGetSecureContainerPath_NonExistPath_Failure() throws Exception {
        IStorageManager sm = getSm();
        assertNull("Getting the path for an invalid container should return null",
                sm.getSecureContainerPath("jparks.broke.it"));
    }

    /*------------ Tests for unmounting volume ---*/
    public final long MAX_WAIT_TIME=120*1000;
    public final long WAIT_TIME_INCR=20*1000;

    boolean getMediaState() throws Exception {
        String mPath = Environment.getExternalStorageDirectory().toString();
        String state = getSm().getVolumeState(mPath);
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    boolean mountMedia() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return true;
        }

        if (getMediaState()) {
            return true;
        }

        String mPath = Environment.getExternalStorageDirectory().toString();
        int ret = getSm().mountVolume(mPath);
        return ret == StorageResultCode.OperationSucceeded;
    }

    class StorageListener extends StorageEventListener {
        String oldState;
        String newState;
        String path;
        private boolean doneFlag = false;

        public void action() {
            synchronized (this) {
                doneFlag = true;
                notifyAll();
            }
        }

        public boolean isDone() {
            return doneFlag;
        }

        @Override
        public void onStorageStateChanged(String path, String oldState, String newState) {
            if (localLOGV) Log.i(TAG, "Storage state changed from " + oldState + " to " + newState);
            this.oldState = oldState;
            this.newState = newState;
            this.path = path;
            action();
        }
    }

    private void unmountMedia() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        if (!getMediaState()) {
            return;
        }

        String path = Environment.getExternalStorageDirectory().toString();
        StorageListener observer = new StorageListener();
        StorageManager sm = (StorageManager) mContext.getSystemService(Context.STORAGE_SERVICE);
        sm.registerListener(observer);
        try {
            // Wait on observer
            synchronized(observer) {
                getSm().unmountVolume(path, false, false);
                long waitTime = 0;
                while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    observer.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if(!observer.isDone()) {
                    fail("Timed out waiting for packageInstalled callback");
                }
            }
        } finally {
            sm.unregisterListener(observer);
        }
    }

    public void testUnmount() throws Exception {
        boolean oldStatus = getMediaState();
        Log.i(TAG, "oldStatus="+oldStatus);
        try {
            // Mount media firsts
            if (!getMediaState()) {
                mountMedia();
            }
            unmountMedia();
        } finally {
            // Restore old status
            boolean currStatus = getMediaState();
            if (oldStatus != currStatus) {
                if (oldStatus) {
                    // Mount media
                    mountMedia();
                } else {
                    unmountMedia();
                }
            }
        }
    }

    class MultipleStorageLis extends StorageListener {
        int count = 0;
        public void onStorageStateChanged(String path, String oldState, String newState) {
            count++;
            super.action();
        }
    }
    /*
     * This test invokes unmount multiple time and expects the call back
     * to be invoked just once.
     */
    public void testUnmountMultiple() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        boolean oldStatus = getMediaState();
        StorageManager sm = (StorageManager) mContext.getSystemService(Context.STORAGE_SERVICE);
        MultipleStorageLis observer = new MultipleStorageLis();
        try {
            // Mount media firsts
            if (!getMediaState()) {
                mountMedia();
            }
            String path = Environment.getExternalStorageDirectory().toString();
            sm.registerListener(observer);
            // Wait on observer
            synchronized(observer) {
                for (int i = 0; i < 5; i++) {
                    getSm().unmountVolume(path, false, false);
                }
                long waitTime = 0;
                while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    observer.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if(!observer.isDone()) {
                    fail("Timed out waiting for packageInstalled callback");
                }
            }
            assertEquals(observer.count, 1);
        } finally {
            sm.unregisterListener(observer);
            // Restore old status
            boolean currStatus = getMediaState();
            if (oldStatus != currStatus) {
                if (oldStatus) {
                    // Mount media
                    mountMedia();
                } else {
                    unmountMedia();
                }
            }
        }
    }

    class ShutdownObserver extends  IStorageShutdownObserver.Stub{
        private boolean doneFlag = false;
        int statusCode;

        public void action() {
            synchronized (this) {
                doneFlag = true;
                notifyAll();
            }
        }

        public boolean isDone() {
            return doneFlag;
        }
        public void onShutDownComplete(int statusCode) throws RemoteException {
            this.statusCode = statusCode;
            action();
        }
        
    }

    void invokeShutdown() throws Exception {
        IStorageManager sm = getSm();
        ShutdownObserver observer = new ShutdownObserver();
        synchronized (observer) {
            sm.shutdown(observer);
        }
    }

    public void testShutdown() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        boolean oldStatus = getMediaState();
        try {
            // Mount media firsts
            if (!getMediaState()) {
                mountMedia();
            }
            invokeShutdown();
        } finally {
            // Restore old status
            boolean currStatus = getMediaState();
            if (oldStatus != currStatus) {
                if (oldStatus) {
                    // Mount media
                    mountMedia();
                } else {
                    unmountMedia();
                }
            }
        }
    }

    /*
     * This test invokes unmount multiple time and expects the call back
     * to be invoked just once.
     */
    public void testShutdownMultiple() throws Exception {
        if (Environment.isExternalStorageEmulated()) {
            return;
        }

        boolean oldStatus = getMediaState();
        try {
            // Mount media firsts
            if (!getMediaState()) {
                mountMedia();
            }
            IStorageManager sm = getSm();
            ShutdownObserver observer = new ShutdownObserver();
            synchronized (observer) {
                sm.shutdown(observer);
                for (int i = 0; i < 4; i++) {
                    sm.shutdown(null);
                }
            }
        } finally {
            // Restore old status
            boolean currStatus = getMediaState();
            if (oldStatus != currStatus) {
                if (oldStatus) {
                    // Mount media
                    mountMedia();
                } else {
                    unmountMedia();
                }
            }
        }
    }

}
