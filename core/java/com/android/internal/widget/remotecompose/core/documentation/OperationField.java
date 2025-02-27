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

public class OperationField {
    final int mType;
    @NonNull final String mName;
    @NonNull final String mDescription;
    @Nullable String mVarSize = null;

    @NonNull ArrayList<StringPair> mPossibleValues = new ArrayList<>();

    public OperationField(int type, @NonNull String name, @NonNull String description) {
        mType = type;
        mName = name;
        mDescription = description;
    }

    public OperationField(
            int type, @NonNull String name, @Nullable String varSize, @NonNull String description) {
        mType = type;
        mName = name;
        mDescription = description;
        mVarSize = varSize;
    }

    public int getType() {
        return mType;
    }

    @NonNull
    public String getName() {
        return mName;
    }

    @NonNull
    public String getDescription() {
        return mDescription;
    }

    @NonNull
    public ArrayList<StringPair> getPossibleValues() {
        return mPossibleValues;
    }

    public void possibleValue(@NonNull String name, @NonNull String value) {
        mPossibleValues.add(new StringPair(name, value));
    }

    public boolean hasEnumeratedValues() {
        return !mPossibleValues.isEmpty();
    }

    @Nullable
    public String getVarSize() {
        return mVarSize;
    }

    public int getSize() {
        switch (mType) {
            case DocumentedOperation.BYTE:
                return 1;
            case DocumentedOperation.INT:
                return 4;
            case DocumentedOperation.FLOAT:
                return 4;
            case DocumentedOperation.LONG:
                return 8;
            case DocumentedOperation.SHORT:
                return 2;
            case DocumentedOperation.INT_ARRAY:
                return -1;
            case DocumentedOperation.FLOAT_ARRAY:
                return -1;
            default:
                return 0;
        }
    }
}
