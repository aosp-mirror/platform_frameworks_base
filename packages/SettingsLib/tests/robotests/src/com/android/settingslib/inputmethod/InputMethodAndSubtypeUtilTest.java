/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.inputmethod;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.HashSet;

@RunWith(RobolectricTestRunner.class)
public class InputMethodAndSubtypeUtilTest {

    private static final HashSet<String> EMPTY_STRING_SET = new HashSet<>();

    private static HashSet<String> asHashSet(String... strings) {
        HashSet<String> hashSet = new HashSet<>();
        for (String s : strings) {
            hashSet.add(s);
        }
        return hashSet;
    }

    @Test
    public void parseInputMethodsAndSubtypesString_EmptyString() {
        assertThat(InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString("")).isEmpty();
        assertThat(InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString(null)).isEmpty();
    }

    @Test
    public void parseInputMethodsAndSubtypesString_SingleImeNoSubtype() {
        HashMap<String, HashSet<String>> r =
                InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString("ime0");
        assertThat(r).containsExactly("ime0", EMPTY_STRING_SET);
    }

    @Test
    public void parseInputMethodsAndSubtypesString_MultipleImesNoSubtype() {
        HashMap<String, HashSet<String>> r =
                InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString("ime0:ime1");
        assertThat(r).containsExactly("ime0", EMPTY_STRING_SET, "ime1", EMPTY_STRING_SET);
    }

    @Test
    public void parseInputMethodsAndSubtypesString_SingleImeSingleSubtype() {
        HashMap<String, HashSet<String>> r =
                InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString("ime0;subtype0");
        assertThat(r).containsExactly("ime0", asHashSet("subtype0"));
    }

    @Test
    public void parseInputMethodsAndSubtypesString_SingleImeDuplicateSameSubtypes() {
        HashMap<String, HashSet<String>> r =
                InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString(
                        "ime0;subtype0;subtype0");
        assertThat(r).containsExactly("ime0", asHashSet("subtype0"));
    }

    @Test
    public void parseInputMethodsAndSubtypesString_SingleImeMultipleSubtypes() {
        HashMap<String, HashSet<String>> r =
                InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString(
                        "ime0;subtype0;subtype1");
        assertThat(r).containsExactly("ime0", asHashSet("subtype0", "subtype1"));
    }

    @Test
    public void parseInputMethodsAndSubtypesString_MultiplePairsOfImeSubtype() {
        assertThat(InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString(
                "ime0;subtype0:ime1;subtype1"))
                .containsExactly("ime0", asHashSet("subtype0"), "ime1", asHashSet("subtype1"));
        assertThat(InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString(
                "ime0;subtype0;subtype1:ime1;subtype2"))
                .containsExactly("ime0", asHashSet("subtype0", "subtype1"),
                        "ime1", asHashSet("subtype2"));
        assertThat(InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString(
                "ime0;subtype0;subtype1:ime1;subtype1;subtype2"))
                .containsExactly("ime0", asHashSet("subtype0", "subtype1"),
                        "ime1", asHashSet("subtype1", "subtype2"));

    }

    @Test
    public void parseInputMethodsAndSubtypesString_MixedImeSubtypePairsAndImeNoSubtype() {
        HashMap<String, HashSet<String>> r =
                InputMethodAndSubtypeUtil.parseInputMethodsAndSubtypesString(
                        "ime0;subtype0;subtype1:ime1;subtype1;subtype2:ime2");
        assertThat(r).containsExactly("ime0", asHashSet("subtype0", "subtype1"),
                "ime1", asHashSet("subtype1", "subtype2"),
                "ime2", EMPTY_STRING_SET);
    }

    @Test
    public void buildInputMethodsAndSubtypesString_EmptyInput() {
        HashMap<String, HashSet<String>> map = new HashMap<>();
        assertThat(map).isEmpty();
    }

