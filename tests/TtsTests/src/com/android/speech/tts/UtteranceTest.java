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

import android.speech.tts.Markup;
import android.speech.tts.Utterance;
import android.speech.tts.Utterance.TtsCardinal;
import android.speech.tts.Utterance.TtsText;

import android.test.InstrumentationTestCase;

public class UtteranceTest extends InstrumentationTestCase {

    public void testEmptyUtterance() {
        Utterance utt = new Utterance();
        assertEquals(0, utt.size());
    }

    public void testSizeCardinal() {
        Utterance utt = new Utterance()
                .append(new TtsCardinal(42));
        assertEquals(1, utt.size());
    }

    public void testSizeCardinalString() {
        Utterance utt = new Utterance()
                .append(new TtsCardinal(42))
                .append(new TtsText("is the answer"));
        assertEquals(2, utt.size());
    }

    public void testMarkupEmpty() {
        Markup m = new Utterance().createMarkup();
        assertEquals("utterance", m.getType());
        assertEquals("", m.getPlainText());
    }

    public void testMarkupCardinal() {
        Utterance utt = new Utterance()
                .append(new TtsCardinal(42));
        Markup markup = utt.createMarkup();
        assertEquals("utterance", markup.getType());
        assertEquals("42", markup.getPlainText());
        assertEquals("42", markup.getNestedMarkup(0).getParameter("integer"));
        assertEquals("42", markup.getNestedMarkup(0).getPlainText());
    }

    public void testMarkupCardinalString() {
        Utterance utt = new Utterance()
                .append(new TtsCardinal(42))
                .append(new TtsText("is not just a number."));
        Markup markup = utt.createMarkup();
        assertEquals("utterance", markup.getType());
        assertEquals("42 is not just a number.", markup.getPlainText());
        assertEquals("cardinal", markup.getNestedMarkup(0).getType());
        assertEquals("42", markup.getNestedMarkup(0).getParameter("integer"));
        assertEquals("42", markup.getNestedMarkup(0).getPlainText());
        assertEquals("text", markup.getNestedMarkup(1).getType());
        assertEquals("is not just a number.", markup.getNestedMarkup(1).getParameter("text"));
        assertEquals("is not just a number.", markup.getNestedMarkup(1).getPlainText());
    }

    public void testTextCardinalToFromString() {
        Utterance utt = new Utterance()
                .append(new TtsCardinal(55))
                .append(new TtsText("this is a text."));
        String str = utt.toString();
        assertEquals(
            "type: \"utterance\" " +
            "markup { " +
                "type: \"cardinal\" " +
                "integer: \"55\" " +
            "} " +
            "markup { " +
                "type: \"text\" " +
                "text: \"this is a text.\" " +
            "}"
            , str);

        Utterance utt_new = Utterance.utteranceFromString(str);
        assertEquals(str, utt_new.toString());
    }

