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

package com.android.tools.layoutlib.create;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;

public class DelegateClassAdapterTest {

    private MockLog mLog;

    private static final String CLASS_NAME =
        DelegateClassAdapterTest.class.getCanonicalName() + "$" +
        ClassWithNative.class.getSimpleName();

    @Before
    public void setUp() throws Exception {
        mLog = new MockLog();
        mLog.setVerbose(true); // capture debug error too
    }

    /**
     * Tests that a class not being modified still works.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testNoOp() throws Exception {
        // create an instance of the class that will be modified
        // (load the class in a distinct class loader so that we can trash its definition later)
        ClassLoader cl1 = new ClassLoader(this.getClass().getClassLoader()) { };
        Class<ClassWithNative> clazz1 = (Class<ClassWithNative>) cl1.loadClass(CLASS_NAME);
        ClassWithNative instance1 = clazz1.newInstance();
        assertEquals(42, instance1.add(20, 22));
        try {
            instance1.callNativeInstance(10, 3.1415, new Object[0] );
            fail("Test should have failed to invoke callTheNativeMethod [1]");
        } catch (UnsatisfiedLinkError e) {
            // This is expected to fail since the native method is not implemented.
        }

        // Now process it but tell the delegate to not modify any method
        ClassWriter cw = new ClassWriter(0 /*flags*/);

        HashSet<String> delegateMethods = new HashSet<String>();
        String internalClassName = CLASS_NAME.replace('.', '/');
        DelegateClassAdapter cv = new DelegateClassAdapter(
                mLog, cw, internalClassName, delegateMethods);

        ClassReader cr = new ClassReader(CLASS_NAME);
        cr.accept(cv, 0 /* flags */);

        // Load the generated class in a different class loader and try it again
        final byte[] bytes = cw.toByteArray();

        ClassLoader2 cl2 = new ClassLoader2(bytes) {
            @Override
            public void testModifiedInstance() throws Exception {
                Class<?> clazz2 = loadClass(CLASS_NAME);
                Object i2 = clazz2.newInstance();
                assertNotNull(i2);
                assertEquals(42, callAdd(i2, 20, 22));

                try {
                    callCallNativeInstance(i2, 10, 3.1415, new Object[0]);
                    fail("Test should have failed to invoke callTheNativeMethod [2]");
                } catch (InvocationTargetException e) {
                    // This is expected to fail since the native method has NOT been
                    // overridden here.
                    assertEquals(UnsatisfiedLinkError.class, e.getCause().getClass());
                }

                // Check that the native method does NOT have the new annotation
                Method[] m = clazz2.getDeclaredMethods();
                assertEquals("native_instance", m[2].getName());
                assertTrue(Modifier.isNative(m[2].getModifiers()));
                Annotation[] a = m[2].getAnnotations();
                assertEquals(0, a.length);
            }
        };
        cl2.testModifiedInstance();
    }

    /**
     * {@link DelegateMethodAdapter} does not support overriding constructors yet,
     * so this should fail with an {@link UnsupportedOperationException}.
     *
     * Although not tested here, the message of the exception should contain the
     * constructor signature.
     */
    @Test(expected=UnsupportedOperationException.class)
    public void testConstructorsNotSupported() throws IOException {
        ClassWriter cw = new ClassWriter(0 /*flags*/);

        String internalClassName = CLASS_NAME.replace('.', '/');

        HashSet<String> delegateMethods = new HashSet<String>();
        delegateMethods.add("<init>");
        DelegateClassAdapter cv = new DelegateClassAdapter(
                mLog, cw, internalClassName, delegateMethods);

        ClassReader cr = new ClassReader(CLASS_NAME);
        cr.accept(cv, 0 /* flags */);
    }

    @Test
    public void testDelegateNative() throws Exception {
        ClassWriter cw = new ClassWriter(0 /*flags*/);
        String internalClassName = CLASS_NAME.replace('.', '/');

        HashSet<String> delegateMethods = new HashSet<String>();
        delegateMethods.add(DelegateClassAdapter.ALL_NATIVES);
        DelegateClassAdapter cv = new DelegateClassAdapter(
                mLog, cw, internalClassName, delegateMethods);

        ClassReader cr = new ClassReader(CLASS_NAME);
        cr.accept(cv, 0 /* flags */);

        // Load the generated class in a different class loader and try it
        final byte[] bytes = cw.toByteArray();

        try {
            ClassLoader2 cl2 = new ClassLoader2(bytes) {
                @Override
                public void testModifiedInstance() throws Exception {
                    Class<?> clazz2 = loadClass(CLASS_NAME);
                    Object i2 = clazz2.newInstance();
                    assertNotNull(i2);

                    // Use reflection to access inner methods
                    assertEquals(42, callAdd(i2, 20, 22));

                     Object[] objResult = new Object[] { null };
                     int result = callCallNativeInstance(i2, 10, 3.1415, objResult);
                     assertEquals((int)(10 + 3.1415), result);
                     assertSame(i2, objResult[0]);

                     // Check that the native method now has the new annotation and is not native
                     Method[] m = clazz2.getDeclaredMethods();
                     assertEquals("native_instance", m[2].getName());
                     assertFalse(Modifier.isNative(m[2].getModifiers()));
                     Annotation[] a = m[2].getAnnotations();
                     assertEquals("LayoutlibDelegate", a[0].annotationType().getSimpleName());
                }
            };
            cl2.testModifiedInstance();

        } catch (Throwable t) {
            // For debugging, dump the bytecode of the class in case of unexpected error.
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            TraceClassVisitor tcv = new TraceClassVisitor(pw);

            ClassReader cr2 = new ClassReader(bytes);
            cr2.accept(tcv, 0 /* flags */);

            String msg = "\n" + t.getClass().getCanonicalName();
            if (t.getMessage() != null) {
                msg += ": " + t.getMessage();
            }
            msg = msg + "\nBytecode dump:\n" + sw.toString();

            // Re-throw exception with new message
            RuntimeException ex = new RuntimeException(msg, t);
            throw ex;
        }
    }

    //-------

    /**
     * A class loader than can define and instantiate our dummy {@link ClassWithNative}.
     * <p/>
     * The trick here is that this class loader will test our modified version of ClassWithNative.
     * Trying to do so in the original class loader generates all sort of link issues because
     * there are 2 different definitions of the same class name. This class loader will
     * define and load the class when requested by name and provide helpers to access the
     * instance methods via reflection.
     */
    private abstract class ClassLoader2 extends ClassLoader {
        private final byte[] mClassWithNative;

        public ClassLoader2(byte[] classWithNative) {
            super(null);
            mClassWithNative = classWithNative;
        }

        @SuppressWarnings("unused")
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try {
                return super.findClass(name);
            } catch (ClassNotFoundException e) {

                if (CLASS_NAME.equals(name)) {
                    // Load the modified ClassWithNative from its bytes representation.
                    return defineClass(CLASS_NAME, mClassWithNative, 0, mClassWithNative.length);
                }

                try {
                    // Load everything else from the original definition into the new class loader.
                    ClassReader cr = new ClassReader(name);
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(cw, 0);
                    byte[] bytes = cw.toByteArray();
                    return defineClass(name, bytes, 0, bytes.length);

                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
            }
        }

        /**
         * Accesses {@link ClassWithNative#add(int, int)} via reflection.
         */
        public int callAdd(Object instance, int a, int b) throws Exception {
            Method m = instance.getClass().getMethod("add",
                    new Class<?>[] { int.class, int.class });

            Object result = m.invoke(instance, new Object[] { a, b });
            return ((Integer) result).intValue();
        }

        /**
         * Accesses {@link ClassWithNative#callNativeInstance(int, double, Object[])}
         * via reflection.
         */
        public int callCallNativeInstance(Object instance, int a, double d, Object[] o)
                throws Exception {
            Method m = instance.getClass().getMethod("callNativeInstance",
                    new Class<?>[] { int.class, double.class, Object[].class });

            Object result = m.invoke(instance, new Object[] { a, d, o });
            return ((Integer) result).intValue();
        }

        public abstract void testModifiedInstance() throws Exception;
    }

    /**
     * Dummy test class with a native method.
     * The native method is not defined and any attempt to invoke it will
     * throw an {@link UnsatisfiedLinkError}.
     */
    public static class ClassWithNative {
        public ClassWithNative() {
        }

        public int add(int a, int b) {
            return a + b;
        }

        public int callNativeInstance(int a, double d, Object[] o) {
            return native_instance(a, d, o);
        }

        private native int native_instance(int a, double d, Object[] o);
    }

    /**
     * The delegate that receives the call to {@link ClassWithNative}'s overridden methods.
     */
    public static class ClassWithNative_Delegate {
        public static int native_instance(ClassWithNative instance, int a, double d, Object[] o) {
            if (o != null && o.length > 0) {
                o[0] = instance;
            }
            return (int)(a + d);
        }
    }
}
