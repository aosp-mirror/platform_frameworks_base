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
package com.android.internal.widget.remotecompose.core.semantics;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;
import com.android.internal.widget.remotecompose.core.serialize.MapSerializer;
import com.android.internal.widget.remotecompose.core.serialize.SerializeTags;

import java.util.List;

/** Implementation of the most common semantics used in typical Android apps. */
public class CoreSemantics extends Operation implements AccessibilityModifier {
    public int mContentDescriptionId = 0;
    public @Nullable Role mRole = null;
    public int mTextId = 0;
    public int mStateDescriptionId = 0;
    public boolean mEnabled = true;
    public Mode mMode = Mode.SET;
    public boolean mClickable = false;

    @Override
    public int getOpCode() {
        return Operations.ACCESSIBILITY_SEMANTICS;
    }

    @Nullable
    @Override
    public Role getRole() {
        return mRole;
    }

    @Override
    public Mode getMode() {
        return mMode;
    }

    @Override
    public void write(WireBuffer buffer) {
        buffer.writeInt(mContentDescriptionId);
        buffer.writeByte((mRole != null) ? mRole.ordinal() : -1);
        buffer.writeInt(mTextId);
        buffer.writeInt(mStateDescriptionId);
        buffer.writeByte(mMode.ordinal());
        buffer.writeBoolean(mEnabled);
        buffer.writeBoolean(mClickable);
    }

    private void read(WireBuffer buffer) {
        mContentDescriptionId = buffer.readInt();
        mRole = Role.fromInt(buffer.readByte());
        mTextId = buffer.readInt();
        mStateDescriptionId = buffer.readInt();
        mMode = Mode.values()[buffer.readByte()];
        mEnabled = buffer.readBoolean();
        mClickable = buffer.readBoolean();
    }

    @Override
    public void apply(RemoteContext context) {
        // Handled via touch helper
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("SEMANTICS");
        if (mMode != Mode.SET) {
            builder.append(" ");
            builder.append(mMode);
        }
        if (mRole != null) {
            builder.append(" ");
            builder.append(mRole);
        }
        if (mContentDescriptionId > 0) {
            builder.append(" contentDescription=");
            builder.append(mContentDescriptionId);
        }
        if (mTextId > 0) {
            builder.append(" text=");
            builder.append(mTextId);
        }
        if (mStateDescriptionId > 0) {
            builder.append(" stateDescription=");
            builder.append(mStateDescriptionId);
        }
        if (!mEnabled) {
            builder.append(" disabled");
        }
        if (mClickable) {
            builder.append(" clickable");
        }
        return builder.toString();
    }

    @Nullable
    @Override
    public String deepToString(String indent) {
        return indent + this;
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(indent, "SEMANTICS" + " = " + this);
    }

    /**
     * Reads a CoreSemantics object from a WireBuffer and adds it to a list of operations.
     *
     * <p>This method reads the data required to construct a CoreSemantics object from the provided
     * WireBuffer. After reading and constructing the CoreSemantics object, it is added to the
     * provided list of operations.
     *
     * @param buffer The WireBuffer to read data from.
     * @param operations The list of operations to which the read CoreSemantics object will be
     *     added.
     */
    public static void read(WireBuffer buffer, List<Operation> operations) {
        CoreSemantics semantics = new CoreSemantics();

        semantics.read(buffer);

        operations.add(semantics);
    }

    @Override
    public Integer getContentDescriptionId() {
        return mContentDescriptionId != 0 ? mContentDescriptionId : null;
    }

    public @Nullable Integer getStateDescriptionId() {
        return mStateDescriptionId != 0 ? mStateDescriptionId : null;
    }

    public @Nullable Integer getTextId() {
        return mTextId != 0 ? mTextId : null;
    }

    @Override
    public void serialize(MapSerializer serializer) {
        serializer
                .addTags(SerializeTags.MODIFIER, SerializeTags.A11Y)
                .add("type", "CoreSemantics")
                .add("contentDescriptionId", mContentDescriptionId)
                .add("role", mRole)
                .add("textId", mTextId)
                .add("stateDescriptionId", mStateDescriptionId)
                .add("enabled", mEnabled)
                .add("mode", mMode)
                .add("clickable", mClickable);
    }
}
