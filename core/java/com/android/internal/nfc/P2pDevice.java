/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.nfc;

import java.io.IOException;

/**
 * P2pDevice is the abstract base class for all supported P2P targets the
 * NfcManager can handle.
 */
public abstract class P2pDevice {

    /**
     * Peer-to-Peer Target.
     */
    public static final short MODE_P2P_TARGET = 0x00;

    /**
     * Peer-to-Peer Initiator.
     */
    public static final short MODE_P2P_INITIATOR = 0x01;

    /**
     * Invalid target type.
     */
    public static final short MODE_INVALID = 0xff;

    /**
     * Target handle, used by native calls.
     */
    protected int mHandle;
	
    /**
     * Flag set when the object is closed and thus not usable any more.
     */
	protected boolean isClosed = false;

    /**
     * Prevent default constructor to be public.
     */
	protected P2pDevice() {
	}

	/**
     * Returns the remote NFC-IP1 General Bytes.
     * 
     * @return remote general bytes
	 * @throws IOException 
     */
    public byte[] getGeneralBytes() throws IOException {
        // Should not be called directly (use subclasses overridden method instead)
        return null;
    }

    /**
     * Returns target type. The value returned can be one of the TYPE_*
     * constants.
     * 
     * @return target type.
     */
    public int getMode() {
        // Should not be called directly (use subclasses overridden method instead)
        return MODE_INVALID;
    }
}
