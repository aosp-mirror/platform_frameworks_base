/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.testables;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MultipleStaticMocksTest {
    @Rule
    public StaticMockFixtureRule mStaticMockFixtureRule =
            new StaticMockFixtureRule(AB::new, CD::new);

    private List<String> mCollected;

    @Test
    public void testMultipleStaticMocks() throws Exception {
        mCollected = new ArrayList<>();
        int n = 0;

        A.a();
        n = verifyCollected(n, "A.a");

        D.b();
        n = verifyCollected(n, "D.b");

        C.b();
        n = verifyCollected(n, "C.b");

        B.a();
        n = verifyCollected(n, "B.a");
    }

    private int verifyCollected(int n, String... last) {
        assertThat(mCollected).hasSize(n + last.length);
        assertThat(mCollected.subList(n, mCollected.size()))
                .containsExactlyElementsIn(last).inOrder();
        return n + last.length;
    }

    private static class A {
        /* package */ static void a() {}
        /* package */ static void b() {}
    }

    private static class B {
        /* package */ static void a() {}
        /* package */ static void b() {}
    }

    private static class C {
        /* package */ static void a() {}
        /* package */ static void b() {}
    }

    private static class D {
        /* package */ static void a() {}
        /* package */ static void b() {}
    }

    /**
     * AB StaticMockFixture class that handles two mocked classes, {@link A} and {@link B}.
     */
    private class AB implements StaticMockFixture {
        @Override
        public StaticMockitoSessionBuilder setUpMockedClasses(
                StaticMockitoSessionBuilder sessionBuilder) {
            sessionBuilder.spyStatic(A.class);
            sessionBuilder.spyStatic(B.class);
            return sessionBuilder;
        }

        @Override
        public void setUpMockBehaviors() {
            ExtendedMockito.doAnswer(invocation -> {
                mCollected.add("A.a");
                return null;
            }).when(A::a);
            ExtendedMockito.doAnswer(invocation -> {
                mCollected.add("A.b");
                return null;
            }).when(A::b);
            ExtendedMockito.doAnswer(invocation -> {
                mCollected.add("B.a");
                return null;
            }).when(B::a);
            ExtendedMockito.doAnswer(invocation -> {
                mCollected.add("B.b");
                return null;
            }).when(B::b);
        }

        @Override
        public void tearDown() {

        }
    }

    /**
     * AB StaticMockFixture class that handles two mocked classes, {@link C} and {@link D}.
     */
    private class CD implements StaticMockFixture {
        @Override
        public StaticMockitoSessionBuilder setUpMockedClasses(
                StaticMockitoSessionBuilder sessionBuilder) {
            sessionBuilder.spyStatic(C.class);
            sessionBuilder.spyStatic(D.class);
            return sessionBuilder;
        }

        @Override
        public void setUpMockBehaviors() {
            ExtendedMockito.doAnswer(invocation -> {
                mCollected.add("C.a");
                return null;
            }).when(C::a);
            ExtendedMockito.doAnswer(invocation -> {
                mCollected.add("C.b");
                return null;
            }).when(C::b);
            ExtendedMockito.doAnswer(invocation -> {
                mCollected.add("D.a");
                return null;
            }).when(D::a);
            ExtendedMockito.doAnswer(invocation -> {
                mCollected.add("D.b");
                return null;
            }).when(D::b);
        }

        @Override
        public void tearDown() {

        }
    }
}
