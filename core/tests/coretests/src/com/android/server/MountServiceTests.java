/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.frameworks.coretests.R;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.os.FileUtils;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.test.AndroidTestCase;
import android.test.ComparisonFailure;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import java.io.File;
import java.io.InputStream;

public class MountServiceTests extends AndroidTestCase {
    private static final String TAG = "MountServiceTests";

    private static final long MAX_WAIT_TIME = 25*1000;
    private static final long WAIT_TIME_INCR = 5*1000;

    private static final String OBB_MOUNT_PREFIX = "/mnt/obb/";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    private static void assertStartsWith(String message, String prefix, String actual) {
        if (!actual.startsWith(prefix)) {
            throw new ComparisonFailure(message, prefix, actual);
        }
    }

    private static class ObbObserver extends OnObbStateChangeListener {
        private String path;

        public int state = -1;
        boolean done = false;

        @Override
        public void onObbStateChange(String path, int state) {
            Log.d(TAG, "Received message.  path=" + path + ", state=" + state);
            synchronized (this) {
                this.path = path;
                this.state = state;
                done = true;
                notifyAll();
            }
        }

        public String getPath() {
            assertTrue("Expected ObbObserver to have received a state change.", done);
            return path;
        }

        public int getState() {
            assertTrue("Expected ObbObserver to have received a state change.", done);
            return state;
        }

        public void reset() {
            this.path = null;
            this.state = -1;
            done = false;
        }

        public boolean isDone() {
            return done;
        }

        public boolean waitForCompletion() {
            long waitTime = 0;
            synchronized (this) {
                while (!isDone() && waitTime < MAX_WAIT_TIME) {
                    try {
                        wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    } catch (InterruptedException e) {
                        Log.i(TAG, "Interrupted during sleep", e);
                    }
                }
            }

            return isDone();
        }
    }

    private File getFilePath(String name) {
        final File filesDir = mContext.getFilesDir();
        final File outFile = new File(filesDir, name);
        return outFile;
    }

    private void copyRawToFile(int rawResId, File outFile) {
        Resources res = mContext.getResources();
        InputStream is = null;
        try {
            is = res.openRawResource(rawResId);
        } catch (NotFoundException e) {
            fail("Failed to load resource with id: " + rawResId);
        }
        FileUtils.setPermissions(outFile.getPath(), FileUtils.S_IRWXU | FileUtils.S_IRWXG
                | FileUtils.S_IRWXO, -1, -1);
        assertTrue(FileUtils.copyToFile(is, outFile));
        FileUtils.setPermissions(outFile.getPath(), FileUtils.S_IRWXU | FileUtils.S_IRWXG
                | FileUtils.S_IRWXO, -1, -1);
    }

