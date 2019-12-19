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

package com.android.internal.infra;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.os.Parcel;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

/**
 * Unit test for {@link AndroidFuture}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:com.android.internal.infra.AndroidFutureTest}
 */

@RunWith(AndroidJUnit4.class)
public class AndroidFutureTest {
    @Test
    public void testGet() throws Exception {
        AndroidFuture<Integer> future = new AndroidFuture<>();
        future.complete(5);
        assertThat(future.get()).isEqualTo(5);
    }

    @Test
    public void testWhenComplete_AlreadyComplete() throws Exception {
        AndroidFuture<Integer> future = new AndroidFuture<>();
        future.complete(5);
        CountDownLatch latch = new CountDownLatch(1);
        future.whenComplete((obj, err) -> {
            assertThat(obj).isEqualTo(5);
            assertThat(err).isNull();
            latch.countDown();
        });
        latch.await();
    }

    @Test
    public void testWhenComplete_NotYetComplete() throws Exception {
        AndroidFuture<Integer> future = new AndroidFuture<>();
        CountDownLatch latch = new CountDownLatch(1);
        future.whenComplete((obj, err) -> {
            assertThat(obj).isEqualTo(5);
            assertThat(err).isNull();
            latch.countDown();
        });
        assertThat(latch.getCount()).isEqualTo(1);
        future.complete(5);
        latch.await();
        assertThat(latch.getCount()).isEqualTo(0);
    }

    @Test
    public void testCompleteExceptionally() {
        AndroidFuture<Integer> future = new AndroidFuture<>();
        Exception origException = new UnsupportedOperationException();
        future.completeExceptionally(origException);
        ExecutionException executionException =
                expectThrows(ExecutionException.class, future::get);
        assertThat(executionException.getCause()).isSameAs(origException);
    }

    @Test
    public void testCompleteExceptionally_Listener() throws Exception {
        AndroidFuture<Integer> future = new AndroidFuture<>();
        Exception origException = new UnsupportedOperationException();
        future.completeExceptionally(origException);
        CountDownLatch latch = new CountDownLatch(1);
        future.whenComplete((obj, err) -> {
            assertThat(obj).isNull();
            assertThat(err).isSameAs(origException);
            latch.countDown();
        });
        latch.await();
    }

    @Test
    public void testWriteToParcel() throws Exception {
        Parcel parcel = Parcel.obtain();
        AndroidFuture<Integer> future1 = new AndroidFuture<>();
        future1.complete(5);
        future1.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);
        AndroidFuture future2 = AndroidFuture.CREATOR.createFromParcel(parcel);
        assertThat(future2.get()).isEqualTo(5);
    }

    @Test
    public void testWriteToParcel_Exception() throws Exception {
        Parcel parcel = Parcel.obtain();
        AndroidFuture<Integer> future1 = new AndroidFuture<>();
        future1.completeExceptionally(new UnsupportedOperationException());
        future1.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);
        AndroidFuture future2 = AndroidFuture.CREATOR.createFromParcel(parcel);
        ExecutionException executionException =
                expectThrows(ExecutionException.class, future2::get);

        Throwable cause = executionException.getCause();
        String msg = cause.getMessage();
        assertThat(cause).isInstanceOf(UnsupportedOperationException.class);
        assertThat(msg).contains(getClass().getName());
        assertThat(msg).contains("testWriteToParcel_Exception");
    }

    @Test
    public void testWriteToParcel_Incomplete() throws Exception {
        Parcel parcel = Parcel.obtain();
        AndroidFuture<Integer> future1 = new AndroidFuture<>();
        future1.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);
        AndroidFuture future2 = AndroidFuture.CREATOR.createFromParcel(parcel);
        future2.complete(5);
        assertThat(future1.get()).isEqualTo(5);
    }

    @Test
    public void testWriteToParcel_Incomplete_Exception() throws Exception {
        Parcel parcel = Parcel.obtain();
        AndroidFuture<Integer> future1 = new AndroidFuture<>();
        future1.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);
        AndroidFuture future2 = AndroidFuture.CREATOR.createFromParcel(parcel);
        future2.completeExceptionally(new UnsupportedOperationException());
        ExecutionException executionException =
                expectThrows(ExecutionException.class, future1::get);
        assertThat(executionException.getCause()).isInstanceOf(UnsupportedOperationException.class);
    }
}
