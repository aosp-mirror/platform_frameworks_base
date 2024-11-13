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

public class OperationField {
    int mType;
    String mName;
    String mDescription;
    ArrayList<StringPair> mPossibleValues = new ArrayList<>();

    public OperationField(int type, String name, String description) {
        mType = type;
        mName = name;
        mDescription = description;
    }
    public int getType() {
        return mType;
    }
    public String getName() {
        return mName;
    }
    public String getDescription() {
        return mDescription;
    }
    public ArrayList<StringPair> getPossibleValues() {
        return mPossibleValues;
    }
    public void possibleValue(String name, String value) {
        mPossibleValues.add(new StringPair(name, value));
    }
    public boolean hasEnumeratedValues() {
        return !mPossibleValues.isEmpty();
    }
    public int getSize() {
        switch (mType) {
            case (Operation.BYTE) : return 1;
            case (Operation.INT) : return 4;
            case (Operation.FLOAT) : return 4;
            case (Operation.LONG) : return 8;
            default : return 0;
        }
    }
}
