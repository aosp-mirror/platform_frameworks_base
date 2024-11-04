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

import java.util.List;

public class ClickModifierEnd implements Operation {

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer);
    }

    @Override
    public String toString() {
        return "CLICK_END";
    }

    @Override
    public void apply(RemoteContext context) {
        // nothing
    }

    @Override
    public String deepToString(String indent) {
        return (indent != null ? indent : "") + toString();
    }

    public static String name() {
        return "ClickModifierEnd";
    }

    public static int id() {
        return Operations.MODIFIER_CLICK_END;
    }

    public static void apply(WireBuffer buffer) {
        buffer.start(id());
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        operations.add(new ClickModifierEnd());
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Layout Operations", id(), name())
                .description(
                        "End tag for click modifiers. This operation marks the end"
                                + "of a click modifier");
    }
}
