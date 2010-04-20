/*
*******************************************************************************
*   Copyright (C) 2001-2009, International Business Machines
*   Corporation and others.  All Rights Reserved.
*******************************************************************************
*/

/*
 * Ported with minor modifications from ICU4J 4.2's
 * com.ibm.icu.text.ArabicShaping class.
 */

package android.icu.text;


/**
 * Shape Arabic text on a character basis.
 *
 * <p>ArabicShaping performs basic operations for "shaping" Arabic text. It is most
 * useful for use with legacy data formats and legacy display technology
 * (simple terminals). All operations are performed on Unicode characters.</p>
 *
 * <p>Text-based shaping means that some character code points in the text are
 * replaced by others depending on the context. It transforms one kind of text
 * into another. In comparison, modern displays for Arabic text select
 * appropriate, context-dependent font glyphs for each text element, which means
 * that they transform text into a glyph vector.</p>
 *
 * <p>Text transformations are necessary when modern display technology is not
 * available or when text needs to be transformed to or from legacy formats that
 * use "shaped" characters. Since the Arabic script is cursive, connecting
 * adjacent letters to each other, computers select images for each letter based
 * on the surrounding letters. This usually results in four images per Arabic
 * letter: initial, middle, final, and isolated forms. In Unicode, on the other
 * hand, letters are normally stored abstract, and a display system is expected
 * to select the necessary glyphs. (This makes searching and other text
 * processing easier because the same letter has only one code.) It is possible
 * to mimic this with text transformations because there are characters in
 * Unicode that are rendered as letters with a specific shape
 * (or cursive connectivity). They were included for interoperability with
 * legacy systems and codepages, and for unsophisticated display systems.</p>
 *
 * <p>A second kind of text transformations is supported for Arabic digits:
 * For compatibility with legacy codepages that only include European digits,
 * it is possible to replace one set of digits by another, changing the
 * character code points. These operations can be performed for either
 * Arabic-Indic Digits (U+0660...U+0669) or Eastern (Extended) Arabic-Indic
 * digits (U+06f0...U+06f9).</p>
 *
 * <p>Some replacements may result in more or fewer characters (code points).
 * By default, this means that the destination buffer may receive text with a
 * length different from the source length. Some legacy systems rely on the
 * length of the text to be constant. They expect extra spaces to be added
 * or consumed either next to the affected character or at the end of the
 * text.</p>
 * @stable ICU 2.0
 *
 * @hide
 */
public class ArabicShaping {
    private final int options;
    private boolean isLogical; // convenience
    private boolean spacesRelativeToTextBeginEnd;
    private char tailChar;

    public static final ArabicShaping SHAPER = new ArabicShaping(
            ArabicShaping.TEXT_DIRECTION_LOGICAL |
            ArabicShaping.LENGTH_FIXED_SPACES_NEAR |
            ArabicShaping.LETTERS_SHAPE |
            ArabicShaping.DIGITS_NOOP);

    /**
     * Convert a range of text in the source array, putting the result
     * into a range of text in the destination array, and return the number
     * of characters written.
     *
     * @param source An array containing the input text
     * @param sourceStart The start of the range of text to convert
     * @param sourceLength The length of the range of text to convert
     * @param dest The destination array that will receive the result.
     *   It may be <code>NULL</code> only if  <code>destSize</code> is 0.
     * @param destStart The start of the range of the destination buffer to use.
     * @param destSize The size (capacity) of the destination buffer.
     *   If <code>destSize</code> is 0, then no output is produced,
     *   but the necessary buffer size is returned ("preflighting").  This
     *   does not validate the text against the options, for example,
     *   if letters are being unshaped, and spaces are being consumed
     *   following lamalef, this will not detect a lamalef without a
     *   corresponding space.  An error will be thrown when the actual
     *   conversion is attempted.
     * @return The number of chars written to the destination buffer.
     *   If an error occurs, then no output was written, or it may be
     *   incomplete.
     * @throws ArabicShapingException if the text cannot be converted according to the options.
     * @stable ICU 2.0
     */
    public int shape(char[] source, int sourceStart, int sourceLength,
                     char[] dest, int destStart, int destSize) throws ArabicShapingException {
        if (source == null) {
            throw new IllegalArgumentException("source can not be null");
        }
        if (sourceStart < 0 || sourceLength < 0 || sourceStart + sourceLength > source.length) {
            throw new IllegalArgumentException("bad source start (" + sourceStart +
                                               ") or length (" + sourceLength +
                                               ") for buffer of length " + source.length);
        }
        if (dest == null && destSize != 0) {
            throw new IllegalArgumentException("null dest requires destSize == 0");
        }
        if ((destSize != 0) &&
            (destStart < 0 || destSize < 0 || destStart + destSize > dest.length)) {
            throw new IllegalArgumentException("bad dest start (" + destStart +
                                               ") or size (" + destSize +
                                               ") for buffer of length " + dest.length);
        }
        /* Validate input options */
        if ( ((options&TASHKEEL_MASK) > 0) &&
             !(((options & TASHKEEL_MASK)==TASHKEEL_BEGIN)  ||
               ((options & TASHKEEL_MASK)==TASHKEEL_END )   ||
               ((options & TASHKEEL_MASK)==TASHKEEL_RESIZE )||
               ((options & TASHKEEL_MASK)==TASHKEEL_REPLACE_BY_TATWEEL)) ){
            throw new IllegalArgumentException("Wrong Tashkeel argument");
        }

       ///CLOVER:OFF
       //According to Steven Loomis, the code is unreachable when you OR all the constants within the if statements
       if(((options&LAMALEF_MASK) > 0)&&
              !(((options & LAMALEF_MASK)==LAMALEF_BEGIN)  ||
                ((options & LAMALEF_MASK)==LAMALEF_END )   ||
                ((options & LAMALEF_MASK)==LAMALEF_RESIZE )||
                 ((options & LAMALEF_MASK)==LAMALEF_AUTO)  ||
                 ((options & LAMALEF_MASK)==LAMALEF_NEAR))){
           throw new IllegalArgumentException("Wrong Lam Alef argument");
       }
       ///CLOVER:ON

       /* Validate Tashkeel (Tashkeel replacement options should be enabled in shaping mode only)*/
       if(((options&TASHKEEL_MASK) > 0) && (options&LETTERS_MASK) == LETTERS_UNSHAPE) {
            throw new IllegalArgumentException("Tashkeel replacement should not be enabled in deshaping mode ");
       }
       return internalShape(source, sourceStart, sourceLength, dest, destStart, destSize);
    }

    /**
     * Convert a range of text in place.  This may only be used if the Length option
     * does not grow or shrink the text.
     *
     * @param source An array containing the input text
     * @param start The start of the range of text to convert
     * @param length The length of the range of text to convert
     * @throws ArabicShapingException if the text cannot be converted according to the options.
     * @stable ICU 2.0
     */
    public void shape(char[] source, int start, int length) throws ArabicShapingException {
        if ((options & LAMALEF_MASK) == LAMALEF_RESIZE) {
            throw new ArabicShapingException("Cannot shape in place with length option resize.");
        }
        shape(source, start, length, source, start, length);
    }

    /**
     * Convert a string, returning the new string.
     *
     * @param text the string to convert
     * @return the converted string
     * @throws ArabicShapingException if the string cannot be converted according to the options.
     * @stable ICU 2.0
     */
    public String shape(String text) throws ArabicShapingException {
        char[] src = text.toCharArray();
        char[] dest = src;
        if (((options & LAMALEF_MASK) == LAMALEF_RESIZE) &&
            ((options & LETTERS_MASK) == LETTERS_UNSHAPE)) {

            dest = new char[src.length * 2]; // max
        }
        int len = shape(src, 0, src.length, dest, 0, dest.length);

        return new String(dest, 0, len);
    }

    /**
     * Construct ArabicShaping using the options flags.
     * The flags are as follows:<br>
     * 'LENGTH' flags control whether the text can change size, and if not,
     * how to maintain the size of the text when LamAlef ligatures are
     * formed or broken.<br>
     * 'TEXT_DIRECTION' flags control whether the text is read and written
     * in visual order or in logical order.<br>
     * 'LETTERS_SHAPE' flags control whether conversion is to or from
     * presentation forms.<br>
     * 'DIGITS' flags control whether digits are shaped, and whether from
     * European to Arabic-Indic or vice-versa.<br>
     * 'DIGIT_TYPE' flags control whether standard or extended Arabic-Indic
     * digits are used when performing digit conversion.
     * @stable ICU 2.0
     */
    public ArabicShaping(int options) {
        this.options = options;
        if ((options & DIGITS_MASK) > 0x80) {
            throw new IllegalArgumentException("bad DIGITS options");
        }

        isLogical = ( (options & TEXT_DIRECTION_MASK) == TEXT_DIRECTION_LOGICAL );
        /* Validate options */
        spacesRelativeToTextBeginEnd = ( (options & SPACES_RELATIVE_TO_TEXT_MASK) == SPACES_RELATIVE_TO_TEXT_BEGIN_END );
        if ( (options&SHAPE_TAIL_TYPE_MASK) == SHAPE_TAIL_NEW_UNICODE){
            tailChar = NEW_TAIL_CHAR;
        } else {
            tailChar = OLD_TAIL_CHAR;
        }
    }

