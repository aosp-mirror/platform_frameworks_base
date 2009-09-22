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

package android.core;

import junit.framework.TestCase;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

class Fibonacci {
    int n1 = -1;
    int n2;

    public int next() {
        if (n1 < 0) {
            n1 = 0;
            return 0;
        } else if (n1 == 0) {
            n2 = 0;
            n1 = 1;
            return 1;
        } else {
            int ret = n1 + n2;
            n2 = n1;
            n1 = ret;
            return ret;
        }
    }
}


public class PipedStreamTest extends TestCase {

    private abstract static class TestThread extends Thread {
        public abstract void runTest() throws Exception;

        public final void run() {
            try {
                runTest();
            } catch (Throwable e) {
                android.util.Log.e("PST", "Got exception " + e, e);
                android.util.Log.e("PST", android.util.Log.getStackTraceString(e));
                exception = e;
            }
        }

        Throwable exception;
        int countRead = 0;
    }

    @MediumTest
    public void testA() throws Exception {

        final PipedInputStream in = new PipedInputStream();
        final PipedOutputStream out = new PipedOutputStream(in);

        assertEquals(0, in.available());

        TestThread reader, writer;

        reader = new TestThread() {
            Fibonacci fib = new Fibonacci();

            @Override
            public void runTest() throws Exception {
                int readInt;
                byte readByte;

                for (; ;) {
                    readInt = in.read();

                    if (readInt == -1) {
                        return;
                    }

                    readByte = (byte) readInt;
                    assertEquals(readByte, (byte) fib.next());
                    countRead++;
                }
            }
        };

        reader.start();

        writer = new TestThread() {
            Fibonacci fib = new Fibonacci();

            @Override
            public void runTest() throws Exception {
                for (int i = 0; i < 2000; i++) {
                    int toWrite = fib.next();
                    out.write(toWrite);
                }
                out.close();
            }
        };

        writer.start();


        for (; ;) {
            try {
                reader.join(60 * 1000);
                writer.join(1000);
                break;
            } catch (InterruptedException ex) {
            }
        }

        assertEquals(2000, reader.countRead);

        if (writer.exception != null) {
            throw new Exception(writer.exception);
        }
        if (reader.exception != null) {
            throw new Exception(reader.exception);
        }
    }

    @MediumTest
    public void testB() throws Exception {
        final PipedInputStream in = new PipedInputStream();
        final PipedOutputStream out = new PipedOutputStream(in);

        assertEquals(0, in.available());

        TestThread reader, writer;

        reader = new TestThread() {
            Fibonacci fib = new Fibonacci();

            @Override
            public void runTest() throws Exception {
                byte readBytes[] = new byte[5];
                int ret;

                for (; ;) {
                    int nread = 0;
                    while (nread < 5) {
                        ret = in.read(readBytes, nread, readBytes.length - nread);

                        if (ret == -1) {
                            return;
                        }
                        nread += ret;
                    }

                    assertEquals(5, nread);

                    int readInt = (((int) readBytes[0] & 0xff) << 24)
                            | (((int) readBytes[1] & 0xff) << 16)
                            | (((int) readBytes[2] & 0xff) << 8)
                            | (((int) readBytes[3] & 0xff));


                    assertEquals("Error at " + countRead, fib.next(), readInt);
                    assertEquals("Error at " + countRead, 0, readBytes[4]);
                    countRead++;
                }
            }
        };

        reader.start();

        writer = new TestThread() {
            Fibonacci fib = new Fibonacci();

            @Override
            public void runTest() throws Exception {
                byte writeBytes[] = new byte[5];
                for (int i = 0; i < 2000; i++) {
                    int toWrite = fib.next();
                    writeBytes[0] = (byte) (toWrite >> 24);
                    writeBytes[1] = (byte) (toWrite >> 16);
                    writeBytes[2] = (byte) (toWrite >> 8);
                    writeBytes[3] = (byte) (toWrite);
                    writeBytes[4] = 0;
                    out.write(writeBytes, 0, writeBytes.length);
                }
                out.close();
            }
        };

        writer.start();


        for (; ;) {
            try {
                reader.join(60 * 1000);
                writer.join(1000);
                break;
            } catch (InterruptedException ex) {
            }
        }

        if (reader.exception != null) {
            throw new Exception(reader.exception);
        }
        if (writer.exception != null) {
            throw new Exception(writer.exception);
        }

        assertEquals(2000, reader.countRead);
    }

    @SmallTest
    public void testC() throws Exception {
        final PipedInputStream in = new PipedInputStream();
        final PipedOutputStream out = new PipedOutputStream(in);
        final byte readBytes[] = new byte[1024 * 2];

        assertEquals(0, in.available());

        TestThread reader, writer;

        reader = new TestThread() {
            @Override
            public void runTest() throws Exception {
                int ret;

                for (; ;) {
                    int nread = 0;
                    while (nread < readBytes.length) {
                        ret = in.read(readBytes, nread, readBytes.length - nread);

                        if (ret == -1) {
                            return;
                        }
                        nread += ret;
                    }
                }
            }
        };

        reader.start();

        writer = new TestThread() {
            Fibonacci fib = new Fibonacci();

            @Override
            public void runTest() throws Exception {
                byte writeBytes[] = new byte[1024 * 2];
                for (int i = 0; i < (writeBytes.length - 4); i += 4) {
                    int toWrite = fib.next();
                    writeBytes[i    ] = (byte) (toWrite >> 24);
                    writeBytes[i + 1] = (byte) (toWrite >> 16);
                    writeBytes[i + 2] = (byte) (toWrite >> 8);
                    writeBytes[i + 3] = (byte) (toWrite);
                }
                out.write(writeBytes, 0, writeBytes.length);
                out.close();
            }
        };

        writer.start();


        for (; ;) {
            try {
                reader.join(60 * 1000);
                writer.join(1000);
                break;
            } catch (InterruptedException ex) {
            }
        }

        if (reader.exception != null) {
            throw new Exception(reader.exception);
        }
        if (writer.exception != null) {
            throw new Exception(writer.exception);
        }

        Fibonacci fib = new Fibonacci();
        for (int i = 0; i < (readBytes.length - 4); i += 4) {
            int readInt = (((int) readBytes[i] & 0xff) << 24)
                    | (((int) readBytes[i + 1] & 0xff) << 16)
                    | (((int) readBytes[i + 2] & 0xff) << 8)
                    | (((int) readBytes[i + 3] & 0xff));

            assertEquals("Error at " + i, readInt, fib.next());
        }
    }
}
