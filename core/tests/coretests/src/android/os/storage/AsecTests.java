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

import junit.framework.Assert;

public class AsecTests extends AndroidTestCase {
    private static final boolean localLOGV = true;
    public static final String TAG="AsecTests";

    void failStr(String errMsg) {
        Log.w(TAG, "errMsg="+errMsg);
    }

    void failStr(Exception e) {
        Log.w(TAG, "e.getMessage="+e.getMessage());
        Log.w(TAG, "e="+e);
    }

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
        IMountService ms = getMs();
        String[] containers = ms.getSecureContainerList();

        for (int i = 0; i < containers.length; i++) {
            if (containers[i].startsWith("com.android.unittests.AsecTests.")) {
                ms.destroySecureContainer(containers[i], true);
            }
        }
    }

    private boolean containerExists(String localId) throws RemoteException {
        IMountService ms = getMs();
        String[] containers = ms.getSecureContainerList();
        String fullId = "com.android.unittests.AsecTests." + localId;

        for (int i = 0; i < containers.length; i++) {
            if (containers[i].equals(fullId)) {
                return true;
            }
        }
        return false;
    }

    private int createContainer(String localId, int size, String key) throws RemoteException {
        Assert.assertTrue(isMediaMounted());
        String fullId = "com.android.unittests.AsecTests." + localId;

        IMountService ms = getMs();
        return ms.createSecureContainer(fullId, size, "fat", key, android.os.Process.myUid());
    }

    private int mountContainer(String localId, String key) throws RemoteException {
        Assert.assertTrue(isMediaMounted());
        String fullId = "com.android.unittests.AsecTests." + localId;

        IMountService ms = getMs();
        return ms.mountSecureContainer(fullId, key, android.os.Process.myUid());
    }

    private int renameContainer(String localId1, String localId2) throws RemoteException {
        Assert.assertTrue(isMediaMounted());
        String fullId1 = "com.android.unittests.AsecTests." + localId1;
        String fullId2 = "com.android.unittests.AsecTests." + localId2;

        IMountService ms = getMs();
        return ms.renameSecureContainer(fullId1, fullId2);
    }

    private int unmountContainer(String localId, boolean force) throws RemoteException {
        Assert.assertTrue(isMediaMounted());
        String fullId = "com.android.unittests.AsecTests." + localId;

        IMountService ms = getMs();
        return ms.unmountSecureContainer(fullId, force);
    }

    private int destroyContainer(String localId, boolean force) throws RemoteException {
        Assert.assertTrue(isMediaMounted());
        String fullId = "com.android.unittests.AsecTests." + localId;

        IMountService ms = getMs();
        return ms.destroySecureContainer(fullId, force);
    }

    private boolean isContainerMounted(String localId) throws RemoteException {
        Assert.assertTrue(isMediaMounted());
        String fullId = "com.android.unittests.AsecTests." + localId;

        IMountService ms = getMs();
        return ms.isSecureContainerMounted(fullId);
    }

    private IMountService getMs() {
        IBinder service = ServiceManager.getService("mount");
        if (service != null) {
            return IMountService.Stub.asInterface(service);
        } else {
            Log.e(TAG, "Can't get mount service");
        }
        return null;
    }

    private boolean isMediaMounted() {
        try {
        String mPath = Environment.getExternalStorageDirectory().toString();
        String state = getMs().getVolumeState(mPath);
        return Environment.MEDIA_MOUNTED.equals(state);
        } catch (RemoteException e) {
            failStr(e);
            return false;
        }
    }

    public void testCreateContainer() {
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testCreateContainer", 4, "none"));
            Assert.assertEquals(true, containerExists("testCreateContainer"));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testCreateMinSizeContainer() {
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testCreateContainer", 1, "none"));
            Assert.assertEquals(true, containerExists("testCreateContainer"));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testCreateZeroSizeContainer() {
        try {
            Assert.assertEquals(StorageResultCode.OperationFailedInternalError,
                    createContainer("testCreateZeroContainer", 0, "none"));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testCreateDuplicateContainer() {
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testCreateDupContainer", 4, "none"));

            Assert.assertEquals(StorageResultCode.OperationFailedInternalError,
                    createContainer("testCreateDupContainer", 4, "none"));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testDestroyContainer() {
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testDestroyContainer", 4, "none"));
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    destroyContainer("testDestroyContainer", false));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testMountContainer() {
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testMountContainer", 4, "none"));

            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    unmountContainer("testMountContainer", false));

            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    mountContainer("testMountContainer", "none"));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testMountBadKey() {
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testMountBadKey", 4, "00000000000000000000000000000000"));

            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    unmountContainer("testMountBadKey", false));

            Assert.assertEquals(StorageResultCode.OperationFailedInternalError,
                    mountContainer("testMountContainer", "000000000000000000000000000000001"));

            Assert.assertEquals(StorageResultCode.OperationFailedInternalError,
                    mountContainer("testMountContainer", "none"));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testNonExistPath() {
        IMountService ms = getMs();
        try {
            String path = ms.getSecureContainerPath("jparks.broke.it");
            failStr(path);
        } catch (IllegalArgumentException e) {
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testUnmountBusyContainer() {
        IMountService ms = getMs();
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testUnmountBusyContainer", 4, "none"));

            String path = ms.getSecureContainerPath("com.android.unittests.AsecTests.testUnmountBusyContainer");

            File f = new File(path, "reference");
            FileOutputStream fos = new FileOutputStream(f);

            Assert.assertEquals(StorageResultCode.OperationFailedStorageBusy,
                    unmountContainer("testUnmountBusyContainer", false));

            fos.close();
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    unmountContainer("testUnmountBusyContainer", false));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testDestroyBusyContainer() {
        IMountService ms = getMs();
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testDestroyBusyContainer", 4, "none"));

            String path = ms.getSecureContainerPath("com.android.unittests.AsecTests.testDestroyBusyContainer");

            File f = new File(path, "reference");
            FileOutputStream fos = new FileOutputStream(f);

            Assert.assertEquals(StorageResultCode.OperationFailedStorageBusy,
                    destroyContainer("testDestroyBusyContainer", false));

            fos.close();
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    destroyContainer("testDestroyBusyContainer", false));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testRenameContainer() {
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testRenameContainer.1", 4, "none"));

            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    unmountContainer("testRenameContainer.1", false));

            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    renameContainer("testRenameContainer.1", "testRenameContainer.2"));

            Assert.assertEquals(false, containerExists("testRenameContainer.1"));
            Assert.assertEquals(true, containerExists("testRenameContainer.2"));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testRenameSrcMountedContainer() {
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testRenameContainer.1", 4, "none"));

            Assert.assertEquals(StorageResultCode.OperationFailedStorageMounted,
                    renameContainer("testRenameContainer.1", "testRenameContainer.2"));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testRenameDstMountedContainer() {
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testRenameContainer.1", 4, "none"));

            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    unmountContainer("testRenameContainer.1", false));

            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testRenameContainer.2", 4, "none"));

            Assert.assertEquals(StorageResultCode.OperationFailedStorageMounted,
                    renameContainer("testRenameContainer.1", "testRenameContainer.2"));
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testContainerSize() {
        IMountService ms = getMs();
        try {
            Assert.assertEquals(StorageResultCode.OperationSucceeded,
                    createContainer("testContainerSize", 1, "none"));
            String path = ms.getSecureContainerPath("com.android.unittests.AsecTests.testUnmountBusyContainer");

            byte[] buf = new byte[4096];
            File f = new File(path, "reference");
            FileOutputStream fos = new FileOutputStream(f);
            for (int i = 0; i < (1024 * 1024); i+= buf.length) {
                fos.write(buf);
            }
            fos.close();
        } catch (Exception e) {
            failStr(e);
        }
    }

    /*------------ Tests for unmounting volume ---*/
    public final long MAX_WAIT_TIME=120*1000;
    public final long WAIT_TIME_INCR=20*1000;
    boolean getMediaState() {
        try {
        String mPath = Environment.getExternalStorageDirectory().toString();
        String state = getMs().getVolumeState(mPath);
        return Environment.MEDIA_MOUNTED.equals(state);
        } catch (RemoteException e) {
            return false;
        }
    }

    boolean mountMedia() {
        if (getMediaState()) {
            return true;
        }
        try {
        String mPath = Environment.getExternalStorageDirectory().toString();
        int ret = getMs().mountVolume(mPath);
        return ret == StorageResultCode.OperationSucceeded;
        } catch (RemoteException e) {
            return false;
        }
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

    private boolean unmountMedia() {
        if (!getMediaState()) {
            return true;
        }
        String path = Environment.getExternalStorageDirectory().toString();
        StorageListener observer = new StorageListener();
        StorageManager sm = (StorageManager) mContext.getSystemService(Context.STORAGE_SERVICE);
        sm.registerListener(observer);
        try {
            // Wait on observer
            synchronized(observer) {
                getMs().unmountVolume(path, false, false);
                long waitTime = 0;
                while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    observer.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if(!observer.isDone()) {
                    throw new Exception("Timed out waiting for packageInstalled callback");
                }
                return true;
            }
        } catch (Exception e) {
            return false;
        } finally {
            sm.unregisterListener(observer);
        }
    }
    public void testUnmount() {
        boolean oldStatus = getMediaState();
        Log.i(TAG, "oldStatus="+oldStatus);
        try {
            // Mount media firsts
            if (!getMediaState()) {
                mountMedia();
            }
            assertTrue(unmountMedia());
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
    public void testUnmountMultiple() {
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
                    getMs().unmountVolume(path, false, false);
                }
                long waitTime = 0;
                while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    observer.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if(!observer.isDone()) {
                    failStr("Timed out waiting for packageInstalled callback");
                }
            }
            assertEquals(observer.count, 1);
        } catch (Exception e) {
            failStr(e);
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
    
    class ShutdownObserver extends  IMountShutdownObserver.Stub{
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

    boolean invokeShutdown() {
        IMountService ms = getMs();
        ShutdownObserver observer = new ShutdownObserver();
        synchronized (observer) {
            try {
                ms.shutdown(observer);
                return true;
            } catch (RemoteException e) {
                failStr(e);
            }
        }
        return false;
    }

    public void testShutdown() {
        boolean oldStatus = getMediaState();
        try {
            // Mount media firsts
            if (!getMediaState()) {
                mountMedia();
            }
            assertTrue(invokeShutdown());
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
    public void testShutdownMultiple() {
        boolean oldStatus = getMediaState();
        try {
            // Mount media firsts
            if (!getMediaState()) {
                mountMedia();
            }
            IMountService ms = getMs();
            ShutdownObserver observer = new ShutdownObserver();
            synchronized (observer) {
                try {
                    ms.shutdown(observer);
                    for (int i = 0; i < 4; i++) {
                        ms.shutdown(null);
                    }
                } catch (RemoteException e) {
                    failStr(e);
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
