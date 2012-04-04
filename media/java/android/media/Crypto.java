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

package android.media;

/**
 * Crypto class can be used in conjunction with MediaCodec to decode
 * encrypted media data.
 * @hide
*/
public final class Crypto {
    public static final native boolean isCryptoSchemeSupported(byte[] uuid);

    public Crypto(byte[] uuid, byte[] initData) {
        native_setup(uuid, initData);
    }

    public final native boolean requiresSecureDecoderComponent(String mime);

    @Override
    protected void finalize() {
        native_finalize();
    }

    public native final void release();
    private static native final void native_init();
    private native final void native_setup(byte[] uuid, byte[] initData);
    private native final void native_finalize();

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private int mNativeContext;
}
