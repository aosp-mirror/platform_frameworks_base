/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keystore;

import android.security.KeyStore;
import android.security.KeyStoreException;

/**
 * Helper for streaming a crypto operation's input and output via {@link KeyStore} service's
 * {@code update} and {@code finish} operations.
 *
 * <p>The helper abstracts away to issues that need to be solved in most code that uses KeyStore's
 * update and finish operations. Firstly, KeyStore's update operation can consume only a limited
 * amount of data in one go because the operations are marshalled via Binder. Secondly, the update
 * operation may consume less data than provided, in which case the caller has to buffer the
 * remainder for next time. The helper exposes {@link #update(byte[], int, int) update} and
 * {@link #doFinal(byte[], int, int, byte[], byte[]) doFinal} operations which can be used to
 * conveniently implement various JCA crypto primitives.
 *
 * @hide
 */
interface KeyStoreCryptoOperationStreamer {
    byte[] update(byte[] input, int inputOffset, int inputLength) throws KeyStoreException;
    byte[] doFinal(byte[] input, int inputOffset, int inputLength, byte[] signature,
            byte[] additionalEntropy) throws KeyStoreException;
    long getConsumedInputSizeBytes();
    long getProducedOutputSizeBytes();
}
