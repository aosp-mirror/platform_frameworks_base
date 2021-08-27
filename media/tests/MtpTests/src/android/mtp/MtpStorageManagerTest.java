/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.os.FileUtils;
import android.os.UserHandle;
import android.os.storage.StorageVolume;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tests for MtpStorageManager functionality.
 */
@RunWith(JUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class MtpStorageManagerTest {
    private static final String TAG = MtpStorageManagerTest.class.getSimpleName();

    private static final String TEMP_DIR = InstrumentationRegistry.getContext().getFilesDir()
            + "/" + TAG + "/";
    private static final File TEMP_DIR_FILE = new File(TEMP_DIR);

    private MtpStorageManager manager;

    private ArrayList<Integer> objectsAdded;
    private ArrayList<Integer> objectsRemoved;
    private ArrayList<Integer> objectsInfoChanged;

    private File mainStorageDir;
    private File secondaryStorageDir;

    private MtpStorage mainMtpStorage;
    private MtpStorage secondaryMtpStorage;

    static {
        MtpStorageManager.sDebug = true;
    }

    private static void logMethodName() {
        Log.d(TAG, Thread.currentThread().getStackTrace()[3].getMethodName());
    }

    private static void logMethodValue(String szVar, int iValue)
    {
        Log.d(TAG, szVar + "=" + iValue + ": " + Thread.currentThread().getStackTrace()[3].getMethodName());
    }

    private static void vWriteNewFile(File newFile) {
        try {
            new FileOutputStream(newFile).write(new byte[] {0, 0, 0});
        } catch (IOException e) {
            Assert.fail();
        }
    }

    private static File createNewFile(File parent) {
        return createNewFile(parent, "file-" + UUID.randomUUID().toString());
    }

    private static File createNewFileNonZero(File parent) {
        return createNewFileNonZero(parent, "file-" + UUID.randomUUID().toString());
    }

    private static File createNewFile(File parent, String name) {
        return createNewFile(parent, name, false);
    }

    private static File createNewFileNonZero(File parent, String name) {
        return createNewFile(parent, name, true);
    }

    private static File createNewFile(File parent, String name, boolean fgNonZero) {
        try {
            File ret = new File(parent, name);
            if (!ret.createNewFile())
                throw new AssertionError("Failed to create file");
            if (fgNonZero)
                vWriteNewFile(ret);     // create non-zero size file
            return ret;
        } catch (IOException e) {
            throw new AssertionError(e.getMessage());
        }
    }

    private static File createNewDir(File parent, String name) {
        File ret = new File(parent, name);
        if (!ret.mkdir())
            throw new AssertionError("Failed to create file");
        return ret;
    }

    private static File createNewDir(File parent) {
        return createNewDir(parent, "dir-" + UUID.randomUUID().toString());
    }

    @Before
    public void before() {
        FileUtils.deleteContentsAndDir(TEMP_DIR_FILE);
        Assert.assertTrue(TEMP_DIR_FILE.mkdir());
        mainStorageDir = createNewDir(TEMP_DIR_FILE);
        secondaryStorageDir = createNewDir(TEMP_DIR_FILE);

        StorageVolume mainStorage = new StorageVolume("1", mainStorageDir, mainStorageDir,
                "", true, false, true, false, -1, UserHandle.CURRENT, null /* uuid */, "", "");
        StorageVolume secondaryStorage = new StorageVolume("2", secondaryStorageDir,
                secondaryStorageDir, "", false, false, true, false, -1, UserHandle.CURRENT,
                null /* uuid */, "", "");

        objectsAdded = new ArrayList<>();
        objectsRemoved = new ArrayList<>();
        objectsInfoChanged = new ArrayList<>();

        manager = new MtpStorageManager(new MtpStorageManager.MtpNotifier() {
            @Override
            public void sendObjectAdded(int id) {
                Log.d(TAG, "sendObjectAdded " + id);
                objectsAdded.add(id);
            }

            @Override
            public void sendObjectRemoved(int id) {
                Log.d(TAG, "sendObjectRemoved " + id);
                objectsRemoved.add(id);
            }

            @Override
            public void sendObjectInfoChanged(int id) {
                Log.d(TAG, "sendObjectInfoChanged: " + id);
                objectsInfoChanged.add(id);
            }
        }, null);

        mainMtpStorage = manager.addMtpStorage(mainStorage);
        secondaryMtpStorage = manager.addMtpStorage(secondaryStorage);
    }

    @After
    public void after() {
        manager.close();
        FileUtils.deleteContentsAndDir(TEMP_DIR_FILE);
    }

    /** MtpObject getter tests. **/

    @Test
    @SmallTest
    public void testMtpObjectGetNameRoot() {
        logMethodName();
        MtpStorageManager.MtpObject obj = manager.getStorageRoot(mainMtpStorage.getStorageId());
        Assert.assertEquals(obj.getName(), mainStorageDir.getPath());
    }

    @Test
    @SmallTest
    public void testMtpObjectGetNameNonRoot() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getName(), newFile.getName());
    }

    @Test
    @SmallTest
    public void testMtpObjectGetIdRoot() {
        logMethodName();
        MtpStorageManager.MtpObject obj = manager.getStorageRoot(mainMtpStorage.getStorageId());
        Assert.assertEquals(obj.getId(), mainMtpStorage.getStorageId());
    }

    @Test
    @SmallTest
    public void testMtpObjectGetIdNonRoot() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getId(), 1);
    }

    @Test
    @SmallTest
    public void testMtpObjectIsDirTrue() {
        logMethodName();
        MtpStorageManager.MtpObject obj = manager.getStorageRoot(mainMtpStorage.getStorageId());
        Assert.assertTrue(obj.isDir());
    }

    @Test
    @SmallTest
    public void testMtpObjectIsDirFalse() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir, "TEST123.mp3");
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertFalse(stream.get(0).isDir());
    }

    @Test
    @SmallTest
    public void testMtpObjectGetFormatDir() {
        logMethodName();
        File newFile = createNewDir(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getFormat(), MtpConstants.FORMAT_ASSOCIATION);
    }

    @Test
    @SmallTest
    public void testMtpObjectGetFormatNonDir() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir, "TEST123.mp3");
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getFormat(), MtpConstants.FORMAT_MP3);
    }

    @Test
    @SmallTest
    public void testMtpObjectGetStorageId() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getStorageId(), mainMtpStorage.getStorageId());
    }

    @Test
    @SmallTest
    public void testMtpObjectGetLastModified() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getModifiedTime(), newFile.lastModified() / 1000);
    }

    @Test
    @SmallTest
    public void testMtpObjectGetParent() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getParent(),
                manager.getStorageRoot(mainMtpStorage.getStorageId()));
    }

    @Test
    @SmallTest
    public void testMtpObjectGetRoot() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getRoot(),
                manager.getStorageRoot(mainMtpStorage.getStorageId()));
    }

    @Test
    @SmallTest
    public void testMtpObjectGetPath() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getPath().toString(), newFile.getPath());
    }

    @Test
    @SmallTest
    public void testMtpObjectGetSize() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        try {
            new FileOutputStream(newFile).write(new byte[] {0, 0, 0, 0, 0, 0, 0, 0});
        } catch (IOException e) {
            Assert.fail();
        }
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getSize(), 8);
    }

    @Test
    @SmallTest
    public void testMtpObjectGetSizeDir() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getSize(), 0);
    }

    /** MtpStorageManager cache access tests. **/

    @Test
    @SmallTest
    public void testAddMtpStorage() {
        logMethodName();
        Assert.assertEquals(mainMtpStorage.getPath(), mainStorageDir.getPath());
        Assert.assertNotNull(manager.getStorageRoot(mainMtpStorage.getStorageId()));
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRemoveMtpStorage() {
        logMethodName();
        File newFile = createNewFile(secondaryStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                secondaryMtpStorage.getStorageId());
        Assert.assertEquals(stream.size(), 1);

        manager.removeMtpStorage(secondaryMtpStorage);
        Assert.assertNull(manager.getStorageRoot(secondaryMtpStorage.getStorageId()));
        Assert.assertNull(manager.getObject(1));
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testGetByPath() {
        logMethodName();
        File newFile = createNewFile(createNewDir(createNewDir(mainStorageDir)));

        MtpStorageManager.MtpObject obj = manager.getByPath(newFile.getPath());
        Assert.assertNotNull(obj);
        Assert.assertEquals(obj.getPath().toString(), newFile.getPath());
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testGetByPathError() {
        logMethodName();
        File newFile = createNewFile(createNewDir(createNewDir(mainStorageDir)));

        MtpStorageManager.MtpObject obj = manager.getByPath(newFile.getPath() + "q");
        Assert.assertNull(obj);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testGetObject() {
        logMethodName();
        File newFile = createNewFile(createNewDir(createNewDir(mainStorageDir)));
        MtpStorageManager.MtpObject obj = manager.getByPath(newFile.getPath());
        Assert.assertNotNull(obj);

        Assert.assertEquals(manager.getObject(obj.getId()), obj);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testGetObjectError() {
        logMethodName();
        File newFile = createNewFile(createNewDir(createNewDir(mainStorageDir)));

        Assert.assertNull(manager.getObject(42));
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testGetStorageRoot() {
        logMethodName();
        MtpStorageManager.MtpObject obj = manager.getStorageRoot(mainMtpStorage.getStorageId());
        Assert.assertEquals(obj.getPath().toString(), mainStorageDir.getPath());
    }

    @Test
    @SmallTest
    public void testGetObjectsParent() {
        logMethodName();
        File newDir = createNewDir(createNewDir(mainStorageDir));
        File newFile = createNewFile(newDir);
        File newMP3File = createNewFile(newDir, "lalala.mp3");
        MtpStorageManager.MtpObject parent = manager.getByPath(newDir.getPath());
        Assert.assertNotNull(parent);

        List<MtpStorageManager.MtpObject> stream = manager.getObjects(parent.getId(), 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.size(), 2);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testGetObjectsFormat() {
        logMethodName();
        File newDir = createNewDir(createNewDir(mainStorageDir));
        File newFile = createNewFile(newDir);
        File newMP3File = createNewFile(newDir, "lalala.mp3");
        MtpStorageManager.MtpObject parent = manager.getByPath(newDir.getPath());
        Assert.assertNotNull(parent);

        List<MtpStorageManager.MtpObject> stream = manager.getObjects(parent.getId(),
                MtpConstants.FORMAT_MP3, mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.get(0).getPath().toString(), newMP3File.toString());
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testGetObjectsRoot() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        File newFile = createNewFile(mainStorageDir);
        File newMP3File = createNewFile(newDir, "lalala.mp3");

        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.size(), 2);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testGetObjectsAll() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        File newFile = createNewFile(mainStorageDir);
        File newMP3File = createNewFile(newDir, "lalala.mp3");

        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.size(), 3);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testGetObjectsAllStorages() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        createNewFile(mainStorageDir);
        createNewFile(newDir, "lalala.mp3");
        File newDir2 = createNewDir(secondaryStorageDir);
        createNewFile(secondaryStorageDir);
        createNewFile(newDir2, "lalala.mp3");

        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0, 0, 0xFFFFFFFF);
        Assert.assertEquals(stream.size(), 6);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testGetObjectsAllStoragesRoot() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        createNewFile(mainStorageDir);
        createNewFile(newDir, "lalala.mp3");
        File newDir2 = createNewDir(secondaryStorageDir);
        createNewFile(secondaryStorageDir);
        createNewFile(newDir2, "lalala.mp3");

        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0, 0xFFFFFFFF);
        Assert.assertEquals(stream.size(), 4);
        Assert.assertTrue(manager.checkConsistency());
    }

    /** MtpStorageManager event handling tests. **/

    @Test
    @SmallTest
    public void testObjectAdded() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.size(), 0);

        File newFile = createNewFileNonZero(mainStorageDir);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertEquals(manager.getObject(objectsAdded.get(0)).getPath().toString(),
                newFile.getPath());

        logMethodValue("objectsInfoChanged.size", objectsInfoChanged.size());
        if (objectsInfoChanged.size() > 0)
            Assert.assertEquals(objectsAdded.get(0).intValue(), objectsInfoChanged.get(0).intValue());

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testObjectAddedDir() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.size(), 0);

        File newDir = createNewDir(mainStorageDir);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertEquals(manager.getObject(objectsAdded.get(0)).getPath().toString(),
                newDir.getPath());
        Assert.assertTrue(manager.getObject(objectsAdded.get(0)).isDir());
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testObjectAddedRecursiveDir() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.size(), 0);

        File newDir = createNewDir(createNewDir(createNewDir(mainStorageDir)));
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 3);
        Assert.assertEquals(manager.getObject(objectsAdded.get(2)).getPath().toString(),
                newDir.getPath());
        Assert.assertTrue(manager.getObject(objectsAdded.get(2)).isDir());
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testObjectRemoved() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.size(), 1);

        Assert.assertTrue(newFile.delete());
        manager.flushEvents();
        Assert.assertEquals(objectsRemoved.size(), 1);
        Assert.assertNull(manager.getObject(objectsRemoved.get(0)));
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testObjectMoved() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        Assert.assertEquals(stream.size(), 1);
        File toFile = new File(mainStorageDir, "to" + newFile.getName());

        Assert.assertTrue(newFile.renameTo(toFile));
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertEquals(objectsRemoved.size(), 1);
        Assert.assertEquals(manager.getObject(objectsAdded.get(0)).getPath().toString(),
                toFile.getPath());
        Assert.assertNull(manager.getObject(objectsRemoved.get(0)));
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    /** MtpStorageManager operation tests. Ensure that events are not sent for the main operation,
        and also test all possible cases of other processes accessing the file at the same time, as
        well as cases of both failure and success. **/

    @Test
    @SmallTest
    public void testSendObjectSuccess() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        int id = manager.beginSendObject(manager.getStorageRoot(mainMtpStorage.getStorageId()),
                "newFile", MtpConstants.FORMAT_UNDEFINED);
        Assert.assertEquals(id, 1);

        File newFile = createNewFileNonZero(mainStorageDir, "newFile");
        manager.flushEvents();
        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endSendObject(obj, true));
        Assert.assertEquals(obj.getPath().toString(), newFile.getPath());
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testSendObjectSuccessDir() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        int id = manager.beginSendObject(manager.getStorageRoot(mainMtpStorage.getStorageId()),
                "newDir", MtpConstants.FORMAT_ASSOCIATION);
        Assert.assertEquals(id, 1);

        File newFile = createNewDir(mainStorageDir, "newDir");
        manager.flushEvents();
        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endSendObject(obj, true));
        Assert.assertEquals(obj.getPath().toString(), newFile.getPath());
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(obj.getFormat(), MtpConstants.FORMAT_ASSOCIATION);
        Assert.assertTrue(manager.checkConsistency());

        // Check that new dir receives events
        File newerFile = createNewFileNonZero(newFile);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertEquals(manager.getObject(objectsAdded.get(0)).getPath().toString(),
                newerFile.getPath());
    }

    @Test
    @SmallTest
    public void testSendObjectSuccessDelayed() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        int id = manager.beginSendObject(manager.getStorageRoot(mainMtpStorage.getStorageId()),
                "newFile", MtpConstants.FORMAT_UNDEFINED);
        Assert.assertEquals(id, 1);
        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endSendObject(obj, true));

        File newFile = createNewFileNonZero(mainStorageDir, "newFile");
        manager.flushEvents();
        Assert.assertEquals(obj.getPath().toString(), newFile.getPath());
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testSendObjectSuccessDirDelayed() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        int id = manager.beginSendObject(manager.getStorageRoot(mainMtpStorage.getStorageId()),
                "newDir", MtpConstants.FORMAT_ASSOCIATION);
        Assert.assertEquals(id, 1);

        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endSendObject(obj, true));
        File newFile = createNewDir(mainStorageDir, "newDir");
        manager.flushEvents();
        Assert.assertEquals(obj.getPath().toString(), newFile.getPath());
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(obj.getFormat(), MtpConstants.FORMAT_ASSOCIATION);
        Assert.assertTrue(manager.checkConsistency());

        // Check that new dir receives events
        File newerFile = createNewFileNonZero(newFile);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertEquals(manager.getObject(objectsAdded.get(0)).getPath().toString(),
                newerFile.getPath());
    }

    @Test
    @SmallTest
    public void testSendObjectSuccessDeleted() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        int id = manager.beginSendObject(manager.getStorageRoot(mainMtpStorage.getStorageId()),
                "newFile", MtpConstants.FORMAT_UNDEFINED);
        Assert.assertEquals(id, 1);

        File newFile = createNewFile(mainStorageDir, "newFile");
        Assert.assertTrue(newFile.delete());
        manager.flushEvents();
        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endSendObject(obj, true));
        Assert.assertNull(manager.getObject(obj.getId()));
        Assert.assertEquals(objectsRemoved.get(0).intValue(), obj.getId());
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testSendObjectFailed() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        int id = manager.beginSendObject(manager.getStorageRoot(mainMtpStorage.getStorageId()),
                "newFile", MtpConstants.FORMAT_UNDEFINED);
        Assert.assertEquals(id, 1);

        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endSendObject(obj, false));
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testSendObjectFailedDeleted() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        int id = manager.beginSendObject(manager.getStorageRoot(mainMtpStorage.getStorageId()),
                "newFile", MtpConstants.FORMAT_UNDEFINED);
        Assert.assertEquals(id, 1);
        MtpStorageManager.MtpObject obj = manager.getObject(id);

        File newFile = createNewFileNonZero(mainStorageDir, "newFile");
        Assert.assertTrue(newFile.delete());
        manager.flushEvents();
        Assert.assertTrue(manager.endSendObject(obj, false));
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testSendObjectFailedAdded() {
        logMethodName();
        List<MtpStorageManager.MtpObject> stream = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId());
        int id = manager.beginSendObject(manager.getStorageRoot(mainMtpStorage.getStorageId()),
                "newFile", MtpConstants.FORMAT_UNDEFINED);
        Assert.assertEquals(id, 1);
        MtpStorageManager.MtpObject obj = manager.getObject(id);

        File newDir = createNewDir(mainStorageDir, "newFile");
        manager.flushEvents();
        Assert.assertTrue(manager.endSendObject(obj, false));
        Assert.assertNotEquals(objectsAdded.get(0).intValue(), id);
        logMethodValue("objectsInfoChanged.size", objectsInfoChanged.size());
        if (objectsInfoChanged.size() > 0)
            Assert.assertNotEquals(objectsInfoChanged.get(0).intValue(), id);
        Assert.assertNull(manager.getObject(id));
        Assert.assertEquals(manager.getObject(objectsAdded.get(0)).getPath().toString(),
                newDir.getPath());
        Assert.assertTrue(manager.checkConsistency());

        // Expect events in new dir
        createNewFileNonZero(newDir);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 2);
        logMethodValue("objectsInfoChanged.size", objectsInfoChanged.size());
        if (objectsInfoChanged.size() == 1)
            Assert.assertEquals(objectsAdded.get(1).intValue(), objectsInfoChanged.get(0).intValue());
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRemoveObjectSuccess() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRemoveObject(obj));

        Assert.assertTrue(newFile.delete());
        manager.flushEvents();
        Assert.assertTrue(manager.endRemoveObject(obj, true));
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertNull(manager.getObject(obj.getId()));
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRemoveObjectDelayed() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRemoveObject(obj));

        Assert.assertTrue(manager.endRemoveObject(obj, true));
        Assert.assertTrue(newFile.delete());
        manager.flushEvents();
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertNull(manager.getObject(obj.getId()));
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRemoveObjectDir() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        createNewFileNonZero(createNewDir(newDir));
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        manager.getObjects(obj.getId(), 0, mainMtpStorage.getStorageId());
        Assert.assertTrue(manager.beginRemoveObject(obj));

        createNewFileNonZero(newDir);
        Assert.assertTrue(FileUtils.deleteContentsAndDir(newDir));
        manager.flushEvents();
        Assert.assertTrue(manager.endRemoveObject(obj, true));
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertEquals(objectsRemoved.size(), 1);
        Assert.assertEquals(manager.getObjects(0, 0, mainMtpStorage.getStorageId()).size(), 0);
        Assert.assertNull(manager.getObject(obj.getId()));
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRemoveObjectDirDelayed() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        createNewFile(createNewDir(newDir));
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRemoveObject(obj));

        Assert.assertTrue(manager.endRemoveObject(obj, true));
        Assert.assertTrue(FileUtils.deleteContentsAndDir(newDir));
        manager.flushEvents();
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(manager.getObjects(0, 0, mainMtpStorage.getStorageId()).size(), 0);
        Assert.assertNull(manager.getObject(obj.getId()));
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRemoveObjectSuccessAdded() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        int id = obj.getId();
        Assert.assertTrue(manager.beginRemoveObject(obj));

        Assert.assertTrue(newFile.delete());
        createNewFileNonZero(mainStorageDir, newFile.getName());
        manager.flushEvents();
        Assert.assertTrue(manager.endRemoveObject(obj, true));
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertNull(manager.getObject(id));
        Assert.assertNotEquals(objectsAdded.get(0).intValue(), id);
        logMethodValue("objectsInfoChanged.size", objectsInfoChanged.size());
        if (objectsInfoChanged.size() > 0)
            Assert.assertNotEquals(objectsInfoChanged.get(0).intValue(), objectsAdded.get(0).intValue());
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRemoveObjectFailed() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRemoveObject(obj));

        Assert.assertTrue(manager.endRemoveObject(obj, false));
        Assert.assertEquals(manager.getObject(obj.getId()), obj);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRemoveObjectFailedDir() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        manager.getObjects(obj.getId(), 0, mainMtpStorage.getStorageId());
        Assert.assertTrue(manager.beginRemoveObject(obj));

        createNewFile(newDir);
        manager.flushEvents();
        Assert.assertTrue(manager.endRemoveObject(obj, false));
        Assert.assertEquals(manager.getObject(obj.getId()), obj);
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRemoveObjectFailedRemoved() {
        logMethodName();
        File newFile = createNewFile(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRemoveObject(obj));

        Assert.assertTrue(newFile.delete());
        manager.flushEvents();
        Assert.assertTrue(manager.endRemoveObject(obj, false));
        Assert.assertEquals(objectsRemoved.size(), 1);
        Assert.assertEquals(objectsRemoved.get(0).intValue(), obj.getId());
        Assert.assertNull(manager.getObject(obj.getId()));
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testCopyObjectSuccess() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        File newDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId())
                .stream().filter(MtpStorageManager.MtpObject::isDir).findFirst().get();
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> !o.isDir()).findFirst().get();

        int id = manager.beginCopyObject(fileObj, dirObj);
        Assert.assertNotEquals(id, -1);
        createNewFileNonZero(newDir, newFile.getName());
        manager.flushEvents();
        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endCopyObject(obj, true));
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testCopyObjectSuccessRecursive() {
        logMethodName();
        File newDirFrom = createNewDir(mainStorageDir);
        File newDirFrom1 = createNewDir(newDirFrom);
        File newDirFrom2 = createNewFileNonZero(newDirFrom1);
        File delayedFile = createNewFileNonZero(newDirFrom);
        File deletedFile = createNewFileNonZero(newDirFrom);
        File newDirTo = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject toObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> o.getName().equals(newDirTo.getName())).findFirst().get();
        MtpStorageManager.MtpObject fromObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> o.getName().equals(newDirFrom.getName())).findFirst().get();

        manager.getObjects(fromObj.getId(), 0, mainMtpStorage.getStorageId());
        int id = manager.beginCopyObject(fromObj, toObj);
        Assert.assertNotEquals(id, -1);
        File copiedDir = createNewDir(newDirTo, newDirFrom.getName());
        File copiedDir1 = createNewDir(copiedDir, newDirFrom1.getName());
        createNewFileNonZero(copiedDir1, newDirFrom2.getName());
        createNewFileNonZero(copiedDir, "extraFile");
        File toDelete = createNewFileNonZero(copiedDir, deletedFile.getName());
        manager.flushEvents();
        Assert.assertTrue(toDelete.delete());
        manager.flushEvents();
        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endCopyObject(obj, true));
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertEquals(objectsRemoved.size(), 1);

        createNewFileNonZero(copiedDir, delayedFile.getName());
        manager.flushEvents();
        Assert.assertTrue(manager.checkConsistency());

        // Expect events in the visited dir, but not the unvisited dir.
        createNewFileNonZero(copiedDir);
        createNewFileNonZero(copiedDir1);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 2);
        Assert.assertEquals(objectsAdded.size(), 2);

        // Number of files/dirs created, minus the one that was deleted.
        Assert.assertEquals(manager.getObjects(0, 0, mainMtpStorage.getStorageId()).size(), 13);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testCopyObjectFailed() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        File newDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(MtpStorageManager.MtpObject::isDir).findFirst().get();
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> !o.isDir()).findFirst().get();

        int id = manager.beginCopyObject(fileObj, dirObj);
        Assert.assertNotEquals(id, -1);
        manager.flushEvents();
        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endCopyObject(obj, false));
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testCopyObjectFailedAdded() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        File newDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(MtpStorageManager.MtpObject::isDir).findFirst().get();
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> !o.isDir()).findFirst().get();

        int id = manager.beginCopyObject(fileObj, dirObj);
        Assert.assertNotEquals(id, -1);
        File addedDir = createNewDir(newDir, newFile.getName());
        manager.flushEvents();
        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endCopyObject(obj, false));
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertNotEquals(objectsAdded.get(0).intValue(), id);
        Assert.assertTrue(manager.checkConsistency());

        // Expect events in new dir
        createNewFileNonZero(addedDir);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 2);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testCopyObjectFailedDeleted() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        File newDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(MtpStorageManager.MtpObject::isDir).findFirst().get();
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> !o.isDir()).findFirst().get();

        int id = manager.beginCopyObject(fileObj, dirObj);
        Assert.assertNotEquals(id, -1);
        Assert.assertTrue(createNewFileNonZero(newDir, newFile.getName()).delete());
        manager.flushEvents();
        MtpStorageManager.MtpObject obj = manager.getObject(id);
        Assert.assertTrue(manager.endCopyObject(obj, false));
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRenameObjectSuccess() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRenameObject(obj, "renamed"));

        File renamed = new File(mainStorageDir, "renamed");
        Assert.assertTrue(newFile.renameTo(renamed));
        manager.flushEvents();
        Assert.assertTrue(manager.endRenameObject(obj, newFile.getName(), true));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(obj.getPath().toString(), renamed.getPath());

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRenameObjectDirSuccess() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRenameObject(obj, "renamed"));

        File renamed = new File(mainStorageDir, "renamed");
        Assert.assertTrue(newDir.renameTo(renamed));
        manager.flushEvents();
        Assert.assertTrue(manager.endRenameObject(obj, newDir.getName(), true));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(obj.getPath().toString(), renamed.getPath());

        Assert.assertTrue(manager.checkConsistency());

        // Don't expect events
        createNewFile(renamed);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRenameObjectDirVisitedSuccess() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        manager.getObjects(obj.getId(), 0, mainMtpStorage.getStorageId());
        Assert.assertTrue(manager.beginRenameObject(obj, "renamed"));

        File renamed = new File(mainStorageDir, "renamed");
        Assert.assertTrue(newDir.renameTo(renamed));
        manager.flushEvents();
        Assert.assertTrue(manager.endRenameObject(obj, newDir.getName(), true));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(obj.getPath().toString(), renamed.getPath());

        Assert.assertTrue(manager.checkConsistency());

        // Expect events since the dir was visited
        createNewFileNonZero(renamed);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRenameObjectDelayed() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRenameObject(obj, "renamed"));

        Assert.assertTrue(manager.endRenameObject(obj, newFile.getName(), true));
        File renamed = new File(mainStorageDir, "renamed");
        Assert.assertTrue(newFile.renameTo(renamed));
        manager.flushEvents();

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(obj.getPath().toString(), renamed.getPath());

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRenameObjectDirVisitedDelayed() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        manager.getObjects(obj.getId(), 0, mainMtpStorage.getStorageId());
        Assert.assertTrue(manager.beginRenameObject(obj, "renamed"));

        Assert.assertTrue(manager.endRenameObject(obj, newDir.getName(), true));
        File renamed = new File(mainStorageDir, "renamed");
        Assert.assertTrue(newDir.renameTo(renamed));
        manager.flushEvents();

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(obj.getPath().toString(), renamed.getPath());

        Assert.assertTrue(manager.checkConsistency());

        // Expect events since the dir was visited
        createNewFileNonZero(renamed);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRenameObjectFailed() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRenameObject(obj, "renamed"));

        Assert.assertTrue(manager.endRenameObject(obj, newFile.getName(), false));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRenameObjectFailedOldRemoved() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRenameObject(obj, "renamed"));

        Assert.assertTrue(newFile.delete());
        manager.flushEvents();
        Assert.assertTrue(manager.endRenameObject(obj, newFile.getName(), false));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 1);

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testRenameObjectFailedNewAdded() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        MtpStorageManager.MtpObject obj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginRenameObject(obj, "renamed"));

        createNewFileNonZero(mainStorageDir, "renamed");
        manager.flushEvents();
        Assert.assertTrue(manager.endRenameObject(obj, newFile.getName(), false));

        Assert.assertEquals(objectsAdded.size(), 1);
        logMethodValue("objectsInfoChanged.size", objectsInfoChanged.size());
        if (objectsInfoChanged.size() > 0)
            Assert.assertNotEquals(objectsAdded.get(0).intValue(), objectsInfoChanged.get(0).intValue());
        Assert.assertEquals(objectsRemoved.size(), 0);

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectSuccess() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        File dir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(MtpStorageManager.MtpObject::isDir).findFirst().get();
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> !o.isDir()).findFirst().get();
        Assert.assertTrue(manager.beginMoveObject(fileObj, dirObj));

        File moved = new File(dir, newFile.getName());
        Assert.assertTrue(newFile.renameTo(moved));
        manager.flushEvents();
        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                dirObj, newFile.getName(), true));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(fileObj.getPath().toString(), moved.getPath());

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectDirSuccess() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        File movedDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> o.getName().equals(newDir.getName())).findFirst().get();
        MtpStorageManager.MtpObject movedObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> o.getName().equals(movedDir.getName())).findFirst().get();
        Assert.assertTrue(manager.beginMoveObject(movedObj, dirObj));

        File renamed = new File(newDir, movedDir.getName());
        Assert.assertTrue(movedDir.renameTo(renamed));
        manager.flushEvents();
        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                dirObj, movedDir.getName(), true));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(movedObj.getPath().toString(), renamed.getPath());

        Assert.assertTrue(manager.checkConsistency());

        // Don't expect events
        createNewFile(renamed);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectDirVisitedSuccess() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        File movedDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> o.getName().equals(newDir.getName())).findFirst().get();
        MtpStorageManager.MtpObject movedObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> o.getName().equals(movedDir.getName())).findFirst().get();
        manager.getObjects(movedObj.getId(), 0, mainMtpStorage.getStorageId());
        Assert.assertTrue(manager.beginMoveObject(movedObj, dirObj));

        File renamed = new File(newDir, movedDir.getName());
        Assert.assertTrue(movedDir.renameTo(renamed));
        manager.flushEvents();
        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                dirObj, movedDir.getName(), true));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(movedObj.getPath().toString(), renamed.getPath());

        Assert.assertTrue(manager.checkConsistency());

        // Expect events since the dir was visited
        createNewFileNonZero(renamed);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectDelayed() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        File dir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(MtpStorageManager.MtpObject::isDir).findFirst().get();
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> !o.isDir()).findFirst().get();
        Assert.assertTrue(manager.beginMoveObject(fileObj, dirObj));

        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                dirObj, newFile.getName(), true));

        File moved = new File(dir, newFile.getName());
        Assert.assertTrue(newFile.renameTo(moved));
        manager.flushEvents();

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(fileObj.getPath().toString(), moved.getPath());

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectDirVisitedDelayed() {
        logMethodName();
        File newDir = createNewDir(mainStorageDir);
        File movedDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> o.getName().equals(newDir.getName())).findFirst().get();
        MtpStorageManager.MtpObject movedObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> o.getName().equals(movedDir.getName())).findFirst().get();
        manager.getObjects(movedObj.getId(), 0, mainMtpStorage.getStorageId());
        Assert.assertTrue(manager.beginMoveObject(movedObj, dirObj));

        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                dirObj, movedDir.getName(), true));

        File renamed = new File(newDir, movedDir.getName());
        Assert.assertTrue(movedDir.renameTo(renamed));
        manager.flushEvents();

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(movedObj.getPath().toString(), renamed.getPath());

        Assert.assertTrue(manager.checkConsistency());

        // Expect events since the dir was visited
        createNewFileNonZero(renamed);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectFailed() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        File dir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(MtpStorageManager.MtpObject::isDir).findFirst().get();
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> !o.isDir()).findFirst().get();
        Assert.assertTrue(manager.beginMoveObject(fileObj, dirObj));

        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                dirObj, newFile.getName(), false));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectFailedOldRemoved() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        File dir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(MtpStorageManager.MtpObject::isDir).findFirst().get();
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> !o.isDir()).findFirst().get();
        Assert.assertTrue(manager.beginMoveObject(fileObj, dirObj));

        Assert.assertTrue(newFile.delete());
        manager.flushEvents();
        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                dirObj, newFile.getName(), false));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 1);

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectFailedNewAdded() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        File dir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject dirObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(MtpStorageManager.MtpObject::isDir).findFirst().get();
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).stream()
                .filter(o -> !o.isDir()).findFirst().get();
        Assert.assertTrue(manager.beginMoveObject(fileObj, dirObj));

        createNewFileNonZero(dir, newFile.getName());
        manager.flushEvents();
        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                dirObj, newFile.getName(), false));

        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertEquals(objectsRemoved.size(), 0);

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectXStorageSuccess() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginMoveObject(fileObj,
                manager.getStorageRoot(secondaryMtpStorage.getStorageId())));

        Assert.assertTrue(newFile.delete());
        File moved = createNewFileNonZero(secondaryStorageDir, newFile.getName());
        manager.flushEvents();
        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                manager.getStorageRoot(secondaryMtpStorage.getStorageId()),
                newFile.getName(), true));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(manager.getObject(fileObj.getId()).getPath().toString(),
                moved.getPath());

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectXStorageDirSuccess() {
        logMethodName();
        File movedDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject movedObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginMoveObject(movedObj,
                manager.getStorageRoot(secondaryMtpStorage.getStorageId())));

        Assert.assertTrue(movedDir.delete());
        File moved = createNewDir(secondaryStorageDir, movedDir.getName());
        manager.flushEvents();
        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                manager.getStorageRoot(secondaryMtpStorage.getStorageId()),
                movedDir.getName(), true));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(manager.getObject(movedObj.getId()).getPath().toString(),
                moved.getPath());

        Assert.assertTrue(manager.checkConsistency());

        // Don't expect events
        createNewFile(moved);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectXStorageDirVisitedSuccess() {
        logMethodName();
        File movedDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject movedObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        manager.getObjects(movedObj.getId(), 0, mainMtpStorage.getStorageId());
        Assert.assertTrue(manager.beginMoveObject(movedObj,
                manager.getStorageRoot(secondaryMtpStorage.getStorageId())));

        Assert.assertTrue(movedDir.delete());
        File moved = createNewDir(secondaryStorageDir, movedDir.getName());
        manager.flushEvents();
        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                manager.getStorageRoot(secondaryMtpStorage.getStorageId()),
                movedDir.getName(), true));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(manager.getObject(movedObj.getId()).getPath().toString(),
                moved.getPath());

        Assert.assertTrue(manager.checkConsistency());

        // Expect events since the dir was visited
        createNewFileNonZero(moved);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectXStorageDelayed() {
        logMethodName();
        File movedFile = createNewFileNonZero(mainStorageDir);
        MtpStorageManager.MtpObject movedObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginMoveObject(movedObj,
                manager.getStorageRoot(secondaryMtpStorage.getStorageId())));

        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                manager.getStorageRoot(secondaryMtpStorage.getStorageId()),
                movedFile.getName(), true));

        Assert.assertTrue(movedFile.delete());
        File moved = createNewFileNonZero(secondaryStorageDir, movedFile.getName());
        manager.flushEvents();

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(manager.getObject(movedObj.getId()).getPath().toString(),
                moved.getPath());

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectXStorageDirVisitedDelayed() {
        logMethodName();
        File movedDir = createNewDir(mainStorageDir);
        MtpStorageManager.MtpObject movedObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        manager.getObjects(movedObj.getId(), 0, mainMtpStorage.getStorageId());
        Assert.assertTrue(manager.beginMoveObject(movedObj,
                manager.getStorageRoot(secondaryMtpStorage.getStorageId())));

        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                manager.getStorageRoot(secondaryMtpStorage.getStorageId()),
                movedDir.getName(), true));

        Assert.assertTrue(movedDir.delete());
        File moved = createNewDir(secondaryStorageDir, movedDir.getName());
        manager.flushEvents();

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);
        Assert.assertEquals(manager.getObject(movedObj.getId()).getPath().toString(),
                moved.getPath());

        Assert.assertTrue(manager.checkConsistency());

        // Expect events since the dir was visited
        createNewFileNonZero(moved);
        manager.flushEvents();
        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectXStorageFailed() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginMoveObject(fileObj,
                manager.getStorageRoot(secondaryMtpStorage.getStorageId())));

        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                manager.getStorageRoot(secondaryMtpStorage.getStorageId()),
                newFile.getName(), false));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 0);

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectXStorageFailedOldRemoved() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginMoveObject(fileObj,
                manager.getStorageRoot(secondaryMtpStorage.getStorageId())));

        Assert.assertTrue(newFile.delete());
        manager.flushEvents();
        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                manager.getStorageRoot(secondaryMtpStorage.getStorageId()),
                newFile.getName(), false));

        Assert.assertEquals(objectsAdded.size(), 0);
        Assert.assertEquals(objectsInfoChanged.size(), 0);
        Assert.assertEquals(objectsRemoved.size(), 1);

        Assert.assertTrue(manager.checkConsistency());
    }

    @Test
    @SmallTest
    public void testMoveObjectXStorageFailedNewAdded() {
        logMethodName();
        File newFile = createNewFileNonZero(mainStorageDir);
        MtpStorageManager.MtpObject fileObj = manager.getObjects(0xFFFFFFFF, 0,
                mainMtpStorage.getStorageId()).get(0);
        Assert.assertTrue(manager.beginMoveObject(fileObj,
                manager.getStorageRoot(secondaryMtpStorage.getStorageId())));

        createNewFileNonZero(secondaryStorageDir, newFile.getName());
        manager.flushEvents();
        Assert.assertTrue(manager.endMoveObject(
                manager.getStorageRoot(mainMtpStorage.getStorageId()),
                manager.getStorageRoot(secondaryMtpStorage.getStorageId()),
                newFile.getName(), false));

        Assert.assertEquals(objectsAdded.size(), 1);
        Assert.assertEquals(objectsRemoved.size(), 0);

        Assert.assertTrue(manager.checkConsistency());
    }
}
