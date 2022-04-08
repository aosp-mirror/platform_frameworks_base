/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.os;

import android.os.FileBridge.FileBridgeOutputStream;
import android.test.AndroidTestCase;
import android.test.MoreAsserts;

import libcore.io.Streams;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class FileBridgeTest extends AndroidTestCase {

    private File file;
    private FileOutputStream fileOs;
    private FileBridge bridge;
    private FileBridgeOutputStream client;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        file = getContext().getFileStreamPath("meow.dat");
        file.delete();

        fileOs = new FileOutputStream(file);

        bridge = new FileBridge();
        bridge.setTargetFile(fileOs.getFD());
        bridge.start();
        client = new FileBridgeOutputStream(bridge.getClientSocket());
    }

    @Override
    protected void tearDown() throws Exception {
        fileOs.close();
        file.delete();
    }

    private void assertOpen() throws Exception {
        assertFalse("expected open", bridge.isClosed());
    }

    private void closeAndAssertClosed() throws Exception {
        client.close();

        // Wait a beat for things to settle down
        SystemClock.sleep(200);
        assertTrue("expected closed", bridge.isClosed());
    }

    private void assertContents(byte[] expected) throws Exception {
        MoreAsserts.assertEquals(expected, Streams.readFully(new FileInputStream(file)));
    }

    public void testNoWriteNoSync() throws Exception {
        assertOpen();
        closeAndAssertClosed();
    }

    public void testNoWriteSync() throws Exception {
        assertOpen();
        client.flush();
        closeAndAssertClosed();
    }

    public void testWriteNoSync() throws Exception {
        assertOpen();
        client.write("meow".getBytes(StandardCharsets.UTF_8));
        closeAndAssertClosed();
        assertContents("meow".getBytes(StandardCharsets.UTF_8));
    }

    public void testWriteSync() throws Exception {
        assertOpen();
        client.write("cake".getBytes(StandardCharsets.UTF_8));
        client.flush();
        closeAndAssertClosed();
        assertContents("cake".getBytes(StandardCharsets.UTF_8));
    }

    public void testWriteSyncWrite() throws Exception {
        assertOpen();
        client.write("meow".getBytes(StandardCharsets.UTF_8));
        client.flush();
        client.write("cake".getBytes(StandardCharsets.UTF_8));
        closeAndAssertClosed();
        assertContents("meowcake".getBytes(StandardCharsets.UTF_8));
    }

    public void testEmptyWrite() throws Exception {
        assertOpen();
        client.write(new byte[0]);
        closeAndAssertClosed();
        assertContents(new byte[0]);
    }

    public void testWriteAfterClose() throws Exception {
        assertOpen();
        client.write("meow".getBytes(StandardCharsets.UTF_8));
        closeAndAssertClosed();
        try {
            client.write("cake".getBytes(StandardCharsets.UTF_8));
            fail("wrote after close!");
        } catch (IOException expected) {
        }
        assertContents("meow".getBytes(StandardCharsets.UTF_8));
    }

    public void testRandomWrite() throws Exception {
        final Random r = new Random();
        final ByteArrayOutputStream result = new ByteArrayOutputStream();

        for (int i = 0; i < 512; i++) {
            final byte[] test = new byte[r.nextInt(24169)];
            r.nextBytes(test);
            result.write(test);
            client.write(test);
            client.flush();
        }

        closeAndAssertClosed();
        assertContents(result.toByteArray());
    }

    public void testGiantWrite() throws Exception {
        final byte[] test = new byte[263401];
        new Random().nextBytes(test);

        assertOpen();
        client.write(test);
        closeAndAssertClosed();
        assertContents(test);
    }
}
