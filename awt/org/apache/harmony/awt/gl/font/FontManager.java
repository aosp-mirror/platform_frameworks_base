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
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Properties;
import java.util.Vector;

import org.apache.harmony.awt.gl.CommonGraphics2DFactory;
import org.apache.harmony.luni.util.NotImplementedException;


public abstract class FontManager {
    
    //???AWT
    boolean NOT_IMP = false;
    
    /**
     * array of font families names
     */
    public String[] allFamilies;

    public static final String DEFAULT_NAME = "Default"; /* Default font name */ //$NON-NLS-1$
    public static final String DIALOG_NAME = "Dialog";  /* Dialog font name */ //$NON-NLS-1$

    /**
     * Set of constants applicable to the TrueType 'name' table.
     */
    public static final byte  FAMILY_NAME_ID  = 1;      /* Family name identifier   */
    public static final byte  FONT_NAME_ID  = 4;        /* Full font name identifier    */
    public static final byte  POSTSCRIPT_NAME_ID = 6;   /* PostScript name identifier   */
    public static final short ENGLISH_LANGID = 0x0409;  /* English (United States)language identifier   */

    /**
     * Set of constants describing font type.
     */
    public static final byte  FONT_TYPE_TT  = 4;        /* TrueType type (TRUETYPE_FONTTYPE)    */
    public static final byte  FONT_TYPE_T1  = 2;        /* Type1 type    (DEVICE_FONTTYPE)      */
    public static final byte  FONT_TYPE_UNDEF  = 0;     /* Undefined type                       */

    // logical family types (indices in FontManager.LOGICAL_FONT_NAMES)
    static final int DIALOG = 3;        // FF_SWISS
    static final int SANSSERIF = 1;     // FF_SWISS
    static final int DIALOGINPUT = 4;   // FF_MODERN
    static final int MONOSPACED = 2;    // FF_MODERN
    static final int SERIF = 0;         // FF_ROMAN


    /**
     * FontProperty related constants. 
     */
    public static final String PLATFORM_FONT_NAME = "PlatformFontName"; //$NON-NLS-1$
    public static final String LOGICAL_FONT_NAME = "LogicalFontName"; //$NON-NLS-1$
    public static final String COMPONENT_INDEX = "ComponentIndex"; //$NON-NLS-1$
    public static final String STYLE_INDEX = "StyleIndex"; //$NON-NLS-1$

    public static final String[] FONT_MAPPING_KEYS = {
            "LogicalFontName.StyleName.ComponentIndex", "LogicalFontName.ComponentIndex" //$NON-NLS-1$ //$NON-NLS-2$
    };

    public static final String FONT_CHARACTER_ENCODING = "fontcharset.LogicalFontName.ComponentIndex"; //$NON-NLS-1$

    public static final String EXCLUSION_RANGES = "exclusion.LogicalFontName.ComponentIndex"; //$NON-NLS-1$

    public static final String FONT_FILE_NAME = "filename.PlatformFontName"; //$NON-NLS-1$

