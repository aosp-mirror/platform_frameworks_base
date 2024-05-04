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
package androidx.window.extensions.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.window.extensions.core.util.function.Consumer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * A class to validate {@link DeduplicateConsumer}.
 */
public class DeduplicateConsumerTest {

    @Test
    public void test_duplicate_value_is_filtered() {
        String value = "test_value";
        List<String> expected = new ArrayList<>();
        expected.add(value);
        RecordingConsumer recordingConsumer = new RecordingConsumer();
        DeduplicateConsumer<String> deduplicateConsumer =
                new DeduplicateConsumer<>(recordingConsumer);

        deduplicateConsumer.accept(value);
        deduplicateConsumer.accept(value);

        assertEquals(expected, recordingConsumer.getValues());
    }

    @Test
    public void test_different_value_is_filtered() {
        String value = "test_value";
        String newValue = "test_value_new";
        List<String> expected = new ArrayList<>();
        expected.add(value);
        expected.add(newValue);
        RecordingConsumer recordingConsumer = new RecordingConsumer();
        DeduplicateConsumer<String> deduplicateConsumer =
                new DeduplicateConsumer<>(recordingConsumer);

        deduplicateConsumer.accept(value);
        deduplicateConsumer.accept(value);
        deduplicateConsumer.accept(newValue);

        assertEquals(expected, recordingConsumer.getValues());
    }

    @Test
    public void test_match_against_consumer_property_returns_true() {
        RecordingConsumer recordingConsumer = new RecordingConsumer();
        DeduplicateConsumer<String> deduplicateConsumer =
                new DeduplicateConsumer<>(recordingConsumer);

        assertTrue(deduplicateConsumer.matchesConsumer(recordingConsumer));
    }

    @Test
    public void test_match_against_self_returns_true() {
        RecordingConsumer recordingConsumer = new RecordingConsumer();
        DeduplicateConsumer<String> deduplicateConsumer =
                new DeduplicateConsumer<>(recordingConsumer);

        assertTrue(deduplicateConsumer.matchesConsumer(deduplicateConsumer));
    }

    private static final class RecordingConsumer implements Consumer<String> {

        private final List<String> mValues = new ArrayList<>();

        @Override
        public void accept(String s) {
            mValues.add(s);
        }

        public List<String> getValues() {
            return mValues;
        }
    }
}
