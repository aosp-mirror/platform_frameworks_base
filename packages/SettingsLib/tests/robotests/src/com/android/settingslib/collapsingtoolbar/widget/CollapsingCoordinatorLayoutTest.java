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
package com.android.settingslib.collapsingtoolbar.widget;

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.settingslib.collapsingtoolbar.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link CollapsingCoordinatorLayout}. */
@RunWith(RobolectricTestRunner.class)
public class CollapsingCoordinatorLayoutTest {
    private static final String TEXT_HELLO_WORLD = "Hello World!";
    private static final String TEST_TITLE = "RENO NAKAMURA";

    private TestActivity mActivity;

    @Before
    public void setUp() {
        mActivity = Robolectric.buildActivity(TestActivity.class).create().get();
    }

    @Test
    public void onCreate_childViewsNumberShouldBeTwo() {
        CollapsingCoordinatorLayout layout = mActivity.getCollapsingCoordinatorLayout();

        assertThat(layout.getChildCount()).isEqualTo(2);
    }

    @Test
    public void onCreate_userAddedChildViewsBeMovedToContentFrame() {
        CollapsingCoordinatorLayout layout = mActivity.getCollapsingCoordinatorLayout();
        View contentFrameView = layout.findViewById(R.id.content_frame);

        TextView textView = contentFrameView.findViewById(com.android.settingslib.robotests.R.id.text_hello_world);

        assertThat(textView).isNotNull();
        assertThat(textView.getText().toString()).isEqualTo(TEXT_HELLO_WORLD);
    }

    @Test
    public void initSettingsStyleToolBar_assignedTitle() {
        CollapsingCoordinatorLayout layout = mActivity.getCollapsingCoordinatorLayout();

        layout.initSettingsStyleToolBar(mActivity, TEST_TITLE);

        assertThat(layout.getCollapsingToolbarLayout().getTitle().toString()).isEqualTo(TEST_TITLE);
    }

    public static class TestActivity extends Activity {
        private CollapsingCoordinatorLayout mCollapsingCoordinatorLayout;

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setTheme(android.R.style.Theme_Light_NoTitleBar);
            setContentView(com.android.settingslib.robotests.R.layout.collapsing_test_layout);
            mCollapsingCoordinatorLayout = findViewById(com.android.settingslib.robotests.R.id.id_collapsing_test);
        }

        public CollapsingCoordinatorLayout getCollapsingCoordinatorLayout() {
            return mCollapsingCoordinatorLayout;
        }
    }
}
