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

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.SerializableToString;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.MatrixRestore;
import com.android.internal.widget.remotecompose.core.operations.MatrixSave;
import com.android.internal.widget.remotecompose.core.operations.layout.ClickHandler;
import com.android.internal.widget.remotecompose.core.operations.layout.ClickModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.DecoratorComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.TouchHandler;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.ArrayList;

/** Maintain a list of modifiers */
public class ComponentModifiers extends PaintOperation
        implements DecoratorComponent, ClickHandler, TouchHandler, SerializableToString {
    @NonNull ArrayList<ModifierOperation> mList = new ArrayList<>();

    @NonNull
    public ArrayList<ModifierOperation> getList() {
        return mList;
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        super.apply(context);
        for (ModifierOperation op : mList) {
            op.apply(context);
            context.incrementOpCount();
        }
    }

    @NonNull
    @Override
    public String toString() {
        String str = "ComponentModifiers \n";
        for (ModifierOperation modifierOperation : mList) {
            str += "    " + modifierOperation.toString() + "\n";
        }
        return str;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        // nothing
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(indent, "MODIFIERS");
        for (ModifierOperation m : mList) {
            m.serializeToString(indent + 1, serializer);
        }
    }

    /**
     * Add a ModifierOperation
     *
     * @param operation a ModifierOperation
     */
    public void add(@NonNull ModifierOperation operation) {
        mList.add(operation);
    }

    /**
     * Returns the size of the modifier list
     *
     * @return number of modifiers
     */
    public int size() {
        return mList.size();
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        float tx = 0f;
        float ty = 0f;
        for (ModifierOperation op : mList) {
            if (op.isDirty() && op instanceof VariableSupport) {
                ((VariableSupport) op).updateVariables(context.getContext());
                op.markNotDirty();
            }
            if (op instanceof PaddingModifierOperation) {
                PaddingModifierOperation pop = (PaddingModifierOperation) op;
                context.translate(pop.getLeft(), pop.getTop());
                tx += pop.getLeft();
                ty += pop.getTop();
            }
            if (op instanceof MatrixSave || op instanceof MatrixRestore) {
                continue;
            }
            if (op instanceof ClickModifierOperation) {
                context.translate(-tx, -ty);
                ((ClickModifierOperation) op).paint(context);
                context.translate(tx, ty);
            } else if (op instanceof PaintOperation) {
                ((PaintOperation) op).paint(context);
            }
        }
        // Back out the translates created by paddings
        // TODO: we should be able to get rid of this when drawing the content of a component
        context.translate(-tx, -ty);
    }

    @Override
    public void layout(
            @NonNull RemoteContext context, Component component, float width, float height) {
        float w = width;
        float h = height;
        for (ModifierOperation op : mList) {
            if (op instanceof PaddingModifierOperation) {
                PaddingModifierOperation pop = (PaddingModifierOperation) op;
                w -= pop.getLeft() + pop.getRight();
                h -= pop.getTop() + pop.getBottom();
            }
            if (op instanceof ClickModifierOperation) {
                ((DecoratorComponent) op).layout(context, component, width, height);
            } else if (op instanceof DecoratorComponent) {
                ((DecoratorComponent) op).layout(context, component, w, h);
            }
        }
    }

    /**
     * Add the operations to this ComponentModifier
     *
     * @param operations list of ModifierOperation
     */
    public void addAll(@NonNull ArrayList<ModifierOperation> operations) {
        mList.addAll(operations);
    }

    @Override
    public void onClick(
            @NonNull RemoteContext context,
            @NonNull CoreDocument document,
            @NonNull Component component,
            float x,
            float y) {
        for (ModifierOperation op : mList) {
            if (op instanceof ClickHandler) {
                ((ClickHandler) op).onClick(context, document, component, x, y);
            }
        }
    }

    @Override
    public void onTouchDown(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        for (ModifierOperation op : mList) {
            if (op instanceof TouchHandler) {
                ((TouchHandler) op).onTouchDown(context, document, component, x, y);
            }
        }
    }

    @Override
    public void onTouchUp(
            RemoteContext context,
            CoreDocument document,
            Component component,
            float x,
            float y,
            float dx,
            float dy) {
        for (ModifierOperation op : mList) {
            if (op instanceof TouchHandler) {
                ((TouchHandler) op).onTouchUp(context, document, component, x, y, dx, dy);
            }
        }
    }

    @Override
    public void onTouchCancel(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        for (ModifierOperation op : mList) {
            if (op instanceof TouchHandler) {
                ((TouchHandler) op).onTouchCancel(context, document, component, x, y);
            }
        }
    }

    @Override
    public void onTouchDrag(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        for (ModifierOperation op : mList) {
            if (op instanceof TouchHandler) {
                ((TouchHandler) op).onTouchDrag(context, document, component, x, y);
            }
        }
    }

    /**
     * Returns true if we have a horizontal scroll modifier
     *
     * @return true if we have a horizontal scroll modifier, false otherwise
     */
    public boolean hasHorizontalScroll() {
        for (ModifierOperation op : mList) {
            if (op instanceof ScrollModifierOperation) {
                ScrollModifierOperation scrollModifier = (ScrollModifierOperation) op;
                if (scrollModifier.isHorizontalScroll()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if we have a vertical scroll modifier
     *
     * @return true if we have a vertical scroll modifier, false otherwise
     */
    public boolean hasVerticalScroll() {
        for (ModifierOperation op : mList) {
            if (op instanceof ScrollModifierOperation) {
                ScrollModifierOperation scrollModifier = (ScrollModifierOperation) op;
                if (scrollModifier.isVerticalScroll()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Set the horizontal scroll dimension (if we have a scroll modifier)
     *
     * @param hostDimension the host component horizontal dimension
     * @param contentDimension the content horizontal dimension
     */
    public void setHorizontalScrollDimension(float hostDimension, float contentDimension) {
        for (ModifierOperation op : mList) {
            if (op instanceof ScrollModifierOperation) {
                ScrollModifierOperation scrollModifier = (ScrollModifierOperation) op;
                if (scrollModifier.isHorizontalScroll()) {
                    scrollModifier.setHorizontalScrollDimension(hostDimension, contentDimension);
                }
            }
        }
    }

    /**
     * Set the vertical scroll dimension (if we have a scroll modifier)
     *
     * @param hostDimension the host component vertical dimension
     * @param contentDimension the content vertical dimension
     */
    public void setVerticalScrollDimension(float hostDimension, float contentDimension) {
        for (ModifierOperation op : mList) {
            if (op instanceof ScrollModifierOperation) {
                ScrollModifierOperation scrollModifier = (ScrollModifierOperation) op;
                if (scrollModifier.isVerticalScroll()) {
                    scrollModifier.setVerticalScrollDimension(hostDimension, contentDimension);
                }
            }
        }
    }

    /**
     * Returns the horizontal scroll dimension if we have a scroll modifier
     *
     * @return the horizontal scroll dimension, or 0 if no scroll modifier
     */
    public float getHorizontalScrollDimension() {
        for (ModifierOperation op : mList) {
            if (op instanceof ScrollModifierOperation) {
                ScrollModifierOperation scrollModifier = (ScrollModifierOperation) op;
                if (scrollModifier.isHorizontalScroll()) {
                    return scrollModifier.getContentDimension();
                }
            }
        }
        return 0f;
    }

    /**
     * Returns the vertical scroll dimension if we have a scroll modifier
     *
     * @return the vertical scroll dimension, or 0 if no scroll modifier
     */
    public float getVerticalScrollDimension() {
        for (ModifierOperation op : mList) {
            if (op instanceof ScrollModifierOperation) {
                ScrollModifierOperation scrollModifier = (ScrollModifierOperation) op;
                if (scrollModifier.isVerticalScroll()) {
                    return scrollModifier.getContentDimension();
                }
            }
        }
        return 0f;
    }
}
