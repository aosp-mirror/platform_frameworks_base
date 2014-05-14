/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.speech.tts;

import android.test.InstrumentationTestCase;

import android.speech.tts.Markup;
import android.speech.tts.Utterance;
import android.speech.tts.Utterance.TtsText;

public class TtsTextTest extends InstrumentationTestCase {

    public void testConstruct() {
        assertNotNull(new TtsText());
    }

    public void testFluentAPI() {
        new TtsText()
            .setPlainText("a plaintext") // from AbstractTts
            .setGender(Utterance.GENDER_MALE) // from AbstractTtsSemioticClass
            .setText("text"); // from TtsText
    }

    public void testConstructEmptyString() {
        assertTrue(new TtsText("").getText().isEmpty());
    }

    public void testConstructString() {
        assertEquals("this is a test.", new TtsText("this is a test.").getText());
    }

    public void testSetText() {
        assertEquals("This is a test.", new TtsText().setText("This is a test.").getText());
    }

    public void testEmptyMarkup() {
        TtsText t = new TtsText();
        Markup m = t.getMarkup();
        assertEquals("text", m.getType());
        assertNull(m.getPlainText());
        assertEquals(0, m.nestedMarkupSize());
    }

    public void testConstructStringMarkup() {
        TtsText t = new TtsText("test");
        Markup m = t.getMarkup();
        assertEquals("text", m.getType());
        assertEquals("test", m.getParameter("text"));
        assertEquals(0, m.nestedMarkupSize());
    }

    public void testSetStringMarkup() {
        TtsText t = new TtsText();
        t.setText("test");
        Markup m = t.getMarkup();
        assertEquals("text", m.getType());
        assertEquals("test", m.getParameter("text"));
        assertEquals(0, m.nestedMarkupSize());
    }
}
