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

package com.android.server.location.listeners;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.location.listeners.ListenerMultiplexer.UpdateServiceLock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

import java.util.Collection;
import java.util.function.Consumer;

@SuppressWarnings("unchecked")
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ListenerMultiplexerTest {

    private interface Callbacks {
        void onRegister();

        void onUnregister();

        void onRegistrationAdded(Consumer<TestListenerRegistration> consumer,
                TestListenerRegistration registration);

        void onRegistrationReplaced(Consumer<TestListenerRegistration> consumer,
                TestListenerRegistration oldRegistration, TestListenerRegistration newRegistration);

        void onRegistrationRemoved(Consumer<TestListenerRegistration> consumer,
                TestListenerRegistration registration);

        void onActive();

        void onInactive();
    }

    Callbacks mCallbacks;
    TestMultiplexer mMultiplexer;
    InOrder mInOrder;

    @Before
    public void setUp() {
        mCallbacks = mock(Callbacks.class);
        mMultiplexer = new TestMultiplexer(mCallbacks);
        mInOrder = inOrder(mCallbacks);
    }

    @Test
    public void testAdd() {
        Consumer<TestListenerRegistration> consumer = mock(Consumer.class);

        mMultiplexer.addListener(0, consumer);
        mInOrder.verify(mCallbacks).onRegister();
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onActive();
        assertThat(mMultiplexer.mRegistered).isTrue();
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(0);

        mMultiplexer.addListener(1, consumer);
        mInOrder.verify(mCallbacks).onRegistrationRemoved(eq(consumer),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onRegistrationReplaced(eq(consumer),
                any(TestListenerRegistration.class), any(TestListenerRegistration.class));
        assertThat(mMultiplexer.mRegistered).isTrue();
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(1);

        mMultiplexer.notifyListeners();
        verify(consumer).accept(any(TestListenerRegistration.class));
    }

    @Test
    public void testReplace() {
        Consumer<TestListenerRegistration> oldConsumer = mock(Consumer.class);
        Consumer<TestListenerRegistration> consumer = mock(Consumer.class);

        mMultiplexer.addListener(0, oldConsumer);
        mInOrder.verify(mCallbacks).onRegister();
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(oldConsumer),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onActive();
        mMultiplexer.replaceListener(1, oldConsumer, consumer);
        mInOrder.verify(mCallbacks).onRegistrationRemoved(eq(oldConsumer),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onRegistrationReplaced(eq(consumer),
                any(TestListenerRegistration.class), any(TestListenerRegistration.class));
        assertThat(mMultiplexer.mRegistered).isTrue();
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(1);

        mMultiplexer.notifyListeners();
        verify(consumer).accept(any(TestListenerRegistration.class));
        verify(oldConsumer, never()).accept(any(TestListenerRegistration.class));
    }

    @Test
    public void testRemove() {
        Consumer<TestListenerRegistration> consumer = mock(Consumer.class);

        mMultiplexer.addListener(0, consumer);
        mInOrder.verify(mCallbacks).onRegister();
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onActive();

        mMultiplexer.removeListener(consumer);
        mInOrder.verify(mCallbacks).onInactive();
        mInOrder.verify(mCallbacks).onRegistrationRemoved(eq(consumer),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onUnregister();
        assertThat(mMultiplexer.mRegistered).isFalse();

        mMultiplexer.notifyListeners();
        verify(consumer, never()).accept(any(TestListenerRegistration.class));
    }

    @Test
    public void testRemoveIf() {
        Consumer<TestListenerRegistration> consumer1 = mock(Consumer.class);
        Consumer<TestListenerRegistration> consumer2 = mock(Consumer.class);
        Consumer<TestListenerRegistration> consumer3 = mock(Consumer.class);

        mMultiplexer.addListener(2, consumer1);
        mMultiplexer.addListener(1, consumer2);
        mMultiplexer.addListener(0, consumer3);

        mMultiplexer.removeRegistrationIf(consumer -> consumer == consumer1);
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(1);
        mInOrder.verify(mCallbacks).onRegistrationRemoved(eq(consumer1),
                any(TestListenerRegistration.class));

        mMultiplexer.notifyListeners();
        verify(consumer1, never()).accept(any(TestListenerRegistration.class));
        verify(consumer2, times(1)).accept(any(TestListenerRegistration.class));
        verify(consumer3, times(1)).accept(any(TestListenerRegistration.class));
    }

    @Test
    public void testMergeMultiple() {
        Consumer<TestListenerRegistration> consumer1 = mock(Consumer.class);
        Consumer<TestListenerRegistration> consumer2 = mock(Consumer.class);
        Consumer<TestListenerRegistration> consumer3 = mock(Consumer.class);

        mMultiplexer.addListener(0, consumer1);
        mInOrder.verify(mCallbacks).onRegister();
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer1),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onActive();
        mMultiplexer.addListener(1, consumer2);
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer2),
                any(TestListenerRegistration.class));
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(1);

        mMultiplexer.notifyListeners();
        verify(consumer1, times(1)).accept(any(TestListenerRegistration.class));
        verify(consumer2, times(1)).accept(any(TestListenerRegistration.class));
        verify(consumer3, times(0)).accept(any(TestListenerRegistration.class));

        mMultiplexer.addListener(0, consumer3);
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer3),
                any(TestListenerRegistration.class));
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(1);

        mMultiplexer.notifyListeners();
        verify(consumer1, times(2)).accept(any(TestListenerRegistration.class));
        verify(consumer2, times(2)).accept(any(TestListenerRegistration.class));
        verify(consumer3, times(1)).accept(any(TestListenerRegistration.class));

        mMultiplexer.removeListener(consumer2);
        mInOrder.verify(mCallbacks).onRegistrationRemoved(eq(consumer2),
                any(TestListenerRegistration.class));
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(0);

        mMultiplexer.notifyListeners();
        verify(consumer1, times(3)).accept(any(TestListenerRegistration.class));
        verify(consumer2, times(2)).accept(any(TestListenerRegistration.class));
        verify(consumer3, times(2)).accept(any(TestListenerRegistration.class));

        mMultiplexer.removeListener(consumer1);
        mInOrder.verify(mCallbacks).onRegistrationRemoved(eq(consumer1),
                any(TestListenerRegistration.class));
        mMultiplexer.removeListener(consumer3);
        mInOrder.verify(mCallbacks).onInactive();
        mInOrder.verify(mCallbacks).onRegistrationRemoved(eq(consumer3),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onUnregister();
    }

    @Test
    public void testBufferUpdateService() {
        Consumer<TestListenerRegistration> consumer1 = mock(Consumer.class);
        Consumer<TestListenerRegistration> consumer2 = mock(Consumer.class);
        Consumer<TestListenerRegistration> consumer3 = mock(Consumer.class);

        try (UpdateServiceLock ignored = mMultiplexer.newUpdateServiceLock()) {
            mMultiplexer.addListener(0, consumer1);
            mMultiplexer.addListener(1, consumer2);
            mMultiplexer.addListener(2, consumer3);
        }

        assertThat(mMultiplexer.mMergeCount).isEqualTo(1);
    }

    @Test
    public void testInactive() {
        Consumer<TestListenerRegistration> consumer = mock(Consumer.class);

        mMultiplexer.addListener(0, consumer);
        mInOrder.verify(mCallbacks).onRegister();
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onActive();
        mMultiplexer.setActive(0, false);
        mInOrder.verify(mCallbacks).onInactive();
        assertThat(mMultiplexer.mRegistered).isFalse();

        mMultiplexer.notifyListeners();
        verify(consumer, never()).accept(any(TestListenerRegistration.class));

        mMultiplexer.setActive(0, true);
        mInOrder.verify(mCallbacks).onActive();
        assertThat(mMultiplexer.mRegistered).isTrue();
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(0);

        mMultiplexer.notifyListeners();
        verify(consumer).accept(any(TestListenerRegistration.class));
    }

    @Test
    public void testMergeMultiple_Inactive() {
        Consumer<TestListenerRegistration> consumer1 = mock(Consumer.class);
        Consumer<TestListenerRegistration> consumer2 = mock(Consumer.class);
        Consumer<TestListenerRegistration> consumer3 = mock(Consumer.class);

        mMultiplexer.addListener(0, consumer1);
        mInOrder.verify(mCallbacks).onRegister();
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer1),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onActive();
        mMultiplexer.addListener(2, consumer2);
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer2),
                any(TestListenerRegistration.class));
        mMultiplexer.addListener(1, consumer3);
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer3),
                any(TestListenerRegistration.class));
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(2);

        mMultiplexer.notifyListeners();
        verify(consumer1, times(1)).accept(any(TestListenerRegistration.class));
        verify(consumer2, times(1)).accept(any(TestListenerRegistration.class));
        verify(consumer3, times(1)).accept(any(TestListenerRegistration.class));

        mMultiplexer.setActive(2, false);
        assertThat(mMultiplexer.mRegistered).isTrue();
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(1);

        mMultiplexer.notifyListeners();
        verify(consumer1, times(2)).accept(any(TestListenerRegistration.class));
        verify(consumer2, times(1)).accept(any(TestListenerRegistration.class));
        verify(consumer3, times(2)).accept(any(TestListenerRegistration.class));

        mMultiplexer.setActive(2, true);
        assertThat(mMultiplexer.mRegistered).isTrue();
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(2);

        mMultiplexer.notifyListeners();
        verify(consumer1, times(3)).accept(any(TestListenerRegistration.class));
        verify(consumer2, times(2)).accept(any(TestListenerRegistration.class));
        verify(consumer3, times(3)).accept(any(TestListenerRegistration.class));

        mMultiplexer.setActive(0, false);
        mMultiplexer.setActive(1, false);
        mMultiplexer.setActive(2, false);
        mInOrder.verify(mCallbacks).onInactive();
        assertThat(mMultiplexer.mRegistered).isFalse();
    }

    @Test
    public void testReentrancy() {
        BadTestMultiplexer mManager = new BadTestMultiplexer(mCallbacks);

        assertThrows(IllegalStateException.class,
                () -> mManager.addListener(0, mock(Consumer.class)));
    }

    @Test
    public void testRemoveLater() {
        Consumer<TestListenerRegistration> consumer1 = new Consumer<TestListenerRegistration>() {
            @Override
            public void accept(TestListenerRegistration registration) {
                mMultiplexer.removeListener(this, registration);
            }
        };
        Consumer<TestListenerRegistration> consumer2 = new Consumer<TestListenerRegistration>() {
            @Override
            public void accept(TestListenerRegistration registration) {
                mMultiplexer.removeListener(this, registration);
            }
        };

        mMultiplexer.addListener(0, consumer1);
        mMultiplexer.addListener(0, consumer2);
        mInOrder.verify(mCallbacks).onRegister();
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer1),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onActive();
        mInOrder.verify(mCallbacks).onRegistrationAdded(eq(consumer2),
                any(TestListenerRegistration.class));

        mMultiplexer.notifyListeners();
        mInOrder.verify(mCallbacks).onInactive();
        // cannot verify in order because listener execution order is unspecified
        verify(mCallbacks).onRegistrationRemoved(eq(consumer2),
                any(TestListenerRegistration.class));
        verify(mCallbacks).onRegistrationRemoved(eq(consumer1),
                any(TestListenerRegistration.class));
        mInOrder.verify(mCallbacks).onUnregister();
    }

    private static class TestListenerRegistration extends
            RequestListenerRegistration<Integer, Consumer<TestListenerRegistration>> {

        boolean mActive = true;

        protected TestListenerRegistration(Integer integer,
                Consumer<TestListenerRegistration> consumer) {
            super(DIRECT_EXECUTOR, integer, consumer);
        }
    }

    private static class TestMultiplexer extends
            ListenerMultiplexer<Consumer<TestListenerRegistration>,
                    Consumer<TestListenerRegistration>, TestListenerRegistration, Integer> {

        boolean mRegistered;
        int mMergedRequest;
        int mMergeCount;
        Callbacks mCallbacks;

        TestMultiplexer(Callbacks callbacks) {
            mCallbacks = callbacks;
        }

        @Override
        public String getTag() {
            return "TestMultiplexer";
        }

        public void addListener(Integer request, Consumer<TestListenerRegistration> consumer) {
            putRegistration(consumer, new TestListenerRegistration(request, consumer));
        }

        public void replaceListener(Integer request, Consumer<TestListenerRegistration> oldConsumer,
                Consumer<TestListenerRegistration> consumer) {
            replaceRegistration(oldConsumer, consumer,
                    new TestListenerRegistration(request, consumer));
        }

        public void removeListener(Consumer<TestListenerRegistration> consumer) {
            removeRegistration(consumer);
        }

        public void removeListener(Consumer<TestListenerRegistration> consumer,
                TestListenerRegistration registration) {
            removeRegistration(consumer, registration);
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
            deliverToListeners(registration -> consumer -> consumer.accept(registration));
        }

        @Override
        protected boolean registerWithService(Integer mergedRequest,
                Collection<TestListenerRegistration> registrations) {
            mRegistered = true;
            mMergedRequest = mergedRequest;
            return true;
        }

        @Override
        protected void unregisterWithService() {
            mRegistered = false;
        }

        @Override
        protected boolean isActive(TestListenerRegistration registration) {
            return registration.mActive;
        }

        @Override
        protected void onActive() {
            mCallbacks.onActive();
        }

        @Override
        protected void onInactive() {
            mCallbacks.onInactive();
        }

        @Override
        protected void onRegister() {
            mCallbacks.onRegister();
        }

        @Override
        protected void onUnregister() {
            mCallbacks.onUnregister();
        }

        @Override
        protected void onRegistrationAdded(Consumer<TestListenerRegistration> consumer,
                TestListenerRegistration registration) {
            mCallbacks.onRegistrationAdded(consumer, registration);
        }

        @Override
        protected void onRegistrationReplaced(Consumer<TestListenerRegistration> consumer,
                TestListenerRegistration oldRegistration,
                TestListenerRegistration newRegistration) {
            mCallbacks.onRegistrationReplaced(consumer, oldRegistration, newRegistration);
        }

        @Override
        protected void onRegistrationRemoved(Consumer<TestListenerRegistration> consumer,
                TestListenerRegistration registration) {
            mCallbacks.onRegistrationRemoved(consumer, registration);
        }

        @Override
        protected Integer mergeRegistrations(
                Collection<TestListenerRegistration> testRegistrations) {
            int max = Integer.MIN_VALUE;
            for (TestListenerRegistration registration : testRegistrations) {
                if (registration.getRequest() > max) {
                    max = registration.getRequest();
                }
            }
            mMergeCount++;
            return max;
        }
    }

    private static class BadTestMultiplexer extends TestMultiplexer {

        BadTestMultiplexer(Callbacks callbacks) {
            super(callbacks);
        }

        @Override
        protected void onRegistrationAdded(Consumer<TestListenerRegistration> consumer,
                TestListenerRegistration registration) {
            addListener(registration.getRequest(), consumer);
        }
    }
}
