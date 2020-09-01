/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.util;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.collect.Lists;

import java.util.List;

/**
 * Utility base class for creating scroll view scenarios, allowing you to add
 * a series of different kinds of views arranged vertically, taking up a
 * specified amount of the screen height.
 */
public abstract class ScrollViewScenario extends Activity {

    /**
     * Holds content of scroll view
     */
    private LinearLayout mLinearLayout;

    /**
     * The actual scroll view
     */
    private ScrollView mScrollView;


    /**
     * What we need of each view that the user wants: the view, and the ratio
     * to the screen height for its desired height.
     */
    private interface ViewFactory {
        View create(final Context context);

        float getHeightRatio();
    }

    /**
     * Partially implement ViewFactory given a height ratio.
     * A negative height ratio means that WRAP_CONTENT will be used as height
     */
    private static abstract class ViewFactoryBase implements ViewFactory {

        private float mHeightRatio;

        @SuppressWarnings({"UnusedDeclaration"})
        private ViewFactoryBase() {throw new UnsupportedOperationException("don't call this!");}

        protected ViewFactoryBase(float heightRatio) {
            mHeightRatio = heightRatio;
        }

        public float getHeightRatio() {
            return mHeightRatio;
        }
    }

    /**
     * Builder for selecting the views to be vertically arranged in the scroll
     * view.
     */
    @SuppressWarnings({"JavaDoc"})
    public static class Params {

        List<ViewFactory> mViewFactories = Lists.newArrayList();

        int mTopPadding = 0;
        int mBottomPadding = 0;

        /**
         * Add a text view.
         * @param text The text of the text view.
         * @param heightRatio The view's height will be this * the screen height.
         */
        public Params addTextView(final String text, float heightRatio) {
            mViewFactories.add(new ViewFactoryBase(heightRatio) {
                public View create(final Context context) {
                    final TextView tv = new TextView(context);
                    tv.setText(text);
                    return tv;
                }
            });
            return this;
        }

        /**
         * Add multiple text views.
         * @param numViews the number of views to add.
         * @param textPrefix The text to prepend to each text view.
         * @param heightRatio The view's height will be this * the screen height.
         */
        public Params addTextViews(int numViews, String textPrefix, float heightRatio) {
            for (int i = 0; i < numViews; i++) {
                addTextView(textPrefix + i, heightRatio);
            }
            return this;
        }

        /**
         * Add a button.
         * @param text The text of the button.
         * @param heightRatio The view's height will be this * the screen height.
         */
        public Params addButton(final String text, float heightRatio) {
            mViewFactories.add(new ViewFactoryBase(heightRatio) {
                public View create(final Context context) {
                    final Button button = new Button(context);
                    button.setText(text);
                    return button;
                }
            });
            return this;
        }

        /**
         * Add multiple buttons.
         * @param numButtons the number of views to add.
         * @param textPrefix The text to prepend to each button.
         * @param heightRatio The view's height will be this * the screen height.
         */
        public Params addButtons(int numButtons, String textPrefix, float heightRatio) {
            for (int i = 0; i < numButtons; i++) {
                addButton(textPrefix + i, heightRatio);
            }
            return this;
        }

        /**
         * Add an {@link InternalSelectionView}.
         * @param numRows The number of rows in the internal selection view.
         * @param heightRatio The view's height will be this * the screen height.
         */
        public Params addInternalSelectionView(final int numRows, float heightRatio) {
            mViewFactories.add(new ViewFactoryBase(heightRatio) {
                public View create(final Context context) {
                    return new InternalSelectionView(context, numRows, "isv");
                }
            });
            return this;
        }

        /**
         * Add a sublayout of buttons as a single child of the scroll view.
         * @param numButtons The number of buttons in the sub layout
         * @param heightRatio The layout's height will be this * the screen height.
         */
        public Params addVerticalLLOfButtons(final String prefix, final int numButtons, float heightRatio) {
            mViewFactories.add(new ViewFactoryBase(heightRatio) {

                public View create(Context context) {
                    final LinearLayout ll = new LinearLayout(context);
                    ll.setOrientation(LinearLayout.VERTICAL);

                    // fill width, equally weighted on height
                    final LinearLayout.LayoutParams lp =
                            new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
                    for (int i = 0; i < numButtons; i++) {
                        final Button button = new Button(context);
                        button.setText(prefix + i);
                        ll.addView(button, lp);
                    }

                    return ll;
                }
            });
            return this;
        }

        public Params addPaddingToScrollView(int topPadding, int bottomPadding) {
            mTopPadding = topPadding;
            mBottomPadding = bottomPadding;

            return this;
        }
    }

    /**
     * Override this and initialized the views in the scroll view.
     * @param params Used to configure the contents of the scroll view.
     */
    protected abstract void init(Params params);

    public LinearLayout getLinearLayout() {
        return mLinearLayout;
    }

    public ScrollView getScrollView() {
        return mScrollView;
    }

    /**
     * Get the child contained within the vertical linear layout of the
     * scroll view.
     * @param index The index within the linear layout.
     * @return the child within the vertical linear layout of the scroll view
     *   at the specified index.
     */
    @SuppressWarnings({"unchecked"})
    public <T extends View> T getContentChildAt(int index) {
        return (T) mLinearLayout.getChildAt(index);
    }

    /**
     * Hook for changing how scroll view's are created.
     */
    @SuppressWarnings({"JavaDoc"})
    protected ScrollView createScrollView() {
        return new ScrollView(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // for test stability, turn off title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        int screenHeight = getWindowManager().getCurrentWindowMetrics().getBounds().height()
                - 25;
        mLinearLayout = new LinearLayout(this);
        mLinearLayout.setOrientation(LinearLayout.VERTICAL);

        // initialize params
        final Params params = new Params();
        init(params);

        // create views specified by params
        for (ViewFactory viewFactory : params.mViewFactories) {
            int height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (viewFactory.getHeightRatio() >= 0) {
                height = (int) (viewFactory.getHeightRatio() * screenHeight);
            }
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height);
            mLinearLayout.addView(viewFactory.create(this), lp);
        }

        mScrollView = createScrollView();
        mScrollView.setPadding(0, params.mTopPadding, 0, params.mBottomPadding);
        mScrollView.addView(mLinearLayout, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // no animation to speed up tests
        mScrollView.setSmoothScrollingEnabled(false);

        setContentView(mScrollView);
        mScrollView.post(() -> mScrollView.restoreDefaultFocus());
    }
}
