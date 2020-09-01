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

package com.android.server.backup.encryption;

import java.io.IOException;

/** Interface for classes which will provide backup data */
public abstract class FullRestoreDownloader {
    /** Enum to provide information on why a download finished */
    public enum FinishType {
        UNKNOWN_FINISH(0),
        // Finish the downloading and successfully write data to Android OS.
        FINISHED(1),
        // Download failed with any kind of exception.
        TRANSFER_FAILURE(2),
        // Download failed due to auth failure on the device.
        AUTH_FAILURE(3),
        // Aborted by Android Framework.
        FRAMEWORK_ABORTED(4);

        private int mValue;

        FinishType(int value) {
            mValue = value;
        }
    }

    /** Get the next data chunk from the backing store */
    public abstract int readNextChunk(byte[] buffer) throws IOException;

    /** Called when we've finished restoring the data */
    public abstract void finish(FinishType finishType);
}