    @Test
    public void buildInputMethodsAndSubtypesString_SingleIme() {
        HashMap<String, HashSet<String>> map = new HashMap<>();
        map.put("ime0", new HashSet<>());
        String result = InputMethodAndSubtypeUtil.buildInputMethodsAndSubtypesString(map);
        assertThat(result).isEqualTo("ime0");
    }

    @Test
    public void buildInputMethodsAndSubtypesString_SingleImeSingleSubtype() {
        HashMap<String, HashSet<String>> map = new HashMap<>();
        map.put("ime0", asHashSet("subtype0"));
        String result = InputMethodAndSubtypeUtil.buildInputMethodsAndSubtypesString(map);
        assertThat(result).isEqualTo("ime0;subtype0");
    }

    @Test
    public void buildInputMethodsAndSubtypesString_SingleImeMultipleSubtypes() {
        HashMap<String, HashSet<String>> map = new HashMap<>();
        map.put("ime0", asHashSet("subtype0", "subtype1"));
        String result = InputMethodAndSubtypeUtil.buildInputMethodsAndSubtypesString(map);

        // We do not expect what order will be used to concatenate items in
        // InputMethodAndSubtypeUtil.buildInputMethodsAndSubtypesString() hence accept all possible
        // permutations here.
        assertThat(result).matches("ime0;subtype0;subtype1|ime0;subtype1;subtype0");
    }

    @Test
    public void buildInputMethodsAndSubtypesString_MultipleImesNoSubtypes() {
        HashMap<String, HashSet<String>> map = new HashMap<>();
        map.put("ime0", EMPTY_STRING_SET);
        map.put("ime1", EMPTY_STRING_SET);
        String result = InputMethodAndSubtypeUtil.buildInputMethodsAndSubtypesString(map);

        // We do not expect what order will be used to concatenate items in
        // InputMethodAndSubtypeUtil.buildInputMethodsAndSubtypesString() hence accept all possible
        // permutations here.
        assertThat(result).matches("ime0:ime1|ime1:ime0");
    }

    @Test
    public void buildInputMethodsAndSubtypesString_MultipleImesWithAndWithoutSubtypes() {
        HashMap<String, HashSet<String>> map = new HashMap<>();
        map.put("ime0", asHashSet("subtype0", "subtype1"));
        map.put("ime1", EMPTY_STRING_SET);
        String result = InputMethodAndSubtypeUtil.buildInputMethodsAndSubtypesString(map);

        // We do not expect what order will be used to concatenate items in
        // InputMethodAndSubtypeUtil.buildInputMethodsAndSubtypesString() hence accept all possible
        // permutations here.
        assertThat(result).matches("ime0;subtype0;subtype1:ime1|ime0;subtype1;subtype0:ime1"
                + "|ime1:ime0;subtype0;subtype1|ime1:ime0;subtype1;subtype0");
    }

    @Test
    public void buildInputMethodsAndSubtypesString_MultipleImesWithSubtypes() {
        HashMap<String, HashSet<String>> map = new HashMap<>();
        map.put("ime0", asHashSet("subtype0", "subtype1"));
        map.put("ime1", asHashSet("subtype2", "subtype3"));
        String result = InputMethodAndSubtypeUtil.buildInputMethodsAndSubtypesString(map);

        // We do not expect what order will be used to concatenate items in
        // InputMethodAndSubtypeUtil.buildInputMethodsAndSubtypesString() hence accept all possible
        // permutations here.
        assertThat(result).matches("ime0;subtype0;subtype1:ime1;subtype2;subtype3"
                + "|ime0;subtype1;subtype0:ime1;subtype2;subtype3"
                + "|ime0;subtype0;subtype1:ime1;subtype3;subtype2"
                + "|ime0;subtype1;subtype0:ime1;subtype3;subtype2"
                + "|ime1;subtype2;subtype3:ime0;subtype0;subtype1"
                + "|ime2;subtype3;subtype2:ime0;subtype0;subtype1"
                + "|ime3;subtype2;subtype3:ime0;subtype1;subtype0"
                + "|ime4;subtype3;subtype2:ime0;subtype1;subtype0");
    }
}
