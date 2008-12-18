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

package java.awt;

import com.android.internal.awt.AndroidGraphics2D;

import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.font.TransformAttribute;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.text.CharacterIterator;
import java.text.AttributedCharacterIterator.Attribute;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.harmony.awt.gl.font.AndroidGlyphVector;
import org.apache.harmony.awt.gl.font.CommonGlyphVector;
import org.apache.harmony.awt.gl.font.FontPeerImpl;
import org.apache.harmony.awt.gl.font.FontMetricsImpl;
import org.apache.harmony.awt.gl.font.LineMetricsImpl;
import org.apache.harmony.awt.internal.nls.Messages;
import org.apache.harmony.luni.util.NotImplementedException;
import org.apache.harmony.misc.HashCode;

/**
 * The Font class represents fonts for rendering text. This class allow to map
 * characters to glyphs.
 * <p>
 * A glyph is a shape used to render a character or a sequence of characters.
 * For example one character of Latin writing system represented by one glyph,
 * but in complex writing system such as South and South-East Asian there is
 * more complicated correspondence between characters and glyphs.
 * <p>
 * The Font object is identified by two types of names. The logical font name is
 * the name that is used to construct the font. The font name is the name of a
 * particular font face (for example, Arial Bold). The family name is the font's
 * family name that specifies the typographic design across several faces (for
 * example, Arial). In all the Font is identified by three attributes: the
 * family name, the style (such as bold or italic), and the size.
 * 
 * @since Android 1.0
 */
