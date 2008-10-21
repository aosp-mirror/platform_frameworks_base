/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.server.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Stack trace element data transfer object. Keep in sync. with the server side
 * version.
 */
public class StackTraceElementData {

    final String className;
    final String fileName;
    final String methodName;
    final int lineNumber;

    public StackTraceElementData(StackTraceElement element) {
        this.className = element.getClassName();

        String fileName = element.getFileName();
        this.fileName = fileName == null ? "[unknown source]" : fileName;

        this.methodName = element.getMethodName();
        this.lineNumber = element.getLineNumber();
    }

    public StackTraceElementData(DataInput in) throws IOException {
        int dataVersion = in.readInt();
        if (dataVersion != 0) {
            throw new IOException("Expected 0. Got: " + dataVersion);
        }

        this.className = in.readUTF();
        this.fileName = in.readUTF();
        this.methodName = in.readUTF();
        this.lineNumber = in.readInt();
    }

    void write(DataOutput out) throws IOException {
        out.writeInt(0); // version

        out.writeUTF(className);
        out.writeUTF(fileName);
        out.writeUTF(methodName);
        out.writeInt(lineNumber);
    }

    public String getClassName() {
        return className;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMethodName() {
        return methodName;
    }

    public int getLineNumber() {
        return lineNumber;
    }
}
