/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.DropBoxManager;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import android.os.StatFs;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.AndroidTestCase;

import com.android.server.DropBoxManagerInternal.EntrySource;
import com.android.server.DropBoxManagerService.EntryFile;

import libcore.io.Streams;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/**
 * Test {@link DropBoxManager} functionality.
 *
 * Run with:
 * bit FrameworksServicesTests:com.android.server.DropBoxTest
 */
public class DropBoxTest extends AndroidTestCase {
    private Context mContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        LocalServices.removeServiceForTest(DropBoxManagerInternal.class);

        mContext = new ContextWrapper(super.getContext()) {
            @Override
            public void sendBroadcastAsUser(Intent intent,
                    UserHandle user, String receiverPermission) {
                // Don't actually send broadcasts.
            }
        };
    }

    public void tearDown() throws Exception {
        ContentResolver cr = getContext().getContentResolver();
        Settings.Global.putString(cr, Settings.Global.DROPBOX_AGE_SECONDS, "");
        Settings.Global.putString(cr, Settings.Global.DROPBOX_MAX_FILES, "");
        Settings.Global.putString(cr, Settings.Global.DROPBOX_QUOTA_KB, "");
        Settings.Global.putString(cr, Settings.Global.DROPBOX_TAG_PREFIX + "DropBoxTest", "");
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    public void testAddText() throws Exception {
        File dir = getEmptyDir("testAddText");
        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        long before = System.currentTimeMillis();
        Thread.sleep(5);
        dropbox.addText("DropBoxTest", "TEST0");
        Thread.sleep(5);
        long between = System.currentTimeMillis();
        Thread.sleep(5);
        dropbox.addText("DropBoxTest", "TEST1");
        dropbox.addText("DropBoxTest", "TEST2");
        Thread.sleep(5);
        long after = System.currentTimeMillis();

        DropBoxManager.Entry e0 = dropbox.getNextEntry("DropBoxTest", before);
        DropBoxManager.Entry e1 = dropbox.getNextEntry("DropBoxTest", e0.getTimeMillis());
        DropBoxManager.Entry e2 = dropbox.getNextEntry("DropBoxTest", e1.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry("DropBoxTest", e2.getTimeMillis()));

        assertTrue(e0.getTimeMillis() > before);
        assertTrue(e0.getTimeMillis() < between);
        assertTrue(e1.getTimeMillis() > between);
        assertTrue(e1.getTimeMillis() < e2.getTimeMillis());
        assertTrue(e2.getTimeMillis() < after);

        assertEquals("TEST0", e0.getText(80));
        assertEquals("TEST1", e1.getText(80));
        assertEquals("TES", e2.getText(3));

        e0.close();
        e1.close();
        e2.close();
    }

    public void testAddData() throws Exception {
        File dir = getEmptyDir("testAddData");
        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        long before = System.currentTimeMillis();
        Thread.sleep(1);
        dropbox.addData("DropBoxTest", "TEST".getBytes(), 0);
        Thread.sleep(1);
        long after = System.currentTimeMillis();

        DropBoxManager.Entry e = dropbox.getNextEntry("DropBoxTest", before);
        assertNotNull(e);
        assertNull(dropbox.getNextEntry("DropBoxTest", e.getTimeMillis()));

        assertEquals("DropBoxTest", e.getTag());
        assertTrue(e.getTimeMillis() >= before);
        assertEquals(0, e.getFlags());
        assertTrue(null == e.getText(80));

        byte[] buf = new byte[80];
        assertEquals("TEST", new String(buf, 0, e.getInputStream().read(buf)));

        e.close();
    }

    public void testAddFile() throws Exception {
        File dir = getEmptyDir("testAddFile");
        long before = System.currentTimeMillis();

        File clientDir = getEmptyDir("testAddFile_client");

        File f0 = new File(clientDir, "f0.txt");
        File f1 = new File(clientDir, "f1.txt.gz");
        File f2 = new File(clientDir, "f2.dat");
        File f3 = new File(clientDir, "f2.dat.gz");

        FileWriter w0 = new FileWriter(f0);
        GZIPOutputStream gz1 = new GZIPOutputStream(new FileOutputStream(f1));
        FileOutputStream os2 = new FileOutputStream(f2);
        GZIPOutputStream gz3 = new GZIPOutputStream(new FileOutputStream(f3));

        w0.write("FILE0");
        gz1.write("FILE1".getBytes());
        os2.write("DATA2".getBytes());
        gz3.write("DATA3".getBytes());

        w0.close();
        gz1.close();
        os2.close();
        gz3.close();

        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        dropbox.addFile("DropBoxTest", f0, DropBoxManager.IS_TEXT);
        dropbox.addFile("DropBoxTest", f1, DropBoxManager.IS_TEXT | DropBoxManager.IS_GZIPPED);
        dropbox.addFile("DropBoxTest", f2, 0);
        dropbox.addFile("DropBoxTest", f3, DropBoxManager.IS_GZIPPED);

        DropBoxManager.Entry e0 = dropbox.getNextEntry("DropBoxTest", before);
        DropBoxManager.Entry e1 = dropbox.getNextEntry("DropBoxTest", e0.getTimeMillis());
        DropBoxManager.Entry e2 = dropbox.getNextEntry("DropBoxTest", e1.getTimeMillis());
        DropBoxManager.Entry e3 = dropbox.getNextEntry("DropBoxTest", e2.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry("DropBoxTest", e3.getTimeMillis()));

        assertTrue(e0.getTimeMillis() > before);
        assertTrue(e1.getTimeMillis() > e0.getTimeMillis());
        assertTrue(e2.getTimeMillis() > e1.getTimeMillis());
        assertTrue(e3.getTimeMillis() > e2.getTimeMillis());

        assertEquals(DropBoxManager.IS_TEXT, e0.getFlags());
        assertEquals(DropBoxManager.IS_TEXT, e1.getFlags());
        assertEquals(0, e2.getFlags());
        assertEquals(0, e3.getFlags());

        assertEquals("FILE0", e0.getText(80));

        byte[] buf1 = new byte[80];
        assertEquals("FILE1", new String(buf1, 0, e1.getInputStream().read(buf1)));

        assertTrue(null == e2.getText(80));
        byte[] buf2 = new byte[80];
        assertEquals("DATA2", new String(buf2, 0, e2.getInputStream().read(buf2)));

        assertTrue(null == e3.getText(80));
        byte[] buf3 = new byte[80];
        assertEquals("DATA3", new String(buf3, 0, e3.getInputStream().read(buf3)));

        e0.close();
        e1.close();
        e2.close();
        e3.close();
    }

    public void testAddEntry_Success() throws Exception {
        File dir = getEmptyDir("testAddEntry");
        long before = System.currentTimeMillis();

        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        LocalServices.getService(DropBoxManagerInternal.class).addEntry("DropBoxTest",
                new EntrySource() {
                    @Override
                    public void writeTo(FileDescriptor fd) throws IOException {
                        try (FileOutputStream out = new FileOutputStream(fd)) {
                            out.write("test".getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    @Override
                    public void close() throws IOException {
                    }

                    @Override
                    public long length() {
                        return 0;
                    }
                }, DropBoxManager.IS_TEXT);

        DropBoxManager.Entry entry = dropbox.getNextEntry("DropBoxTest", before);
        assertEquals(DropBoxManager.IS_TEXT, entry.getFlags());
        assertEquals("test", new String(Streams.readFully(entry.getInputStream())));
    }

    public void testAddEntry_Failure() throws Exception {
        File dir = getEmptyDir("testAddEntry");
        long before = System.currentTimeMillis();

        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        LocalServices.getService(DropBoxManagerInternal.class).addEntry("DropBoxTest",
                new EntrySource() {
                    @Override
                    public void writeTo(FileDescriptor fd) throws IOException {
                        throw new IOException();
                    }

                    @Override
                    public void close() throws IOException {
                    }

                    @Override
                    public long length() {
                        return 0;
                    }
                }, DropBoxManager.IS_TEXT);

        DropBoxManager.Entry entry = dropbox.getNextEntry("DropBoxTest", before);
        assertNull(entry);
    }

    public void testAddEntriesInTheFuture() throws Exception {
        File dir = getEmptyDir("testAddEntriesInTheFuture");
        long before = System.currentTimeMillis();

        // Near future: should be allowed to persist
        FileWriter w0 = new FileWriter(new File(dir, "DropBoxTest@" + (before + 5000) + ".txt"));
        w0.write("FUTURE0");
        w0.close();

        // Far future: should be collapsed
        FileWriter w1 = new FileWriter(new File(dir, "DropBoxTest@" + (before + 100000) + ".txt"));
        w1.write("FUTURE1");
        w1.close();

        // Another far future item, this one gzipped
        File f2 = new File(dir, "DropBoxTest@" + (before + 100001) + ".txt.gz");
        GZIPOutputStream gz2 = new GZIPOutputStream(new FileOutputStream(f2));
        gz2.write("FUTURE2".getBytes());
        gz2.close();

        // Tombstone in the far future
        new FileOutputStream(new File(dir, "DropBoxTest@" + (before + 100002) + ".lost")).close();

        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        // Until a write, the timestamps are taken at face value
        DropBoxManager.Entry e0 = dropbox.getNextEntry(null, before);
        DropBoxManager.Entry e1 = dropbox.getNextEntry(null, e0.getTimeMillis());
        DropBoxManager.Entry e2 = dropbox.getNextEntry(null, e1.getTimeMillis());
        DropBoxManager.Entry e3 = dropbox.getNextEntry(null, e2.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry(null, e3.getTimeMillis()));

        assertEquals("FUTURE0", e0.getText(80));
        assertEquals("FUTURE1", e1.getText(80));
        assertEquals("FUTURE2", e2.getText(80));
        assertEquals(null, e3.getText(80));

        assertEquals(before + 5000, e0.getTimeMillis());
        assertEquals(before + 100000, e1.getTimeMillis());
        assertEquals(before + 100001, e2.getTimeMillis());
        assertEquals(before + 100002, e3.getTimeMillis());

        e0.close();
        e1.close();
        e2.close();
        e3.close();

        // Write something to force a collapse
        dropbox.addText("NotDropBoxTest", "FUTURE");
        e0 = dropbox.getNextEntry(null, before);
        e1 = dropbox.getNextEntry(null, e0.getTimeMillis());
        e2 = dropbox.getNextEntry(null, e1.getTimeMillis());
        e3 = dropbox.getNextEntry(null, e2.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry("DropBoxTest", e3.getTimeMillis()));

        assertEquals("FUTURE0", e0.getText(80));
        assertEquals("FUTURE1", e1.getText(80));
        assertEquals("FUTURE2", e2.getText(80));
        assertEquals(null, e3.getText(80));

        assertEquals(before + 5000, e0.getTimeMillis());
        assertEquals(before + 5001, e1.getTimeMillis());
        assertEquals(before + 5002, e2.getTimeMillis());
        assertEquals(before + 5003, e3.getTimeMillis());

        e0.close();
        e1.close();
        e2.close();
        e3.close();
    }

    public void testIsTagEnabled() throws Exception {
        File dir = getEmptyDir("testIsTagEnabled");
        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        long before = System.currentTimeMillis();
        dropbox.addText("DropBoxTest", "TEST-ENABLED");
        assertTrue(dropbox.isTagEnabled("DropBoxTest"));

        ContentResolver cr = getContext().getContentResolver();
        Settings.Global.putString(cr, Settings.Global.DROPBOX_TAG_PREFIX + "DropBoxTest",
                                  "disabled");

        dropbox.addText("DropBoxTest", "TEST-DISABLED");
        assertFalse(dropbox.isTagEnabled("DropBoxTest"));

        Settings.Global.putString(cr, Settings.Global.DROPBOX_TAG_PREFIX + "DropBoxTest",
                                  "");

        dropbox.addText("DropBoxTest", "TEST-ENABLED-AGAIN");
        assertTrue(dropbox.isTagEnabled("DropBoxTest"));

        DropBoxManager.Entry e0 = dropbox.getNextEntry("DropBoxTest", before);
        DropBoxManager.Entry e1 = dropbox.getNextEntry("DropBoxTest", e0.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry("DropBoxTest", e1.getTimeMillis()));

        assertEquals("TEST-ENABLED", e0.getText(80));
        assertEquals("TEST-ENABLED-AGAIN", e1.getText(80));

        e0.close();
        e1.close();
    }

    public void testGetNextEntry() throws Exception {
        File dir = getEmptyDir("testGetNextEntry");
        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        long before = System.currentTimeMillis();
        dropbox.addText("DropBoxTest.A", "A0");
        dropbox.addText("DropBoxTest.B", "B0");
        dropbox.addText("DropBoxTest.A", "A1");

        DropBoxManager.Entry a0 = dropbox.getNextEntry("DropBoxTest.A", before);
        DropBoxManager.Entry a1 = dropbox.getNextEntry("DropBoxTest.A", a0.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry("DropBoxTest.A", a1.getTimeMillis()));

        DropBoxManager.Entry b0 = dropbox.getNextEntry("DropBoxTest.B", before);
        assertTrue(null == dropbox.getNextEntry("DropBoxTest.B", b0.getTimeMillis()));

        DropBoxManager.Entry x0 = dropbox.getNextEntry(null, before);
        DropBoxManager.Entry x1 = dropbox.getNextEntry(null, x0.getTimeMillis());
        DropBoxManager.Entry x2 = dropbox.getNextEntry(null, x1.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry(null, x2.getTimeMillis()));

        assertEquals("DropBoxTest.A", a0.getTag());
        assertEquals("DropBoxTest.A", a1.getTag());
        assertEquals("A0", a0.getText(80));
        assertEquals("A1", a1.getText(80));

        assertEquals("DropBoxTest.B", b0.getTag());
        assertEquals("B0", b0.getText(80));

        assertEquals("DropBoxTest.A", x0.getTag());
        assertEquals("DropBoxTest.B", x1.getTag());
        assertEquals("DropBoxTest.A", x2.getTag());
        assertEquals("A0", x0.getText(80));
        assertEquals("B0", x1.getText(80));
        assertEquals("A1", x2.getText(80));

        a0.close();
        a1.close();
        b0.close();
        x0.close();
        x1.close();
        x2.close();
    }

    public void testSizeLimits() throws Exception {
        File dir = getEmptyDir("testSizeLimits");
        int blockSize =  new StatFs(dir.getPath()).getBlockSize();

        // Limit storage to 10 blocks
        int kb = blockSize * 10 / 1024;
        ContentResolver cr = getContext().getContentResolver();
        Settings.Global.putString(cr, Settings.Global.DROPBOX_QUOTA_KB, Integer.toString(kb));

        // Three tags using a total of 12 blocks:
        // DropBoxTest0 [ ][ ]
        // DropBoxTest1 [x][ ][    ][ ][xxx(20 blocks)xxx]
        // DropBoxTest2 [xxxxxxxxxx][ ][ ]
        //
        // The blocks marked "x" will be removed due to storage restrictions.
        // Use random fill (so it doesn't compress), subtract a little for gzip overhead

        final int overhead = 64;
        long before = System.currentTimeMillis();
        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        addRandomEntry(dropbox, "DropBoxTest0", blockSize - overhead);
        addRandomEntry(dropbox, "DropBoxTest0", blockSize - overhead);

        addRandomEntry(dropbox, "DropBoxTest1", blockSize - overhead);
        addRandomEntry(dropbox, "DropBoxTest1", blockSize - overhead);
        addRandomEntry(dropbox, "DropBoxTest1", blockSize * 2 - overhead);
        addRandomEntry(dropbox, "DropBoxTest1", blockSize - overhead);
        addRandomEntry(dropbox, "DropBoxTest1", blockSize * 20 - overhead);

        addRandomEntry(dropbox, "DropBoxTest2", blockSize * 4 - overhead);
        addRandomEntry(dropbox, "DropBoxTest2", blockSize - overhead);
        addRandomEntry(dropbox, "DropBoxTest2", blockSize - overhead);

        DropBoxManager.Entry e0 = dropbox.getNextEntry(null, before);
        DropBoxManager.Entry e1 = dropbox.getNextEntry(null, e0.getTimeMillis());
        DropBoxManager.Entry e2 = dropbox.getNextEntry(null, e1.getTimeMillis());
        DropBoxManager.Entry e3 = dropbox.getNextEntry(null, e2.getTimeMillis());
        DropBoxManager.Entry e4 = dropbox.getNextEntry(null, e3.getTimeMillis());
        DropBoxManager.Entry e5 = dropbox.getNextEntry(null, e4.getTimeMillis());
        DropBoxManager.Entry e6 = dropbox.getNextEntry(null, e5.getTimeMillis());
        DropBoxManager.Entry e7 = dropbox.getNextEntry(null, e6.getTimeMillis());
        DropBoxManager.Entry e8 = dropbox.getNextEntry(null, e7.getTimeMillis());
        DropBoxManager.Entry e9 = dropbox.getNextEntry(null, e8.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry(null, e9.getTimeMillis()));

        assertEquals("DropBoxTest0", e0.getTag());
        assertEquals("DropBoxTest0", e1.getTag());
        assertEquals(blockSize - overhead, getEntrySize(e0));
        assertEquals(blockSize - overhead, getEntrySize(e1));

        assertEquals("DropBoxTest1", e2.getTag());
        assertEquals("DropBoxTest1", e3.getTag());
        assertEquals("DropBoxTest1", e4.getTag());
        assertEquals("DropBoxTest1", e5.getTag());
        assertEquals("DropBoxTest1", e6.getTag());
        assertEquals(-1, getEntrySize(e2));  // Tombstone
        assertEquals(blockSize - overhead, getEntrySize(e3));
        assertEquals(blockSize * 2 - overhead, getEntrySize(e4));
        assertEquals(blockSize - overhead, getEntrySize(e5));
        assertEquals(-1, getEntrySize(e6));

        assertEquals("DropBoxTest2", e7.getTag());
        assertEquals("DropBoxTest2", e8.getTag());
        assertEquals("DropBoxTest2", e9.getTag());
        assertEquals(-1, getEntrySize(e7));  // Tombstone
        assertEquals(blockSize - overhead, getEntrySize(e8));
        assertEquals(blockSize - overhead, getEntrySize(e9));

        e0.close();
        e1.close();
        e2.close();
        e3.close();
        e4.close();
        e5.close();
        e6.close();
        e7.close();
        e8.close();
        e9.close();

        // Specifying a tag name skips tombstone records.

        DropBoxManager.Entry t0 = dropbox.getNextEntry("DropBoxTest1", before);
        DropBoxManager.Entry t1 = dropbox.getNextEntry("DropBoxTest1", t0.getTimeMillis());
        DropBoxManager.Entry t2 = dropbox.getNextEntry("DropBoxTest1", t1.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry("DropBoxTest1", t2.getTimeMillis()));

        assertEquals("DropBoxTest1", t0.getTag());
        assertEquals("DropBoxTest1", t1.getTag());
        assertEquals("DropBoxTest1", t2.getTag());

        assertEquals(blockSize - overhead, getEntrySize(t0));
        assertEquals(blockSize * 2 - overhead, getEntrySize(t1));
        assertEquals(blockSize - overhead, getEntrySize(t2));

        t0.close();
        t1.close();
        t2.close();
    }

    public void testAgeLimits() throws Exception {
        File dir = getEmptyDir("testAgeLimits");
        int blockSize = new StatFs(dir.getPath()).getBlockSize();

        // Limit storage to 10 blocks with an expiration of 1 second
        int kb = blockSize * 10 / 1024;
        ContentResolver cr = getContext().getContentResolver();
        Settings.Global.putString(cr, Settings.Global.DROPBOX_AGE_SECONDS, "1");
        Settings.Global.putString(cr, Settings.Global.DROPBOX_QUOTA_KB, Integer.toString(kb));

        // Write one normal entry and another so big that it is instantly tombstoned
        long before = System.currentTimeMillis();
        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        dropbox.addText("DropBoxTest", "TEST");
        addRandomEntry(dropbox, "DropBoxTest", blockSize * 20);

        // Verify that things are as expected
        DropBoxManager.Entry e0 = dropbox.getNextEntry(null, before);
        DropBoxManager.Entry e1 = dropbox.getNextEntry(null, e0.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry(null, e1.getTimeMillis()));

        assertEquals("TEST", e0.getText(80));
        assertEquals(null, e1.getText(80));
        assertEquals(-1, getEntrySize(e1));

        e0.close();
        e1.close();

        // Wait a second and write another entry -- old ones should be expunged
        Thread.sleep(2000);
        dropbox.addText("DropBoxTest", "TEST1");

        e0 = dropbox.getNextEntry(null, before);
        assertTrue(null == dropbox.getNextEntry(null, e0.getTimeMillis()));
        assertEquals("TEST1", e0.getText(80));
        e0.close();
    }

    public void testFileCountLimits() throws Exception {
        File dir = getEmptyDir("testFileCountLimits");

        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());
        dropbox.addText("DropBoxTest", "TEST0");
        dropbox.addText("DropBoxTest", "TEST1");
        dropbox.addText("DropBoxTest", "TEST2");
        dropbox.addText("DropBoxTest", "TEST3");
        dropbox.addText("DropBoxTest", "TEST4");
        dropbox.addText("DropBoxTest", "TEST5");

        // Verify 6 files added
        DropBoxManager.Entry e0 = dropbox.getNextEntry(null, 0);
        DropBoxManager.Entry e1 = dropbox.getNextEntry(null, e0.getTimeMillis());
        DropBoxManager.Entry e2 = dropbox.getNextEntry(null, e1.getTimeMillis());
        DropBoxManager.Entry e3 = dropbox.getNextEntry(null, e2.getTimeMillis());
        DropBoxManager.Entry e4 = dropbox.getNextEntry(null, e3.getTimeMillis());
        DropBoxManager.Entry e5 = dropbox.getNextEntry(null, e4.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry(null, e5.getTimeMillis()));
        assertEquals("TEST0", e0.getText(80));
        assertEquals("TEST5", e5.getText(80));

        e0.close();
        e1.close();
        e2.close();
        e3.close();
        e4.close();
        e5.close();

        // Limit to 3 files and add one more entry
        ContentResolver cr = getContext().getContentResolver();
        Settings.Global.putString(cr, Settings.Global.DROPBOX_MAX_FILES, "3");
        dropbox.addText("DropBoxTest", "TEST6");

        // Verify only 3 files left
        DropBoxManager.Entry f0 = dropbox.getNextEntry(null, 0);
        DropBoxManager.Entry f1 = dropbox.getNextEntry(null, f0.getTimeMillis());
        DropBoxManager.Entry f2 = dropbox.getNextEntry(null, f1.getTimeMillis());
        assertTrue(null == dropbox.getNextEntry(null, f2.getTimeMillis()));
        assertEquals("TEST4", f0.getText(80));
        assertEquals("TEST5", f1.getText(80));
        assertEquals("TEST6", f2.getText(80));

        f0.close();
        f1.close();
        f2.close();
    }

    public void testCreateDropBoxManagerWithInvalidDirectory() throws Exception {
        // If created with an invalid directory, the DropBoxManager should suffer quietly
        // and fail all operations (this is how it survives a full disk).
        // Once the directory becomes possible to create, it will start working.

        File dir = new File(getEmptyDir("testCreateDropBoxManagerWith"), "InvalidDirectory");
        new FileOutputStream(dir).close();  // Create an empty file
        DropBoxManagerService service = new DropBoxManagerService(getContext(), dir,
                Looper.getMainLooper());
        DropBoxManager dropbox = new DropBoxManager(getContext(), service.getServiceStub());

        dropbox.addText("DropBoxTest", "should be ignored");
        dropbox.addData("DropBoxTest", "should be ignored".getBytes(), 0);
        assertTrue(null == dropbox.getNextEntry("DropBoxTest", 0));

        dir.delete();  // Remove the file so a directory can be created
        dropbox.addText("DropBoxTest", "TEST");
        DropBoxManager.Entry e = dropbox.getNextEntry("DropBoxTest", 0);
        assertTrue(null == dropbox.getNextEntry("DropBoxTest", e.getTimeMillis()));
        assertEquals("DropBoxTest", e.getTag());
        assertEquals("TEST", e.getText(80));
        e.close();
    }

    public void testDropBoxEntrySerialization() throws Exception {
        // Make sure DropBoxManager.Entry can be serialized to a Parcel and back
        // under a variety of conditions.

        Parcel parcel = Parcel.obtain();
        File dir = getEmptyDir("testDropBoxEntrySerialization");

        new DropBoxManager.Entry("empty", 1000000).writeToParcel(parcel, 0);
        new DropBoxManager.Entry("string", 2000000, "String Value").writeToParcel(parcel, 0);
        new DropBoxManager.Entry("bytes", 3000000, "Bytes Value".getBytes(),
                DropBoxManager.IS_TEXT).writeToParcel(parcel, 0);
        new DropBoxManager.Entry("zerobytes", 4000000, new byte[0], 0).writeToParcel(parcel, 0);
        new DropBoxManager.Entry("emptybytes", 5000000, (byte[]) null,
                DropBoxManager.IS_EMPTY).writeToParcel(parcel, 0);

        try {
            new DropBoxManager.Entry("badbytes", 99999,
                    "Bad Bytes Value".getBytes(),
                    DropBoxManager.IS_EMPTY).writeToParcel(parcel, 0);
            fail("IllegalArgumentException expected for non-null byte[] and IS_EMPTY flags");
        } catch (IllegalArgumentException e) {
            // expected
        }

        try {
            new DropBoxManager.Entry("badbytes", 99999, (byte[]) null, 0).writeToParcel(parcel, 0);
            fail("IllegalArgumentException expected for null byte[] and non-IS_EMPTY flags");
        } catch (IllegalArgumentException e) {
            // expected
        }

        File f = new File(dir, "file.dat");
        FileOutputStream os = new FileOutputStream(f);
        os.write("File Value".getBytes());
        os.close();

        new DropBoxManager.Entry("file", 6000000, f, DropBoxManager.IS_TEXT).writeToParcel(
                parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        new DropBoxManager.Entry("binfile", 7000000, f, 0).writeToParcel(
                parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        new DropBoxManager.Entry("emptyfile", 8000000, (ParcelFileDescriptor) null,
                DropBoxManager.IS_EMPTY).writeToParcel(parcel, 0);

        try {
            new DropBoxManager.Entry("badfile", 99999, new File(dir, "nonexist.dat"), 0);
            fail("IOException expected for nonexistent file");
        } catch (IOException e) {
            // expected
        }

        try {
            new DropBoxManager.Entry("badfile", 99999, f, DropBoxManager.IS_EMPTY).writeToParcel(
                    parcel, 0);
            fail("IllegalArgumentException expected for non-null file and IS_EMPTY flags");
        } catch (IllegalArgumentException e) {
            // expected
        }

        try {
            new DropBoxManager.Entry("badfile", 99999, (ParcelFileDescriptor) null, 0);
            fail("IllegalArgumentException expected for null PFD and non-IS_EMPTY flags");
        } catch (IllegalArgumentException e) {
            // expected
        }

        File gz = new File(dir, "file.gz");
        GZIPOutputStream gzout = new GZIPOutputStream(new FileOutputStream(gz));
        gzout.write("Gzip File Value".getBytes());
        gzout.close();

        new DropBoxManager.Entry("gzipfile", 9000000, gz,
                DropBoxManager.IS_TEXT | DropBoxManager.IS_GZIPPED).writeToParcel(
                    parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        new DropBoxManager.Entry("gzipbinfile", 10000000, gz,
                DropBoxManager.IS_GZIPPED).writeToParcel(
                    parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);

        //
        // Switch from writing to reading
        //

        parcel.setDataPosition(0);
        DropBoxManager.Entry e;

        e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
        assertEquals("empty", e.getTag());
        assertEquals(1000000, e.getTimeMillis());
        assertEquals(DropBoxManager.IS_EMPTY, e.getFlags());
        assertEquals(null, e.getText(100));
        assertEquals(null, e.getInputStream());
        e.close();

        e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
        assertEquals("string", e.getTag());
        assertEquals(2000000, e.getTimeMillis());
        assertEquals(DropBoxManager.IS_TEXT, e.getFlags());
        assertEquals("String Value", e.getText(100));
        assertEquals("String Value",
                new BufferedReader(new InputStreamReader(e.getInputStream())).readLine());
        e.close();

        e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
        assertEquals("bytes", e.getTag());
        assertEquals(3000000, e.getTimeMillis());
        assertEquals(DropBoxManager.IS_TEXT, e.getFlags());
        assertEquals("Bytes Value", e.getText(100));
        e.close();

        e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
        assertEquals("zerobytes", e.getTag());
        assertEquals(4000000, e.getTimeMillis());
        assertEquals(0, e.getFlags());
        assertEquals(null, e.getText(100));
        assertEquals(null,
                new BufferedReader(new InputStreamReader(e.getInputStream())).readLine());
        e.close();

        e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
        assertEquals("emptybytes", e.getTag());
        assertEquals(5000000, e.getTimeMillis());
        assertEquals(DropBoxManager.IS_EMPTY, e.getFlags());
        assertEquals(null, e.getText(100));
        assertEquals(null, e.getInputStream());
        e.close();

        e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
        assertEquals("file", e.getTag());
        assertEquals(6000000, e.getTimeMillis());
        assertEquals(DropBoxManager.IS_TEXT, e.getFlags());
        assertEquals("File Value", e.getText(100));
        e.close();

        e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
        assertEquals("binfile", e.getTag());
        assertEquals(7000000, e.getTimeMillis());
        assertEquals(0, e.getFlags());
        assertEquals(null, e.getText(100));
        assertEquals("File Value",
                new BufferedReader(new InputStreamReader(e.getInputStream())).readLine());
        e.close();
        e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
        assertEquals("emptyfile", e.getTag());
        assertEquals(8000000, e.getTimeMillis());
        assertEquals(DropBoxManager.IS_EMPTY, e.getFlags());
        assertEquals(null, e.getText(100));
        assertEquals(null, e.getInputStream());
        e.close();

        e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
        assertEquals("gzipfile", e.getTag());
        assertEquals(9000000, e.getTimeMillis());
        assertEquals(DropBoxManager.IS_TEXT, e.getFlags());
        assertEquals("Gzip File Value", e.getText(100));
        e.close();

        e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
        assertEquals("gzipbinfile", e.getTag());
        assertEquals(10000000, e.getTimeMillis());
        assertEquals(0, e.getFlags());
        assertEquals(null, e.getText(100));
        assertEquals("Gzip File Value",
                new BufferedReader(new InputStreamReader(e.getInputStream())).readLine());
        e.close();
        assertEquals(0, parcel.dataAvail());
        parcel.recycle();
    }

    public void testDropBoxEntrySerializationDoesntLeakFileDescriptors() throws Exception {
        File dir = getEmptyDir("testDropBoxEntrySerialization");
        File f = new File(dir, "file.dat");
        FileOutputStream os = new FileOutputStream(f);
        os.write("File Value".getBytes());
        os.close();

        int before = countOpenFiles();
        assertTrue(before > 0);

        for (int i = 0; i < 1000; i++) {
            Parcel parcel = Parcel.obtain();
            new DropBoxManager.Entry("file", 1000000, f, 0).writeToParcel(
                    parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);

            parcel.setDataPosition(0);
            DropBoxManager.Entry e = DropBoxManager.Entry.CREATOR.createFromParcel(parcel);
            assertEquals("file", e.getTag());
            e.close();

            parcel.recycle();
        }

        int after = countOpenFiles();
        assertTrue(after > 0);
        assertTrue(after < before + 20);
    }

    public void testEntryFile() throws Exception {
        File fromDir = getEmptyDir("testEntryFile_from");
        File toDir = getEmptyDir("testEntryFile_to");

        {
            File f = new File(fromDir, "f0.txt");
            try (FileWriter w = new FileWriter(f)) {
                w.write("abc");
            }

            EntryFile e = new EntryFile(f, toDir, "tag:!", 12345, DropBoxManager.IS_TEXT, 1024);

            assertEquals("tag:!", e.tag);
            assertEquals(12345, e.timestampMillis);
            assertEquals(DropBoxManager.IS_TEXT, e.flags);
            assertEquals(1, e.blocks);

            assertFalse(f.exists()); // Because it should be renamed.

            assertTrue(e.hasFile());
            assertEquals(new File(toDir, "tag%3A!@12345.txt"), e.getFile(toDir));
            assertTrue(e.getFile(toDir).exists());
        }
        // Same test with gzip.
        {
            File f = new File(fromDir, "f0.txt.gz"); // It's a lie; it's not actually gz.
            try (FileWriter w = new FileWriter(f)) {
                w.write("abc");
            }

            EntryFile e = new EntryFile(f, toDir, "tag:!", 12345,
                    DropBoxManager.IS_TEXT | DropBoxManager.IS_GZIPPED, 1024);

            assertEquals("tag:!", e.tag);

            assertFalse(f.exists()); // Because it should be renamed.

            assertTrue(e.hasFile());
            assertEquals(new File(toDir, "tag%3A!@12345.txt.gz"), e.getFile(toDir));
            assertTrue(e.getFile(toDir).exists());

        }
        // binary, gzip.
        {
            File f = new File(fromDir, "f0.dat.gz"); // It's a lie; it's not actually gz.
            try (FileWriter w = new FileWriter(f)) {
                w.write("abc");
            }

            EntryFile e = new EntryFile(f, toDir, "tag:!", 12345,
                    DropBoxManager.IS_GZIPPED, 1024);

            assertEquals("tag:!", e.tag);

            assertFalse(f.exists()); // Because it should be renamed.

            assertTrue(e.hasFile());
            assertEquals(new File(toDir, "tag%3A!@12345.dat.gz"), e.getFile(toDir));
            assertTrue(e.getFile(toDir).exists());

        }

        // Tombstone.
        {
            EntryFile e = new EntryFile(toDir, "tag:!", 12345);

            assertEquals("tag:!", e.tag);
            assertEquals(12345, e.timestampMillis);
            assertEquals(DropBoxManager.IS_EMPTY, e.flags);
            assertEquals(0, e.blocks);

            assertTrue(e.hasFile());
            assertEquals(new File(toDir, "tag%3A!@12345.lost"), e.getFile(toDir));
            assertTrue(e.getFile(toDir).exists());
        }

        // From existing files.
        {
            File f = new File(fromDir, "tag%3A!@12345.dat");
            f.createNewFile();

            EntryFile e = new EntryFile(f, 1024);

            assertEquals("tag:!", e.tag);
            assertEquals(12345, e.timestampMillis);
            assertEquals(0, e.flags);
            assertEquals(0, e.blocks);

            assertTrue(f.exists());
        }
        {
            File f = new File(fromDir, "tag%3A!@12345.dat.gz");
            f.createNewFile();

            EntryFile e = new EntryFile(f, 1024);

            assertEquals("tag:!", e.tag);
            assertEquals(12345, e.timestampMillis);
            assertEquals(DropBoxManager.IS_GZIPPED, e.flags);
            assertEquals(0, e.blocks);

            assertTrue(f.exists());
        }
        {
            File f = new File(fromDir, "tag%3A!@12345.txt");
            try (FileWriter w = new FileWriter(f)) {
                w.write(new char[1024]);
            }

            EntryFile e = new EntryFile(f, 1024);

            assertEquals("tag:!", e.tag);
            assertEquals(12345, e.timestampMillis);
            assertEquals(DropBoxManager.IS_TEXT, e.flags);
            assertEquals(1, e.blocks);

            assertTrue(f.exists());
        }
        {
            File f = new File(fromDir, "tag%3A!@12345.txt.gz");
            try (FileWriter w = new FileWriter(f)) {
                w.write(new char[1025]);
            }

            EntryFile e = new EntryFile(f, 1024);

            assertEquals("tag:!", e.tag);
            assertEquals(12345, e.timestampMillis);
            assertEquals(DropBoxManager.IS_TEXT | DropBoxManager.IS_GZIPPED, e.flags);
            assertEquals(2, e.blocks);

            assertTrue(f.exists());
        }
        {
            File f = new File(fromDir, "tag%3A!@12345.lost");
            f.createNewFile();

            EntryFile e = new EntryFile(f, 1024);

            assertEquals("tag:!", e.tag);
            assertEquals(12345, e.timestampMillis);
            assertEquals(DropBoxManager.IS_EMPTY, e.flags);
            assertEquals(0, e.blocks);

            assertTrue(f.exists());
        }
        {
            File f = new File(fromDir, "@12345.dat"); // Empty tag -- this actually works.
            f.createNewFile();

            EntryFile e = new EntryFile(f, 1024);

            assertEquals("", e.tag);
            assertEquals(12345, e.timestampMillis);
            assertEquals(0, e.flags);
            assertEquals(0, e.blocks);

            assertTrue(f.exists());
        }
        // From invalid filenames.
        {
            File f = new File(fromDir, "tag.dat"); // No @.
            f.createNewFile();

            EntryFile e = new EntryFile(f, 1024);

            assertEquals(null, e.tag);
            assertEquals(0, e.timestampMillis);
            assertEquals(DropBoxManager.IS_EMPTY, e.flags);
            assertEquals(0, e.blocks);

            assertFalse(f.exists());
        }
        {
            File f = new File(fromDir, "tag@.dat"); // Invalid timestamp.
            f.createNewFile();

            EntryFile e = new EntryFile(f, 1024);

            assertEquals(null, e.tag);
            assertEquals(0, e.timestampMillis);
            assertEquals(DropBoxManager.IS_EMPTY, e.flags);
            assertEquals(0, e.blocks);

            assertFalse(f.exists());
        }
        {
            File f = new File(fromDir, "tag@12345.daxt"); // Invalid extension.
            f.createNewFile();

            EntryFile e = new EntryFile(f, 1024);

            assertEquals(null, e.tag);
            assertEquals(0, e.timestampMillis);
            assertEquals(DropBoxManager.IS_EMPTY, e.flags);
            assertEquals(0, e.blocks);

            assertFalse(f.exists());
        }
    }

    public void testCompareEntries() {
        File dir = getEmptyDir("testCompareEntries");
        assertEquals(-1,
                new EntryFile(new File(dir, "aaa@100.dat"), 1).compareTo(
                new EntryFile(new File(dir, "bbb@200.dat"), 1)));
        assertEquals(1,
                new EntryFile(new File(dir, "aaa@200.dat"), 1).compareTo(
                new EntryFile(new File(dir, "bbb@100.dat"), 1)));
        assertEquals(-1,
                new EntryFile(new File(dir, "aaa@100.dat"), 1).compareTo(
                new EntryFile(new File(dir, "bbb@100.dat"), 1)));
        assertEquals(1,
                new EntryFile(new File(dir, "bbb@100.dat"), 1).compareTo(
                new EntryFile(new File(dir, "aaa@100.dat"), 1)));
    }

    private void addRandomEntry(DropBoxManager dropbox, String tag, int size) throws Exception {
        byte[] bytes = new byte[size];
        new Random(System.currentTimeMillis()).nextBytes(bytes);

        File f = new File(getEmptyDir("addRandomEntry"), "random.dat");
        FileOutputStream os = new FileOutputStream(f);
        os.write(bytes);
        os.close();

        dropbox.addFile(tag, f, 0);
    }

    private int getEntrySize(DropBoxManager.Entry e) throws Exception {
        InputStream is = e.getInputStream();
        if (is == null) return -1;
        int length = 0;
        while (is.read() != -1) length++;
        return length;
    }

    private void recursiveDelete(File file) {
        if (!file.delete() && file.isDirectory()) {
            for (File f : file.listFiles()) recursiveDelete(f);
            file.delete();
        }
    }

    private File getEmptyDir(String name) {
        File dir = getContext().getDir("DropBoxTest." + name, 0);
        for (File f : dir.listFiles()) recursiveDelete(f);
        assertTrue(dir.listFiles().length == 0);
        return dir;
    }

    private int countOpenFiles() {
        return new File("/proc/" + Process.myPid() + "/fd").listFiles().length;
    }
}
