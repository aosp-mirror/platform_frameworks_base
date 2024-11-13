/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class AnnotatedElementPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Class<?> mType;
    private Field mField;
    private Method mMethod;

    @Before
    public void setUp() throws Exception {
        mType = Type.class;
        mField = Type.class.getField("field");
        mMethod = Type.class.getMethod("method", String.class);
    }

    // get annotations by member type and method

    @Test
    public void timeGetTypeAnnotations() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mType.getAnnotations();
        }
    }

    @Test
    public void timeGetFieldAnnotations() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mField.getAnnotations();
        }
    }

    @Test
    public void timeGetMethodAnnotations() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mMethod.getAnnotations();
        }
    }

    @Test
    public void timeGetParameterAnnotations() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mMethod.getParameterAnnotations();
        }
    }

    @Test
    public void timeGetTypeAnnotation() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mType.getAnnotation(Marker.class);
        }
    }

    @Test
    public void timeGetFieldAnnotation() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mField.getAnnotation(Marker.class);
        }
    }

    @Test
    public void timeGetMethodAnnotation() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mMethod.getAnnotation(Marker.class);
        }
    }

    @Test
    public void timeIsTypeAnnotationPresent() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mType.isAnnotationPresent(Marker.class);
        }
    }

    @Test
    public void timeIsFieldAnnotationPresent() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mField.isAnnotationPresent(Marker.class);
        }
    }

    @Test
    public void timeIsMethodAnnotationPresent() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mMethod.isAnnotationPresent(Marker.class);
        }
    }

    // get annotations by result size

    @Test
    public void timeGetAllReturnsLargeAnnotation() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            HasLargeAnnotation.class.getAnnotations();
        }
    }

    @Test
    public void timeGetAllReturnsSmallAnnotation() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            HasSmallAnnotation.class.getAnnotations();
        }
    }

    @Test
    public void timeGetAllReturnsMarkerAnnotation() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            HasMarkerAnnotation.class.getAnnotations();
        }
    }

    @Test
    public void timeGetAllReturnsNoAnnotation() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            HasNoAnnotations.class.getAnnotations();
        }
    }

    @Test
    public void timeGetAllReturnsThreeAnnotations() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            HasThreeAnnotations.class.getAnnotations();
        }
    }

    // get annotations with inheritance

    @Test
    public void timeGetAnnotationsOnSubclass() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            ExtendsHasThreeAnnotations.class.getAnnotations();
        }
    }

    @Test
    public void timeGetDeclaredAnnotationsOnSubclass() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            ExtendsHasThreeAnnotations.class.getDeclaredAnnotations();
        }
    }

    // get annotations with enclosing / inner classes

    @Test
    public void timeGetDeclaredClasses() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            AnnotatedElementPerfTest.class.getDeclaredClasses();
        }
    }

    @Test
    public void timeGetDeclaringClass() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            HasSmallAnnotation.class.getDeclaringClass();
        }
    }

    @Test
    public void timeGetEnclosingClass() {
        Object anonymousClass = new Object() {};
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            anonymousClass.getClass().getEnclosingClass();
        }
    }

    @Test
    public void timeGetEnclosingConstructor() {
        Object anonymousClass = new Object() {};
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            anonymousClass.getClass().getEnclosingConstructor();
        }
    }

    @Test
    public void timeGetEnclosingMethod() {
        Object anonymousClass = new Object() {};
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            anonymousClass.getClass().getEnclosingMethod();
        }
    }

    @Test
    public void timeGetModifiers() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            HasSmallAnnotation.class.getModifiers();
        }
    }

    @Test
    public void timeGetSimpleName() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            HasSmallAnnotation.class.getSimpleName();
        }
    }

    @Test
    public void timeIsAnonymousClass() {
        Object anonymousClass = new Object() {};
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            anonymousClass.getClass().isAnonymousClass();
        }
    }

    @Test
    public void timeIsLocalClass() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            HasSmallAnnotation.class.isLocalClass();
        }
    }

    // the annotated elements

    @Marker
    public class Type {
        @Marker public String field;

        @Marker
        public void method(@Marker String parameter) {}
    }

    @Large(
            a = "on class",
            b = {"A", "B", "C"},
            c = @Small(e = "E1", f = 1695938256, g = 7264081114510713000L),
            d = {@Small(e = "E2", f = 1695938256, g = 7264081114510713000L)})
    public class HasLargeAnnotation {}

    @Small(e = "E1", f = 1695938256, g = 7264081114510713000L)
    public class HasSmallAnnotation {}

    @Marker
    public class HasMarkerAnnotation {}

    public class HasNoAnnotations {}

    @Large(
            a = "on class",
            b = {"A", "B", "C"},
            c = @Small(e = "E1", f = 1695938256, g = 7264081114510713000L),
            d = {@Small(e = "E2", f = 1695938256, g = 7264081114510713000L)})
    @Small(e = "E1", f = 1695938256, g = 7264081114510713000L)
    @Marker
    public class HasThreeAnnotations {}

    public class ExtendsHasThreeAnnotations extends HasThreeAnnotations {}

    // the annotations

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker {}

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Large {
        String a() default "";

        String[] b() default {};

        Small c() default @Small;

        Small[] d() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Small {
        String e() default "";

        int f() default 0;

        long g() default 0L;
    }
}
