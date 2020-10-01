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

import android.compat.annotation.UnsupportedAppUsage;
import android.util.Log;

import org.apache.harmony.dalvik.ddmc.Chunk;
import org.apache.harmony.dalvik.ddmc.ChunkHandler;
import org.apache.harmony.dalvik.ddmc.DdmServer;

import java.nio.ByteBuffer;


/**
 * Track our app name.  We don't (currently) handle any inbound packets.
 */
public class DdmHandleAppName extends ChunkHandler {

    public static final int CHUNK_APNM = type("APNM");

    private static volatile Names sNames = new Names("", "");

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
     * Sets all names to the same name.
     */
    @UnsupportedAppUsage
    public static void setAppName(String name, int userId) {
        setAppName(name, name, userId);
    }

    /**
     * Set the application name.  Called when we get named, which may be
     * before or after DDMS connects.  For the latter we need to send up
     * an APNM message.
     */
    @UnsupportedAppUsage
    public static void setAppName(String appName, String pkgName, int userId) {
        if (appName == null || appName.isEmpty() || pkgName == null || pkgName.isEmpty()) return;

        sNames = new Names(appName, pkgName);

        // if DDMS is already connected, send the app name up
        sendAPNM(appName, pkgName, userId);
    }

    @UnsupportedAppUsage
    public static Names getNames() {
        return sNames;
    }

    /**
     * Send an APNM (APplication NaMe) chunk.
     */
    private static void sendAPNM(String appName, String pkgName, int userId) {
        if (false)
            Log.v("ddm", "Sending app name");

        ByteBuffer out = ByteBuffer.allocate(
                            4 /* appName's length */
                            + appName.length() * 2 /* appName */
                            + 4 /* userId */
                            + 4 /* pkgName's length */
                            + pkgName.length() * 2 /* pkgName */);
        out.order(ChunkHandler.CHUNK_ORDER);
        out.putInt(appName.length());
        putString(out, appName);
        out.putInt(userId);
        out.putInt(pkgName.length());
        putString(out, pkgName);

        Chunk chunk = new Chunk(CHUNK_APNM, out);
        DdmServer.sendChunk(chunk);
    }

    /**
     * A class that encapsulates the app and package names into a single
     * instance, effectively synchronizing the two names.
     */
    static final class Names {

        private final String mAppName;

        private final String mPkgName;

        private Names(String appName, String pkgName) {
            mAppName = appName;
            mPkgName = pkgName;
        }

        public String getAppName() {
            return mAppName;
        }

        public String getPkgName() {
            return mPkgName;
        }

    }

}

