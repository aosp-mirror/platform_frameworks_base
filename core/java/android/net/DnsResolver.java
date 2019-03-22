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

package android.net;

import static android.net.NetworkUtils.resNetworkCancel;
import static android.net.NetworkUtils.resNetworkQuery;
import static android.net.NetworkUtils.resNetworkResult;
import static android.net.NetworkUtils.resNetworkSend;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.CancellationSignal;
import android.os.Looper;
import android.system.ErrnoException;
import android.util.Log;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Dns resolver class for asynchronous dns querying
 *
 * Note that if a client sends a query with more than 1 record in the question section but
 * the remote dns server does not support this, it may not respond at all, leading to a timeout.
 *
 */
public final class DnsResolver {
    private static final String TAG = "DnsResolver";
    private static final int FD_EVENTS = EVENT_INPUT | EVENT_ERROR;
    private static final int MAXPACKET = 8 * 1024;

    @IntDef(prefix = { "CLASS_" }, value = {
            CLASS_IN
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface QueryClass {}
    public static final int CLASS_IN = 1;

    @IntDef(prefix = { "TYPE_" },  value = {
            TYPE_A,
            TYPE_AAAA
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface QueryType {}
    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;

    @IntDef(prefix = { "FLAG_" }, value = {
            FLAG_EMPTY,
            FLAG_NO_RETRY,
            FLAG_NO_CACHE_STORE,
            FLAG_NO_CACHE_LOOKUP
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface QueryFlag {}
    public static final int FLAG_EMPTY = 0;
    public static final int FLAG_NO_RETRY = 1 << 0;
    public static final int FLAG_NO_CACHE_STORE = 1 << 1;
    public static final int FLAG_NO_CACHE_LOOKUP = 1 << 2;

    private static final int NETID_UNSET = 0;

    private static final DnsResolver sInstance = new DnsResolver();

    /**
     * Get instance for DnsResolver
     */
    public static @NonNull DnsResolver getInstance() {
        return sInstance;
    }

    private DnsResolver() {}

    /**
     * Answer parser for parsing raw answers
     *
     * @param <T> The type of the parsed answer
     */
    public interface AnswerParser<T> {
        /**
         * Creates a <T> answer by parsing the given raw answer.
         *
         * @param rawAnswer the raw answer to be parsed
         * @return a parsed <T> answer
         * @throws ParseException if parsing failed
         */
        @NonNull T parse(@NonNull byte[] rawAnswer) throws ParseException;
    }

    /**
     * Base class for answer callbacks
     *
     * @param <T> The type of the parsed answer
     */
    public abstract static class AnswerCallback<T> {
        /** @hide */
        public final AnswerParser<T> parser;

        public AnswerCallback(@NonNull AnswerParser<T> parser) {
            this.parser = parser;
        };

        /**
         * Success response to
         * {@link android.net.DnsResolver#query query()}.
         *
         * Invoked when the answer to a query was successfully parsed.
         *
         * @param answer parsed answer to the query.
         *
         * {@see android.net.DnsResolver#query query()}
         */
        public abstract void onAnswer(@NonNull T answer);

        /**
         * Error response to
         * {@link android.net.DnsResolver#query query()}.
         *
         * Invoked when there is no valid answer to
         * {@link android.net.DnsResolver#query query()}
         *
         * @param exception a {@link ParseException} object with additional
         *    detail regarding the failure
         */
        public abstract void onParseException(@NonNull ParseException exception);

        /**
         * Error response to
         * {@link android.net.DnsResolver#query query()}.
         *
         * Invoked if an error happens when
         * issuing the DNS query or receiving the result.
         * {@link android.net.DnsResolver#query query()}
         *
         * @param exception an {@link ErrnoException} object with additional detail
         *    regarding the failure
         */
        public abstract void onQueryException(@NonNull ErrnoException exception);
    }

    /**
     * Callback for receiving raw answers
     */
    public abstract static class RawAnswerCallback extends AnswerCallback<byte[]> {
        public RawAnswerCallback() {
            super(rawAnswer -> rawAnswer);
        }
    }

    /**
     * Callback for receiving parsed {@link InetAddress} answers
     *
     * Note that if the answer does not contain any IP addresses,
     * onAnswer will be called with an empty list.
     */
    public abstract static class InetAddressAnswerCallback
            extends AnswerCallback<List<InetAddress>> {
        public InetAddressAnswerCallback() {
            super(rawAnswer -> new DnsAddressAnswer(rawAnswer).getAddresses());
        }
    }

    /**
     * Send a raw DNS query.
     * The answer will be provided asynchronously through the provided {@link AnswerCallback}.
     *
     * @param network {@link Network} specifying which network for querying.
     *         {@code null} for query on default network.
     * @param query blob message
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback an {@link AnswerCallback} which will be called to notify the caller
     *    of the result of dns query.
     */
    public <T> void query(@Nullable Network network, @NonNull byte[] query, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull AnswerCallback<T> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }
        final FileDescriptor queryfd;
        try {
            queryfd = resNetworkSend((network != null
                ? network.netId : NETID_UNSET), query, query.length, flags);
        } catch (ErrnoException e) {
            callback.onQueryException(e);
            return;
        }

        maybeAddCancellationSignal(cancellationSignal, queryfd);
        registerFDListener(executor, queryfd, callback);
    }

    /**
     * Send a DNS query with the specified name, class and query type.
     * The answer will be provided asynchronously through the provided {@link AnswerCallback}.
     *
     * @param network {@link Network} specifying which network for querying.
     *         {@code null} for query on default network.
     * @param domain domain name for querying
     * @param nsClass dns class as one of the CLASS_* constants
     * @param nsType dns resource record (RR) type as one of the TYPE_* constants
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback an {@link AnswerCallback} which will be called to notify the caller
     *    of the result of dns query.
     */
    public <T> void query(@Nullable Network network, @NonNull String domain,
            @QueryClass int nsClass, @QueryType int nsType, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull AnswerCallback<T> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }
        final FileDescriptor queryfd;
        try {
            queryfd = resNetworkQuery((network != null
                    ? network.netId : NETID_UNSET), domain, nsClass, nsType, flags);
        } catch (ErrnoException e) {
            callback.onQueryException(e);
            return;
        }

        maybeAddCancellationSignal(cancellationSignal, queryfd);
        registerFDListener(executor, queryfd, callback);
    }

    private <T> void registerFDListener(@NonNull Executor executor,
            @NonNull FileDescriptor queryfd, @NonNull AnswerCallback<T> answerCallback) {
        Looper.getMainLooper().getQueue().addOnFileDescriptorEventListener(
                queryfd,
                FD_EVENTS,
                (fd, events) -> {
                    executor.execute(() -> {
                        byte[] answerbuf = null;
                        try {
                            answerbuf = resNetworkResult(fd);
                        } catch (ErrnoException e) {
                            Log.e(TAG, "resNetworkResult:" + e.toString());
                            answerCallback.onQueryException(e);
                            return;
                        }

                        try {
                            answerCallback.onAnswer(
                                    answerCallback.parser.parse(answerbuf));
                        } catch (ParseException e) {
                            answerCallback.onParseException(e);
                        }
                    });
                    // Unregister this fd listener
                    return 0;
                });
    }

    private void maybeAddCancellationSignal(@Nullable CancellationSignal cancellationSignal,
            @NonNull FileDescriptor queryfd) {
        if (cancellationSignal == null) return;
        cancellationSignal.setOnCancelListener(
                () -> {
                    Looper.getMainLooper().getQueue()
                            .removeOnFileDescriptorEventListener(queryfd);
                    resNetworkCancel(queryfd);
            });
    }

    private static class DnsAddressAnswer extends DnsPacket {
        private static final String TAG = "DnsResolver.DnsAddressAnswer";
        private static final boolean DBG = false;

        private final int mQueryType;

        DnsAddressAnswer(@NonNull byte[] data) throws ParseException {
            super(data);
            if ((mHeader.flags & (1 << 15)) == 0) {
                throw new ParseException("Not an answer packet");
            }
            if (mHeader.getRecordCount(QDSECTION) == 0) {
                throw new ParseException("No question found");
            }
            // Expect only one question in question section.
            mQueryType = mRecords[QDSECTION].get(0).nsType;
        }

        public @NonNull List<InetAddress> getAddresses() {
            final List<InetAddress> results = new ArrayList<InetAddress>();
            if (mHeader.getRecordCount(ANSECTION) == 0) return results;

            for (final DnsRecord ansSec : mRecords[ANSECTION]) {
                // Only support A and AAAA, also ignore answers if query type != answer type.
                int nsType = ansSec.nsType;
                if (nsType != mQueryType || (nsType != TYPE_A && nsType != TYPE_AAAA)) {
                    continue;
                }
                try {
                    results.add(InetAddress.getByAddress(ansSec.getRR()));
                } catch (UnknownHostException e) {
                    if (DBG) {
                        Log.w(TAG, "rr to address fail");
                    }
                }
            }
            return results;
        }
    }

}
