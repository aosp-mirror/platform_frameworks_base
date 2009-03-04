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
 * @author Ilya S. Okomin
 * @version $Revision$
 */
package org.apache.harmony.awt.gl.font;

import java.awt.Font;
import java.awt.peer.FontPeer;
import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.Vector;

import org.apache.harmony.awt.gl.font.FontManager;
import org.apache.harmony.awt.gl.font.FontProperty;
import org.apache.harmony.awt.internal.nls.Messages;

import android.util.Log;

public class AndroidFontManager extends FontManager {

    // set of all available faces supported by a system
    String faces[];

    // weight names according to xlfd structure
    public static final String[] LINUX_WEIGHT_NAMES = {
            "black", "bold", "demibold", "medium", "light" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    // slant names according to xlfd structure
    public static final String[] LINUX_SLANT_NAMES = {
            "i", "o", "r" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };

    /** Singleton AndroidFontManager instance */
    public static final AndroidFontManager inst = new AndroidFontManager();

    private AndroidFontManager() {
        super();
        faces = new String[] {/*"PLAIN",*/ "NORMAL", "BOLD", "ITALIC", "BOLDITALIC"};
        initFontProperties();
    }

    public void initLCIDTable(){
    	throw new RuntimeException("Not implemented!");
    }

    /**
     * Returns temporary File object to store data from InputStream.
     * This File object saved to `~/.fonts/' folder that is included in the 
     * list of folders searched for font files, and this is where user-specific 
     * font files should be installed.
     */
    public File getTempFontFile()throws IOException{
        File fontFile = File.createTempFile("jFont", ".ttf", new File(System.getProperty("user.home") +"/.fonts")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        fontFile.deleteOnExit();

        return fontFile;
    }

    /**
     * Initializes fProperties array field for the current system configuration font
     * property file.
     * 
     * RuntimeException is thrown if font property contains incorrect format of 
     * xlfd string.
     * 
     * @return true is success, false if font property doesn't exist or doesn't
     * contain roperties. 
     */
    public boolean initFontProperties(){
        File fpFile = getFontPropertyFile();
        if (fpFile == null){
            return false;
        }

        Properties props = getProperties(fpFile);
        if (props == null){
            return false;
        }

        for (int i=0; i < LOGICAL_FONT_NAMES.length; i++){
            String lName = LOGICAL_FONT_NAMES[i];
            for (int j=0; j < STYLE_NAMES.length; j++){
                String styleName = STYLE_NAMES[j];
                Vector propsVector = new Vector();

                // Number of entries for a logical font
                int numComp = 0;
                // Is more entries for this style and logical font name left
                boolean moreEntries = true;
                String value = null;

                while(moreEntries){
                    // Component Font Mappings property name
                    String property = FONT_MAPPING_KEYS[0].replaceAll("LogicalFontName", lName).replaceAll("StyleName", styleName).replaceAll("ComponentIndex", String.valueOf(numComp)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    value = props.getProperty(property);

                    // If the StyleName is omitted, it's assumed to be plain
                    if ((j == 0) && (value == null)){
                        property = FONT_MAPPING_KEYS[1].replaceAll("LogicalFontName", lName).replaceAll("ComponentIndex", String.valueOf(numComp)); //$NON-NLS-1$ //$NON-NLS-2$
                        value = props.getProperty(property);
                    }

                    if (value != null){
                        String[] fields = parseXLFD(value);

                        if (fields == null){
                            // awt.08=xfld parse string error: {0}
                            throw new RuntimeException(Messages.getString("awt.08", value)); //$NON-NLS-1$
                        }
                        
                        String fontName = fields[1];
                        String weight = fields[2];
                        String italic = fields[3];

                        int style = getBoldStyle(weight) | getItalicStyle(italic);
                        // Component Font Character Encodings property value
                        String encoding = props.getProperty(FONT_CHARACTER_ENCODING.replaceAll("LogicalFontName", lName).replaceAll("ComponentIndex", String.valueOf(numComp))); //$NON-NLS-1$ //$NON-NLS-2$

                        // Exclusion Ranges property value
                        String exclString = props.getProperty(EXCLUSION_RANGES.replaceAll("LogicalFontName", lName).replaceAll("ComponentIndex", String.valueOf(numComp))); //$NON-NLS-1$ //$NON-NLS-2$
                        int[] exclRange = parseIntervals(exclString);

                        FontProperty fp = new AndroidFontProperty(lName, styleName, null, fontName, value, style, exclRange, encoding);

                        propsVector.add(fp);
                        numComp++;
                    } else {
                        moreEntries = false;
                    }
                }
                fProperties.put(LOGICAL_FONT_NAMES[i] + "." + j, propsVector); //$NON-NLS-1$
            }
        }

        return true;

    }

    /**
     * Returns style according to the xlfd weight string.
     * If weight string is incorrect returned value is Font.PLAIN
     * 
     * @param str weight name String
     */
    private int getBoldStyle(String str){
        for (int i = 0; i < LINUX_WEIGHT_NAMES.length;i++){
            if (str.equalsIgnoreCase(LINUX_WEIGHT_NAMES[i])){
                return (i < 3) ? Font.BOLD : Font.PLAIN;
            }
        }
        return Font.PLAIN;
    }
    
    /**
     * Returns style according to the xlfd slant string.
     * If slant string is incorrect returned value is Font.PLAIN
     * 
     * @param str slant name String
     */
    private int getItalicStyle(String str){
        for (int i = 0; i < LINUX_SLANT_NAMES.length;i++){
            if (str.equalsIgnoreCase(LINUX_SLANT_NAMES[i])){
                return (i < 2) ? Font.ITALIC : Font.PLAIN;
            }
        }
        return Font.PLAIN;
    }

    /**
     * Parse xlfd string and returns array of Strings with separate xlfd 
     * elements.<p>
     * 
     * xlfd format:
     *      -Foundry-Family-Weight-Slant-Width-Style-PixelSize-PointSize-ResX-ResY-Spacing-AvgWidth-Registry-Encoding
     * @param xlfd String parameter in xlfd format
     */
    public static String[] parseXLFD(String xlfd){
        int fieldsCount = 14;
        String fieldsDelim = "-"; //$NON-NLS-1$
        String[] res = new String[fieldsCount];
        if (!xlfd.startsWith(fieldsDelim)){
            return null;
        }

        xlfd = xlfd.substring(1);
        int i=0;
        int pos;
        for (i=0; i < fieldsCount-1; i++){
            pos = xlfd.indexOf(fieldsDelim);
            if (pos != -1){
                res[i] = xlfd.substring(0, pos);
                xlfd = xlfd.substring(pos + 1);
            } else {
                return null;
            }
        }
        pos = xlfd.indexOf(fieldsDelim);

        // check if no fields left
        if(pos != -1){
            return null;
        }
        res[fieldsCount-1] = xlfd;

        return res;
    }

    public int getFaceIndex(String faceName){
    	
        for (int i = 0; i < faces.length; i++) {
            if(faces[i].equals(faceName)){
                return i;
            }
        }
        return -1;
    }

    public String[] getAllFamilies(){
        if (allFamilies == null){
        	allFamilies = new String[]{"sans-serif", "serif", "monospace"};
        }
        return allFamilies;
    }

    public Font[] getAllFonts(){
        Font[] fonts = new Font[faces.length];
        for (int i =0; i < fonts.length;i++){
            fonts[i] = new Font(faces[i], Font.PLAIN, 1);
        }
        return fonts;
    }

    public FontPeer createPhysicalFontPeer(String name, int style, int size) {
        AndroidFont peer;
        int familyIndex = getFamilyIndex(name);
        if (familyIndex != -1){
            // !! we use family names from the list with cached families because 
            // they are differ from the family names in xlfd structure, in xlfd 
            // family names mostly in lower case.
            peer = new AndroidFont(getFamily(familyIndex), style, size);
            peer.setFamily(getFamily(familyIndex));
            return peer;
        }
        int faceIndex = getFaceIndex(name); 
        if (faceIndex != -1){

            peer = new AndroidFont(name, style, size);
            return peer;
        }
        
        return null;
    }

    public FontPeer createDefaultFont(int style, int size) {
    	Log.i("DEFAULT FONT", Integer.toString(style));
        return new AndroidFont(DEFAULT_NAME, style, size);
    }

}