    private StorageManager getStorageManager() {
        return (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
    }

    private void mountObb(StorageManager sm, final int resource, final File file,
            int expectedState) {
        copyRawToFile(resource, file);

        final ObbObserver observer = new ObbObserver();
        assertTrue("mountObb call on " + file.getPath() + " should succeed",
                sm.mountObb(file.getPath(), null, observer));

        assertTrue("Mount should have completed",
                observer.waitForCompletion());

        if (expectedState == OnObbStateChangeListener.MOUNTED) {
            assertTrue("OBB should be mounted", sm.isObbMounted(file.getPath()));
        }

        assertEquals("Actual file and resolved file should be the same",
                file.getPath(), observer.getPath());

        assertEquals(expectedState, observer.getState());
    }

    private ObbObserver mountObbWithoutWait(final StorageManager sm, final int resource,
            final File file) {
        copyRawToFile(resource, file);

        final ObbObserver observer = new ObbObserver();
        assertTrue("mountObb call on " + file.getPath() + " should succeed", sm.mountObb(file
                .getPath(), null, observer));

        return observer;
    }

    private void waitForObbActionCompletion(final StorageManager sm, final File file,
            final ObbObserver observer, int expectedState, boolean checkPath) {
        assertTrue("Mount should have completed", observer.waitForCompletion());

        assertTrue("OBB should be mounted", sm.isObbMounted(file.getPath()));

        if (checkPath) {
            assertEquals("Actual file and resolved file should be the same", file.getPath(),
                    observer.getPath());
        }

        assertEquals(expectedState, observer.getState());
    }

    private String checkMountedPath(final StorageManager sm, final File file) {
        final String mountPath = sm.getMountedObbPath(file.getPath());
        assertStartsWith("Path should be in " + OBB_MOUNT_PREFIX,
                OBB_MOUNT_PREFIX,
                mountPath);
        return mountPath;
    }

    private void unmountObb(final StorageManager sm, final File file, int expectedState) {
        final ObbObserver observer = new ObbObserver();

        assertTrue("unmountObb call on test1.obb should succeed",
 sm.unmountObb(file.getPath(),
                false, observer));

        assertTrue("Unmount should have completed",
                observer.waitForCompletion());

        assertEquals(expectedState, observer.getState());

        if (expectedState == OnObbStateChangeListener.UNMOUNTED) {
            assertFalse("OBB should not be mounted", sm.isObbMounted(file.getPath()));
        }
    }

    @LargeTest
    public void testMountAndUnmountObbNormal() {
        StorageManager sm = getStorageManager();

        final File outFile = getFilePath("test1.obb");

        mountObb(sm, R.raw.test1, outFile, OnObbStateChangeListener.MOUNTED);

        mountObb(sm, R.raw.test1, outFile, OnObbStateChangeListener.ERROR_ALREADY_MOUNTED);

        final String mountPath = checkMountedPath(sm, outFile);
        final File mountDir = new File(mountPath);

        assertTrue("OBB mounted path should be a directory",
                mountDir.isDirectory());

        unmountObb(sm, outFile, OnObbStateChangeListener.UNMOUNTED);
    }

    @LargeTest
    public void testAttemptMountNonObb() {
        StorageManager sm = getStorageManager();

        final File outFile = getFilePath("test1_nosig.obb");

        mountObb(sm, R.raw.test1_nosig, outFile, OnObbStateChangeListener.ERROR_INTERNAL);

        assertFalse("OBB should not be mounted",
                sm.isObbMounted(outFile.getPath()));

        assertNull("OBB's mounted path should be null",
                sm.getMountedObbPath(outFile.getPath()));
    }

    @LargeTest
    public void testAttemptMountObbWrongPackage() {
        StorageManager sm = getStorageManager();

        final File outFile = getFilePath("test1_wrongpackage.obb");

        mountObb(sm, R.raw.test1_wrongpackage, outFile,
                OnObbStateChangeListener.ERROR_PERMISSION_DENIED);

        assertFalse("OBB should not be mounted",
                sm.isObbMounted(outFile.getPath()));

        assertNull("OBB's mounted path should be null",
                sm.getMountedObbPath(outFile.getPath()));
    }

    @LargeTest
    public void testMountAndUnmountTwoObbs() {
        StorageManager sm = getStorageManager();

        final File file1 = getFilePath("test1.obb");
        final File file2 = getFilePath("test2.obb");

        ObbObserver oo1 = mountObbWithoutWait(sm, R.raw.test1, file1);
        ObbObserver oo2 = mountObbWithoutWait(sm, R.raw.test1, file2);

        Log.d(TAG, "Waiting for OBB #1 to complete mount");
        waitForObbActionCompletion(sm, file1, oo1, OnObbStateChangeListener.MOUNTED, false);
        Log.d(TAG, "Waiting for OBB #2 to complete mount");
        waitForObbActionCompletion(sm, file2, oo2, OnObbStateChangeListener.MOUNTED, false);

        final String mountPath1 = checkMountedPath(sm, file1);
        final File mountDir1 = new File(mountPath1);
        assertTrue("OBB mounted path should be a directory", mountDir1.isDirectory());

        final String mountPath2 = checkMountedPath(sm, file2);
        final File mountDir2 = new File(mountPath2);
        assertTrue("OBB mounted path should be a directory", mountDir2.isDirectory());

        unmountObb(sm, file1, OnObbStateChangeListener.UNMOUNTED);
        unmountObb(sm, file2, OnObbStateChangeListener.UNMOUNTED);
    }
}
