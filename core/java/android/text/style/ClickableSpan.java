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

package android.text.style;

import android.text.TextPaint;
import android.view.View;

/**
 * If an object of this type is attached to the text of a TextView
 * with a movement method of LinkMovementMethod, the affected spans of
 * text can be selected. If selected and clicked, the {@link #onClick} method will
 * be called.
 */
public abstract class ClickableSpan extends CharacterStyle implements UpdateAppearance {
    private static int sIdCounter = 0;

    private int mId = sIdCounter++;

    /**
     * Performs the click action associated with this span.
     */
    public abstract void onClick(View widget);

    /**
     * Makes the text underlined and in the link color.
     */
    @Override
    public void updateDrawState(TextPaint ds) {
        ds.setColor(ds.linkColor);
        ds.setUnderlineText(true);
    }

    /**
     * Get the unique ID for this span.
     *
     * @return The unique ID.
     * @hide
     */
    public int getId() {
        return mId;
    }
}
