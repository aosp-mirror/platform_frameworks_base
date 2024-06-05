/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.widget.scroll;

import android.widget.ScrollViewScenario;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Two buttons sandwiching a tall text view (good for testing panning across
 * before getting to next button).
 */
public class ButtonsWithTallTextViewInBetween extends ScrollViewScenario {

    public Button getTopButton() {
        return getContentChildAt(0);
    }

    public TextView getMiddleFiller() {
        return getContentChildAt(1);
    }

    public Button getBottomButton() {
        LinearLayout ll = getContentChildAt(2);
        return (Button) ll.getChildAt(0);
    }

    protected void init(Params params) {
        
        params.addButton("top button", 0.2f)
                .addTextView("middle filler", 1.51f)
                .addVerticalLLOfButtons("bottom", 1, 0.2f);
    }
}
