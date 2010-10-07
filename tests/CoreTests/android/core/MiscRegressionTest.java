/*
 * Copyright (C) 2008 The Android Open Source Project
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

import junit.framework.Assert;
import junit.framework.TestCase;

import java.io.File;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.LargeTest;

public class MiscRegressionTest extends TestCase {

    // Regression test for #857840: want JKS key store
    @SmallTest
    public void testDefaultKeystore() {
        String type = KeyStore.getDefaultType();
        Assert.assertEquals("Default keystore type must be Bouncy Castle", "BKS", type);

        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            Assert.assertNotNull("Keystore must not be null", store);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        
        try {
            KeyStore store = KeyStore.getInstance("BKS");
            Assert.assertNotNull("Keystore must not be null", store);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // Regression test for #951285: Suitable LogHandler should be chosen
    // depending on the environment.
    @MediumTest
    public void testAndroidLogHandler() throws Exception {
        Logger.global.severe("This has logging Level.SEVERE, should become ERROR");
        Logger.global.warning("This has logging Level.WARNING, should become WARN");
        Logger.global.info("This has logging Level.INFO, should become INFO");
        Logger.global.config("This has logging Level.CONFIG, should become DEBUG");
        Logger.global.fine("This has logging Level.FINE, should become VERBOSE");
        Logger.global.finer("This has logging Level.FINER, should become VERBOSE");
        Logger.global.finest("This has logging Level.FINEST, should become VERBOSE");
    }

    // Regression test for Issue 5697:
    // getContextClassLoader returns a non-application classloader
    // http://code.google.com/p/android/issues/detail?id=5697
    //
    @MediumTest
    public void testJavaContextClassLoader() throws Exception {
        Assert.assertNotNull("Must hava a Java context ClassLoader",
                             Thread.currentThread().getContextClassLoader());
    }

    // Regression test for #1045939: Different output for Method.toString()
    @SmallTest
    public void testMethodToString() {
        try {
            Method m1 = Object.class.getMethod("notify", new Class[] { });
            Method m2 = Object.class.getMethod("toString", new Class[] { });
            Method m3 = Object.class.getMethod("wait", new Class[] { long.class, int.class });
            Method m4 = Object.class.getMethod("equals", new Class[] { Object.class });
            Method m5 = String.class.getMethod("valueOf", new Class[] { char[].class });
            Method m6 = Runtime.class.getMethod("exec", new Class[] { String[].class });

            assertEquals("Method.toString() must match expectations",
                    "public final native void java.lang.Object.notify()",
                    m1.toString());
            
            assertEquals("Method.toString() must match expectations",
                    "public java.lang.String java.lang.Object.toString()",
                    m2.toString());

            assertEquals("Method.toString() must match expectations",
                    "public final native void java.lang.Object.wait(long,int) throws java.lang.InterruptedException",
                    m3.toString());

            assertEquals("Method.toString() must match expectations",
                    "public boolean java.lang.Object.equals(java.lang.Object)",
                    m4.toString());

            assertEquals("Method.toString() must match expectations",
                    "public static java.lang.String java.lang.String.valueOf(char[])",
                    m5.toString());

            assertEquals("Method.toString() must match expectations",
                    "public java.lang.Process java.lang.Runtime.exec(java.lang.String[]) throws java.io.IOException",
                    m6.toString());

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

    }

    // Regression test for #1062200: Enum fails to deserialize. Actual problem
    // was that Class.isEnum() erroneously returned true for indirect
    // descendants of Enum.
    enum TrafficLights {
        RED,
        YELLOW {},
        GREEN {
            @SuppressWarnings("unused")
            int i;
            @SuppressWarnings("unused")
            void foobar() {}
        };
    }
    
    @SmallTest
    public void testClassIsEnum() {
        Class<?> trafficClass = TrafficLights.class;
        
        Class<?> redClass = TrafficLights.RED.getClass();
        Class<?> yellowClass = TrafficLights.YELLOW.getClass();
        Class<?> greenClass = TrafficLights.GREEN.getClass();
        
        Assert.assertSame("Classes must be equal", trafficClass, redClass);
        Assert.assertNotSame("Classes must be different", trafficClass, yellowClass);
        Assert.assertNotSame("Classes must be different", trafficClass, greenClass);
        Assert.assertNotSame("Classes must be different", yellowClass, greenClass);
        
        Assert.assertTrue("Must be an enum", trafficClass.isEnum());
        Assert.assertTrue("Must be an enum", redClass.isEnum());
        Assert.assertFalse("Must not be an enum", yellowClass.isEnum());
        Assert.assertFalse("Must not be an enum", greenClass.isEnum());
        
        Assert.assertNotNull("Must have enum constants", trafficClass.getEnumConstants());
        Assert.assertNull("Must not have enum constants", yellowClass.getEnumConstants());
        Assert.assertNull("Must not have enum constants", greenClass.getEnumConstants());
    }
    
    // Regression test for #1046174: JarEntry.getCertificates() is really slow.
    public void checkJarCertificates(File file) {
        try {
            JarFile jarFile = new JarFile(file);
            JarEntry je = jarFile.getJarEntry("AndroidManifest.xml");
            byte[] readBuffer = new byte[1024];
            
            long t0 = System.currentTimeMillis();
            
            // We must read the stream for the JarEntry to retrieve
            // its certificates.
            InputStream is = jarFile.getInputStream(je);
            while (is.read(readBuffer, 0, readBuffer.length) != -1) {
                // not using
            }
            is.close();
            Certificate[] certs = je != null ? je.getCertificates() : null;

            long t1 = System.currentTimeMillis();
            android.util.Log.d("TestHarness", "loadCertificates() took " + (t1 - t0) + " ms");
            if (certs == null) {
                android.util.Log.d("TestHarness", "We have no certificates");
            } else {
                android.util.Log.d("TestHarness", "We have " + certs.length + " certificates");
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    
    @LargeTest
    public void testJarCertificates() {
        File[] files = new File("/system/app").listFiles();
        for (int i = 0; i < files.length; i++) {
            checkJarCertificates(files[i]);
        }
    }
    
    // Regression test for #1120750: Reflection for static long fields is broken
    private static final long MY_LONG = 5073258162644648461L;
    
    @SmallTest
    public void testLongFieldReflection() {
        try {
            Field field = getClass().getDeclaredField("MY_LONG");
            assertEquals(5073258162644648461L, field.getLong(null));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // Regression test for Harmony LinkedHashMap bug. Copied from core, just
    // to make sure it doesn't get lost.
    @SmallTest
    public void testLinkedHashMap() {
        // we want to test the LinkedHashMap in access ordering mode.
        LinkedHashMap map = new LinkedHashMap<String, String>(10, 0.75f, true);

        map.put("key1", "value1");
        map.put("key2", "value2");
        map.put("key3", "value3");

        Iterator iterator = map.keySet().iterator();
        String id = (String) iterator.next();
        map.get(id);
        try {
            iterator.next();
            // A LinkedHashMap is supposed to throw this Exception when a 
            // iterator.next() Operation takes place after a get
            // Operation. This is because the get Operation is considered
            // a structural modification if the LinkedHashMap is in
            // access order mode.
            fail("expected ConcurrentModificationException was not thrown.");
        } catch(ConcurrentModificationException e) {
            // expected
        }
        
        LinkedHashMap mapClone = (LinkedHashMap) map.clone();
        
        iterator = map.keySet().iterator();
        id = (String) iterator.next();
        mapClone.get(id);
        try {
            iterator.next();
        } catch(ConcurrentModificationException e) {
            fail("expected ConcurrentModificationException was not thrown.");
        }
    }
    
    // Regression test for #1212257: Boot-time package scan is slow. Not
    // expected to fail. Please see log if you are interested in the results.
    @LargeTest
    public void testZipStressManifest() {
        android.util.Log.d("MiscRegressionTest", "ZIP stress test started");
        
        long time0 = System.currentTimeMillis();
        
        try {
            File[] files = new File("/system/app").listFiles();

            byte[] buffer = new byte[512];
            
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    android.util.Log.d("MiscRegressionTest",
                            "ZIP stress test processing " + files[i] + "...");
                    
                    ZipFile zip = new ZipFile(files[i]);
                    
                    ZipEntry entry = zip.getEntry("AndroidManifest.xml");
                    InputStream stream = zip.getInputStream(entry);
                    
                    int j = stream.read(buffer);
                    while (j != -1) {
                        j = stream.read(buffer);
                    }
                    
                    stream.close();
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        
        long time1 = System.currentTimeMillis();
        
        android.util.Log.d("MiscRegressionTest", "ZIP stress test finished, " +
                "time was " + (time1- time0) + "ms");
    }

    @LargeTest
    public void testZipStressAllFiles() {
        android.util.Log.d("MiscRegressionTest", "ZIP stress test started");
        
        long time0 = System.currentTimeMillis();
        
        try {
            File[] files = new File("/system/app").listFiles();

            byte[] buffer = new byte[512];
            
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    android.util.Log.d("MiscRegressionTest",
                            "ZIP stress test processing " + files[i] + "...");
                    
                    ZipFile zip = new ZipFile(files[i]);
                    
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        InputStream stream = zip.getInputStream(entries.nextElement());
                        
                        int j = stream.read(buffer);
                        while (j != -1) {
                            j = stream.read(buffer);
                        }
                        
                        stream.close();
                    }
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        
        long time1 = System.currentTimeMillis();
        
        android.util.Log.d("MiscRegressionTest", "ZIP stress test finished, " +
                "time was " + (time1- time0) + "ms");
    }

    @SmallTest
    public void testOsEncodingProperty() {
        long time0 = System.currentTimeMillis();
        String[] files = new File("/system/app").list();
        long time1 = System.currentTimeMillis();
        android.util.Log.d("MiscRegressionTest", "File.list() test finished, " +
                "time was " + (time1- time0) + "ms");
    }

    // -------------------------------------------------------------------------
    // Regression test for #1185084: Native memory allocated by
    // java.util.zip.Deflater in system_server. The fix reduced some internal
    // ZLIB buffers in size, so this test is trying to execute a lot of
    // deflating to ensure that things are still working properly.
    private void assertEquals(byte[] a, byte[] b) {
        assertEquals("Arrays must have same length", a.length, b.length);
        
        for (int i = 0; i < a.length; i++) {
            assertEquals("Array elements #" + i + " must be equal", a[i], b[i]);
        }
    }
    
    @LargeTest
    public void testZipDeflateInflateStress() {
        
        final int DATA_SIZE = 16384;

        Random random = new Random(42); // Seed makes test reproducible
        
        try {
            // Outer loop selects "mode" of test.
            for (int j = 1; j <=2 ; j++) {

                byte[] input = new byte[DATA_SIZE];
                
                if (j == 1) {
                    // Totally random content
                    random.nextBytes(input);
                } else {
                    // Random contents with longer repetitions
                    int pos = 0;
                    while (pos < input.length) {
                        byte what = (byte)random.nextInt(256);
                        int howMany = random.nextInt(32);
                        if (pos + howMany >= input.length) {
                            howMany = input.length - pos;
                        }
                        Arrays.fill(input, pos, pos + howMany, what);
                        pos += howMany;
                    }
                }
                
                // Inner loop tries all 9 compression levels.
                for (int i = 1; i <= 9; i++) {
                    android.util.Log.d("MiscRegressionTest", "ZipDeflateInflateStress test (" + j + "," + i + ")...");
                    
                    byte[] zipped = new byte[2 * DATA_SIZE]; // Just to make sure...
                    
                    Deflater deflater = new Deflater(i);
                    deflater.setInput(input);
                    deflater.finish();
                    
                    deflater.deflate(zipped);
                    
                    byte[] output = new byte[DATA_SIZE];
                    
                    Inflater inflater = new Inflater();
                    inflater.setInput(zipped);
                    inflater.finished();
    
                    inflater.inflate(output);
                    
                    assertEquals(input, output);
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    
    // -------------------------------------------------------------------------
    // Regression test for #1252043: Thread.getStackTrace() is broken
    class MyThread extends Thread {
        public MyThread(String name) {
            super(name);
        }
        
        @Override
        public void run() {
            doSomething();
        }
        
        public void doSomething() {
            for (int i = 0; i < 20;) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                }
            }
        }
    }

    class MyOtherThread extends Thread {
        public int visibleTraces;
        
        public MyOtherThread(ThreadGroup group, String name) {
            super(group, name);
        }
        
        @Override
        public void run() {
            visibleTraces = Thread.getAllStackTraces().size();
        }
    }
    
    @LargeTest
    public void testThreadGetStackTrace() {
        MyThread t1 = new MyThread("t1");
        t1.start();
        
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
        }

        StackTraceElement[] traces = t1.getStackTrace();
        StackTraceElement trace = traces[traces.length - 2];
        
        // Expect to find MyThread.doSomething in the trace
        assertTrue("Must find MyThread.doSomething in trace",
                trace.getClassName().endsWith("$MyThread") && 
                trace.getMethodName().equals("doSomething"));

        ThreadGroup g1 = new ThreadGroup("1");
        MyOtherThread t2 = new MyOtherThread(g1, "t2");
        t2.start();
        try {
            t2.join();
        } catch (InterruptedException ex) {
        }
        
        // Expect to see the traces of all threads (not just t2)
        assertTrue("Must have traces for all threads", t2.visibleTraces > 1);
    }
}
