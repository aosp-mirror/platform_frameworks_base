/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.accessibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;

@MediumTest
public class FindViewByIdTest {

    @Rule
    public ActivityTestRule<Activity> mActivityRule = new ActivityTestRule<>(Activity.class);

    private Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    private Activity getActivity() {
        return mActivityRule.getActivity();
    }

    @UiThreadTest
    @Test
    public void testFindViewById() {
        LinearLayout contentView = new LinearLayout(getContext());
        getActivity().setContentView(contentView);
        View child1 = new View(getContext());
        View child2 = new View(getContext());
        child1.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        child2.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        contentView.addView(child1);
        contentView.addView(child2);
        View result = AccessibilityNodeIdManager.getInstance().findView(
                child2.getAccessibilityViewId());
        assertEquals(result, child2);
    }

    @UiThreadTest
    @Test
    public void testFindViewByIdReturnNullIfRemovedFromHierarchy() {
        LinearLayout contentView = new LinearLayout(getContext());
        getActivity().setContentView(contentView);
        View child1 = new View(getContext());
        View child2 = new View(getContext());
        contentView.addView(child1);
        contentView.addView(child2);
        child1.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        child2.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        contentView.removeView(child1);
        View result = AccessibilityNodeIdManager.getInstance().findView(
                child1.getAccessibilityViewId());
        assertNull(result);
    }

    @UiThreadTest
    @Test
    public void testFindViewByIdReturnNullIfNotImportant() {
        LinearLayout contentView = new LinearLayout(getContext());
        getActivity().setContentView(contentView);
        View child1 = new View(getContext());
        View child2 = new View(getContext());
        child2.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        contentView.addView(child1);
        contentView.addView(child2);

        View result = AccessibilityNodeIdManager.getInstance().findView(
                child1.getAccessibilityViewId());
        assertNull(result);
    }
}
