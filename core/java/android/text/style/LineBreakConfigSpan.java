/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.text.style;

import android.annotation.NonNull;
import android.graphics.text.LineBreakConfig;

import java.util.Objects;

/**
 * LineBreakSpan for changing line break style of the specific region of the text.
 */
public class LineBreakConfigSpan {
    private final LineBreakConfig mLineBreakConfig;

    /**
     * Construct a new {@link LineBreakConfigSpan}
     * @param lineBreakConfig a line break config
     */
    public LineBreakConfigSpan(@NonNull LineBreakConfig lineBreakConfig) {
        mLineBreakConfig = lineBreakConfig;
    }

    /**
     * Gets an associated line break config.
     * @return associated line break config.
     */
    public @NonNull LineBreakConfig getLineBreakConfig() {
        return mLineBreakConfig;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LineBreakConfigSpan)) return false;
        LineBreakConfigSpan that = (LineBreakConfigSpan) o;
        return Objects.equals(mLineBreakConfig, that.mLineBreakConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLineBreakConfig);
    }

    @Override
    public String toString() {
        return "LineBreakConfigSpan{mLineBreakConfig=" + mLineBreakConfig + '}';
    }
}
