/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.os.DdmSyncState.Stage;
import android.util.Slog;

import org.apache.harmony.dalvik.ddmc.Chunk;
import org.apache.harmony.dalvik.ddmc.ChunkHandler;
import org.apache.harmony.dalvik.ddmc.DdmServer;

import java.nio.ByteBuffer;

/**
 * @hide
 */
// Making it public so ActivityThread can access it.
public class DdmSyncStageUpdater {

    private static final String TAG = "DdmSyncStageUpdater";

    private static final int CHUNK_STAGE = ChunkHandler.type("STAG");

    /**
     * @hide
     */
    public DdmSyncStageUpdater() {
    }

    /**
     * @hide
     */
    // Making it public so ActivityThread can access it.
    public synchronized void next(Stage stage) {
        try {
            DdmSyncState.next(stage);

            // Request DDMServer to send a STAG chunk
            ByteBuffer data = ByteBuffer.allocate(Integer.BYTES);
            data.putInt(stage.toInt());
            Chunk stagChunk = new Chunk(CHUNK_STAGE, data);
            DdmServer.sendChunk(stagChunk);
        } catch (Exception e) {
            // Catch everything to make sure we don't impact ActivityThread
            Slog.w(TAG, "Unable to go to next stage" + stage, e);
        }
    }

}
