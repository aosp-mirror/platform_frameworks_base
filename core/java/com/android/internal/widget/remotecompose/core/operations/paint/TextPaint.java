/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.paint;

import android.annotation.NonNull;

// TODO: this interface is unused. Delete it.
public interface TextPaint {
    void setARGB(int a, int r, int g, int b);

    void setDither(boolean dither);

    void setElegantTextHeight(boolean elegant);

    void setEndHyphenEdit(int endHyphen);

    void setFakeBoldText(boolean fakeBoldText);

    void setFlags(int flags);

    void setFontFeatureSettings(@NonNull String settings);

    void setHinting(int mode);

    void setLetterSpacing(float letterSpacing);

    void setLinearText(boolean linearText);

    void setShadowLayer(float radius, float dx, float dy, int shadowColor);

    void setStartHyphenEdit(int startHyphen);

    void setStrikeThruText(boolean strikeThruText);

    void setStrokeCap(int cap);

    void setSubpixelText(boolean subpixelText);

    void setTextAlign(int align);

    void setTextLocale(int locale);

    void setTextLocales(int localesArray);

    void setTextScaleX(float scaleX);

    void setTextSize(float textSize);

    void setTextSkewX(float skewX);

    void setUnderlineText(boolean underlineText);

    void setWordSpacing(float wordSpacing);
}
