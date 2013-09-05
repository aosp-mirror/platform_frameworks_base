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

package com.android.layoutlib.bridge.impl;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import android.graphics.Typeface;

import java.awt.Font;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Provides {@link Font} object to the layout lib.
 * <p/>
 * The fonts are loaded from the SDK directory. Family/style mapping is done by parsing the
 * fonts.xml file located alongside the ttf files.
 */
public final class FontLoader {
    private static final String FONTS_SYSTEM = "system_fonts.xml";
    private static final String FONTS_VENDOR = "vendor_fonts.xml";
    private static final String FONTS_FALLBACK = "fallback_fonts.xml";

    private static final String NODE_FAMILYSET = "familyset";
    private static final String NODE_FAMILY = "family";
    private static final String NODE_NAME = "name";
    private static final String NODE_FILE = "file";

    private static final String ATTRIBUTE_VARIANT = "variant";
    private static final String ATTRIBUTE_VALUE_ELEGANT = "elegant";
    private static final String FONT_SUFFIX_NONE = ".ttf";
    private static final String FONT_SUFFIX_REGULAR = "-Regular.ttf";
    private static final String FONT_SUFFIX_BOLD = "-Bold.ttf";
    private static final String FONT_SUFFIX_ITALIC = "-Italic.ttf";
    private static final String FONT_SUFFIX_BOLDITALIC = "-BoldItalic.ttf";

    // This must match the values of Typeface styles so that we can use them for indices in this
    // array.
    private static final int[] AWT_STYLES = new int[] {
        Font.PLAIN,
        Font.BOLD,
        Font.ITALIC,
        Font.BOLD | Font.ITALIC
    };
    private static int[] DERIVE_BOLD_ITALIC = new int[] {
        Typeface.ITALIC, Typeface.BOLD, Typeface.NORMAL
    };
    private static int[] DERIVE_ITALIC = new int[] { Typeface.NORMAL };
    private static int[] DERIVE_BOLD = new int[] { Typeface.NORMAL };

    private static final List<FontInfo> mMainFonts = new ArrayList<FontInfo>();
    private static final List<FontInfo> mFallbackFonts = new ArrayList<FontInfo>();

    private final String mOsFontsLocation;

    public static FontLoader create(String fontOsLocation) {
        try {
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
                parserFactory.setNamespaceAware(true);

            // parse the system fonts
            FontHandler handler = parseFontFile(parserFactory, fontOsLocation, FONTS_SYSTEM);
            List<FontInfo> systemFonts = handler.getFontList();


            // parse the fallback fonts
            handler = parseFontFile(parserFactory, fontOsLocation, FONTS_FALLBACK);
            List<FontInfo> fallbackFonts = handler.getFontList();

            return new FontLoader(fontOsLocation, systemFonts, fallbackFonts);
        } catch (ParserConfigurationException e) {
            // return null below
        } catch (SAXException e) {
            // return null below
        } catch (FileNotFoundException e) {
            // return null below
        } catch (IOException e) {
            // return null below
        }

        return null;
    }

    private static FontHandler parseFontFile(SAXParserFactory parserFactory,
            String fontOsLocation, String fontFileName)
            throws ParserConfigurationException, SAXException, IOException, FileNotFoundException {

        SAXParser parser = parserFactory.newSAXParser();
        File f = new File(fontOsLocation, fontFileName);

        FontHandler definitionParser = new FontHandler(
                fontOsLocation + File.separator);
        parser.parse(new FileInputStream(f), definitionParser);
        return definitionParser;
    }

    private FontLoader(String fontOsLocation,
            List<FontInfo> fontList, List<FontInfo> fallBackList) {
        mOsFontsLocation = fontOsLocation;
        mMainFonts.addAll(fontList);
        mFallbackFonts.addAll(fallBackList);
    }


    public String getOsFontsLocation() {
        return mOsFontsLocation;
    }

    /**
     * Returns a {@link Font} object given a family name and a style value (constant in
     * {@link Typeface}).
     * @param family the family name
     * @param style a 1-item array containing the requested style. Based on the font being read
     *              the actual style may be different. The array contains the actual style after
     *              the method returns.
     * @return the font object or null if no match could be found.
     */
    public synchronized List<Font> getFont(String family, int style) {
        List<Font> result = new ArrayList<Font>();

        if (family == null) {
            return result;
        }


        // get the font objects from the main list based on family.
        for (FontInfo info : mMainFonts) {
            if (info.families.contains(family)) {
                result.add(info.font[style]);
                break;
            }
        }

        // add all the fallback fonts for the given style
        for (FontInfo info : mFallbackFonts) {
            result.add(info.font[style]);
        }

        return result;
    }


    public synchronized List<Font> getFallbackFonts(int style) {
        List<Font> result = new ArrayList<Font>();
        // add all the fallback fonts
        for (FontInfo info : mFallbackFonts) {
            result.add(info.font[style]);
        }
        return result;
    }


    private final static class FontInfo {
        final Font[] font = new Font[4]; // Matches the 4 type-face styles.
        final Set<String> families;

        FontInfo() {
            families = new HashSet<String>();
        }
    }

    private final static class FontHandler extends DefaultHandler {
        private final String mOsFontsLocation;

        private FontInfo mFontInfo = null;
        private final StringBuilder mBuilder = new StringBuilder();
        private List<FontInfo> mFontList = new ArrayList<FontInfo>();
        private boolean isCompactFont = true;

        private FontHandler(String osFontsLocation) {
            super();
            mOsFontsLocation = osFontsLocation;
        }

