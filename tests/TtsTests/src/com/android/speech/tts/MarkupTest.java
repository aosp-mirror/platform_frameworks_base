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
import android.os.Parcel;
import android.test.InstrumentationTestCase;

import android.speech.tts.Markup;

public class MarkupTest extends InstrumentationTestCase {

  public void testEmptyMarkup() {
      Markup markup = new Markup();
      assertNull(markup.getType());
      assertNull(markup.getPlainText());
      assertEquals(0, markup.parametersSize());
      assertEquals(0, markup.nestedMarkupSize());
  }

  public void testGetSetType() {
      Markup markup = new Markup();
      markup.setType("one");
      assertEquals("one", markup.getType());
      markup.setType(null);
      assertNull(markup.getType());
      markup.setType("two");
      assertEquals("two", markup.getType());
  }

  public void testGetSetPlainText() {
      Markup markup = new Markup();
      markup.setPlainText("one");
      assertEquals("one", markup.getPlainText());
      markup.setPlainText(null);
      assertNull(markup.getPlainText());
      markup.setPlainText("two");
      assertEquals("two", markup.getPlainText());
  }

  public void testParametersSize1() {
      Markup markup = new Markup();
      markup.addNestedMarkup(new Markup());
      assertEquals(1, markup.nestedMarkupSize());
  }

  public void testParametersSize2() {
      Markup markup = new Markup();
      markup.addNestedMarkup(new Markup());
      markup.addNestedMarkup(new Markup());
      assertEquals(2, markup.nestedMarkupSize());
  }

  public void testRemoveParameter() {
      Markup m = new Markup("type");
      m.setParameter("key1", "value1");
      m.setParameter("key2", "value2");
      m.setParameter("key3", "value3");
      assertEquals(3, m.parametersSize());
      m.removeParameter("key1");
      assertEquals(2, m.parametersSize());
      m.removeParameter("key3");
      assertEquals(1, m.parametersSize());
      assertNull(m.getParameter("key1"));
      assertEquals("value2", m.getParameter("key2"));
      assertNull(m.getParameter("key3"));
  }

  public void testEmptyEqual() {
      Markup m1 = new Markup();
      Markup m2 = new Markup();
      assertTrue(m1.equals(m2));
  }

  public void testFilledEqual() {
      Markup m1 = new Markup();
      m1.setType("type");
      m1.setPlainText("plain text");
      m1.setParameter("key1", "value1");
      m1.addNestedMarkup(new Markup());
      Markup m2 = new Markup();
      m2.setType("type");
      m2.setPlainText("plain text");
      m2.setParameter("key1", "value1");
      m2.addNestedMarkup(new Markup());
      assertTrue(m1.equals(m2));
  }

  public void testDifferentTypeEqual() {
      Markup m1 = new Markup();
      m1.setType("type1");
      Markup m2 = new Markup();
      m2.setType("type2");
      assertFalse(m1.equals(m2));
  }

  public void testDifferentPlainTextEqual() {
      Markup m1 = new Markup();
      m1.setPlainText("plainText1");
      Markup m2 = new Markup();
      m2.setPlainText("plainText2");
      assertFalse(m1.equals(m2));
  }

  public void testDifferentParamEqual() {
      Markup m1 = new Markup();
      m1.setParameter("test", "value1");
      Markup m2 = new Markup();
      m2.setParameter("test", "value2");
      assertFalse(m1.equals(m2));
  }

  public void testDifferentParameterKeyEqual() {
      Markup m1 = new Markup();
      m1.setParameter("test1", "value");
      Markup m2 = new Markup();
      m2.setParameter("test2", "value");
      assertFalse(m1.equals(m2));
  }

  public void testDifferentParameterValueEqual() {
      Markup m1 = new Markup();
      m1.setParameter("test", "value1");
      Markup m2 = new Markup();
      m2.setParameter("test", "value2");
      assertFalse(m1.equals(m2));
  }

  public void testDifferentNestedMarkupEqual() {
      Markup m1 = new Markup();
      Markup nested = new Markup();
      nested.setParameter("key", "value");
      m1.addNestedMarkup(nested);
      Markup m2 = new Markup();
      m2.addNestedMarkup(new Markup());
      assertFalse(m1.equals(m2));
  }

