/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.opengl.GLUtils;

import org.apache.harmony.dalvik.ddmc.Chunk;
import org.apache.harmony.dalvik.ddmc.ChunkHandler;
import org.apache.harmony.dalvik.ddmc.DdmServer;

import java.nio.ByteBuffer;

public class DdmHandleGlTracing extends ChunkHandler {
    /** GL TRace control packets. Packet data controls starting/stopping the trace. */
    public static final int CHUNK_GLTR = type("GLTR");

    private static final DdmHandleGlTracing sInstance = new DdmHandleGlTracing();

    /** singleton, do not instantiate. */
    private DdmHandleGlTracing() {}

    public static void register() {
        DdmServer.registerHandler(CHUNK_GLTR, sInstance);
    }

    @Override
    public void connected() {
    }

    @Override
    public void disconnected() {
    }

    @Override
    public Chunk handleChunk(Chunk request) {
        int type = request.type;

        if (type != CHUNK_GLTR) {
            throw new RuntimeException("Unknown packet " + ChunkHandler.name(type));
        }

        ByteBuffer in = wrapChunk(request);
        GLUtils.setTracingLevel(in.getInt());
        return null;    // empty response
    }
}