    /* Seen Tail options */
    /**
     * Memory option: the result must have the same length as the source.
     * Shaping mode: The SEEN family character will expand into two characters using space near
     *               the SEEN family character(i.e. the space after the character).
     *               if there are no spaces found, ArabicShapingException will be thrown
     *
     * De-shaping mode: Any Seen character followed by Tail character will be
     *                  replaced by one cell Seen and a space will replace the Tail.
     * Affects: Seen options
     */
    public static final int SEEN_TWOCELL_NEAR = 0x200000;

    /** Bit mask for Seen memory options. */
    public static final int SEEN_MASK = 0x700000;

    /* YehHamza options */
    /**
     * Memory option: the result must have the same length as the source.
     * Shaping mode: The YEHHAMZA character will expand into two characters using space near it
     *              (i.e. the space after the character)
     *               if there are no spaces found, ArabicShapingException will be thrown
     *
     * De-shaping mode: Any Yeh (final or isolated) character followed by Hamza character will be
     *                  replaced by one cell YehHamza and space will replace the Hamza.
     * Affects: YehHamza options
     */
    public static final int YEHHAMZA_TWOCELL_NEAR  = 0x1000000;


    /** Bit mask for YehHamza memory options. */
    public static final int YEHHAMZA_MASK = 0x3800000;

    /* New Tashkeel options */
    /**
     * Memory option: the result must have the same length as the source.
     * Shaping mode: Tashkeel characters will be replaced by spaces.
     *               Spaces will be placed at beginning of the buffer
     *
     * De-shaping mode: N/A
     * Affects: Tashkeel options
     */
    public static final int TASHKEEL_BEGIN = 0x40000;

    /**
     * Memory option: the result must have the same length as the source.
     * Shaping mode: Tashkeel characters will be replaced by spaces.
     *               Spaces will be placed at end of the buffer
     *
     * De-shaping mode: N/A
     * Affects: Tashkeel options
     */
    public static final int TASHKEEL_END = 0x60000;

    /**
     * Memory option: allow the result to have a different length than the source.
     * Shaping mode: Tashkeel characters will be removed, buffer length will shrink.
     * De-shaping mode: N/A
     *
     * Affects: Tashkeel options
     */
    public static final int TASHKEEL_RESIZE = 0x80000;

    /**
     * Memory option: the result must have the same length as the source.
     * Shaping mode: Tashkeel characters will be replaced by Tatweel if it is connected to adjacent
     *               characters (i.e. shaped on Tatweel) or replaced by space if it is not connected.
     *
     * De-shaping mode: N/A
     * Affects: YehHamza options
     */
    public static final int TASHKEEL_REPLACE_BY_TATWEEL = 0xC0000;

    /** Bit mask for Tashkeel replacement with Space or Tatweel memory options. */
    public static final int TASHKEEL_MASK  = 0xE0000;

    /* Space location Control options */
    /**
     * This option effects the meaning of BEGIN and END options. if this option is not used the default
     * for BEGIN and END will be as following:
     * The Default (for both Visual LTR, Visual RTL and Logical Text)
     *           1. BEGIN always refers to the start address of physical memory.
     *           2. END always refers to the end address of physical memory.
     *
     * If this option is used it will swap the meaning of BEGIN and END only for Visual LTR text.
     *
     * The affect on BEGIN and END Memory Options will be as following:
     *    A. BEGIN For Visual LTR text: This will be the beginning (right side) of the visual text
     *       (corresponding to the physical memory address end, same as END in default behavior)
     *    B. BEGIN For Logical text: Same as BEGIN in default behavior.
     *    C. END For Visual LTR text: This will be the end (left side) of the visual text. (corresponding to
     *      the physical memory address beginning, same as BEGIN in default behavior)
     *    D. END For Logical text: Same as END in default behavior.
     * Affects: All LamAlef BEGIN, END and AUTO options.
     */
    public static final int SPACES_RELATIVE_TO_TEXT_BEGIN_END = 0x4000000;

    /** Bit mask for swapping BEGIN and END for Visual LTR text */
    public static final int SPACES_RELATIVE_TO_TEXT_MASK = 0x4000000;

    /**
     * If this option is used, shaping will use the new Unicode code point for TAIL (i.e. 0xFE73).
     * If this option is not specified (Default), old unofficial Unicode TAIL code point is used (i.e. 0x200B)
     * De-shaping will not use this option as it will always search for both the new Unicode code point for the
     * TAIL (i.e. 0xFE73) or the old unofficial Unicode TAIL code point (i.e. 0x200B) and de-shape the
     * Seen-Family letter accordingly.
     *
     * Shaping Mode: Only shaping.
     * De-shaping Mode: N/A.
     * Affects: All Seen options
     */
    public static final int SHAPE_TAIL_NEW_UNICODE = 0x8000000;

    /** Bit mask for new Unicode Tail option */
    public static final int SHAPE_TAIL_TYPE_MASK = 0x8000000;

    /**
     * Memory option: allow the result to have a different length than the source.
     * @stable ICU 2.0
     */
    public static final int LENGTH_GROW_SHRINK = 0;

    /**
     * Memory option: allow the result to have a different length than the source.
     * Affects: LamAlef options
     * This option is an alias to LENGTH_GROW_SHRINK
     */
    public static final int LAMALEF_RESIZE   = 0;

    /**
     * Memory option: the result must have the same length as the source.
     * If more room is necessary, then try to consume spaces next to modified characters.
     * @stable ICU 2.0
     */
    public static final int LENGTH_FIXED_SPACES_NEAR = 1;

    /**
     * Memory option: the result must have the same length as the source.
     * If more room is necessary, then try to consume spaces next to modified characters.
     * Affects: LamAlef options
     * This option is an alias to LENGTH_FIXED_SPACES_NEAR
     */
    public static final int LAMALEF_NEAR = 1 ;

    /**
     * Memory option: the result must have the same length as the source.
     * If more room is necessary, then try to consume spaces at the end of the text.
     * @stable ICU 2.0
     */
    public static final int LENGTH_FIXED_SPACES_AT_END = 2;


    /**
     * Memory option: the result must have the same length as the source.
     * If more room is necessary, then try to consume spaces at the end of the text.
     * Affects: LamAlef options
     * This option is an alias to LENGTH_FIXED_SPACES_AT_END
     */
    public static final int LAMALEF_END = 2;

    /**
     * Memory option: the result must have the same length as the source.
     * If more room is necessary, then try to consume spaces at the beginning of the text.
     * @stable ICU 2.0
     */
    public static final int LENGTH_FIXED_SPACES_AT_BEGINNING = 3;

    /**
     * Memory option: the result must have the same length as the source.
     * If more room is necessary, then try to consume spaces at the beginning of the text.
     * Affects: LamAlef options
     * This option is an alias to LENGTH_FIXED_SPACES_AT_BEGINNING
     */
    public static final int LAMALEF_BEGIN = 3;

    /**
     * Memory option: the result must have the same length as the source.
     * Shaping Mode: For each LAMALEF character found, expand LAMALEF using space at end.
     *               If there is no space at end, use spaces at beginning of the buffer. If there
     *               is no space at beginning of the buffer, use spaces at the near (i.e. the space
     *               after the LAMALEF character).
     *
     * Deshaping Mode: Perform the same function as the flag equals LAMALEF_END.
     * Affects: LamAlef options
     */
    public static final int LAMALEF_AUTO  = 0x10000;

    /**
     * Bit mask for memory options.
     * @stable ICU 2.0
     */
    public static final int LENGTH_MASK = 0x10003;

    /** Bit mask for LamAlef memory options. */

    public static final int LAMALEF_MASK  = 0x10003;

    /**
     * Direction indicator: the source is in logical (keyboard) order.
     * @stable ICU 2.0
     */
    public static final int TEXT_DIRECTION_LOGICAL = 0;

    /**
     * Direction indicator:the source is in visual RTL order,
     * the rightmost displayed character stored first.
     * This option is an alias to U_SHAPE_TEXT_DIRECTION_LOGICAL
     */
    public static final int TEXT_DIRECTION_VISUAL_RTL = 0;

    /**
     * Direction indicator: the source is in visual (display) order, that is,
     * the leftmost displayed character is stored first.
     * @stable ICU 2.0
     */
    public static final int TEXT_DIRECTION_VISUAL_LTR = 4;

    /**
     * Bit mask for direction indicators.
     * @stable ICU 2.0
     */
    public static final int TEXT_DIRECTION_MASK = 4;


    /**
     * Letter shaping option: do not perform letter shaping.
     * @stable ICU 2.0
     */
    public static final int LETTERS_NOOP = 0;

    /**
     * Letter shaping option: replace normative letter characters in the U+0600 (Arabic) block,
     * by shaped ones in the U+FE70 (Presentation Forms B) block. Performs Lam-Alef ligature
     * substitution.
     * @stable ICU 2.0
     */
    public static final int LETTERS_SHAPE = 8;

    /**
     * Letter shaping option: replace shaped letter characters in the U+FE70 (Presentation Forms B) block
     * by normative ones in the U+0600 (Arabic) block.  Converts Lam-Alef ligatures to pairs of Lam and
     * Alef characters, consuming spaces if required.
     * @stable ICU 2.0
     */
    public static final int LETTERS_UNSHAPE = 0x10;

    /**
     * Letter shaping option: replace normative letter characters in the U+0600 (Arabic) block,
     * except for the TASHKEEL characters at U+064B...U+0652, by shaped ones in the U+Fe70
     * (Presentation Forms B) block.  The TASHKEEL characters will always be converted to
     * the isolated forms rather than to their correct shape.
     * @stable ICU 2.0
     */
    public static final int LETTERS_SHAPE_TASHKEEL_ISOLATED = 0x18;

