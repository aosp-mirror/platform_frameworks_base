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
 * Throwable data transfer object. Keep in sync. with the server side version.
 */
public class ThrowableData {

    final String message;
    final String type;
    final StackTraceElementData[] stackTrace;
    final ThrowableData cause;

    public ThrowableData(Throwable throwable) {
        this.type = throwable.getClass().getName();
        String message = throwable.getMessage();
        this.message = message == null ? "" : message;

        StackTraceElement[] elements = throwable.getStackTrace();
        this.stackTrace = new StackTraceElementData[elements.length];
        for (int i = 0; i < elements.length; i++) {
            this.stackTrace[i] = new StackTraceElementData(elements[i]);
        }

        Throwable cause = throwable.getCause();
        this.cause = cause == null ? null : new ThrowableData(cause);
    }

    public ThrowableData(DataInput in) throws IOException {
        int dataVersion = in.readInt();
        if (dataVersion != 0) {
            throw new IOException("Expected 0. Got: " + dataVersion);
        }

        this.message = in.readUTF();
        this.type = in.readUTF();

        int count = in.readInt();
        this.stackTrace = new StackTraceElementData[count];
        for (int i = 0; i < count; i++) {
            this.stackTrace[i] = new StackTraceElementData(in);
        }

        this.cause = in.readBoolean() ? new ThrowableData(in) : null;
    }

    public void write(DataOutput out) throws IOException {
        out.writeInt(0); // version

        out.writeUTF(message);
        out.writeUTF(type);

        out.writeInt(stackTrace.length);
        for (StackTraceElementData elementData : stackTrace) {
            elementData.write(out);
        }

        out.writeBoolean(cause != null);
        if (cause != null) {
            cause.write(out);
        }
    }

    public String getMessage() {
        return message;
    }

    public String getType() {
        return type;
    }

    public StackTraceElementData[] getStackTrace() {
        return stackTrace;
    }

    public ThrowableData getCause() {
        return cause;
    }


    public String toString() {
        return toString(null);
    }

    public String toString(String prefix) {
        StringBuilder builder = new StringBuilder();
        append(prefix, builder, this);
        return builder.toString();
    }

    private static void append(String prefix, StringBuilder builder,
            ThrowableData throwableData) {
        if (prefix != null) builder.append(prefix);
        builder.append(throwableData.getType())
                .append(": ")
                .append(throwableData.getMessage())
                .append('\n');
        for (StackTraceElementData element : throwableData.getStackTrace()) {
            if (prefix != null ) builder.append(prefix);
            builder.append("  at ")
                    .append(element.getClassName())
                    .append('.')
                    .append(element.getMethodName())
                    .append("(")
                    .append(element.getFileName())
                    .append(':')
                    .append(element.getLineNumber())
                    .append(")\n");

        }

        ThrowableData cause = throwableData.getCause();
        if (cause != null) {
            if (prefix != null ) builder.append(prefix);
            builder.append("Caused by: ");
            append(prefix, builder, cause);
        }
    }
}
