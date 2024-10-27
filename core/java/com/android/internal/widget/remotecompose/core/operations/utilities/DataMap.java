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
package com.android.internal.widget.remotecompose.core.operations.utilities;

public class DataMap {
    public String[] mNames;
    public int[] mIds;
    public byte[] mTypes;

    public DataMap(String[] names, byte[] types, int[] ids) {
        mNames = names;
        mTypes = types;
        mIds = ids;
    }

    public int getPos(String str) {
        for (int i = 0; i < mNames.length; i++) {
            String name = mNames[i];
            if (str.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    public byte getType(int pos) {
        return mTypes[pos];
    }

    public int getId(int pos) {
        return mIds[pos];
    }
}
