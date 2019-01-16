/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.net;

import android.test.MoreAsserts;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

import java.io.FileDescriptor;
import java.io.IOException;

public class LocalSocketTest extends TestCase {

    @SmallTest
    public void testBasic() throws Exception {
        LocalServerSocket ss;
        LocalSocket ls;
        LocalSocket ls1;

        ss = new LocalServerSocket("android.net.LocalSocketTest");

        ls = new LocalSocket();

        try {
            ls.connect(new LocalSocketAddress(null));
            fail("Expected NullPointerException");
        } catch (NullPointerException e) {
            // pass
        }

        try {
            ls.bind(new LocalSocketAddress(null));
            fail("Expected NullPointerException");
        } catch (NullPointerException e) {
            // pass
        }

        ls.connect(new LocalSocketAddress("android.net.LocalSocketTest"));

        ls1 = ss.accept();

        // Test trivial read and write
        ls.getOutputStream().write(42);

        assertEquals(42, ls1.getInputStream().read());

        // Test getting credentials
        Credentials c = ls1.getPeerCredentials();

        MoreAsserts.assertNotEqual(0, c.getPid());

        // Test sending and receiving file descriptors
        ls.setFileDescriptorsForSend(
                new FileDescriptor[]{FileDescriptor.in});

        ls.getOutputStream().write(42);

        assertEquals(42, ls1.getInputStream().read());

        FileDescriptor[] out = ls1.getAncillaryFileDescriptors();

        assertEquals(1, out.length);

        // Test multible byte write and available()
        ls1.getOutputStream().write(new byte[]{0, 1, 2, 3, 4, 5}, 1, 5);

        assertEquals(1, ls.getInputStream().read());
        assertEquals(4, ls.getInputStream().available());

        byte[] buffer = new byte[16];
        int countRead;

        countRead = ls.getInputStream().read(buffer, 1, 15);

        assertEquals(4, countRead);
        assertEquals(2, buffer[1]);
        assertEquals(3, buffer[2]);
        assertEquals(4, buffer[3]);
        assertEquals(5, buffer[4]);

        // Try various array-out-of-bound cases
        try {
            ls.getInputStream().read(buffer, 1, 16);
            fail("expected exception");
        } catch (ArrayIndexOutOfBoundsException ex) {
            // excpected
        }

        try {
            ls.getOutputStream().write(buffer, 1, 16);
            fail("expected exception");
        } catch (ArrayIndexOutOfBoundsException ex) {
            // excpected
        }

        try {
            ls.getOutputStream().write(buffer, -1, 15);
            fail("expected exception");
        } catch (ArrayIndexOutOfBoundsException ex) {
            // excpected
        }

        try {
            ls.getOutputStream().write(buffer, 0, -1);
            fail("expected exception");
        } catch (ArrayIndexOutOfBoundsException ex) {
            // excpected
        }

        try {
            ls.getInputStream().read(buffer, -1, 15);
            fail("expected exception");
        } catch (ArrayIndexOutOfBoundsException ex) {
            // excpected
        }

        try {
            ls.getInputStream().read(buffer, 0, -1);
            fail("expected exception");
        } catch (ArrayIndexOutOfBoundsException ex) {
            // excpected
        }

        // Try read of length 0
        ls.getOutputStream().write(42);
        countRead = ls1.getInputStream().read(buffer, 0, 0);
        assertEquals(0, countRead);
        assertEquals(42, ls1.getInputStream().read());

        ss.close();

        ls.close();

        // Try write on closed socket

        try {
            ls.getOutputStream().write(42);
            fail("expected exception");
        } catch (IOException ex) {
            // Expected
        }

        // Try read on closed socket

        try {
            ls.getInputStream().read();
            fail("expected exception");
        } catch (IOException ex) {
            // Expected
        }

        // Try write on socket whose peer has closed

        try {
            ls1.getOutputStream().write(42);
            fail("expected exception");
        } catch (IOException ex) {
            // Expected
        }

        // Try read on socket whose peer has closed

        assertEquals(-1, ls1.getInputStream().read());

        ls1.close();
    }
}
