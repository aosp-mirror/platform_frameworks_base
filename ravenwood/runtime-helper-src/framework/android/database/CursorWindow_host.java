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
package android.database;

import android.database.sqlite.SQLiteException;
import android.os.Parcel;
import android.util.Base64;

import java.text.DecimalFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class CursorWindow_host {

    private static final HashMap<Long, CursorWindow_host> sInstances = new HashMap<>();
    private static long sNextId = 1;

    private String mName;
    private int mColumnNum;
    private static class Row {
        String[] mFields;
        int[] mTypes;
    }

    private final List<Row> mRows = new ArrayList<>();

    public static long nativeCreate(String name, int cursorWindowSize) {
        CursorWindow_host instance = new CursorWindow_host();
        instance.mName = name;
        long instanceId = sNextId++;
        sInstances.put(instanceId, instance);
        return instanceId;
    }

    public static void nativeDispose(long windowPtr) {
        sInstances.remove(windowPtr);
    }

    public static String nativeGetName(long windowPtr) {
        return sInstances.get(windowPtr).mName;
    }

    public static boolean nativeSetNumColumns(long windowPtr, int columnNum) {
        sInstances.get(windowPtr).mColumnNum = columnNum;
        return true;
    }

    public static int nativeGetNumRows(long windowPtr) {
        return sInstances.get(windowPtr).mRows.size();
    }

    public static boolean nativeAllocRow(long windowPtr) {
        CursorWindow_host instance = sInstances.get(windowPtr);
        Row row = new Row();
        row.mFields = new String[instance.mColumnNum];
        row.mTypes = new int[instance.mColumnNum];
        Arrays.fill(row.mTypes, Cursor.FIELD_TYPE_NULL);
        instance.mRows.add(row);
        return true;
    }

    private static boolean put(long windowPtr, String value, int type, int row, int column) {
        CursorWindow_host instance = sInstances.get(windowPtr);
        if (row >= instance.mRows.size() || column >= instance.mColumnNum) {
            return false;
        }
        Row r = instance.mRows.get(row);
        r.mFields[column] = value;
        r.mTypes[column] = type;
        return true;
    }

    public static int nativeGetType(long windowPtr, int row, int column) {
        CursorWindow_host instance = sInstances.get(windowPtr);
        if (row >= instance.mRows.size() || column >= instance.mColumnNum) {
            return Cursor.FIELD_TYPE_NULL;
        }

        return instance.mRows.get(row).mTypes[column];
    }

    public static boolean nativePutString(long windowPtr, String value,
            int row, int column) {
        return put(windowPtr, value, Cursor.FIELD_TYPE_STRING, row, column);
    }

    public static String nativeGetString(long windowPtr, int row, int column) {
        CursorWindow_host instance = sInstances.get(windowPtr);
        if (row >= instance.mRows.size() || column >= instance.mColumnNum) {
            return null;
        }

        return instance.mRows.get(row).mFields[column];
    }

    public static boolean nativePutLong(long windowPtr, long value, int row, int column) {
        return put(windowPtr, Long.toString(value), Cursor.FIELD_TYPE_INTEGER, row, column);
    }

    public static long nativeGetLong(long windowPtr, int row, int column) {
        String value = nativeGetString(windowPtr, row, column);
        if (value == null) {
            return 0;
        }

        Number number = new DecimalFormat().parse(value, new ParsePosition(0));
        return number == null ? 0 : number.longValue();
    }

    public static boolean nativePutDouble(long windowPtr, double value, int row, int column) {
        return put(windowPtr, Double.toString(value), Cursor.FIELD_TYPE_FLOAT, row, column);
    }

    public static double nativeGetDouble(long windowPtr, int row, int column) {
        String value = nativeGetString(windowPtr, row, column);
        if (value == null) {
            return 0;
        }

        Number number = new DecimalFormat().parse(value, new ParsePosition(0));
        return number == null ? 0 : number.doubleValue();
    }

    public static boolean nativePutBlob(long windowPtr, byte[] value, int row, int column) {
        return put(windowPtr, value == null ? null : Base64.encodeToString(value, 0),
                Cursor.FIELD_TYPE_BLOB, row, column);
    }

    public static byte[] nativeGetBlob(long windowPtr, int row, int column) {
        int type = nativeGetType(windowPtr, row, column);
        switch (type) {
            case Cursor.FIELD_TYPE_BLOB: {
                String value = nativeGetString(windowPtr, row, column);
                return value == null ? null : Base64.decode(value, 0);
            }
            case Cursor.FIELD_TYPE_STRING: {
                String value = nativeGetString(windowPtr, row, column);
                return value == null ? null : value.getBytes();
            }
            case Cursor.FIELD_TYPE_FLOAT:
                throw new SQLiteException();
            case Cursor.FIELD_TYPE_INTEGER:
                throw new SQLiteException();
            case Cursor.FIELD_TYPE_NULL:
            default:
                return null;
        }
    }

    public static void nativeWriteToParcel(long windowPtr, Parcel parcel) {
        CursorWindow_host window = sInstances.get(windowPtr);
        parcel.writeString(window.mName);
        parcel.writeInt(window.mColumnNum);
        parcel.writeInt(window.mRows.size());
        for (int row = 0; row < window.mRows.size(); row++) {
            parcel.writeStringArray(window.mRows.get(row).mFields);
            parcel.writeIntArray(window.mRows.get(row).mTypes);
        }
    }

    public static long nativeCreateFromParcel(Parcel parcel) {
        long windowPtr = nativeCreate(null, 0);
        CursorWindow_host window = sInstances.get(windowPtr);
        window.mName = parcel.readString();
        window.mColumnNum = parcel.readInt();
        int rowCount = parcel.readInt();
        for (int row = 0; row < rowCount; row++) {
            Row r = new Row();
            r.mFields = parcel.createStringArray();
            r.mTypes = parcel.createIntArray();
            window.mRows.add(r);
        }
        return windowPtr;
    }
}
