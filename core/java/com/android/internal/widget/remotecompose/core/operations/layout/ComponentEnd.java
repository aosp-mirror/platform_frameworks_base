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

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedCompanionOperation;

import java.util.List;

public class ComponentEnd implements Operation {

    public static final ComponentEnd.Companion COMPANION = new ComponentEnd.Companion();

    @Override
    public void write(WireBuffer buffer) {
        Companion.apply(buffer);
    }

    @Override
    public String toString() {
        return "COMPONENT_END";
    }

    @Override
    public void apply(RemoteContext context) {
        // nothing
    }

    @Override
    public String deepToString(String indent) {
        return (indent != null ? indent : "") + toString();
    }

    public static class Companion implements DocumentedCompanionOperation {
        @Override
        public String name() {
            return "ComponentEnd";
        }

        @Override
        public int id() {
            return Operations.COMPONENT_END;
        }

        public static void apply(WireBuffer buffer) {
            buffer.start(Operations.COMPONENT_END);
        }

        public static int size() {
            return 1 + 4 + 4 + 4;
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            operations.add(new ComponentEnd());
        }

        @Override
        public void documentation(DocumentationBuilder doc) {
            doc.operation("Layout Operations", id(), name())
                    .description("End tag for components / layouts. This operation marks the end"
                            + "of a component");
        }
    }
}
