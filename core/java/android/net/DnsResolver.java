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
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.CancellationSignal;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
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
    private static final int SLEEP_TIME = 2;

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
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param query blob message to query
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
        final Object lock = new Object();
        final FileDescriptor queryfd;
        try {
            queryfd = resNetworkSend((network != null
                ? network.netId : NETID_UNSET), query, query.length, flags);
        } catch (ErrnoException e) {
            executor.execute(() -> {
                callback.onQueryException(e);
            });
            return;
        }

        registerFDListener(executor, queryfd, callback, cancellationSignal, lock);
        maybeAddCancellationSignal(cancellationSignal, queryfd, lock);
    }

    /**
     * Send a DNS query with the specified name, class and query type.
     * The answer will be provided asynchronously through the provided {@link AnswerCallback}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param domain domain name to query
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
        final Object lock = new Object();
        final FileDescriptor queryfd;
        try {
            queryfd = resNetworkQuery((network != null
                    ? network.netId : NETID_UNSET), domain, nsClass, nsType, flags);
        } catch (ErrnoException e) {
            executor.execute(() -> {
                callback.onQueryException(e);
            });
            return;
        }

        registerFDListener(executor, queryfd, callback, cancellationSignal, lock);
        maybeAddCancellationSignal(cancellationSignal, queryfd, lock);
    }

    private class InetAddressAnswerAccumulator extends InetAddressAnswerCallback {
        private final List<InetAddress> mAllAnswers;
        private ParseException mParseException;
        private ErrnoException mErrnoException;
        private final InetAddressAnswerCallback mUserCallback;
        private final int mTargetAnswerCount;
        private int mReceivedAnswerCount = 0;

        InetAddressAnswerAccumulator(int size, @NonNull InetAddressAnswerCallback callback) {
            mTargetAnswerCount = size;
            mAllAnswers = new ArrayList<>();
            mUserCallback = callback;
        }

        private boolean maybeReportException() {
            if (mErrnoException != null) {
                mUserCallback.onQueryException(mErrnoException);
                return true;
            }
            if (mParseException != null) {
                mUserCallback.onParseException(mParseException);
                return true;
            }
            return false;
        }

        private void maybeReportAnswer() {
            if (++mReceivedAnswerCount != mTargetAnswerCount) return;
            if (mAllAnswers.isEmpty() && maybeReportException()) return;
            // TODO: Do RFC6724 sort.
            mUserCallback.onAnswer(mAllAnswers);
        }

        @Override
        public void onAnswer(@NonNull List<InetAddress> answer) {
            mAllAnswers.addAll(answer);
            maybeReportAnswer();
        }

        @Override
        public void onParseException(@NonNull ParseException e) {
            mParseException = e;
            maybeReportAnswer();
        }

        @Override
        public void onQueryException(@NonNull ErrnoException e) {
            mErrnoException = e;
            maybeReportAnswer();
        }
    }

    /**
     * Send a DNS query with the specified name, get back a set of InetAddresses asynchronously.
     * The answer will be provided asynchronously through the provided
     * {@link InetAddressAnswerCallback}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param domain domain name to query
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback an {@link InetAddressAnswerCallback} which will be called to notify the
     *    caller of the result of dns query.
     */
    public void query(@Nullable Network network, @NonNull String domain, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull InetAddressAnswerCallback callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }
        final Object lock = new Object();
        final boolean queryIpv6 = haveIpv6(network);
        final boolean queryIpv4 = haveIpv4(network);

        final FileDescriptor v4fd;
        final FileDescriptor v6fd;

        int queryCount = 0;

        if (queryIpv6) {
            try {
                v6fd = resNetworkQuery((network != null
                        ? network.netId : NETID_UNSET), domain, CLASS_IN, TYPE_AAAA, flags);
            } catch (ErrnoException e) {
                executor.execute(() -> {
                    callback.onQueryException(e);
                });
                return;
            }
            queryCount++;
        } else v6fd = null;

        // TODO: Use device flag to controll the sleep time.
        // Avoiding gateways drop packets if queries are sent too close together
        try {
            Thread.sleep(SLEEP_TIME);
        } catch (InterruptedException ex) { }

        if (queryIpv4) {
            try {
                v4fd = resNetworkQuery((network != null
                        ? network.netId : NETID_UNSET), domain, CLASS_IN, TYPE_A, flags);
            } catch (ErrnoException e) {
                if (queryIpv6) resNetworkCancel(v6fd);  // Closes fd, marks it invalid.
                executor.execute(() -> {
                    callback.onQueryException(e);
                });
                return;
            }
            queryCount++;
        } else v4fd = null;

        final InetAddressAnswerAccumulator accumulator =
                new InetAddressAnswerAccumulator(queryCount, callback);

        if (queryIpv6) registerFDListener(executor, v6fd, accumulator, cancellationSignal, lock);
        if (queryIpv4) registerFDListener(executor, v4fd, accumulator, cancellationSignal, lock);

        if (cancellationSignal == null) return;
        cancellationSignal.setOnCancelListener(() -> {
            synchronized (lock)  {
                if (queryIpv4) cancelQuery(v4fd);
                if (queryIpv6) cancelQuery(v6fd);
            }
        });
    }

    private <T> void registerFDListener(@NonNull Executor executor,
            @NonNull FileDescriptor queryfd, @NonNull AnswerCallback<T> answerCallback,
            @Nullable CancellationSignal cancellationSignal, @NonNull Object lock) {
        Looper.getMainLooper().getQueue().addOnFileDescriptorEventListener(
                queryfd,
                FD_EVENTS,
                (fd, events) -> {
                    executor.execute(() -> {
                        synchronized (lock) {
                            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                                return;
                            }
                            byte[] answerbuf = null;
                            try {
                                answerbuf = resNetworkResult(fd);  // Closes fd, marks it invalid.
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
                        }
                    });
                    // Unregister this fd listener
                    return 0;
                });
    }

    private void cancelQuery(@NonNull FileDescriptor queryfd) {
        if (!queryfd.valid()) return;
        Looper.getMainLooper().getQueue().removeOnFileDescriptorEventListener(queryfd);
        resNetworkCancel(queryfd);  // Closes fd, marks it invalid.
    }

    private void maybeAddCancellationSignal(@Nullable CancellationSignal cancellationSignal,
            @NonNull FileDescriptor queryfd, @NonNull Object lock) {
        if (cancellationSignal == null) return;
        cancellationSignal.setOnCancelListener(() -> {
            synchronized (lock)  {
                cancelQuery(queryfd);
            }
        });
    }

    // These two functions match the behaviour of have_ipv4 and have_ipv6 in the native resolver.
    private boolean haveIpv4(@Nullable Network network) {
        final SocketAddress addrIpv4 =
                new InetSocketAddress(InetAddresses.parseNumericAddress("8.8.8.8"), 0);
        return checkConnectivity(network, AF_INET, addrIpv4);
    }

    private boolean haveIpv6(@Nullable Network network) {
        final SocketAddress addrIpv6 =
                new InetSocketAddress(InetAddresses.parseNumericAddress("2000::"), 0);
        return checkConnectivity(network, AF_INET6, addrIpv6);
    }

    private boolean checkConnectivity(@Nullable Network network,
            int domain, @NonNull SocketAddress addr) {
        final FileDescriptor socket;
        try {
            socket = Os.socket(domain, SOCK_DGRAM, IPPROTO_UDP);
        } catch (ErrnoException e) {
            return false;
        }
        try {
            if (network != null) network.bindSocket(socket);
            Os.connect(socket, addr);
        } catch (IOException | ErrnoException e) {
            return false;
        } finally {
            IoUtils.closeQuietly(socket);
        }
        return true;
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