    /**
     * Bit mask for letter shaping options.
     * @stable ICU 2.0
     */
    public static final int LETTERS_MASK = 0x18;


    /**
     * Digit shaping option: do not perform digit shaping.
     * @stable ICU 2.0
     */
    public static final int DIGITS_NOOP = 0;

    /**
     * Digit shaping option: Replace European digits (U+0030...U+0039) by Arabic-Indic digits.
     * @stable ICU 2.0
     */
    public static final int DIGITS_EN2AN = 0x20;

    /**
     * Digit shaping option: Replace Arabic-Indic digits by European digits (U+0030...U+0039).
     * @stable ICU 2.0
     */
    public static final int DIGITS_AN2EN = 0x40;

    /**
     * Digit shaping option:
     * Replace European digits (U+0030...U+0039) by Arabic-Indic digits
     * if the most recent strongly directional character
     * is an Arabic letter (its Bidi direction value is RIGHT_TO_LEFT_ARABIC).
     * The initial state at the start of the text is assumed to be not an Arabic,
     * letter, so European digits at the start of the text will not change.
     * Compare to DIGITS_ALEN2AN_INIT_AL.
     * @stable ICU 2.0
     */
    public static final int DIGITS_EN2AN_INIT_LR = 0x60;

    /**
     * Digit shaping option:
     * Replace European digits (U+0030...U+0039) by Arabic-Indic digits
     * if the most recent strongly directional character
     * is an Arabic letter (its Bidi direction value is RIGHT_TO_LEFT_ARABIC).
     * The initial state at the start of the text is assumed to be an Arabic,
     * letter, so European digits at the start of the text will change.
     * Compare to DIGITS_ALEN2AN_INT_LR.
     * @stable ICU 2.0
     */
    public static final int DIGITS_EN2AN_INIT_AL = 0x80;

    /** Not a valid option value. */
    //private static final int DIGITS_RESERVED = 0xa0;

    /**
     * Bit mask for digit shaping options.
     * @stable ICU 2.0
     */
    public static final int DIGITS_MASK = 0xe0;

    /**
     * Digit type option: Use Arabic-Indic digits (U+0660...U+0669).
     * @stable ICU 2.0
     */
    public static final int DIGIT_TYPE_AN = 0;

    /**
     * Digit type option: Use Eastern (Extended) Arabic-Indic digits (U+06f0...U+06f9).
     * @stable ICU 2.0
     */
    public static final int DIGIT_TYPE_AN_EXTENDED = 0x100;

    /**
     * Bit mask for digit type options.
     * @stable ICU 2.0
     */
    public static final int DIGIT_TYPE_MASK = 0x0100; // 0x3f00?

    /**
     * some constants
     */
    private static final char HAMZAFE_CHAR       = '\ufe80';
    private static final char HAMZA06_CHAR       = '\u0621';
    private static final char YEH_HAMZA_CHAR     = '\u0626';
    private static final char YEH_HAMZAFE_CHAR   = '\uFE89';
    private static final char LAMALEF_SPACE_SUB  = '\uffff';
    private static final char TASHKEEL_SPACE_SUB = '\ufffe';
    private static final char LAM_CHAR      = '\u0644';
    private static final char SPACE_CHAR    = '\u0020';
    private static final char SPACE_CHAR_FOR_LAMALEF = '\ufeff'; // XXX: tweak for TextLine use
    private static final char SHADDA_CHAR   = '\uFE7C';
    private static final char TATWEEL_CHAR  = '\u0640';
    private static final char SHADDA_TATWEEL_CHAR = '\uFE7D';
    private static final char NEW_TAIL_CHAR = '\uFE73';
    private static final char OLD_TAIL_CHAR = '\u200B';
    private static final int SHAPE_MODE      = 0;
    private static final int DESHAPE_MODE    = 1;

    /**
     * @stable ICU 2.0
     */
    public boolean equals(Object rhs) {
        return rhs != null &&
            rhs.getClass() == ArabicShaping.class &&
            options == ((ArabicShaping)rhs).options;
    }

    /**
     * @stable ICU 2.0
     */
     ///CLOVER:OFF
    public int hashCode() {
        return options;
    }

    /**
     * @stable ICU 2.0
     */
    public String toString() {
        StringBuffer buf = new StringBuffer(super.toString());
        buf.append('[');

        switch (options & LAMALEF_MASK) {
        case LAMALEF_RESIZE: buf.append("LamAlef resize"); break;
        case LAMALEF_NEAR: buf.append("LamAlef spaces at near"); break;
        case LAMALEF_BEGIN: buf.append("LamAlef spaces at begin"); break;
        case LAMALEF_END: buf.append("LamAlef spaces at end"); break;
        case LAMALEF_AUTO: buf.append("lamAlef auto"); break;
        }
        switch (options & TEXT_DIRECTION_MASK) {
        case TEXT_DIRECTION_LOGICAL: buf.append(", logical"); break;
        case TEXT_DIRECTION_VISUAL_LTR: buf.append(", visual"); break;
        }
        switch (options & LETTERS_MASK) {
        case LETTERS_NOOP: buf.append(", no letter shaping"); break;
        case LETTERS_SHAPE: buf.append(", shape letters"); break;
        case LETTERS_SHAPE_TASHKEEL_ISOLATED: buf.append(", shape letters tashkeel isolated"); break;
        case LETTERS_UNSHAPE: buf.append(", unshape letters"); break;
        }
        switch (options & SEEN_MASK) {
        case SEEN_TWOCELL_NEAR: buf.append(", Seen at near"); break;
        }
        switch (options & YEHHAMZA_MASK) {
        case YEHHAMZA_TWOCELL_NEAR: buf.append(", Yeh Hamza at near"); break;
        }
        switch (options & TASHKEEL_MASK) {
        case TASHKEEL_BEGIN: buf.append(", Tashkeel at begin"); break;
        case TASHKEEL_END: buf.append(", Tashkeel at end"); break;
        case TASHKEEL_REPLACE_BY_TATWEEL: buf.append(", Tashkeel replace with tatweel"); break;
        case TASHKEEL_RESIZE: buf.append(", Tashkeel resize"); break;
        }

        switch (options & DIGITS_MASK) {
        case DIGITS_NOOP: buf.append(", no digit shaping"); break;
        case DIGITS_EN2AN: buf.append(", shape digits to AN"); break;
        case DIGITS_AN2EN: buf.append(", shape digits to EN"); break;
        case DIGITS_EN2AN_INIT_LR: buf.append(", shape digits to AN contextually: default EN"); break;
        case DIGITS_EN2AN_INIT_AL: buf.append(", shape digits to AN contextually: default AL"); break;
        }
        switch (options & DIGIT_TYPE_MASK) {
        case DIGIT_TYPE_AN: buf.append(", standard Arabic-Indic digits"); break;
        case DIGIT_TYPE_AN_EXTENDED: buf.append(", extended Arabic-Indic digits"); break;
        }
        buf.append("]");

        return buf.toString();
    }
    ///CLOVER:ON

    //
    // ported api
    //

    private static final int IRRELEVANT = 4;
    private static final int LAMTYPE = 16;
    private static final int ALEFTYPE = 32;

    private static final int LINKR = 1;
    private static final int LINKL = 2;
    private static final int LINK_MASK = 3;

    private static final int irrelevantPos[] = {
        0x0, 0x2, 0x4, 0x6, 0x8, 0xA, 0xC, 0xE
    };

/*
    private static final char convertLamAlef[] =  {
        '\u0622', // FEF5
        '\u0622', // FEF6
        '\u0623', // FEF7
        '\u0623', // FEF8
        '\u0625', // FEF9
        '\u0625', // FEFA
        '\u0627', // FEFB
        '\u0627'  // FEFC
    };
*/

    private static final int tailFamilyIsolatedFinal[] = {
        /* FEB1 */ 1,
        /* FEB2 */ 1,
        /* FEB3 */ 0,
        /* FEB4 */ 0,
        /* FEB5 */ 1,
        /* FEB6 */ 1,
        /* FEB7 */ 0,
        /* FEB8 */ 0,
        /* FEB9 */ 1,
        /* FEBA */ 1,
        /* FEBB */ 0,
        /* FEBC */ 0,
        /* FEBD */ 1,
        /* FEBE */ 1
    };

    private static final int tashkeelMedial[] = {
        /* FE70 */ 0,
        /* FE71 */ 1,
        /* FE72 */ 0,
        /* FE73 */ 0,
        /* FE74 */ 0,
        /* FE75 */ 0,
        /* FE76 */ 0,
        /* FE77 */ 1,
        /* FE78 */ 0,
        /* FE79 */ 1,
        /* FE7A */ 0,
        /* FE7B */ 1,
        /* FE7C */ 0,
        /* FE7D */ 1,
        /* FE7E */ 0,
        /* FE7F */ 1
    };

    private static final char yehHamzaToYeh[] =
    {
    /* isolated*/ 0xFEEF,
    /* final   */ 0xFEF0
    };

    private static final char convertNormalizedLamAlef[] = {
        '\u0622', // 065C
        '\u0623', // 065D
        '\u0625', // 065E
        '\u0627', // 065F
    };

