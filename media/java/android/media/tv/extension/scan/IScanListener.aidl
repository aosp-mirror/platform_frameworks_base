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

package android.media.tv.extension.scan;

import android.os.Bundle;

/**
 * @hide
 */
oneway interface IScanListener {
    // notify events during scan.
    void onEvent(in Bundle eventArgs);
    // notify the scan progress.
    void onScanProgress(String scanProgress, in Bundle scanProgressInfo);
    // notify the scan completion.
    void onScanCompleted(int scanResult);
    // notify that the temporaily held channel list is stored.
    void onStoreCompleted(int storeResult);
}
