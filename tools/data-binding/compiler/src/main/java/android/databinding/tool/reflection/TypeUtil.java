/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.databinding.tool.reflection;

public abstract class TypeUtil {

    public static final String BYTE = "B";

    public static final String CHAR = "C";

    public static final String DOUBLE = "D";

    public static final String FLOAT = "F";

    public static final String INT = "I";

    public static final String LONG = "J";

    public static final String SHORT = "S";

    public static final String VOID = "V";

    public static final String BOOLEAN = "Z";

    public static final String ARRAY = "[";

    public static final String CLASS_PREFIX = "L";

    public static final String CLASS_SUFFIX = ";";

    private static TypeUtil sInstance;

    abstract public String getDescription(ModelClass modelClass);

    abstract public String getDescription(ModelMethod modelMethod);

    public static TypeUtil getInstance() {
        if (sInstance == null) {
            sInstance = ModelAnalyzer.getInstance().createTypeUtil();
        }
        return sInstance;
    }
}
