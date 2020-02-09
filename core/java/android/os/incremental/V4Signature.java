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

package android.os.incremental;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * V4 signature fields.
 * Keep in sync with APKSig master copy.
 * @hide
 */
public class V4Signature {
    public final byte[] verityRootHash;
    public final byte[] v3Digest;
    public final byte[] pkcs7SignatureBlock;

    V4Signature(byte[] verityRootHash, byte[] v3Digest, byte[] pkcs7SignatureBlock) {
        this.verityRootHash = verityRootHash;
        this.v3Digest = v3Digest;
        this.pkcs7SignatureBlock = pkcs7SignatureBlock;
    }

    static byte[] readBytes(DataInputStream stream) throws IOException {
        byte[] result = new byte[stream.readInt()];
        stream.read(result);
        return result;
    }

    static V4Signature readFrom(DataInputStream stream) throws IOException {
        byte[] verityRootHash = readBytes(stream);
        byte[] v3Digest = readBytes(stream);
        byte[] pkcs7SignatureBlock = readBytes(stream);
        return new V4Signature(verityRootHash, v3Digest, pkcs7SignatureBlock);
    }

    static void writeBytes(DataOutputStream stream, byte[] bytes) throws IOException {
        stream.writeInt(bytes.length);
        stream.write(bytes);
    }

    void writeTo(DataOutputStream stream) throws IOException {
        writeBytes(stream, this.verityRootHash);
        writeBytes(stream, this.v3Digest);
        writeBytes(stream, this.pkcs7SignatureBlock);
    }
}
