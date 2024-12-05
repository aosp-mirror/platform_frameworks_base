/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.extension.oad;

/**
 * @hide
 */
interface IOadUpdateInterface {
    // Enable or disable the OAD function.
    void setOadStatus(boolean enable);
    // Get status of OAD function.
    boolean getOadStatus();
    // Start OAD scan of all frequency in the program list.
    void startScan();
    // Stop OAD scan of all frequency in the program list.
    void stopScan();
    // Start OAD detect for the current channel.
    void startDetect();
    // Stop OAD detect for the current channel.
    void stopDetect();
    // Start OAD download after it has been detected or scanned.
    void startDownload();
    // Stop OAD download.
    void stopDownload();
    // Retrieves current OAD software version.
    int getSoftwareVersion();
}
