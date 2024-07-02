/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations;

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringUtils;

import java.util.List;

/**
 * Operation convert floats to text
 * This command is structured [command][textID][before,after][flags]
 * before and after define number of digits before and after the decimal point
 */
public class TextFromFloat implements Operation, VariableSupport {
    public int mTextId;
    public float mValue;
    public float mOutValue;
    public short mDigitsBefore;
    public short mDigitsAfter;
    public int mFlags;
    public static final Companion COMPANION = new Companion();
    public static final int MAX_STRING_SIZE = 4000;
    char mPre = ' ';
    char mAfter = ' ';
    // Theses flags define what how to/if  fill the space
    public static final int PAD_AFTER_SPACE = 0; // pad past point with space
    public static final int PAD_AFTER_NONE = 1; // do not pad past last digit
    public static final int PAD_AFTER_ZERO = 3; // pad with 0 past last digit
    public static final int PAD_PRE_SPACE = 0;  // pad before number with spaces
    public static final int PAD_PRE_NONE = 4;   // pad before number with 0s
    public static final int PAD_PRE_ZERO = 12;  // do not pad before number

    public TextFromFloat(int textId, float value, short digitsBefore,
                         short digitsAfter, int flags) {
        this.mTextId = textId;
        this.mValue = value;
        this.mDigitsAfter = digitsAfter;
        this.mDigitsBefore = digitsBefore;
        this.mFlags = flags;
        mOutValue = mValue;
        switch (mFlags & 3) {
            case PAD_AFTER_SPACE:
                mAfter = ' ';
                break;
            case PAD_AFTER_NONE:
                mAfter = 0;
                break;
            case PAD_AFTER_ZERO:
                mAfter = '0';
                break;
        }
        switch (mFlags & 12) {
            case PAD_PRE_SPACE:
                mPre = ' ';
                break;
            case PAD_PRE_NONE:
                mPre = 0;
                break;
            case PAD_PRE_ZERO:
                mPre = '0';
                break;
        }
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mTextId, mValue, mDigitsAfter, mDigitsBefore, mFlags);
    }

    @Override
    public String toString() {
        return "TextFromFloat[" + mTextId + "] = "
                + Utils.floatToString(mValue) + " " + mDigitsBefore
                + "." + mDigitsAfter + " " + mFlags;
    }


    @Override
    public void updateVariables(RemoteContext context) {
        if (Float.isNaN(mValue)) {
            mOutValue = context.getFloat(Utils.idFromNan(mValue));
        }

    }


    @Override
    public void registerListening(RemoteContext context) {
        if (Float.isNaN(mValue)) {
            context.listensTo(Utils.idFromNan(mValue), this);
        }
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "TextData";
        }

        @Override
        public int id() {
            return Operations.TEXT_FROM_FLOAT;
        }

        /**
         * Writes out the operation to the buffer
         * @param buffer
         * @param textId
         * @param value
         * @param digitsBefore
         * @param digitsAfter
         * @param flags
         */
        public void apply(WireBuffer buffer, int textId,
                          float value, short digitsBefore,
                          short digitsAfter, int flags) {
            buffer.start(Operations.TEXT_FROM_FLOAT);
            buffer.writeInt(textId);
            buffer.writeFloat(value);
            buffer.writeInt((digitsBefore << 16) | digitsAfter);
            buffer.writeInt(flags);

        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int textId = buffer.readInt();
            float value = buffer.readFloat();
            int tmp = buffer.readInt();
            short post = (short) (tmp & 0xFFFF);
            short pre = (short) ((tmp >> 16) & 0xFFFF);

            int flags = buffer.readInt();
            operations.add(new TextFromFloat(textId, value, pre, post, flags));
        }
    }

    @Override
    public void apply(RemoteContext context) {
        float v = mOutValue;
        String s = StringUtils.floatToString(v, mDigitsBefore,
                mDigitsAfter, mPre, mAfter);
        context.loadText(mTextId, s);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
