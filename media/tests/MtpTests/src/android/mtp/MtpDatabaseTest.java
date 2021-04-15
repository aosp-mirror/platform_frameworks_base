/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.mtp;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.FileUtils;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.Preconditions;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Tests for MtpDatabase functionality.
 */
@RunWith(AndroidJUnit4.class)
public class MtpDatabaseTest {
    private static final String TAG = MtpDatabaseTest.class.getSimpleName();

    private final Context mContext = InstrumentationRegistry.getContext();

    private static final File mBaseDir = InstrumentationRegistry.getContext().getExternalCacheDir();
    private static final String MAIN_STORAGE_DIR = mBaseDir.getPath() + "/" + TAG + "/";
    private static final String TEST_DIRNAME = "/TestIs";

    private static final int MAIN_STORAGE_ID = 0x10001;
    private static final int SCND_STORAGE_ID = 0x20001;
    private static final String MAIN_STORAGE_ID_STR = Integer.toHexString(MAIN_STORAGE_ID);
    private static final String SCND_STORAGE_ID_STR = Integer.toHexString(SCND_STORAGE_ID);

    private static final File mMainStorageDir = new File(MAIN_STORAGE_DIR);

    private static ServerHolder mServerHolder;
    private MtpDatabase mMtpDatabase;

    private static void logMethodName() {
        Log.d(TAG, Thread.currentThread().getStackTrace()[3].getMethodName());
    }

    private static File createNewDir(File parent, String name) {
        File ret = new File(parent, name);
        if (!ret.mkdir())
            throw new AssertionError(
                    "Failed to create file: name=" + name + ", " + parent.getPath());
        return ret;
    }

    private static void writeNewFile(File newFile) {
        try {
            new FileOutputStream(newFile).write(new byte[] {0, 0, 0});
        } catch (IOException e) {
            Assert.fail();
        }
    }

    private static void writeNewFileFromByte(File newFile, byte[] byteData) {
        try {
            new FileOutputStream(newFile).write(byteData);
        } catch (IOException e) {
            Assert.fail();
        }
    }

    private static class ServerHolder {
        @NonNull final MtpServer server;
        @NonNull final MtpDatabase database;

        ServerHolder(@NonNull MtpServer server, @NonNull MtpDatabase database) {
            Preconditions.checkNotNull(server);
            Preconditions.checkNotNull(database);
            this.server = server;
            this.database = database;
        }

        void close() {
            this.database.setServer(null);
        }
    }

    private class OnServerTerminated implements Runnable {
        @Override
        public void run() {
            if (mServerHolder == null) {
                Log.e(TAG, "mServerHolder is unexpectedly null.");
                return;
            }
            mServerHolder.close();
            mServerHolder = null;
        }
    }

    @Before
    public void setUp() {
        FileUtils.deleteContentsAndDir(mMainStorageDir);
        Assert.assertTrue(mMainStorageDir.mkdir());

        StorageVolume mainStorage = new StorageVolume(MAIN_STORAGE_ID_STR,
                mMainStorageDir, mMainStorageDir, "Primary Storage",
				true, false, true, false, -1, UserHandle.CURRENT, "", "");

        final StorageVolume primary = mainStorage;

        mMtpDatabase = new MtpDatabase(mContext, null);

        final MtpServer server =
                new MtpServer(mMtpDatabase, null, false,
                        new OnServerTerminated(), Build.MANUFACTURER,
                        Build.MODEL, "1.0");
        mMtpDatabase.setServer(server);
        mServerHolder = new ServerHolder(server, mMtpDatabase);

        mMtpDatabase.addStorage(mainStorage);
    }

    @After
    public void tearDown() {
        FileUtils.deleteContentsAndDir(mMainStorageDir);
    }

    private File stageFile(int resId, File file) throws IOException {
        try (InputStream source = mContext.getResources().openRawResource(resId);
                OutputStream target = new FileOutputStream(file)) {
            android.os.FileUtils.copy(source, target);
        }
        return file;
    }

    /**
     * Refer to BitmapUtilTests, but keep here,
	 * so as to be aware of the behavior or interface change there
     */
    private void assertBitmapSize(int expectedWidth, int expectedHeight, Bitmap bitmap) {
        Assert.assertTrue(
                "Abnormal bitmap.width: " + bitmap.getWidth(), bitmap.getWidth() >= expectedWidth);
        Assert.assertTrue(
                "Abnormal bitmap.height: " + bitmap.getHeight(),
                bitmap.getHeight() >= expectedHeight);
    }

    private byte[] createJpegRawData(int sourceWidth, int sourceHeight) throws IOException {
        return createRawData(Bitmap.CompressFormat.JPEG, sourceWidth, sourceHeight);
    }

    private byte[] createPngRawData(int sourceWidth, int sourceHeight) throws IOException {
        return createRawData(Bitmap.CompressFormat.PNG, sourceWidth, sourceHeight);
    }

    private byte[] createRawData(Bitmap.CompressFormat format, int sourceWidth, int sourceHeight)
            throws IOException {
        // Create a temp bitmap as our source
        Bitmap b = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        b.compress(format, 50, outputStream);
        final byte[] data = outputStream.toByteArray();
        outputStream.close();
        return data;
    }

