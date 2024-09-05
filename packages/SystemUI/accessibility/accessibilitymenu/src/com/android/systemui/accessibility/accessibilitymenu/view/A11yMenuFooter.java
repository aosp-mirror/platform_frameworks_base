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

package com.android.systemui.accessibility.accessibilitymenu.view;

import static android.view.View.LAYOUT_DIRECTION_LTR;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.TouchDelegate;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.widget.ImageButton;

import androidx.annotation.Nullable;

import com.android.systemui.accessibility.accessibilitymenu.R;

/**
 * This class is for Accessibility menu footer layout. Handles switching between a11y menu pages.
 */
public class A11yMenuFooter {

    /** Provides an interface for footer of a11yMenu. */
    public interface A11yMenuFooterCallBack {

        /** Calls back when user clicks the left button. */
        void onNextButtonClicked();

        /** Calls back when user clicks the right button. */
        void onPreviousButtonClicked();
    }

    private final FooterButtonClickListener mFooterButtonClickListener;

    private ImageButton mPageLeftBtn;
    private ImageButton mPageRightBtn;
    private View mTopListDivider;
    private View mBottomListDivider;
    private final A11yMenuFooterCallBack mCallBack;
    private final ViewGroup mMenuLayout;
    private ViewGroup mFooterContainer;
    private int mFooterContainerBaseHeight = 0;
    private int mRightToLeftDirection = LAYOUT_DIRECTION_LTR;

    public A11yMenuFooter(ViewGroup menuLayout, A11yMenuFooterCallBack callBack) {
        this.mCallBack = callBack;
        mFooterButtonClickListener = new FooterButtonClickListener();
        configureFooterLayout(menuLayout);
        mMenuLayout = menuLayout;
    }

    public @Nullable ImageButton getPreviousPageBtn() {
        return mRightToLeftDirection == LAYOUT_DIRECTION_LTR
                ? mPageLeftBtn : mPageRightBtn;
    }

    public @Nullable ImageButton getNextPageBtn() {
        return mRightToLeftDirection == LAYOUT_DIRECTION_LTR
                ? mPageRightBtn : mPageLeftBtn;
    }

    void adjustFooterToDensityScale(float densityScale) {
        mFooterContainer.getLayoutParams().height =
                (int) (mFooterContainerBaseHeight / densityScale);
    }

    int getHeight() {
        return mFooterContainer.getLayoutParams().height;
    }

    /** Sets right to left direction of footer. */
    public void updateRightToLeftDirection(Configuration configuration) {
        mRightToLeftDirection = TextUtils.getLayoutDirectionFromLocale(
                configuration.getLocales().get(0));
        getPreviousPageBtn().setContentDescription(mMenuLayout.getResources().getString(
                R.string.previous_button_content_description));
        getNextPageBtn().setContentDescription(mMenuLayout.getResources().getString(
                R.string.next_button_content_description));
    }

    private void configureFooterLayout(ViewGroup menuLayout) {
        mFooterContainer = menuLayout.findViewById(R.id.footerlayout);
        mFooterContainer.setVisibility(View.VISIBLE);
        mFooterContainerBaseHeight = mFooterContainer.getLayoutParams().height;

        mPageLeftBtn = menuLayout.findViewById(R.id.menu_left_button);
        mPageRightBtn = menuLayout.findViewById(R.id.menu_right_button);
        mTopListDivider = menuLayout.findViewById(R.id.top_listDivider);
        mBottomListDivider = menuLayout.findViewById(R.id.bottom_listDivider);

        // Registers listeners for footer buttons.
        setListener(mPageLeftBtn);
        setListener(mPageRightBtn);

        menuLayout
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        new OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                menuLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                expandBtnTouchArea(mPageLeftBtn, menuLayout);
                                expandBtnTouchArea(mPageRightBtn, (View) mPageRightBtn.getParent());
                            }
                        });
    }

    private void expandBtnTouchArea(ImageButton btn, View btnParent) {
        Rect btnRect = new Rect();
        btn.getHitRect(btnRect);
        btnRect.top -= getHitRectHeight(mTopListDivider);
        btnRect.bottom += getHitRectHeight(mBottomListDivider);
        btnParent.setTouchDelegate(new TouchDelegate(btnRect, btn));
    }

    private static int getHitRectHeight(View listDivider) {
        Rect hitRect = new Rect();
        listDivider.getHitRect(hitRect);
        return hitRect.height();
    }

    private void setListener(@Nullable View view) {
        if (view != null) {
            view.setOnClickListener(mFooterButtonClickListener);
        }
    }

    /** Handles click event for footer buttons. */
    private class FooterButtonClickListener implements OnClickListener {
        @Override
        public void onClick(View view) {
            if (view.getId() == getPreviousPageBtn().getId()) {
                mCallBack.onPreviousButtonClicked();
            } else if (view.getId() == getNextPageBtn().getId()) {
                mCallBack.onNextButtonClicked();
            }
        }
    }
}
