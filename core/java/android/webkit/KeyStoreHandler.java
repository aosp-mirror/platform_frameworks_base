/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.webkit;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

/**
 * KeyStoreHandler: class responsible for certificate installation to
 * the system key store. It reads the certificates file from network
 * then pass the bytes to class CertTool.
 * This class is only needed if the Chromium HTTP stack is used.
 */
class KeyStoreHandler extends Handler {
    private static final String LOGTAG = "KeyStoreHandler";

    private final ByteArrayBuilder mDataBuilder = new ByteArrayBuilder();

    private String mMimeType;

    public KeyStoreHandler(String mimeType) {
      mMimeType = mimeType;
    }

    /**
     * Add data to the internal collection of data.
     * @param data A byte array containing the content.
     * @param length The length of data.
     */
    public void didReceiveData(byte[] data, int length) {
        synchronized (mDataBuilder) {
            mDataBuilder.append(data, 0, length);
        }
    }

    public void installCert(Context context) {
        String type = CertTool.getCertType(mMimeType);
        if (type == null) return;

        // This must be synchronized so that no more data can be added
        // after getByteSize returns.
        synchronized (mDataBuilder) {
            // In the case of downloading certificate, we will save it
            // to the KeyStore and stop the current loading so that it
            // will not generate a new history page
            byte[] cert = new byte[mDataBuilder.getByteSize()];
            int offset = 0;
            while (true) {
                ByteArrayBuilder.Chunk c = mDataBuilder.getFirstChunk();
                if (c == null) break;

                if (c.mLength != 0) {
                    System.arraycopy(c.mArray, 0, cert, offset, c.mLength);
                    offset += c.mLength;
                }
                c.release();
            }
            CertTool.addCertificate(context, type, cert);
            return;
        }
    }
}