    public void testNotUtteranceFromString() {
        String str =
            "type: \"this_is_not_an_utterance\" " +
            "markup { " +
                "type: \"cardinal\" " +
                "plain_text: \"55\" " +
                "integer: \"55\" " +
            "}";
        try {
            Utterance.utteranceFromString(str);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testFromMarkup() {
        String markup_str =
            "type: \"utterance\" " +
            "markup { " +
                "type: \"cardinal\" " +
                "plain_text: \"55\" " +
                "integer: \"55\" " +
            "} " +
            "markup { " +
                "type: \"text\" " +
                "plain_text: \"this is a text.\" " +
                "text: \"this is a text.\" " +
            "}";
        Utterance utt = Utterance.utteranceFromString(markup_str);
        assertEquals(markup_str, utt.toString());
    }

    public void testsetPlainText() {
        Utterance utt = new Utterance()
            .append(new TtsCardinal(-100).setPlainText("minus one hundred"));
        assertEquals("minus one hundred", utt.get(0).getPlainText());
    }

    public void testRemoveTextThroughSet() {
        Utterance utt = new Utterance()
            .append(new TtsText().setText("test").setText(null));
        assertNull(((TtsText) utt.get(0)).getText());
    }

    public void testUnknownNodeWithPlainText() {
        String str =
            "type: \"utterance\" " +
            "markup { " +
                "type: \"some_future_feature\" " +
                "plain_text: \"biep bob bob\" " +
                "bombom: \"lorum ipsum\" " +
            "}";
        Utterance utt = Utterance.utteranceFromString(str);
        assertNotNull(utt);
        assertEquals("text", utt.get(0).getType());
        assertEquals("biep bob bob", ((TtsText) utt.get(0)).getText());
    }

    public void testUnknownNodeWithNoPlainTexts() {
        String str =
            "type: \"utterance\" " +
            "markup { " +
                "type: \"some_future_feature\" " +
                "bombom: \"lorum ipsum\" " +
                "markup { type: \"cardinal\" integer: \"10\" } " +
                "markup { type: \"text\" text: \"pears\" } " +
            "}";
        Utterance utt = Utterance.utteranceFromString(str);
        assertEquals(
            "type: \"utterance\" " +
            "markup { type: \"cardinal\" integer: \"10\" } " +
            "markup { type: \"text\" text: \"pears\" }", utt.toString());
    }

    public void testCreateWarningOnFallbackTrue() {
        Utterance utt = new Utterance()
          .append(new TtsText("test"))
          .setNoWarningOnFallback(true);
        assertEquals(
            "type: \"utterance\" " +
            "no_warning_on_fallback: \"true\" " +
            "markup { " +
                "type: \"text\" " +
                "text: \"test\" " +
            "}", utt.toString());
    }

    public void testCreateWarningOnFallbackFalse() {
        Utterance utt = new Utterance()
          .append(new TtsText("test"))
          .setNoWarningOnFallback(false);
        assertEquals(
            "type: \"utterance\" " +
            "no_warning_on_fallback: \"false\" " +
            "markup { " +
                "type: \"text\" " +
                "text: \"test\" " +
            "}", utt.toString());
    }

    public void testCreatePlainTexts() {
        Utterance utt = new Utterance()
            .append(new TtsText("test"))
            .append(new TtsCardinal(-55));
        assertEquals(
            "type: \"utterance\" " +
            "plain_text: \"test -55\" " +
            "markup { type: \"text\" plain_text: \"test\" text: \"test\" } " +
            "markup { type: \"cardinal\" plain_text: \"-55\" integer: \"-55\" }",
            utt.createMarkup().toString()
        );
    }

    public void testDontOverwritePlainTexts() {
        Utterance utt = new Utterance()
            .append(new TtsText("test").setPlainText("else"))
            .append(new TtsCardinal(-55).setPlainText("44"));
        assertEquals(
            "type: \"utterance\" " +
            "plain_text: \"else 44\" " +
            "markup { type: \"text\" plain_text: \"else\" text: \"test\" } " +
            "markup { type: \"cardinal\" plain_text: \"44\" integer: \"-55\" }",
            utt.createMarkup().toString()
        );
    }

    public void test99BottlesOnWallMarkup() {
        Utterance utt = new Utterance()
            .append("there are")
            .append(99)
            .append("bottles on the wall.");
        assertEquals(
                "type: \"utterance\" " +
                "plain_text: \"there are 99 bottles on the wall.\" " +
                "markup { type: \"text\" plain_text: \"there are\" text: \"there are\" } " +
                "markup { type: \"cardinal\" plain_text: \"99\" integer: \"99\" } " +
                "markup { type: \"text\" plain_text: \"bottles on the wall.\" text: \"bottles on the wall.\" }",
                utt.createMarkup().toString());
        assertEquals("99", utt.createMarkup().getNestedMarkup(1).getPlainText());
        Markup markup = new Markup(utt.createMarkup());
        assertEquals("99", markup.getNestedMarkup(1).getPlainText());
    }

    public void testWhat() {
        Utterance utt = new Utterance()
            .append("there are")
            .append(99)
            .append("bottles on the wall.");
        Markup m = utt.createMarkup();
        m.getNestedMarkup(1).getPlainText().equals("99");
    }
}
