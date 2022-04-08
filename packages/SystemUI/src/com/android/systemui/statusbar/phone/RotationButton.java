/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.view.View;

import com.android.systemui.statusbar.policy.KeyButtonDrawable;

/** Interface of a rotation button that interacts {@link RotationButtonController}. */
interface RotationButton {
    void setRotationButtonController(RotationButtonController rotationButtonController);
    View getCurrentView();
    boolean show();
    boolean hide();
    boolean isVisible();
    void updateIcon();
    void setOnClickListener(View.OnClickListener onClickListener);
    void setOnHoverListener(View.OnHoverListener onHoverListener);
    KeyButtonDrawable getImageDrawable();
    void setDarkIntensity(float darkIntensity);
    default void setCanShowRotationButton(boolean canShow) {}
    default boolean acceptRotationProposal() {
        return getCurrentView() != null;
    }
}
