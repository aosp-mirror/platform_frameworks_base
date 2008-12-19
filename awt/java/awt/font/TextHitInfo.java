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
/*
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */

package java.awt.font;

import org.apache.harmony.misc.HashCode;

/**
 * The TextHitInfo class provides information about a caret position in a text
 * model for insertion or deletion of a character in a text. The TextHitInfo
 * defines two biases of the character: leading or trailing. Leading position
 * means the left edge of the specified character (TextHitInfo.leading(2) method
 * for "text" returns the left side of "x"). Trailing position means the right
 * edge of the specified character (TextHitInfo.trailing(2) method for "text"
 * returns the right side of "x").
 * 
 * @since Android 1.0
 */
public final class TextHitInfo {

    /**
     * The char idx.
     */
    private int charIdx; // Represents character index in the line

    /**
     * The is trailing.
     */
    private boolean isTrailing;

    /**
     * Instantiates a new text hit info.
     * 
     * @param idx
     *            the idx.
     * @param isTrailing
     *            the is trailing.
     */
    private TextHitInfo(int idx, boolean isTrailing) {
        charIdx = idx;
        this.isTrailing = isTrailing;
    }

    /**
     * Returns the textual string representation of this TextHitInfo instance.
     * 
     * @return the string representation.
     */
    @Override
    public String toString() {
        return new String("TextHitInfo[" + charIdx + ", " + //$NON-NLS-1$ //$NON-NLS-2$
                (isTrailing ? "Trailing" : "Leading") + "]" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        );
    }

    /**
     * Compares this TextHitInfo object with the specified object.
     * 
     * @param obj
     *            the Object to be compared.
     * @return true, if the specified object is a TextHitInfo object with the
     *         same data values as this TextHitInfo, false otherwise.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TextHitInfo) {
            return equals((TextHitInfo)obj);
        }
        return false;
    }

    /**
     * Compares this TextHitInfo object with the specified TextHitInfo object.
     * 
     * @param thi
     *            the TextHitInfo object to be compared.
     * @return true, if this TextHitInfo object has the same data values as the
     *         specified TextHitInfo object, false otherwise.
     */
    public boolean equals(TextHitInfo thi) {
        return thi != null && thi.charIdx == charIdx && thi.isTrailing == isTrailing;
    }

    /**
     * Gets a TextHitInfo object with its character index at the specified
     * offset from the character index of this TextHitInfo.
     * 
     * @param offset
     *            the offset.
     * @return the TextHitInfo.
     */
    public TextHitInfo getOffsetHit(int offset) {
        return new TextHitInfo(charIdx + offset, isTrailing);
    }

    /**
     * Gets a TextHitInfo associated with the other side of the insertion point.
     * 
     * @return the other hit.
     */
    public TextHitInfo getOtherHit() {
        return isTrailing ? new TextHitInfo(charIdx + 1, false)
                : new TextHitInfo(charIdx - 1, true);
    }

    /**
     * Returns true if the leading edge of the character is hit, false if the
     * trailing edge of the character is hit.
     * 
     * @return true if the leading edge of the character is hit, false if the
     *         trailing edge of the character is hit.
     */
    public boolean isLeadingEdge() {
        return !isTrailing;
    }

    /**
     * Returns the hash code value of this TextHitInfo instance.
     * 
     * @return the hash code value.
     */
    @Override
    public int hashCode() {
        return HashCode.combine(charIdx, isTrailing);
    }

    /**
     * Gets the insertion index.
     * 
     * @return the insertion index: character index if the leading edge is hit,
     *         or character index + 1 if the trailing edge is hit.
     */
    public int getInsertionIndex() {
        return isTrailing ? charIdx + 1 : charIdx;
    }

    /**
     * Gets the index of the character hit.
     * 
     * @return the character hit's index.
     */
    public int getCharIndex() {
        return charIdx;
    }

    /**
     * Returns a TextHitInfo associated with the trailing edge of the character
     * at the specified char index.
     * 
     * @param charIndex
     *            the char index.
     * @return the TextHitInfo associated with the trailing edge of the
     *         character at the specified char index.
     */
    public static TextHitInfo trailing(int charIndex) {
        return new TextHitInfo(charIndex, true);
    }

    /**
     * Returns a TextHitInfo object associated with the leading edge of the
     * character at the specified char index.
     * 
     * @param charIndex
     *            the char index.
     * @return the TextHitInfo object associated with the leading edge of the
     *         character at the specified char index.
     */
    public static TextHitInfo leading(int charIndex) {
        return new TextHitInfo(charIndex, false);
    }

    /**
     * Returns a (trailing) TextHitInfo object associated with the character
     * before the specified offset.
     * 
     * @param offset
     *            the offset.
     * @return the TextHitInfo object associated with the character before the
     *         specified offset.
     */
    public static TextHitInfo beforeOffset(int offset) {
        return new TextHitInfo(offset - 1, true);
    }

    /**
     * Returns a (leading) TextHitInfo object associated with the character
     * after the specified offset.
     * 
     * @param offset
     *            the offset.
     * @return the TextHitInfo object associated with the character after the
     *         specified offset.
     */
    public static TextHitInfo afterOffset(int offset) {
        return new TextHitInfo(offset, false);
    }
}
