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
package com.android.internal.widget.remotecompose.core.operations;

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteComposeOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Describe a content description for the document
 */
public class RootContentDescription implements RemoteComposeOperation {
    int mContentDescription;

    public static final Companion COMPANION = new Companion();

    /**
     * Encodes a content description for the document
     *
     * @param contentDescription content description for the document
     */
    public RootContentDescription(int contentDescription) {
        this.mContentDescription = contentDescription;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mContentDescription);
    }

    @Override
    public String toString() {
        return "ROOT_CONTENT_DESCRIPTION " + mContentDescription;
    }

    @Override
    public void apply(RemoteContext context) {
        context.setDocumentContentDescription(mContentDescription);
    }

    @Override
    public String deepToString(String indent) {
        return toString();
    }

    public static class Companion implements CompanionOperation {
        private Companion() {}

        @Override
        public String name() {
            return "RootContentDescription";
        }

        @Override
        public int id() {
            return Operations.ROOT_CONTENT_DESCRIPTION;
        }

        public void apply(WireBuffer buffer, int contentDescription) {
            buffer.start(Operations.ROOT_CONTENT_DESCRIPTION);
            buffer.writeInt(contentDescription);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int contentDescription = buffer.readInt();
            RootContentDescription header = new RootContentDescription(contentDescription);
            operations.add(header);
        }
    }
}
