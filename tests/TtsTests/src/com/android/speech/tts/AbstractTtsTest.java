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
import android.speech.tts.Utterance.AbstractTts;

public class AbstractTtsTest extends InstrumentationTestCase {

    public static class TtsMock extends AbstractTts<TtsMock> {
        public TtsMock() {
            super();
        }

        public TtsMock(Markup markup) {
            super();
        }

        public void setType(String type) {
            mMarkup.setType(type);
        }

        @Override
        public TtsMock setParameter(String key, String value) {
           return super.setParameter(key, value);
        }

        @Override
        public TtsMock removeParameter(String key) {
           return super.removeParameter(key);
        }
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

    public void testGeneratePlainText() {
        assertNull(new TtsMock().generatePlainText());
    }

    public void testToString() {
        TtsMock t = new TtsMock();
        t.setType("a_type");
        t.setPlainText("a plaintext");
        t.setParameter("key1", "value1");
        t.setParameter("aaa", "value2");
        String str =
            "type: \"a_type\" " +
            "plain_text: \"a plaintext\" " +
            "aaa: \"value2\" " +
            "key1: \"value1\"";
        assertEquals(str, t.toString());
    }

    public void testRemoveParameter() {
        TtsMock t = new TtsMock();
        t.setParameter("key1", "value 1");
        t.setParameter("aaa", "value a");
        t.removeParameter("key1");
        String str =
            "aaa: \"value a\"";
        assertEquals(str, t.toString());
    }

    public void testRemoveParameterBySettingNull() {
        TtsMock t = new TtsMock();
        t.setParameter("key1", "value 1");
        t.setParameter("aaa", "value a");
        t.setParameter("aaa", null);
        String str =
            "key1: \"value 1\"";
        assertEquals(str, t.toString());
    }
}
