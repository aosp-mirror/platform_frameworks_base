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

import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.MatrixRestore;
import com.android.internal.widget.remotecompose.core.operations.MatrixSave;
import com.android.internal.widget.remotecompose.core.operations.layout.DecoratorComponent;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.ArrayList;

/**
 * Maintain a list of modifiers
 */
public class ComponentModifiers extends PaintOperation implements DecoratorComponent {
    ArrayList<ModifierOperation> mList = new ArrayList<>();

    public ArrayList<ModifierOperation> getList() {
        return mList;
    }

    @Override
    public void write(WireBuffer buffer) {
        // nothing
    }

    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, "MODIFIERS");
        for (ModifierOperation m : mList) {
            m.serializeToString(indent + 1, serializer);
        }
    }

    public void add(ModifierOperation operation) {
        mList.add(operation);
    }

    public int size() {
        return mList.size();
    }

    @Override
    public void paint(PaintContext context) {
        float tx = 0f;
        float ty = 0f;
        for (ModifierOperation op : mList) {
            if (op instanceof PaddingModifierOperation) {
                PaddingModifierOperation pop = (PaddingModifierOperation) op;
                context.translate(pop.getLeft(), pop.getTop());
                tx += pop.getLeft();
                ty += pop.getTop();
            }
            if (op instanceof MatrixSave || op instanceof MatrixRestore) {
                continue;
            }
            if (op instanceof PaintOperation) {
                ((PaintOperation) op).paint(context);
            }
        }
        // Back out the translates created by paddings
        // TODO: we should be able to get rid of this when drawing the content of a component
        context.translate(-tx, -ty);
    }

    @Override
    public void layout(RemoteContext context, float width, float height) {
        float w = width;
        float h = height;
        for (ModifierOperation op : mList) {
            if (op instanceof PaddingModifierOperation) {
                PaddingModifierOperation pop = (PaddingModifierOperation) op;
                w -= pop.getLeft() + pop.getRight();
                h -= pop.getTop() + pop.getBottom();
            }
            if (op instanceof DecoratorComponent) {
                ((DecoratorComponent) op).layout(context, w, h);
            }
        }
    }

    public void addAll(ArrayList<ModifierOperation> operations) {
        mList.addAll(operations);
    }

    public void onClick(float x, float y) {
        for (ModifierOperation op : mList) {
            if (op instanceof DecoratorComponent) {
                ((DecoratorComponent) op).onClick(x, y);
            }
        }
    }
}
