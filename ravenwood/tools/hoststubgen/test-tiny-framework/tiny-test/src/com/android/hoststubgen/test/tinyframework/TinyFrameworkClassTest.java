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

import static org.junit.Assert.assertThrows;

import com.android.hoststubgen.test.tinyframework.R.Nested;

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

    @Test
    public void testRemove() {
        TinyFrameworkForTextPolicy tfc = new TinyFrameworkForTextPolicy();
        assertThrows(NoSuchMethodError.class, () -> tfc.toBeRemoved("abc"));
        assertThrows(NoSuchFieldError.class, () -> tfc.remove = 1);
    }

    @Test
    public void testIgnore() {
        TinyFrameworkForTextPolicy tfc = new TinyFrameworkForTextPolicy();
        tfc.toBeIgnoredV();
        assertThat(tfc.toBeIgnoredZ()).isEqualTo(false);
        assertThat(tfc.toBeIgnoredB()).isEqualTo(0);
        assertThat(tfc.toBeIgnoredC()).isEqualTo(0);
        assertThat(tfc.toBeIgnoredS()).isEqualTo(0);
        assertThat(tfc.toBeIgnoredI()).isEqualTo(0);
        assertThat(tfc.toBeIgnoredF()).isEqualTo(0);
        assertThat(tfc.toBeIgnoredD()).isEqualTo(0);
        assertThat(tfc.toBeIgnoredObj()).isEqualTo(null);
    }

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
    public void testUnsupportedMethod() {
        TinyFrameworkForTextPolicy tfc = new TinyFrameworkForTextPolicy();

        thrown.expect(RuntimeException.class);
        thrown.expectMessage("not yet supported");
        tfc.unsupportedMethod();
    }

    @Test
    public void testLambda1() {
        assertThat(new TinyFrameworkLambdas().mSupplier.get()).isEqualTo(1);
    }

    @Test
    public void testLambda2() {
        assertThat(TinyFrameworkLambdas.sSupplier.get()).isEqualTo(2);
    }

    @Test
    public void testLambda3() {
        assertThat(new TinyFrameworkLambdas().getSupplier().get()).isEqualTo(3);
    }

    @Test
    public void testLambda4() {
        assertThat(TinyFrameworkLambdas.getSupplier_static().get()).isEqualTo(4);
    }

    @Test
    public void testLambda5() {
        assertThat(new TinyFrameworkLambdas.Nested().mSupplier.get()).isEqualTo(5);
    }

    @Test
    public void testLambda6() {
        assertThat(TinyFrameworkLambdas.Nested.sSupplier.get()).isEqualTo(6);
    }

    @Test
    public void testLambda7() {
        assertThat(new TinyFrameworkLambdas.Nested().getSupplier().get()).isEqualTo(7);
    }

    @Test
    public void testLambda8() {
        assertThat(TinyFrameworkLambdas.Nested.getSupplier_static().get()).isEqualTo(8);
    }

    @Test
    public void testNativeSubstitutionClass() {
        assertThat(TinyFrameworkNative.nativeAddTwo(3)).isEqualTo(5);
    }

    @Test
    public void testNativeSubstitutionLong() {
        assertThat(TinyFrameworkNative.nativeLongPlus(1L, 2L)).isEqualTo(3L);
    }

    @Test
    public void testNativeSubstitutionByte() {
        assertThat(TinyFrameworkNative.nativeBytePlus((byte) 3, (byte) 4)).isEqualTo(7);
    }

    @Test
    public void testNativeSubstitutionClass_nonStatic() {
        TinyFrameworkNative instance = new TinyFrameworkNative();
        instance.setValue(5);
        assertThat(instance.nativeNonStaticAddToValue(3)).isEqualTo(8);
    }

    @Test
    public void testSubstituteNativeWithThrow() {
        thrown.expect(RuntimeException.class);
        thrown.expectMessage("not yet supported");

        TinyFrameworkNative.nativeStillNotSupported();
    }

    @Test
    public void testSubstituteNativeWithKeep() {
        // We don't want to complicate the test by setting up JNI,
        // so to test out whether the native method is preserved, we
        // check whether calling it will throw UnsatisfiedLinkError,
        // which would only happen on native methods.
        thrown.expect(UnsatisfiedLinkError.class);

        TinyFrameworkNative.nativeStillKeep();
    }

    @Test
    public void testNotNativeRedirect() {
        TinyFrameworkNative.notNativeStaticRedirected();
        new TinyFrameworkNative().notNativeRedirected();
    }

    @Test
    public void testExitLog() {
        thrown.expect(RuntimeException.class);
        thrown.expectMessage("Outer exception");

        TinyFrameworkExceptionTester.testException();
    }

    @Test
    public void testClassLoadHook() {
        assertThat(TinyFrameworkClassWithInitializerStub.sInitialized).isTrue();

        // Having this line before assertThat() will ensure these class are already loaded.
        var classes = new Class[]{
                TinyFrameworkClassWithInitializerStub.class,
                TinyFrameworkAnnotations.class,
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

    @Test
    public void testEnumSimple() throws Exception {
        assertThat(TinyFrameworkEnumSimple.CAT.ordinal()).isEqualTo(0);
        assertThat(TinyFrameworkEnumSimple.CAT.name()).isEqualTo("CAT");

        assertThat(TinyFrameworkEnumSimple.DOG.ordinal()).isEqualTo(1);
        assertThat(TinyFrameworkEnumSimple.DOG.name()).isEqualTo("DOG");

        assertThat(TinyFrameworkEnumSimple.valueOf("DOG").ordinal()).isEqualTo(1);

        assertThat(TinyFrameworkEnumSimple.values()).isEqualTo(
                new TinyFrameworkEnumSimple[]{
                        TinyFrameworkEnumSimple.CAT,
                        TinyFrameworkEnumSimple.DOG,
                }
        );
    }

    @Test
    public void testEnumComplex() throws Exception {
        assertThat(TinyFrameworkEnumComplex.RED.ordinal()).isEqualTo(0);
        assertThat(TinyFrameworkEnumComplex.RED.name()).isEqualTo("RED");

        assertThat(TinyFrameworkEnumComplex.RED.getShortName()).isEqualTo("R");

        assertThat(TinyFrameworkEnumComplex.GREEN.ordinal()).isEqualTo(1);
        assertThat(TinyFrameworkEnumComplex.GREEN.name()).isEqualTo("GREEN");

        assertThat(TinyFrameworkEnumComplex.valueOf("BLUE").ordinal()).isEqualTo(2);

        assertThat(TinyFrameworkEnumComplex.values()).isEqualTo(
                new TinyFrameworkEnumComplex[]{
                        TinyFrameworkEnumComplex.RED,
                        TinyFrameworkEnumComplex.GREEN,
                        TinyFrameworkEnumComplex.BLUE,
                }
        );
    }

    @Test
    public void testAidlHeuristics() {
        assertThat(IPretendingAidl.Stub.addOne(1)).isEqualTo(2);
        assertThat(IPretendingAidl.Stub.Proxy.addTwo(1)).isEqualTo(3);
    }

    @Test
    public void testRFileHeuristics() {
        assertThat(Nested.ARRAY.length).isEqualTo(1);
    }

    @Test
    public void testTypeRename() {
        assertThat(TinyFrameworkRenamedClassCaller.foo(1)).isEqualTo(1);
    }

    @Test
    public void testMethodCallReplaceNonStatic() throws Exception {
        assertThat(TinyFrameworkMethodCallReplace.nonStaticMethodCallReplaceTester())
                .isEqualTo(true);
    }

    @Test
    public void testMethodCallReplaceStatic() throws Exception {
        assertThat(TinyFrameworkMethodCallReplace.staticMethodCallReplaceTester())
                .isEqualTo(3);
    }
}
