/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

/**
 * {@hide}
 *
 * Simple Roughtime client class for retrieving network time.
 */
public class RoughtimeClient
{
    private static final String TAG = "RoughtimeClient";
    private static final boolean ENABLE_DEBUG = true;

    private static final int ROUGHTIME_PORT = 5333;

    private static final int MIN_REQUEST_SIZE = 1024;

    private static final int NONCE_SIZE = 64;

    private static final int MAX_DATAGRAM_SIZE = 65507;

    private final SecureRandom random = new SecureRandom();

    /**
     * Tag values. Exposed for use in tests only.
     */
    protected static enum Tag {
        /**
         * Nonce used to initiate a transaction.
         */
        NONC(0x434e4f4e),

        /**
         * Signed portion of a response.
         **/
        SREP(0x50455253),

        /**
         * Pad data. Always the largest tag lexicographically.
         */
        PAD(0xff444150),

        /**
         * A signature for a neighboring SREP.
         */
        SIG(0x474953),

        /**
         * Server certificate.
         */
        CERT(0x54524543),

        /**
         * Position in the Merkle tree.
         */
        INDX(0x58444e49),

        /**
         * Upward path in the Merkle tree.
         */
        PATH(0x48544150),

        /**
         * Midpoint of the time interval in the response.
         */
        MIDP(0x5044494d),

        /**
         * Radius of the time interval in the response.
         */
        RADI(0x49444152),

        /**
         * Root of the Merkle tree.
         */
        ROOT(0x544f4f52),

        /**
         * Delegation from the long term key to an online key.
         */
        DELE(0x454c4544),

        /**
         * Online public key.
         */
        PUBK(0x4b425550),

        /**
         * Earliest midpoint time the given PUBK can authenticate.
         */
        MINT(0x544e494d),

        /**
         * Latest midpoint time the given PUBK can authenticate.
         */
        MAXT(0x5458414d);

        private final int value;

        Tag(int value) {
            this.value = value;
        }

        private int value() {
            return value;
        }
    }

    /**
     * A result retrieved from a roughtime server.
     */
    private static class Result {
        public long midpoint;
        public int radius;
        public long collectionTime;
    }

    /**
     * A Roughtime protocol message. Functionally a serializable map from Tags
     * to byte arrays.
     */
    protected static class Message {
        private HashMap<Integer,byte[]> items = new HashMap<Integer,byte[]>();
        public int padSize = 0;

        public Message() {}

        /**
         * Set the given data for the given tag.
         */
        public void put(Tag tag, byte[] data) {
            put(tag.value(), data);
        }

        private void put(int tag, byte[] data) {
            items.put(tag, data);
        }

        /**
         * Get the data associated with the given tag.
         */
        public byte[] get(Tag tag) {
            return items.get(tag.value());
        }

        /**
         * Get the data associated with the given tag and decode it as a 64-bit
         * integer.
         */
        public long getLong(Tag tag) {
            ByteBuffer b = ByteBuffer.wrap(get(tag));
            return b.getLong();
        }

        /**
         * Get the data associated with the given tag and decode it as a 32-bit
         * integer.
         */
        public int getInt(Tag tag) {
            ByteBuffer b = ByteBuffer.wrap(get(tag));
            return b.getInt();
        }

        /**
         * Encode the given long value as a 64-bit little-endian value and
         * associate it with the given tag.
         */
        public void putLong(Tag tag, long l) {
            ByteBuffer b = ByteBuffer.allocate(8);
            b.putLong(l);
            put(tag, b.array());
        }

        /**
         * Encode the given int value as a 32-bit little-endian value and
         * associate it with the given tag.
         */
        public void putInt(Tag tag, int l) {
            ByteBuffer b = ByteBuffer.allocate(4);
            b.putInt(l);
            put(tag, b.array());
        }

        /**
         * Get a packed representation of this message suitable for the wire.
         */
        public byte[] serialize() {
            if (items.size() == 0) {
                if (padSize > 4)
                    return new byte[padSize];
                else
                    return new byte[4];
            }

            int size = 0;

            ArrayList<Integer> offsets = new ArrayList<Integer>();
            ArrayList<Integer> tagList = new ArrayList<Integer>(items.keySet());
            Collections.sort(tagList);

            boolean first = true;
            for (int tag : tagList) {
                if (! first) {
                    offsets.add(size);
                }

                first = false;
                size += items.get(tag).length;
            }

            ByteBuffer dataBuf = ByteBuffer.allocate(size);
            dataBuf.order(ByteOrder.LITTLE_ENDIAN);

            int valueDataSize = size;
            size += 4 + offsets.size() * 4 + tagList.size() * 4;

            int tagCount = items.size();

            if (size < padSize) {
                offsets.add(valueDataSize);
                tagList.add(Tag.PAD.value());

                if (size + 8 > padSize) {
                    size = size + 8;
                } else {
                    size = padSize;
                }

                tagCount += 1;
            }

            ByteBuffer buf = ByteBuffer.allocate(size);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(tagCount);

            for (int offset : offsets) {
                buf.putInt(offset);
            }

            for (int tag : tagList) {
                buf.putInt(tag);

                if (tag != Tag.PAD.value()) {
                    dataBuf.put(items.get(tag));
                }
            }

            buf.put(dataBuf.array());

            return buf.array();
        }

        /**
         * Given a byte stream from the wire, unpack it into a Message object.
         */
        public static Message deserialize(byte[] data) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            Message msg = new Message();

            int count = buf.getInt();

            if (count == 0) {
                return msg;
            }

