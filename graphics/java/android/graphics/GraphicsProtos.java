/*
 * Copyright 2020 The Android Open Source Project
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

package android.graphics;

import android.annotation.NonNull;
import android.util.proto.ProtoOutputStream;

/**
 * Utility class for creating protos from parcelable Graphics classes.
 *
 * @hide
 */
public final class GraphicsProtos {
    /** GraphicsProtos can never be an instance */
    private GraphicsProtos() {}

    /**
     * Write to a protocol buffer output stream.
     * Protocol buffer message definition at {@link android.graphics.PointProto}
     *
     * @param point             Point to serialize into a protocol buffer
     * @param protoOutputStream Stream to write the Point object to.
     * @param fieldId           Field Id of the Point as defined in the parent message
     * @hide
     */
    public static void dumpPointProto(
            @NonNull Point point, @NonNull ProtoOutputStream protoOutputStream, long fieldId) {
        final long token = protoOutputStream.start(fieldId);
        protoOutputStream.write(PointProto.X, point.x);
        protoOutputStream.write(PointProto.Y, point.y);
        protoOutputStream.end(token);
    }
}

