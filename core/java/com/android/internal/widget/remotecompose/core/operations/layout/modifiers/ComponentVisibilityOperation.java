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
package com.android.internal.widget.remotecompose.core.operations.layout.modifiers;

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.DecoratorComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.LayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/** Allows setting visibility on a component */
public class ComponentVisibilityOperation
        implements ModifierOperation, VariableSupport, DecoratorComponent {
    private static final int OP_CODE = Operations.MODIFIER_VISIBILITY;

    int mVisibilityId;
    @NonNull Component.Visibility mVisibility = Component.Visibility.VISIBLE;
    private LayoutComponent mParent;

    public ComponentVisibilityOperation(int id) {
        mVisibilityId = id;
    }

    @NonNull
    @Override
    public String toString() {
        return "ComponentVisibilityOperation(" + mVisibilityId + ")";
    }

    @NonNull
    public String serializedName() {
        return "COMPONENT_VISIBILITY";
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(indent, serializedName() + " = " + mVisibilityId);
    }

    @Override
    public void apply(RemoteContext context) {}

    @NonNull
    @Override
    public String deepToString(@Nullable String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void write(WireBuffer buffer) {}

    public static void apply(@NonNull WireBuffer buffer, int valueId) {
        buffer.start(OP_CODE);
        buffer.writeInt(valueId);
    }

    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int valueId = buffer.readInt();
        operations.add(new ComponentVisibilityOperation(valueId));
    }

    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Layout Operations", OP_CODE, "ComponentVisibility")
                .description(
                        "This operation allows setting a component"
                                + "visibility from a provided value")
                .field(INT, "VALUE_ID", "Value ID representing the visibility");
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        context.listensTo(mVisibilityId, this);
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        int visibility = context.getInteger(mVisibilityId);
        if (visibility == Component.Visibility.VISIBLE.ordinal()) {
            mVisibility = Component.Visibility.VISIBLE;
        } else if (visibility == Component.Visibility.GONE.ordinal()) {
            mVisibility = Component.Visibility.GONE;
        } else if (visibility == Component.Visibility.INVISIBLE.ordinal()) {
            mVisibility = Component.Visibility.INVISIBLE;
        } else {
            mVisibility = Component.Visibility.GONE;
        }
        if (mParent != null) {
            mParent.setVisibility(mVisibility);
        }
    }

    public void setParent(LayoutComponent parent) {
        mParent = parent;
    }

    @Override
    public void layout(RemoteContext context, float width, float height) {}
}
