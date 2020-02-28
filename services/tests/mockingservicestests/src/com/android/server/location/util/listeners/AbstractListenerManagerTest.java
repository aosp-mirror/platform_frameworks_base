/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.util.listeners;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.location.util.listeners.AbstractListenerManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.function.Predicate;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AbstractListenerManagerTest {

    private TestListenerManager mListenerManager;

    @Before
    public void setUp() {
        mListenerManager = new TestListenerManager();
    }

    @Test
    public void testAdd() {
        Runnable listener = mock(Runnable.class);

        mListenerManager.addListener(0, listener);
        assertThat(mListenerManager.mRegistered).isTrue();
        assertThat(mListenerManager.mActive).isTrue();
        assertThat(mListenerManager.mMergedRequest).isEqualTo(0);

        mListenerManager.notifyListeners();
        verify(listener).run();
    }

    @Test
    public void testRemove() {
        Runnable listener = mock(Runnable.class);

        mListenerManager.addListener(0, listener);
        mListenerManager.removeListener(listener);
        assertThat(mListenerManager.mRegistered).isFalse();
        assertThat(mListenerManager.mActive).isFalse();

        mListenerManager.notifyListeners();
        verify(listener, never()).run();
    }

    @Test
    public void testMergeMultiple() {
        Runnable listener1 = mock(Runnable.class);
        Runnable listener2 = mock(Runnable.class);
        Runnable listener3 = mock(Runnable.class);

        mListenerManager.addListener(0, listener1);
        mListenerManager.addListener(1, listener2);
        assertThat(mListenerManager.mRegistered).isTrue();
        assertThat(mListenerManager.mActive).isTrue();
        assertThat(mListenerManager.mMergedRequest).isEqualTo(1);

        mListenerManager.notifyListeners();
        verify(listener1, times(1)).run();
        verify(listener2, times(1)).run();
        verify(listener3, times(0)).run();

        mListenerManager.addListener(0, listener3);
        assertThat(mListenerManager.mRegistered).isTrue();
        assertThat(mListenerManager.mActive).isTrue();
        assertThat(mListenerManager.mMergedRequest).isEqualTo(1);

        mListenerManager.notifyListeners();
        verify(listener1, times(2)).run();
        verify(listener2, times(2)).run();
        verify(listener3, times(1)).run();

        mListenerManager.removeListener(listener2);
        assertThat(mListenerManager.mRegistered).isTrue();
        assertThat(mListenerManager.mActive).isTrue();
        assertThat(mListenerManager.mMergedRequest).isEqualTo(0);

        mListenerManager.notifyListeners();
        verify(listener1, times(3)).run();
        verify(listener2, times(2)).run();
        verify(listener3, times(2)).run();

        mListenerManager.removeListener(listener1);
        mListenerManager.removeListener(listener3);
        assertThat(mListenerManager.mRegistered).isFalse();
        assertThat(mListenerManager.mActive).isFalse();
    }

    @Test
    public void testPredicate() {
        Runnable listener = mock(Runnable.class);

        mListenerManager.addListener(0, listener);

        mListenerManager.notifyListeners(i -> i != 0);
        verify(listener, never()).run();

        mListenerManager.notifyListeners(i -> i == 0);
        verify(listener).run();
    }

    @Test
    public void testInactive() {
        Runnable listener = mock(Runnable.class);

        mListenerManager.addListener(0, listener);
        mListenerManager.setActive(0, false);
        assertThat(mListenerManager.mRegistered).isFalse();
        assertThat(mListenerManager.mActive).isFalse();

        mListenerManager.notifyListeners();
        verify(listener, never()).run();

        mListenerManager.setActive(0, true);
        assertThat(mListenerManager.mRegistered).isTrue();
        assertThat(mListenerManager.mActive).isTrue();
        assertThat(mListenerManager.mMergedRequest).isEqualTo(0);

        mListenerManager.notifyListeners();
        verify(listener).run();
    }

    @Test
    public void testMergeMultiple_Inactive() {
        Runnable listener1 = mock(Runnable.class);
        Runnable listener2 = mock(Runnable.class);
        Runnable listener3 = mock(Runnable.class);

        mListenerManager.addListener(0, listener1);
        mListenerManager.addListener(2, listener2);
        mListenerManager.addListener(1, listener3);
        assertThat(mListenerManager.mRegistered).isTrue();
        assertThat(mListenerManager.mActive).isTrue();
        assertThat(mListenerManager.mMergedRequest).isEqualTo(2);

        mListenerManager.notifyListeners();
        verify(listener1, times(1)).run();
        verify(listener2, times(1)).run();
        verify(listener3, times(1)).run();

        mListenerManager.setActive(2, false);
        assertThat(mListenerManager.mRegistered).isTrue();
        assertThat(mListenerManager.mActive).isTrue();
        assertThat(mListenerManager.mMergedRequest).isEqualTo(1);

        mListenerManager.notifyListeners();
        verify(listener1, times(2)).run();
        verify(listener2, times(1)).run();
        verify(listener3, times(2)).run();

        mListenerManager.setActive(2, true);
        assertThat(mListenerManager.mRegistered).isTrue();
        assertThat(mListenerManager.mActive).isTrue();
        assertThat(mListenerManager.mMergedRequest).isEqualTo(2);

        mListenerManager.notifyListeners();
        verify(listener1, times(3)).run();
        verify(listener2, times(2)).run();
        verify(listener3, times(3)).run();

        mListenerManager.setActive(0, false);
        mListenerManager.setActive(1, false);
        mListenerManager.setActive(2, false);
        assertThat(mListenerManager.mRegistered).isFalse();
        assertThat(mListenerManager.mActive).isFalse();
    }

    private static class TestRegistration extends
            AbstractListenerManager.Registration<Integer, Runnable> {

        boolean mActive = true;

        protected TestRegistration(Integer integer, Runnable runnable) {
            super(integer, DIRECT_EXECUTOR, runnable);
        }
    }

    private static class TestListenerManager extends
            AbstractListenerManager<Runnable, Integer, Runnable, TestRegistration, Integer> {

        boolean mActive;
        boolean mRegistered;
        int mMergedRequest;

        TestListenerManager() {
        }

        public void addListener(Integer request, Runnable listener) {
            addRegistration(listener, new TestRegistration(request, listener));
        }

        public void removeListener(Runnable listener) {
            removeRegistration(listener);
        }

        public void setActive(Integer request, boolean active) {
            updateRegistrations(testRegistration -> {
                if (testRegistration.getRequest().equals(request)) {
                    testRegistration.mActive = active;
                    return true;
                }
                return false;
            });
        }

        public void notifyListeners() {
            deliverToListeners(Runnable::run);
        }

        public void notifyListeners(Predicate<Integer> predicate) {
            deliverToListeners(Runnable::run, r -> predicate.test(r.getRequest()));
        }

        @Override
        protected boolean registerService(Integer mergedRequest) {
            mRegistered = true;
            mMergedRequest = mergedRequest;
            return true;
        }

        @Override
        protected void unregisterService() {
            mRegistered = false;
        }

        @Override
        protected boolean isActive(TestRegistration registration) {
            return registration.mActive;
        }

        @Override
        protected void onActive() {
            mActive = true;
        }

        @Override
        protected void onInactive() {
            mActive = false;
        }

        @Override
        protected Integer mergeRequests(List<TestRegistration> testRegistrations) {
            int max = Integer.MIN_VALUE;
            for (TestRegistration registration : testRegistrations) {
                if (registration.getRequest() > max) {
                    max = registration.getRequest();
                }
            }
            return max;
        }
    }
}
