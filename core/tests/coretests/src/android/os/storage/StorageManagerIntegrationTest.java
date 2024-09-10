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

import android.content.res.ObbInfo;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.ServiceManager;
import android.system.ErrnoException;

import androidx.test.filters.LargeTest;

import com.android.frameworks.coretests.R;

import java.io.File;
import java.util.concurrent.ThreadFactory;

public class StorageManagerIntegrationTest extends StorageManagerBaseTest {
    private static String LOG_TAG = "StorageManagerIntegrationTest";

    /**
     * Tests mounting a single OBB file and verifies its contents.
     */
    @LargeTest
    public void testMountSingleObb() throws Exception {
        final File file = createObbFile(OBB_FILE_1, R.raw.obb_file1);
        String filePath = file.getAbsolutePath();
        mountObb(filePath);
        verifyObb1Contents(filePath);
        unmountObb(filePath, DONT_FORCE);
    }

    /**
     * Tests mounting several OBB files and verifies its contents.
     */
    @LargeTest
    public void testMountMultipleObb() throws Exception {
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
     * Tests mounting a single OBB that isn't signed.
     */
    @LargeTest
    public void testMountUnsignedObb() throws Exception {
        final File file = createObbFile(OBB_FILE_2_UNSIGNED, R.raw.obb_file2_nosign);
        String filePath = file.getAbsolutePath();
        try {
            mountObb(filePath, OnObbStateChangeListener.ERROR_INTERNAL);
            fail("mountObb should've failed with an exception");
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    /**
     * Tests mounting a single OBB that is signed with a different package.
     */
    @LargeTest
    public void testMountBadPackageNameObb() throws Exception {
        final File file = createObbFile(OBB_FILE_3_BAD_PACKAGENAME, R.raw.obb_file3_bad_packagename);
        String filePath = file.getAbsolutePath();
        try {
            mountObb(filePath, OnObbStateChangeListener.ERROR_PERMISSION_DENIED);
            fail("mountObb should throw an exception as package name is incorrect");
        } catch (Exception ex) {
            assertEquals("Path " + filePath
                            + " does not contain package name " + mContext.getPackageName(),
                    ex.getMessage());
        }
    }

    /**
     * Tests remounting a single OBB that has already been mounted.
     */
    @LargeTest
    public void testRemountObb() throws Exception {
        final File file = createObbFile(OBB_FILE_1, R.raw.obb_file1);
        String filePath = file.getAbsolutePath();
        mountObb(filePath);
        verifyObb1Contents(filePath);
        mountObb(filePath, OnObbStateChangeListener.ERROR_ALREADY_MOUNTED);
        verifyObb1Contents(filePath);
        unmountObb(filePath, DONT_FORCE);
    }

    @LargeTest
    public void testOpenProxyFileDescriptor() throws Exception {
        final ProxyFileDescriptorCallback callback = new ProxyFileDescriptorCallback() {
            @Override
            public long onGetSize() throws ErrnoException {
                return 0;
            }

            @Override
            public void onRelease() {}
        };

        final MyThreadFactory factory = new MyThreadFactory();
        int firstMountId;
        try (final ParcelFileDescriptor fd = mSm.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, null, factory)) {
            assertNotSame(Thread.State.TERMINATED, factory.thread.getState());
            firstMountId = mSm.getProxyFileDescriptorMountPointId();
            assertNotSame(-1, firstMountId);
        }

        // After closing descriptor, the loop should terminate.
        factory.thread.join(3000);
        assertEquals(Thread.State.TERMINATED, factory.thread.getState());

        // StorageManager should mount another bridge on the next open request.
        try (final ParcelFileDescriptor fd = mSm.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_WRITE_ONLY, callback, null, factory)) {
            assertNotSame(Thread.State.TERMINATED, factory.thread.getState());
            assertNotSame(firstMountId, mSm.getProxyFileDescriptorMountPointId());
        }
    }

    @LargeTest
    public void testObbInfo_withValidObbInfo_success() throws Exception {
        final File file = createObbFile(OBB_FILE_1, R.raw.obb_file1);
        String filePath = file.getAbsolutePath();
        try {
            mountObb(filePath);
            unmountObb(filePath, DONT_FORCE);
        } catch (Exception ex) {
            fail("No exception expected, got " + ex.getMessage());
        }
    }

    @LargeTest
    public void testObbInfo_withInvalidObbInfo_exception() throws Exception {
        final File file = createObbFile(OBB_FILE_1, R.raw.obb_file1);
        String rawPath = file.getAbsolutePath();
        String canonicalPath = file.getCanonicalPath();

        ObbInfo obbInfo = ObbInfo.CREATOR.createFromParcel(Parcel.obtain());
        obbInfo.packageName = "com.android.obbcrash";
        obbInfo.version = 1;
        obbInfo.filename = canonicalPath;

        try {
            IStorageManager.Stub.asInterface(ServiceManager.getServiceOrThrow("mount")).mountObb(
                    rawPath, canonicalPath, new ObbActionListener(), 0, obbInfo);
            fail("mountObb should throw an exception as package name is incorrect");
        } catch (SecurityException ex) {
            assertEquals("Path " + canonicalPath
                            + " does not contain package name " + mContext.getPackageName(),
                    ex.getMessage());
        }
    }

    private static class ObbActionListener extends IObbActionListener.Stub {
        @SuppressWarnings("hiding")
        @Override
        public void onObbResult(String filename, int nonce, int status) {

        }
    }

    private static class MyThreadFactory implements ThreadFactory {
        Thread thread = null;

        @Override
        public Thread newThread(Runnable r) {
            thread = new Thread(r);
            return thread;
        }
    }
}
