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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Used in conjunction with
 * {@link ConnectivityManager#registerQosCallback}
 * in order to receive Qos Sessions related to the local address and port of a bound {@link Socket}.
 *
 * @hide
 */
@SystemApi
public final class QosSocketInfo implements Parcelable {

    @NonNull
    private final Network mNetwork;

    @NonNull
    private final ParcelFileDescriptor mParcelFileDescriptor;

    @NonNull
    private final InetSocketAddress mLocalSocketAddress;

    /**
     * The {@link Network} the socket is on.
     *
     * @return the registered {@link Network}
     */
    @NonNull
    public Network getNetwork() {
        return mNetwork;
    }

    /**
     * The parcel file descriptor wrapped around the socket's file descriptor.
     *
     * @return the parcel file descriptor of the socket
     */
    @NonNull
    ParcelFileDescriptor getParcelFileDescriptor() {
        return mParcelFileDescriptor;
    }

    /**
     * The local address of the socket passed into {@link QosSocketInfo(Network, Socket)}.
     * The value does not reflect any changes that occur to the socket after it is first set
     * in the constructor.
     *
     * @return the local address of the socket
     */
    @NonNull
    public InetSocketAddress getLocalSocketAddress() {
        return mLocalSocketAddress;
    }

    /**
     * Creates a {@link QosSocketInfo} given a {@link Network} and bound {@link Socket}.  The
     * {@link Socket} must remain bound in order to receive {@link QosSession}s.
     *
     * @param network the network
     * @param socket the bound {@link Socket}
     */
    public QosSocketInfo(@NonNull final Network network, @NonNull final Socket socket)
            throws IOException {
        Objects.requireNonNull(socket, "socket cannot be null");

        mNetwork = Objects.requireNonNull(network, "network cannot be null");
        mParcelFileDescriptor = ParcelFileDescriptor.dup(socket.getFileDescriptor$());
        mLocalSocketAddress =
                new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
    }

    /* Parcelable methods */
    private QosSocketInfo(final Parcel in) {
        mNetwork = Objects.requireNonNull(Network.CREATOR.createFromParcel(in));
        mParcelFileDescriptor = ParcelFileDescriptor.CREATOR.createFromParcel(in);

        final int addressLength = in.readInt();
        mLocalSocketAddress = readSocketAddress(in, addressLength);
    }

    private InetSocketAddress readSocketAddress(final Parcel in, final int addressLength) {
        final byte[] address = new byte[addressLength];
        in.readByteArray(address);
        final int port = in.readInt();

        try {
            return new InetSocketAddress(InetAddress.getByAddress(address), port);
        } catch (final UnknownHostException e) {
            /* The catch block was purposely left empty.  UnknownHostException will never be thrown
               since the address provided is numeric and non-null. */
        }
        return new InetSocketAddress();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        mNetwork.writeToParcel(dest, 0);
        mParcelFileDescriptor.writeToParcel(dest, 0);

        final byte[] address = mLocalSocketAddress.getAddress().getAddress();
        dest.writeInt(address.length);
        dest.writeByteArray(address);
        dest.writeInt(mLocalSocketAddress.getPort());
    }

    @NonNull
    public static final Parcelable.Creator<QosSocketInfo> CREATOR =
            new Parcelable.Creator<QosSocketInfo>() {
            @NonNull
            @Override
            public QosSocketInfo createFromParcel(final Parcel in) {
                return new QosSocketInfo(in);
            }

            @NonNull
            @Override
            public QosSocketInfo[] newArray(final int size) {
                return new QosSocketInfo[size];
            }
        };
}
