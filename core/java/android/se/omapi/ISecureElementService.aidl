/*
 * Copyright (C) 2017, The Android Open Source Project
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
/*
 * Copyright (c) 2015-2017, The Linux Foundation.
 */
/*
 * Contributed by: Giesecke & Devrient GmbH.
 */

package android.se.omapi;

import android.se.omapi.ISecureElementReader;

/**
 * SecureElement service interface.
 * @hide
 */
interface ISecureElementService {

    /**
     * Returns the friendly names of available Secure Element readers.
     */
    String[] getReaders();

    /**
     * Returns SecureElement Service reader object to the given name.
     */
    ISecureElementReader getReader(String reader);

    /**
     * Checks if the application defined by the package name is allowed to
     * receive NFC transaction events for the defined AID.
     */
    boolean[] isNFCEventAllowed(String reader, in byte[] aid,
            in String[] packageNames);

}
