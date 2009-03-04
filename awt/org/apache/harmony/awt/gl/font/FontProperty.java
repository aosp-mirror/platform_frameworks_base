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


/**
 * Class containing font property information. This information can be found 
 * in font.property files. See API documentation, logical fonts description part. 
 *
 */
public class FontProperty {

    // font file name 
    String fileName = null;
    
    // name of the encoding to be used 
    String encoding = null;
    
    // array of exclusion ranges (pairs of low and high unicode exclusion bounds)
    int[] exclRange = null;
    
    // font face name
    String name = null;
    
    // font style
    int style = -1;

    /**
     * Returns font style of this font property. 
     */
    public int getStyle(){
        return this.style;
    }

    /**
     * Returns font name of this font property. 
     */
    public String getName(){
        return this.name;
    }

    /**
     * Returns encoding used in this font property. 
     */
    public String getEncoding(){
        return this.encoding;
    }
    
    /**
     * Returns an array of exclusion ranges. This array contain pairs of 
     * low and high bounds of the intervals of characters to ignore in 
     * total Unicode characters range.   
     */
    public int[] getExclusionRange(){
        return this.exclRange;
    }

    /**
     * Returns file name of the font that is described by this font property. 
     */
    public String getFileName(){
        return this.fileName;
    }

    /**
     * Returns true if specified character covered by exclusion ranges of this 
     * font property, false otherwise.
     * 
     * @param ch specified char to check
     */
    public boolean isCharExcluded(char ch){
        if (exclRange == null ){
            return false;
        }

        for (int i = 0; i < exclRange.length;){
            int lb = exclRange[i++];
            int hb = exclRange[i++];

            if (ch >= lb && ch <= hb){
                return true;
            }
        }

        return false;
    }
}
