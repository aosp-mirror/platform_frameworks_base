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

import junit.framework.Assert;
import android.test.InstrumentationTestCase;
import android.test.MoreAsserts;

import android.speech.tts.Markup;
import android.speech.tts.Utterance;
import android.speech.tts.Utterance.TtsCardinal;
import android.speech.tts.Utterance.TtsText;

public class TtsCardinalTest extends InstrumentationTestCase {

    public void testConstruct() {
        assertNotNull(new TtsCardinal(0));
    }

    public void testFluentAPI() {
        new TtsCardinal()
            .setPlainText("a plaintext") // from AbstractTts
            .setGender(Utterance.GENDER_MALE) // from AbstractTtsSemioticClass
            .setInteger("-10001"); // from TtsText
    }

    public void testZero() {
        assertEquals("0", new TtsCardinal(0).getInteger());
    }

    public void testThirtyOne() {
        assertEquals("31", new TtsCardinal(31).getInteger());
    }

    public void testMarkupZero() {
        TtsCardinal c = new TtsCardinal(0);
        Markup m = c.getMarkup();
        assertEquals("0", m.getParameter("integer"));
    }

    public void testMarkupThirtyOne() {
        TtsCardinal c = new TtsCardinal(31);
        Markup m = c.getMarkup();
        assertEquals("31", m.getParameter("integer"));
    }

    public void testMarkupThirtyOneString() {
        TtsCardinal c = new TtsCardinal("31");
        Markup m = c.getMarkup();
        assertEquals("31", m.getParameter("integer"));
    }

    public void testMarkupNegativeThirtyOne() {
        TtsCardinal c = new TtsCardinal(-31);
        Markup m = c.getMarkup();
        assertEquals("-31", m.getParameter("integer"));
    }

    public void testMarkupMinusZero() {
        TtsCardinal c = new TtsCardinal("-0");
        Markup m = c.getMarkup();
        assertEquals("-0", m.getParameter("integer"));
    }

    public void testMarkupNegativeThirtyOneString() {
        TtsCardinal c = new TtsCardinal("-31");
        Markup m = c.getMarkup();
        assertEquals("-31", m.getParameter("integer"));
    }

    public void testOnlyLetters() {
        try {
            new TtsCardinal("abc");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testOnlyMinus() {
        try {
            new TtsCardinal("-");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testNegativeLetters() {
        try {
            new TtsCardinal("-abc");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testLetterNumberMix() {
        try {
            new TtsCardinal("-0a1b2c");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void letterNumberMix2() {
        try {
            new TtsCardinal("-a0b1c2");
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }
}
