/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.qs;

import android.view.View;

import androidx.annotation.Nullable;

/**
 * The bottom footer of the quick settings panel.
 */
public interface QSFooter {
    /**
     * Sets the given {@link QSPanel} to be the one that will display the quick settings.
     */
    void setQSPanel(@Nullable QSPanel panel);

    /**
     * Sets whether or not the footer should be visible.
     *
     * @param visibility One of {@link View#VISIBLE}, {@link View#INVISIBLE} or {@link View#GONE}.
     * @see View#setVisibility(int)
     */
    void setVisibility(int visibility);

    /**
     * Sets whether the footer is in an expanded state.
     */
    void setExpanded(boolean expanded);

    /**
     * Returns the full height of the footer.
     */
    int getHeight();

    /**
     * Sets the percentage amount that the quick settings has been expanded.
     *
     * @param expansion A value from 1 to 0 that indicates how much the quick settings have been
     *                  expanded. 1 is fully expanded.
     */
    void setExpansion(float expansion);

    /**
     * Sets whether or not this footer should set itself to listen for changes in any callbacks
     * that it has implemented.
     */
    void setListening(boolean listening);

    /**
     * Sets whether or not the keyguard is currently being shown.
     */
    void setKeyguardShowing(boolean keyguardShowing);

    /**
     * Sets the {@link android.view.View.OnClickListener to be used on elements that expend QS.
     */
    void setExpandClickListener(View.OnClickListener onClickListener);

    default void disable(int state1, int state2, boolean animate) {}
}
