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
 * @author Dmitry A. Durnev
 * @version $Revision$
 */
package java.awt.im;

import java.util.Map;
import java.awt.font.TextAttribute;

import org.apache.harmony.awt.internal.nls.Messages;

public class InputMethodHighlight {

    public static final int RAW_TEXT = 0;

    public static final int CONVERTED_TEXT = 1;

    public static final InputMethodHighlight
        UNSELECTED_RAW_TEXT_HIGHLIGHT = new InputMethodHighlight(false, RAW_TEXT);

    public static final InputMethodHighlight
        SELECTED_RAW_TEXT_HIGHLIGHT = new InputMethodHighlight(true, RAW_TEXT);

    public static final InputMethodHighlight
        UNSELECTED_CONVERTED_TEXT_HIGHLIGHT = 
            new InputMethodHighlight(false, CONVERTED_TEXT);

    public static final InputMethodHighlight
        SELECTED_CONVERTED_TEXT_HIGHLIGHT = 
            new InputMethodHighlight(true, CONVERTED_TEXT);
    
    private boolean selected;
    private int state;
    private int variation;
    private Map<TextAttribute,?> style;

    public InputMethodHighlight(boolean selected, int state, int variation) {
        this(selected, state, variation, null);
    }

    public InputMethodHighlight(boolean selected, int state,
                                int variation, Map<java.awt.font.TextAttribute, ?> style) {
        if ((state != RAW_TEXT) && (state != CONVERTED_TEXT)) {
            // awt.20B=unknown input method highlight state
            throw new IllegalArgumentException(Messages.getString("awt.20B")); //$NON-NLS-1$
        }
        this.selected = selected;
        this.state = state;
        this.variation = variation;
        this.style = style;
    }

    public InputMethodHighlight(boolean selected, int state) {
        this(selected, state, 0, null);
    }

    public int getState() {
        return state;
    }

    public Map<java.awt.font.TextAttribute, ?> getStyle() {
        return style;
    }

    public int getVariation() {
        return variation;
    }

    public boolean isSelected() {
        return selected;
    }
}

