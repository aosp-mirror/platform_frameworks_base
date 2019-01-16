/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.text;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Quick check of native bidi implementation.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class StaticLayoutBidiTest {

    public static final int REQ_DL = 2; // Layout.DIR_REQUEST_DEFAULT_LTR;
    public static final int REQ_DR = -2; // Layout.DIR_REQUEST_DEFAULT_RTL;
    public static final int REQ_L = 1; // Layout.DIR_REQUEST_LTR;
    public static final int REQ_R = -1; // Layout.DIR_REQUEST_RTL;
    public static final int L = Layout.DIR_LEFT_TO_RIGHT;
    public static final int R = Layout.DIR_RIGHT_TO_LEFT;

    public static final String SP = " ";
    public static final String ALEF = "\u05d0";
    public static final String BET = "\u05d1";
    public static final String GIMEL = "\u05d2";
    public static final String DALET = "\u05d3";

    @Test
    public void testAllLtr() {
        expectNativeBidi(REQ_DL, "a test", "000000", L);
    }

    @Test
    public void testLtrRtl() {
        expectNativeBidi(REQ_DL, "abc " + ALEF + BET + GIMEL, "0000111", L);
    }

    @Test
    public void testAllRtl() {
        expectNativeBidi(REQ_DL, ALEF + SP + ALEF + BET + GIMEL + DALET, "111111", R);
    }

    @Test
    public void testRtlLtr() {
        expectNativeBidi(REQ_DL,  ALEF + BET + GIMEL + " abc", "1111222", R);
    }

    @Test
    public void testRAllLtr() {
        expectNativeBidi(REQ_R, "a test", "222222", R);
    }

    @Test
    public void testRLtrRtl() {
        expectNativeBidi(REQ_R, "abc " + ALEF + BET + GIMEL, "2221111", R);
    }

    @Test
    public void testLAllRtl() {
        expectNativeBidi(REQ_L, ALEF + SP + ALEF + BET + GIMEL + DALET, "111111", L);
    }

    @Test
    public void testLRtlLtr() {
        expectNativeBidi(REQ_DL,  ALEF + BET + GIMEL + " abc", "1111222", R);
    }

    @Test
    public void testNativeBidi() {
        expectNativeBidi(REQ_L,  ALEF + BET + GIMEL + " abc", "1110000", L);
    }

    private void expectNativeBidi(int dir, String text,
            String expectedLevels, int expectedDir) {
        char[] chs = text.toCharArray();
        int n = chs.length;
        byte[] chInfo = new byte[n];

        int resultDir = AndroidBidi.bidi(dir, chs, chInfo);

        {
            StringBuilder sb = new StringBuilder("info:");
            for (int i = 0; i < n; ++i) {
                sb.append(" ").append(String.valueOf(chInfo[i]));
            }
            Log.i("BIDI", sb.toString());
        }

        char[] resultLevelChars = new char[n];
        for (int i = 0; i < n; ++i) {
            resultLevelChars[i] = (char)('0' + chInfo[i]);
        }
        String resultLevels = new String(resultLevelChars);
        assertEquals("direction", expectedDir, resultDir);
        assertEquals("levels", expectedLevels, resultLevels);
    }
}
