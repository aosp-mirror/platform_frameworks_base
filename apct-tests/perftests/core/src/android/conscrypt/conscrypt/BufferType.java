/*
 * Copyright 2017 The Android Open Source Project
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
package android.conscrypt;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;

/**
 * Enumeration that provides allocation of direct or heap buffers.
 */
@SuppressWarnings("unused")
public enum BufferType {
    HEAP {
        @Override
        ByteBuffer newBuffer(int size) {
            return ByteBuffer.allocate(size);
        }
    },
    DIRECT {
        @Override
        ByteBuffer newBuffer(int size) {
            return ByteBuffer.allocateDirect(size);
        }
    };

    abstract ByteBuffer newBuffer(int size);

    ByteBuffer newApplicationBuffer(SSLEngine engine) {
        return newBuffer(engine.getSession().getApplicationBufferSize());
    }

    ByteBuffer newPacketBuffer(SSLEngine engine) {
        return newBuffer(engine.getSession().getPacketBufferSize());
    }
}