/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.app.backup;

import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.support.test.filters.LargeTest;
import android.test.AndroidTestCase;
import android.test.InstrumentationTestCase;
import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.Exception;
import java.nio.ByteBuffer;

@LargeTest
public class BackupDataTest extends AndroidTestCase {
    private static final String KEY1 = "key1";
    private static final String KEY2 = "key2a";
    private static final String KEY3 = "key3bc";
    private static final String KEY4 = "key4dad";  // variable key lengths to test padding
    private static final String[] KEYS = {KEY1, KEY2, KEY3, KEY4};

    private static final String DATA1 = "abcdef";
    private static final String DATA2 = "abcdefg";
    private static final String DATA3 = "abcdefgh";
    private static final String DATA4 = "abcdeffhi"; //variable data lengths to test padding
    private static final String[] DATA = {DATA1, DATA2, DATA3, DATA4};
    private static final String TAG = "BackupDataTest";

    private File mFile;
    private ParcelFileDescriptor mDataFile;
    private File mDirectory;
    private Bundle mStatusBundle;
    private AssetManager mAssets;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mDirectory = new File(Environment.getExternalStorageDirectory(), "test_data");
        mDirectory.mkdirs();
        mAssets = mContext.getAssets();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (mDataFile != null) {
            mDataFile.close();
        }
    }

    public void testSingle() throws IOException {
        mFile = new File(mDirectory, "backup_mixed_sinlge.dat");
        openForWriting();
        BackupDataOutput bdo = new BackupDataOutput(mDataFile.getFileDescriptor());

        writeEntity(bdo, KEY1, DATA1.getBytes());

        mDataFile.close();
        openForReading();

        BackupDataInput bdi = new BackupDataInput(mDataFile.getFileDescriptor());
        int count = 0;
        while (bdi.readNextHeader()) {
            readAndVerifyEntity(bdi, KEY1, DATA1.getBytes());
            count++;
        }
        assertEquals("only one entity in this stream", 1, count);
    }

    public void testMultiple() throws IOException {
        mFile = new File(mDirectory, "backup_multiple_test.dat");
        openForWriting();
        BackupDataOutput bdo = new BackupDataOutput(mDataFile.getFileDescriptor());

        for(int i = 0; i < KEYS.length; i++) {
            writeEntity(bdo, KEYS[i], DATA[i].getBytes());
        }

        mDataFile.close();
        openForReading();

        BackupDataInput bdi = new BackupDataInput(mDataFile.getFileDescriptor());
        int count = 0;
        while (bdi.readNextHeader()) {
            readAndVerifyEntity(bdi, KEYS[count], DATA[count].getBytes());
            count++;
        }
        assertEquals("four entities in this stream", KEYS.length, count);
    }

    public void testDelete() throws IOException {
        mFile = new File(mDirectory, "backup_delete_test.dat");
        openForWriting();
        BackupDataOutput bdo = new BackupDataOutput(mDataFile.getFileDescriptor());

        for(int i = 0; i < KEYS.length; i++) {
            deleteEntity(bdo, KEYS[i]);
        }

        mDataFile.close();
        openForReading();

        BackupDataInput bdi = new BackupDataInput(mDataFile.getFileDescriptor());
        int count = 0;
        while (bdi.readNextHeader()) {
            readAndVerifyDeletedEntity(bdi, KEYS[count]);
            count++;
        }
        assertEquals("four deletes in this stream", KEYS.length, count);
    }

    public void testMixed() throws IOException {
        mFile = new File(mDirectory, "backup_mixed_test.dat");
        openForWriting();

        BackupDataOutput bdo = new BackupDataOutput(mDataFile.getFileDescriptor());

        int i = 0;
        deleteEntity(bdo, KEYS[i]); i++;
        writeEntity(bdo, KEYS[i], DATA[i].getBytes()); i++;
        writeEntity(bdo, KEYS[i], DATA[i].getBytes()); i++;
        deleteEntity(bdo, KEYS[i]); i++;

        mDataFile.close();
        openForReading();

        BackupDataInput bdi = new BackupDataInput(mDataFile.getFileDescriptor());
        int out = 0;
        assertTrue(bdi.readNextHeader());
        readAndVerifyDeletedEntity(bdi, KEYS[out]); out++;
        assertTrue(bdi.readNextHeader());
        readAndVerifyEntity(bdi, KEYS[out], DATA[out].getBytes()); out++;
        assertTrue(bdi.readNextHeader());
        readAndVerifyEntity(bdi, KEYS[out], DATA[out].getBytes()); out++;
        assertTrue(bdi.readNextHeader());
        readAndVerifyDeletedEntity(bdi, KEYS[out]); out++;
        assertFalse("four items in this stream",
                bdi.readNextHeader());
    }

    public void testReadMockData() throws IOException {
        copyAssetToFile("backup_mock.dat", "backup_read_mock_test.dat");

        openForReading();
        BackupDataInput bdi = new BackupDataInput(mDataFile.getFileDescriptor());
        BufferedReader truth = new BufferedReader(new InputStreamReader(
                mAssets.openFd("backup_mock.gld").createInputStream()));
        while( bdi.readNextHeader()) {
            String[] expected = truth.readLine().split(":");
            byte[] expectedBytes = null;
            if (expected.length > 1) {
                expectedBytes = Base64.decode(expected[1], Base64.DEFAULT);
            }
            String key = bdi.getKey();
            int dataSize = bdi.getDataSize();

            assertEquals("wrong key", expected[0], key);
            assertEquals("wrong length for key " + key,
                    (expectedBytes == null ? -1: expectedBytes.length), dataSize);
            if (dataSize != -1) {
                byte[] buffer = new byte[dataSize];
                bdi.readEntityData(buffer, 0, dataSize);
                assertEquals("wrong data for key " + key, expected[1],
                        Base64.encodeToString(buffer, 0, dataSize, Base64.NO_WRAP));
            }
        }
        assertNull("there are unused entries in the golden file", truth.readLine());
    }

    public void testReadRealData() throws IOException {
        copyAssetToFile("backup_real.dat", "backup_read_real_test.dat");

        openForReading();
        BackupDataInput bdi = new BackupDataInput(mDataFile.getFileDescriptor());
        BufferedReader truth = new BufferedReader(new InputStreamReader(
                mAssets.openFd("backup_real.gld").createInputStream()));

        while(bdi.readNextHeader()) {
            String[] expected = truth.readLine().split(":");
            byte[] expectedBytes = null;
            if (expected.length > 1) {
                expectedBytes = Base64.decode(expected[1], Base64.DEFAULT);
            }
            String key = bdi.getKey();
            int dataSize = bdi.getDataSize();

            assertEquals("wrong key", expected[0], key);
            assertEquals("wrong length for key " + key,
                    (expectedBytes == null ? -1: expectedBytes.length), dataSize);
            if (dataSize != -1) {
                byte[] buffer = new byte[dataSize];
                bdi.readEntityData(buffer, 0, dataSize);
                assertEquals("wrong data for key " + key, expected[1],
                        Base64.encodeToString(buffer, 0, dataSize, Base64.NO_WRAP));
            }
        }
        assertNull("there are unused entries in the golden file", truth.readLine());
    }

    private void copyAssetToFile(String source, String destination) throws IOException {
        mFile = new File(mDirectory, destination);
        openForWriting();
        FileInputStream fileInputStream = mAssets.openFd(source).createInputStream();
        FileOutputStream fileOutputStream = new FileOutputStream(mDataFile.getFileDescriptor());
        byte[] copybuffer = new byte[1024];
        int numBytes = fileInputStream.read(copybuffer);
        fileOutputStream.write(copybuffer, 0, numBytes);
        fileOutputStream.close();
    }

    private void openForWriting() throws FileNotFoundException {
        mDataFile = ParcelFileDescriptor.open(mFile,
                ParcelFileDescriptor.MODE_WRITE_ONLY |
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE);  // Make an empty file if necessary
    }

    private void openForReading() throws FileNotFoundException {
        mDataFile = ParcelFileDescriptor.open(mFile,
                ParcelFileDescriptor.MODE_READ_ONLY |
                        ParcelFileDescriptor.MODE_CREATE);  // Make an empty file if necessary
    }

    private void writeEntity(BackupDataOutput bdo, String key, byte[] data) throws IOException {
        int status = bdo.writeEntityHeader(key, data.length);
        // documentation says "number of bytes written" but that's not what we get:
        assertEquals(0, status);

        status = bdo.writeEntityData(data, data.length);
        // documentation says "number of bytes written" but that's not what we get:
        assertEquals(0, status);
    }

    private void deleteEntity(BackupDataOutput bdo, String key) throws IOException {
        int status = bdo.writeEntityHeader(key, -1);
        // documentation says "number of bytes written" but that's not what we get:
        assertEquals(0, status);
    }

    private void readAndVerifyEntity(BackupDataInput bdi, String expectedKey, byte[] expectedData)
            throws IOException {
        assertEquals("Key mismatch",
                expectedKey, bdi.getKey());
        assertEquals("data size mismatch",
                expectedData.length, bdi.getDataSize());
        byte[] data = new byte[bdi.getDataSize()];
        bdi.readEntityData(data, 0, bdi.getDataSize());
        assertEquals("payload size is wrong",
                expectedData.length, data.length);
        for (int i = 0; i < data.length; i++) {
            assertEquals("payload mismatch",
                    expectedData[i], data[i]);
        }
    }
    private void readAndVerifyDeletedEntity(BackupDataInput bdi, String expectedKey)
            throws IOException {
        assertEquals("Key mismatch",
                expectedKey, bdi.getKey());
        assertEquals("deletion mis-reported",
                -1, bdi.getDataSize());
    }
}