        public List<FontInfo> getFontList() {
            return mFontList;
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String, java.lang.String, java.lang.String, org.xml.sax.Attributes)
         */
        @Override
        public void startElement(String uri, String localName, String name, Attributes attributes)
                throws SAXException {
            if (NODE_FAMILYSET.equals(localName)) {
                mFontList = new ArrayList<FontInfo>();
            } else if (NODE_FAMILY.equals(localName)) {
                if (mFontList != null) {
                    mFontInfo = null;
                }
            } else if (NODE_NAME.equals(localName)) {
                if (mFontList != null && mFontInfo == null) {
                    mFontInfo = new FontInfo();
                }
            } else if (NODE_FILE.equals(localName)) {
                if (mFontList != null && mFontInfo == null) {
                    mFontInfo = new FontInfo();
                }
                if (ATTRIBUTE_VALUE_ELEGANT.equals(attributes.getValue(ATTRIBUTE_VARIANT))) {
                    isCompactFont = false;
                } else {
                    isCompactFont = true;
                }
            }

            mBuilder.setLength(0);

            super.startElement(uri, localName, name, attributes);
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#characters(char[], int, int)
         */
        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (isCompactFont) {
              mBuilder.append(ch, start, length);
            }
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String, java.lang.String, java.lang.String)
         */
        @Override
        public void endElement(String uri, String localName, String name) throws SAXException {
            if (NODE_FAMILY.equals(localName)) {
                if (mFontInfo != null) {
                    // if has a normal font file, add to the list
                    if (mFontInfo.font[Typeface.NORMAL] != null) {
                        mFontList.add(mFontInfo);

                        // create missing font styles, order is important.
                        if (mFontInfo.font[Typeface.BOLD_ITALIC] == null) {
                            computeDerivedFont(Typeface.BOLD_ITALIC, DERIVE_BOLD_ITALIC);
                        }
                        if (mFontInfo.font[Typeface.ITALIC] == null) {
                            computeDerivedFont(Typeface.ITALIC, DERIVE_ITALIC);
                        }
                        if (mFontInfo.font[Typeface.BOLD] == null) {
                            computeDerivedFont(Typeface.BOLD, DERIVE_BOLD);
                        }
                    }

                    mFontInfo = null;
                }
            } else if (NODE_NAME.equals(localName)) {
                // handle a new name for an existing Font Info
                if (mFontInfo != null) {
                    String family = trimXmlWhitespaces(mBuilder.toString());
                    mFontInfo.families.add(family);
                }
            } else if (NODE_FILE.equals(localName)) {
                // handle a new file for an existing Font Info
                if (isCompactFont && mFontInfo != null) {
                    String fileName = trimXmlWhitespaces(mBuilder.toString());
                    Font font = getFont(fileName);
                    if (font != null) {
                        if (fileName.endsWith(FONT_SUFFIX_REGULAR)) {
                            mFontInfo.font[Typeface.NORMAL] = font;
                        } else if (fileName.endsWith(FONT_SUFFIX_BOLD)) {
                            mFontInfo.font[Typeface.BOLD] = font;
                        } else if (fileName.endsWith(FONT_SUFFIX_ITALIC)) {
                            mFontInfo.font[Typeface.ITALIC] = font;
                        } else if (fileName.endsWith(FONT_SUFFIX_BOLDITALIC)) {
                            mFontInfo.font[Typeface.BOLD_ITALIC] = font;
                        } else if (fileName.endsWith(FONT_SUFFIX_NONE)) {
                            mFontInfo.font[Typeface.NORMAL] = font;
                        }
                    }
                }
            }
        }

        private Font getFont(String fileName) {
            try {
                File file = new File(mOsFontsLocation, fileName);
                if (file.exists()) {
                    return Font.createFont(Font.TRUETYPE_FONT, file);
                }
            } catch (Exception e) {

            }

            return null;
        }

        private void computeDerivedFont( int toCompute, int[] basedOnList) {
            for (int basedOn : basedOnList) {
                if (mFontInfo.font[basedOn] != null) {
                    mFontInfo.font[toCompute] =
                        mFontInfo.font[basedOn].deriveFont(AWT_STYLES[toCompute]);
                    return;
                }
            }

            // we really shouldn't stop there. This means we don't have a NORMAL font...
            assert false;
        }

        private String trimXmlWhitespaces(String value) {
            if (value == null) {
                return null;
            }

            // look for carriage return and replace all whitespace around it by just 1 space.
            int index;

            while ((index = value.indexOf('\n')) != -1) {
                // look for whitespace on each side
                int left = index - 1;
                while (left >= 0) {
                    if (Character.isWhitespace(value.charAt(left))) {
                        left--;
                    } else {
                        break;
                    }
                }

                int right = index + 1;
                int count = value.length();
                while (right < count) {
                    if (Character.isWhitespace(value.charAt(right))) {
                        right++;
                    } else {
                        break;
                    }
                }

                // remove all between left and right (non inclusive) and replace by a single space.
                String leftString = null;
                if (left >= 0) {
                    leftString = value.substring(0, left + 1);
                }
                String rightString = null;
                if (right < count) {
                    rightString = value.substring(right);
                }

                if (leftString != null) {
                    value = leftString;
                    if (rightString != null) {
                        value += " " + rightString;
                    }
                } else {
                    value = rightString != null ? rightString : "";
                }
            }

            // now we un-escape the string
            int length = value.length();
            char[] buffer = value.toCharArray();

            for (int i = 0 ; i < length ; i++) {
                if (buffer[i] == '\\') {
                    if (buffer[i+1] == 'n') {
                        // replace the char with \n
                        buffer[i+1] = '\n';
                    }

                    // offset the rest of the buffer since we go from 2 to 1 char
                    System.arraycopy(buffer, i+1, buffer, i, length - i - 1);
                    length--;
                }
            }

            return new String(buffer, 0, length);
        }

    }
}
