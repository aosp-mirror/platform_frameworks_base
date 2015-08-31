/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

/**
 * A MenuPopupWindow represents the popup window for menu.
 *
 * MenuPopupWindow is mostly same as ListPopupWindow, but it has customized
 * behaviors specific to menus,
 *
 * @hide
 */
public class MenuPopupWindow extends ListPopupWindow {
    public MenuPopupWindow(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    DropDownListView createDropDownListView(Context context, boolean hijackFocus) {
        return new MenuDropDownListView(context, hijackFocus);
    }

    static class MenuDropDownListView extends DropDownListView {
        private boolean mHoveredOnDisabledItem = false;
        private AccessibilityManager mAccessibilityManager;

        MenuDropDownListView(Context context, boolean hijackFocus) {
            super(context, hijackFocus);
            mAccessibilityManager = (AccessibilityManager) getContext().getSystemService(
                    Context.ACCESSIBILITY_SERVICE);
        }

        @Override
        protected boolean shouldShowSelector() {
            return (isHovered() && !mHoveredOnDisabledItem) || super.shouldShowSelector();
        }

        @Override
        public boolean onHoverEvent(MotionEvent ev) {
            mHoveredOnDisabledItem = false;

            // Accessibility system should already handle hover events and selections, menu does
            // not have to handle it by itself.
            if (mAccessibilityManager.isTouchExplorationEnabled()) {
                return super.onHoverEvent(ev);
            }

            final int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER
                    || action == MotionEvent.ACTION_HOVER_MOVE) {
                final int position = pointToPosition((int) ev.getX(), (int) ev.getY());
                if (position != INVALID_POSITION && position != mSelectedPosition) {
                    final View hoveredItem = getChildAt(position - getFirstVisiblePosition());
                    if (hoveredItem.isEnabled()) {
                        positionSelector(position, hoveredItem);
                        setSelectedPositionInt(position);
                    } else {
                        mHoveredOnDisabledItem = true;
                    }
                    updateSelectorState();
                }
            } else {
                // Do not cancel the selected position if the selection is visible by other reasons.
                if (!super.shouldShowSelector()) {
                    setSelectedPositionInt(INVALID_POSITION);
                }
            }
            return super.onHoverEvent(ev);
        }
    }
}