    /**
     * Decodes the bitmap with the given sample size
     */
    public static Bitmap decodeBitmapFromBytes(byte[] bytes, int sampleSize) {
        final BitmapFactory.Options options;
        if (sampleSize <= 1) {
            options = null;
        } else {
            options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void testThumbnail(int fileHandle, File imgFile, boolean isGoodThumb)
            throws IOException {
        boolean isValidThumb;
        byte[] byteArray;
        long[] outLongs = new long[3];

        isValidThumb = mMtpDatabase.getThumbnailInfo(fileHandle, outLongs);
        Assert.assertTrue(isValidThumb);

        byteArray = mMtpDatabase.getThumbnailData(fileHandle);

        if (isGoodThumb) {
            Assert.assertNotNull("Fail to generate thumbnail:" + imgFile.getPath(), byteArray);

            Bitmap testBitmap = decodeBitmapFromBytes(byteArray, 4);
            assertBitmapSize(32, 16, testBitmap);
        } else Assert.assertNull("Bad image should return null:" + imgFile.getPath(), byteArray);
    }

    @Test
    @SmallTest
    public void testMtpDatabaseThumbnail() throws IOException {
        int baseHandle;
        int handleJpgBadThumb, handleJpgNoThumb, handleJpgBad;
        int handlePng1, handlePngBad;
        final String baseTestDirStr = mMainStorageDir.getPath() + TEST_DIRNAME;

        logMethodName();

        Log.d(TAG, "testMtpDatabaseThumbnail: Generate and insert tested files.");

        baseHandle = mMtpDatabase.beginSendObject(baseTestDirStr,
                MtpConstants.FORMAT_ASSOCIATION, 0, MAIN_STORAGE_ID);

        File baseDir = new File(baseTestDirStr);
        baseDir.mkdirs();

        final File jpgfileBadThumb = new File(baseDir, "jpgfileBadThumb.jpg");
        final File jpgFileNoThumb = new File(baseDir, "jpgFileNoThumb.jpg");
        final File jpgfileBad = new File(baseDir, "jpgfileBad.jpg");
        final File pngFile1 = new File(baseDir, "pngFile1.png");
        final File pngFileBad = new File(baseDir, "pngFileBad.png");

        handleJpgBadThumb = mMtpDatabase.beginSendObject(jpgfileBadThumb.getPath(),
                MtpConstants.FORMAT_EXIF_JPEG, baseHandle, MAIN_STORAGE_ID);
        stageFile(R.raw.test_bad_thumb, jpgfileBadThumb);

        handleJpgNoThumb = mMtpDatabase.beginSendObject(jpgFileNoThumb.getPath(),
                MtpConstants.FORMAT_EXIF_JPEG, baseHandle, MAIN_STORAGE_ID);
        writeNewFileFromByte(jpgFileNoThumb, createJpegRawData(128, 64));

        handleJpgBad = mMtpDatabase.beginSendObject(jpgfileBad.getPath(),
                MtpConstants.FORMAT_EXIF_JPEG, baseHandle, MAIN_STORAGE_ID);
        writeNewFile(jpgfileBad);

        handlePng1 = mMtpDatabase.beginSendObject(pngFile1.getPath(),
                MtpConstants.FORMAT_PNG, baseHandle, MAIN_STORAGE_ID);
        writeNewFileFromByte(pngFile1, createPngRawData(128, 64));

        handlePngBad = mMtpDatabase.beginSendObject(pngFileBad.getPath(),
                MtpConstants.FORMAT_PNG, baseHandle, MAIN_STORAGE_ID);
        writeNewFile(pngFileBad);

        Log.d(TAG, "testMtpDatabaseThumbnail: Test bad JPG");

// Now we support to generate thumbnail if embedded thumbnail is corrupted or not existed
        testThumbnail(handleJpgBadThumb, jpgfileBadThumb, true);

        testThumbnail(handleJpgNoThumb, jpgFileNoThumb, true);

        testThumbnail(handleJpgBad, jpgfileBad, false);

        Log.d(TAG, "testMtpDatabaseThumbnail: Test PNG");

        testThumbnail(handlePng1, pngFile1, true);

        Log.d(TAG, "testMtpDatabaseThumbnail: Test bad PNG");

        testThumbnail(handlePngBad, pngFileBad, false);
    }

    @Test
    @SmallTest
    public void testMtpDatabaseExtStorage() throws IOException {
        int numObj;
        StorageVolume[] mVolumes;

        logMethodName();

        mVolumes = StorageManager.getVolumeList(UserHandle.myUserId(), 0);
        // Currently it may need manual setup for 2nd storage on virtual device testing.
        // Thus only run test when 2nd storage exists.
        Assume.assumeTrue(
                "Skip when 2nd storage not available, volume numbers = " + mVolumes.length,
                mVolumes.length >= 2);

        for (int ii = 0; ii < mVolumes.length; ii++) {
            StorageVolume volume = mVolumes[ii];
            // Skip Actual Main storage (Internal Storage),
            // since we use manipulated path as testing Main storage
            if (ii > 0)
                mMtpDatabase.addStorage(volume);
        }

        numObj = mMtpDatabase.getNumObjects(SCND_STORAGE_ID, 0, 0xFFFFFFFF);
        Assert.assertTrue(
                "Fail to get objects in 2nd storage, object numbers = " + numObj, numObj >= 0);
    }
}
