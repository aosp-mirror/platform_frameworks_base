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
package com.android.internal.widget.remotecompose.core.operations.layout;

/**
 * Represent scroll delegates components.
 *
 * <p>Components have scroll X & Y properties. We can inject a scroll delegate as a modifier (e.g. a
 * scrollView, a marquee...) to control the value of those properties.
 */
public interface ScrollDelegate {

    /**
     * Returns the horizontal scroll value
     *
     * @param currentValue the current value
     * @return the value set by the delegate
     */
    float getScrollX(float currentValue);

    /**
     * Returns the vertical scroll value
     *
     * @param currentValue the current value
     * @return the value set by the delegate
     */
    float getScrollY(float currentValue);

    /**
     * Returns true if the delegate can handle horizontal scroll
     *
     * @return true if the delegate handles horizontal scrolling
     */
    boolean handlesHorizontalScroll();

    /**
     * Returns true if the delegate can handle vertical scroll
     *
     * @return true if the delegate handles vertical scrolling
     */
    boolean handlesVerticalScroll();

    /** Reset the delegate (e.g. the content of the component has changed) */
    void reset();
}
