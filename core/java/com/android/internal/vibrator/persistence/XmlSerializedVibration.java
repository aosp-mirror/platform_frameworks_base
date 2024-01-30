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

package com.android.internal.vibrator.persistence;

import android.annotation.NonNull;

import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;

/**
 * Serialized representation of a generic vibration.
 *
 * <p>This can be used to represent a {@link android.os.CombinedVibration} or a
 * {@link android.os.VibrationEffect}. Instances can be created from vibration objects via
 * {@link XmlSerializer}, or from XML content via {@link XmlParser}.
 *
 * <p>The separation of serialization and writing procedures enables configurable rules to define
 * which vibrations can be successfully serialized before any data is written to the output stream.
 * Serialization can fail early and prevent writing partial data into the output.
 *
 * @param <T> The type of vibration represented by this serialization
 * @hide
 */
public interface XmlSerializedVibration<T> {

    /** Reconstructs the vibration using the serialized fields. */
    @NonNull
    T deserialize();

    /**
     * Writes the top level XML tag and the serialized fields into given XML.
     *
     * @param serializer The output XML serializer where the vibration will be written
     */
    void write(@NonNull TypedXmlSerializer serializer) throws IOException;

    /**
     * Writes the serialized fields into given XML, without the top level XML tag.
     *
     * <p>This allows the same serialized representation of a vibration to be used in different
     * contexts (e.g. a {@link android.os.VibrationEffect} can be written into any of the tags
     * {@code <vibration-effect>}, {@code <parallel-vibration-effect>}
     * or {@code <vibration-effect vibratorId="0">}).
     *
     * @param serializer The output XML serializer where the vibration will be written
     */
    void writeContent(@NonNull TypedXmlSerializer serializer) throws IOException;
}
