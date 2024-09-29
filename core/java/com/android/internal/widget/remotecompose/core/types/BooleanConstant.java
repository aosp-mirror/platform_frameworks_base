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
package com.android.internal.widget.remotecompose.core.types;

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Used to represent a boolean
 */
public class BooleanConstant implements Operation {
    boolean mValue = false;
    private int mId;

    public BooleanConstant(int id, boolean value) {
        mId = id;
        mValue = value;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mId, mValue);
    }

    @Override
    public void apply(RemoteContext context) {

    }

    @Override
    public String deepToString(String indent) {
        return toString();
    }

    @Override
    public String toString() {
        return "BooleanConstant[" + mId + "] = " + mValue + "";
    }

    public static final Companion COMPANION = new Companion();

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "OrigamiBoolean";
        }

        @Override
        public int id() {
            return Operations.DATA_BOOLEAN;
        }

        /**
         * Writes out the operation to the buffer
         *
         * @param buffer
         * @param id
         * @param value
         */
        public void apply(WireBuffer buffer, int id, boolean value) {
            buffer.start(Operations.DATA_BOOLEAN);
            buffer.writeInt(id);
            buffer.writeBoolean(value);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int id = buffer.readInt();

            boolean value = buffer.readBoolean();
            operations.add(new BooleanConstant(id, value));
        }
    }

}
