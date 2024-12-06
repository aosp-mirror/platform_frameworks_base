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

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.operations.TextData;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.ArrayList;

public abstract class ListActionsOperation extends PaintOperation
        implements ModifierOperation, DecoratorComponent {

    String mOperationName;
    protected float mWidth = 0;
    protected float mHeight = 0;

    private final float[] mLocationInWindow = new float[2];

    public ListActionsOperation(String operationName) {
        mOperationName = operationName;
    }

    public ArrayList<Operation> mList = new ArrayList<>();

    public ArrayList<Operation> getList() {
        return mList;
    }

    @Override
    public String toString() {
        return mOperationName;
    }

    @Override
    public void apply(RemoteContext context) {
        for (Operation op : mList) {
            if (op instanceof TextData) {
                op.apply(context);
                context.incrementOpCount();
            }
        }
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void paint(PaintContext context) {}

    @Override
    public void layout(RemoteContext context, Component component, float width, float height) {
        mWidth = width;
        mHeight = height;
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, mOperationName);
        for (Operation o : mList) {
            if (o instanceof ActionOperation) {
                ((ActionOperation) o).serializeToString(indent + 1, serializer);
            }
        }
    }

    public boolean applyActions(
            RemoteContext context,
            CoreDocument document,
            Component component,
            float x,
            float y,
            boolean force) {
        if (!force && !component.isVisible()) {
            return false;
        }
        if (!force && !component.contains(x, y)) {
            return false;
        }
        mLocationInWindow[0] = 0f;
        mLocationInWindow[1] = 0f;
        component.getLocationInWindow(mLocationInWindow);
        for (Operation o : mList) {
            if (o instanceof ActionOperation) {
                ((ActionOperation) o).runAction(context, document, component, x, y);
            }
        }
        return true;
    }
}