    private static final int[] araLink = {
        1           + 32 + 256 * 0x11,  /*0x0622*/
        1           + 32 + 256 * 0x13,  /*0x0623*/
        1                + 256 * 0x15,  /*0x0624*/
        1           + 32 + 256 * 0x17,  /*0x0625*/
        1 + 2            + 256 * 0x19,  /*0x0626*/
        1           + 32 + 256 * 0x1D,  /*0x0627*/
        1 + 2            + 256 * 0x1F,  /*0x0628*/
        1                + 256 * 0x23,  /*0x0629*/
        1 + 2            + 256 * 0x25,  /*0x062A*/
        1 + 2            + 256 * 0x29,  /*0x062B*/
        1 + 2            + 256 * 0x2D,  /*0x062C*/
        1 + 2            + 256 * 0x31,  /*0x062D*/
        1 + 2            + 256 * 0x35,  /*0x062E*/
        1                + 256 * 0x39,  /*0x062F*/
        1                + 256 * 0x3B,  /*0x0630*/
        1                + 256 * 0x3D,  /*0x0631*/
        1                + 256 * 0x3F,  /*0x0632*/
        1 + 2            + 256 * 0x41,  /*0x0633*/
        1 + 2            + 256 * 0x45,  /*0x0634*/
        1 + 2            + 256 * 0x49,  /*0x0635*/
        1 + 2            + 256 * 0x4D,  /*0x0636*/
        1 + 2            + 256 * 0x51,  /*0x0637*/
        1 + 2            + 256 * 0x55,  /*0x0638*/
        1 + 2            + 256 * 0x59,  /*0x0639*/
        1 + 2            + 256 * 0x5D,  /*0x063A*/
        0, 0, 0, 0, 0,                  /*0x063B-0x063F*/
        1 + 2,                          /*0x0640*/
        1 + 2            + 256 * 0x61,  /*0x0641*/
        1 + 2            + 256 * 0x65,  /*0x0642*/
        1 + 2            + 256 * 0x69,  /*0x0643*/
        1 + 2       + 16 + 256 * 0x6D,  /*0x0644*/
        1 + 2            + 256 * 0x71,  /*0x0645*/
        1 + 2            + 256 * 0x75,  /*0x0646*/
        1 + 2            + 256 * 0x79,  /*0x0647*/
        1                + 256 * 0x7D,  /*0x0648*/
        1                + 256 * 0x7F,  /*0x0649*/
        1 + 2            + 256 * 0x81,  /*0x064A*/
        4, 4, 4, 4,                     /*0x064B-0x064E*/
        4, 4, 4, 4,                     /*0x064F-0x0652*/
        4, 4, 4, 0, 0,                  /*0x0653-0x0657*/
        0, 0, 0, 0,                     /*0x0658-0x065B*/
        1                + 256 * 0x85,  /*0x065C*/
        1                + 256 * 0x87,  /*0x065D*/
        1                + 256 * 0x89,  /*0x065E*/
        1                + 256 * 0x8B,  /*0x065F*/
        0, 0, 0, 0, 0,                  /*0x0660-0x0664*/
        0, 0, 0, 0, 0,                  /*0x0665-0x0669*/
        0, 0, 0, 0, 0, 0,               /*0x066A-0x066F*/
        4,                              /*0x0670*/
        0,                              /*0x0671*/
        1           + 32,               /*0x0672*/
        1           + 32,               /*0x0673*/
        0,                              /*0x0674*/
        1           + 32,               /*0x0675*/
        1, 1,                           /*0x0676-0x0677*/
        1+2, 1+2, 1+2, 1+2, 1+2, 1+2,   /*0x0678-0x067D*/
        1+2, 1+2, 1+2, 1+2, 1+2, 1+2,   /*0x067E-0x0683*/
        1+2, 1+2, 1+2, 1+2,             /*0x0684-0x0687*/
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1,   /*0x0688-0x0691*/
        1, 1, 1, 1, 1, 1, 1, 1,         /*0x0692-0x0699*/
        1+2, 1+2, 1+2, 1+2, 1+2, 1+2,   /*0x069A-0x06A3*/
        1+2, 1+2, 1+2, 1+2,             /*0x069A-0x06A3*/
        1+2, 1+2, 1+2, 1+2, 1+2, 1+2,   /*0x06A4-0x06AD*/
        1+2, 1+2, 1+2, 1+2,             /*0x06A4-0x06AD*/
        1+2, 1+2, 1+2, 1+2, 1+2, 1+2,   /*0x06AE-0x06B7*/
        1+2, 1+2, 1+2, 1+2,             /*0x06AE-0x06B7*/
        1+2, 1+2, 1+2, 1+2, 1+2, 1+2,   /*0x06B8-0x06BF*/
        1+2, 1+2,                       /*0x06B8-0x06BF*/
        1,                              /*0x06C0*/
        1+2,                            /*0x06C1*/
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1,   /*0x06C2-0x06CB*/
        1+2,                            /*0x06CC*/
        1,                              /*0x06CD*/
        1+2, 1+2, 1+2, 1+2,             /*0x06CE-0x06D1*/
        1, 1                            /*0x06D2-0x06D3*/
    };

    private static final int[] presLink = {
        1 + 2,                        /*0xFE70*/
        1 + 2,                        /*0xFE71*/
        1 + 2, 0, 1+ 2, 0, 1+ 2,      /*0xFE72-0xFE76*/
        1 + 2,                        /*0xFE77*/
        1+ 2, 1 + 2, 1+2, 1 + 2,      /*0xFE78-0xFE81*/
        1+ 2, 1 + 2, 1+2, 1 + 2,      /*0xFE82-0xFE85*/
        0, 0 + 32, 1 + 32, 0 + 32,    /*0xFE86-0xFE89*/
        1 + 32, 0, 1,  0 + 32,        /*0xFE8A-0xFE8D*/
        1 + 32, 0, 2,  1 + 2,         /*0xFE8E-0xFE91*/
        1, 0 + 32, 1 + 32, 0,         /*0xFE92-0xFE95*/
        2, 1 + 2, 1, 0,               /*0xFE96-0xFE99*/
        1, 0, 2, 1 + 2,               /*0xFE9A-0xFE9D*/
        1, 0, 2, 1 + 2,               /*0xFE9E-0xFEA1*/
        1, 0, 2, 1 + 2,               /*0xFEA2-0xFEA5*/
        1, 0, 2, 1 + 2,               /*0xFEA6-0xFEA9*/
        1, 0, 2, 1 + 2,               /*0xFEAA-0xFEAD*/
        1, 0, 1, 0,                   /*0xFEAE-0xFEB1*/
        1, 0, 1, 0,                   /*0xFEB2-0xFEB5*/
        1, 0, 2, 1+2,                 /*0xFEB6-0xFEB9*/
        1, 0, 2, 1+2,                 /*0xFEBA-0xFEBD*/
        1, 0, 2, 1+2,                 /*0xFEBE-0xFEC1*/
        1, 0, 2, 1+2,                 /*0xFEC2-0xFEC5*/
        1, 0, 2, 1+2,                 /*0xFEC6-0xFEC9*/
        1, 0, 2, 1+2,                 /*0xFECA-0xFECD*/
        1, 0, 2, 1+2,                 /*0xFECE-0xFED1*/
        1, 0, 2, 1+2,                 /*0xFED2-0xFED5*/
        1, 0, 2, 1+2,                 /*0xFED6-0xFED9*/
        1, 0, 2, 1+2,                 /*0xFEDA-0xFEDD*/
        1, 0, 2, 1+2,                 /*0xFEDE-0xFEE1*/
        1, 0 + 16, 2 + 16, 1 + 2 +16, /*0xFEE2-0xFEE5*/
        1 + 16, 0, 2, 1+2,            /*0xFEE6-0xFEE9*/
        1, 0, 2, 1+2,                 /*0xFEEA-0xFEED*/
        1, 0, 2, 1+2,                 /*0xFEEE-0xFEF1*/
        1, 0, 1, 0,                   /*0xFEF2-0xFEF5*/
        1, 0, 2, 1+2,                 /*0xFEF6-0xFEF9*/
        1, 0, 1, 0,                   /*0xFEFA-0xFEFD*/
        1, 0, 1, 0,
        1
    };

    private static int[] convertFEto06 = {
        /***********0******1******2******3******4******5******6******7******8******9******A******B******C******D******E******F***/
        /*FE7*/   0x64B, 0x64B, 0x64C, 0x64C, 0x64D, 0x64D, 0x64E, 0x64E, 0x64F, 0x64F, 0x650, 0x650, 0x651, 0x651, 0x652, 0x652,
        /*FE8*/   0x621, 0x622, 0x622, 0x623, 0x623, 0x624, 0x624, 0x625, 0x625, 0x626, 0x626, 0x626, 0x626, 0x627, 0x627, 0x628,
        /*FE9*/   0x628, 0x628, 0x628, 0x629, 0x629, 0x62A, 0x62A, 0x62A, 0x62A, 0x62B, 0x62B, 0x62B, 0x62B, 0x62C, 0x62C, 0x62C,
        /*FEA*/   0x62C, 0x62D, 0x62D, 0x62D, 0x62D, 0x62E, 0x62E, 0x62E, 0x62E, 0x62F, 0x62F, 0x630, 0x630, 0x631, 0x631, 0x632,
        /*FEB*/   0x632, 0x633, 0x633, 0x633, 0x633, 0x634, 0x634, 0x634, 0x634, 0x635, 0x635, 0x635, 0x635, 0x636, 0x636, 0x636,
        /*FEC*/   0x636, 0x637, 0x637, 0x637, 0x637, 0x638, 0x638, 0x638, 0x638, 0x639, 0x639, 0x639, 0x639, 0x63A, 0x63A, 0x63A,
        /*FED*/   0x63A, 0x641, 0x641, 0x641, 0x641, 0x642, 0x642, 0x642, 0x642, 0x643, 0x643, 0x643, 0x643, 0x644, 0x644, 0x644,
        /*FEE*/   0x644, 0x645, 0x645, 0x645, 0x645, 0x646, 0x646, 0x646, 0x646, 0x647, 0x647, 0x647, 0x647, 0x648, 0x648, 0x649,
        /*FEF*/   0x649, 0x64A, 0x64A, 0x64A, 0x64A, 0x65C, 0x65C, 0x65D, 0x65D, 0x65E, 0x65E, 0x65F, 0x65F
    };

