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

package android.debug;

import java.io.File;

/**
 * This class allows the control of ADB-related functions that should only be called from the system
 * server.
 *
 * @hide Only should be called from the system server.
 */
public abstract class AdbManagerInternal {
    /**
     * Registers a ADB transport mechanism.
     *
     * @param transport ADB transport interface to register
     */
    public abstract void registerTransport(IAdbTransport transport);

    /**
     * Unregisters a previously registered ADB transport mechanism.
     *
     * @param transport previously-added ADB transport interface to be removed
     */
    public abstract void unregisterTransport(IAdbTransport transport);

    /**
     * Returns {@code true} if ADB debugging is enabled.
     */
    public abstract boolean isAdbEnabled(byte transportType);

    /**
     * Returns the file that contains all of the ADB keys used by the device.
     */
    public abstract File getAdbKeysFile();

    /**
     * Returns the file that contains all of the ADB keys and their last used time.
     */
    public abstract File getAdbTempKeysFile();

    /**
     * Notify the AdbManager that the key files have changed and any in-memory state should be
     * reloaded.
     */
    public abstract void notifyKeyFilesUpdated();

    /**
     * Starts adbd for a transport.
     */
    public abstract void startAdbdForTransport(byte transportType);

    /**
     * Stops adbd for a transport.
     */
    public abstract void stopAdbdForTransport(byte transportType);
}
