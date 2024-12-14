/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.widget;

import static android.view.flags.Flags.FLAG_VIEW_VELOCITY_API;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.AttributeSet;
import android.util.PollingCheck;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;

import com.android.compatibility.common.util.WidgetTestUtils;
import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class AbsListViewFunctionalTest {
    private final String[] mCountryList = new String[] {
        "Argentina", "Australia", "Belize", "Botswana", "Brazil", "Cameroon", "China", "Cyprus",
        "Denmark", "Djibouti", "Ethiopia", "Fiji", "Finland", "France", "Gabon", "Germany",
        "Ghana", "Haiti", "Honduras", "Iceland", "India", "Indonesia", "Ireland", "Italy",
        "Japan", "Kiribati", "Laos", "Lesotho", "Liberia", "Malaysia", "Mongolia", "Myanmar",
        "Nauru", "Norway", "Oman", "Pakistan", "Philippines", "Portugal", "Romania", "Russia",
        "Rwanda", "Singapore", "Slovakia", "Slovenia", "Somalia", "Swaziland", "Togo", "Tuvalu",
        "Uganda", "Ukraine", "United States", "Vanuatu", "Venezuela", "Zimbabwe"
    };
    private AbsListViewActivity mActivity;
    private MyListView mMyListView;

    @Rule
    public ActivityTestRule<AbsListViewActivity> mActivityRule = new ActivityTestRule<>(
            AbsListViewActivity.class);

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws Exception {
        mActivity = mActivityRule.getActivity();
        mMyListView = (MyListView) mActivity.findViewById(R.id.list_view);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void testLsitViewSetVelocity() throws Throwable {
        final ArrayList<String> items = new ArrayList<>(Arrays.asList(mCountryList));
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(mActivity,
                android.R.layout.simple_list_item_1, items);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mMyListView,
                () -> mMyListView.setAdapter(adapter));
        mActivityRule.runOnUiThread(() -> {
            // Create an adapter to display the list
            mMyListView.setFrameContentVelocity(0);
        });
        // set setFrameContentVelocity shouldn't do anything.
        assertEquals(mMyListView.isSetVelocityCalled, false);

        mActivityRule.runOnUiThread(() -> {
            mMyListView.fling(100);
        });
        PollingCheck.waitFor(100, () -> mMyListView.isSetVelocityCalled);
        // set setFrameContentVelocity should be called when fling.
        assertTrue(mMyListView.isSetVelocityCalled);
    }

    public static class MyListView extends ListView {

        public boolean isSetVelocityCalled;

        public MyListView(Context context) {
            super(context);
        }

        public MyListView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public MyListView(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
        }

        public MyListView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
        }

        @Override
        public void setFrameContentVelocity(float pixelsPerSecond) {
            if (pixelsPerSecond != 0) {
                isSetVelocityCalled = true;
            }
        }
    }
}