    private static final int shapeTable[][][] = {
        { {0,0,0,0}, {0,0,0,0}, {0,1,0,3}, {0,1,0,1} },
        { {0,0,2,2}, {0,0,1,2}, {0,1,1,2}, {0,1,1,3} },
        { {0,0,0,0}, {0,0,0,0}, {0,1,0,3}, {0,1,0,3} },
        { {0,0,1,2}, {0,0,1,2}, {0,1,1,2}, {0,1,1,3} }
    };

    /*
     * This function shapes European digits to Arabic-Indic digits
     * in-place, writing over the input characters.  Data is in visual
     * order.
     */
    private void shapeToArabicDigitsWithContext(char[] dest,
                                                int start,
                                                int length,
                                                char digitBase,
                                                boolean lastStrongWasAL) {
        digitBase -= '0'; // move common adjustment out of loop

        for(int i = start + length; --i >= start;) {
            char ch = dest[i];
            switch (Character.getDirectionality(ch)) {
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT:
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT:
                lastStrongWasAL = false;
                break;
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
                lastStrongWasAL = true;
                break;
            case Character.DIRECTIONALITY_EUROPEAN_NUMBER:
                if (lastStrongWasAL && ch <= '\u0039') {
                    dest[i] = (char)(ch + digitBase);
                }
                break;
            default:
                break;
            }
        }
    }

    /*
     * Name    : invertBuffer
     * Function: This function inverts the buffer, it's used
     *           in case the user specifies the buffer to be
     *           TEXT_DIRECTION_LOGICAL
     */
    private static void invertBuffer(char[] buffer,
                                     int start,
                                     int length) {

        for(int i = start, j = start + length - 1; i < j; i++, --j) {
            char temp = buffer[i];
            buffer[i] = buffer[j];
            buffer[j] = temp;
        }
    }

    /*
     * Name    : changeLamAlef
     * Function: Converts the Alef characters into an equivalent
     *           LamAlef location in the 0x06xx Range, this is an
     *           intermediate stage in the operation of the program
     *           later it'll be converted into the 0xFExx LamAlefs
     *           in the shaping function.
     */
    private static char changeLamAlef(char ch) {
        switch(ch) {
        case '\u0622': return '\u065C';
        case '\u0623': return '\u065D';
        case '\u0625': return '\u065E';
        case '\u0627': return '\u065F';
        default:  return '\u0000'; // not a lamalef
        }
    }

    /*
     * Name    : specialChar
     * Function: Special Arabic characters need special handling in the shapeUnicode
     *           function, this function returns 1 or 2 for these special characters
     */
    private static int specialChar(char ch) {
        if ((ch > '\u0621' && ch < '\u0626') ||
            (ch == '\u0627') ||
            (ch > '\u062E' && ch < '\u0633') ||
            (ch > '\u0647' && ch < '\u064A') ||
            (ch == '\u0629')) {
            return 1;
        } else if (ch >= '\u064B' && ch<= '\u0652') {
            return 2;
        } else if (ch >= 0x0653 && ch <= 0x0655 ||
                   ch == 0x0670 ||
                   ch >= 0xFE70 && ch <= 0xFE7F) {
            return 3;
        } else {
            return 0;
        }
    }

    /*
     * Name    : getLink
     * Function: Resolves the link between the characters as
     *           Arabic characters have four forms :
     *           Isolated, Initial, Middle and Final Form
     */
    private static int getLink(char ch) {
        if (ch >= '\u0622' && ch <= '\u06D3') {
            return araLink[ch - '\u0622'];
        } else if (ch == '\u200D') {
            return 3;
        } else if (ch >= '\u206D' && ch <= '\u206F') {
            return 4;
        } else if (ch >= '\uFE70' && ch <= '\uFEFC') {
            return presLink[ch - '\uFE70'];
        } else {
            return 0;
        }
    }

    /*
     * Name    : countSpaces
     * Function: Counts the number of spaces
     *           at each end of the logical buffer
     */
    private static int countSpacesLeft(char[] dest,
                                       int start,
                                       int count) {
        for (int i = start, e = start + count; i < e; ++i) {
            if (dest[i] != SPACE_CHAR) {
                return i - start;
            }
        }
        return count;
    }

    private static int countSpacesRight(char[] dest,
                                        int start,
                                        int count) {

        for (int i = start + count; --i >= start;) {
            if (dest[i] != SPACE_CHAR) {
                return start + count - 1 - i;
            }
        }
        return count;
    }

    /*
     * Name    : isTashkeelChar
     * Function: Returns true for Tashkeel characters else return false
     */
    private static boolean isTashkeelChar(char ch) {
        return ( ch >='\u064B' && ch <= '\u0652' );
    }

    /*
     *Name     : isSeenTailFamilyChar
     *Function : returns 1 if the character is a seen family isolated character
     *           in the FE range otherwise returns 0
     */

    private static int isSeenTailFamilyChar(char ch) {
        if (ch >= 0xfeb1 && ch < 0xfebf){
             return tailFamilyIsolatedFinal [ch - 0xFEB1];
        } else {
             return 0;
        }
    }

     /* Name     : isSeenFamilyChar
      * Function : returns 1 if the character is a seen family character in the Unicode
      *            06 range otherwise returns 0
     */

    private static int isSeenFamilyChar(char  ch){
        if (ch >= 0x633 && ch <= 0x636){
            return 1;
        }else {
            return 0;
        }
    }

    /*
     *Name     : isTailChar
     *Function : returns true if the character matches one of the tail characters
     *           (0xfe73 or 0x200b) otherwise returns false
     */

    private static boolean isTailChar(char ch) {
        if(ch == OLD_TAIL_CHAR || ch == NEW_TAIL_CHAR){
                return true;
        }else{
                return false;
        }
    }

    /*
     *Name     : isAlefMaksouraChar
     *Function : returns true if the character is a Alef Maksoura Final or isolated
     *           otherwise returns false
     */
    private static boolean isAlefMaksouraChar(char ch) {
        return ( (ch == 0xFEEF) || ( ch == 0xFEF0) || (ch == 0x0649));
    }

    /*
     * Name     : isYehHamzaChar
     * Function : returns true if the character is a yehHamza isolated or yehhamza
     *            final is found otherwise returns false
     */
    private static boolean isYehHamzaChar(char ch) {
        if((ch==0xFE89)||(ch==0xFE8A)){
            return true;
        }else{
            return false;
        }
    }

    /*
     *Name     : isTashkeelCharFE
     *Function : Returns true for Tashkeel characters in FE range else return false
     */

    private static boolean isTashkeelCharFE(char ch) {
        return ( ch!=0xFE75 &&(ch>=0xFE70 && ch<= 0xFE7F) );
    }

    /*
     * Name: isTashkeelOnTatweelChar
     * Function: Checks if the Tashkeel Character is on Tatweel or not,if the
     *           Tashkeel on tatweel (FE range), it returns 1 else if the
     *           Tashkeel with shadda on tatweel (FC range)return 2 otherwise
     *           returns 0
     */
    private static int isTashkeelOnTatweelChar(char ch){
        if (ch >= 0xfe70 && ch <= 0xfe7f && ch != NEW_TAIL_CHAR && ch != 0xFE75 && ch != SHADDA_TATWEEL_CHAR)
        {
            return tashkeelMedial [ch - 0xFE70];
        } else if( (ch >= 0xfcf2 && ch <= 0xfcf4) || (ch == SHADDA_TATWEEL_CHAR)) {
            return 2;
        } else {
            return 0;
        }
    }

    /*
     * Name: isIsolatedTashkeelChar
     * Function: Checks if the Tashkeel Character is in the isolated form
     *           (i.e. Unicode FE range) returns 1 else if the Tashkeel
     *           with shadda is in the isolated form (i.e. Unicode FC range)
     *           returns 1 otherwise returns 0
     */
    private static int isIsolatedTashkeelChar(char ch){
        if (ch >= 0xfe70 && ch <= 0xfe7f && ch != NEW_TAIL_CHAR && ch != 0xFE75){
            return (1 - tashkeelMedial [ch - 0xFE70]);
        } else if(ch >= 0xfc5e && ch <= 0xfc63){
            return 1;
        } else{
            return 0;
        }
    }

    /*
     * Name    : isAlefChar
     * Function: Returns 1 for Alef characters else return 0
     */
    private static boolean isAlefChar(char ch) {
        return ch == '\u0622' || ch == '\u0623' || ch == '\u0625' || ch == '\u0627';
    }

    /*
     * Name    : isLamAlefChar
     * Function: Returns true for LamAlef characters else return false
     */
    private static boolean isLamAlefChar(char ch) {
        return ch >= '\uFEF5' && ch <= '\uFEFC';
    }

