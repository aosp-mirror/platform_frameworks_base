/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.widget;

import static android.view.DisplayCutout.NO_CUTOUT;
import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.makeMeasureSpec;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.DisplayCutout;
import android.view.View;
import android.view.View.OnApplyWindowInsetsListener;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toolbar;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ActionBarOverlayLayoutTest {

    private static final Insets TOP_INSET_5 = Insets.of(0, 5, 0, 0);
    private static final Insets TOP_INSET_25 = Insets.of(0, 25, 0, 0);
    private static final DisplayCutout CONSUMED_CUTOUT = null;
    private static final DisplayCutout CUTOUT_5 = new DisplayCutout(
            TOP_INSET_5,
            null /* boundLeft */,
            new Rect(100, 0, 200, 5),
            null /* boundRight */,
            null /* boundBottom*/);
    private static final int EXACTLY_1000 = makeMeasureSpec(1000, EXACTLY);

    private Context mContext;
    private TestActionBarOverlayLayout mLayout;

    private ViewGroup mContent;
    private ViewGroup mActionBarTop;
    private Toolbar mToolbar;
    private FakeOnApplyWindowListener mContentInsetsListener;


    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mLayout = new TestActionBarOverlayLayout(mContext);
        mLayout.makeOptionalFitsSystemWindows();

        mContent = createViewGroupWithId(com.android.internal.R.id.content);
        mContent.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        mLayout.addView(mContent);

        mContentInsetsListener = new FakeOnApplyWindowListener();
        mContent.setOnApplyWindowInsetsListener(mContentInsetsListener);

        mActionBarTop = new ActionBarContainer(mContext);
        mActionBarTop.setId(com.android.internal.R.id.action_bar_container);
        mActionBarTop.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, 20));
        mLayout.addView(mActionBarTop);
        mLayout.setActionBarHeight(20);

        mToolbar = new Toolbar(mContext);
        mToolbar.setId(com.android.internal.R.id.action_bar);
        mActionBarTop.addView(mToolbar);
    }

    @Test
    public void topInset_consumedCutout_stable() {
        mLayout.setStable(true);
        mLayout.dispatchApplyWindowInsets(insetsWith(TOP_INSET_5, CONSUMED_CUTOUT));

        assertThat(mContentInsetsListener.captured, nullValue());

        mLayout.measure(EXACTLY_1000, EXACTLY_1000);

        // Action bar height is added to the top inset
        assertThat(mContentInsetsListener.captured, is(insetsWith(TOP_INSET_25, CONSUMED_CUTOUT)));
    }

    @Test
    public void topInset_consumedCutout_notStable() {
        mLayout.dispatchApplyWindowInsets(insetsWith(TOP_INSET_5, CONSUMED_CUTOUT));

        assertThat(mContentInsetsListener.captured, nullValue());

        mLayout.measure(EXACTLY_1000, EXACTLY_1000);

        assertThat(mContentInsetsListener.captured, is(insetsWith(Insets.NONE, CONSUMED_CUTOUT)));
    }

    @Test
    public void topInset_noCutout_stable() {
        mLayout.setStable(true);
        mLayout.dispatchApplyWindowInsets(insetsWith(TOP_INSET_5, NO_CUTOUT));

        assertThat(mContentInsetsListener.captured, nullValue());

        mLayout.measure(EXACTLY_1000, EXACTLY_1000);

        // Action bar height is added to the top inset
        assertThat(mContentInsetsListener.captured, is(insetsWith(TOP_INSET_25, NO_CUTOUT)));
    }

    @Test
    public void topInset_noCutout_notStable() {
        mLayout.dispatchApplyWindowInsets(insetsWith(TOP_INSET_5, NO_CUTOUT));

        assertThat(mContentInsetsListener.captured, nullValue());

        mLayout.measure(EXACTLY_1000, EXACTLY_1000);

        assertThat(mContentInsetsListener.captured, is(insetsWith(Insets.NONE, NO_CUTOUT)));
    }

    @Test
    public void topInset_cutout_stable() {
        mLayout.setStable(true);
        mLayout.dispatchApplyWindowInsets(insetsWith(TOP_INSET_5, CUTOUT_5));

        assertThat(mContentInsetsListener.captured, nullValue());

        mLayout.measure(EXACTLY_1000, EXACTLY_1000);

        // Action bar height is added to the top inset
        assertThat(mContentInsetsListener.captured, is(insetsWith(TOP_INSET_25, CUTOUT_5)));
    }

    @Test
    public void topInset_cutout_notStable() {
        mLayout.dispatchApplyWindowInsets(insetsWith(TOP_INSET_5, CUTOUT_5));

        assertThat(mContentInsetsListener.captured, nullValue());

        mLayout.measure(EXACTLY_1000, EXACTLY_1000);

        assertThat(mContentInsetsListener.captured, is(insetsWith(Insets.NONE, NO_CUTOUT)));
    }

    private WindowInsets insetsWith(Insets content, DisplayCutout cutout) {
        return new WindowInsets(WindowInsets.createCompatTypeMap(content.toRect()), null, null,
                false, 0, 0, cutout, null, null, null, WindowInsets.Type.systemBars(), false,
                null, null, 0, 0);
    }

    private ViewGroup createViewGroupWithId(int id) {
        final FrameLayout v = new FrameLayout(mContext);
        v.setId(id);
        return v;
    }

    static class TestActionBarOverlayLayout extends ActionBarOverlayLayout {
        private boolean mStable;

        public TestActionBarOverlayLayout(Context context) {
            super(context);
        }

        @Override
        public WindowInsets computeSystemWindowInsets(WindowInsets in, Rect outLocalInsets) {
            if (mStable) {
                // Emulate the effect of makeOptionalFitsSystemWindows, because we can't do that
                // without being attached to a window.
                outLocalInsets.setEmpty();
                return in;
            }
            return super.computeSystemWindowInsets(in, outLocalInsets);
        }

        void setStable(boolean stable) {
            mStable = stable;
            setSystemUiVisibility(stable ? SYSTEM_UI_FLAG_LAYOUT_STABLE : 0);
        }

        @Override
        public int getWindowSystemUiVisibility() {
            return getSystemUiVisibility();
        }

        void setActionBarHeight(int height) {
            try {
                final Field field = ActionBarOverlayLayout.class.getDeclaredField(
                        "mActionBarHeight");
                field.setAccessible(true);
                field.setInt(this, height);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class FakeOnApplyWindowListener implements OnApplyWindowInsetsListener {
        WindowInsets captured;

        @Override
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            assertNotNull(insets);
            captured = insets;
            return v.onApplyWindowInsets(insets);
        }
    }
}
