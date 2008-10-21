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

    /**
     * Parses a line from the loaded-classes file.
     */
    Record(String line) {
        char typeChar = line.charAt(0);
        switch (typeChar) {
            case '>': type = Type.START_LOAD; break;
            case '<': type = Type.END_LOAD; break;
            case '+': type = Type.START_INIT; break;
            case '-': type = Type.END_INIT; break;
            default: throw new AssertionError("Bad line: " + line);
        }

        line = line.substring(1);
        String[] parts = line.split(":");

        ppid = Integer.parseInt(parts[0]);
        pid = Integer.parseInt(parts[1]);
        tid = Integer.parseInt(parts[2]);

        processName = parts[3].intern();

        classLoader = Integer.parseInt(parts[4]);
        className = vmTypeToLanguage(parts[5]).intern();

        time = Long.parseLong(parts[6]);
    }

    /**
     * Converts a VM-style name to a language-style name.
     */
    static String vmTypeToLanguage(String typeName) {
        if (!typeName.startsWith("L") || !typeName.endsWith(";") ) {
            throw new AssertionError("Bad name: " + typeName);
        }

        typeName = typeName.substring(1, typeName.length() - 1);
        return typeName.replace("/", ".");
    }
}
