/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.utilities;

/**
 * Utility serializer maintaining an indent buffer
 */
public class StringSerializer {
    StringBuffer mBuffer = new StringBuffer();
    String mIndentBuffer = "                                                                      ";

    /**
     * Append some content to the current buffer
     * @param indent the indentation level to use
     * @param content content to append
     */
    public void append(int indent, String content) {
        String indentation = mIndentBuffer.substring(0, indent);
        mBuffer.append(indentation);
        mBuffer.append(indentation);
        mBuffer.append(content);
        mBuffer.append("\n");
    }

    /**
     * Reset the buffer
     */
    public void reset() {
        mBuffer = new StringBuffer();
    }

    /**
     * Return a string representation of the buffer
     * @return string representation
     */
    public String toString() {
        return mBuffer.toString();
    }
}
