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
package com.android.internal.widget.remotecompose.core.operations;

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.SHORT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.types.LongConstant;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/** Operation to perform time related calculation */
public class TimeAttribute extends PaintOperation {
    private static final int OP_CODE = Operations.ATTRIBUTE_TIME;
    private static final String CLASS_NAME = "TimeAttribute";
    private final int[] mArgs;
    public int mId;
    public int mTimeId;
    public short mType;

    /** (value - currentTimeMillis) * 1E-3 */
    public static final short TIME_FROM_NOW_SEC = 0;

    /** (value - currentTimeMillis) * 1E-3 / 60 */
    public static final short TIME_FROM_NOW_MIN = 1;

    /** (value - currentTimeMillis) * 1E-3 / 3600 */
    public static final short TIME_FROM_NOW_HR = 2;

    /** (value - arg[0]) * 1E-3 */
    public static final short TIME_FROM_ARG_SEC = 3;

    /** (value - arg[0]) * 1E-3 / 60 */
    public static final short TIME_FROM_ARG_MIN = 4;

    /** (value - arg[0]) * 1E-3 / 3600 */
    public static final short TIME_FROM_ARG_HR = 5;

    /** second-of-minute */
    public static final short TIME_IN_SEC = 6;

    /** minute-of-hour */
    public static final short TIME_IN_MIN = 7;

    /** hour-of-day */
    public static final short TIME_IN_HR = 8;

    /** day-of-month */
    public static final short TIME_DAY_OF_MONTH = 9;

    /** month-of-year from 0 to 11 */
    public static final short TIME_MONTH_VALUE = 10;

    /** day-of-week from 0 to 6 */
    public static final short TIME_DAY_OF_WEEK = 11;

    /** the year */
    public static final short TIME_YEAR = 12;

    /**
     * creates a new operation
     *
     * @param id to write value to
     * @param longId of long to calculate on
     * @param type the type of calculation
     * @param args the optional args
     */
    public TimeAttribute(int id, int longId, short type, int[] args) {
        this.mId = id;
        this.mTimeId = longId;
        this.mType = type;
        this.mArgs = args;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mTimeId, mType);
    }

    @Override
    public @NonNull String toString() {
        if (mArgs == null) {
            return CLASS_NAME + "[" + mId + "] = " + mTimeId + " " + mType;
        } else {
            return CLASS_NAME
                    + "["
                    + mId
                    + "] = "
                    + mTimeId
                    + " "
                    + mType
                    + " "
                    + Arrays.toString(mArgs);
        }
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    public static @NonNull String name() {
        return CLASS_NAME;
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer write command to this buffer
     * @param id the id
     * @param textId the id
     * @param type the value of the float
     */
    public static void apply(@NonNull WireBuffer buffer, int id, int textId, short type) {
        apply(buffer, id, textId, type, null);
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer write command to this buffer
     * @param id the id
     * @param textId the id
     * @param type the value of the float
     * @param args the optional args
     */
    public static void apply(
            @NonNull WireBuffer buffer, int id, int textId, short type, int[] args) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(textId);
        buffer.writeShort(type);
        if (args == null) {
            buffer.writeShort(0);
        } else {
            buffer.writeShort(args.length);
            for (int arg : args) {
                buffer.writeInt(arg);
            }
        }
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();
        int textId = buffer.readInt();
        short type = (short) buffer.readShort();
        short len = (short) buffer.readShort();
        int[] args = null;
        if (len != 0) {
            args = new int[len];
            for (int i = 0; i < len; i++) {
                args[i] = buffer.readInt();
            }
        }
        operations.add(new TimeAttribute(id, textId, type, args));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Time Operations", OP_CODE, CLASS_NAME)
                .description("Calculate Information about time")
                .field(INT, "id", "id to output")
                .field(INT, "longId", "id of time to calculate on")
                .field(SHORT, "type", "the type of calculation")
                .field(SHORT, "argsLength", "The number of additional args")
                .field(INT, "args", "argsLength", "The number of additional args");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @NonNull float[] mBounds = new float[4];

    @Override
    public void paint(@NonNull PaintContext context) {
        int val = mType & 255;
        int flags = mType >> 8;
        RemoteContext ctx = context.getContext();
        LongConstant longConstant = (LongConstant) ctx.getObject(mTimeId);
        long value = longConstant.getValue();
        long delta = 0;
        LocalDateTime time = null;

        switch (val) {
            case TIME_FROM_NOW_SEC:
            case TIME_FROM_NOW_MIN:
            case TIME_FROM_NOW_HR:
                delta = (value - System.currentTimeMillis());
                break;
            case TIME_FROM_ARG_SEC:
            case TIME_FROM_ARG_MIN:
            case TIME_FROM_ARG_HR:
                LongConstant lc2 = (LongConstant) ctx.getObject(mArgs[0]);
                delta = (value - lc2.getValue());
                break;
            case TIME_IN_SEC:
            case TIME_IN_MIN:
            case TIME_IN_HR:
            case TIME_DAY_OF_MONTH:
            case TIME_MONTH_VALUE:
            case TIME_DAY_OF_WEEK:
            case TIME_YEAR:
                time =
                        (LocalDateTime)
                                Instant.ofEpochMilli(value)
                                        .atZone(ZoneOffset.systemDefault())
                                        .toLocalDateTime();

                break;
        }
        switch (val) {
            case TIME_FROM_NOW_SEC:
            case TIME_FROM_ARG_SEC:
                ctx.loadFloat(mId, (delta) * 1E-3f);
                break;
            case TIME_FROM_ARG_MIN:
            case TIME_FROM_NOW_MIN:
                ctx.loadFloat(mId, (float) (delta * 1E-3 / 60));
                break;
            case TIME_FROM_ARG_HR:
            case TIME_FROM_NOW_HR:
                ctx.loadFloat(mId, (float) (delta * 1E-3 / 3600));
                break;
            case TIME_IN_SEC:
                ctx.loadFloat(mId, time.getSecond());
                break;
            case TIME_IN_MIN:
                ctx.loadFloat(mId, time.getDayOfMonth());
                break;
            case TIME_IN_HR:
                ctx.loadFloat(mId, time.getHour());
                break;
            case TIME_DAY_OF_MONTH:
                ctx.loadFloat(mId, time.getDayOfMonth());
                break;
            case TIME_MONTH_VALUE:
                ctx.loadFloat(mId, time.getMonthValue() - 1);
                break;
            case TIME_DAY_OF_WEEK:
                ctx.loadFloat(mId, time.getDayOfWeek().ordinal());
                break;
            case TIME_YEAR:
                ctx.loadFloat(mId, time.getYear());
                break;
        }
    }
}
