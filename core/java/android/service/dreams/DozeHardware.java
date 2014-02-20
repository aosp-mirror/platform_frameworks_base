/**
 * Copyright (C) 2014 The Android Open Source Project
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
package android.service.dreams;

import android.os.RemoteException;
import android.util.Log;

/**
 * Provides access to low-level hardware features that a dream may use to provide
 * a richer user experience while dozing.
 * <p>
 * This class contains functions that should be called by the dream to configure
 * hardware before starting to doze and allowing the application processor to suspend.
 * For example, the dream may provide the hardware with enough information to render
 * some content on its own without any further assistance from the application processor.
 * </p><p>
 * This object is obtained by calling {@link DreamService#getDozeHardware()}.
 * </p>
 *
 * @hide experimental
 */
public final class DozeHardware {
    private static final String TAG = "DozeHardware";

    public static final String MSG_ENABLE_MCU = "enable_mcu";

    public static final byte[] VALUE_ON = "on".getBytes();
    public static final byte[] VALUE_OFF = "off".getBytes();

    private final IDozeHardware mHardware;

    DozeHardware(IDozeHardware hardware) {
        mHardware = hardware;
    }

    /**
     * Sets whether to enable the microcontroller.
     *
     * @param enable If true, enables the MCU otherwise disables it.
     */
    public void setEnableMcu(boolean enable) {
        sendMessage(MSG_ENABLE_MCU, enable ? VALUE_ON : VALUE_OFF);
    }

    /**
     * Sends a message to the doze hardware module.
     *
     * @param msg The name of the message to send.
     * @param arg An optional argument data blob, may be null.
     * @return A result data blob, may be null.
     */
    public byte[] sendMessage(String msg, byte[] arg) {
        if (msg == null) {
            throw new IllegalArgumentException("msg must not be null");
        }

        try {
            return mHardware.sendMessage(msg, arg);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to send message to doze hardware module.", ex);
            return null;
        }
    }
}
