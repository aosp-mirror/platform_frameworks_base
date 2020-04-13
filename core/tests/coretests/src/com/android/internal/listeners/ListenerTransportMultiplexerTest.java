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

package com.android.internal.listeners;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.os.Looper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ListenerTransportMultiplexerTest extends TestCase {

    TestMultiplexer mMultiplexer;

    @Before
    public void setUp() {
        mMultiplexer = new TestMultiplexer();
    }

    @Test
    public void testAdd() {
        Runnable runnable = mock(Runnable.class);

        mMultiplexer.addListener(0, runnable, Runnable::run);
        assertThat(mMultiplexer.mRegistered).isTrue();
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(0);

        mMultiplexer.notifyListeners();
        verify(runnable, times(1)).run();
    }

    @Test
    public void testAdd_Multiple() {
        Runnable runnable1 = mock(Runnable.class);
        Runnable runnable2 = mock(Runnable.class);

        mMultiplexer.addListener(0, runnable1, Runnable::run);
        mMultiplexer.addListener(0, runnable2, Runnable::run);

        mMultiplexer.notifyListeners();
        verify(runnable1).run();
        verify(runnable2).run();
    }

    @Test
    public void testRemove() {
        Runnable runnable = mock(Runnable.class);

        mMultiplexer.addListener(0, runnable, Runnable::run);
        mMultiplexer.removeListener(runnable);
        assertThat(mMultiplexer.mRegistered).isFalse();

        mMultiplexer.notifyListeners();
        verify(runnable, never()).run();
    }

    @Test
    public void testRemove_Multiple() {
        Runnable runnable1 = mock(Runnable.class);
        Runnable runnable2 = mock(Runnable.class);

        mMultiplexer.addListener(0, runnable1, Runnable::run);
        mMultiplexer.addListener(1, runnable2, Runnable::run);
        mMultiplexer.removeListener(runnable1);

        mMultiplexer.notifyListeners();
        verify(runnable1, never()).run();
        verify(runnable2).run();
    }

    @Test
    public void testMergeMultiple() {
        Runnable runnable1 = mock(Runnable.class);
        Runnable runnable2 = mock(Runnable.class);
        Runnable runnable3 = mock(Runnable.class);

        mMultiplexer.addListener(0, runnable1, Runnable::run);
        mMultiplexer.addListener(1, runnable2, Runnable::run);
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(1);

        mMultiplexer.notifyListeners();
        verify(runnable1, times(1)).run();
        verify(runnable2, times(1)).run();
        verify(runnable3, times(0)).run();

        mMultiplexer.addListener(0, runnable3, Runnable::run);
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(1);

        mMultiplexer.notifyListeners();
        verify(runnable1, times(2)).run();
        verify(runnable2, times(2)).run();
        verify(runnable3, times(1)).run();

        mMultiplexer.removeListener(runnable2);
        assertThat(mMultiplexer.mMergedRequest).isEqualTo(0);

        mMultiplexer.notifyListeners();
        verify(runnable1, times(3)).run();
        verify(runnable2, times(2)).run();
        verify(runnable3, times(2)).run();

        mMultiplexer.removeListener(runnable1);
        mMultiplexer.removeListener(runnable3);
        mMultiplexer.notifyListeners();
        verify(runnable1, times(3)).run();
        verify(runnable2, times(2)).run();
        verify(runnable3, times(2)).run();
    }

    @Test(timeout = 5000)
    public void testReentrancy() {
        AtomicReference<Runnable> runnable = new AtomicReference<>();
        runnable.set(() -> mMultiplexer.removeListener(runnable.get()));

        mMultiplexer.addListener(0, runnable.get(), command -> {
            CountDownLatch latch = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                command.run();
                latch.countDown();
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });

        mMultiplexer.notifyListeners();
        assertThat(mMultiplexer.mRegistered).isFalse();
    }

    private static class TestMultiplexer extends ListenerTransportMultiplexer<Integer, Runnable> {

        boolean mRegistered;
        int mMergedRequest;

        TestMultiplexer() {
        }

        public void notifyListeners() {
            deliverToListeners(Runnable::run);
        }

        @Override
        protected void registerWithServer(Integer mergedRequest) {
            mRegistered = true;
            mMergedRequest = mergedRequest;
        }

        @Override
        protected void unregisterWithServer() {
            mRegistered = false;
        }

        @Override
        protected Integer mergeRequests(Collection<Integer> requests) {
            return requests.stream().max(Comparator.naturalOrder()).get();
        }
    }
}
