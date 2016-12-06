/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.app;

import android.content.Context;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.internal.app.AlertController;
import com.android.internal.R;

public class MicroAlertController extends AlertController {
    public MicroAlertController(Context context, DialogInterface di, Window window) {
        super(context, di, window);
    }

    @Override
    protected void setupContent(ViewGroup contentPanel) {
        // Special case for small screen - the scroll view is higher in hierarchy
        mScrollView = (ScrollView) mWindow.findViewById(R.id.scrollView);

        // Special case for users that only want to display a String
        mMessageView = (TextView) contentPanel.findViewById(R.id.message);
        if (mMessageView == null) {
            return;
        }

        if (mMessage != null) {
            mMessageView.setText(mMessage);
        } else {
            // no message, remove associated views
            mMessageView.setVisibility(View.GONE);
            contentPanel.removeView(mMessageView);

            if (mListView != null) {
                // has ListView, swap scrollView with ListView

                // move topPanel into top of scrollParent
                View topPanel = mScrollView.findViewById(R.id.topPanel);
                ((ViewGroup) topPanel.getParent()).removeView(topPanel);
                FrameLayout.LayoutParams topParams =
                        new FrameLayout.LayoutParams(topPanel.getLayoutParams());
                topParams.gravity = Gravity.TOP;
                topPanel.setLayoutParams(topParams);

                // move buttonPanel into bottom of scrollParent
                View buttonPanel = mScrollView.findViewById(R.id.buttonPanel);
                ((ViewGroup) buttonPanel.getParent()).removeView(buttonPanel);
                FrameLayout.LayoutParams buttonParams =
                        new FrameLayout.LayoutParams(buttonPanel.getLayoutParams());
                buttonParams.gravity = Gravity.BOTTOM;
                buttonPanel.setLayoutParams(buttonParams);

                // remove scrollview
                final ViewGroup scrollParent = (ViewGroup) mScrollView.getParent();
                final int childIndex = scrollParent.indexOfChild(mScrollView);
                scrollParent.removeViewAt(childIndex);

                // add list view
                scrollParent.addView(mListView,
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));

                // add top and button panel
                scrollParent.addView(topPanel);
                scrollParent.addView(buttonPanel);
            } else {
                // no content, just hide everything
                contentPanel.setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void setupTitle(ViewGroup topPanel) {
        super.setupTitle(topPanel);
        if (topPanel.getVisibility() == View.GONE) {
            topPanel.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    protected void setupButtons(ViewGroup buttonPanel) {
        super.setupButtons(buttonPanel);
        if (buttonPanel.getVisibility() == View.GONE) {
            buttonPanel.setVisibility(View.INVISIBLE);
        }
    }
}
