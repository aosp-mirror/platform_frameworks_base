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
import android.os.Environment;
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

    private interface CompletableTask {
        public boolean isDone();
    }

    private static class ObbObserver extends OnObbStateChangeListener implements CompletableTask {
        public String path;
        public String state;
        boolean done = false;

        @Override
        public void onObbStateChange(String path, String state) {
            synchronized (this) {
                this.path = path;
                this.state = state;
                done = true;
                notifyAll();
            }
        }

        public void reset() {
            this.path = null;
            this.state = null;
            done = false;
        }

        public boolean isDone() {
            return done;
        }
    }

    private boolean waitForCompletion(CompletableTask task) {
        long waitTime = 0;
        synchronized (task) {
            while (!task.isDone() && waitTime < MAX_WAIT_TIME) {
                try {
                    task.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                } catch (InterruptedException e) {
                    Log.i(TAG, "Interrupted during sleep", e);
                }
            }
        }

        return task.isDone();
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
            String expectedState) {
        copyRawToFile(resource, file);

        ObbObserver observer = new ObbObserver();
        assertTrue("mountObb call on " + file.getPath() + " should succeed",
                sm.mountObb(file.getPath(), null, observer));

        assertTrue("Mount should have completed",
                waitForCompletion(observer));

        assertEquals("Actual file and resolved file should be the same",
                file.getPath(), observer.path);

        assertEquals(expectedState, observer.state);
    }

    private String checkMountedPath(StorageManager sm, File file) {
        final String mountPath = sm.getMountedObbPath(file.getPath());
        assertStartsWith("Path should be in " + OBB_MOUNT_PREFIX,
                OBB_MOUNT_PREFIX,
                mountPath);
        return mountPath;
    }

    private void unmountObb(StorageManager sm, final File outFile) {
        ObbObserver observer = new ObbObserver();
        assertTrue("unmountObb call on test1.obb should succeed",
                sm.unmountObb(outFile.getPath(), false, observer));

        assertTrue("Unmount should have completed",
                waitForCompletion(observer));
    }

    @LargeTest
    public void testMountAndUnmountObbNormal() {
        StorageManager sm = getStorageManager();

        final File outFile = getFilePath("test1.obb");

        mountObb(sm, R.raw.test1, outFile, Environment.MEDIA_MOUNTED);

        final String mountPath = checkMountedPath(sm, outFile);
        final File mountDir = new File(mountPath);

        assertTrue("OBB mounted path should be a directory",
                mountDir.isDirectory());

        unmountObb(sm, outFile);
    }

    @LargeTest
    public void testAttemptMountNonObb() {
        StorageManager sm = getStorageManager();

        final File outFile = getFilePath("test1_nosig.obb");

        mountObb(sm, R.raw.test1_nosig, outFile, Environment.MEDIA_BAD_REMOVAL);

        assertFalse("OBB should not be mounted",
                sm.isObbMounted(outFile.getPath()));

        assertNull("OBB's mounted path should be null",
                sm.getMountedObbPath(outFile.getPath()));
    }

    @LargeTest
    public void testAttemptMountObbWrongPackage() {
        StorageManager sm = getStorageManager();

        final File outFile = getFilePath("test1_wrongpackage.obb");

        mountObb(sm, R.raw.test1_wrongpackage, outFile, Environment.MEDIA_BAD_REMOVAL);

        assertFalse("OBB should not be mounted",
                sm.isObbMounted(outFile.getPath()));

        assertNull("OBB's mounted path should be null",
                sm.getMountedObbPath(outFile.getPath()));
    }
}
