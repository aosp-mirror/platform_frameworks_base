/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.security;

import android.annotation.NonNull;
import android.app.KeyguardManager;
import android.content.Context;
import android.security.keystore.KeyProtection;

import java.security.KeyPairGenerator;
import java.security.KeyStore.ProtectionParameter;

/**
 * This provides the optional parameters that can be specified for
 * {@code KeyStore} entries that work with
 * <a href="{@docRoot}training/articles/keystore.html">Android KeyStore
 * facility</a>. The Android KeyStore facility is accessed through a
 * {@link java.security.KeyStore} API using the {@code AndroidKeyStore}
 * provider. The {@code context} passed in may be used to pop up some UI to ask
 * the user to unlock or initialize the Android KeyStore facility.
 * <p>
 * Any entries placed in the {@code KeyStore} may be retrieved later. Note that
 * there is only one logical instance of the {@code KeyStore} per application
 * UID so apps using the {@code sharedUid} facility will also share a
 * {@code KeyStore}.
 * <p>
 * Keys may be generated using the {@link KeyPairGenerator} facility with a
 * {@link KeyPairGeneratorSpec} to specify the entry's {@code alias}. A
 * self-signed X.509 certificate will be attached to generated entries, but that
 * may be replaced at a later time by a certificate signed by a real Certificate
 * Authority.
 *
 * @deprecated Use {@link KeyProtection} instead.
 */
@Deprecated
public final class KeyStoreParameter implements ProtectionParameter {
    private final int mFlags;

    private KeyStoreParameter(
            int flags) {
        mFlags = flags;
    }

    /**
     * @hide
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Returns {@code true} if the {@link java.security.KeyStore} entry must be encrypted at rest.
     * This will protect the entry with the secure lock screen credential (e.g., password, PIN, or
     * pattern).
     *
     * <p>Note that encrypting the key at rest requires that the secure lock screen (e.g., password,
     * PIN, pattern) is set up, otherwise key generation will fail. Moreover, this key will be
     * deleted when the secure lock screen is disabled or reset (e.g., by the user or a Device
     * Administrator). Finally, this key cannot be used until the user unlocks the secure lock
     * screen after boot.
     *
     * @see KeyguardManager#isDeviceSecure()
     */
    public boolean isEncryptionRequired() {
        return (mFlags & KeyStore.FLAG_ENCRYPTED) != 0;
    }

    /**
     * Builder class for {@link KeyStoreParameter} objects.
     * <p>
     * This will build protection parameters for use with the
     * <a href="{@docRoot}training/articles/keystore.html">Android KeyStore
     * facility</a>.
     * <p>
     * This can be used to require that KeyStore entries be stored encrypted.
     * <p>
     * Example:
     *
     * <pre class="prettyprint">
     * KeyStoreParameter params = new KeyStoreParameter.Builder(mContext)
     *         .setEncryptionRequired()
     *         .build();
     * </pre>
     *
     *  @deprecated Use {@link KeyProtection.Builder} instead.
     */
    @Deprecated
    public final static class Builder {
        private int mFlags;

        /**
         * Creates a new instance of the {@code Builder} with the given
         * {@code context}. The {@code context} passed in may be used to pop up
         * some UI to ask the user to unlock or initialize the Android KeyStore
         * facility.
         */
        public Builder(@NonNull Context context) {
            if (context == null) {
                throw new NullPointerException("context == null");
            }
        }

        /**
         * Sets whether this {@link java.security.KeyStore} entry must be encrypted at rest.
         * Encryption at rest will protect the entry with the secure lock screen credential (e.g.,
         * password, PIN, or pattern).
         *
         * <p>Note that enabling this feature requires that the secure lock screen (e.g., password,
         * PIN, pattern) is set up, otherwise setting the {@code KeyStore} entry will fail.
         * Moreover, this entry will be deleted when the secure lock screen is disabled or reset
         * (e.g., by the user or a Device Administrator). Finally, this entry cannot be used until
         * the user unlocks the secure lock screen after boot.
         *
         * @see KeyguardManager#isDeviceSecure()
         */
        @NonNull
        public Builder setEncryptionRequired(boolean required) {
            if (required) {
                mFlags |= KeyStore.FLAG_ENCRYPTED;
            } else {
                mFlags &= ~KeyStore.FLAG_ENCRYPTED;
            }
            return this;
        }

        /**
         * Builds the instance of the {@code KeyStoreParameter}.
         *
         * @throws IllegalArgumentException if a required field is missing
         * @return built instance of {@code KeyStoreParameter}
         */
        @NonNull
        public KeyStoreParameter build() {
            return new KeyStoreParameter(
                    mFlags);
        }
    }
}