    private static boolean isNormalizedLamAlefChar(char ch) {
        return ch >= '\u065C' && ch <= '\u065F';
    }

    /*
     * Name    : calculateSize
     * Function: This function calculates the destSize to be used in preflighting
     *           when the destSize is equal to 0
     */
    private int calculateSize(char[] source,
                              int sourceStart,
                              int sourceLength) {

        int destSize = sourceLength;

        switch (options & LETTERS_MASK) {
        case LETTERS_SHAPE:
        case LETTERS_SHAPE_TASHKEEL_ISOLATED:
            if (isLogical) {
                for (int i = sourceStart, e = sourceStart + sourceLength - 1; i < e; ++i) {
                    if ((source[i] == LAM_CHAR && isAlefChar(source[i+1])) || isTashkeelCharFE(source[i])){
                        --destSize;
                    }
                }
            } else { // visual
                for(int i = sourceStart + 1, e = sourceStart + sourceLength; i < e; ++i) {
                    if ((source[i] == LAM_CHAR && isAlefChar(source[i-1])) || isTashkeelCharFE(source[i])) {
                        --destSize;
                    }
                }
            }
            break;

        case LETTERS_UNSHAPE:
            for(int i = sourceStart, e = sourceStart + sourceLength; i < e; ++i) {
                if (isLamAlefChar(source[i])) {
                    destSize++;
                }
            }
            break;

        default:
            break;
        }

        return destSize;
    }


    /*
     * Name    : countSpaceSub
     * Function: Counts number of times the subChar appears in the array
     */
    public static int countSpaceSub(char [] dest,int length, char subChar){
        int i = 0;
        int count = 0;
        while (i < length) {
          if (dest[i] == subChar) {
              count++;
              }
          i++;
        }
        return count;
    }

    /*
     * Name    : shiftArray
     * Function: Shifts characters to replace space sub characters
     */
    public static void shiftArray(char [] dest,int start, int e, char subChar){
        int w = e;
        int r = e;
        while (--r >= start) {
          char ch = dest[r];
          if (ch != subChar) {
            --w;
            if (w != r) {
              dest[w] = ch;
            }
          }
        }
   }

    /*
     * Name    : flipArray
     * Function: inverts array, so that start becomes end and vice versa
     */
      public static int flipArray(char [] dest, int start, int e, int w){
        int r;
        if (w > start) {
        // shift, assume small buffer size so don't use arraycopy
          r = w;
          w = start;
          while (r < e) {
            dest[w++] = dest[r++];
           }
         } else {
             w = e;
         }
        return w;
      }

    /*
     * Name     : handleTashkeelWithTatweel
     * Function : Replaces Tashkeel as following:
     *            Case 1 :if the Tashkeel on tatweel, replace it with Tatweel.
     *            Case 2 :if the Tashkeel aggregated with Shadda on Tatweel, replace
     *                   it with Shadda on Tatweel.
     *            Case 3: if the Tashkeel is isolated replace it with Space.
     *
     */
    private static int handleTashkeelWithTatweel(char[] dest, int sourceLength) {
                     int i;
                     for(i = 0; i < sourceLength; i++){
                         if((isTashkeelOnTatweelChar(dest[i]) == 1)){
                             dest[i] = TATWEEL_CHAR;
                        }else if((isTashkeelOnTatweelChar(dest[i]) == 2)){
                             dest[i] = SHADDA_TATWEEL_CHAR;
                        }else if((isIsolatedTashkeelChar(dest[i])==1) && dest[i] != SHADDA_CHAR){
                             dest[i] = SPACE_CHAR;
                        }
                     }
                     return sourceLength;
    }

    /*
     *Name     : handleGeneratedSpaces
     *Function : The shapeUnicode function converts Lam + Alef into LamAlef + space,
     *           and Tashkeel to space.
     *           handleGeneratedSpaces function puts these generated spaces
     *           according to the options the user specifies. LamAlef and Tashkeel
     *           spaces can be replaced at begin, at end, at near or decrease the
     *           buffer size.
     *
     *           There is also Auto option for LamAlef and tashkeel, which will put
     *           the spaces at end of the buffer (or end of text if the user used
     *           the option SPACES_RELATIVE_TO_TEXT_BEGIN_END).
     *
     *           If the text type was visual_LTR and the option
     *           SPACES_RELATIVE_TO_TEXT_BEGIN_END was selected the END
     *           option will place the space at the beginning of the buffer and
     *           BEGIN will place the space at the end of the buffer.
     */
  private int handleGeneratedSpaces(char[] dest,
            int start,
            int length) {

      int lenOptionsLamAlef = options & LAMALEF_MASK;
      int lenOptionsTashkeel = options & TASHKEEL_MASK;
      boolean lamAlefOn = false;
      boolean tashkeelOn = false;

      if (!isLogical & !spacesRelativeToTextBeginEnd) {
          switch (lenOptionsLamAlef) {
          case LAMALEF_BEGIN: lenOptionsLamAlef = LAMALEF_END; break;
          case LAMALEF_END: lenOptionsLamAlef = LAMALEF_BEGIN; break;
          default: break;
         }
          switch (lenOptionsTashkeel){
          case TASHKEEL_BEGIN: lenOptionsTashkeel = TASHKEEL_END; break;
          case TASHKEEL_END: lenOptionsTashkeel = TASHKEEL_BEGIN; break;
          default: break;
          }
        }


      if (lenOptionsLamAlef == LAMALEF_NEAR) {
          for (int i = start, e = i + length; i < e; ++i) {
              if (dest[i] == LAMALEF_SPACE_SUB) {
                  dest[i] = SPACE_CHAR_FOR_LAMALEF;
              }
          }

      } else {

          final int e = start + length;
          int wL = countSpaceSub(dest, length, LAMALEF_SPACE_SUB);
          int wT = countSpaceSub(dest, length, TASHKEEL_SPACE_SUB);

          if (lenOptionsLamAlef == LAMALEF_END){
            lamAlefOn = true;
          }
          if (lenOptionsTashkeel == TASHKEEL_END){
            tashkeelOn = true;
          }


          if (lamAlefOn && (lenOptionsLamAlef == LAMALEF_END)) {
            shiftArray(dest, start, e, LAMALEF_SPACE_SUB);
            while (wL > start) {
                dest[--wL] = SPACE_CHAR;
            }
          }

          if (tashkeelOn && (lenOptionsTashkeel == TASHKEEL_END)){
            shiftArray(dest, start, e, TASHKEEL_SPACE_SUB);
            while (wT > start) {
                 dest[--wT] = SPACE_CHAR;
            }
          }

          lamAlefOn = false;
          tashkeelOn = false;

          if (lenOptionsLamAlef == LAMALEF_RESIZE){
            lamAlefOn = true;
          }
          if (lenOptionsTashkeel == TASHKEEL_RESIZE){
            tashkeelOn = true;
          }

          if (lamAlefOn && (lenOptionsLamAlef == LAMALEF_RESIZE)){
              shiftArray(dest, start, e, LAMALEF_SPACE_SUB);
              wL = flipArray(dest,start,e, wL);
              length = wL - start;
          }
          if (tashkeelOn && (lenOptionsTashkeel == TASHKEEL_RESIZE)) {
              shiftArray(dest, start, e, TASHKEEL_SPACE_SUB);
              wT = flipArray(dest,start,e, wT);
              length = wT - start;
          }

          lamAlefOn = false;
          tashkeelOn = false;

          if ((lenOptionsLamAlef == LAMALEF_BEGIN) ||
              (lenOptionsLamAlef == LAMALEF_AUTO)){
                lamAlefOn = true;
          }
          if (lenOptionsTashkeel == TASHKEEL_BEGIN){
                tashkeelOn = true;
          }

          if (lamAlefOn && ((lenOptionsLamAlef == LAMALEF_BEGIN)||
                            (lenOptionsLamAlef == LAMALEF_AUTO))) { // spaces at beginning
              shiftArray(dest, start, e, LAMALEF_SPACE_SUB);
               wL = flipArray(dest,start,e, wL);
                  while (wL < e) {
                      dest[wL++] = SPACE_CHAR;
                  }
              }
              if(tashkeelOn && (lenOptionsTashkeel == TASHKEEL_BEGIN)){
               shiftArray(dest, start, e, TASHKEEL_SPACE_SUB);
               wT = flipArray(dest,start,e, wT);
                  while (wT < e) {
                      dest[wT++] = SPACE_CHAR;
                  }
              }
           }

      return length;
  }


  /*
   *Name     :expandCompositCharAtBegin
   *Function :Expands the LamAlef character to Lam and Alef consuming the required
   *         space from beginning of the buffer. If the text type was visual_LTR
   *         and the option SPACES_RELATIVE_TO_TEXT_BEGIN_END was selected
   *         the spaces will be located at end of buffer.
   *         If there are no spaces to expand the LamAlef, an exception is thrown.
*/
 private boolean expandCompositCharAtBegin(char[] dest,int start, int length,
                            int lacount) {
     boolean spaceNotFound = false;

     if (lacount > countSpacesRight(dest, start, length)) {
         spaceNotFound = true;
         return spaceNotFound;
     }
     for (int r = start + length - lacount, w = start + length; --r >= start;) {
         char ch = dest[r];
         if (isNormalizedLamAlefChar(ch)) {
             dest[--w] = LAM_CHAR;
             dest[--w] = convertNormalizedLamAlef[ch - '\u065C'];
         } else {
             dest[--w] = ch;
         }
     }
     return spaceNotFound;

  }

