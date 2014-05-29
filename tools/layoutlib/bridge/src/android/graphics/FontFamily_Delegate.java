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

package android.graphics;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.awt.Font;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static android.graphics.Typeface_Delegate.SYSTEM_FONTS;

/**
 * Delegate implementing the native methods of android.graphics.FontFamily
 *
 * Through the layoutlib_create tool, the original native methods of FontFamily have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original FontFamily class.
 *
 * @see DelegateManager
 */
public class FontFamily_Delegate {

    // FONT_SUFFIX_ITALIC will always match FONT_SUFFIX_BOLDITALIC and hence it must be checked
    // separately.
    private static final String FONT_SUFFIX_BOLDITALIC = "BoldItalic.ttf";
    private static final String FONT_SUFFIX_BOLD = "Bold.ttf";
    private static final String FONT_SUFFIX_ITALIC = "Italic.ttf";
    private static final String FONT_SUBSTRING_COMPACT = "UI";

    /**
     * A class associating {@link Font} with its metadata.
     */
    private static final class FontInfo {
        Font mFont;
        /** Regular, Bold, Italic, or BoldItalic. */
        int mStyle;
        /**
         * The variant of the Font - compact or elegant.
         * @see Paint#setElegantTextHeight(boolean)
         */
        boolean mIsCompact;
    }

    // ---- delegate manager ----
    private static final DelegateManager<FontFamily_Delegate> sManager =
            new DelegateManager<FontFamily_Delegate>(FontFamily_Delegate.class);

    // ---- delegate helper data ----
    private static String sFontLocation;
    private static final List<FontFamily_Delegate> sPostInitDelegate = new
            ArrayList<FontFamily_Delegate>();


    // ---- delegate data ----
    private List<FontInfo> mFonts = new ArrayList<FontInfo>();
    // Path of fonts that haven't been created since sFontLoader hasn't been initialized.
    private List<String> mPath = new ArrayList<String>();


    // ---- Public Helper methods ----

    public static FontFamily_Delegate getDelegate(long nativeFontFamily) {
        return sManager.getDelegate(nativeFontFamily);
    }

    public static synchronized void setFontLocation(String fontLocation) {
        sFontLocation = fontLocation;
        for (FontFamily_Delegate fontFamily : sPostInitDelegate) {
            fontFamily.init();
        }
        sPostInitDelegate.clear();
    }

    public Font getFont(int style, boolean isCompact) {
        FontInfo plainFont = null;
        FontInfo styledFont = null;  // Font matching the style but not isCompact
        for (FontInfo font : mFonts) {
            if (font.mStyle == style) {
                if (font.mIsCompact == isCompact) {
                    return font.mFont;
                }
                styledFont = font;
            }
            if (font.mStyle == Font.PLAIN) {
                if (plainFont == null) {
                    plainFont = font;
                    continue;
                }
                if (font.mIsCompact == isCompact) {
                    // Override the previous selection of plain font since we've found a better one.
                    plainFont = font;
                }
            }
        }
        if (styledFont != null) {
            return styledFont.mFont;
        }

        // No font with the mentioned style is found. Try to derive one.
        if (plainFont != null && style > 0 && style < 4) {
            styledFont = new FontInfo();
            styledFont.mFont = plainFont.mFont.deriveFont(style);
            styledFont.mStyle = style;
            styledFont.mIsCompact = plainFont.mIsCompact;
            // Add the font to the list of fonts so that we don't have to derive it the next time.
            mFonts.add(styledFont);
            return styledFont.mFont;
        }
        return null;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long nCreateFamily() {
        FontFamily_Delegate delegate = new FontFamily_Delegate();
        if (sFontLocation != null) {
            delegate.init();
        } else {
            sPostInitDelegate.add(delegate);
        }
        return sManager.addNewDelegate(delegate);
    }

    @LayoutlibDelegate
    /*package*/ static void nUnrefFamily(long nativePtr) {
        // Removing the java reference for the object doesn't mean that it's freed for garbage
        // collection. Typeface_Delegate may still hold a reference for it.
        sManager.removeJavaReferenceFor(nativePtr);
    }

    @LayoutlibDelegate
    /*package*/ static boolean nAddFont(long nativeFamily, String path) {
        FontFamily_Delegate delegate = getDelegate(nativeFamily);
        if (delegate != null) {
            if (sFontLocation == null) {
                delegate.mPath.add(path);
                return true;
            }
            return delegate.addFont(path);
        }
        return false;
    }

    private void init() {
        for (String path : mPath) {
            addFont(path);
        }
        mPath = null;
    }

    private boolean addFont(String path) {
        Font font = loadFont(path);
        if (font == null) {
            return false;
        }
        FontInfo fontInfo = new FontInfo();
        fontInfo.mFont = font;
        addFontMetadata(fontInfo, path);
        // TODO ensure that mFonts doesn't have the font with this style already.
        mFonts.add(fontInfo);
        return true;
    }

    private static void addFontMetadata(FontInfo fontInfo, String path) {
        int style = Font.PLAIN;
        String fontName = path.substring(path.lastIndexOf('/'), path.length());
        if (fontName.endsWith(FONT_SUFFIX_BOLDITALIC)) {
            style = Font.BOLD | Font.ITALIC;
        } else if (fontName.endsWith(FONT_SUFFIX_BOLD)) {
            style = Font.BOLD;
        } else if (fontName.endsWith(FONT_SUFFIX_ITALIC)) {
            style = Font.ITALIC;
        }
        fontInfo.mStyle = style;

        // Names of compact fonts end with UI-<style>.ttf. For example, NotoNakshUI-Regular.ttf.
        // This should go away when this info is passed on by nAddFont().
        int hyphenIndex = fontName.lastIndexOf('-');
        fontInfo.mIsCompact = hyphenIndex > 0 &&
                fontName.substring(0, hyphenIndex).endsWith(FONT_SUBSTRING_COMPACT);

    }

    private static Font loadFont(String path) {
        if (path.startsWith(SYSTEM_FONTS) ) {
            String relativePath = path.substring(SYSTEM_FONTS.length());
            File f = new File(sFontLocation, relativePath);

            try {
                return Font.createFont(Font.TRUETYPE_FONT, f);
            } catch (Exception e) {
                Bridge.getLog().fidelityWarning(LayoutLog.TAG_BROKEN,
                        String.format("Unable to load font %1$s", relativePath),
                        null /*throwable*/, null /*data*/);
            }
        } else {
            Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                    "Only platform fonts located in " + SYSTEM_FONTS + "can be loaded.",
                    null /*throwable*/, null /*data*/);
        }

        return null;
    }
}
