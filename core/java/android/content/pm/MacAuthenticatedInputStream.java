/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.content.pm;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.crypto.Mac;

/**
 * An input stream filter that applies a MAC to the data passing through it. At
 * the end of the data that should be authenticated, the tag can be calculated.
 * After that, the stream should not be used.
 *
 * @hide
 */
public class MacAuthenticatedInputStream extends FilterInputStream {
    private final Mac mMac;

    public MacAuthenticatedInputStream(InputStream in, Mac mac) {
        super(in);

        mMac = mac;
    }

    public boolean isTagEqual(byte[] tag) {
        final byte[] actualTag = mMac.doFinal();

        if (tag == null || actualTag == null || tag.length != actualTag.length) {
            return false;
        }

        /*
         * Attempt to prevent timing attacks by doing the same amount of work
         * whether the first byte matches or not. Do not change this to a loop
         * that exits early when a byte does not match.
         */
        int value = 0;
        for (int i = 0; i < tag.length; i++) {
            value |= tag[i] ^ actualTag[i];
        }

        return value == 0;
    }

    @Override
    public int read() throws IOException {
        final int b = super.read();
        if (b >= 0) {
            mMac.update((byte) b);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int count) throws IOException {
        int numRead = super.read(buffer, offset, count);
        if (numRead > 0) {
            mMac.update(buffer, offset, numRead);
        }
        return numRead;
    }
}