  /*
   *Name     : expandCompositCharAtEnd
   *Function : Expands the LamAlef character to Lam and Alef consuming the
   *           required space from end of the buffer. If the text type was
   *           Visual LTR and the option SPACES_RELATIVE_TO_TEXT_BEGIN_END
   *           was used, the spaces will be consumed from begin of buffer. If
   *           there are no spaces to expand the LamAlef, an exception is thrown.
   */

  private boolean  expandCompositCharAtEnd(char[] dest,int start, int length,
                          int lacount){
      boolean spaceNotFound = false;

      if (lacount > countSpacesLeft(dest, start, length)) {
          spaceNotFound = true;
          return spaceNotFound;
      }
      for (int r = start + lacount, w = start, e = start + length; r < e; ++r) {
          char ch = dest[r];
          if (isNormalizedLamAlefChar(ch)) {
              dest[w++] = convertNormalizedLamAlef[ch - '\u065C'];
              dest[w++] = LAM_CHAR;
          } else {
              dest[w++] = ch;
          }
      }
      return spaceNotFound;
  }

  /*
   *Name     : expandCompositCharAtNear
   *Function : Expands the LamAlef character into Lam + Alef, YehHamza character
   *           into Yeh + Hamza, SeenFamily character into SeenFamily character
   *           + Tail, while consuming the space next to the character.
   */

  private boolean expandCompositCharAtNear(char[] dest,int start, int length,
                                       int yehHamzaOption, int seenTailOption, int lamAlefOption){

      boolean spaceNotFound = false;



      if (isNormalizedLamAlefChar(dest[start])) {
          spaceNotFound = true;
          return spaceNotFound;
      }
      for (int i = start + length; --i >=start;) {
          char ch = dest[i];
          if (lamAlefOption == 1 && isNormalizedLamAlefChar(ch)) {
              if (i>start &&dest[i-1] == SPACE_CHAR) {
                  dest[i] = LAM_CHAR;
                  dest[--i] = convertNormalizedLamAlef[ch - '\u065C'];
              } else {
                  spaceNotFound = true;
                  return spaceNotFound;
              }
          }else if(seenTailOption == 1 && isSeenTailFamilyChar(ch) == 1){
              if(i>start &&dest[i-1] == SPACE_CHAR){
                  dest[i-1] = tailChar;
              } else{
                  spaceNotFound = true;
                  return spaceNotFound;
              }
          }else if(yehHamzaOption == 1 && isYehHamzaChar(ch)){

               if(i>start &&dest[i-1] == SPACE_CHAR){
                  dest[i] = yehHamzaToYeh[ch - YEH_HAMZAFE_CHAR];
                  dest[i-1] = HAMZAFE_CHAR;
              }else{
                  spaceNotFound = true;
                  return spaceNotFound;
                }


          }
      }
      return false;

  }

    /*
     * Name    : expandCompositChar
     * Function: LamAlef needs special handling as the LamAlef is
     *           one character while expanding it will give two
     *           characters Lam + Alef, so we need to expand the LamAlef
     *           in near or far spaces according to the options the user
     *           specifies or increase the buffer size.
     *           Dest has enough room for the expansion if we are growing.
     *           lamalef are normalized to the 'special characters'
     */
    private int expandCompositChar(char[] dest,
                              int start,
                              int length,
                              int lacount,
                              int shapingMode) throws ArabicShapingException {

        int lenOptionsLamAlef = options & LAMALEF_MASK;
        int lenOptionsSeen = options & SEEN_MASK;
        int lenOptionsYehHamza = options & YEHHAMZA_MASK;
        boolean spaceNotFound = false;

        if (!isLogical && !spacesRelativeToTextBeginEnd) {
            switch (lenOptionsLamAlef) {
            case LAMALEF_BEGIN: lenOptionsLamAlef = LAMALEF_END; break;
            case LAMALEF_END: lenOptionsLamAlef = LAMALEF_BEGIN; break;
            default: break;
            }
        }

        if(shapingMode == 1){
            if(lenOptionsLamAlef == LAMALEF_AUTO){
                if(isLogical){
                    spaceNotFound = expandCompositCharAtEnd(dest, start, length, lacount);
                    if(spaceNotFound){
                        spaceNotFound = expandCompositCharAtBegin(dest, start, length, lacount);
                    }
                    if(spaceNotFound){
                        spaceNotFound = expandCompositCharAtNear(dest, start, length,0,0,1);
                    }
                    if(spaceNotFound){
                        throw new ArabicShapingException("No spacefor lamalef");
                    }
                }else{
                    spaceNotFound = expandCompositCharAtBegin(dest, start, length, lacount);
                    if(spaceNotFound){
                        spaceNotFound = expandCompositCharAtEnd(dest, start, length, lacount);
                    }
                    if(spaceNotFound){
                        spaceNotFound = expandCompositCharAtNear(dest, start, length,0,0,1);
                    }
                    if(spaceNotFound){
                        throw new ArabicShapingException("No spacefor lamalef");
                    }
                }
            }else if(lenOptionsLamAlef == LAMALEF_END){
                spaceNotFound = expandCompositCharAtEnd(dest, start, length, lacount);
                if(spaceNotFound){
                    throw new ArabicShapingException("No spacefor lamalef");
                }
            }else if(lenOptionsLamAlef == LAMALEF_BEGIN){
                spaceNotFound = expandCompositCharAtBegin(dest, start, length, lacount);
                if(spaceNotFound){
                    throw new ArabicShapingException("No spacefor lamalef");
                }
            }else if(lenOptionsLamAlef == LAMALEF_NEAR){
                spaceNotFound = expandCompositCharAtNear(dest, start, length,0,0,1);
                if(spaceNotFound){
                    throw new ArabicShapingException("No spacefor lamalef");
            }
            }else if(lenOptionsLamAlef == LAMALEF_RESIZE){
                for (int r = start + length, w = r + lacount; --r >= start;) {
                    char ch = dest[r];
                    if (isNormalizedLamAlefChar(ch)) {
                        dest[--w] = '\u0644';
                        dest[--w] = convertNormalizedLamAlef[ch - '\u065C'];
                    } else {
                        dest[--w] = ch;
                    }
                }
                length += lacount;
            }
            }else{
                if(lenOptionsSeen == SEEN_TWOCELL_NEAR){
                spaceNotFound = expandCompositCharAtNear(dest, start, length,0,1,0);
                if(spaceNotFound){
                    throw new ArabicShapingException("No space for Seen tail expansion");
                }
            }
            if(lenOptionsYehHamza == YEHHAMZA_TWOCELL_NEAR){
                spaceNotFound = expandCompositCharAtNear(dest, start, length,1,0,0);
                if(spaceNotFound){
                    throw new ArabicShapingException("No space for YehHamza expansion");
                }
            }
            }
        return length;
    }


    /* Convert the input buffer from FExx Range into 06xx Range
     * to put all characters into the 06xx range
     * even the lamalef is converted to the special region in
     * the 06xx range.  Return the number of lamalef chars found.
     */
    private int normalize(char[] dest, int start, int length) {
        int lacount = 0;
        for (int i = start, e = i + length; i < e; ++i) {
            char ch = dest[i];
            if (ch >= '\uFE70' && ch <= '\uFEFC') {
                if (isLamAlefChar(ch)) {
                    ++lacount;
                }
                dest[i] = (char)convertFEto06[ch - '\uFE70'];
            }
        }
        return lacount;
    }

    /*
     * Name    : deshapeNormalize
     * Function: Convert the input buffer from FExx Range into 06xx Range
     *           even the lamalef is converted to the special region in the 06xx range.
     *           According to the options the user enters, all seen family characters
     *           followed by a tail character are merged to seen tail family character and
     *           any yeh followed by a hamza character are merged to yehhamza character.
     *           Method returns the number of lamalef chars found.
     */
    private int deshapeNormalize(char[] dest, int start, int length) {
        int lacount = 0;
        int yehHamzaComposeEnabled = 0;
        int seenComposeEnabled = 0;

        yehHamzaComposeEnabled = ((options&YEHHAMZA_MASK) == YEHHAMZA_TWOCELL_NEAR) ? 1 : 0;
        seenComposeEnabled = ((options&SEEN_MASK) == SEEN_TWOCELL_NEAR)? 1 : 0;

        for (int i = start, e = i + length; i < e; ++i) {
            char ch = dest[i];

        if( (yehHamzaComposeEnabled == 1) && ((ch == HAMZA06_CHAR) || (ch == HAMZAFE_CHAR))
               && (i < (length - 1)) && isAlefMaksouraChar(dest[i+1] )) {
                dest[i] = SPACE_CHAR;
                dest[i+1] = YEH_HAMZA_CHAR;
       } else if ( (seenComposeEnabled == 1) && (isTailChar(ch)) && (i< (length - 1))
                       && (isSeenTailFamilyChar(dest[i+1])==1) ) {
               dest[i] = SPACE_CHAR;
       }
       else if (ch >= '\uFE70' && ch <= '\uFEFC') {
                if (isLamAlefChar(ch)) {
                    ++lacount;
                }
                dest[i] = (char)convertFEto06[ch - '\uFE70'];
            }
        }
        return lacount;
    }