    /**
     * Available logical font families names.
     */
    public static final String[] LOGICAL_FONT_FAMILIES = {
            "Serif", "SansSerif", "Monospaced", "Dialog", "DialogInput" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    /**
     * Available logical font names.
     */
    public static final String[] LOGICAL_FONT_NAMES = {
            "serif", "serif.plain", "serif.bold", "serif.italic", "serif.bolditalic", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "sansserif", "sansserif.plain", "sansserif.bold", "sansserif.italic", "sansserif.bolditalic", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "monospaced", "monospaced.plain", "monospaced.bold", "monospaced.italic", "monospaced.bolditalic", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "dialog", "dialog.plain", "dialog.bold", "dialog.italic", "dialog.bolditalic", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "dialoginput", "dialoginput.plain", "dialoginput.bold", "dialoginput.italic", "dialoginput.bolditalic" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    /**
     * Available logical font face names.
     */
    public static final String[] LOGICAL_FONT_FACES = {
            "Serif", "Serif.plain", "Serif.bold", "Serif.italic", "Serif.bolditalic", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "Sansserif", "Sansserif.plain", "Sansserif.bold", "Sansserif.italic", "Sansserif.bolditalic", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "Monospaced", "Monospaced.plain", "Monospaced.bold", "Monospaced.italic", "Monospaced.bolditalic", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "Dialog", "Dialog.plain", "Dialog.bold", "Dialog.italic", "Dialog.bolditalic", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "Dialoginput", "Dialoginput.plain", "Dialoginput.bold", "Dialoginput.italic", "Dialoginput.bolditalic" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    /**
     * Set of font style names.
     * Font.getStyle() corresponds to indexes in STYLE_NAMES array.
     */
    public static final String[] STYLE_NAMES = {
            "plain", "bold", "italic", "bolditalic" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    };

    /**
     * Logical font styles names table where font styles names used 
     * as the key and the value is the index of this style name.
     */
    private static final Hashtable<String, Integer> style_keys = new Hashtable<String, Integer>(4);

    /**
     * Initialize font styles keys table.
     */
    static {
        for (int i = 0; i < STYLE_NAMES.length; i++){
            style_keys.put(STYLE_NAMES[i], Integer.valueOf(i));
        }
    }

    /**
     * Return font style from the logical style name.
     * 
     * @param lName style name of the logical face
     */
    public static int getLogicalStyle(String lName){
        Integer value = style_keys.get(lName);
        return value != null ? value.intValue(): -1;
    }

    /**
     * Set of possible "os" property values.
     */
    public static final String[] OS_VALUES = {
            "NT", "98", "2000", "Me", "XP", // For Windows //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "Redhat", "Turbo", "SuSE"       // For Linux //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };

    /**
     * Set of possible font.property file names.
     * Language, Country, Encoding, OS, Version should be replaced with
     * the values from current configuration.
     */
    public static final String[] FP_FILE_NAMES = {
            "/lib/font.properties.Language_Country_Encoding.OSVersion", //$NON-NLS-1$
            "/lib/font.properties.Language_Country_Encoding.OS", //$NON-NLS-1$
            "/lib/font.properties.Language_Country_Encoding.Version", //$NON-NLS-1$
            "/lib/font.properties.Language_Country_Encoding", //$NON-NLS-1$
            "/lib/font.properties.Language_Country.OSVersion", //$NON-NLS-1$
            "/lib/font.properties.Language_Country.OS", //$NON-NLS-1$
            "/lib/font.properties.Language_Country.Version", //$NON-NLS-1$
            "/lib/font.properties.Language_Country", //$NON-NLS-1$
            "/lib/font.properties.Language_Encoding.OSVersion", //$NON-NLS-1$
            "/lib/font.properties.Language_Encoding.OS", //$NON-NLS-1$
            "/lib/font.properties.Language_Encoding.Version", //$NON-NLS-1$
            "/lib/font.properties.Language_Encoding", //$NON-NLS-1$
            "/lib/font.properties.Language.OSVersion", //$NON-NLS-1$
            "/lib/font.properties.Language.OS", //$NON-NLS-1$
            "/lib/font.properties.Language.Version", //$NON-NLS-1$
            "/lib/font.properties.Language", //$NON-NLS-1$
            "/lib/font.properties.Encoding.OSVersion", //$NON-NLS-1$
            "/lib/font.properties.Encoding.OS", //$NON-NLS-1$
            "/lib/font.properties.Encoding.Version", //$NON-NLS-1$
            "/lib/font.properties.Encoding", //$NON-NLS-1$
            "/lib/font.properties.OSVersion", //$NON-NLS-1$
            "/lib/font.properties.OS", //$NON-NLS-1$
            "/lib/font.properties.Version", //$NON-NLS-1$
            "/lib/font.properties" //$NON-NLS-1$
    };

    /**
     * Table with all available font properties corresponding
     * to the current system configuration.
     */
    public Hashtable<String, Vector<FontProperty>> fProperties = new Hashtable<String, Vector<FontProperty>>();
    
    public FontManager(){
        allFamilies = getAllFamilies();
        /*
         * Creating and registering shutdown hook to free resources
         * before object is destroyed.
         */
        //???AWT
        //DisposeNativeHook shutdownHook = new DisposeNativeHook();
        //Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Maximum number of unreferenced font peers to keep.
     */
    public static final int EMPTY_FONTS_CAPACITY = 10;

    /**
     * Locale - Language ID hash table.
     */
    Hashtable<String, Short> tableLCID = new Hashtable<String, Short>();

    /**
     * Hash table that contains FontPeers instances.
     */
    public Hashtable<String, HashMapReference> fontsTable = new Hashtable<String, HashMapReference>();
    
    /**
     * ReferenceQueue for HashMapReference objects to check
     * if they were collected by garbage collector. 
     */
    public ReferenceQueue<FontPeer> queue = new ReferenceQueue<FontPeer>();

    /**
     * Singleton instance
     */
    public final static FontManager inst = CommonGraphics2DFactory.inst.getFontManager();

    /**
     * Gets singleton instance of FontManager
     * 
     * @return instance of FontManager implementation
     */
    public static FontManager getInstance() {
        return inst;
    }

    /**
     * Returns platform-dependent Font peer created from the specified 
     * Font object from the table with cached FontPeers instances.
     * 
     * Note, this method checks whether FontPeer with specified parameters 
     * exists in the table with cached FontPeers' instances. If there is no needed 
     * instance - it is created and cached.
     * 
     * @param fontName name of the font 
     * @param _fontStyle style of the font 
     * @param size font size
     * 
     * @return platform dependent FontPeer implementation created from 
     * the specified parameters
     */
    public FontPeer getFontPeer(String fontName, int _fontStyle, int size) {
        updateFontsTable();
        
        FontPeer peer = null;
        String key; 
        String name;
        int fontStyle = _fontStyle;
        
        int logicalIndex = getLogicalFaceIndex(fontName);
        
        if (logicalIndex != -1){
            name = getLogicalFaceFromFont(fontStyle, logicalIndex);
            fontStyle = getStyleFromLogicalFace(name);
            key = name.concat(String.valueOf(size));
        } else {
            name = fontName;
            key = name.concat(String.valueOf(fontStyle)).
                    concat(String.valueOf(size));
        }
        
        HashMapReference hmr   = fontsTable.get(key);
        if (hmr != null) {
            peer = hmr.get();
        }

        if (peer == null) {
            peer = createFontPeer(name, fontStyle, size, logicalIndex);
            if (peer == null){
                peer = getFontPeer(DIALOG_NAME, fontStyle, size);
            }
            fontsTable.put(key, new HashMapReference(key, peer, queue));
        }

        return peer;
    }
    
    /**
     * Returns instance of font peer (logical or physical) according to the 
     * specified parameters.
     * 
     * @param name font face name
     * @param style style of the font
     * @param size size of the font
     * @param logicalIndex index of the logical face name in LOGICAL_FONT_FACES 
     * array or -1 if desired font peer is not logical.
     */
    private FontPeer createFontPeer(String name, int style, int size, int logicalIndex){
        FontPeer peer;
        if (logicalIndex != -1){
            peer = createLogicalFontPeer(name, style, size);
        }else {
            peer = createPhysicalFontPeer(name, style, size);
        }
        
        return peer;
    }
    
    /**
     * Returns family name for logical face names as a parameter.
     * 
     * @param faceName logical font face name
     */
    public String getFamilyFromLogicalFace(String faceName){
        int pos = faceName.indexOf("."); //$NON-NLS-1$
        if (pos == -1){
            return faceName;
        }
            
        return faceName.substring(0, pos);
    }
            
    /**
     * Returns new logical font peer for the parameters specified using font 
     * properties.
     * 
     * @param faceName face name of the logical font 
     * @param style style of the font 
     * @param size font size
     * 
     */
    private FontPeer createLogicalFontPeer(String faceName, int style, int size){
        String family = getFamilyFromLogicalFace(faceName);
        FontProperty[] fps = getFontProperties(family.toLowerCase() + "." + style); //$NON-NLS-1$
        if (fps != null){
            int numFonts = fps.length;
            FontPeerImpl[] physicalFonts = new FontPeerImpl[numFonts];
            for (int i = 0; i < numFonts; i++){
                FontProperty fp = fps[i];
                
                String name = fp.getName();
                int fpStyle = fp.getStyle();
                String key = name.concat(String.valueOf(fpStyle)).
                    concat(String.valueOf(size));
                
                HashMapReference hmr   = fontsTable.get(key);
                if (hmr != null) {
                    physicalFonts[i] = (FontPeerImpl)hmr.get();
                }

                if (physicalFonts[i] == null){
                    physicalFonts[i] = (FontPeerImpl)createPhysicalFontPeer(name, fpStyle, size);
                    fontsTable.put(key, new HashMapReference(key, physicalFonts[i], queue));
                }

                if (physicalFonts[i] == null){
                    physicalFonts[i] = (FontPeerImpl)getDefaultFont(style, size);
                }
            }
            return new CompositeFont(family, faceName, style, size, fps, physicalFonts); 
        }
        
        // if there is no property for this logical font - default font is to be
        // created
        FontPeerImpl peer = (FontPeerImpl)getDefaultFont(style, size);
        
        return peer;
    }

    /**
     * Returns new physical font peer for the parameters specified using font properties
     * This method must be overridden by subclasses implementations.
     *  
     * @param faceName face name or family name of the font 
     * @param style style of the font 
     * @param size font size
     * 
     */
    public abstract FontPeer createPhysicalFontPeer(String name, int style, int size);
    
    /**
     * Returns default font peer class with "Default" name that is usually 
     * used when font with specified font names and style doesn't exsist 
     * on a system. 
     * 
     * @param style style of the font
     * @param size size of the font
     */
    public FontPeer getDefaultFont(int style, int size){
        updateFontsTable();
        
        FontPeer peer = null;
        String key = DEFAULT_NAME.concat(String.valueOf(style)).
                    concat(String.valueOf(size));
        
        HashMapReference hmr   = fontsTable.get(key);
        if (hmr != null) {
            peer = hmr.get();
        }

        if (peer == null) {
            peer = createDefaultFont(style, size);
            
            ((FontPeerImpl)peer).setFamily(DEFAULT_NAME);
            ((FontPeerImpl)peer).setPSName(DEFAULT_NAME);
            ((FontPeerImpl)peer).setFontName(DEFAULT_NAME);

            fontsTable.put(key, new HashMapReference(key, peer, queue));
        }

        return peer;
    }
    
    /**
     * 
     * Returns new default font peer with "Default" name for the parameters 
     * specified. This method must be overridden by subclasses implementations.
     *  
     * @param style style of the font
     * @param size size of the font
     */
    public abstract FontPeer createDefaultFont(int style, int size);
    
    /**
     * Returns face name of the logical font, which is the result
     * of specified font style and face style union.   
     * 
     * @param fontStyle specified style of the font
     * @param logicalIndex index of the specified face from the 
     * LOGICAL_FONT_FACES array
     * @return resulting face name
     */
    public String getLogicalFaceFromFont(int fontStyle, int logicalIndex){
        int style = 0;
        String name = LOGICAL_FONT_FACES[logicalIndex];
        int pos = name.indexOf("."); //$NON-NLS-1$
        
        if (pos == -1){
            return createLogicalFace(name, fontStyle);
        }
        
        String styleName = name.substring(pos+1);
        name = name.substring(0, pos);
        
        // appending font style to the face style
        style = fontStyle | getLogicalStyle(styleName);
        
        return createLogicalFace(name, style);
    }
    
    /**
     * Function returns style value from logical face name.
     *  
     * @param name face name
     * @return font style
     */
    public int getStyleFromLogicalFace(String name){
        int style;
        int pos = name.indexOf("."); //$NON-NLS-1$
        
        if (pos == -1){
            return Font.PLAIN;
        }
        
        String styleName = name.substring(pos+1);
        
        style = getLogicalStyle(styleName);
        
        return style;
    }

    /**
     * Returns logical face name corresponding to the logical
     * family name and style of the font.
     * 
     * @param family font family
     * @param styleIndex index of the style name from the STYLE_NAMES array 
     */
    public String createLogicalFace(String family, int styleIndex){
        return family + "." + STYLE_NAMES[styleIndex]; //$NON-NLS-1$
    }
    
    /**
     * Return language Id from LCID hash corresponding to the specified locale
     * 
     * @param l specified locale
     */
    public Short getLCID(Locale l){
        if (this.tableLCID.size() == 0){
            initLCIDTable();
        }

        return tableLCID.get(l.toString());
    }

    /**
     * Platform-dependent LCID table init.
     */
    public abstract void initLCIDTable();

    /**
     * Freeing native resources. This hook is used to avoid 
     * sudden application exit and to free resources created in native code.
     */
    private class DisposeNativeHook extends Thread {

        @Override
        public void run() {
            try{
                /* Disposing native font peer's resources */
                Enumeration<String> kEnum = fontsTable.keys();

                while(kEnum.hasMoreElements()){
                    Object key = kEnum.nextElement();
                    HashMapReference hmr = fontsTable.remove(key);
                    FontPeerImpl delPeer = (FontPeerImpl)hmr.get();
                    
                    if ((delPeer != null) && (delPeer.getClass() != CompositeFont.class)){
                        // there's nothing to dispose in CompositeFont objects
                        delPeer.dispose();
                    }
                }
            } catch (Throwable t){
                throw new RuntimeException(t);
            }
        }
      }

    /**
     * Returns File object, created in a directory
     * according to the System, where JVM is being ran.
     *
     * In Linux case we use ".fonts" directory (for fontconfig purpose),
     * where font file from the stream will be stored, hence in LinuxFontManager this
     * method is overridden.
     * In Windows case we use Windows temp directory (default implementation)
     *
     */
    public File getTempFontFile()throws IOException{
        //???AWT
        /*
        File fontFile = File.createTempFile("jFont", ".ttf"); //$NON-NLS-1$ //$NON-NLS-2$
        fontFile.deleteOnExit();

        return fontFile;
         */
        if(NOT_IMP)
            throw new NotImplementedException("getTempFontFile not Implemented");
        return null;
    }

    /**
     * Returns File object with font properties. It's name obtained using current 
     * system configuration properties and locale settings. If no appropriate 
     * file is found method returns null. 
     */
    public static File getFontPropertyFile(){
        File file = null;

        String javaHome = System.getProperty("java.home"); //$NON-NLS-1$
        Locale l = Locale.getDefault();
        String language = l.getLanguage();
        String country = l.getCountry();
        String fileEncoding = System.getProperty("file.encoding"); //$NON-NLS-1$

        String os = System.getProperty("os.name"); //$NON-NLS-1$

        int i = 0;

        // OS names from system properties don't match
        // OS identifiers used in font.property files
        for (; i < OS_VALUES.length; i++){
            if (os.endsWith(OS_VALUES[i])){
                os = OS_VALUES[i];
                break;
            }
        }

        if (i == OS_VALUES.length){
            os = null;
        }

        String version = System.getProperty("os.version"); //$NON-NLS-1$
        String pathname;

        for (i = 0; i < FP_FILE_NAMES.length; i++){
            pathname = FP_FILE_NAMES[i];
            if (os != null){
                pathname = pathname.replaceFirst("OS", os); //$NON-NLS-1$
            }

            pathname = javaHome + pathname;

            pathname = pathname.replaceAll("Language", language). //$NON-NLS-1$
                                replaceAll("Country", country). //$NON-NLS-1$
                                replaceAll("Encoding", fileEncoding). //$NON-NLS-1$
                                replaceAll("Version", version); //$NON-NLS-1$

            file = new File(pathname);

            if (file.exists()){
                break;
            }
        }

        return file.exists() ? file : null;
    }

    /**
     * Returns an array of integer range values
     * if the parameter exclusionString has format:
     *          Range
     *          Range [, exclusionString]
     *
     *          Range:
     *              Char-Char
     *
     *          Char:
     *              HexDigit HexDigit HexDigit HexDigit
     * 
     * Method returns null if the specified string is null.
     *  
     * @param exclusionString string parameter in specified format
     */
    public static int[] parseIntervals(String exclusionString){
        int[] results = null;

        if (exclusionString == null){
            return null;
        }

        String[] intervals = exclusionString.split(","); //$NON-NLS-1$

        if (intervals != null){
            int num = intervals.length;
            if (num > 0){
                results = new int[intervals.length << 1];
                for (int i = 0; i < intervals.length; i++){
                    String ranges[] = intervals[i].split("-"); //$NON-NLS-1$
                    results[i*2] = Integer.parseInt(ranges[0], 16);
                    results[i*2+1] = Integer.parseInt(ranges[1], 16);

                }
            }
        }
        return results;
    }

    /**
     * Returns Properties from the properties file or null if 
     * there is an error with FileInputStream processing.
     * 
     * @param file File object containing properties
     */
    public static Properties getProperties(File file){
        Properties props = null;
        FileInputStream fis = null;
        try{
            fis = new FileInputStream(file);
            props = new Properties();
            props.load(fis);
        } catch (Exception e){
            System.out.println(e);
        }
        return props;
    }

    /**
     * Returns an array of FontProperties from the properties file
     * with the specified property name "logical face.style". E.g. 
     * "dialog.2" corresponds to the font family Dialog with bold style. 
     *
     * @param fpName key of the font properties in the properties set
     */
    public FontProperty[] getFontProperties(String fpName){
        Vector<FontProperty> props = fProperties.get(fpName);
        
        if (props == null){
            return null;
        }

        int size =  props.size();
        
        if (size == 0){
            return null;
        }

        FontProperty[] fps = new FontProperty[size];
        for (int i=0; i < fps.length; i++){
            fps[i] = props.elementAt(i);
        }
        return fps;
    }

    /**
     * Returns index of the font name in array of font names or -1 if 
     * this font is not logical.
     * 
     * @param fontName specified font name
     */
    public static int getLogicalFaceIndex(String fontName){
        for (int i=0; i<LOGICAL_FONT_NAMES.length; i++ ){
            if (LOGICAL_FONT_NAMES[i].equalsIgnoreCase(fontName)){
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns true if specified family name is available in this 
     * GraphicsEnvironment. 
     * 
     * @param familyName the specified font family name
     */
    public boolean isFamilyExist(String familyName){
        return (getFamilyIndex(familyName) != -1);
    }

    /**
     * Returns index of family name from the array of family names available in 
     * this GraphicsEnvironment or -1 if no family name was found.
     * 
     * @param familyName specified font family name 
     */
    public int getFamilyIndex(String familyName){
        for (int i=0; i<allFamilies.length; i++ ){
            if (familyName.equalsIgnoreCase(allFamilies[i])){
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns family with index specified from the array of family names available in 
     * this GraphicsEnvironment.
     * 
     * @param index index of the family in families names array 
     */
    public String getFamily(int index){
        return allFamilies[index];
    }
    /**
     * Returns index of face name from the array of face names available in 
     * this GraphicsEnvironment or -1 if no face name was found. Default return 
     * value is -1, method must be overridden by FontManager implementation.
     * 
     * @param faceName font face name which index is to be searched
     */
    public int getFaceIndex(String faceName){
        return -1;
    }

    public abstract String[] getAllFamilies();

    public abstract Font[] getAllFonts();
    
    /**
     * Class contains SoftReference instance that can be stored in the 
     * Hashtable by means of key field corresponding to it.
     */
    private class HashMapReference extends SoftReference<FontPeer> {
        
        /**
         * The key for Hashtable.
         */
        private final String key;

        /**
         * Creates a new soft reference with the key specified and 
         * adding this reference in the reference queue specified.
         *
         * @param key the key in Hashtable
         * @param value object that corresponds to the key
         * @param queue reference queue where reference is to be added 
         */
        public HashMapReference(final String key, final FontPeer value,
                              final ReferenceQueue<FontPeer> queue) {
            super(value, queue);
            this.key = key;
        }

        /**
         * Returns the key that corresponds to the SoftReference instance 
         *
         * @return the key in Hashtable with cached references
         */
        public Object getKey() {
            return key;
        }
    }

    /**
     * Removes keys from the Hashtable with font peers which corresponding 
     * HashMapReference objects were garbage collected.
     */
    private void updateFontsTable() {
        HashMapReference r;
        //???AWT
        //while ((r = (HashMapReference)queue.poll()) != null) {
        //    fontsTable.remove(r.getKey());
        //}
    }

}


