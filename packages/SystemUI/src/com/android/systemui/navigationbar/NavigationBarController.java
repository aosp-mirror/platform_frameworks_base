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

package com.android.systemui.navigationbar;

import androidx.annotation.Nullable;

import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.systemui.shared.statusbar.phone.BarTransitions;

/** A controller to handle navigation bars. */
public interface NavigationBarController {
    /**
     * Creates navigation bars when car/status bar initializes.
     * <p>
     * TODO(b/117478341): I use {@code includeDefaultDisplay} to make this method compatible to
     * CarStatusBar because they have their own nav bar. Think about a better way for it.
     *
     * @param includeDefaultDisplay {@code true} to create navigation bar on default display.
     */
    void createNavigationBars(
            boolean includeDefaultDisplay,
            RegisterStatusBarResult result);

    /** Removes the navigation bar for the given display ID. */
    void removeNavigationBar(int displayId);

    /** @see NavigationBar#checkNavBarModes() */
    void checkNavBarModes(int displayId);

    /** @see NavigationBar#finishBarAnimations() */
    void finishBarAnimations(int displayId);

    /** @see NavigationBar#touchAutoDim() */
    void touchAutoDim(int displayId);

    /** @see NavigationBar#transitionTo(int, boolean) */
    void transitionTo(int displayId, @BarTransitions.TransitionMode int barMode, boolean animate);

    /** @see NavigationBar#disableAnimationsDuringHide(long) */
    void disableAnimationsDuringHide(int displayId, long delay);

    /** @return {@link NavigationBarView} on the default display. */
    @Nullable
    NavigationBarView getDefaultNavigationBarView();

    /**
     * @param displayId the ID of display which Navigation bar is on
     * @return {@link NavigationBarView} on the display with {@code displayId}.
     *         {@code null} if no navigation bar on that display.
     */
    @Nullable
    NavigationBarView getNavigationBarView(int displayId);

    boolean isOverviewEnabled(int displayId);

    /** @return {@link NavigationBar} on the default display. */
    @Nullable
    NavigationBar getDefaultNavigationBar();
}
