/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.ddm;

import org.apache.harmony.dalvik.ddmc.Chunk;
import org.apache.harmony.dalvik.ddmc.ChunkHandler;
import org.apache.harmony.dalvik.ddmc.DdmServer;
import android.util.Log;

/**
 * Handle thread-related traffic.
 */
public class DdmHandleNativeHeap extends ChunkHandler {

    public static final int CHUNK_NHGT = type("NHGT");

    private static DdmHandleNativeHeap mInstance = new DdmHandleNativeHeap();


    /* singleton, do not instantiate */
    private DdmHandleNativeHeap() {}

    /**
     * Register for the messages we're interested in.
     */
    public static void register() {
        DdmServer.registerHandler(CHUNK_NHGT, mInstance);
    }

    /**
     * Called when the DDM server connects.  The handler is allowed to
     * send messages to the server.
     */
    public void connected() {}

    /**
     * Called when the DDM server disconnects.  Can be used to disable
     * periodic transmissions or clean up saved state.
     */
    public void disconnected() {}

    /**
     * Handle a chunk of data.
     */
    public Chunk handleChunk(Chunk request) {
        Log.i("ddm-nativeheap", "Handling " + name(request.type) + " chunk");
        int type = request.type;

        if (type == CHUNK_NHGT) {
            return handleNHGT(request);
        } else {
            throw new RuntimeException("Unknown packet "
                + ChunkHandler.name(type));
        }
    }

    /*
     * Handle a "Native Heap GeT" request.
     */
    private Chunk handleNHGT(Chunk request) {
        //ByteBuffer in = wrapChunk(request);

        byte[] data = getLeakInfo();

        if (data != null) {
            // wrap & return
            Log.i("ddm-nativeheap", "Sending " + data.length + " bytes");
            return new Chunk(ChunkHandler.type("NHGT"), data, 0, data.length);
        } else {
            // failed, return a failure error code and message
            return createFailChunk(1, "Something went wrong");
        }
    }
    
    private native byte[] getLeakInfo();
}

