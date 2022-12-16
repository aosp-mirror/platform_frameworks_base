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

import com.android.modules.utils.TypedXmlPullParser;

import java.io.IOException;

/**
 * Parse XML tags into valid {@link XmlSerializedVibration} instances.
 *
 * @param <T> The vibration type that will be parsed.
 * @see XmlSerializedVibration
 * @hide
 */
@FunctionalInterface
public interface XmlParser<T> {

    /**
     * Parses the current XML tag with all nested tags into a single {@link XmlSerializedVibration}.
     *
     * <p>This method will consume nested XML tags until it finds the
     * {@link TypedXmlPullParser#END_TAG} for the current tag.
     *
     * <p>The vibration reconstructed by the returned {@link XmlSerializedVibration#deserialize()}
     * is guaranteed to be valid. This method will throw an exception otherwise.
     *
     * @param pullParser The {@link TypedXmlPullParser} with the input XML.
     * @return The parsed vibration wrapped in a {@link XmlSerializedVibration} representation.
     * @throws IOException        On any I/O error while reading the input XML
     * @throws XmlParserException If the XML content does not represent a valid vibration.
     */
    XmlSerializedVibration<T> parseTag(@NonNull TypedXmlPullParser pullParser)
            throws XmlParserException, IOException;
}
