/*
 * Copyright (C) 2018 The Android Open Source Project
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

/**
 * A Java wrapper for the JNI function to perform the password hashing algorithm SCRYPT.
 *
 * @hide
 */
public class Scrypt {

    native byte[] nativeScrypt(byte[] password, byte[] salt, int n, int r, int p, int outLen);

    /** Computes the password hashing algorithm SCRYPT. */
    public byte[] scrypt(byte[] password, byte[] salt, int n, int r, int p, int outLen) {
        return nativeScrypt(password, salt, n, r, p, outLen);
    }
}
