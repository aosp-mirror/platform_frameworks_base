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

package android.nfc.cardemulation;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.ComponentNameProto;
import android.util.proto.ProtoOutputStream;

/** @hide */
public final class Utils {
    private Utils() {
    }

    /** Copied from {@link ComponentName#dumpDebug(ProtoOutputStream, long)} */
    public static void dumpDebugComponentName(
            @NonNull ComponentName componentName, @NonNull ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(ComponentNameProto.PACKAGE_NAME, componentName.getPackageName());
        proto.write(ComponentNameProto.CLASS_NAME, componentName.getClassName());
        proto.end(token);
    }
}
