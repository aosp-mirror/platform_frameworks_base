/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import android.os.Environment;

import android.content.res.AssetManager;
import android.content.res.Resources;

/**
 * @hide
 * @deprecated in API 16
 * <p>This class gives users a simple way to draw hardware accelerated text.
 * Internally, the glyphs are rendered using the Freetype library and an internal cache of
 * rendered glyph bitmaps is maintained. Each font object represents a combination of a typeface,
 * and point size. You can create multiple font objects to represent styles such as bold or italic text,
 * faces, and different font sizes. During creation, the Android system quieries device's screen DPI to
 * ensure proper sizing across multiple device configurations.</p>
 * <p>Fonts are rendered using screen-space positions and no state setup beyond binding a
 * font to the RenderScript is required. A note of caution on performance, though the state changes
 * are transparent to the user, they do happen internally, and it is more efficient to
 * render large batches of text in sequence. It is also more efficient to render multiple
 * characters at once instead of one by one to improve draw call batching.</p>
 * <p>Font color and transparency are not part of the font object and you can freely modify
 * them in the script to suit the user's rendering needs. Font colors work as a state machine.
 * Every new call to draw text uses the last color set in the script.</p>
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

    /**
     * @deprecated in API 16
     */
    public enum Style {
        /**
         * @deprecated in API 16
         */
        NORMAL,
        /**
         * @deprecated in API 16
         */
        BOLD,
        /**
         * @deprecated in API 16
         */
        ITALIC,
        /**
         * @deprecated in API 16
         */
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
        sansFamily.mNormalFileName = "Roboto-Regular.ttf";
        sansFamily.mBoldFileName = "Roboto-Bold.ttf";
        sansFamily.mItalicFileName = "Roboto-Italic.ttf";
        sansFamily.mBoldItalicFileName = "Roboto-BoldItalic.ttf";
        addFamilyToMap(sansFamily);

        FontFamily serifFamily = new FontFamily();
        serifFamily.mNames = sSerifNames;
        serifFamily.mNormalFileName = "NotoSerif-Regular.ttf";
        serifFamily.mBoldFileName = "NotoSerif-Bold.ttf";
        serifFamily.mItalicFileName = "NotoSerif-Italic.ttf";
        serifFamily.mBoldItalicFileName = "NotoSerif-BoldItalic.ttf";
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

    Font(long id, RenderScript rs) {
        super(id, rs);
        guard.open("destroy");
    }

    /**
     * @deprecated in API 16
     * Takes a specific file name as an argument
     */
    static public Font createFromFile(RenderScript rs, Resources res, String path, float pointSize) {
        rs.validate();
        int dpi = res.getDisplayMetrics().densityDpi;
        long fontId = rs.nFontCreateFromFile(path, pointSize, dpi);

        if(fontId == 0) {
            throw new RSRuntimeException("Unable to create font from file " + path);
        }
        Font rsFont = new Font(fontId, rs);

        return rsFont;
    }

    /**
     * @deprecated in API 16
     */
    static public Font createFromFile(RenderScript rs, Resources res, File path, float pointSize) {
        return createFromFile(rs, res, path.getAbsolutePath(), pointSize);
    }

    /**
     * @deprecated in API 16
     */
    static public Font createFromAsset(RenderScript rs, Resources res, String path, float pointSize) {
        rs.validate();
        AssetManager mgr = res.getAssets();
        int dpi = res.getDisplayMetrics().densityDpi;

        long fontId = rs.nFontCreateFromAsset(mgr, path, pointSize, dpi);
        if(fontId == 0) {
            throw new RSRuntimeException("Unable to create font from asset " + path);
        }
        Font rsFont = new Font(fontId, rs);
        return rsFont;
    }

    /**
     * @deprecated in API 16
     */
    static public Font createFromResource(RenderScript rs, Resources res, int id, float pointSize) {
        String name = "R." + Integer.toString(id);

        rs.validate();
        InputStream is = null;
        try {
            is = res.openRawResource(id);
        } catch (Exception e) {
            throw new RSRuntimeException("Unable to open resource " + id);
        }

        int dpi = res.getDisplayMetrics().densityDpi;

        long fontId = 0;
        if (is instanceof AssetManager.AssetInputStream) {
            long asset = ((AssetManager.AssetInputStream) is).getNativeAsset();
            fontId = rs.nFontCreateFromAssetStream(name, pointSize, dpi, asset);
        } else {
            throw new RSRuntimeException("Unsupported asset stream created");
        }

        if(fontId == 0) {
            throw new RSRuntimeException("Unable to create font from resource " + id);
        }
        Font rsFont = new Font(fontId, rs);
        return rsFont;
    }

    /**
     * @deprecated in API 16
     * Accepts one of the following family names as an argument
     * and will attempt to produce the best match with a system font:
     *
     * "sans-serif" "arial" "helvetica" "tahoma" "verdana"
     * "serif" "times" "times new roman" "palatino" "georgia" "baskerville"
     * "goudy" "fantasy" "cursive" "ITC Stone Serif"
     * "monospace" "courier" "courier new" "monaco"
     *
     * Returns default font if no match could be found.
     */
    static public Font create(RenderScript rs, Resources res, String familyName, Style fontStyle, float pointSize) {
        String fileName = getFontFileName(familyName, fontStyle);
        String fontPath = Environment.getRootDirectory().getAbsolutePath();
        fontPath += "/fonts/" + fileName;
        return createFromFile(rs, res, fontPath, pointSize);
    }

}
