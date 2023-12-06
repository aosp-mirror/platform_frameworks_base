/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.widget.scroll.arrowscroll;

import android.widget.ScrollViewScenario;

/**
 * One TextView with a text covering several pages. Padding is added
 * above and below the ScrollView.
 */
public class MultiPageTextWithPadding extends ScrollViewScenario {

    @Override
    protected void init(Params params) {

        String text = "This is a long text.";
        String longText = "First text.";
        for (int i = 0; i < 300; i++) {
            longText = longText + " " + text;
        }
        longText = longText + " Last text.";
        params.addTextView(longText, -1.0f).addPaddingToScrollView(50, 50);
    }
}
