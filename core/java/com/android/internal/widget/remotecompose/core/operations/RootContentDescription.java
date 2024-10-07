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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteComposeOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/**
 * Describe a content description for the document
 */
public class RootContentDescription implements RemoteComposeOperation {
    private static final int OP_CODE = Operations.ROOT_CONTENT_DESCRIPTION;
    private static final String CLASS_NAME = "RootContentDescription";
    int mContentDescription;

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
        apply(buffer, mContentDescription);
    }

    @Override
    public String toString() {
        return "RootContentDescription " + mContentDescription;
    }

    @Override
    public void apply(RemoteContext context) {
        context.setDocumentContentDescription(mContentDescription);
    }

    @Override
    public String deepToString(String indent) {
        return toString();
    }

    public static String name() {
        return CLASS_NAME;
    }

    public static int id() {
        return OP_CODE;
    }

    public static void apply(WireBuffer buffer, int contentDescription) {
        buffer.start(Operations.ROOT_CONTENT_DESCRIPTION);
        buffer.writeInt(contentDescription);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int contentDescription = buffer.readInt();
        RootContentDescription header = new RootContentDescription(contentDescription);
        operations.add(header);
    }


    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Protocol Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Content description of root")
                .field(INT, "id", "id of Int");

    }
}
