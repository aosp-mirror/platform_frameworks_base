/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.android.tools.layoutlib.create.dataclass.StubClass;

import org.junit.Assert;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class StubMethodAdapterTest {

    private static final String STUB_CLASS_NAME = StubClass.class.getName();

    /**
     * Load a dummy class, stub one of its method and ensure that the modified class works as
     * intended.
     */
    @Test
    public void testBoolean() throws Exception {
        final String methodName = "returnTrue";
        // First don't change the method and assert that it returns true
        testBoolean((name, type) -> false, Assert::assertTrue, methodName);
        // Change the method now and assert that it returns false.
        testBoolean((name, type) -> methodName.equals(name) &&
                Type.BOOLEAN_TYPE.equals(type.getReturnType()), Assert::assertFalse, methodName);
    }

    /**
     * @param methodPredicate tests if the method should be replaced
     */
    private void testBoolean(BiPredicate<String, Type> methodPredicate, Consumer<Boolean> assertion,
            String methodName) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        // Always rename the class to avoid conflict with the original class.
        String newClassName = STUB_CLASS_NAME + '_';
        new ClassReader(STUB_CLASS_NAME).accept(
                new ClassAdapter(newClassName, writer, methodPredicate), 0);
        MyClassLoader myClassLoader = new MyClassLoader(newClassName, writer.toByteArray());
        Class<?> aClass = myClassLoader.loadClass(newClassName);
        assertTrue("StubClass not loaded by the classloader. Likely a bug in the test.",
                myClassLoader.findClassCalled);
        Method method = aClass.getMethod(methodName);
        Object o = aClass.newInstance();
        assertion.accept((Boolean) method.invoke(o));
    }

    private static class ClassAdapter extends ClassVisitor {

        private final String mClassName;
        private final BiPredicate<String, Type> mMethodPredicate;

        private ClassAdapter(String className, ClassVisitor cv,
                BiPredicate<String, Type> methodPredicate) {
            super(Main.ASM_VERSION, cv);
            mClassName = className.replace('.', '/');
            mMethodPredicate = methodPredicate;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            super.visit(version, access, mClassName, signature, superName,
                    interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                String[] exceptions) {
            // Copied partly from
            // com.android.tools.layoutlib.create.DelegateClassAdapter.visitMethod()
            // but not generating the _Original method.
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            boolean isNative = (access & Opcodes.ACC_NATIVE) != 0;
            MethodVisitor originalMethod =
                    super.visitMethod(access, name, desc, signature, exceptions);
            Type descriptor = Type.getMethodType(desc);
            if (mMethodPredicate.test(name, descriptor)) {
                String methodSignature = mClassName + "#" + name;
                String invokeSignature = methodSignature + desc;
                return new StubMethodAdapter(originalMethod, name, descriptor.getReturnType(),
                        invokeSignature, isStatic, isNative);
            }
            return originalMethod;
        }
    }

    private static class MyClassLoader extends ClassLoader {
        private final String mName;
        private final byte[] mBytes;
        private boolean findClassCalled;

        private MyClassLoader(String name, byte[] bytes) {
            mName = name;
            mBytes = bytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(mName)) {
                findClassCalled = true;
                return defineClass(name, mBytes, 0, mBytes.length);
            }
            return super.findClass(name);
        }
    }
}
