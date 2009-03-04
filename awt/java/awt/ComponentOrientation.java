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
 * @author Michael Danilov, Dmitry A. Durnev
 * @version $Revision$
 */

package java.awt;

import java.io.Serializable;
import java.util.*;

/**
 * The ComponentOrientation class specifies the language-sensitive orientation
 * of component's elements or text. It is used to reflect the differences in
 * this ordering between different writing systems. The ComponentOrientation
 * class indicates the orientation of the elements/text in the horizontal
 * direction ("left to right" or "right to left") and in the vertical direction
 * ("top to bottom" or "bottom to top").
 * 
 * @since Android 1.0
 */
public final class ComponentOrientation implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -4113291392143563828L;

    /**
     * The Constant LEFT_TO_RIGHT indicates that items run left to right.
     */
    public static final ComponentOrientation LEFT_TO_RIGHT = new ComponentOrientation(true, true);

    /**
     * The Constant RIGHT_TO_LEFT indicates that items run right to left.
     */
    public static final ComponentOrientation RIGHT_TO_LEFT = new ComponentOrientation(true, false);

    /**
     * The Constant UNKNOWN indicates that a component's orientation is not set.
     */
    public static final ComponentOrientation UNKNOWN = new ComponentOrientation(true, true);

    /**
     * The Constant rlLangs.
     */
    private static final Set<String> rlLangs = new HashSet<String>(); // RIGHT_TO_LEFT

    // languages

    /**
     * The horizontal.
     */
    private final boolean horizontal;

    /**
     * The left2right.
     */
    private final boolean left2right;

    static {
        rlLangs.add("ar"); //$NON-NLS-1$
        rlLangs.add("fa"); //$NON-NLS-1$
        rlLangs.add("iw"); //$NON-NLS-1$
        rlLangs.add("ur"); //$NON-NLS-1$
    }

    /**
     * Gets the orientation for the given ResourceBundle's localization.
     * 
     * @param bdl
     *            the ResourceBundle.
     * @return the ComponentOrientation.
     * @deprecated Use getOrientation(java.util.Locale) method.
     */
    @Deprecated
    public static ComponentOrientation getOrientation(ResourceBundle bdl) {
        Object obj = null;
        try {
            obj = bdl.getObject("Orientation"); //$NON-NLS-1$
        } catch (MissingResourceException mre) {
            obj = null;
        }
        if (obj instanceof ComponentOrientation) {
            return (ComponentOrientation)obj;
        }
        Locale locale = bdl.getLocale();
        if (locale == null) {
            locale = Locale.getDefault();
        }
        return getOrientation(locale);
    }

    /**
     * Gets the orientation for the specified locale.
     * 
     * @param locale
     *            the specified Locale.
     * @return the ComponentOrientation.
     */
    public static ComponentOrientation getOrientation(Locale locale) {
        String lang = locale.getLanguage();
        return rlLangs.contains(lang) ? RIGHT_TO_LEFT : LEFT_TO_RIGHT;
    }

    /**
     * Instantiates a new component orientation.
     * 
     * @param hor
     *            whether the items should be arranged horizontally.
     * @param l2r
     *            whether this orientation specifies a left-to-right flow.
     */
    private ComponentOrientation(boolean hor, boolean l2r) {
        horizontal = hor;
        left2right = l2r;
    }

    /**
     * Returns true if the text of the of writing systems arranged horizontally.
     * 
     * @return true, if the text is written horizontally, false for a vertical
     *         arrangement.
     */
    public boolean isHorizontal() {
        return horizontal;
    }

    /**
     * Returns true if the text is arranged from left to right.
     * 
     * @return true, for writing systems written from left to right; false for
     *         right-to-left.
     */
    public boolean isLeftToRight() {
        return left2right;
    }

}
