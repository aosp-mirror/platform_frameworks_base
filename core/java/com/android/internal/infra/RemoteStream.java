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

import static java.util.concurrent.TimeUnit.SECONDS;

import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;

import com.android.internal.util.FunctionalUtils.ThrowingConsumer;
import com.android.internal.util.FunctionalUtils.ThrowingFunction;

import libcore.io.IoUtils;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

/**
 * Utility class for streaming bytes across IPC, using standard APIs such as
 * {@link InputStream}/{@link OutputStream} or simply {@code byte[]}
 *
 * <p>
 * To use this, you'll want to declare your IPC methods to accept a {@link ParcelFileDescriptor},
 * and call them from within lambdas passed to {@link #receiveBytes}/{@link #sendBytes},
 * passing on the provided {@link ParcelFileDescriptor}.
 *
 * <p>
 * E.g.:
 * {@code
 *     //IFoo.aidl
 *     oneway interface IFoo {
 *         void sendGreetings(in ParcelFileDescriptor pipe);
 *         void receiveGreetings(in ParcelFileDescriptor pipe);
 *     }
 *
 *     //Foo.java
 *     mServiceConnector.postAsync(service -> RemoteStream.sendBytes(
 *             pipe -> service.sendGreetings(pipe, greetings)))...
 *
 *     mServiceConnector.postAsync(service -> RemoteStream.receiveBytes(
 *                    pipe -> service.receiveGreetings(pipe)))
 *                .whenComplete((greetings, err) -> ...);
 * }
 *
 * <p>
 * Each operation has a 30 second timeout by default, as it's possible for an operation to be
 * stuck forever otherwise.
 * You can {@link #cancelTimeout cancel} and/or {@link #orTimeout set a custom timeout}, using the
 * {@link AndroidFuture} you get as a result.
 *
 * <p>
 * You can also {@link #cancel} the operation, which will result in closing the underlying
 * {@link ParcelFileDescriptor}.
 *
 * @see #sendBytes
 * @see #receiveBytes
 *
 * @param <RES> the result of a successful streaming.
 * @param <IOSTREAM> either {@link InputStream} or {@link OutputStream} depending on the direction.
 */
public abstract class RemoteStream<RES, IOSTREAM extends Closeable>
        extends AndroidFuture<RES>
        implements Runnable {

    private final ThrowingFunction<IOSTREAM, RES> mHandleStream;
    private volatile ParcelFileDescriptor mLocalPipe;

    /**
     * Call an IPC, and process incoming bytes as an {@link InputStream} within {@code read}.
     *
     * @param ipc action to perform the IPC. Called directly on the calling thread.
     * @param read action to read from an {@link InputStream}, transforming data into {@code R}.
     *             Called asynchronously on the background thread.
     * @param <R> type of the end result of reading the bytes (if any).
     * @return an {@link AndroidFuture} that can be used to track operation's completion and
     *         retrieve its result (if any).
     */
    public static <R> AndroidFuture<R> receiveBytes(
            ThrowingConsumer<ParcelFileDescriptor> ipc, ThrowingFunction<InputStream, R> read) {
        return new RemoteStream<R, InputStream>(
                ipc, read, AsyncTask.THREAD_POOL_EXECUTOR, true /* read */) {
            @Override
            protected InputStream createStream(ParcelFileDescriptor fd) {
                return new ParcelFileDescriptor.AutoCloseInputStream(fd);
            }
        };
    }

    /**
     * Call an IPC, and asynchronously return incoming bytes as {@code byte[]}.
     *
     * @param ipc action to perform the IPC. Called directly on the calling thread.
     * @return an {@link AndroidFuture} that can be used to track operation's completion and
     *         retrieve its result.
     */
    public static AndroidFuture<byte[]> receiveBytes(ThrowingConsumer<ParcelFileDescriptor> ipc) {
        return receiveBytes(ipc, RemoteStream::readAll);
    }

    /**
     * Convert a given {@link InputStream} into {@code byte[]}.
     *
     * <p>
     * This doesn't close the given {@link InputStream}
     */
    public static byte[] readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream combinedBuffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        while (true) {
            int numRead = inputStream.read(buffer);
            if (numRead == -1) {
                break;
            }
            combinedBuffer.write(buffer, 0, numRead);
        }
        return combinedBuffer.toByteArray();
    }

    /**
     * Call an IPC, and perform sending bytes via an {@link OutputStream} within {@code write}.
     *
     * @param ipc action to perform the IPC. Called directly on the calling thread.
     * @param write action to write to an {@link OutputStream}, optionally returning operation
     *              result as {@code R}. Called asynchronously on the background thread.
     * @param <R> type of the end result of writing the bytes (if any).
     * @return an {@link AndroidFuture} that can be used to track operation's completion and
     *         retrieve its result (if any).
     */
    public static <R> AndroidFuture<R> sendBytes(
            ThrowingConsumer<ParcelFileDescriptor> ipc, ThrowingFunction<OutputStream, R> write) {
        return new RemoteStream<R, OutputStream>(
                ipc, write, AsyncTask.THREAD_POOL_EXECUTOR, false /* read */) {
            @Override
            protected OutputStream createStream(ParcelFileDescriptor fd) {
                return new ParcelFileDescriptor.AutoCloseOutputStream(fd);
            }
        };
    }

    /**
     * Same as {@link #sendBytes(ThrowingConsumer, ThrowingFunction)}, but explicitly avoids
     * returning a result.
     */
    public static AndroidFuture<Void> sendBytes(
            ThrowingConsumer<ParcelFileDescriptor> ipc, ThrowingConsumer<OutputStream> write) {
        return sendBytes(ipc, os -> {
            write.acceptOrThrow(os);
            return null;
        });
    }

    /**
     * Same as {@link #sendBytes(ThrowingConsumer, ThrowingFunction)}, but providing the data to
     * send eagerly as {@code byte[]}.
     */
    public static AndroidFuture<Void> sendBytes(
            ThrowingConsumer<ParcelFileDescriptor> ipc, byte[] data) {
        return sendBytes(ipc, os -> {
            os.write(data);
            return null;
        });
    }

    private RemoteStream(
            ThrowingConsumer<ParcelFileDescriptor> ipc,
            ThrowingFunction<IOSTREAM, RES> handleStream,
            Executor backgroundExecutor,
            boolean read) {
        mHandleStream = handleStream;

        ParcelFileDescriptor[] pipe;
        try {
            //TODO consider using createReliablePipe
            pipe = ParcelFileDescriptor.createPipe();
            try (ParcelFileDescriptor remotePipe = pipe[read ? 1 : 0]) {
                ipc.acceptOrThrow(remotePipe);
                // Remote pipe end is duped by binder call. Local copy is not needed anymore
            }

            mLocalPipe = pipe[read ? 0 : 1];
            backgroundExecutor.execute(this);

            // Guard against getting stuck forever
            orTimeout(30, SECONDS);
        } catch (Throwable e) {
            completeExceptionally(e);
            // mLocalPipe closes in #onCompleted
        }
    }

    protected abstract IOSTREAM createStream(ParcelFileDescriptor fd);

    @Override
    public void run() {
        try (IOSTREAM stream = createStream(mLocalPipe)) {
            complete(mHandleStream.applyOrThrow(stream));
        } catch (Throwable t) {
            completeExceptionally(t);
        }
    }

    @Override
    protected void onCompleted(RES res, Throwable err) {
        super.onCompleted(res, err);
        IoUtils.closeQuietly(mLocalPipe);
    }
}
