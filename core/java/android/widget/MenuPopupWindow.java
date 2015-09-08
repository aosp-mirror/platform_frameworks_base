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
import android.content.res.Configuration;
import android.content.res.Resources;
import android.transition.Transition;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.android.internal.view.menu.ListMenuItemView;
import com.android.internal.view.menu.MenuAdapter;
import com.android.internal.view.menu.MenuBuilder;

/**
 * A MenuPopupWindow represents the popup window for menu.
 *
 * MenuPopupWindow is mostly same as ListPopupWindow, but it has customized
 * behaviors specific to menus,
 *
 * @hide
 */
public class MenuPopupWindow extends ListPopupWindow implements MenuItemHoverListener {
    private MenuItemHoverListener mHoverListener;

    public MenuPopupWindow(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    DropDownListView createDropDownListView(Context context, boolean hijackFocus) {
        MenuDropDownListView view = new MenuDropDownListView(context, hijackFocus);
        view.setHoverListener(this);
        return view;
    }

    public void setEnterTransition(Transition enterTransition) {
        mPopup.setEnterTransition(enterTransition);
    }

    public void setExitTransition(Transition exitTransition) {
        mPopup.setExitTransition(exitTransition);
    }

    public void setHoverListener(MenuItemHoverListener hoverListener) {
        mHoverListener = hoverListener;
    }

    /**
     * Set whether this window is touch modal or if outside touches will be sent to
     * other windows behind it.
     */
    public void setTouchModal(boolean touchModal) {
        mPopup.setTouchModal(touchModal);
    }

    @Override
    public void onItemHovered(MenuBuilder menu, int position) {
        // Forward up the chain
        if (mHoverListener != null) {
            mHoverListener.onItemHovered(menu, position);
        }
    }

    /**
     * @hide
     */
    public static class MenuDropDownListView extends DropDownListView {
        final int mAdvanceKey;
        final int mRetreatKey;

        private MenuItemHoverListener mHoverListener;

        public MenuDropDownListView(Context context, boolean hijackFocus) {
            super(context, hijackFocus);

            final Resources res = context.getResources();
            final Configuration config = res.getConfiguration();
            if (config.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                mAdvanceKey = KeyEvent.KEYCODE_DPAD_LEFT;
                mRetreatKey = KeyEvent.KEYCODE_DPAD_RIGHT;
            } else {
                mAdvanceKey = KeyEvent.KEYCODE_DPAD_RIGHT;
                mRetreatKey = KeyEvent.KEYCODE_DPAD_LEFT;
            }
        }

        public void setHoverListener(MenuItemHoverListener hoverListener) {
            mHoverListener = hoverListener;
        }

        public void clearSelection() {
            setSelectedPositionInt(INVALID_POSITION);
            setNextSelectedPositionInt(INVALID_POSITION);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            ListMenuItemView selectedItem = (ListMenuItemView) getSelectedView();
            if (selectedItem != null && keyCode == mAdvanceKey) {
                if (selectedItem.isEnabled() &&
                        ((ListMenuItemView) selectedItem).getItemData().hasSubMenu()) {
                    performItemClick(
                            selectedItem,
                            getSelectedItemPosition(),
                            getSelectedItemId());
                }
                return true;
            } else if (selectedItem != null && keyCode == mRetreatKey) {
                setSelectedPositionInt(-1);
                setNextSelectedPositionInt(-1);

                ((MenuAdapter) getAdapter()).getAdapterMenu().close();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean onHoverEvent(MotionEvent ev) {
            boolean dispatchHover = false;
            final int position = pointToPosition((int) ev.getX(), (int) ev.getY());

            final int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER
                    || action == MotionEvent.ACTION_HOVER_MOVE) {
                if (position != INVALID_POSITION && position != mSelectedPosition) {
                    final View hoveredItem = getChildAt(position - getFirstVisiblePosition());
                    if (hoveredItem.isEnabled()) {
                        dispatchHover = true;
                    }
                }
            }

            boolean superVal = super.onHoverEvent(ev);

            if (dispatchHover && mHoverListener != null) {
                mHoverListener.onItemHovered(
                        ((MenuAdapter) getAdapter()).getAdapterMenu(), position);
            }

            return superVal;
        }
    }


}