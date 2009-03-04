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

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The GlyphJustificationInfo class provides information about the glyph's
 * justification properties. There are four justification properties: weight,
 * priority, absorb, and limit.
 * <p>
 * There are two sets of metrics: growing and shrinking. Growing metrics are
 * used when the glyphs are to be spread apart to fit a larger width. Shrinking
 * metrics are used when the glyphs are to be moved together to fit a smaller
 * width.
 * </p>
 * 
 * @since Android 1.0
 */
public final class GlyphJustificationInfo {

    /**
     * The Constant PRIORITY_KASHIDA indicates the highest justification
     * priority.
     */
    public static final int PRIORITY_KASHIDA = 0;

    /**
     * The Constant PRIORITY_WHITESPACE indicates the second highest
     * justification priority.
     */
    public static final int PRIORITY_WHITESPACE = 1;

    /**
     * The Constant PRIORITY_INTERCHAR indicates the second lowest justification
     * priority.
     */
    public static final int PRIORITY_INTERCHAR = 2;

    /**
     * The Constant PRIORITY_NONE indicates the lowest justification priority.
     */
    public static final int PRIORITY_NONE = 3;

    /**
     * The grow absorb flag indicates if this glyph absorbs all extra space at
     * this and lower priority levels when it grows.
     */
    public final boolean growAbsorb;

    /**
     * The grow left limit value represents the maximum value by which the left
     * side of this glyph grows.
     */
    public final float growLeftLimit;

    /**
     * The grow right limit value repesents the maximum value by which the right
     * side of this glyph grows.
     */
    public final float growRightLimit;

    /**
     * The grow priority value represents the priority level of this glyph as it
     * is growing.
     */
    public final int growPriority;

    /**
     * The shrink absorb fleg indicates this glyph absorbs all remaining
     * shrinkage at this and lower priority levels as it shrinks.
     */
    public final boolean shrinkAbsorb;

    /**
     * The shrink left limit value represents the maximum value by which the
     * left side of this glyph shrinks.
     */
    public final float shrinkLeftLimit;

    /**
     * The shrink right limit value represents the maximum value by which the
     * right side of this glyph shrinks.
     */
    public final float shrinkRightLimit;

    /**
     * The shrink priority represents the glyth's priority level as it is
     * shrinking.
     */
    public final int shrinkPriority;

    /**
     * The weight of the glyph.
     */
    public final float weight;

    /**
     * Instantiates a new GlyphJustificationInfo object which contains glyph's
     * justification properties.
     * 
     * @param weight
     *            the weight of glyph.
     * @param growAbsorb
     *            indicates if this glyph contais all space at this priority and
     *            lower priority levels when it grows.
     * @param growPriority
     *            indicates the priority level of this glyph when it grows.
     * @param growLeftLimit
     *            indicates the maximum value of which the left side of this
     *            glyph can grow.
     * @param growRightLimit
     *            the maximum value of which the right side of this glyph can
     *            grow.
     * @param shrinkAbsorb
     *            indicates if this glyph contains all remaining shrinkage at
     *            this and lower priority levels when it shrinks.
     * @param shrinkPriority
     *            indicates the glyph's priority level when it shrinks.
     * @param shrinkLeftLimit
     *            indicates the maximum value of which the left side of this
     *            glyph can shrink.
     * @param shrinkRightLimit
     *            indicates the maximum amount by which the right side of this
     *            glyph can shrink.
     */
    public GlyphJustificationInfo(float weight, boolean growAbsorb, int growPriority,
            float growLeftLimit, float growRightLimit, boolean shrinkAbsorb, int shrinkPriority,
            float shrinkLeftLimit, float shrinkRightLimit) {

        if (weight < 0) {
            // awt.19C=weight must be a positive number
            throw new IllegalArgumentException(Messages.getString("awt.19C")); //$NON-NLS-1$
        }
        this.weight = weight;

        if (growLeftLimit < 0) {
            // awt.19D=growLeftLimit must be a positive number
            throw new IllegalArgumentException(Messages.getString("awt.19D")); //$NON-NLS-1$
        }
        this.growLeftLimit = growLeftLimit;

        if (growRightLimit < 0) {
            // awt.19E=growRightLimit must be a positive number
            throw new IllegalArgumentException(Messages.getString("awt.19E")); //$NON-NLS-1$
        }
        this.growRightLimit = growRightLimit;

        if ((shrinkPriority < 0) || (shrinkPriority > PRIORITY_NONE)) {
            // awt.19F=incorrect value for shrinkPriority, more than
            // PRIORITY_NONE or less than PRIORITY_KASHIDA value
            throw new IllegalArgumentException(Messages.getString("awt.19F")); //$NON-NLS-1$
        }
        this.shrinkPriority = shrinkPriority;

        if ((growPriority < 0) || (growPriority > PRIORITY_NONE)) {
            // awt.200=incorrect value for growPriority, more than PRIORITY_NONE
            // or less than PRIORITY_KASHIDA value
            throw new IllegalArgumentException(Messages.getString("awt.200")); //$NON-NLS-1$
        }
        this.growPriority = growPriority;

        if (shrinkLeftLimit < 0) {
            // awt.201=shrinkLeftLimit must be a positive number
            throw new IllegalArgumentException(Messages.getString("awt.201")); //$NON-NLS-1$
        }
        this.shrinkLeftLimit = shrinkLeftLimit;

        if (shrinkRightLimit < 0) {
            // awt.202=shrinkRightLimit must be a positive number
            throw new IllegalArgumentException(Messages.getString("awt.202")); //$NON-NLS-1$
        }
        this.shrinkRightLimit = shrinkRightLimit;

        this.shrinkAbsorb = shrinkAbsorb;
        this.growAbsorb = growAbsorb;
    }
}
