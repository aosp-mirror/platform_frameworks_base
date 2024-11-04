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

package android.processor.property_cache;

import com.google.common.collect.ImmutableMap;

public final class Constants {
    public static final String EMPTY_STRING = "";
    public static final String JAVA_LANG_VOID = "java.lang.Void";
    public static final ImmutableMap<String, String> PRIMITIVE_TYPE_MAP =
            ImmutableMap.of(
                    "int", "java.lang.Integer",
                    "boolean", "java.lang.Boolean",
                    "long", "java.lang.Long",
                    "float", "java.lang.Float",
                    "double", "java.lang.Double",
                    "byte", "java.lang.Byte",
                    "short", "java.lang.Short",
                    "char", "java.lang.Character");

    public static final String METHOD_COMMENT = "\n    /**"
            + "\n    * This method is auto-generated%s"
            + "\n    * "
            + "\n    * @hide"
            + "\n    */";
}
