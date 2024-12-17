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

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.ArrayList;

public class DocumentedOperation {
    public static final int LAYOUT = 0;
    public static final int INT = 0;
    public static final int FLOAT = 1;
    public static final int BOOLEAN = 2;
    public static final int BUFFER = 4;
    public static final int UTF8 = 5;
    public static final int BYTE = 6;
    public static final int VALUE = 7;
    public static final int LONG = 8;
    public static final int SHORT = 9;

    public static final int FLOAT_ARRAY = 10;
    public static final int INT_ARRAY = 11;

    @NonNull final String mCategory;
    int mId;
    @NonNull final String mName;
    @NonNull String mDescription = "";

    boolean mWIP;
    @Nullable String mTextExamples;

    @NonNull ArrayList<StringPair> mExamples = new ArrayList<>();
    @NonNull ArrayList<OperationField> mFields = new ArrayList<>();
    @NonNull String mVarSize = "";
    int mExamplesWidth = 100;
    int mExamplesHeight = 100;

    /**
     * Returns the string representation of a field type
     *
     * @param type
     * @return
     */
    @NonNull
    public static String getType(int type) {
        switch (type) {
            case INT:
                return "INT";
            case FLOAT:
                return "FLOAT";
            case BOOLEAN:
                return "BOOLEAN";
            case BUFFER:
                return "BUFFER";
            case UTF8:
                return "UTF8";
            case BYTE:
                return "BYTE";
            case VALUE:
                return "VALUE";
            case LONG:
                return "LONG";
            case SHORT:
                return "SHORT";
            case FLOAT_ARRAY:
                return "FLOAT[]";
            case INT_ARRAY:
                return "INT[]";
        }
        return "UNKNOWN";
    }

    public DocumentedOperation(
            @NonNull String category, int id, @NonNull String name, boolean wip) {
        mCategory = category;
        mId = id;
        mName = name;
        mWIP = wip;
    }

    public DocumentedOperation(@NonNull String category, int id, @NonNull String name) {
        this(category, id, name, false);
    }

    @NonNull
    public ArrayList<OperationField> getFields() {
        return mFields;
    }

    public @NonNull String getCategory() {
        return mCategory;
    }

    public int getId() {
        return mId;
    }

    @NonNull
    public String getName() {
        return mName;
    }

    public boolean isWIP() {
        return mWIP;
    }

    @NonNull
    public String getVarSize() {
        return mVarSize;
    }

    /**
     * Returns the size of the operation fields
     *
     * @return size in bytes
     */
    public int getSizeFields() {
        int size = 0;
        mVarSize = "";
        for (OperationField field : mFields) {
            size += Math.max(0, field.getSize());
            if (field.getSize() < 0) {
                mVarSize += " + " + field.getVarSize() + " x 4";
            }
        }
        return size;
    }

    @Nullable
    public String getDescription() {
        return mDescription;
    }

    @Nullable
    public String getTextExamples() {
        return mTextExamples;
    }

    @NonNull
    public ArrayList<StringPair> getExamples() {
        return mExamples;
    }

    public int getExamplesWidth() {
        return mExamplesWidth;
    }

    public int getExamplesHeight() {
        return mExamplesHeight;
    }

    /**
     * Document a field of the operation
     *
     * @param type
     * @param name
     * @param description
     * @return
     */
    @NonNull
    public DocumentedOperation field(int type, @NonNull String name, @NonNull String description) {
        mFields.add(new OperationField(type, name, description));
        return this;
    }

    /**
     * Document a field of the operation
     *
     * @param type
     * @param name
     * @param varSize
     * @param description
     * @return
     */
    @NonNull
    public DocumentedOperation field(
            int type, @NonNull String name, @NonNull String varSize, @NonNull String description) {
        mFields.add(new OperationField(type, name, varSize, description));
        return this;
    }

    /**
     * Add possible values for the operation field
     *
     * @param name
     * @param value
     * @return
     */
    @NonNull
    public DocumentedOperation possibleValues(@NonNull String name, int value) {
        if (!mFields.isEmpty()) {
            mFields.get(mFields.size() - 1).possibleValue(name, "" + value);
        }
        return this;
    }

    /**
     * Add a description
     *
     * @param description
     * @return
     */
    @NonNull
    public DocumentedOperation description(@NonNull String description) {
        mDescription = description;
        return this;
    }

    /**
     * Add arbitrary text as examples
     *
     * @param examples
     * @return
     */
    @NonNull
    public DocumentedOperation examples(@NonNull String examples) {
        mTextExamples = examples;
        return this;
    }

    /**
     * Add an example image
     *
     * @param name the title of the image
     * @param imagePath the path of the image
     * @return
     */
    @NonNull
    public DocumentedOperation exampleImage(@NonNull String name, @NonNull String imagePath) {
        mExamples.add(new StringPair(name, imagePath));
        return this;
    }

    /**
     * Add examples with a given size
     *
     * @param width
     * @param height
     * @return
     */
    @NonNull
    public DocumentedOperation examplesDimension(int width, int height) {
        mExamplesWidth = width;
        mExamplesHeight = height;
        return this;
    }
}
