/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsed representation of /etc/event-log-tags. */
public class EventLogTags {
    private final static String TAG = "EventLogTags";

    private final static String TAGS_FILE = "/etc/event-log-tags";

    public static class Description {
        public final int mTag;
        public final String mName;
        // TODO: Parse parameter descriptions when anyone has a use for them.

        Description(int tag, String name) {
            mTag = tag;
            mName = name;
        }
    }

    private final static Pattern COMMENT_PATTERN = Pattern.compile(
            "^\\s*(#.*)?$");

    private final static Pattern TAG_PATTERN = Pattern.compile(
            "^\\s*(\\d+)\\s+(\\w+)\\s*(\\(.*\\))?\\s*$");

    private final HashMap<String, Description> mNameMap =
            new HashMap<String, Description>();

    private final HashMap<Integer, Description> mTagMap =
            new HashMap<Integer, Description>();

    public EventLogTags() throws IOException {
        this(new BufferedReader(new FileReader(TAGS_FILE), 256));
    }

    public EventLogTags(BufferedReader input) throws IOException {
        String line;
        while ((line = input.readLine()) != null) {
            Matcher m = COMMENT_PATTERN.matcher(line);
            if (m.matches()) continue;

            m = TAG_PATTERN.matcher(line);
            if (m.matches()) {
                try {
                    int tag = Integer.parseInt(m.group(1));
                    Description d = new Description(tag, m.group(2));
                    mNameMap.put(d.mName, d);
                    mTagMap.put(d.mTag, d);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error in event log tags entry: " + line, e);
                }
            } else {
                Log.e(TAG, "Can't parse event log tags entry: " + line);
            }
        }
    }

    public Description get(String name) {
        return mNameMap.get(name);
    }

    public Description get(int tag) {
        return mTagMap.get(tag);
    }
}
