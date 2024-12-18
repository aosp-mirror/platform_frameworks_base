/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.os.instrumentation;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import android.os.instrumentation.MethodDescriptor;
import android.os.instrumentation.MethodDescriptorParser;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.TestAbstractClass;
import com.android.server.TestAbstractClassImpl;
import com.android.server.TestClass;
import com.android.server.TestInterface;
import com.android.server.TestInterfaceImpl;

import org.junit.Test;

import java.lang.reflect.Method;


/**
 * Test class for
 * {@link MethodDescriptorParser#parseMethodDescriptor(ClassLoader,
 * MethodDescriptor)}.
 * <p>
 * Build/Install/Run:
 * atest FrameworksMockingServicesTests:ParseMethodDescriptorTest
 */
@Presubmit
@SmallTest
public class ParseMethodDescriptorTest {
    private static final String[] PRIMITIVE_PARAMS = new String[]{
            "boolean", "boolean[]", "byte", "byte[]", "char", "char[]", "short", "short[]", "int",
            "int[]", "long", "long[]", "float", "float[]", "double", "double[]"};
    private static final String[] CLASS_PARAMS =
            new String[]{"java.lang.String", "java.lang.String[]"};

    @Test
    public void primitiveParams() {
        assertNotNull(parseMethodDescriptor(TestClass.class.getName(), "primitiveParams",
                PRIMITIVE_PARAMS));
    }

    @Test
    public void classParams() {
        assertNotNull(
                parseMethodDescriptor(TestClass.class.getName(), "classParams", CLASS_PARAMS));
    }

    @Test
    public void publicMethod() {
        assertNotNull(
                parseMethodDescriptor(TestClass.class.getName(), "publicMethod"));
    }

    @Test
    public void privateMethod() {
        assertNotNull(
                parseMethodDescriptor(TestClass.class.getName(), "privateMethod"));
    }

    @Test
    public void innerClass() {
        assertNotNull(
                parseMethodDescriptor(TestClass.class.getName() + "$InnerClass", "innerMethod",
                        new String[]{TestClass.class.getName() + "$InnerClass",
                                TestClass.class.getName() + "$InnerClass[]"}));
    }

    @Test
    public void interface_concreteMethod() {
        assertNotNull(
                parseMethodDescriptor(TestInterfaceImpl.class.getName(), "interfaceMethod"));
    }

    @Test
    public void interface_defaultMethod() {
        assertNotNull(
                parseMethodDescriptor(TestInterface.class.getName(), "defaultMethod"));
    }

    @Test
    public void abstractClassImpl_abstractMethod() {
        assertNotNull(
                parseMethodDescriptor(TestAbstractClassImpl.class.getName(), "abstractMethod"));
    }

    @Test
    public void abstractClass_concreteMethod() {
        assertNotNull(
                parseMethodDescriptor(TestAbstractClass.class.getName(), "concreteMethod"));
    }

    @Test
    public void notFound_illegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> parseMethodDescriptor("foo", "bar"));
        assertThrows(IllegalArgumentException.class,
                () -> parseMethodDescriptor(TestClass.class.getName(), "bar"));
        assertThrows(IllegalArgumentException.class,
                () -> parseMethodDescriptor(TestClass.class.getName(), "primitiveParams",
                        new String[]{"int"}));
    }

    private Method parseMethodDescriptor(String fqcn, String methodName) {
        return MethodDescriptorParser.parseMethodDescriptor(
                getClass().getClassLoader(),
                getMethodDescriptor(fqcn, methodName, new String[]{}));
    }

    private Method parseMethodDescriptor(String fqcn, String methodName, String[] fqParameters) {
        return MethodDescriptorParser.parseMethodDescriptor(
                getClass().getClassLoader(),
                getMethodDescriptor(fqcn, methodName, fqParameters));
    }

    private MethodDescriptor getMethodDescriptor(String fqcn, String methodName,
            String[] fqParameters) {
        MethodDescriptor methodDescriptor = new MethodDescriptor();
        methodDescriptor.fullyQualifiedClassName = fqcn;
        methodDescriptor.methodName = methodName;
        methodDescriptor.fullyQualifiedParameters = fqParameters;
        return methodDescriptor;
    }


}
