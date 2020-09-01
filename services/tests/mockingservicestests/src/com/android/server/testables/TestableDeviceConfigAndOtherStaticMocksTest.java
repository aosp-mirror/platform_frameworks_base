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

import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TestableDeviceConfigAndOtherStaticMocksTest {
    @Rule
    public StaticMockFixtureRule mStaticMockFixtureRule =
            new StaticMockFixtureRule(TestableDeviceConfig::new, AB::new, CD::new);

    private List<String> mCollected;

    @Test
    public void testDeviceConfigAndOtherStaticMocks() throws Exception {
        mCollected = new ArrayList<>();
        int n = 0;

        String namespace = "foo";
        String flag = "bar";
        String flagValue = "new value";

        Assert.assertNull(DeviceConfig.getProperty(namespace, flag));

        A.a();
        verifyCollected(++n, "A.a");

        DeviceConfig.setProperty(namespace, flag, flagValue, false);

        D.b();
        verifyCollected(++n, "D.b");

        Assert.assertEquals(flagValue, DeviceConfig.getProperty(namespace, flag));

        C.b();
        verifyCollected(++n, "C.b");

        B.a();
        verifyCollected(++n, "B.a");
    }

    private void verifyCollected(int n, String last) {
        Assert.assertEquals(n, mCollected.size());
        Assert.assertEquals(last, mCollected.get(n - 1));
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
