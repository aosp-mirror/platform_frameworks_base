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
public class TextMerge implements Operation {
    public int mTextId;
    public int mSrcId1;
    public int mSrcId2;
    public static final Companion COMPANION = new Companion();
    public static final int MAX_STRING_SIZE = 4000;

    public TextMerge(int textId, int srcId1, int srcId2) {
        this.mTextId = textId;
        this.mSrcId1 = srcId1;
        this.mSrcId2 = srcId2;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mTextId, mSrcId1, mSrcId2);
    }

    @Override
    public String toString() {
        return "TextMerge[" + mTextId + "] = [" + mSrcId1 + " ] + [ " + mSrcId2 + "]";
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
            return Operations.TEXT_MERGE;
        }

        /**
         * Writes out the operation to the buffer
         * @param buffer
         * @param textId
         * @param srcId1
         * @param srcId2
         */
        public void apply(WireBuffer buffer, int textId, int srcId1, int srcId2) {
            buffer.start(Operations.TEXT_MERGE);
            buffer.writeInt(textId);
            buffer.writeInt(srcId1);
            buffer.writeInt(srcId2);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int textId = buffer.readInt();
            int srcId1 = buffer.readInt();
            int srcId2 = buffer.readInt();

            operations.add(new TextMerge(textId, srcId1, srcId2));
        }
    }

    @Override
    public void apply(RemoteContext context) {
        String str1 = context.getText(mSrcId1);
        String str2 = context.getText(mSrcId2);
        context.loadText(mTextId, str1 + str2);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
