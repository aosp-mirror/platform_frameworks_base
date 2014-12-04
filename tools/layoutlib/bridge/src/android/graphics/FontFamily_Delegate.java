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

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.content.res.AssetManager;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

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

    public static final int DEFAULT_FONT_WEIGHT = 400;
    public static final int BOLD_FONT_WEIGHT_DELTA = 300;
    public static final int BOLD_FONT_WEIGHT = 700;

    // FONT_SUFFIX_ITALIC will always match FONT_SUFFIX_BOLDITALIC and hence it must be checked
    // separately.
    private static final String FONT_SUFFIX_ITALIC = "Italic.ttf";
    private static final String FN_ALL_FONTS_LIST = "fontsInSdk.txt";

    /**
     * A class associating {@link Font} with its metadata.
     */
    private static final class FontInfo {
        @Nullable
        Font mFont;
        int mWeight;
        boolean mIsItalic;
    }

    // ---- delegate manager ----
    private static final DelegateManager<FontFamily_Delegate> sManager =
            new DelegateManager<FontFamily_Delegate>(FontFamily_Delegate.class);

    // ---- delegate helper data ----
    private static String sFontLocation;
    private static final List<FontFamily_Delegate> sPostInitDelegate = new
            ArrayList<FontFamily_Delegate>();
    private static Set<String> SDK_FONTS;


    // ---- delegate data ----
    private List<FontInfo> mFonts = new ArrayList<FontInfo>();

    /**
     * The variant of the Font Family - compact or elegant.
     * <p/>
     * 0 is unspecified, 1 is compact and 2 is elegant. This needs to be kept in sync with values in
     * android.graphics.FontFamily
     *
     * @see Paint#setElegantTextHeight(boolean)
     */
    private FontVariant mVariant;
    // List of runnables to process fonts after sFontLoader is initialized.
    private List<Runnable> mPostInitRunnables = new ArrayList<Runnable>();
    /** @see #isValid() */
    private boolean mValid = false;


    // ---- Public helper class ----

    public enum FontVariant {
        // The order needs to be kept in sync with android.graphics.FontFamily.
        NONE, COMPACT, ELEGANT
    }

    // ---- Public Helper methods ----

    public static FontFamily_Delegate getDelegate(long nativeFontFamily) {
        return sManager.getDelegate(nativeFontFamily);
    }

    public static synchronized void setFontLocation(String fontLocation) {
        sFontLocation = fontLocation;
        // init list of bundled fonts.
        File allFonts = new File(fontLocation, FN_ALL_FONTS_LIST);
        // Current number of fonts is 103. Use the next round number to leave scope for more fonts
        // in the future.
        Set<String> allFontsList = new HashSet<String>(128);
        Scanner scanner = null;
        try {
            scanner = new Scanner(allFonts);
            while (scanner.hasNext()) {
                String name = scanner.next();
                // Skip font configuration files.
                if (!name.endsWith(".xml")) {
                    allFontsList.add(name);
                }
            }
        } catch (FileNotFoundException e) {
            Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                    "Unable to load the list of fonts. Try re-installing the SDK Platform from the SDK Manager.",
                    e, null);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }
        SDK_FONTS = Collections.unmodifiableSet(allFontsList);
        for (FontFamily_Delegate fontFamily : sPostInitDelegate) {
            fontFamily.init();
        }
        sPostInitDelegate.clear();
    }

    @Nullable
    public Font getFont(int desiredWeight, boolean isItalic) {
        FontInfo desiredStyle = new FontInfo();
        desiredStyle.mWeight = desiredWeight;
        desiredStyle.mIsItalic = isItalic;
        FontInfo bestFont = null;
        int bestMatch = Integer.MAX_VALUE;
        for (FontInfo font : mFonts) {
            int match = computeMatch(font, desiredStyle);
            if (match < bestMatch) {
                bestMatch = match;
                bestFont = font;
            }
        }
        if (bestFont == null) {
            return null;
        }
        if (bestMatch == 0) {
            return bestFont.mFont;
        }
        // Derive the font as required and add it to the list of Fonts.
        deriveFont(bestFont, desiredStyle);
        addFont(desiredStyle);
        return desiredStyle.mFont;
    }

    public FontVariant getVariant() {
        return mVariant;
    }

    /**
     * Returns if the FontFamily should contain any fonts. If this returns true and
     * {@link #getFont(int, boolean)} returns an empty list, it means that an error occurred while
     * loading the fonts. However, some fonts are deliberately skipped, for example they are not
     * bundled with the SDK. In such a case, this method returns false.
     */
    public boolean isValid() {
        return mValid;
    }

    /*package*/ static Font loadFont(String path) {
        if (path.startsWith(SYSTEM_FONTS) ) {
            String relativePath = path.substring(SYSTEM_FONTS.length());
            File f = new File(sFontLocation, relativePath);

            try {
                return Font.createFont(Font.TRUETYPE_FONT, f);
            } catch (Exception e) {
                if (path.endsWith(".otf") && e instanceof FontFormatException) {
                    // If we aren't able to load an Open Type font, don't log a warning just yet.
                    // We wait for a case where font is being used. Only then we try to log the
                    // warning.
                    return null;
                }
                Bridge.getLog().fidelityWarning(LayoutLog.TAG_BROKEN,
                        String.format("Unable to load font %1$s", relativePath),
                        e, null);
            }
        } else {
            Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                    "Only platform fonts located in " + SYSTEM_FONTS + "can be loaded.",
                    null, null);
        }

        return null;
    }

    @Nullable
    /*package*/ static String getFontLocation() {
        return sFontLocation;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long nCreateFamily(String lang, int variant) {
        // TODO: support lang. This is required for japanese locale.
        FontFamily_Delegate delegate = new FontFamily_Delegate();
        // variant can be 0, 1 or 2.
        assert variant < 3;
        delegate.mVariant = FontVariant.values()[variant];
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
    /*package*/ static boolean nAddFont(long nativeFamily, final String path) {
        final FontFamily_Delegate delegate = getDelegate(nativeFamily);
        if (delegate != null) {
            if (sFontLocation == null) {
                delegate.mPostInitRunnables.add(new Runnable() {
                    @Override
                    public void run() {
                        delegate.addFont(path);
                    }
                });
                return true;
            }
            return delegate.addFont(path);
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nAddFontWeightStyle(long nativeFamily, final String path,
            final int weight, final boolean isItalic) {
        final FontFamily_Delegate delegate = getDelegate(nativeFamily);
        if (delegate != null) {
            if (sFontLocation == null) {
                delegate.mPostInitRunnables.add(new Runnable() {
                    @Override
                    public void run() {
                        delegate.addFont(path, weight, isItalic);
                    }
                });
                return true;
            }
            return delegate.addFont(path, weight, isItalic);
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nAddFontFromAsset(long nativeFamily, AssetManager mgr, String path) {
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Typeface.createFromAsset is not supported.", null, null);
        return false;
    }


    // ---- private helper methods ----

    private void init() {
        for (Runnable postInitRunnable : mPostInitRunnables) {
            postInitRunnable.run();
        }
        mPostInitRunnables = null;
    }

     private boolean addFont(@NonNull String path) {
         return addFont(path, DEFAULT_FONT_WEIGHT, path.endsWith(FONT_SUFFIX_ITALIC));
     }

    private boolean addFont(@NonNull String path, int weight, boolean isItalic) {
        if (path.startsWith(SYSTEM_FONTS) &&
                !SDK_FONTS.contains(path.substring(SYSTEM_FONTS.length()))) {
            return mValid = false;
        }
        // Set valid to true, even if the font fails to load.
        mValid = true;
        Font font = loadFont(path);
        if (font == null) {
            return false;
        }
        FontInfo fontInfo = new FontInfo();
        fontInfo.mFont = font;
        fontInfo.mWeight = weight;
        fontInfo.mIsItalic = isItalic;
        addFont(fontInfo);
        return true;
    }

    private boolean addFont(@NonNull FontInfo fontInfo) {
        int weight = fontInfo.mWeight;
        boolean isItalic = fontInfo.mIsItalic;
        // The list is usually just two fonts big. So iterating over all isn't as bad as it looks.
        // It's biggest for roboto where the size is 12.
        for (FontInfo font : mFonts) {
            if (font.mWeight == weight && font.mIsItalic == isItalic) {
                return false;
            }
        }
        mFonts.add(fontInfo);
        return true;
    }

    /**
     * Compute matching metric between two styles - 0 is an exact match.
     */
    private static int computeMatch(@NonNull FontInfo font1, @NonNull FontInfo font2) {
        int score = Math.abs(font1.mWeight - font2.mWeight);
        if (font1.mIsItalic != font2.mIsItalic) {
            score += 200;
        }
        return score;
    }

    /**
     * Try to derive a font from {@code srcFont} for the style in {@code outFont}.
     * <p/>
     * {@code outFont} is updated to reflect the style of the derived font.
     * @param srcFont the source font
     * @param outFont contains the desired font style. Updated to contain the derived font and
     *                its style
     * @return outFont
     */
    @NonNull
    private FontInfo deriveFont(@NonNull FontInfo srcFont, @NonNull FontInfo outFont) {
        int desiredWeight = outFont.mWeight;
        int srcWeight = srcFont.mWeight;
        Font derivedFont = srcFont.mFont;
        // Embolden the font if required.
        if (desiredWeight >= BOLD_FONT_WEIGHT && desiredWeight - srcWeight > BOLD_FONT_WEIGHT_DELTA / 2) {
            derivedFont = derivedFont.deriveFont(Font.BOLD);
            srcWeight += BOLD_FONT_WEIGHT_DELTA;
        }
        // Italicize the font if required.
        if (outFont.mIsItalic && !srcFont.mIsItalic) {
            derivedFont = derivedFont.deriveFont(Font.ITALIC);
        } else if (outFont.mIsItalic != srcFont.mIsItalic) {
            // The desired font is plain, but the src font is italics. We can't convert it back. So
            // we update the value to reflect the true style of the font we're deriving.
            outFont.mIsItalic = srcFont.mIsItalic;
        }
        outFont.mFont = derivedFont;
        outFont.mWeight = srcWeight;
        // No need to update mIsItalics, as it's already been handled above.
        return outFont;
    }
}
