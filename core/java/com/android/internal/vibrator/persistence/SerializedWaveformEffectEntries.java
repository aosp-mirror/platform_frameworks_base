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

package com.android.internal.vibrator.persistence;

import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_AMPLITUDE;
import static com.android.internal.vibrator.persistence.XmlConstants.ATTRIBUTE_DURATION_MS;
import static com.android.internal.vibrator.persistence.XmlConstants.NAMESPACE;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_WAVEFORM_ENTRY;
import static com.android.internal.vibrator.persistence.XmlConstants.VALUE_AMPLITUDE_DEFAULT;

import android.annotation.NonNull;
import android.os.VibrationEffect;
import android.util.IntArray;
import android.util.LongArray;

import com.android.internal.vibrator.persistence.SerializedComposedEffect.SerializedSegment;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;
import java.util.Arrays;

/**
 * Serialized representation of a list of waveform entries created via
 * {@link VibrationEffect#createWaveform(long[], int[], int)}.
 *
 * @hide
 */
final class SerializedWaveformEffectEntries implements SerializedSegment {

    @NonNull
    private final long[] mTimings;
    @NonNull
    private final int[] mAmplitudes;

    private SerializedWaveformEffectEntries(@NonNull long[] timings,
            @NonNull int[] amplitudes) {
        mTimings = timings;
        mAmplitudes = amplitudes;
    }

    @Override
    public void deserializeIntoComposition(@NonNull VibrationEffect.Composition composition) {
        composition.addEffect(VibrationEffect.createWaveform(mTimings, mAmplitudes, -1));
    }

    @Override
    public void write(@NonNull TypedXmlSerializer serializer) throws IOException {
        for (int i = 0; i < mTimings.length; i++) {
            serializer.startTag(NAMESPACE, TAG_WAVEFORM_ENTRY);

            if (mAmplitudes[i] == VibrationEffect.DEFAULT_AMPLITUDE) {
                serializer.attribute(NAMESPACE, ATTRIBUTE_AMPLITUDE, VALUE_AMPLITUDE_DEFAULT);
            } else {
                serializer.attributeInt(NAMESPACE, ATTRIBUTE_AMPLITUDE, mAmplitudes[i]);
            }

            serializer.attributeLong(NAMESPACE, ATTRIBUTE_DURATION_MS, mTimings[i]);
            serializer.endTag(NAMESPACE, TAG_WAVEFORM_ENTRY);
        }

    }

    @Override
    public String toString() {
        return "SerializedWaveformEffectEntries{"
                + "timings=" + Arrays.toString(mTimings)
                + ", amplitudes=" + Arrays.toString(mAmplitudes)
                + '}';
    }

    /** Builder for {@link SerializedWaveformEffectEntries}. */
    static final class Builder {
        private final LongArray mTimings = new LongArray();
        private final IntArray mAmplitudes = new IntArray();

        void addDurationAndAmplitude(long durationMs, int amplitude) {
            mTimings.add(durationMs);
            mAmplitudes.add(amplitude);
        }

        boolean hasNonZeroDuration() {
            for (int i = 0; i < mTimings.size(); i++) {
                if (mTimings.get(i) > 0) {
                    return true;
                }
            }
            return false;
        }

        SerializedWaveformEffectEntries build() {
            return new SerializedWaveformEffectEntries(
                    mTimings.toArray(), mAmplitudes.toArray());
        }
    }

    /** Parser implementation for the {@link XmlConstants#TAG_WAVEFORM_ENTRY}. */
    static final class Parser {

        /** Parses a single {@link XmlConstants#TAG_WAVEFORM_ENTRY} into the builder. */
        public static void parseWaveformEntry(TypedXmlPullParser parser, Builder waveformBuilder)
                throws XmlParserException, IOException {
            SerializedAmplitudeStepWaveform.Parser.parseWaveformEntry(parser,
                    waveformBuilder::addDurationAndAmplitude);
        }
    }
}
