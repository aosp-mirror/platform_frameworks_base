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
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;

public interface StatusIconDisplayable extends DarkReceiver {
    String getSlot();
    void setStaticDrawableColor(int color);
    void setDecorColor(int color);

    /** Sets the visible state that this displayable should be. */
    default void setVisibleState(@StatusBarIconView.VisibleState int state) {
        setVisibleState(state, false);
    }

    /**
     * Sets the visible state that this displayable should be, and whether the change should
     * animate.
     */
    void setVisibleState(@StatusBarIconView.VisibleState int state, boolean animate);

    /** Returns the current visible state of this displayable. */
    @StatusBarIconView.VisibleState
    int getVisibleState();

    /**
     * Returns true if this icon should be visible if there's space, and false otherwise.
     *
     * Note that this doesn't necessarily mean it *will* be visible. It's possible that there are
     * more icons than space, in which case this icon might just show a dot or might be completely
     * hidden. {@link #getVisibleState} will return the icon's actual visible status.
     */
    boolean isIconVisible();

    default boolean isIconBlocked() {
        return false;
    }
}
