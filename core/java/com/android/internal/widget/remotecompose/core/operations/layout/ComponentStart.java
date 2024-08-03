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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.FLOAT;
import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedCompanionOperation;

import java.util.List;

public class ComponentStart implements ComponentStartOperation {

    public static final ComponentStart.Companion COMPANION = new ComponentStart.Companion();

    int mType = DEFAULT;
    float mX;
    float mY;
    float mWidth;
    float mHeight;
    int mComponentId;

    public int getType() {
        return mType;
    }

    public float getX() {
        return mX;
    }

    public float getY() {
        return mY;
    }

    public float getWidth() {
        return mWidth;
    }

    public float getHeight() {
        return mHeight;
    }

    public int getComponentId() {
        return mComponentId;
    }

    public ComponentStart(int type, int componentId, float width, float height) {
        this.mType = type;
        this.mComponentId = componentId;
        this.mX = 0f;
        this.mY = 0f;
        this.mWidth = width;
        this.mHeight = height;
    }

    @Override
    public void write(WireBuffer buffer) {
        Companion.apply(buffer, mType, mComponentId, mWidth, mHeight);
    }

    @Override
    public String toString() {
        return "COMPONENT_START (type " + mType + " " + Companion.typeDescription(mType)
                + ") - (" + mX + ", " + mY + " - " + mWidth + " x " + mHeight + ")";
    }

    @Override
    public String deepToString(String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void apply(RemoteContext context) {
        // nothing
    }

    public static final int UNKNOWN = -1;
    public static final int DEFAULT = 0;
    public static final int ROOT_LAYOUT = 1;
    public static final int LAYOUT = 2;
    public static final int LAYOUT_CONTENT = 3;
    public static final int SCROLL_CONTENT = 4;
    public static final int BUTTON = 5;
    public static final int CHECKBOX = 6;
    public static final int TEXT = 7;
    public static final int CURVED_TEXT = 8;
    public static final int STATE_HOST = 9;
    public static final int CUSTOM = 10;
    public static final int LOTTIE = 11;
    public static final int IMAGE = 12;
    public static final int STATE_BOX_CONTENT = 13;
    public static final int LAYOUT_BOX = 14;
    public static final int LAYOUT_ROW = 15;
    public static final int LAYOUT_COLUMN = 16;

    public static class Companion implements DocumentedCompanionOperation {


        public static String typeDescription(int type) {
            switch (type) {
                case DEFAULT:
                    return "DEFAULT";
                case ROOT_LAYOUT:
                    return "ROOT_LAYOUT";
                case LAYOUT:
                    return "LAYOUT";
                case LAYOUT_CONTENT:
                    return "CONTENT";
                case SCROLL_CONTENT:
                    return "SCROLL_CONTENT";
                case BUTTON:
                    return "BUTTON";
                case CHECKBOX:
                    return "CHECKBOX";
                case TEXT:
                    return "TEXT";
                case CURVED_TEXT:
                    return "CURVED_TEXT";
                case STATE_HOST:
                    return "STATE_HOST";
                case LOTTIE:
                    return "LOTTIE";
                case CUSTOM:
                    return "CUSTOM";
                case IMAGE:
                    return "IMAGE";
                default:
                    return "UNKNOWN";
            }
        }

        @Override
        public String name() {
            return "ComponentStart";
        }

        @Override
        public int id() {
            return Operations.COMPONENT_START;
        }

        public static void apply(WireBuffer buffer, int type, int componentId,
                                 float width, float height) {
            buffer.start(Operations.COMPONENT_START);
            buffer.writeInt(type);
            buffer.writeInt(componentId);
            buffer.writeFloat(width);
            buffer.writeFloat(height);
        }

        public static int size() {
            return 1 + 4 + 4 + 4;
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int type = buffer.readInt();
            int componentId = buffer.readInt();
            float width = buffer.readFloat();
            float height = buffer.readFloat();
            operations.add(new ComponentStart(type, componentId, width, height));
        }

        @Override
        public void documentation(DocumentationBuilder doc) {
            doc.operation("Layout Operations", id(), name())
                    .description("Basic component encapsulating draw commands."
                           + "This is not resizable.")
                    .field(INT, "TYPE", "Type of components")
                    .field(INT, "COMPONENT_ID", "unique id for this component")
                    .field(FLOAT, "WIDTH", "width of the component")
                    .field(FLOAT, "HEIGHT", "height of the component");
        }
    }
}
