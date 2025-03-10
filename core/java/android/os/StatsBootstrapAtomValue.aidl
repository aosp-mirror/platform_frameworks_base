/*
 * Copyright 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.os;
/*
 * Supported field types.
 *
 * @hide
 */
parcelable StatsBootstrapAtomValue {
    union Primitive {
        boolean boolValue;
        int intValue;
        long longValue;
        float floatValue;
        String stringValue;
        byte[] bytesValue;
	String[] stringArrayValue;
    }

    Primitive value;

    parcelable Annotation {
        // Match the definitions in
        // packages/modules/StatsD/framework/java/android/util/StatsLog.java
        // Only supports UIDs for now.
        @Backing(type="byte")
        enum Id {
            NONE,
            IS_UID,
        }
        Id id;

        union Primitive {
            boolean boolValue;
            int intValue;
        }
        Primitive value;
    }

    Annotation[] annotations;
}
