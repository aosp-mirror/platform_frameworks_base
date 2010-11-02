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

package android.os.storage;

import android.content.Context;
import android.os.Environment;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.frameworks.coretests.R;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;

import junit.framework.AssertionFailedError;

public class StorageManagerIntegrationTest extends StorageManagerBaseTest {

    private static String LOG_TAG = "StorageManagerBaseTest.StorageManagerIntegrationTest";
    protected File mFile = null;

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mContext = getInstrumentation().getContext();
        mFile = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void tearDown() throws Exception {
        if (mFile != null) {
            mFile.delete();
            mFile = null;
        }
        super.tearDown();
    }

    /**
     * Tests mounting a single OBB file and verifies its contents.
     */
    @LargeTest
    public void testMountSingleObb() {
        mFile = createObbFile(OBB_FILE_1, R.raw.obb_file1);
        String filePath = mFile.getAbsolutePath();
        mountObb(filePath);
        verifyObb1Contents(filePath);
        unmountObb(filePath, DONT_FORCE);
    }

    /**
     * Tests mounting several OBB files and verifies its contents.
     */
    @LargeTest
    public void testMountMultipleObb() {
        File file1 = null;
        File file2 = null;
        File file3 = null;
        try {
            file1 = createObbFile(OBB_FILE_1, R.raw.obb_file1);
            String filePath1 = file1.getAbsolutePath();
            mountObb(filePath1);
            verifyObb1Contents(filePath1);

            file2 = createObbFile(OBB_FILE_2, R.raw.obb_file2);
            String filePath2 = file2.getAbsolutePath();
            mountObb(filePath2);
            verifyObb2Contents(filePath2);

            file3 = createObbFile(OBB_FILE_3, R.raw.obb_file3);
            String filePath3 = file3.getAbsolutePath();
            mountObb(filePath3);
            verifyObb3Contents(filePath3);

            unmountObb(filePath1, DONT_FORCE);
            unmountObb(filePath2, DONT_FORCE);
            unmountObb(filePath3, DONT_FORCE);
        } finally {
            if (file1 != null) {
                file1.delete();
            }
            if (file2 != null) {
                file2.delete();
            }
            if (file3 != null) {
                file3.delete();
            }
        }
    }

    /**
     * Tests mounting a single encrypted OBB file and verifies its contents.
     */
    @LargeTest
    public void testMountSingleEncryptedObb() {
        mFile = createObbFile(OBB_FILE_3_ENCRYPTED, R.raw.obb_enc_file100_orig3);
        String filePath = mFile.getAbsolutePath();
        mountObb(filePath, OBB_FILE_3_PASSWORD, OnObbStateChangeListener.MOUNTED);
        verifyObb3Contents(filePath);
        unmountObb(filePath, DONT_FORCE);
    }

    /**
     * Tests mounting a single encrypted OBB file using an invalid password.
     */
    @LargeTest
    public void testMountSingleEncryptedObbInvalidPassword() {
        mFile = createObbFile("bad password@$%#@^*(!&)", R.raw.obb_enc_file100_orig3);
        String filePath = mFile.getAbsolutePath();
        mountObb(filePath, OBB_FILE_3_PASSWORD, OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT);
        unmountObb(filePath, DONT_FORCE);
    }

    /**
     * Tests simultaneously mounting 2 encrypted OBBs with different keys and verifies contents.
     */
    @LargeTest
    public void testMountTwoEncryptedObb() {
        File file3 = null;
        File file1 = null;
        try {
            file3 = createObbFile(OBB_FILE_3_ENCRYPTED, R.raw.obb_enc_file100_orig3);
            String filePath3 = file3.getAbsolutePath();
            mountObb(filePath3, OBB_FILE_3_PASSWORD, OnObbStateChangeListener.MOUNTED);
            verifyObb3Contents(filePath3);

            file1 = createObbFile(OBB_FILE_1_ENCRYPTED, R.raw.obb_enc_file100_orig1);
            String filePath1 = file1.getAbsolutePath();
            mountObb(filePath1, OBB_FILE_1_PASSWORD, OnObbStateChangeListener.MOUNTED);
            verifyObb1Contents(filePath1);

            unmountObb(filePath3, DONT_FORCE);
            unmountObb(filePath1, DONT_FORCE);
        } finally {
            if (file3 != null) {
                file3.delete();
            }
            if (file1 != null) {
                file1.delete();
            }
        }
    }

    /**
     * Tests that we can not force unmount when a file is currently open on the OBB.
     */
    @LargeTest
    public void testUnmount_DontForce() {
        mFile = createObbFile(OBB_FILE_1, R.raw.obb_file1);
        String obbFilePath = mFile.getAbsolutePath();

        MountingObbThread mountingThread = new MountingObbThread(obbFilePath,
                OBB_FILE_1_CONTENTS_1);

        try {
            mountingThread.start();

            long waitTime = 0;
            while (!mountingThread.isFileOpenOnObb()) {
                synchronized (mountingThread) {
                    Log.i(LOG_TAG, "Waiting for file to be opened on OBB...");
                    mountingThread.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                    if (waitTime > MAX_WAIT_TIME) {
                        fail("Timed out waiting for file file to be opened on OBB!");
                    }
                }
            }

            unmountObb(obbFilePath, DONT_FORCE);

            // verify still mounted
            assertTrue("mounted path should not be null!", obbFilePath != null);
            assertTrue("mounted path should still be mounted!", mSm.isObbMounted(obbFilePath));

            // close the opened file
            mountingThread.doStop();

            // try unmounting again (should succeed this time)
            unmountObb(obbFilePath, DONT_FORCE);
            assertFalse("mounted path should no longer be mounted!",
                    mSm.isObbMounted(obbFilePath));
        } catch (InterruptedException e) {
            fail("Timed out waiting for file on OBB to be opened...");
        }
    }

    /**
     * Tests mounting a single OBB that isn't signed.
     */
    @LargeTest
    public void testMountUnsignedObb() {
        mFile = createObbFile(OBB_FILE_2_UNSIGNED, R.raw.obb_file2_nosign);
        String filePath = mFile.getAbsolutePath();
        mountObb(filePath, OBB_FILE_2_UNSIGNED, OnObbStateChangeListener.ERROR_INTERNAL);
    }

    /**
     * Tests mounting a single OBB that is signed with a different package.
     */
    @LargeTest
    public void testMountBadPackageNameObb() {
        mFile = createObbFile(OBB_FILE_3_BAD_PACKAGENAME, R.raw.obb_file3_bad_packagename);
        String filePath = mFile.getAbsolutePath();
        mountObb(filePath, OBB_FILE_3_BAD_PACKAGENAME,
                OnObbStateChangeListener.ERROR_PERMISSION_DENIED);
    }

    /**
     * Tests remounting a single OBB that has already been mounted.
     */
    @LargeTest
    public void testRemountObb() {
        mFile = createObbFile(OBB_FILE_1, R.raw.obb_file1);
        String filePath = mFile.getAbsolutePath();
        mountObb(filePath);
        verifyObb1Contents(filePath);
        mountObb(filePath, null, OnObbStateChangeListener.ERROR_ALREADY_MOUNTED);
        verifyObb1Contents(filePath);
        unmountObb(filePath, DONT_FORCE);
    }
}