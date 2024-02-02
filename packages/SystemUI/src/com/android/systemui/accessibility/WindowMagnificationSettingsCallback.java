/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static com.android.systemui.accessibility.WindowMagnificationSettings.MagnificationSize;

/**
 * A callback to inform WindowMagnificationController about
 * the setting value change or the user interaction.
 */
public interface WindowMagnificationSettingsCallback {

    /**
     * Called when change magnification size.
     *
     * @param index Magnification size index.
     * 0 : MagnificationSize.NONE, 1 : MagnificationSize.SMALL,
     * 2 : MagnificationSize.MEDIUM, 3: MagnificationSize.LARGE,
     * 4 : MagnificationSize.FULLSCREEN
     */
    void onSetMagnifierSize(@MagnificationSize int index);

    /**
     * Called when set allow diagonal scrolling.
     *
     * @param enable Allow diagonal scrolling enable value.
     */
    void onSetDiagonalScrolling(boolean enable);

    /**
     * Called when change magnification size on free mode.
     *
     * @param enable Free mode enable value.
     */
    void onEditMagnifierSizeMode(boolean enable);

    /**
     * Called when set magnification scale.
     *
     * @param scale Magnification scale value.
     * @param updatePersistence whether the scale should be persisted
     */
    void onMagnifierScale(float scale, boolean updatePersistence);

    /**
     * Called when magnification mode changed.
     *
     * @param newMode Magnification mode
     * 1 : ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN, 2 : ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
     */
    void onModeSwitch(int newMode);

    /**
     * Called when the visibility of the magnification settings panel changed.
     *
     * @param shown The visibility of the magnification settings panel.
     */
    void onSettingsPanelVisibilityChanged(boolean shown);
}
