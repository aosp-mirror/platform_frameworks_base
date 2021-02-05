/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.apphibernation;

import static org.junit.Assert.assertEquals;

import android.os.FileUtils;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


@SmallTest
public class HibernationStateDiskStoreTest {
    private static final String STATES_FILE_NAME = "states";
    private final MockScheduledExecutorService mMockScheduledExecutorService =
            new MockScheduledExecutorService();

    private File mFile;
    private HibernationStateDiskStore<String> mHibernationStateDiskStore;


    @Before
    public void setUp() {
        mFile = new File(InstrumentationRegistry.getContext().getCacheDir(), "test");
        mHibernationStateDiskStore = new HibernationStateDiskStore<>(mFile,
                new MockProtoReadWriter(), mMockScheduledExecutorService, STATES_FILE_NAME);
    }

    @After
    public void tearDown() {
        FileUtils.deleteContentsAndDir(mFile);
    }

    @Test
    public void testScheduleWriteHibernationStates_writesDataThatCanBeRead() {
        // GIVEN some data to be written
        List<String> toWrite = new ArrayList<>(Arrays.asList("A", "B"));

        // WHEN the data is written
        mHibernationStateDiskStore.scheduleWriteHibernationStates(toWrite);
        mMockScheduledExecutorService.executeScheduledTask();

        // THEN the read data is equal to what was written
        List<String> storedStrings = mHibernationStateDiskStore.readHibernationStates();
        for (int i = 0; i < toWrite.size(); i++) {
            assertEquals(toWrite.get(i), storedStrings.get(i));
        }
    }

    @Test
    public void testScheduleWriteHibernationStates_laterWritesOverwritePrevious() {
        // GIVEN store has some data it is scheduled to write
        mHibernationStateDiskStore.scheduleWriteHibernationStates(
                new ArrayList<>(Arrays.asList("C", "D")));

        // WHEN a write is scheduled with new data
        List<String> toWrite = new ArrayList<>(Arrays.asList("A", "B"));
        mHibernationStateDiskStore.scheduleWriteHibernationStates(toWrite);
        mMockScheduledExecutorService.executeScheduledTask();

        // THEN the written data is the last scheduled data
        List<String> storedStrings = mHibernationStateDiskStore.readHibernationStates();
        for (int i = 0; i < toWrite.size(); i++) {
            assertEquals(toWrite.get(i), storedStrings.get(i));
        }
    }

    /**
     * Mock proto read / writer that just writes and reads a list of String data.
     */
    private final class MockProtoReadWriter implements ProtoReadWriter<List<String>> {
        private static final long FIELD_ID = 1;

        @Override
        public void writeToProto(@NonNull ProtoOutputStream stream,
                @NonNull List<String> data) {
            for (int i = 0, size = data.size(); i < size; i++) {
                stream.write(FIELD_ID, data.get(i));
            }
        }

        @Nullable
        @Override
        public List<String> readFromProto(@NonNull ProtoInputStream stream)
                throws IOException {
            ArrayList<String> list = new ArrayList<>();
            while (stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                list.add(stream.readString(FIELD_ID));
            }
            return list;
        }
    }

    /**
     * Mock scheduled executor service that has minimum implementation and can synchronously
     * execute scheduled tasks.
     */
    private final class MockScheduledExecutorService implements ScheduledExecutorService {

        Runnable mScheduledRunnable = null;

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            mScheduledRunnable = command;
            return Mockito.mock(ScheduledFuture.class);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout,
                TimeUnit unit) throws InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }

        void executeScheduledTask() {
            mScheduledRunnable.run();
        }
    }
}
