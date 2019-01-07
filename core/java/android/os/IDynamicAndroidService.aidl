/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.os;

import android.gsi.GsiProgress;

/** {@hide} */
interface IDynamicAndroidService
{
    /**
     * Start DynamicAndroid installation. This call may take 60~90 seconds. The caller
     * may use another thread to call the getStartProgress() to get the progress.
     *
     * @param systemSize system size in bytes
     * @param userdataSize userdata size in bytes
     * @return true if the call succeeds
     */
    boolean startInstallation(long systemSize, long userdataSize);

    /**
     * Query the progress of the current installation operation. This can be called while
     * the installation is in progress.
     *
     * @return GsiProgress
     */
    GsiProgress getInstallationProgress();

    /**
     * Abort the installation process. Note this method must be called in a thread other
     * than the one calling the startInstallation method as the startInstallation
     * method will not return until it is finished.
     *
     * @return true if the call succeeds
     */
    boolean abort();

    /**
     * @return true if the device is running an DynamicAnroid image
     */
    boolean isInUse();

    /**
     * @return true if the device has an DynamicAndroid image installed
     */
    boolean isInstalled();

    /**
     * Remove DynamicAndroid installation if present
     *
     * @return true if the call succeeds
     */
    boolean remove();

    /**
     * Enable DynamicAndroid when it's not enabled, otherwise, disable it.
     *
     * @return true if the call succeeds
     */
    boolean toggle();

    /**
     * Write a chunk of the DynamicAndroid system image
     *
     * @return true if the call succeeds
     */
    boolean write(in byte[] buf);

    /**
     * Finish write and make device to boot into the it after reboot.
     *
     * @return true if the call succeeds
     */
    boolean commit();
}
