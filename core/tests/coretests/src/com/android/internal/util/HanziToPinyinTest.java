/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.util;

import java.text.Collator;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Locale;

import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.internal.util.HanziToPinyin;
import com.android.internal.util.HanziToPinyin.Token;

import junit.framework.TestCase;

public class HanziToPinyinTest extends TestCase {
    private final static String ONE_HANZI = "\u675C";
    private final static String TWO_HANZI = "\u675C\u9D51";
    private final static String ASSIC = "test";
    private final static String ONE_UNKNOWN = "\uFF71";
    private final static String MISC = "test\u675C   Test with space\uFF71\uFF71\u675C";

    @SmallTest
    public void testGetToken() throws Exception {
        if (!Arrays.asList(Collator.getAvailableLocales()).contains(Locale.CHINA)) {
            return;
        }
        ArrayList<Token> tokens = HanziToPinyin.getInstance().get(ONE_HANZI);
        assertEquals(tokens.size(), 1);
        assertEquals(tokens.get(0).type, Token.PINYIN);
        assertTrue(tokens.get(0).target.equalsIgnoreCase("DU"));

        tokens = HanziToPinyin.getInstance().get(TWO_HANZI);
        assertEquals(tokens.size(), 2);
        assertEquals(tokens.get(0).type, Token.PINYIN);
        assertEquals(tokens.get(1).type, Token.PINYIN);
        assertTrue(tokens.get(0).target.equalsIgnoreCase("DU"));
        assertTrue(tokens.get(1).target.equalsIgnoreCase("JUAN"));

        tokens = HanziToPinyin.getInstance().get(ASSIC);
        assertEquals(tokens.size(), 1);
        assertEquals(tokens.get(0).type, Token.LATIN);

        tokens = HanziToPinyin.getInstance().get(ONE_UNKNOWN);
        assertEquals(tokens.size(), 1);
        assertEquals(tokens.get(0).type, Token.UNKNOWN);

        tokens = HanziToPinyin.getInstance().get(MISC);
        assertEquals(tokens.size(), 7);
        assertEquals(tokens.get(0).type, Token.LATIN);
        assertEquals(tokens.get(1).type, Token.PINYIN);
        assertEquals(tokens.get(2).type, Token.LATIN);
        assertEquals(tokens.get(3).type, Token.LATIN);
        assertEquals(tokens.get(4).type, Token.LATIN);
        assertEquals(tokens.get(5).type, Token.UNKNOWN);
        assertEquals(tokens.get(6).type, Token.PINYIN);
    }
}
