/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics.text;

import android.annotation.IntDef;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Indicates the strategies can be used when calculating the text wrapping.
 *
 * See <a href="https://drafts.csswg.org/css-text/#line-break-property">the line-break property</a>
 */
public final class LineBreakConfig {

    /**
     * No line break style specified.
     */
    public static final int LINE_BREAK_STYLE_NONE = 0;

    /**
     * Use the least restrictive rule for line-breaking. This is usually used for short lines.
     */
    public static final int LINE_BREAK_STYLE_LOOSE = 1;

    /**
     * Indicate breaking text with the most comment set of line-breaking rules.
     */
    public static final int LINE_BREAK_STYLE_NORMAL = 2;

    /**
     * Indicates breaking text with the most strictest line-breaking rules.
     */
    public static final int LINE_BREAK_STYLE_STRICT = 3;

    /** @hide */
    @IntDef(prefix = { "LINE_BREAK_STYLE_" }, value = {
            LINE_BREAK_STYLE_NONE, LINE_BREAK_STYLE_LOOSE, LINE_BREAK_STYLE_NORMAL,
            LINE_BREAK_STYLE_STRICT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LineBreakStyle {}

    private @LineBreakStyle int mLineBreakStyle = LINE_BREAK_STYLE_NONE;

    public LineBreakConfig() {
    }

    /**
     * Set the line break configuration.
     *
     * @param config the new line break configuration.
     */
    public void set(@Nullable LineBreakConfig config) {
        if (config != null) {
            mLineBreakStyle = config.getLineBreakStyle();
        } else {
            mLineBreakStyle = LineBreakConfig.LINE_BREAK_STYLE_NONE;
        }
    }

    /**
     * Get the line break style.
     *
     * @return The current line break style to be used for the text wrapping.
     */
    public @LineBreakStyle int getLineBreakStyle() {
        return mLineBreakStyle;
    }

    /**
     * Set the line break style.
     *
     * @param lineBreakStyle the new line break style.
     */
    public void setLineBreakStyle(@LineBreakStyle int lineBreakStyle) {
        mLineBreakStyle = lineBreakStyle;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (this == o) return true;
        if (!(o instanceof LineBreakConfig)) return false;
        LineBreakConfig that = (LineBreakConfig) o;
        return mLineBreakStyle == that.mLineBreakStyle;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLineBreakStyle);
    }
}
