/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.renderscript;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.HashMap;

import android.content.res.Resources;
import android.content.res.AssetManager;
import android.util.Log;
import android.util.TypedValue;

/**
 * @hide
 *
 **/
public class Font extends BaseObj {

    //These help us create a font by family name
    private static final String[] sSansNames = {
        "sans-serif", "arial", "helvetica", "tahoma", "verdana"
    };

    private static final String[] sSerifNames = {
        "serif", "times", "times new roman", "palatino", "georgia", "baskerville",
        "goudy", "fantasy", "cursive", "ITC Stone Serif"
    };

    private static final String[] sMonoNames = {
        "monospace", "courier", "courier new", "monaco"
    };

    private static class FontFamily {
        String[] mNames;
        String mNormalFileName;
        String mBoldFileName;
        String mItalicFileName;
        String mBoldItalicFileName;
    }

    private static Map<String, FontFamily> sFontFamilyMap;

    public enum Style {
        NORMAL,
        BOLD,
        ITALIC,
        BOLD_ITALIC;
    }

    private static void addFamilyToMap(FontFamily family) {
        for(int i = 0; i < family.mNames.length; i ++) {
            sFontFamilyMap.put(family.mNames[i], family);
        }
    }

    private static void initFontFamilyMap() {
        sFontFamilyMap = new HashMap<String, FontFamily>();

        FontFamily sansFamily = new FontFamily();
        sansFamily.mNames = sSansNames;
        sansFamily.mNormalFileName = "DroidSans.ttf";
        sansFamily.mBoldFileName = "DroidSans-Bold.ttf";
        sansFamily.mItalicFileName = "DroidSans.ttf";
        sansFamily.mBoldItalicFileName = "DroidSans-Bold.ttf";
        addFamilyToMap(sansFamily);

        FontFamily serifFamily = new FontFamily();
        serifFamily.mNames = sSerifNames;
        serifFamily.mNormalFileName = "DroidSerif-Regular.ttf";
        serifFamily.mBoldFileName = "DroidSerif-Bold.ttf";
        serifFamily.mItalicFileName = "DroidSerif-Italic.ttf";
        serifFamily.mBoldItalicFileName = "DroidSerif-BoldItalic.ttf";
        addFamilyToMap(serifFamily);

        FontFamily monoFamily = new FontFamily();
        monoFamily.mNames = sMonoNames;
        monoFamily.mNormalFileName = "DroidSansMono.ttf";
        monoFamily.mBoldFileName = "DroidSansMono.ttf";
        monoFamily.mItalicFileName = "DroidSansMono.ttf";
        monoFamily.mBoldItalicFileName = "DroidSansMono.ttf";
        addFamilyToMap(monoFamily);
    }

    static {
        initFontFamilyMap();
    }

    static String getFontFileName(String familyName, Style style) {
        FontFamily family = sFontFamilyMap.get(familyName);
        if(family != null) {
            switch(style) {
                case NORMAL:
                    return family.mNormalFileName;
                case BOLD:
                    return family.mBoldFileName;
                case ITALIC:
                    return family.mItalicFileName;
                case BOLD_ITALIC:
                    return family.mBoldItalicFileName;
            }
        }
        // Fallback if we could not find the desired family
        return "DroidSans.ttf";
    }

    Font(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Takes a specific file name as an argument
     */
    static public Font create(RenderScript rs, Resources res, String fileName, int size)
        throws IllegalArgumentException {

        rs.validate();
        try {
            int dpi = res.getDisplayMetrics().densityDpi;
            int fontId = rs.nFontCreateFromFile(fileName, size, dpi);

            if(fontId == 0) {
                throw new IllegalStateException("Failed loading a font");
            }
            Font rsFont = new Font(fontId, rs);

            return rsFont;

        } catch (Exception e) {
            // Ignore
        }

        return null;
    }

    /**
     * Accepts one of the following family names as an argument
     * and will attemp to produce the best match with a system font
     * "sans-serif" "arial" "helvetica" "tahoma" "verdana"
     * "serif" "times" "times new roman" "palatino" "georgia" "baskerville"
     * "goudy" "fantasy" "cursive" "ITC Stone Serif"
     * "monospace" "courier" "courier new" "monaco"
     * Returns default font if no match could be found
     */
    static public Font createFromFamily(RenderScript rs, Resources res, String familyName, Style fontStyle, int size)
    throws IllegalArgumentException {
        String fileName = getFontFileName(familyName, fontStyle);
        return create(rs, res, fileName, size);
    }
}
