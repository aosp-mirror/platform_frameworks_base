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

import android.annotation.UnsupportedAppUsage;
import org.apache.harmony.dalvik.ddmc.Chunk;
import org.apache.harmony.dalvik.ddmc.ChunkHandler;
import org.apache.harmony.dalvik.ddmc.DdmServer;
import android.util.Log;
import java.nio.ByteBuffer;


/**
 * Track our app name.  We don't (currently) handle any inbound packets.
 */
public class DdmHandleAppName extends ChunkHandler {

    public static final int CHUNK_APNM = type("APNM");

    private volatile static String mAppName = "";

    private static DdmHandleAppName mInstance = new DdmHandleAppName();


    /* singleton, do not instantiate */
    private DdmHandleAppName() {}

    /**
     * Register for the messages we're interested in.
     */
    public static void register() {}

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
        return null;
    }



    /**
     * Set the application name.  Called when we get named, which may be
     * before or after DDMS connects.  For the latter we need to send up
     * an APNM message.
     */
    @UnsupportedAppUsage
    public static void setAppName(String name, int userId) {
        if (name == null || name.length() == 0)
            return;

        mAppName = name;

        // if DDMS is already connected, send the app name up
        sendAPNM(name, userId);
    }

    @UnsupportedAppUsage
    public static String getAppName() {
        return mAppName;
    }

    /*
     * Send an APNM (APplication NaMe) chunk.
     */
    private static void sendAPNM(String appName, int userId) {
        if (false)
            Log.v("ddm", "Sending app name");

        ByteBuffer out = ByteBuffer.allocate(
                            4 /* appName's length */
                            + appName.length()*2 /* appName */
                            + 4 /* userId */);
        out.order(ChunkHandler.CHUNK_ORDER);
        out.putInt(appName.length());
        putString(out, appName);
        out.putInt(userId);

        Chunk chunk = new Chunk(CHUNK_APNM, out);
        DdmServer.sendChunk(chunk);
    }

}

