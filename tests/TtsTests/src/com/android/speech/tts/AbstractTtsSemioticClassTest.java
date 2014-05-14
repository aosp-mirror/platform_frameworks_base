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
import android.speech.tts.Utterance.AbstractTtsSemioticClass;

public class AbstractTtsSemioticClassTest extends InstrumentationTestCase {

    public static class TtsMock extends AbstractTtsSemioticClass<TtsMock> {
        public TtsMock() {
            super();
        }

        public TtsMock(Markup markup) {
            super();
        }

        public void setType(String type) {
            mMarkup.setType(type);
        }
    }

    public void testFluentAPI() {
        new TtsMock()
            .setPlainText("a plaintext") // from AbstractTts
            .setGender(Utterance.GENDER_MALE) // from AbstractTtsSemioticClass
            .setType("test"); // from TtsMock
    }

    public void testDefaultConstructor() {
        new TtsMock();
    }

    public void testMarkupConstructor() {
        Markup markup = new Markup();
        new TtsMock(markup);
    }

    public void testGetType() {
        TtsMock t = new TtsMock();
        t.setType("type1");
        assertEquals("type1", t.getType());
        t.setType(null);
        assertEquals(null, t.getType());
        t.setType("type2");
        assertEquals("type2", t.getType());
    }


    public void testDefaultGender() {
        assertEquals(Utterance.GENDER_UNKNOWN, new TtsMock().getGender());
    }

    public void testSetGender() {
        assertEquals(Utterance.GENDER_MALE,
                     new TtsMock().setGender(Utterance.GENDER_MALE).getGender());
    }

    public void testSetGenderNegative() {
        try {
            new TtsMock().setGender(-1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testSetGenderOutOfBounds() {
        try {
            new TtsMock().setGender(4);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testDefaultAnimacy() {
        assertEquals(Utterance.ANIMACY_UNKNOWN, new TtsMock().getAnimacy());
    }

    public void testSetAnimacy() {
        assertEquals(Utterance.ANIMACY_ANIMATE,
                     new TtsMock().setAnimacy(Utterance.ANIMACY_ANIMATE).getAnimacy());
    }

    public void testSetAnimacyNegative() {
        try {
            new TtsMock().setAnimacy(-1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testSetAnimacyOutOfBounds() {
        try {
            new TtsMock().setAnimacy(4);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testDefaultMultiplicity() {
        assertEquals(Utterance.MULTIPLICITY_UNKNOWN, new TtsMock().getMultiplicity());
    }

    public void testSetMultiplicity() {
        assertEquals(Utterance.MULTIPLICITY_DUAL,
                     new TtsMock().setMultiplicity(Utterance.MULTIPLICITY_DUAL).getMultiplicity());
    }

    public void testSetMultiplicityNegative() {
        try {
            new TtsMock().setMultiplicity(-1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testSetMultiplicityOutOfBounds() {
        try {
            new TtsMock().setMultiplicity(4);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testDefaultCase() {
        assertEquals(Utterance.CASE_UNKNOWN, new TtsMock().getCase());
    }

    public void testSetCase() {
        assertEquals(Utterance.CASE_VOCATIVE,
                     new TtsMock().setCase(Utterance.CASE_VOCATIVE).getCase());
    }

    public void testSetCaseNegative() {
        try {
            new TtsMock().setCase(-1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testSetCaseOutOfBounds() {
        try {
            new TtsMock().setCase(9);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {}
    }

    public void testToString() {
        TtsMock t = new TtsMock()
            .setAnimacy(Utterance.ANIMACY_INANIMATE)
            .setCase(Utterance.CASE_INSTRUMENTAL)
            .setGender(Utterance.GENDER_FEMALE)
            .setMultiplicity(Utterance.MULTIPLICITY_PLURAL);
        String str =
            "animacy: \"2\" " +
            "case: \"8\" " +
            "gender: \"3\" " +
            "multiplicity: \"3\"";
        assertEquals(str, t.toString());
    }

    public void testToStringSetToUnkown() {
        TtsMock t = new TtsMock()
            .setAnimacy(Utterance.ANIMACY_INANIMATE)
            .setCase(Utterance.CASE_INSTRUMENTAL)
            .setGender(Utterance.GENDER_FEMALE)
            .setMultiplicity(Utterance.MULTIPLICITY_PLURAL)
        // set back to unknown
            .setAnimacy(Utterance.ANIMACY_UNKNOWN)
            .setCase(Utterance.CASE_UNKNOWN)
            .setGender(Utterance.GENDER_UNKNOWN)
            .setMultiplicity(Utterance.MULTIPLICITY_UNKNOWN);
        String str = "";
        assertEquals(str, t.toString());
    }

}
