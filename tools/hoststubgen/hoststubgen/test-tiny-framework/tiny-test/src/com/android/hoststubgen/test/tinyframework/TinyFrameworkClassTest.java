/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.test.tinyframework;

import static com.google.common.truth.Truth.assertThat;

import com.android.hoststubgen.test.tinyframework.TinyFrameworkNestedClasses.SubClass;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class TinyFrameworkClassTest {
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testSimple() {
        TinyFrameworkForTextPolicy tfc = new TinyFrameworkForTextPolicy();
        assertThat(tfc.addOne(1)).isEqualTo(2);
        assertThat(tfc.stub).isEqualTo(1);
    }

//    @Test
//    public void testDoesntCompile() {
//        TinyFrameworkClass tfc = new TinyFrameworkClass();
//
//        tfc.addOneInner(1); // Shouldn't compile.
//        tfc.toBeRemoved("abc"); // Shouldn't compile.
//        tfc.unsupportedMethod(); // Shouldn't compile.
//        int a = tfc.keep; // Shouldn't compile
//        int b = tfc.remove; // Shouldn't compile
//    }

    @Test
    public void testSubstitute() {
        TinyFrameworkForTextPolicy tfc = new TinyFrameworkForTextPolicy();
        assertThat(tfc.addTwo(1)).isEqualTo(3);
    }

    @Test
    public void testSubstituteNative() {
        TinyFrameworkForTextPolicy tfc = new TinyFrameworkForTextPolicy();
        assertThat(tfc.nativeAddThree(1)).isEqualTo(4);
    }

    @Test
    public void testVisibleButUsesUnsupportedMethod() {
        TinyFrameworkForTextPolicy tfc = new TinyFrameworkForTextPolicy();

        thrown.expect(RuntimeException.class);
        thrown.expectMessage("This method is not supported on the host side");
        tfc.visibleButUsesUnsupportedMethod();
    }

    @Test
    public void testNestedClass1() {
        assertThat(new TinyFrameworkNestedClasses().mSupplier.get()).isEqualTo(1);
    }

    @Test
    public void testNestedClass2() {
        assertThat(TinyFrameworkNestedClasses.sSupplier.get()).isEqualTo(2);
    }

    @Test
    public void testNestedClass3() {
        assertThat(new TinyFrameworkNestedClasses().getSupplier().get()).isEqualTo(3);
    }

    @Test
    public void testNestedClass4() {
        assertThat(TinyFrameworkNestedClasses.getSupplier_static().get()).isEqualTo(4);
    }

    @Test
    public void testNestedClass5() {
        assertThat((new TinyFrameworkNestedClasses()).new InnerClass().value).isEqualTo(5);
    }

    @Test
    public void testNestedClass6() {
        assertThat(new TinyFrameworkNestedClasses.StaticNestedClass().value).isEqualTo(6);
    }

    @Test
    public void testNestedClass7() {
        assertThat(TinyFrameworkNestedClasses.StaticNestedClass.getSupplier_static().get())
                .isEqualTo(7);
    }

    @Test
    public void testNativeSubstitutionClass() {
        assertThat(TinyFrameworkNative.nativeAddTwo(3)).isEqualTo(5);
    }

    @Test
    public void testExitLog() {
        thrown.expect(RuntimeException.class);
        thrown.expectMessage("Outer exception");

        TinyFrameworkExceptionTester.testException();

    }

    @Test
    public void testMethodCallBeforeSuperCall() {
        assertThat(new SubClass(3).value).isEqualTo(3);
    }

    @Test
    public void testClassLoadHook() {
        assertThat(TinyFrameworkClassWithInitializerStub.sInitialized).isTrue();

        // Having this line before assertThat() will ensure these class are already loaded.
        var classes = new Class[]{
                TinyFrameworkClassWithInitializerStub.class,
                TinyFrameworkClassAnnotations.class,
                TinyFrameworkForTextPolicy.class,
        };

        // The following classes have a class load hook, so they should be registered.
        assertThat(TinyFrameworkClassLoadHook.sLoadedClasses)
                .containsAnyIn(classes);

        // This class doesn't have a class load hook, so shouldn't be included.
        assertThat(TinyFrameworkClassLoadHook.sLoadedClasses)
                .doesNotContain(TinyFrameworkNestedClasses.class);
    }

    @Test
    public void testStaticInitializer_Default() {
        assertThat(TinyFrameworkClassWithInitializerDefault.sInitialized).isFalse();
        assertThat(TinyFrameworkClassWithInitializerDefault.sObject).isNull();
    }

    @Test
    public void testStaticInitializer_Stub() {
        assertThat(TinyFrameworkClassWithInitializerStub.sInitialized).isTrue();
        assertThat(TinyFrameworkClassWithInitializerStub.sObject).isNotNull();
    }

    /**
     * Test to try accessing JDK private fields using reflections + setAccessible(true),
     * which is now disallowed due to Java Modules, unless you run the javacommand with.
     *   --add-opens=java.base/java.io=ALL-UNNAMED
     *
     * You can try it from the command line, like:
     * $ JAVA_OPTS="--add-opens=java.base/java.io=ALL-UNNAMED" ./run-test-manually.sh
     *
     * @throws Exception
     */
    @Test
    public void testFileDescriptor() throws Exception {
        var fd = FileDescriptor.out;

        // Get the FD value directly from the private field.
        // This is now prohibited due to Java Modules.
        // It throws:
        // java.lang.reflect.InaccessibleObjectException: Unable to make field private int java.io.FileDescriptor.fd accessible: module java.base does not "opens java.io" to unnamed module @3bb50eaa

        thrown.expect(java.lang.reflect.InaccessibleObjectException.class);

        // Access the private field.
        final Field f = FileDescriptor.class.getDeclaredField("fd");
        final Method m = FileDescriptor.class.getDeclaredMethod("set", int.class);
        f.setAccessible(true);
        m.setAccessible(true);

        assertThat(f.get(fd)).isEqualTo(1);

        // Set
        f.set(fd, 2);
        assertThat(f.get(fd)).isEqualTo(2);

        // Call the package private method, set(int).
        m.invoke(fd, 0);
        assertThat(f.get(fd)).isEqualTo(0);
    }

    @Test
    public void testPackageRedirect() throws Exception {
        assertThat(TinyFrameworkPackageRedirect.foo(1)).isEqualTo(1);
    }
}
