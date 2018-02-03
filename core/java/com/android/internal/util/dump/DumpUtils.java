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

package com.android.internal.util.dump;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.ComponentNameProto;

public class DumpUtils {
    /**
     * Write a string to a proto if the string is not {@code null}.
     *
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the string
     * @param string The string to write
     */
    public static void writeStringIfNotNull(@NonNull DualDumpOutputStream proto, String idName,
            long id, @Nullable String string) {
        if (string != null) {
            proto.write(idName, id, string);
        }
    }

    /**
     * Write a {@link ComponentName} to a proto.
     *
     * @param proto The proto to write to
     * @param idName Clear text name of the proto-id
     * @param id The proto-id of the component name
     * @param component The component name to write
     */
    public static void writeComponentName(@NonNull DualDumpOutputStream proto, String idName,
            long id, @NonNull ComponentName component) {
        long token = proto.start(idName, id);
        proto.write("package_name", ComponentNameProto.PACKAGE_NAME, component.getPackageName());
        proto.write("class_name", ComponentNameProto.CLASS_NAME, component.getClassName());
        proto.end(token);
    }
}