    /*
     * Name    : shapeUnicode
     * Function: Converts an Arabic Unicode buffer in 06xx Range into a shaped
     *           arabic Unicode buffer in FExx Range
     */
    private int shapeUnicode(char[] dest,
                             int start,
                             int length,
                             int destSize,
                             int tashkeelFlag)throws ArabicShapingException {

        int lamalef_count = normalize(dest, start, length);

        // resolve the link between the characters.
        // Arabic characters have four forms: Isolated, Initial, Medial and Final.
        // Tashkeel characters have two, isolated or medial, and sometimes only isolated.
        // tashkeelFlag == 0: shape normally, 1: shape isolated, 2: don't shape

        boolean lamalef_found = false, seenfam_found = false;
        boolean yehhamza_found = false, tashkeel_found = false;
        int i = start + length - 1;
        int currLink = getLink(dest[i]);
        int nextLink = 0;
        int prevLink = 0;
        int lastLink = 0;
        //int prevPos = i;
        int lastPos = i;
        int nx = -2;
        int nw = 0;

        while (i >= 0) {
            // If high byte of currLink > 0 then there might be more than one shape
            if ((currLink & '\uFF00') > 0 || isTashkeelChar(dest[i])) {
                nw = i - 1;
                nx = -2;
                while (nx < 0) { // we need to know about next char
                    if (nw == -1) {
                        nextLink = 0;
                        nx = Integer.MAX_VALUE;
                    } else {
                        nextLink = getLink(dest[nw]);
                        if ((nextLink & IRRELEVANT) == 0) {
                            nx = nw;
                        } else {
                            --nw;
                        }
                    }
                }

                if (((currLink & ALEFTYPE) > 0) && ((lastLink & LAMTYPE) > 0)) {
                    lamalef_found = true;
                    char wLamalef = changeLamAlef(dest[i]); // get from 0x065C-0x065f
                    if (wLamalef != '\u0000') {
                        // replace alef by marker, it will be removed later
                        dest[i] = '\uffff';
                        dest[lastPos] = wLamalef;
                        i = lastPos;
                    }

                    lastLink = prevLink;
                    currLink = getLink(wLamalef); // requires '\u0000', unfortunately
                }
                if ((i > 0) && (dest[i-1] == SPACE_CHAR))
                {
                    if ( isSeenFamilyChar(dest[i]) == 1){
                        seenfam_found = true;
                    } else if (dest[i] == YEH_HAMZA_CHAR) {
                        yehhamza_found = true;
                    }
                }
                else if(i==0){
                    if ( isSeenFamilyChar(dest[i]) == 1){
                        seenfam_found = true;
                    } else if (dest[i] == YEH_HAMZA_CHAR) {
                        yehhamza_found = true;
                    }
                }


                // get the proper shape according to link ability of neighbors
                // and of character; depends on the order of the shapes
                // (isolated, initial, middle, final) in the compatibility area

                int flag = specialChar(dest[i]);

                int shape = shapeTable[nextLink & LINK_MASK]
                    [lastLink & LINK_MASK]
                    [currLink & LINK_MASK];

                if (flag == 1) {
                    shape &= 0x1;
                } else if (flag == 2) {
                    if (tashkeelFlag == 0 &&
                        ((lastLink & LINKL) != 0) &&
                        ((nextLink & LINKR) != 0) &&
                        dest[i] != '\u064C' &&
                        dest[i] != '\u064D' &&
                        !((nextLink & ALEFTYPE) == ALEFTYPE &&
                          (lastLink & LAMTYPE) == LAMTYPE)) {

                        shape = 1;
                    } else {
                        shape = 0;
                    }
                }
                if (flag == 2) {
                    if (tashkeelFlag == 2) {
                        dest[i] = TASHKEEL_SPACE_SUB;
                        tashkeel_found = true;
                    }
                    else{
                        dest[i] = (char)('\uFE70' + irrelevantPos[dest[i] - '\u064B'] + shape);
                    }
                    // else leave tashkeel alone
                } else {
                    dest[i] = (char)('\uFE70' + (currLink >> 8) + shape);
                }
            }

            // move one notch forward
            if ((currLink & IRRELEVANT) == 0) {
                prevLink = lastLink;
                lastLink = currLink;
                //prevPos = lastPos;
                lastPos = i;
            }

            --i;
            if (i == nx) {
                currLink = nextLink;
                nx = -2;
            } else if (i != -1) {
                currLink = getLink(dest[i]);
            }
        }

        // If we found a lam/alef pair in the buffer
        // call handleGeneratedSpaces to remove the spaces that were added

        destSize = length;
        if (lamalef_found || tashkeel_found) {
            destSize = handleGeneratedSpaces(dest, start, length);
        }
        if (seenfam_found || yehhamza_found){
            destSize = expandCompositChar(dest, start, destSize, lamalef_count, SHAPE_MODE);
        }
        return destSize;
    }

    /*
     * Name    : deShapeUnicode
     * Function: Converts an Arabic Unicode buffer in FExx Range into unshaped
     *           arabic Unicode buffer in 06xx Range
     */
    private int deShapeUnicode(char[] dest,
                               int start,
                               int length,
                               int destSize) throws ArabicShapingException {

        int lamalef_count = deshapeNormalize(dest, start, length);

        // If there was a lamalef in the buffer call expandLamAlef
        if (lamalef_count != 0) {
            // need to adjust dest to fit expanded buffer... !!!
            destSize = expandCompositChar(dest, start, length, lamalef_count,DESHAPE_MODE);
        } else {
            destSize = length;
        }

        return destSize;
    }

    private int internalShape(char[] source,
                              int sourceStart,
                              int sourceLength,
                              char[] dest,
                              int destStart,
                              int destSize) throws ArabicShapingException {

        if (sourceLength == 0) {
            return 0;
        }

        if (destSize == 0) {
            if (((options & LETTERS_MASK) != LETTERS_NOOP) &&
                ((options & LAMALEF_MASK) == LAMALEF_RESIZE)) {

                return calculateSize(source, sourceStart, sourceLength);
            } else {
                return sourceLength; // by definition
            }
        }

        // always use temp buffer
        char[] temp = new char[sourceLength * 2]; // all lamalefs requiring expansion
        System.arraycopy(source, sourceStart, temp, 0, sourceLength);

        if (isLogical) {
            invertBuffer(temp, 0, sourceLength);
        }

        int outputSize = sourceLength;

        switch (options & LETTERS_MASK) {
        case LETTERS_SHAPE_TASHKEEL_ISOLATED:
            outputSize = shapeUnicode(temp, 0, sourceLength, destSize, 1);
            break;

        case LETTERS_SHAPE:
            if( ((options&TASHKEEL_MASK)> 0) &&
                ((options&TASHKEEL_MASK) !=TASHKEEL_REPLACE_BY_TATWEEL)) {
                   /* Call the shaping function with tashkeel flag == 2 for removal of tashkeel */
                outputSize = shapeUnicode(temp, 0, sourceLength, destSize, 2);
                }else {
                   //default Call the shaping function with tashkeel flag == 1 */
                    outputSize = shapeUnicode(temp, 0, sourceLength, destSize, 0);

                   /*After shaping text check if user wants to remove tashkeel and replace it with tatweel*/
                   if( (options&TASHKEEL_MASK) == TASHKEEL_REPLACE_BY_TATWEEL){
                       outputSize = handleTashkeelWithTatweel(temp,sourceLength);
                   }
               }
            break;

        case LETTERS_UNSHAPE:
            outputSize = deShapeUnicode(temp, 0, sourceLength, destSize);
            break;

        default:
            break;
        }

        if (outputSize > destSize) {
            throw new ArabicShapingException("not enough room for result data");
        }

        if ((options & DIGITS_MASK) != DIGITS_NOOP) {
            char digitBase = '\u0030'; // European digits
            switch (options & DIGIT_TYPE_MASK) {
            case DIGIT_TYPE_AN:
                digitBase = '\u0660';  // Arabic-Indic digits
                break;

            case DIGIT_TYPE_AN_EXTENDED:
                digitBase = '\u06f0';  // Eastern Arabic-Indic digits (Persian and Urdu)
                break;

            default:
                break;
            }

            switch (options & DIGITS_MASK) {
            case DIGITS_EN2AN:
                {
                    int digitDelta = digitBase - '\u0030';
                    for (int i = 0; i < outputSize; ++i) {
                        char ch = temp[i];
                        if (ch <= '\u0039' && ch >= '\u0030') {
                            temp[i] += digitDelta;
                        }
                    }
                }
                break;

            case DIGITS_AN2EN:
                {
                    char digitTop = (char)(digitBase + 9);
                    int digitDelta = '\u0030' - digitBase;
                    for (int i = 0; i < outputSize; ++i) {
                        char ch = temp[i];
                        if (ch <= digitTop && ch >= digitBase) {
                            temp[i] += digitDelta;
                        }
                    }
                }
                break;

            case DIGITS_EN2AN_INIT_LR:
                shapeToArabicDigitsWithContext(temp, 0, outputSize, digitBase, false);
                break;

            case DIGITS_EN2AN_INIT_AL:
                shapeToArabicDigitsWithContext(temp, 0, outputSize, digitBase, true);
                break;

            default:
                break;
            }
        }

        if (isLogical) {
            invertBuffer(temp, 0, outputSize);
        }

        System.arraycopy(temp, 0, dest, destStart, outputSize);

        return outputSize;
    }

    private static class ArabicShapingException extends RuntimeException {
        ArabicShapingException(String msg) {
            super(msg);
        }
    }
}
