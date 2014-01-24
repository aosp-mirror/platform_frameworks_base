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

/**
 * One line from the loaded-classes file.
 */
class Record {

    /**
     * The delimiter character we use, {@code :}, conflicts with some other
     * names. In that case, manually replace the delimiter with something else.
     */
    private static final String[] REPLACE_CLASSES = {
            "com.google.android.apps.maps:FriendService",
            "com.google.android.apps.maps\\u003AFriendService",
            "com.google.android.apps.maps:driveabout",
            "com.google.android.apps.maps\\u003Adriveabout",
            "com.google.android.apps.maps:GoogleLocationService",
            "com.google.android.apps.maps\\u003AGoogleLocationService",
            "com.google.android.apps.maps:LocationFriendService",
            "com.google.android.apps.maps\\u003ALocationFriendService",
            "com.google.android.apps.maps:MapsBackgroundService",
            "com.google.android.apps.maps\\u003AMapsBackgroundService",
            "com.google.android.apps.maps:NetworkLocationService",
            "com.google.android.apps.maps\\u003ANetworkLocationService",
            "com.android.chrome:sandboxed_process",
            "com.android.chrome\\u003Asandboxed_process",
            "com.android.fakeoemfeatures:background",
            "com.android.fakeoemfeatures\\u003Abackground",
            "com.android.fakeoemfeatures:core",
            "com.android.fakeoemfeatures\\u003Acore",
            "com.android.launcher:wallpaper_chooser",
            "com.android.launcher\\u003Awallpaper_chooser",
            "com.android.nfc:handover",
            "com.android.nfc\\u003Ahandover",
            "com.google.android.music:main",
            "com.google.android.music\\u003Amain",
            "com.google.android.music:ui",
            "com.google.android.music\\u003Aui",
            "com.google.android.setupwarlock:broker",
            "com.google.android.setupwarlock\\u003Abroker",
            "mobi.mgeek.TunnyBrowser:DolphinNotification",
            "mobi.mgeek.TunnyBrowser\\u003ADolphinNotification",
            "com.qo.android.sp.oem:Quickword",
            "com.qo.android.sp.oem\\u003AQuickword",
            "android:ui",
            "android\\u003Aui",
            "system:ui",
            "system\\u003Aui",
    };

    enum Type {
        /** Start of initialization. */
        START_LOAD,

        /** End of initialization. */
        END_LOAD,

        /** Start of initialization. */
        START_INIT,

        /** End of initialization. */
        END_INIT
    }

    /** Parent process ID. */
    final int ppid;

    /** Process ID. */
    final int pid;

    /** Thread ID. */
    final int tid;

    /** Process name. */
    final String processName;

    /** Class loader pointer. */
    final int classLoader;

    /** Type of record. */
    final Type type;

    /** Name of loaded class. */
    final String className;

    /** Record time (ns). */
    final long time;

    /** Source file line# */
    int sourceLineNumber;

    /**
     * Parses a line from the loaded-classes file.
     */
    Record(String line, int lineNum) {
        char typeChar = line.charAt(0);
        switch (typeChar) {
            case '>': type = Type.START_LOAD; break;
            case '<': type = Type.END_LOAD; break;
            case '+': type = Type.START_INIT; break;
            case '-': type = Type.END_INIT; break;
            default: throw new AssertionError("Bad line: " + line);
        }

        sourceLineNumber = lineNum;

        for (int i = 0; i < REPLACE_CLASSES.length; i+= 2) {
            line = line.replace(REPLACE_CLASSES[i], REPLACE_CLASSES[i+1]);
        }

        line = line.substring(1);
        String[] parts = line.split(":");

        ppid = Integer.parseInt(parts[0]);
        pid = Integer.parseInt(parts[1]);
        tid = Integer.parseInt(parts[2]);

        processName = decode(parts[3]).intern();

        classLoader = Integer.parseInt(parts[4]);
        className = vmTypeToLanguage(decode(parts[5])).intern();

        time = Long.parseLong(parts[6]);
    }

    /**
     * Decode any escaping that may have been written to the log line.
     *
     * Supports unicode-style escaping:  \\uXXXX = character in hex
     *
     * @param rawField the field as it was written into the log
     * @result the same field with any escaped characters replaced
     */
    String decode(String rawField) {
        String result = rawField;
        int offset = result.indexOf("\\u");
        while (offset >= 0) {
            String before = result.substring(0, offset);
            String escaped = result.substring(offset+2, offset+6);
            String after = result.substring(offset+6);

            result = String.format("%s%c%s", before, Integer.parseInt(escaped, 16), after);

            // find another but don't recurse
            offset = result.indexOf("\\u", offset + 1);
        }
        return result;
    }

    /**
     * Converts a VM-style name to a language-style name.
     */
    String vmTypeToLanguage(String typeName) {
        // if the typename is (null), just return it as-is.  This is probably in dexopt and
        // will be discarded anyway.  NOTE: This corresponds to the case in dalvik/vm/oo/Class.c
        // where dvmLinkClass() returns false and we clean up and exit.
        if ("(null)".equals(typeName)) {
            return typeName;
        }

        if (!typeName.startsWith("L") || !typeName.endsWith(";") ) {
            throw new AssertionError("Bad name: " + typeName + " in line " + sourceLineNumber);
        }

        typeName = typeName.substring(1, typeName.length() - 1);
        return typeName.replace("/", ".");
    }
}
