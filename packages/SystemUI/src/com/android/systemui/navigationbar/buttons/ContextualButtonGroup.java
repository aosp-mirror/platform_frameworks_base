/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar.buttons;

import android.annotation.IdRes;
import android.annotation.NonNull;
import android.view.View;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class ContextualButtonGroup extends ButtonDispatcher {
    private static final int INVALID_INDEX = -1;

    // List of pairs that contains the button and if the button was visible within this group
    private final List<ButtonData> mButtonData = new ArrayList<>();

    public ContextualButtonGroup(@IdRes int containerId) {
        super(containerId);
    }

    /**
     * Add a contextual button to the group. The order of adding increases in its priority. The
     * priority is used to determine which button should be visible when setting multiple button's
     * visibility {@see setButtonVisibility}.
     * @param button the button added to the group
     */
    public void addButton(@NonNull ContextualButton button) {
        // By default buttons in the context group are not visible until
        // {@link #setButtonVisibility()) is called to show one of the buttons
        button.setVisibility(View.INVISIBLE);
        button.attachToGroup(this);
        mButtonData.add(new ButtonData(button));
    }

    /**
     * Removes a contextual button from the group.
     */
    public void removeButton(@IdRes int buttonResId) {
        int index = getContextButtonIndex(buttonResId);
        if (index != INVALID_INDEX) {
            mButtonData.remove(index);
        }
    }

    public ContextualButton getContextButton(@IdRes int buttonResId) {
        int index = getContextButtonIndex(buttonResId);
        if (index != INVALID_INDEX) {
            return mButtonData.get(index).button;
        }
        return null;
    }

    public ContextualButton getVisibleContextButton() {
        for (int i = mButtonData.size() - 1; i >= 0; --i) {
            if (mButtonData.get(i).markedVisible) {
                return mButtonData.get(i).button;
            }
        }
        return null;
    }

    /**
     * Set the visibility of the button by {@param buttonResId} with {@param visible}. Only one
     * button is shown at a time. The input button will only show up if it has higher priority than
     * a previous button, otherwise it will be marked as visible and shown later if all higher
     * priority buttons are invisible. Therefore hiding a button will show the next marked visible
     * button. This group's view will be visible if at least one button is visible.
     * @return if the button is visible after operation
     * @throws RuntimeException if the input id does not match any of the ids in the group
     */
    public int setButtonVisibility(@IdRes int buttonResId, boolean visible) {
        final int index = getContextButtonIndex(buttonResId);
        if (index == INVALID_INDEX) {
            throw new RuntimeException("Cannot find the button id of " + buttonResId
                    + " in context group");
        }
        setVisibility(View.INVISIBLE);
        mButtonData.get(index).markedVisible = visible;

        // Make all buttons invisible except the first markedVisible button
        boolean alreadyFoundVisibleButton = false;
        int i = mButtonData.size() - 1;
        for (; i >= 0; --i) {
            final ButtonData buttonData = mButtonData.get(i);
            if (!alreadyFoundVisibleButton && buttonData.markedVisible) {
                buttonData.setVisibility(View.VISIBLE);
                setVisibility(View.VISIBLE);
                alreadyFoundVisibleButton = true;
            } else {
                buttonData.setVisibility(View.INVISIBLE);
            }
        }
        return mButtonData.get(index).button.getVisibility();
    }

    /**
     * See if button is group visible. Group visible determines if a button can be visible when
     * higher priority buttons go invisible.
     * @param buttonResId the button to see if it is group visible
     * @return true if button is group visible
     */
    public boolean isButtonVisibleWithinGroup(@IdRes int buttonResId) {
        final int index = getContextButtonIndex(buttonResId);
        return index != INVALID_INDEX && mButtonData.get(index).markedVisible;
    }

    /**
     * Update all the icons that are attached to this group. This will get all the buttons to update
     * their icons for their buttons.
     */
    public void updateIcons(int lightIconColor, int darkIconColor) {
        for (ButtonData data : mButtonData) {
            data.button.updateIcon(lightIconColor, darkIconColor);
        }
    }

    public void dump(PrintWriter pw) {
        View view = getCurrentView();
        pw.println("ContextualButtonGroup");
        pw.println("  getVisibleContextButton(): " + getVisibleContextButton());
        pw.println("  isVisible(): " + isVisible());
        pw.println("  attached(): " + (view != null && view.isAttachedToWindow()));
        pw.println("  mButtonData [ ");
        for (int i = mButtonData.size() - 1; i >= 0; --i) {
            final ButtonData data = mButtonData.get(i);
            view = data.button.getCurrentView();
            pw.println("    " + i + ": markedVisible=" + data.markedVisible
                    + " visible=" + data.button.getVisibility()
                    + " attached=" + (view != null && view.isAttachedToWindow())
                    + " alpha=" + data.button.getAlpha());
        }
        pw.println("  ]");
    }

    private int getContextButtonIndex(@IdRes int buttonResId) {
        for (int i = 0; i < mButtonData.size(); ++i) {
            if (mButtonData.get(i).button.getId() == buttonResId) {
                return i;
            }
        }
        return INVALID_INDEX;
    }

    private final static class ButtonData {
        ContextualButton button;
        boolean markedVisible;

        ButtonData(ContextualButton button) {
            this.button = button;
            this.markedVisible = false;
        }

        void setVisibility(int visiblity) {
            button.setVisibility(visiblity);
        }
    }
}
