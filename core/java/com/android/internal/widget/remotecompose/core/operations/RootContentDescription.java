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

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteComposeOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.semantics.AccessibleComponent;

import java.util.List;

/** Describe a content description for the document */
public class RootContentDescription extends Operation
        implements RemoteComposeOperation, AccessibleComponent {
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
    public boolean isInterestingForSemantics() {
        return mContentDescription != 0;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mContentDescription);
    }

    @NonNull
    @Override
    public String toString() {
        return "RootContentDescription " + mContentDescription;
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.setDocumentContentDescription(mContentDescription);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return toString();
    }

    @Override
    public Integer getContentDescriptionId() {
        return mContentDescription;
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return CLASS_NAME;
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    public static void apply(@NonNull WireBuffer buffer, int contentDescription) {
        buffer.start(Operations.ROOT_CONTENT_DESCRIPTION);
        buffer.writeInt(contentDescription);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int contentDescription = buffer.readInt();
        RootContentDescription header = new RootContentDescription(contentDescription);
        operations.add(header);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Protocol Operations", OP_CODE, CLASS_NAME)
                .description("Content description of root")
                .field(DocumentedOperation.INT, "id", "id of Int");
    }
}
