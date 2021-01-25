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

package android.net;

import static android.net.QosCallbackException.EX_TYPE_FILTER_NONE;
import static android.net.QosCallbackException.EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Objects;

/**
 * Filters a {@link QosSession} according to the binding on the provided {@link Socket}.
 *
 * @hide
 */
public class QosSocketFilter extends QosFilter {

    private static final String TAG = QosSocketFilter.class.getSimpleName();

    @NonNull
    private final QosSocketInfo mQosSocketInfo;

    /**
     * Creates a {@link QosSocketFilter} based off of {@link QosSocketInfo}.
     *
     * @param qosSocketInfo the information required to filter and validate
     */
    public QosSocketFilter(@NonNull final QosSocketInfo qosSocketInfo) {
        Objects.requireNonNull(qosSocketInfo, "qosSocketInfo must be non-null");
        mQosSocketInfo = qosSocketInfo;
    }

    /**
     * Gets the parcelable qos socket info that was used to create the filter.
     */
    @NonNull
    public QosSocketInfo getQosSocketInfo() {
        return mQosSocketInfo;
    }

    /**
     * Performs two validations:
     * 1. If the socket is not bound, then return
     *    {@link QosCallbackException.EX_TYPE_FILTER_SOCKET_NOT_BOUND}. This is detected
     *    by checking the local address on the filter which becomes null when the socket is no
     *    longer bound.
     * 2. In the scenario that the socket is now bound to a different local address, which can
     *    happen in the case of UDP, then
     *    {@link QosCallbackException.EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED} is returned.
     * @return validation error code
     */
    @Override
    public int validate() {
        final InetSocketAddress sa = getAddressFromFileDescriptor();
        if (sa == null) {
            return QosCallbackException.EX_TYPE_FILTER_SOCKET_NOT_BOUND;
        }

        if (!sa.equals(mQosSocketInfo.getLocalSocketAddress())) {
            return EX_TYPE_FILTER_SOCKET_LOCAL_ADDRESS_CHANGED;
        }

        return EX_TYPE_FILTER_NONE;
    }

    /**
     * The local address of the socket's binding.
     *
     * Note: If the socket is no longer bound, null is returned.
     *
     * @return the local address
     */
    @Nullable
    private InetSocketAddress getAddressFromFileDescriptor() {
        final ParcelFileDescriptor parcelFileDescriptor = mQosSocketInfo.getParcelFileDescriptor();
        if (parcelFileDescriptor == null) return null;

        final FileDescriptor fd = parcelFileDescriptor.getFileDescriptor();
        if (fd == null) return null;

        final SocketAddress address;
        try {
            address = Os.getsockname(fd);
        } catch (final ErrnoException e) {
            Log.e(TAG, "getAddressFromFileDescriptor: getLocalAddress exception", e);
            return null;
        }
        if (address instanceof InetSocketAddress) {
            return (InetSocketAddress) address;
        }
        return null;
    }

    /**
     * The network used with this filter.
     *
     * @return the registered {@link Network}
     */
    @NonNull
    @Override
    public Network getNetwork() {
        return mQosSocketInfo.getNetwork();
    }

    /**
     * @inheritDoc
     */
    @Override
    public boolean matchesLocalAddress(@NonNull final InetAddress address, final int startPort,
            final int endPort) {
        if (mQosSocketInfo.getLocalSocketAddress() == null) {
            return false;
        }

        return matchesLocalAddress(mQosSocketInfo.getLocalSocketAddress(), address, startPort,
                endPort);
    }

    /**
     * Called from {@link QosSocketFilter#matchesLocalAddress(InetAddress, int, int)} with the
     * filterSocketAddress coming from {@link QosSocketInfo#getLocalSocketAddress()}.
     * <p>
     * This method exists for testing purposes since {@link QosSocketInfo} couldn't be mocked
     * due to being final.
     *
     * @param filterSocketAddress the socket address of the filter
     * @param address the address to compare the filterSocketAddressWith
     * @param startPort the start of the port range to check
     * @param endPort the end of the port range to check
     */
    @VisibleForTesting
    public static boolean matchesLocalAddress(@NonNull final InetSocketAddress filterSocketAddress,
            @NonNull final InetAddress address,
            final int startPort, final int endPort) {
        return startPort <= filterSocketAddress.getPort()
                && endPort >= filterSocketAddress.getPort()
                && filterSocketAddress.getAddress().equals(address);
    }
}
