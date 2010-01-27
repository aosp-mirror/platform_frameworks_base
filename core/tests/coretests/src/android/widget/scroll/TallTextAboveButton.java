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

import android.util.ScrollViewScenario;

/**
 * An (unfocusable) text view that takes up more than the height
 * of the screen followed by a button.
 */
public class TallTextAboveButton extends ScrollViewScenario {

    protected void init(Params params) {
        params.addTextView("top tall", 1.1f)
                .addButton("button", 0.2f);
    }
}
