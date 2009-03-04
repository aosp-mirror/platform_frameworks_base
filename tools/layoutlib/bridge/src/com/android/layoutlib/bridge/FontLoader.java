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

package com.android.layoutlib.bridge;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import android.graphics.Typeface;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final String FONTS_DEFINITIONS = "fonts.xml";
    
    private static final String NODE_FONTS = "fonts";
    private static final String NODE_FONT = "font";
    private static final String NODE_NAME = "name";
    
    private static final String ATTR_TTF = "ttf";

    private static final String[] NODE_LEVEL = { NODE_FONTS, NODE_FONT, NODE_NAME };

    private static final String FONT_EXT = ".ttf";

    private static final String[] FONT_STYLE_DEFAULT = { "", "-Regular" };
    private static final String[] FONT_STYLE_BOLD = { "-Bold" };
    private static final String[] FONT_STYLE_ITALIC = { "-Italic" };
    private static final String[] FONT_STYLE_BOLDITALIC = { "-BoldItalic" };
    
    // list of font style, in the order matching the Typeface Font style
    private static final String[][] FONT_STYLES = {
        FONT_STYLE_DEFAULT,
        FONT_STYLE_BOLD,
        FONT_STYLE_ITALIC,
        FONT_STYLE_BOLDITALIC
    };
    
    private final Map<String, String> mFamilyToTtf = new HashMap<String, String>();
    private final Map<String, Map<Integer, Font>> mTtfToFontMap =
        new HashMap<String, Map<Integer, Font>>();
    
    public static FontLoader create(String fontOsLocation) {
        try {
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
                parserFactory.setNamespaceAware(true);
    
            SAXParser parser = parserFactory.newSAXParser();
            File f = new File(fontOsLocation + File.separator + FONTS_DEFINITIONS);
            
            FontDefinitionParser definitionParser = new FontDefinitionParser(
                    fontOsLocation + File.separator);
            parser.parse(new FileInputStream(f), definitionParser);
            
            return definitionParser.getFontLoader();
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

    private FontLoader(List<FontInfo> fontList) {
        for (FontInfo info : fontList) {
            for (String family : info.families) {
                mFamilyToTtf.put(family, info.ttf);
            }
        }
    }

    public synchronized Font getFont(String family, int[] style) {
        if (family == null) {
            return null;
        }

        // get the ttf name from the family
        String ttf = mFamilyToTtf.get(family);
        
        if (ttf == null) {
            return null;
        }
        
        // get the font from the ttf
        Map<Integer, Font> styleMap = mTtfToFontMap.get(ttf);
        
        if (styleMap == null) {
            styleMap = new HashMap<Integer, Font>();
            mTtfToFontMap.put(ttf, styleMap);
        }
        
        Font f = styleMap.get(style);
        
        if (f != null) {
            return f;
        }
        
        // if it doesn't exist, we create it, and we can't, we try with a simpler style
        switch (style[0]) {
            case Typeface.NORMAL:
                f = getFont(ttf, FONT_STYLES[Typeface.NORMAL]);
                break;
            case Typeface.BOLD:
            case Typeface.ITALIC:
                f = getFont(ttf, FONT_STYLES[style[0]]);
                if (f == null) {
                    f = getFont(ttf, FONT_STYLES[Typeface.NORMAL]);
                    style[0] = Typeface.NORMAL;
                }
                break;
            case Typeface.BOLD_ITALIC:
                f = getFont(ttf, FONT_STYLES[style[0]]);
                if (f == null) {
                    f = getFont(ttf, FONT_STYLES[Typeface.BOLD]);
                    if (f != null) {
                        style[0] = Typeface.BOLD;
                    } else {
                        f = getFont(ttf, FONT_STYLES[Typeface.ITALIC]);
                        if (f != null) {
                            style[0] = Typeface.ITALIC;
                        } else {
                            f = getFont(ttf, FONT_STYLES[Typeface.NORMAL]);
                            style[0] = Typeface.NORMAL;
                        }
                    }
                }
                break;
        }

        if (f != null) {
            styleMap.put(style[0], f);
            return f;
        }

        return null;
    }

    private Font getFont(String ttf, String[] fontFileSuffix) {
        for (String suffix : fontFileSuffix) {
            String name = ttf + suffix + FONT_EXT;
            
            File f = new File(name);
            if (f.isFile()) {
                try {
                    Font font = Font.createFont(Font.TRUETYPE_FONT, f);
                    if (font != null) {
                        return font;
                    }
                } catch (FontFormatException e) {
                    // skip this font name
                } catch (IOException e) {
                    // skip this font name
                }
            }
        }
        
        return null;
    }

    private final static class FontInfo {
        String ttf;
        final Set<String> families;
        
        FontInfo() {
            families = new HashSet<String>();
        }
    }

    private final static class FontDefinitionParser extends DefaultHandler {
        private final String mOsFontsLocation;
        
        private int mDepth = 0;
        private FontInfo mFontInfo = null;
        private final StringBuilder mBuilder = new StringBuilder();
        private final List<FontInfo> mFontList = new ArrayList<FontInfo>();
        
        private FontDefinitionParser(String osFontsLocation) {
            super();
            mOsFontsLocation = osFontsLocation;
        }
        
        FontLoader getFontLoader() {
            return new FontLoader(mFontList);
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String, java.lang.String, java.lang.String, org.xml.sax.Attributes)
         */
        @Override
        public void startElement(String uri, String localName, String name, Attributes attributes)
                throws SAXException {
            if (localName.equals(NODE_LEVEL[mDepth])) {
                mDepth++;
                
                if (mDepth == 2) { // font level.
                    String ttf = attributes.getValue(ATTR_TTF);
                    if (ttf != null) {
                        mFontInfo = new FontInfo();
                        mFontInfo.ttf = mOsFontsLocation + ttf;
                        mFontList.add(mFontInfo);
                    }
                }
            }

            super.startElement(uri, localName, name, attributes);
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#characters(char[], int, int)
         */
        @SuppressWarnings("unused")
        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (mFontInfo != null) {
                mBuilder.append(ch, start, length);
            }
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String, java.lang.String, java.lang.String)
         */
        @SuppressWarnings("unused")
        @Override
        public void endElement(String uri, String localName, String name) throws SAXException {
            if (localName.equals(NODE_LEVEL[mDepth-1])) {
                mDepth--;
                if (mDepth == 2) { // end of a <name> node
                    if (mFontInfo != null) {
                        String family = trimXmlWhitespaces(mBuilder.toString());
                        mFontInfo.families.add(family);
                        mBuilder.setLength(0);
                    }
                } else if (mDepth == 1) { // end of a <font> node
                    mFontInfo = null;
                }
            }
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
