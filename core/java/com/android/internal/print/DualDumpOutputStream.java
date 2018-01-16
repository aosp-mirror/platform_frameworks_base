/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.print;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;

/**
 * Dump either to a proto or a print writer using the same interface.
 *
 * <p>This mirrors the interface of {@link ProtoOutputStream}.
 */
public class DualDumpOutputStream {
    // When writing to a proto, the proto
    private final @Nullable ProtoOutputStream mProtoStream;

    // When printing in clear text, the writer
    private final @Nullable IndentingPrintWriter mIpw;
    // Temporary storage of data when printing to mIpw
    private final LinkedList<DumpObject> mDumpObjects = new LinkedList<>();

    private static abstract class DumpAble {
        final String name;

        private DumpAble(String name) {
            this.name = name;
        }

        abstract void print(IndentingPrintWriter ipw, boolean printName);
    }

    private static class DumpObject extends DumpAble {
        private final LinkedHashMap<String, ArrayList<DumpAble>> mSubObjects = new LinkedHashMap<>();

        private DumpObject(String name) {
            super(name);
        }

        @Override
        void print(IndentingPrintWriter ipw, boolean printName) {
            if (printName) {
                ipw.println(name + "={");
            } else {
                ipw.println("{");
            }
            ipw.increaseIndent();

            for (ArrayList<DumpAble> subObject: mSubObjects.values()) {
                int numDumpables = subObject.size();

                if (numDumpables == 1) {
                    subObject.get(0).print(ipw, true);
                } else {
                    ipw.println(subObject.get(0).name + "=[");
                    ipw.increaseIndent();

                    for (int i = 0; i < numDumpables; i++) {
                        subObject.get(i).print(ipw, false);
                    }

                    ipw.decreaseIndent();
                    ipw.println("]");
                }
            }

            ipw.decreaseIndent();
            ipw.println("}");
        }

        /**
         * Add new field / subobject to this object.
         *
         * <p>If a name is added twice, they will be printed as a array
         *
         * @param fieldName name of the field added
         * @param d The dumpable to add
         */
        public void add(String fieldName, DumpAble d) {
            ArrayList<DumpAble> l = mSubObjects.get(fieldName);

            if (l == null) {
                l = new ArrayList<>(1);
                mSubObjects.put(fieldName, l);
            }

            l.add(d);
        }
    }

    private static class DumpField extends DumpAble {
        private final String mValue;

        private DumpField(String name, String value) {
            super(name);
            this.mValue = value;
        }

        @Override
        void print(IndentingPrintWriter ipw, boolean printName) {
            if (printName) {
                ipw.println(name + "=" + mValue);
            } else {
                ipw.println(mValue);
            }
        }
    }


    /**
     * Create a new DualDumpOutputStream. Only one output should be set.
     *
     * @param proto If dumping to proto the {@link ProtoOutputStream}
     * @param ipw If dumping to a print writer, the {@link IndentingPrintWriter}
     */
    public DualDumpOutputStream(@Nullable ProtoOutputStream proto,
            @Nullable IndentingPrintWriter ipw) {
        Preconditions.checkArgument((proto == null) != (ipw == null));

        mProtoStream = proto;
        mIpw = ipw;

        if (!isProto()) {
            // Add root object
            mDumpObjects.add(new DumpObject(null));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, double val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, boolean val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, int val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, float val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, byte[] val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, Arrays.toString(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, long val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public void write(@NonNull String fieldName, long fieldId, @Nullable String val) {
        if (mProtoStream != null) {
            mProtoStream.write(fieldId, val);
        } else {
            mDumpObjects.getLast().add(fieldName, new DumpField(fieldName, String.valueOf(val)));
        }
    }

    public long start(@NonNull String fieldName, long fieldId) {
        if (mProtoStream != null) {
            return mProtoStream.start(fieldId);
        } else {
            DumpObject d = new DumpObject(fieldName);
            mDumpObjects.getLast().add(fieldName, d);
            mDumpObjects.addLast(d);
            return 0;
        }
    }

    public void end(long token) {
        if (mProtoStream != null) {
            mProtoStream.end(token);
        } else {
            mDumpObjects.removeLast();
        }
    }

    public void flush() {
        if (mProtoStream != null) {
            mProtoStream.flush();
        } else {
            if (mDumpObjects.size() == 1) {
                mDumpObjects.getFirst().print(mIpw, false);

                // Reset root object
                mDumpObjects.clear();
                mDumpObjects.add(new DumpObject(null));
            }

            mIpw.flush();
        }
    }

    /**
     * Add a dump from a different service into this dump.
     *
     * <p>Only for clear text dump. For proto dump use {@link #write(String, long, byte[])}.
     *
     * @param fieldName The name of the field
     * @param nestedState The state of the dump
     */
    public void writeNested(@NonNull String fieldName, byte[] nestedState) {
        Preconditions.checkNotNull(mIpw);

        mDumpObjects.getLast().add(fieldName,
                new DumpField(fieldName, (new String(nestedState, StandardCharsets.UTF_8)).trim()));
    }

    /**
     * @return {@code true} iff we are dumping to a proto
     */
    public boolean isProto() {
        return mProtoStream != null;
    }
}
