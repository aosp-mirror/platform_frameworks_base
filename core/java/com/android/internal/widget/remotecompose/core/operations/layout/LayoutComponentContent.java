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
package com.android.internal.widget.remotecompose.core.operations.layout;

import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/**
 * Represents the content of a LayoutComponent (i.e. the children components)
 */
public class LayoutComponentContent extends Component implements ComponentStartOperation {

    public LayoutComponentContent(int componentId, float x, float y,
                                  float width, float height, Component parent, int animationId) {
        super(parent, componentId, animationId, x, y, width, height);
    }

    public static String name() {
        return "LayoutContent";
    }

    public static int id() {
        return Operations.LAYOUT_CONTENT;
    }

    @Override protected String getSerializedName() {
        return "CONTENT";
    }

    public static void apply(WireBuffer buffer, int componentId) {
        buffer.start(Operations.LAYOUT_CONTENT);
        buffer.writeInt(componentId);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int componentId = buffer.readInt();
        operations.add(new LayoutComponentContent(
                componentId, 0, 0, 0, 0, null, -1));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Layout Operations", id(), name())
                .field(INT, "COMPONENT_ID", "unique id for this component")
                .description("Container for components. BoxLayout, RowLayout and ColumnLayout "
                        + "expects a LayoutComponentContent as a child, encapsulating the "
                        + "components that needs to be laid out.");
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mComponentId);
    }
}