            ArrayList<Integer> offsets = new ArrayList<Integer>();
            offsets.add(0);

            for (int i = 1; i < count; i++) {
                offsets.add(buf.getInt());
            }

            ArrayList<Integer> tags = new ArrayList<Integer>();
            for (int i = 0; i < count; i++) {
                int tag = buf.getInt();
                tags.add(tag);
            }

            offsets.add(buf.remaining());

            for (int i = 0; i < count; i++) {
                int tag = tags.get(i);
                int start = offsets.get(i);
                int end = offsets.get(i+1);
                byte[] content = new byte[end - start];

                buf.get(content);
                if (tag != Tag.PAD.value()) {
                    msg.put(tag, content);
                }
            }

            return msg;
        }

        /**
         * Send this message over the given socket to the given address and port.
         */
        public void send(DatagramSocket socket, InetAddress address, int port)
                throws IOException {
            byte[] buffer = serialize();
            DatagramPacket message = new DatagramPacket(buffer, buffer.length,
                    address, port);
            socket.send(message);
        }

        /**
         * Receive a Message object from the given socket.
         */
        public static Message receive(DatagramSocket socket)
                throws IOException {
            byte[] buffer = new byte[MAX_DATAGRAM_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            socket.receive(packet);

            return deserialize(Arrays.copyOf(buffer, packet.getLength()));
        }
    }

    private MessageDigest messageDigest = null;

    private final ArrayList<Result> results = new ArrayList<Result>();
    private long lastRequest = 0;

    private Message createRequestMessage() {
        byte[] nonce = new byte[NONCE_SIZE];
        random.nextBytes(nonce); // TODO: Chain nonces

        assert nonce.length == NONCE_SIZE :
            "Nonce must be " + NONCE_SIZE + " bytes.";

        Message msg = new Message();

        msg.put(Tag.NONC, nonce);
        msg.padSize = MIN_REQUEST_SIZE;

        return msg;
    }

    /**
     * Contact the Roughtime server at the given address and port and collect a
     * result time to add to our collection.
     *
     * @param host host name of the server.
     * @param timeout network timeout in milliseconds.
     * @return true if the transaction was successful.
     */
    public boolean requestTime(String host, int timeout) {
        InetAddress address = null;
        try {
            address = InetAddress.getByName(host);
        } catch (Exception e) {
            if (ENABLE_DEBUG) {
                Log.d(TAG, "request time failed", e);
            }

            return false;
        }
        return requestTime(address, ROUGHTIME_PORT, timeout);
    }

    /**
     * Contact the Roughtime server at the given address and port and collect a
     * result time to add to our collection.
     *
     * @param address address for the server.
     * @param port port to talk to the server on.
     * @param timeout network timeout in milliseconds.
     * @return true if the transaction was successful.
     */
    public boolean requestTime(InetAddress address, int port, int timeout) {

        final long rightNow = SystemClock.elapsedRealtime();

        if ((rightNow - lastRequest) > timeout) {
            results.clear();
        }

        lastRequest = rightNow;

        DatagramSocket socket = null;
        try {
            if (messageDigest == null) {
                messageDigest = MessageDigest.getInstance("SHA-512");
            }

            socket = new DatagramSocket();
            socket.setSoTimeout(timeout);
            final long startTime = SystemClock.elapsedRealtime();
            Message request = createRequestMessage();
            request.send(socket, address, port);
            final long endTime = SystemClock.elapsedRealtime();
            Message response = Message.receive(socket);
            byte[] signedData = response.get(Tag.SREP);
            Message signedResponse = Message.deserialize(signedData);

            final Result result = new Result();
            result.midpoint = signedResponse.getLong(Tag.MIDP);
            result.radius = signedResponse.getInt(Tag.RADI);
            result.collectionTime = (startTime + endTime) / 2;

            final byte[] root = signedResponse.get(Tag.ROOT);
            final byte[] path = response.get(Tag.PATH);
            final byte[] nonce = request.get(Tag.NONC);
            final int index = response.getInt(Tag.INDX);

            if (! verifyNonce(root, path, nonce, index)) {
                Log.w(TAG, "failed to authenticate roughtime response.");
                return false;
            }

            results.add(result);
        } catch (Exception e) {
            if (ENABLE_DEBUG) {
                Log.d(TAG, "request time failed", e);
            }

            return false;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }

        return true;
    }

    /**
     * Verify that a reply message corresponds to the nonce sent in the request.
     *
     * @param root  Root of the Merkle tree used to sign the nonce. Received in
     *              the ROOT tag of the reply.
     * @param path  Sibling hashes along the path to the root of the Merkle tree.
     *              Received in the PATH tag of the reply.
     * @param nonce The nonce we sent in the request.
     * @param index Bitfield indicating whether chunks of the path are left or
     *              right children.
     * @return true if the verification is successful.
     */
    private boolean verifyNonce(byte[] root, byte[] path, byte[] nonce,
            int index) {
        messageDigest.update(new byte[]{ 0 });
        byte[] hash = messageDigest.digest(nonce);
        int pos = 0;
        byte[] one = new byte[]{ 1 };

        while (pos < path.length) {
            messageDigest.update(one);

            if ((index&1) != 0) {
                messageDigest.update(path, pos, 64);
                hash = messageDigest.digest(hash);
            } else {
                messageDigest.update(hash);
                messageDigest.update(path, pos, 64);
                hash = messageDigest.digest();
            }

            pos += 64;
            index >>>= 1;
        }

        return Arrays.equals(root, hash);
    }
}
