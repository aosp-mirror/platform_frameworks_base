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

package java.awt.font;

/**
 * The OpenType interface provides constants and methods for getting instance
 * data for fonts of type OpenType and TrueType. For more information, see the
 * <a
 * href="http://partners.adobe.com/public/developer/opentype/index_spec.html">
 * OpenType specification</a>.
 * 
 * @since Android 1.0
 */
public interface OpenType {

    /**
     * The Constant TAG_ACNT indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_ACNT = 1633906292;

    /**
     * The Constant TAG_AVAR indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_AVAR = 1635148146;

    /**
     * The Constant TAG_BASE indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_BASE = 1111577413;

    /**
     * The Constant TAG_BDAT indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_BDAT = 1650745716;

    /**
     * The Constant TAG_BLOC indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_BLOC = 1651273571;

    /**
     * The Constant TAG_BSLN indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_BSLN = 1651731566;

    /**
     * The Constant TAG_CFF indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_CFF = 1128678944;

    /**
     * The Constant TAG_CMAP indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_CMAP = 1668112752;

    /**
     * The Constant TAG_CVAR indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_CVAR = 1668702578;

    /**
     * The Constant TAG_CVT indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_CVT = 1668707360;

    /**
     * The Constant TAG_DSIG indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_DSIG = 1146308935;

    /**
     * The Constant TAG_EBDT indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_EBDT = 1161970772;

    /**
     * The Constant TAG_EBLC indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_EBLC = 1161972803;

    /**
     * The Constant TAG_EBSC indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_EBSC = 1161974595;

    /**
     * The Constant TAG_FDSC indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_FDSC = 1717859171;

    /**
     * The Constant TAG_FEAT indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_FEAT = 1717920116;

    /**
     * The Constant TAG_FMTX indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_FMTX = 1718449272;

    /**
     * The Constant TAG_FPGM indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_FPGM = 1718642541;

    /**
     * The Constant TAG_FVAR indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_FVAR = 1719034226;

    /**
     * The Constant TAG_GASP indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_GASP = 1734439792;

    /**
     * The Constant TAG_GDEF indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_GDEF = 1195656518;

    /**
     * The Constant TAG_GLYF indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_GLYF = 1735162214;

    /**
     * The Constant TAG_GPOS indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_GPOS = 1196445523;

    /**
     * The Constant TAG_GSUB indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_GSUB = 1196643650;

    /**
     * The Constant TAG_GVAR indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_GVAR = 1735811442;

    /**
     * The Constant TAG_HDMX indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_HDMX = 1751412088;

    /**
     * The Constant TAG_HEAD indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_HEAD = 1751474532;

    /**
     * The Constant TAG_HHEA indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_HHEA = 1751672161;

    /**
     * The Constant TAG_HMTX indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_HMTX = 1752003704;

    /**
     * The Constant TAG_JSTF indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_JSTF = 1246975046;

    /**
     * The Constant TAG_JUST indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_JUST = 1786082164;

    /**
     * The Constant TAG_KERN indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_KERN = 1801810542;

    /**
     * The Constant TAG_LCAR indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_LCAR = 1818452338;

    /**
     * The Constant TAG_LOCA indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_LOCA = 1819239265;

    /**
     * The Constant TAG_LTSH indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_LTSH = 1280594760;

    /**
     * The Constant TAG_MAXP indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_MAXP = 1835104368;

    /**
     * The Constant TAG_MMFX indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_MMFX = 1296909912;

    /**
     * The Constant TAG_MMSD indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_MMSD = 1296913220;

    /**
     * The Constant TAG_MORT indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_MORT = 1836020340;

    /**
     * The Constant TAG_NAME indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_NAME = 1851878757;

    /**
     * The Constant TAG_OPBD indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_OPBD = 1836020340;

    /**
     * The Constant TAG_OS2 indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_OS2 = 1330851634;

    /**
     * The Constant TAG_PCLT indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_PCLT = 1346587732;

    /**
     * The Constant TAG_POST indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_POST = 1886352244;

    /**
     * The Constant TAG_PREP indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_PREP = 1886545264;

    /**
     * The Constant TAG_PROP indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_PROP = 1886547824;

    /**
     * The Constant TAG_TRAK indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_TRAK = 1953653099;

    /**
     * The Constant TAG_TYP1 indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_TYP1 = 1954115633;

    /**
     * The Constant TAG_VDMX indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_VDMX = 1447316824;

    /**
     * The Constant TAG_VHEA indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_VHEA = 1986553185;

    /**
     * The Constant TAG_VMTX indicates corresponding table tag in the Open Type
     * Specification.
     */
    public static final int TAG_VMTX = 1986884728;

    /**
     * Returns the OpenType font version.
     * 
     * @return the the OpenType font version.
     */
    public int getVersion();

    /**
     * Gets the table for a specified tag. Sfnt tables include cmap, name and
     * head items.
     * 
     * @param sfntTag
     *            the sfnt tag.
     * @return a byte array contains the font data corresponding to the
     *         specified tag.
     */
    public byte[] getFontTable(int sfntTag);

    /**
     * Gets the table for a specified tag. Sfnt tables include cmap, name and
     * head items.
     * 
     * @param sfntTag
     *            the sfnt tag.
     * @param offset
     *            the offset of the returned table.
     * @param count
     *            the number of returned table.
     * @return the table corresponding to sfntTag and containing the bytes
     *         starting at offset byte and including count bytes.
     */
    public byte[] getFontTable(int sfntTag, int offset, int count);

    /**
     * Gets the table for a specified tag. Sfnt tables include cmap, name and
     * head items.
     * 
     * @param strSfntTag
     *            the str sfnt tag as a String.
     * @return a byte array contains the font data corresponding to the
     *         specified tag.
     */
    public byte[] getFontTable(String strSfntTag);

    /**
     * Gets the table for a specified tag. Sfnt tables include cmap, name and
     * head items.
     * 
     * @param strSfntTag
     *            the sfnt tag as a String.
     * @param offset
     *            the offset of the returned table.
     * @param count
     *            the number of returned table.
     * @return the table corresponding to sfntTag and containing the bytes
     *         starting at offset byte and including count bytes.
     */
    public byte[] getFontTable(String strSfntTag, int offset, int count);

    /**
     * Gets the table size for a specified tag.
     * 
     * @param strSfntTag
     *            the sfnt tag as a String.
     * @return the table size for a specified tag.
     */
    public int getFontTableSize(String strSfntTag);

    /**
     * Gets the table size for a specified tag.
     * 
     * @param sfntTag
     *            the sfnt tag.
     * @return the table size for a specified tag.
     */
    public int getFontTableSize(int sfntTag);

}
