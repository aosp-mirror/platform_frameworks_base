/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.media.soundtrigger_middleware;

import android.media.soundtrigger_middleware.SoundModelType;

/**
 * Base sound model descriptor. This struct can be extended for various specific types by way of
 * aggregation.
 * {@hide}
 */
parcelable SoundModel {
    /** Model type. */
    SoundModelType type;
    /** Unique sound model ID. */
    String uuid;
    /**
     * Unique vendor ID. Identifies the engine the sound model
     * was build for */
    String vendorUuid;
    /** Opaque data transparent to Android framework */
    FileDescriptor data;
    /** Size of the above data, in bytes. */
    int dataSize;
}
