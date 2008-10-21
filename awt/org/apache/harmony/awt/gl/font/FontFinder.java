/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Oleg V. Khaschansky
 * @version $Revision$
 *
 * @date: Jul 12, 2005
 */

package org.apache.harmony.awt.gl.font;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Map;

/**
 * This class chooses the default font for the given text.
 * If it finds the character which current font is unable to display
 * it starts the next font run and looks for the font which is able to
 * display the current character. It also caches the font mappings
 * (index in the array containing all fonts) for the characters,
 * using that fact that scripts are mainly contiguous in the UTF-16 encoding
 * and there's a high probability that the upper byte will be the same for the
 * next character as for the previous. This allows to save the space used for the cache.
 */
public class FontFinder {
    private static final float DEFAULT_FONT_SIZE = 12;

    private static final Font fonts[] =
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts();

    private static final int NUM_BLOCKS = 256;
    private static final int BLOCK_SIZE = 256;
    private static final int INDEX_MASK = 0xFF;
    private static final int BLOCK_SHIFT = 8;

    // Maps characters into the fonts array
    private static final int blocks[][] = new int[NUM_BLOCKS][];

    /**
     * Finds the font which is able to display the given character
     * and saves the font mapping for this character
     * @param c - character
     * @return font
     */
    static Font findFontForChar(char c) {
        int blockNum = c >> BLOCK_SHIFT;
        int index = c & INDEX_MASK;

        if (blocks[blockNum] == null) {
            blocks[blockNum] = new int[BLOCK_SIZE];
        }

        if (blocks[blockNum][index] == 0) {
            blocks[blockNum][index] = 1;

            for (int i=0; i<fonts.length; i++) {
                if (fonts[i].canDisplay(c)) {
                    blocks[blockNum][index] = i+1;
                    break;
                }
            }
        }

        return getDefaultSizeFont(blocks[blockNum][index]-1);
    }

    /**
     * Derives the default size font
     * @param i - index in the array of all fonts
     * @return derived font
     */
    static Font getDefaultSizeFont(int i) {
        if (fonts[i].getSize() != DEFAULT_FONT_SIZE) {
            fonts[i] = fonts[i].deriveFont(DEFAULT_FONT_SIZE);
        }

        return fonts[i];
    }

    /**
     * Assigns default fonts for the given text run.
     * First three parameters are input, last three are output.
     * @param text - given text
     * @param runStart - start of the text run
     * @param runLimit - end of the text run
     * @param runStarts - starts of the resulting font runs
     * @param fonts - mapping of the font run starts to the fonts
     */
    static void findFonts(char text[], int runStart, int runLimit, List<Integer> runStarts,
            Map<Integer, Font> fonts) {
        Font prevFont = null;
        Font currFont;
        for (int i = runStart; i < runLimit; i++) {
            currFont = findFontForChar(text[i]);
            if (currFont != prevFont) {
                prevFont = currFont;
                Integer idx = new Integer(i);
                fonts.put(idx, currFont);
                if (i != runStart) {
                    runStarts.add(idx);
                }
            }
        }
    }
}