public class Font implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -4206021311591459213L;

    // Identity Transform attribute
    /**
     * The Constant IDENTITY_TRANSFORM.
     */
    private static final TransformAttribute IDENTITY_TRANSFORM = new TransformAttribute(
            new AffineTransform());

    /**
     * The Constant PLAIN indicates font's plain style.
     */
    public static final int PLAIN = 0;

    /**
     * The Constant BOLD indicates font's bold style.
     */
    public static final int BOLD = 1;

    /**
     * The Constant ITALIC indicates font's italic style.
     */
    public static final int ITALIC = 2;

    /**
     * The Constant ROMAN_BASELINE indicated roman baseline.
     */
    public static final int ROMAN_BASELINE = 0;

    /**
     * The Constant CENTER_BASELINE indicates center baseline.
     */
    public static final int CENTER_BASELINE = 1;

    /**
     * The Constant HANGING_BASELINE indicates hanging baseline.
     */
    public static final int HANGING_BASELINE = 2;

    /**
     * The Constant TRUETYPE_FONT indicates a font resource of type TRUETYPE.
     */
    public static final int TRUETYPE_FONT = 0;

    /**
     * The Constant TYPE1_FONT indicates a font resource of type TYPE1.
     */
    public static final int TYPE1_FONT = 1;

    /**
     * The Constant LAYOUT_LEFT_TO_RIGHT indicates that text is left to right.
     */
    public static final int LAYOUT_LEFT_TO_RIGHT = 0;

    /**
     * The Constant LAYOUT_RIGHT_TO_LEFT indicates that text is right to left.
     */
    public static final int LAYOUT_RIGHT_TO_LEFT = 1;

    /**
     * The Constant LAYOUT_NO_START_CONTEXT indicates that the text in the char
     * array before the indicated start should not be examined.
     */
    public static final int LAYOUT_NO_START_CONTEXT = 2;

    /**
     * The Constant LAYOUT_NO_LIMIT_CONTEXT indicates that text in the char
     * array after the indicated limit should not be examined.
     */
    public static final int LAYOUT_NO_LIMIT_CONTEXT = 4;

    /**
     * The Constant DEFAULT_FONT.
     */
    static final Font DEFAULT_FONT = new Font("Dialog", Font.PLAIN, 12); //$NON-NLS-1$

    /**
     * The name of the Font.
     */
    protected String name;

    /**
     * The style of the Font.
     */
    protected int style;

    /**
     * The size of the Font.
     */
    protected int size;

    /**
     * The point size of the Font.
     */
    protected float pointSize;

    // Flag if the Font object transformed
    /**
     * The transformed.
     */
    private boolean transformed;

    // Set of font attributes
    /**
     * The requested attributes.
     */
    private Hashtable<Attribute, Object> fRequestedAttributes;

    // font peer object corresponding to this Font
    /**
     * The font peer.
     */
    private transient FontPeerImpl fontPeer;

    // number of glyphs in this Font
    /**
     * The num glyphs.
     */
    private transient int numGlyphs = -1;

    // code for missing glyph for this Font
    /**
     * The missing glyph code.
     */
    private transient int missingGlyphCode = -1;

    /**
     * Writes object to ObjectOutputStream.
     * 
     * @param out
     *            ObjectOutputStream.
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void writeObject(java.io.ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
    }

    /**
     * Reads object from ObjectInputStream object and set native platform
     * dependent fields to default values.
     * 
     * @param in
     *            ObjectInputStream object.
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     * @throws ClassNotFoundException
     *             the class not found exception.
     */
    private void readObject(java.io.ObjectInputStream in) throws IOException,
            ClassNotFoundException {
        in.defaultReadObject();

        numGlyphs = -1;
        missingGlyphCode = -1;

    }

    /**
     * Instantiates a new Font with the specified attributes. The Font will be
     * created with default attributes if the attribute's parameter is null.
     * 
     * @param attributes
     *            the attributes to be assigned to the new Font, or null.
     */
    public Font(Map<? extends Attribute, ?> attributes) {
        Object currAttr;

        // Default values are taken from the documentation of the Font class.
        // See Font constructor, decode and getFont sections.

        this.name = "default"; //$NON-NLS-1$
        this.size = 12;
        this.pointSize = 12;
        this.style = Font.PLAIN;

        if (attributes != null) {

            fRequestedAttributes = new Hashtable<Attribute, Object>(attributes);

            currAttr = attributes.get(TextAttribute.SIZE);
            if (currAttr != null) {
                this.pointSize = ((Float)currAttr).floatValue();
                this.size = (int)Math.ceil(this.pointSize);
            }

            currAttr = attributes.get(TextAttribute.POSTURE);
            if (currAttr != null && currAttr.equals(TextAttribute.POSTURE_OBLIQUE)) {
                this.style |= Font.ITALIC;
            }

            currAttr = attributes.get(TextAttribute.WEIGHT);
            if ((currAttr != null)
                    && (((Float)currAttr).floatValue() >= (TextAttribute.WEIGHT_BOLD).floatValue())) {
                this.style |= Font.BOLD;
            }

            currAttr = attributes.get(TextAttribute.FAMILY);
            if (currAttr != null) {
                this.name = (String)currAttr;
            }

            currAttr = attributes.get(TextAttribute.TRANSFORM);
            if (currAttr != null) {
                if (currAttr instanceof TransformAttribute) {
                    this.transformed = !((TransformAttribute)currAttr).getTransform().isIdentity();
                } else if (currAttr instanceof AffineTransform) {
                    this.transformed = !((AffineTransform)currAttr).isIdentity();
                }
            }

        } else {
            fRequestedAttributes = new Hashtable<Attribute, Object>(5);
            fRequestedAttributes.put(TextAttribute.TRANSFORM, IDENTITY_TRANSFORM);

            this.transformed = false;

            fRequestedAttributes.put(TextAttribute.FAMILY, name);

            fRequestedAttributes.put(TextAttribute.SIZE, new Float(this.size));

            if ((this.style & Font.BOLD) != 0) {
                fRequestedAttributes.put(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD);
            } else {
                fRequestedAttributes.put(TextAttribute.WEIGHT, TextAttribute.WEIGHT_REGULAR);
            }
            if ((this.style & Font.ITALIC) != 0) {
                fRequestedAttributes.put(TextAttribute.POSTURE, TextAttribute.POSTURE_OBLIQUE);
            } else {
                fRequestedAttributes.put(TextAttribute.POSTURE, TextAttribute.POSTURE_REGULAR);
            }

        }
    }

    /**
     * Instantiates a new Font with the specified name, style and size.
     * 
     * @param name
     *            the name of font.
     * @param style
     *            the style of font.
     * @param size
     *            the size of font.
     */
    public Font(String name, int style, int size) {

        this.name = (name != null) ? name : "Default"; //$NON-NLS-1$
        this.size = (size >= 0) ? size : 0;
        this.style = (style & ~0x03) == 0 ? style : Font.PLAIN;
        this.pointSize = this.size;

        fRequestedAttributes = new Hashtable<Attribute, Object>(5);

        fRequestedAttributes.put(TextAttribute.TRANSFORM, IDENTITY_TRANSFORM);

        this.transformed = false;

        fRequestedAttributes.put(TextAttribute.FAMILY, this.name);
        fRequestedAttributes.put(TextAttribute.SIZE, new Float(this.size));

        if ((this.style & Font.BOLD) != 0) {
            fRequestedAttributes.put(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD);
        } else {
            fRequestedAttributes.put(TextAttribute.WEIGHT, TextAttribute.WEIGHT_REGULAR);
        }
        if ((this.style & Font.ITALIC) != 0) {
            fRequestedAttributes.put(TextAttribute.POSTURE, TextAttribute.POSTURE_OBLIQUE);
        } else {
            fRequestedAttributes.put(TextAttribute.POSTURE, TextAttribute.POSTURE_REGULAR);
        }
    }

    /**
     * Returns true if this Font has a glyph for the specified character.
     * 
     * @param c
     *            the character.
     * @return true if this Font has a glyph for the specified character, false
     *         otherwise.
     */
    public boolean canDisplay(char c) {
        FontPeerImpl peer = (FontPeerImpl)this.getPeer();
        return peer.canDisplay(c);
    }

    /**
     * Returns true if the Font can display the characters of the the specified
     * text from the specified start position to the specified limit position.
     * 
     * @param text
     *            the text.
     * @param start
     *            the start offset (in the character array).
     * @param limit
     *            the limit offset (in the character array).
     * @return the a character's position in the text that this Font can not
     *         display, or -1 if this Font can display all characters in this
     *         text.
     */
    public int canDisplayUpTo(char[] text, int start, int limit) {
        int st = start;
        int result;
        while ((st < limit) && canDisplay(text[st])) {
            st++;
        }

        if (st == limit) {
            result = -1;
        } else {
            result = st;
        }

        return result;
    }

    /**
     * Returns true if the Font can display the characters of the the specified
     * CharacterIterator from the specified start position and the specified
     * limit position.
     * 
     * @param iter
     *            the CharacterIterator.
     * @param start
     *            the start offset.
     * @param limit
     *            the limit offset.
     * @return the a character's position in the CharacterIterator that this
     *         Font can not display, or -1 if this Font can display all
     *         characters in this text.
     */
    public int canDisplayUpTo(CharacterIterator iter, int start, int limit) {
        int st = start;
        char c = iter.setIndex(start);
        int result;

        while ((st < limit) && (canDisplay(c))) {
            st++;
            c = iter.next();
        }
        if (st == limit) {
            result = -1;
        } else {
            result = st;
        }

        return result;
    }

    /**
     * Returns true if this Font can display a specified String.
     * 
     * @param str
     *            the String.
     * @return the a character's position in the String that this Font can not
     *         display, or -1 if this Font can display all characters in this
     *         text.
     */
    public int canDisplayUpTo(String str) {
        char[] chars = str.toCharArray();
        return canDisplayUpTo(chars, 0, chars.length);
    }

    /**
     * Creates a GlyphVector of associating characters to glyphs based on the
     * Unicode map of this Font.
     * 
     * @param frc
     *            the FontRenderContext.
     * @param chars
     *            the characters array.
     * @return the GlyphVector of associating characters to glyphs based on the
     *         Unicode map of this Font.
     */
    public GlyphVector createGlyphVector(FontRenderContext frc, char[] chars) {
        return new AndroidGlyphVector(chars, frc, this, 0);
    }

    /**
     * Creates a GlyphVector of associating characters contained in the
     * specified CharacterIterator to glyphs based on the Unicode map of this
     * Font.
     * 
     * @param frc
     *            the FontRenderContext.
     * @param iter
     *            the CharacterIterator.
     * @return the GlyphVector of associating characters contained in the
     *         specified CharacterIterator to glyphs based on the Unicode map of
     *         this Font.
     */
    public GlyphVector createGlyphVector(FontRenderContext frc, CharacterIterator iter) {
        throw new RuntimeException("Not implemented!"); //$NON-NLS-1$    
    }

    /**
     * Creates a GlyphVector of associating characters to glyphs based on the
     * Unicode map of this Font.
     * 
     * @param frc
     *            the FontRenderContext.
     * @param glyphCodes
     *            the specified integer array of glyph codes.
     * @return the GlyphVector of associating characters to glyphs based on the
     *         Unicode map of this Font.
     * @throws NotImplementedException
     *             if this method is not implemented by a subclass.
     */
    public GlyphVector createGlyphVector(FontRenderContext frc, int[] glyphCodes)
            throws org.apache.harmony.luni.util.NotImplementedException {
        throw new RuntimeException("Not implemented!"); //$NON-NLS-1$
    }

    /**
     * Creates a GlyphVector of associating characters to glyphs based on the
     * Unicode map of this Font.
     * 
     * @param frc
     *            the FontRenderContext.
     * @param str
     *            the specified String.
     * @return the GlyphVector of associating characters to glyphs based on the
     *         Unicode map of this Font.
     */
    public GlyphVector createGlyphVector(FontRenderContext frc, String str) {
        return new AndroidGlyphVector(str.toCharArray(), frc, this, 0);

    }

    /**
     * Returns the font style constant value corresponding to one of the font
     * style names ("BOLD", "ITALIC", "BOLDITALIC"). This method returns
     * Font.PLAIN if the argument is not one of the predefined style names.
     * 
     * @param fontStyleName
     *            font style name.
     * @return font style constant value corresponding to the font style name
     *         specified.
     */
    private static int getFontStyle(String fontStyleName) {
        int result = Font.PLAIN;

        if (fontStyleName.toUpperCase().equals("BOLDITALIC")) { //$NON-NLS-1$
            result = Font.BOLD | Font.ITALIC;
        } else if (fontStyleName.toUpperCase().equals("BOLD")) { //$NON-NLS-1$
            result = Font.BOLD;
        } else if (fontStyleName.toUpperCase().equals("ITALIC")) { //$NON-NLS-1$
            result = Font.ITALIC;
        }

        return result;
    }

    /**
     * Decodes the specified string which described the Font. The string should
     * have the following format: fontname-style-pointsize. The style can be
     * PLAIN, BOLD, BOLDITALIC, or ITALIC.
     * 
     * @param str
     *            the string which describes the font.
     * @return the Font from the specified string.
     */
    public static Font decode(String str) {
        // XXX: Documentation doesn't describe all cases, e.g. fonts face names
        // with
        // symbols that are suggested as delimiters in the documentation.
        // In this decode implementation only ***-***-*** format is used with
        // '-'
        // as the delimiter to avoid unexpected parse results of font face names
        // with spaces.

        if (str == null) {
            return DEFAULT_FONT;
        }

        StringTokenizer strTokens;
        String delim = "-"; //$NON-NLS-1$
        String substr;

        int fontSize = DEFAULT_FONT.size;
        int fontStyle = DEFAULT_FONT.style;
        String fontName = DEFAULT_FONT.name;

        strTokens = new StringTokenizer(str.trim(), delim);

        // Font Name
        if (strTokens.hasMoreTokens()) {
            fontName = strTokens.nextToken(); // first token is the font name
        }

        // Font Style or Size (if the style is undefined)
        if (strTokens.hasMoreTokens()) {
            substr = strTokens.nextToken();

            try {
                // if second token is the font size
                fontSize = Integer.parseInt(substr);
            } catch (NumberFormatException e) {
                // then second token is the font style
                fontStyle = getFontStyle(substr);
            }

        }

        // Font Size
        if (strTokens.hasMoreTokens()) {
            try {
                fontSize = Integer.parseInt(strTokens.nextToken());
            } catch (NumberFormatException e) {
            }
        }

        return new Font(fontName, fontStyle, fontSize);
    }

    /**
     * Performs the specified affine transform to the Font and returns a new
     * Font.
     * 
     * @param trans
     *            the AffineTransform.
     * @return the Font object.
     * @throws IllegalArgumentException
     *             if affine transform parameter is null.
     */
    @SuppressWarnings("unchecked")
    public Font deriveFont(AffineTransform trans) {

        if (trans == null) {
            // awt.94=transform can not be null
            throw new IllegalArgumentException(Messages.getString("awt.94")); //$NON-NLS-1$
        }

        Hashtable<Attribute, Object> derivefRequestedAttributes = (Hashtable<Attribute, Object>)fRequestedAttributes
                .clone();

        derivefRequestedAttributes.put(TextAttribute.TRANSFORM, new TransformAttribute(trans));

        return new Font(derivefRequestedAttributes);

    }

    /**
     * Returns a new Font that is a copy of the current Font modified so that
     * the size is the specified size.
     * 
     * @param size
     *            the size of font.
     * @return the Font object.
     */
    @SuppressWarnings("unchecked")
    public Font deriveFont(float size) {
        Hashtable<Attribute, Object> derivefRequestedAttributes = (Hashtable<Attribute, Object>)fRequestedAttributes
                .clone();
        derivefRequestedAttributes.put(TextAttribute.SIZE, new Float(size));
        return new Font(derivefRequestedAttributes);
    }

    /**
     * Returns a new Font that is a copy of the current Font modified so that
     * the style is the specified style.
     * 
     * @param style
     *            the style of font.
     * @return the Font object.
     */
    @SuppressWarnings("unchecked")
    public Font deriveFont(int style) {
        Hashtable<Attribute, Object> derivefRequestedAttributes = (Hashtable<Attribute, Object>)fRequestedAttributes
                .clone();

        if ((style & Font.BOLD) != 0) {
            derivefRequestedAttributes.put(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD);
        } else if (derivefRequestedAttributes.get(TextAttribute.WEIGHT) != null) {
            derivefRequestedAttributes.remove(TextAttribute.WEIGHT);
        }

        if ((style & Font.ITALIC) != 0) {
            derivefRequestedAttributes.put(TextAttribute.POSTURE, TextAttribute.POSTURE_OBLIQUE);
        } else if (derivefRequestedAttributes.get(TextAttribute.POSTURE) != null) {
            derivefRequestedAttributes.remove(TextAttribute.POSTURE);
        }

        return new Font(derivefRequestedAttributes);
    }

    /**
     * Returns a new Font that is a copy of the current Font modified to match
     * the specified style and with the specified affine transform applied to
     * its glyphs.
     * 
     * @param style
     *            the style of font.
     * @param trans
     *            the AffineTransform.
     * @return the Font object.
     */
    @SuppressWarnings("unchecked")
    public Font deriveFont(int style, AffineTransform trans) {

        if (trans == null) {
            // awt.94=transform can not be null
            throw new IllegalArgumentException(Messages.getString("awt.94")); //$NON-NLS-1$
        }
        Hashtable<Attribute, Object> derivefRequestedAttributes = (Hashtable<Attribute, Object>)fRequestedAttributes
                .clone();

        if ((style & BOLD) != 0) {
            derivefRequestedAttributes.put(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD);
        } else if (derivefRequestedAttributes.get(TextAttribute.WEIGHT) != null) {
            derivefRequestedAttributes.remove(TextAttribute.WEIGHT);
        }

        if ((style & ITALIC) != 0) {
            derivefRequestedAttributes.put(TextAttribute.POSTURE, TextAttribute.POSTURE_OBLIQUE);
        } else if (derivefRequestedAttributes.get(TextAttribute.POSTURE) != null) {
            derivefRequestedAttributes.remove(TextAttribute.POSTURE);
        }
        derivefRequestedAttributes.put(TextAttribute.TRANSFORM, new TransformAttribute(trans));

        return new Font(derivefRequestedAttributes);
    }

    /**
     * Returns a new Font that is a copy of the current Font modified so that
     * the size and style are the specified size and style.
     * 
     * @param style
     *            the style of font.
     * @param size
     *            the size of font.
     * @return the Font object.
     */
    @SuppressWarnings("unchecked")
    public Font deriveFont(int style, float size) {
        Hashtable<Attribute, Object> derivefRequestedAttributes = (Hashtable<Attribute, Object>)fRequestedAttributes
                .clone();

        if ((style & BOLD) != 0) {
            derivefRequestedAttributes.put(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD);
        } else if (derivefRequestedAttributes.get(TextAttribute.WEIGHT) != null) {
            derivefRequestedAttributes.remove(TextAttribute.WEIGHT);
        }

        if ((style & ITALIC) != 0) {
            derivefRequestedAttributes.put(TextAttribute.POSTURE, TextAttribute.POSTURE_OBLIQUE);
        } else if (derivefRequestedAttributes.get(TextAttribute.POSTURE) != null) {
            derivefRequestedAttributes.remove(TextAttribute.POSTURE);
        }

        derivefRequestedAttributes.put(TextAttribute.SIZE, new Float(size));
        return new Font(derivefRequestedAttributes);

    }

    /**
     * Returns a new Font object with a new set of font attributes.
     * 
     * @param attributes
     *            the map of attributes.
     * @return the Font.
     */
    @SuppressWarnings("unchecked")
    public Font deriveFont(Map<? extends Attribute, ?> attributes) {
        Attribute[] avalAttributes = this.getAvailableAttributes();

        Hashtable<Attribute, Object> derivefRequestedAttributes = (Hashtable<Attribute, Object>)fRequestedAttributes
                .clone();
        Object currAttribute;
        for (Attribute element : avalAttributes) {
            currAttribute = attributes.get(element);
            if (currAttribute != null) {
                derivefRequestedAttributes.put(element, currAttribute);
            }
        }
        return new Font(derivefRequestedAttributes);
    }

    /**
     * Compares the specified Object with the current Font.
     * 
     * @param obj
     *            the Object to be compared.
     * @return true, if the specified Object is an instance of Font with the
     *         same family, size, and style as this Font, false otherwise.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        if (obj != null) {
            try {
                Font font = (Font)obj;

                return ((this.style == font.style) && (this.size == font.size)
                        && this.name.equals(font.name) && (this.pointSize == font.pointSize) && (this
                        .getTransform()).equals(font.getTransform()));
            } catch (ClassCastException e) {
            }
        }

        return false;
    }

    /**
     * Gets the map of font's attributes.
     * 
     * @return the map of font's attributes.
     */
    @SuppressWarnings("unchecked")
    public Map<TextAttribute, ?> getAttributes() {
        return (Map<TextAttribute, ?>)fRequestedAttributes.clone();
    }

    /**
     * Gets the keys of all available attributes.
     * 
     * @return the keys array of all available attributes.
     */
    public Attribute[] getAvailableAttributes() {
        Attribute[] attrs = {
                TextAttribute.FAMILY, TextAttribute.POSTURE, TextAttribute.SIZE,
                TextAttribute.TRANSFORM, TextAttribute.WEIGHT, TextAttribute.SUPERSCRIPT,
                TextAttribute.WIDTH
        };
        return attrs;
    }

    /**
     * Gets the baseline for this character.
     * 
     * @param c
     *            the character.
     * @return the baseline for this character.
     */
    public byte getBaselineFor(char c) {
        // TODO: implement using TT BASE table data
        return 0;
    }

    /**
     * Gets the family name of the Font.
     * 
     * @return the family name of the Font.
     */
    public String getFamily() {
        if (fRequestedAttributes != null) {
            fRequestedAttributes.get(TextAttribute.FAMILY);
        }
        return null;
    }

    /**
     * Returns the family name of this Font associated with the specified
     * locale.
     * 
     * @param l
     *            the locale.
     * @return the family name of this Font associated with the specified
     *         locale.
     */
    public String getFamily(Locale l) {
        if (l == null) {
            // awt.01='{0}' parameter is null
            throw new NullPointerException(Messages.getString("awt.01", "Locale")); //$NON-NLS-1$ //$NON-NLS-2$ 
        }
        return getFamily();
    }

    /**
     * Gets a Font with the specified attribute set.
     * 
     * @param attributes
     *            the attributes to be assigned to the new Font.
     * @return the Font.
     */
    public static Font getFont(Map<? extends Attribute, ?> attributes) {
        Font fnt = (Font)attributes.get(TextAttribute.FONT);
        if (fnt != null) {
            return fnt;
        }
        return new Font(attributes);
    }

    /**
     * Gets a Font object from the system properties list with the specified
     * name or returns the specified Font if there is no such property.
     * 
     * @param sp
     *            the specified property name.
     * @param f
     *            the Font.
     * @return the Font object from the system properties list with the
     *         specified name or the specified Font if there is no such
     *         property.
     */
    public static Font getFont(String sp, Font f) {
        String pr = System.getProperty(sp);
        if (pr == null) {
            return f;
        }
        return decode(pr);
    }

    /**
     * Gets a Font object from the system properties list with the specified
     * name.
     * 
     * @param sp
     *            the system property name.
     * @return the Font, or null if there is no such property with the specified
     *         name.
     */
    public static Font getFont(String sp) {
        return getFont(sp, null);
    }

    /**
     * Gets the font name.
     * 
     * @return the font name.
     */
    public String getFontName() {
        if (fRequestedAttributes != null) {
            fRequestedAttributes.get(TextAttribute.FAMILY);
        }
        return null;
    }

    /**
     * Returns the font name associated with the specified locale.
     * 
     * @param l
     *            the locale.
     * @return the font name associated with the specified locale.
     */
    public String getFontName(Locale l) {
        return getFamily();
    }

    /**
     * Returns a LineMetrics object created with the specified parameters.
     * 
     * @param chars
     *            the chars array.
     * @param start
     *            the start offset.
     * @param end
     *            the end offset.
     * @param frc
     *            the FontRenderContext.
     * @return the LineMetrics for the specified parameters.
     */
    public LineMetrics getLineMetrics(char[] chars, int start, int end, FontRenderContext frc) {
        if (frc == null) {
            // awt.00=FontRenderContext is null
            throw new NullPointerException(Messages.getString("awt.00")); //$NON-NLS-1$
        }

        // FontMetrics fm = AndroidGraphics2D.getInstance().getFontMetrics();
        FontMetrics fm = new FontMetricsImpl(this);
        float[] fmet = {
                fm.getAscent(), fm.getDescent(), fm.getLeading()
        };
        return new LineMetricsImpl(chars.length, fmet, null);
    }

    /**
     * Returns a LineMetrics object created with the specified parameters.
     * 
     * @param iter
     *            the CharacterIterator.
     * @param start
     *            the start offset.
     * @param end
     *            the end offset.
     * @param frc
     *            the FontRenderContext.
     * @return the LineMetrics for the specified parameters.
     */
    public LineMetrics getLineMetrics(CharacterIterator iter, int start, int end,
            FontRenderContext frc) {

        if (frc == null) {
            // awt.00=FontRenderContext is null
            throw new NullPointerException(Messages.getString("awt.00")); //$NON-NLS-1$
        }

        String resultString;
        int iterCount;

        iterCount = end - start;
        if (iterCount < 0) {
            resultString = ""; //$NON-NLS-1$
        } else {
            char[] chars = new char[iterCount];
            int i = 0;
            for (char c = iter.setIndex(start); c != CharacterIterator.DONE && (i < iterCount); c = iter
                    .next()) {
                chars[i] = c;
                i++;
            }
            resultString = new String(chars);
        }
        return this.getLineMetrics(resultString, frc);
    }

    /**
     * Returns a LineMetrics object created with the specified parameters.
     * 
     * @param str
     *            the String.
     * @param frc
     *            the FontRenderContext.
     * @return the LineMetrics for the specified parameters.
     */
    public LineMetrics getLineMetrics(String str, FontRenderContext frc) {
        // FontMetrics fm = AndroidGraphics2D.getInstance().getFontMetrics();
        FontMetrics fm = new FontMetricsImpl(this);
        float[] fmet = {
                fm.getAscent(), fm.getDescent(), fm.getLeading()
        };
        // Log.i("FONT FMET", fmet.toString());
        return new LineMetricsImpl(str.length(), fmet, null);

    }

    /**
     * Returns a LineMetrics object created with the specified parameters.
     * 
     * @param str
     *            the String.
     * @param start
     *            the start offset.
     * @param end
     *            the end offset.
     * @param frc
     *            the FontRenderContext.
     * @return the LineMetrics for the specified parameters.
     */
    public LineMetrics getLineMetrics(String str, int start, int end, FontRenderContext frc) {
        return this.getLineMetrics(str.substring(start, end), frc);
    }

    /**
     * Gets the logical bounds of the specified String in the specified
     * FontRenderContext. The logical bounds contains the origin, ascent,
     * advance, and height.
     * 
     * @param ci
     *            the specified CharacterIterator.
     * @param start
     *            the start offset.
     * @param end
     *            the end offset.
     * @param frc
     *            the FontRenderContext.
     * @return a Rectangle2D object.
     */
    public Rectangle2D getStringBounds(CharacterIterator ci, int start, int end,
            FontRenderContext frc) {
        int first = ci.getBeginIndex();
        int finish = ci.getEndIndex();
        char[] chars;

        if (start < first) {
            // awt.95=Wrong start index: {0}
            throw new IndexOutOfBoundsException(Messages.getString("awt.95", start)); //$NON-NLS-1$
        }
        if (end > finish) {
            // awt.96=Wrong finish index: {0}
            throw new IndexOutOfBoundsException(Messages.getString("awt.96", end)); //$NON-NLS-1$
        }
        if (start > end) {
            // awt.97=Wrong range length: {0}
            throw new IndexOutOfBoundsException(Messages.getString("awt.97", //$NON-NLS-1$
                    (end - start)));
        }

        if (frc == null) {
            throw new NullPointerException(Messages.getString("awt.00")); //$NON-NLS-1$
        }

        chars = new char[end - start];

        ci.setIndex(start);
        for (int i = 0; i < chars.length; i++) {
            chars[i] = ci.current();
            ci.next();
        }

        return this.getStringBounds(chars, 0, chars.length, frc);

    }

    /**
     * Gets the logical bounds of the specified String in the specified
     * FontRenderContext. The logical bounds contains the origin, ascent,
     * advance, and height.
     * 
     * @param str
     *            the specified String.
     * @param frc
     *            the FontRenderContext.
     * @return a Rectangle2D object.
     */
    public Rectangle2D getStringBounds(String str, FontRenderContext frc) {
        char[] chars = str.toCharArray();
        return this.getStringBounds(chars, 0, chars.length, frc);

    }

    /**
     * Gets the logical bounds of the specified String in the specified
     * FontRenderContext. The logical bounds contains the origin, ascent,
     * advance, and height.
     * 
     * @param str
     *            the specified String.
     * @param start
     *            the start offset.
     * @param end
     *            the end offset.
     * @param frc
     *            the FontRenderContext.
     * @return a Rectangle2D object.
     */
    public Rectangle2D getStringBounds(String str, int start, int end, FontRenderContext frc) {

        return this.getStringBounds((str.substring(start, end)), frc);
    }

    /**
     * Gets the logical bounds of the specified String in the specified
     * FontRenderContext. The logical bounds contains the origin, ascent,
     * advance, and height.
     * 
     * @param chars
     *            the specified character array.
     * @param start
     *            the start offset.
     * @param end
     *            the end offset.
     * @param frc
     *            the FontRenderContext.
     * @return a Rectangle2D object.
     */
    public Rectangle2D getStringBounds(char[] chars, int start, int end, FontRenderContext frc) {
        if (start < 0) {
            // awt.95=Wrong start index: {0}
            throw new IndexOutOfBoundsException(Messages.getString("awt.95", start)); //$NON-NLS-1$
        }
        if (end > chars.length) {
            // awt.96=Wrong finish index: {0}
            throw new IndexOutOfBoundsException(Messages.getString("awt.96", end)); //$NON-NLS-1$
        }
        if (start > end) {
            // awt.97=Wrong range length: {0}
            throw new IndexOutOfBoundsException(Messages.getString("awt.97", //$NON-NLS-1$
                    (end - start)));
        }

        if (frc == null) {
            throw new NullPointerException(Messages.getString("awt.00")); //$NON-NLS-1$
        }

        FontPeerImpl peer = (FontPeerImpl)this.getPeer();

        final int TRANSFORM_MASK = AffineTransform.TYPE_GENERAL_ROTATION
                | AffineTransform.TYPE_GENERAL_TRANSFORM;
        Rectangle2D bounds;

        AffineTransform transform = getTransform();

        // XXX: for transforms where an angle between basis vectors is not 90
        // degrees Rectanlge2D class doesn't fit as Logical bounds.
        if ((transform.getType() & TRANSFORM_MASK) == 0) {
            int width = 0;
            for (int i = start; i < end; i++) {
                width += peer.charWidth(chars[i]);
            }
            // LineMetrics nlm = peer.getLineMetrics();

            LineMetrics nlm = getLineMetrics(chars, start, end, frc);

            bounds = transform.createTransformedShape(
                    new Rectangle2D.Float(0, -nlm.getAscent(), width, nlm.getHeight()))
                    .getBounds2D();
        } else {
            int len = end - start;
            char[] subChars = new char[len];
            System.arraycopy(chars, start, subChars, 0, len);
            bounds = createGlyphVector(frc, subChars).getLogicalBounds();
        }
        return bounds;
    }

    /**
     * Gets the character's maximum bounds as defined in the specified
     * FontRenderContext.
     * 
     * @param frc
     *            the FontRenderContext.
     * @return the character's maximum bounds.
     */
    public Rectangle2D getMaxCharBounds(FontRenderContext frc) {
        if (frc == null) {
            // awt.00=FontRenderContext is null
            throw new NullPointerException(Messages.getString("awt.00")); //$NON-NLS-1$ 
        }

        FontPeerImpl peer = (FontPeerImpl)this.getPeer();

        Rectangle2D bounds = peer.getMaxCharBounds(frc);
        AffineTransform transform = getTransform();
        // !! Documentation doesn't describe meaning of max char bounds
        // for the fonts that have rotate transforms. For all transforms
        // returned bounds are the bounds of transformed maxCharBounds
        // Rectangle2D that corresponds to the font with identity transform.
        // TODO: resolve this issue to return correct bounds
        bounds = transform.createTransformedShape(bounds).getBounds2D();

        return bounds;
    }

    /**
     * Returns a new GlyphVector object performing full layout of the text.
     * 
     * @param frc
     *            the FontRenderContext.
     * @param chars
     *            the character array to be layout.
     * @param start
     *            the start offset of the text to use for the GlyphVector.
     * @param count
     *            the count of characters to use for the GlyphVector.
     * @param flags
     *            the flag indicating text direction: LAYOUT_RIGHT_TO_LEFT,
     *            LAYOUT_LEFT_TO_RIGHT.
     * @return the GlyphVector.
     */
    public GlyphVector layoutGlyphVector(FontRenderContext frc, char[] chars, int start, int count,
            int flags) {
        // TODO: implement method for bidirectional text.
        // At the moment only LTR and RTL texts supported.
        if (start < 0) {
            // awt.95=Wrong start index: {0}
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.95", //$NON-NLS-1$
                    start));
        }

        if (count < 0) {
            // awt.98=Wrong count value, can not be negative: {0}
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.98", //$NON-NLS-1$
                    count));
        }

        if (start + count > chars.length) {
            // awt.99=Wrong [start + count] is out of range: {0}
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.99", //$NON-NLS-1$
                    (start + count)));
        }

        char[] out = new char[count];
        System.arraycopy(chars, start, out, 0, count);

        return new CommonGlyphVector(out, frc, this, flags);
    }

    /**
     * Returns the String representation of this Font.
     * 
     * @return the String representation of this Font.
     */
    @Override
    public String toString() {
        String stl = "plain"; //$NON-NLS-1$
        String result;

        if (this.isBold() && this.isItalic()) {
            stl = "bolditalic"; //$NON-NLS-1$
        }
        if (this.isBold() && !this.isItalic()) {
            stl = "bold"; //$NON-NLS-1$
        }

        if (!this.isBold() && this.isItalic()) {
            stl = "italic"; //$NON-NLS-1$
        }

        result = this.getClass().getName() + "[family=" + this.getFamily() + //$NON-NLS-1$
                ",name=" + this.name + //$NON-NLS-1$
                ",style=" + stl + //$NON-NLS-1$
                ",size=" + this.size + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    /**
     * Gets the postscript name of this Font.
     * 
     * @return the postscript name of this Font.
     */
    public String getPSName() {
        FontPeerImpl peer = (FontPeerImpl)this.getPeer();
        return peer.getPSName();
    }

    /**
     * Gets the logical name of this Font.
     * 
     * @return the logical name of this Font.
     */
    public String getName() {
        return (this.name);
    }

    /**
     * Gets the peer of this Font.
     * 
     * @return the peer of this Font.
     * @deprecated Font rendering is platform independent now.
     */
    @Deprecated
    public java.awt.peer.FontPeer getPeer() {
        if (fontPeer == null) {
            fontPeer = (FontPeerImpl)Toolkit.getDefaultToolkit().getGraphicsFactory().getFontPeer(
                    this);
        }
        return fontPeer;

    }

    /**
     * Gets the transform acting on this Font (from the Font's attributes).
     * 
     * @return the transformation of this Font.
     */
    public AffineTransform getTransform() {
        Object transform = fRequestedAttributes.get(TextAttribute.TRANSFORM);

        if (transform != null) {
            if (transform instanceof TransformAttribute) {
                return ((TransformAttribute)transform).getTransform();
            }
            if (transform instanceof AffineTransform) {
                return new AffineTransform((AffineTransform)transform);
            }
        } else {
            transform = new AffineTransform();
        }
        return (AffineTransform)transform;

    }

    /**
     * Checks if this font is transformed or not.
     * 
     * @return true, if this font is transformed, false otherwise.
     */
    public boolean isTransformed() {
        return this.transformed;
    }

    /**
     * Checks if this font has plain style or not.
     * 
     * @return true, if this font has plain style, false otherwise.
     */
    public boolean isPlain() {
        return (this.style == PLAIN);
    }

    /**
     * Checks if this font has italic style or not.
     * 
     * @return true, if this font has italic style, false otherwise.
     */
    public boolean isItalic() {
        return (this.style & ITALIC) != 0;
    }

    /**
     * Checks if this font has bold style or not.
     * 
     * @return true, if this font has bold style, false otherwise.
     */
    public boolean isBold() {
        return (this.style & BOLD) != 0;
    }

    /**
     * Returns true if this Font has uniform line metrics.
     * 
     * @return true if this Font has uniform line metrics, false otherwise.
     */
    public boolean hasUniformLineMetrics() {
        FontPeerImpl peer = (FontPeerImpl)this.getPeer();
        return peer.hasUniformLineMetrics();
    }

    /**
     * Returns hash code of this Font object.
     * 
     * @return the hash code of this Font object.
     */
    @Override
    public int hashCode() {
        HashCode hash = new HashCode();

        hash.append(this.name);
        hash.append(this.style);
        hash.append(this.size);

        return hash.hashCode();
    }

    /**
     * Gets the style of this Font.
     * 
     * @return the style of this Font.
     */
    public int getStyle() {
        return this.style;
    }

    /**
     * Gets the size of this Font.
     * 
     * @return the size of this Font.
     */
    public int getSize() {
        return this.size;
    }

    /**
     * Gets the number of glyphs for this Font.
     * 
     * @return the number of glyphs for this Font.
     */
    public int getNumGlyphs() {
        if (numGlyphs == -1) {
            FontPeerImpl peer = (FontPeerImpl)this.getPeer();
            this.numGlyphs = peer.getNumGlyphs();
        }
        return this.numGlyphs;
    }

    /**
     * Gets the glyphCode which is used as default glyph when this Font does not
     * have a glyph for a specified Unicode.
     * 
     * @return the missing glyph code.
     */
    public int getMissingGlyphCode() {
        if (missingGlyphCode == -1) {
            FontPeerImpl peer = (FontPeerImpl)this.getPeer();
            this.missingGlyphCode = peer.getMissingGlyphCode();
        }
        return this.missingGlyphCode;
    }

    /**
     * Gets the float value of font's size.
     * 
     * @return the float value of font's size.
     */
    public float getSize2D() {
        return this.pointSize;
    }

    /**
     * Gets the italic angle of this Font.
     * 
     * @return the italic angle of this Font.
     */
    public float getItalicAngle() {
        FontPeerImpl peer = (FontPeerImpl)this.getPeer();
        return peer.getItalicAngle();
    }

    /**
     * Creates the font with the specified font format and font file.
     * 
     * @param fontFormat
     *            the font format.
     * @param fontFile
     *            the file object represented the input data for the font.
     * @return the Font.
     * @throws FontFormatException
     *             is thrown if fontFile does not contain the required font
     *             tables for the specified format.
     * @throws IOException
     *             signals that an I/O exception has occurred.
     */
    public static Font createFont(int fontFormat, File fontFile) throws FontFormatException,
            IOException {
        // ???AWT not supported
        InputStream is = new FileInputStream(fontFile);
        try {
            return createFont(fontFormat, is);
        } finally {
            is.close();
        }
    }

    /**
     * Creates the font with the specified font format and input stream.
     * 
     * @param fontFormat
     *            the font format.
     * @param fontStream
     *            the input stream represented input data for the font.
     * @return the Font.
     * @throws FontFormatException
     *             is thrown if fontFile does not contain the required font
     *             tables for the specified format.
     * @throws IOException
     *             signals that an I/O exception has occurred.
     */
    public static Font createFont(int fontFormat, InputStream fontStream)
            throws FontFormatException, IOException {

        // ???AWT not supported

        BufferedInputStream buffStream;
        int bRead = 0;
        int size = 8192;
        // memory page size, for the faster reading
        byte buf[] = new byte[size];

        if (fontFormat != TRUETYPE_FONT) { // awt.9A=Unsupported font format
            throw new IllegalArgumentException(Messages.getString("awt.9A")); //$NON-NLS-1$ 
        }

        /* Get font file in system-specific directory */

        File fontFile = Toolkit.getDefaultToolkit().getGraphicsFactory().getFontManager()
                .getTempFontFile();

        // BEGIN android-modified
        buffStream = new BufferedInputStream(fontStream, 8192);
        // END android-modified
        FileOutputStream fOutStream = new FileOutputStream(fontFile);

        bRead = buffStream.read(buf, 0, size);

        while (bRead != -1) {
            fOutStream.write(buf, 0, bRead);
            bRead = buffStream.read(buf, 0, size);
        }

        buffStream.close();
        fOutStream.close();

        Font font = null;

        font = Toolkit.getDefaultToolkit().getGraphicsFactory().embedFont(
                fontFile.getAbsolutePath());
        if (font == null) { // awt.9B=Can't create font - bad font data
            throw new FontFormatException(Messages.getString("awt.9B")); //$NON-NLS-1$
        }
        return font;
    }

}