  public void testEmptyToFromString() {
      Markup m1 = new Markup();
      String str = m1.toString();
      assertEquals("", str);

      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1, m2);
  }

  public void testTypeToFromString() {
      Markup m1 = new Markup("atype");
      String str = m1.toString();
      assertEquals("type: \"atype\"", str);
      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1, m2);
  }

  public void testPlainTextToFromString() {
      Markup m1 = new Markup();
      m1.setPlainText("some_plainText");
      String str = m1.toString();
      assertEquals("plain_text: \"some_plainText\"", str);

      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1, m2);
  }

  public void testParameterToFromString() {
      Markup m1 = new Markup("cardinal");
      m1.setParameter("integer", "-22");
      String str = m1.toString();
      assertEquals("type: \"cardinal\" integer: \"-22\"", str);
      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1, m2);
  }

  // Parameters should be ordered alphabettically, so the output is stable.
  public void testParameterOrderToFromString() {
      Markup m1 = new Markup("cardinal");
      m1.setParameter("ccc", "-");
      m1.setParameter("aaa", "-");
      m1.setParameter("aa", "-");
      m1.setParameter("bbb", "-");
      String str = m1.toString();
      assertEquals(
              "type: \"cardinal\" " +
              "aa: \"-\" " +
              "aaa: \"-\" " +
              "bbb: \"-\" " +
              "ccc: \"-\"",
              str);
      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1, m2);
  }

  public void testEmptyNestedToFromString() {
      Markup m1 = new Markup("atype");
      m1.addNestedMarkup(new Markup());
      String str = m1.toString();
      assertEquals("type: \"atype\" markup {}", str);
      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1, m2);
  }

  public void testNestedWithTypeToFromString() {
      Markup m1 = new Markup("atype");
      m1.addNestedMarkup(new Markup("nested_type"));
      String str = m1.toString();
      assertEquals(
              "type: \"atype\" " +
              "markup { type: \"nested_type\" }",
              str);
      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1, m2);
  }

  public void testRemoveNestedMarkup() {
      Markup m = new Markup("atype");
      Markup m1 = new Markup("nested_type1");
      Markup m2 = new Markup("nested_type2");
      Markup m3 = new Markup("nested_type3");
      m.addNestedMarkup(m1);
      m.addNestedMarkup(m2);
      m.addNestedMarkup(m3);
      m.removeNestedMarkup(m1);
      m.removeNestedMarkup(m3);
      String str = m.toString();
      assertEquals(
              "type: \"atype\" " +
              "markup { type: \"nested_type2\" }",
              str);
      Markup mFromString = Markup.markupFromString(str);
      assertEquals(m, mFromString);
  }

  public void testLotsofNestingToFromString() {
      Markup m1 = new Markup("top")
          .addNestedMarkup(new Markup("top_child1")
              .addNestedMarkup(new Markup("top_child1_child1"))
              .addNestedMarkup(new Markup("top_child1_child2")))
          .addNestedMarkup(new Markup("top_child2")
              .addNestedMarkup(new Markup("top_child2_child2"))
              .addNestedMarkup(new Markup("top_child2_child2")));

      String str = m1.toString();
      assertEquals(
              "type: \"top\" " +
              "markup { " +
                  "type: \"top_child1\" " +
                  "markup { type: \"top_child1_child1\" } " +
                  "markup { type: \"top_child1_child2\" } " +
              "} " +
              "markup { " +
                  "type: \"top_child2\" " +
                  "markup { type: \"top_child2_child2\" } " +
                  "markup { type: \"top_child2_child2\" } " +
              "}",
              str);
      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1, m2);
  }

  public void testFilledToFromString() {
      Markup m1 = new Markup("measure");
      m1.setPlainText("fifty-five amps");
      m1.setParameter("unit", "meter");
      m1.addNestedMarkup(new Markup("cardinal").setParameter("integer", "55"));
      String str = m1.toString();
      assertEquals(
              "type: \"measure\" " +
              "plain_text: \"fifty-five amps\" " +
              "unit: \"meter\" " +
              "markup { type: \"cardinal\" integer: \"55\" }",
              str);

      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1, m2);
  }

  public void testErrorFromString() {
      String str = "type: \"atype\" markup {mistake}";
      try {
          Markup.markupFromString(str);
          Assert.fail("Expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {}
  }

  public void testEscapeQuotes() {
      Markup m1 = new Markup("text")
              .setParameter("something_unknown", "\"this\" is \"a sentence \" with quotes\"");
      String str = m1.toString();
      assertEquals(
              "type: \"text\" " +
              "something_unknown: \"\\\"this\\\" is \\\"a sentence \\\" with quotes\\\"\"",
              str);

      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1.toString(), m2.toString());
      assertEquals(m1, m2);
  }

  public void testEscapeSlashes1() {
      Markup m1 = new Markup("text")
              .setParameter("something_unknown", "\\ \\\\ \t \n \"");
      String str = m1.toString();
      assertEquals(
              "type: \"text\" " +
              "something_unknown: \"\\\\ \\\\\\\\ \t \n \\\"\"",
              str);

      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1.toString(), m2.toString());
      assertEquals(m1, m2);
  }

  public void testEscapeSlashes2() {
      Markup m1 = new Markup("text")
              .setParameter("something_unknown", "\\\"\\\"\\\\\"\"\\\\\\\"\"\"");
      String str = m1.toString();
      assertEquals(
              "type: \"text\" " +
              "something_unknown: \"\\\\\\\"\\\\\\\"\\\\\\\\\\\"\\\"\\\\\\\\\\\\\\\"\\\"\\\"\"",
              str);

      Markup m2 = Markup.markupFromString(str);
      assertEquals(m1.toString(), m2.toString());
      assertEquals(m1, m2);
  }

  public void testBadInput1() {
      String str = "type: \"text\" text: \"\\\"";
      try {
          Markup.markupFromString(str);
          fail("Expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {}
  }

  public void testBadInput2() {
      String str = "type: \"text\" text: \"\\a\"";
      try {
          Markup.markupFromString(str);
          fail("Expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {}
  }

  public void testValidParameterKey() {
      Markup m = new Markup();
      m.setParameter("ke9__yk_88ey_za7_", "test");
  }

  public void testInValidParameterKeyEmpty() {
      Markup m = new Markup();
      try {
          m.setParameter("", "test");
          fail("Expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {}
  }

  public void testInValidParameterKeyDollar() {
      Markup m = new Markup();
      try {
          m.setParameter("ke9y$k88ey7", "test");
          fail("Expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {}
  }

  public void testInValidParameterKeySpace() {
      Markup m = new Markup();
      try {
          m.setParameter("ke9yk88ey7 ", "test");
          fail("Expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {}
  }

  public void testValidType() {
      new Markup("_this_is_1_valid_type_222");
  }

  public void testInValidTypeAmpersand() {
      try {
          new Markup("abcde1234&");
          fail("Expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {}
  }

  public void testInValidTypeSpace() {
      try {
          new Markup(" ");
          fail("Expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {}
  }

  public void testSimpleParcelable() {
      Markup markup = new Markup();

      Parcel parcel = Parcel.obtain();
      markup.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);

      Markup fromParcel = (Markup) Markup.CREATOR.createFromParcel(parcel);

      assertFalse(markup == fromParcel);
      assertEquals(markup, fromParcel);
  }

  public void testTypeParcelable() {
      Markup markup = new Markup("text");

      Parcel parcel = Parcel.obtain();
      markup.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);

      Markup fromParcel = (Markup) Markup.CREATOR.createFromParcel(parcel);

      assertFalse(markup == fromParcel);
      assertEquals(markup, fromParcel);
  }

  public void testPlainTextsParcelable() {
      Markup markup = new Markup();
      markup.setPlainText("plainText");

      Parcel parcel = Parcel.obtain();
      markup.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);

      Markup fromParcel = (Markup) Markup.CREATOR.createFromParcel(parcel);

      assertFalse(markup == fromParcel);
      assertEquals(markup, fromParcel);
  }

  public void testParametersParcelable() {
      Markup markup = new Markup();
      markup.setParameter("key1", "value1");
      markup.setParameter("key2", "value2");
      markup.setParameter("key3", "value3");

      Parcel parcel = Parcel.obtain();
      markup.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);

      Markup fromParcel = (Markup) Markup.CREATOR.createFromParcel(parcel);

      assertFalse(markup == fromParcel);
      assertEquals(markup, fromParcel);
  }

  public void testNestedParcelable() {
      Markup markup = new Markup();
      markup.addNestedMarkup(new Markup("first"));
      markup.addNestedMarkup(new Markup("second"));
      markup.addNestedMarkup(new Markup("third"));

      Parcel parcel = Parcel.obtain();
      markup.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);

      Markup fromParcel = (Markup) Markup.CREATOR.createFromParcel(parcel);

      assertFalse(markup == fromParcel);
      assertEquals(markup, fromParcel);
  }

  public void testAllFieldsParcelable() {
      Markup markup = new Markup("text");
      markup.setPlainText("plain text");
      markup.setParameter("key1", "value1");
      markup.setParameter("key2", "value2");
      markup.setParameter("key3", "value3");
      markup.addNestedMarkup(new Markup("first"));
      markup.addNestedMarkup(new Markup("second"));
      markup.addNestedMarkup(new Markup("third"));

      Parcel parcel = Parcel.obtain();
      markup.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);

      Markup fromParcel = (Markup) Markup.CREATOR.createFromParcel(parcel);

      assertFalse(markup == fromParcel);
      assertEquals(markup, fromParcel);
  }

  public void testKeyCannotBeType() {
      try {
          new Markup().setParameter("type", "vale");
          fail("Expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {}
  }

  public void testKeyCannotBePlainText() {
      try {
          new Markup().setParameter("plain_text", "value");
          fail("Expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {}
  }
}
