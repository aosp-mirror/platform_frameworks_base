/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.net;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link UriCodec}
 */
public class UriCodecTest extends TestCase {

    public void testDecode_emptyString_returnsEmptyString() {
        assertEquals("", UriCodec.decode("",
                false /* convertPlus */,
                StandardCharsets.UTF_8,
                true /* throwOnFailure */));
    }

    public void testDecode_wrongHexDigit_fails() {
        try {
            // %p in the end.
            UriCodec.decode("ab%2f$%C4%82%25%e0%a1%80%p",
                    false /* convertPlus */,
                    StandardCharsets.UTF_8,
                    true /* throwOnFailure */);
            fail("Expected URISyntaxException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    public void testDecode_secondHexDigitWrong_fails() {
        try {
            // %1p in the end.
            UriCodec.decode("ab%2f$%c4%82%25%e0%a1%80%1p",
                    false /* convertPlus */,
                    StandardCharsets.UTF_8,
                    true /* throwOnFailure */);
            fail("Expected URISyntaxException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    public void testDecode_endsWithPercent_fails() {
        try {
            // % in the end.
            UriCodec.decode("ab%2f$%c4%82%25%e0%a1%80%",
                    false /* convertPlus */,
                    StandardCharsets.UTF_8,
                    true /* throwOnFailure */);
            fail("Expected URISyntaxException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    public void testDecode_dontThrowException_appendsUnknownCharacter() {
        assertEquals("ab/$\u0102%\u0840\ufffd",
                UriCodec.decode("ab%2f$%c4%82%25%e0%a1%80%",
                        false /* convertPlus */,
                        StandardCharsets.UTF_8,
                        false /* throwOnFailure */));
    }

    public void testDecode_convertPlus() {
        assertEquals("ab/$\u0102% \u0840",
                UriCodec.decode("ab%2f$%c4%82%25+%e0%a1%80",
                        true /* convertPlus */,
                        StandardCharsets.UTF_8,
                        false /* throwOnFailure */));
    }

    // Last character needs decoding (make sure we are flushing the buffer with chars to decode).
    public void testDecode_lastCharacter() {
        assertEquals("ab/$\u0102%\u0840",
                UriCodec.decode("ab%2f$%c4%82%25%e0%a1%80",
                        false /* convertPlus */,
                        StandardCharsets.UTF_8,
                        true /* throwOnFailure */));
    }

    // Check that a second row of encoded characters is decoded properly (internal buffers are
    // reset properly).
    public void testDecode_secondRowOfEncoded() {
        assertEquals("ab/$\u0102%\u0840aa\u0840",
                UriCodec.decode("ab%2f$%c4%82%25%e0%a1%80aa%e0%a1%80",
                        false /* convertPlus */,
                        StandardCharsets.UTF_8,
                        true /* throwOnFailure */));
    }
}
