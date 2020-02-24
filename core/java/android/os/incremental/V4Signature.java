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

import android.os.ParcelFileDescriptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * V4 signature fields.
 * Keep in sync with APKSig master copy.
 * @hide
 */
public class V4Signature {
    public static final String EXT = ".idsig";
    public static final int SUPPORTED_VERSION = 1;

    public final int version;
    public final byte[] verityRootHash;
    public final byte[] v3Digest;
    public final byte[] pkcs7SignatureBlock;

    /**
     * Construct a V4Signature from .idsig file.
     */
    public static V4Signature readFrom(ParcelFileDescriptor pfd) throws IOException {
        final ParcelFileDescriptor dupedFd = pfd.dup();
        final ParcelFileDescriptor.AutoCloseInputStream fdInputStream =
                new ParcelFileDescriptor.AutoCloseInputStream(dupedFd);
        try (DataInputStream stream = new DataInputStream(fdInputStream)) {
            return readFrom(stream);
        }
    }

    /**
     * Construct a V4Signature from .idsig file.
     */
    public static V4Signature readFrom(byte[] bytes) throws IOException {
        try (DataInputStream stream = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return readFrom(stream);
        }
    }

    /**
     * Store the V4Signature to a byte-array.
     */
    public byte[] toByteArray() {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            try (DataOutputStream steam = new DataOutputStream(byteArrayOutputStream)) {
                this.writeTo(steam);
                steam.flush();
            }
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    boolean isVersionSupported() {
        return this.version == SUPPORTED_VERSION;
    }

    static V4Signature readFrom(DataInputStream stream) throws IOException {
        final int version = stream.readInt();
        byte[] verityRootHash = readBytes(stream);
        byte[] v3Digest = readBytes(stream);
        byte[] pkcs7SignatureBlock = readBytes(stream);
        return new V4Signature(version, verityRootHash, v3Digest, pkcs7SignatureBlock);
    }

    V4Signature(int version, byte[] verityRootHash, byte[] v3Digest, byte[] pkcs7SignatureBlock) {
        this.version = version;
        this.verityRootHash = verityRootHash;
        this.v3Digest = v3Digest;
        this.pkcs7SignatureBlock = pkcs7SignatureBlock;
    }

    void writeTo(DataOutputStream stream) throws IOException {
        stream.writeInt(this.version);
        writeBytes(stream, this.verityRootHash);
        writeBytes(stream, this.v3Digest);
        writeBytes(stream, this.pkcs7SignatureBlock);
    }

    private static byte[] readBytes(DataInputStream stream) throws IOException {
        byte[] result = new byte[stream.readInt()];
        stream.read(result);
        return result;
    }

    private static void writeBytes(DataOutputStream stream, byte[] bytes) throws IOException {
        stream.writeInt(bytes.length);
        stream.write(bytes);
    }
}
