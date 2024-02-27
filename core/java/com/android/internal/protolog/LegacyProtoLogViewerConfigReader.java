/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.protolog;

import com.android.internal.protolog.common.ILogger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Handles loading and parsing of ProtoLog viewer configuration.
 */
public class LegacyProtoLogViewerConfigReader {

    private static final String TAG = "ProtoLogViewerConfigReader";
    private Map<Long, String> mLogMessageMap = null;

    /** Returns message format string for its hash or null if unavailable. */
    public synchronized String getViewerString(long messageHash) {
        if (mLogMessageMap != null) {
            return mLogMessageMap.get(messageHash);
        } else {
            return null;
        }
    }

    /**
     * Reads the specified viewer configuration file. Does nothing if the config is already loaded.
     */
    public synchronized void loadViewerConfig(ILogger logger, String viewerConfigFilename) {
        try {
            loadViewerConfig(new GZIPInputStream(new FileInputStream(viewerConfigFilename)));
            logger.log("Loaded " + mLogMessageMap.size()
                    + " log definitions from " + viewerConfigFilename);
        } catch (FileNotFoundException e) {
            logger.log("Unable to load log definitions: File "
                    + viewerConfigFilename + " not found." + e);
        } catch (IOException e) {
            logger.log("Unable to load log definitions: IOException while reading "
                    + viewerConfigFilename + ". " + e);
        } catch (JSONException e) {
            logger.log("Unable to load log definitions: JSON parsing exception while reading "
                    + viewerConfigFilename + ". " + e);
        }
    }

    /**
     * Reads the specified viewer configuration input stream.
     * Does nothing if the config is already loaded.
     */
    public synchronized void loadViewerConfig(InputStream viewerConfigInputStream)
            throws IOException, JSONException {
        if (mLogMessageMap != null) {
            return;
        }
        InputStreamReader config = new InputStreamReader(viewerConfigInputStream);
        BufferedReader reader = new BufferedReader(config);
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        reader.close();
        JSONObject json = new JSONObject(builder.toString());
        JSONObject messages = json.getJSONObject("messages");

        mLogMessageMap = new TreeMap<>();
        Iterator it = messages.keys();
        while (it.hasNext()) {
            String key = (String) it.next();
            try {
                long hash = Long.parseLong(key);
                JSONObject val = messages.getJSONObject(key);
                String msg = val.getString("message");
                mLogMessageMap.put(hash, msg);
            } catch (NumberFormatException expected) {
                // Not a messageHash - skip it
            }
        }
    }

    /**
     * Returns the number of loaded log definitions kept in memory.
     */
    public synchronized int knownViewerStringsNumber() {
        if (mLogMessageMap != null) {
            return mLogMessageMap.size();
        }
        return 0;
    }

}
