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

import static com.android.internal.util.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.os.VibrationEffect;

import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;
import java.util.Arrays;

/**
 * Serialized representation of a {@link VibrationEffect}.
 *
 * <p>The vibration is represented by a list of serialized segments that can be added to a
 * {@link VibrationEffect.Composition} during the {@link #deserialize()} procedure.
 *
 * @hide
 */
final class SerializedVibrationEffect implements XmlSerializedVibration<VibrationEffect> {

    @NonNull
    private final SerializedSegment[] mSegments;

    SerializedVibrationEffect(@NonNull SerializedSegment segment) {
        requireNonNull(segment);
        mSegments = new SerializedSegment[]{ segment };
    }

    SerializedVibrationEffect(@NonNull SerializedSegment[] segments) {
        requireNonNull(segments);
        checkArgument(segments.length > 0, "Unsupported empty vibration");
        mSegments = segments;
    }

    @NonNull
    @Override
    public VibrationEffect deserialize() {
        VibrationEffect.Composition composition = VibrationEffect.startComposition();
        for (SerializedSegment segment : mSegments) {
            segment.deserializeIntoComposition(composition);
        }
        return composition.compose();
    }

    @Override
    public void write(@NonNull TypedXmlSerializer serializer)
            throws IOException {
        serializer.startTag(XmlConstants.NAMESPACE, XmlConstants.TAG_VIBRATION_EFFECT);
        writeContent(serializer);
        serializer.endTag(XmlConstants.NAMESPACE, XmlConstants.TAG_VIBRATION_EFFECT);
    }

    @Override
    public void writeContent(@NonNull TypedXmlSerializer serializer) throws IOException {
        for (SerializedSegment segment : mSegments) {
            segment.write(serializer);
        }
    }

    @Override
    public String toString() {
        return "SerializedVibrationEffect{"
                + "segments=" + Arrays.toString(mSegments)
                + '}';
    }

    /**
     * Serialized representation of a generic part of a {@link VibrationEffect}.
     *
     * <p>This can represent a single {@link android.os.vibrator.VibrationEffectSegment} (e.g. a
     * single primitive or predefined effect) or a more complex effect, like a repeating
     * amplitude-step waveform.
     *
     * @see XmlSerializedVibration
     */
    interface SerializedSegment {

        /** Writes this segment into a {@link TypedXmlSerializer}. */
        void write(@NonNull TypedXmlSerializer serializer) throws IOException;

        /** Adds this segment into a {@link VibrationEffect.Composition}. */
        void deserializeIntoComposition(@NonNull VibrationEffect.Composition composition);
    }
}
