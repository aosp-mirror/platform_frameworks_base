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

/**
 * Retrieves the data during a full restore, decrypting it if necessary.
 *
 * <p>Use {@link FullRestoreDataProcessorFactory} to construct the encrypted or unencrypted
 * processor as appropriate during restore.
 */
public interface FullRestoreDataProcessor {
    /** Return value of {@link #readNextChunk} when there is no more data to download. */
    int END_OF_STREAM = -1;

    /**
     * Reads the next chunk of restore data and writes it to the given buffer.
     *
     * <p>Where necessary, will open the connection to the server and/or decrypt the backup file.
     *
     * <p>The implementation may retry various errors. If the retries fail it will throw the
     * relevant exception.
     *
     * @return the number of bytes read, or {@link #END_OF_STREAM} if there is no more data
     * @throws IOException when downloading from the network or writing to disk
     */
    int readNextChunk(byte[] buffer) throws IOException;

    /**
     * Closes the connection to the server, deletes any temporary files and optionally sends a log
     * with the given finish type.
     *
     * @param finishType one of {@link FullRestoreDownloader.FinishType}
     */
    void finish(FullRestoreDownloader.FinishType finishType);
}
