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
 * @author Rustem V. Rafikov
 * @version $Revision: 1.3 $
 */

package javax.imageio.plugins.jpeg;

/**
 * The JPEGQTable class represents a single JPEG quantization table and provides
 * for the standard tables taken from the JPEG specification.
 * 
 * @since Android 1.0
 */
public class JPEGQTable {

    /**
     * The Constant SIZE.
     */
    private final static int SIZE = 64;

    /**
     * The Constant BASELINE_MAX.
     */
    private final static int BASELINE_MAX = 255;

    /**
     * The Constant MAX.
     */
    private final static int MAX = 32767;

    /**
     * The table.
     */
    private int[] theTable;

    /*
     * K1 & K2 tables can be found in the JPEG format specification at
     * http://www.w3.org/Graphics/JPEG/itu-t81.pdf
     */

    /**
     * The Constant K1LumTable.
     */
    private static final int[] K1LumTable = new int[] {
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57,
            69, 56, 14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55,
            64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100,
            103, 99
    };

    /**
     * The Constant K2ChrTable.
     */
    private static final int[] K2ChrTable = new int[] {
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99,
            99, 99, 47, 66, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99
    };

    /**
     * The K1Luminance indicates standard table K.1 from JPEG specification and
     * produces "good" quality output.
     */
    public static final JPEGQTable K1Luminance = new JPEGQTable(K1LumTable);

    /**
     * The K1Div2Luminance indicates K.1 table from JPEG specification with all
     * elements divided by 2 and produces "very good" quality output.
     */
    public static final JPEGQTable K1Div2Luminance = K1Luminance.getScaledInstance(0.5f, true);

    /**
     * The K2Chrominance indicates K.2 table from JPEG specification and
     * produces "good" quality output.
     */
    public static final JPEGQTable K2Chrominance = new JPEGQTable(K2ChrTable);

    /**
     * The Constant K2Div2Chrominance indicates K.2 table from JPEG
     * specification with all elements divided by 2 and produces "very good"
     * quality output.
     */
    public static final JPEGQTable K2Div2Chrominance = K2Chrominance.getScaledInstance(0.5f, true);;

    /**
     * Instantiates a new JPEGQTable from the array, which should contain 64
     * elements in natural order.
     * 
     * @param table
     *            the quantization table.
     */
    public JPEGQTable(int[] table) {
        if (table == null) {
            throw new IllegalArgumentException("table should not be NULL");
        }
        if (table.length != SIZE) {
            throw new IllegalArgumentException("illegal table size: " + table.length);
        }
        theTable = table.clone();
    }

    /**
     * Gets the current quantization table as an array of integer values.
     * 
     * @return the current quantization table as an array of integer values.
     */
    public int[] getTable() {
        return theTable.clone();
    }

    /**
     * Gets the scaled instance as quantization table where the values are
     * multiplied by the scaleFactor and then clamped if forceBaseline is true.
     * 
     * @param scaleFactor
     *            the scale factor of table.
     * @param forceBaseline
     *            the force baseline flag, the values should be clamped if true.
     * @return the new quantization table.
     */
    public JPEGQTable getScaledInstance(float scaleFactor, boolean forceBaseline) {
        int table[] = new int[SIZE];

        int maxValue = forceBaseline ? BASELINE_MAX : MAX;

        for (int i = 0; i < theTable.length; i++) {
            int rounded = Math.round(theTable[i] * scaleFactor);
            if (rounded < 1) {
                rounded = 1;
            }
            if (rounded > maxValue) {
                rounded = maxValue;
            }
            table[i] = rounded;
        }
        return new JPEGQTable(table);
    }

    /**
     * Returns the string representation of this JPEGQTable object.
     * 
     * @return the string representation of this JPEGQTable object.
     */
    @Override
    public String toString() {
        // -- TODO more informative info
        return "JPEGQTable";
    }
}
