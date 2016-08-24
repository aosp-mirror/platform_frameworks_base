/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.content.res;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources.Theme;
import android.util.AttributeSet;

import java.io.IOException;

/**
 * Class that provides access to the {@link GradientColor#createFromXmlInner(Resources,
 * XmlPullParser, AttributeSet, Theme)} and {@link ColorStateList#createFromXmlInner(Resources,
 * XmlPullParser, AttributeSet, Theme)} methods
 */
public class ComplexColor_Accessor {
    public static GradientColor createGradientColorFromXmlInner(@NonNull Resources r,
            @NonNull XmlPullParser parser, @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws IOException, XmlPullParserException {
        return GradientColor.createFromXmlInner(r, parser, attrs, theme);
    }

    public static ColorStateList createColorStateListFromXmlInner(@NonNull Resources r,
            @NonNull XmlPullParser parser, @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws IOException, XmlPullParserException {
        return ColorStateList.createFromXmlInner(r, parser, attrs, theme);
    }
}
