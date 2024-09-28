/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations;

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Operation to deal with Text data
 */
public class TextData implements Operation {
    public int mTextId;
    public String mText;
    public static final Companion COMPANION = new Companion();
    public static final int MAX_STRING_SIZE = 4000;

    public TextData(int textId, String text) {
        this.mTextId = textId;
        this.mText = text;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mTextId, mText);
    }

    @Override
    public String toString() {
        return "TextData[" + mTextId + "] = \""
                + Utils.trimString(mText, 10) + "\"";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "TextData";
        }

        @Override
        public int id() {
            return Operations.DATA_TEXT;
        }

        public void apply(WireBuffer buffer, int textId, String text) {
            buffer.start(Operations.DATA_TEXT);
            buffer.writeInt(textId);
            buffer.writeUTF8(text);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int textId = buffer.readInt();

            String text = buffer.readUTF8(MAX_STRING_SIZE);
            operations.add(new TextData(textId, text));
        }
    }

    @Override
    public void apply(RemoteContext context) {
        context.loadText(mTextId, mText);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
