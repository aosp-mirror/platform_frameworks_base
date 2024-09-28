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
package com.android.internal.widget.remotecompose.core.documentation;

import java.util.ArrayList;

public class Operation {
    public static final int LAYOUT = 0;
    public static final int INT = 0;
    public static final int FLOAT = 1;
    public static final int BOOLEAN = 2;
    public static final int BUFFER = 4;
    public static final int UTF8 = 5;
    public static final int BYTE = 6;
    public static final int VALUE = 7;
    public static final int LONG = 8;

    String mCategory;
    int mId;
    String mName;
    String mDescription;

    boolean mWIP;
    String mTextExamples;

    ArrayList<StringPair> mExamples = new ArrayList<>();
    ArrayList<OperationField> mFields = new ArrayList<>();

    int mExamplesWidth = 100;
    int mExamplesHeight = 100;


    public static String getType(int type) {
        switch (type) {
            case (INT): return "INT";
            case (FLOAT): return "FLOAT";
            case (BOOLEAN): return "BOOLEAN";
            case (BUFFER): return "BUFFER";
            case (UTF8): return "UTF8";
            case (BYTE): return "BYTE";
            case (VALUE): return "VALUE";
            case (LONG): return "LONG";
        }
        return "UNKNOWN";
    }

    public Operation(String category, int id, String name, boolean wip) {
        mCategory = category;
        mId = id;
        mName = name;
        mWIP = wip;
    }

    public Operation(String category, int id, String name) {
        this(category, id, name, false);
    }

    public ArrayList<OperationField> getFields() {
        return mFields;
    }

    public String getCategory() {
        return mCategory;
    }

    public int getId() {
        return mId;
    }

    public String getName() {
        return mName;
    }

    public boolean isWIP() {
        return mWIP;
    }

    public int getSizeFields() {
        int size = 0;
        for (OperationField field : mFields) {
            size += field.getSize();
        }
        return size;
    }

    public String getDescription() {
        return mDescription;
    }

    public String getTextExamples() {
        return mTextExamples;
    }

    public ArrayList<StringPair> getExamples() {
        return mExamples;
    }

    public int getExamplesWidth() {
        return mExamplesWidth;
    }

    public int getExamplesHeight() {
        return mExamplesHeight;
    }

    public Operation field(int type, String name, String description) {
        mFields.add(new OperationField(type, name, description));
        return this;
    }

    public Operation possibleValues(String name, int value) {
        if (!mFields.isEmpty()) {
            mFields.get(mFields.size() - 1).possibleValue(name, "" + value);
        }
        return this;
    }

    public Operation description(String description) {
        mDescription = description;
        return this;
    }

    public Operation examples(String examples) {
        mTextExamples = examples;
        return this;
    }

    public Operation exampleImage(String name, String imagePath) {
        mExamples.add(new StringPair(name, imagePath));
        return this;
    }

    public Operation examplesDimension(int width, int height) {
        mExamplesWidth = width;
        mExamplesHeight = height;
        return this;
    }
}
