/*
 * Copyright (c) 2015 The Android Open Source Project
 * Copyright (c) 2015 Samsung LSI
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

package javax.obex;

import java.io.IOException;
import java.io.InputStream;

public class ObexPacket {
    public int mHeaderId;
    public int mLength;
    public byte[] mPayload = null;

    private ObexPacket(int headerId, int length) {
        mHeaderId = headerId;
        mLength = length;
    }

    /**
     * Create a complete OBEX packet by reading data from an InputStream.
     * @param is the input stream to read from.
     * @return the OBEX packet read.
     * @throws IOException if an IO exception occurs during read.
     */
    public static ObexPacket read(InputStream is) throws IOException {
        int headerId = is.read();
        return read(headerId, is);
    }

    /**
     * Read the remainder of an OBEX packet, with a specified headerId.
     * @param headerId the headerId already read from the stream.
     * @param is the stream to read from, assuming 1 byte have already been read.
     * @return the OBEX packet read.
     * @throws IOException
     */
    public static ObexPacket read(int headerId, InputStream is) throws IOException {
        // Read the 2 byte length field from the stream
        int length = is.read();
        length = (length << 8) + is.read();

        ObexPacket newPacket = new ObexPacket(headerId, length);

        int bytesReceived;
        byte[] temp = null;
        if (length > 3) {
            // First three bytes already read, compensating for this
            temp = new byte[length - 3];
            bytesReceived = is.read(temp);
            while (bytesReceived != temp.length) {
                bytesReceived += is.read(temp, bytesReceived, temp.length - bytesReceived);
            }
        }
        newPacket.mPayload = temp;
        return newPacket;
    }
}
