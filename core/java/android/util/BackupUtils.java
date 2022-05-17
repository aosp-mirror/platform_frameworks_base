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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Utility methods for Backup/Restore
 * @hide
 */
public class BackupUtils {

    public static final int NULL = 0;
    public static final int NOT_NULL = 1;

    /**
     * Thrown when there is a backup version mismatch
     * between the data received and what the system can handle
     */
    public static class BadVersionException extends Exception {
        public BadVersionException(String message) {
            super(message);
        }

        public BadVersionException(String message, Throwable throwable) {
            super(message, throwable);
        }
    }

    public static String readString(DataInputStream in) throws IOException {
        return (in.readByte() == NOT_NULL) ? in.readUTF() : null;
    }

    public static void writeString(DataOutputStream out, String val) throws IOException {
        if (val != null) {
            out.writeByte(NOT_NULL);
            out.writeUTF(val);
        } else {
            out.writeByte(NULL);
        }
    }
}