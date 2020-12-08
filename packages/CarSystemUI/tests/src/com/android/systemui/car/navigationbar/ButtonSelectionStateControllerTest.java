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

package com.android.systemui.car.navigationbar;

import static com.google.common.truth.Truth.assertThat;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.tests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class ButtonSelectionStateControllerTest extends SysuiTestCase {

    private static final String TEST_COMPONENT_NAME_PACKAGE = "com.android.car.carlauncher";
    private static final String TEST_COMPONENT_NAME_CLASS = ".CarLauncher";
    private static final String TEST_CATEGORY = "com.google.android.apps.maps";
    private static final String TEST_CATEGORY_CLASS = ".APP_MAPS";
    private static final String TEST_PACKAGE = "com.android.car.dialer";
    private static final String TEST_PACKAGE_CLASS = ".Dialer";

    // LinearLayout with CarNavigationButtons with different configurations.
    private LinearLayout mTestView;
    private ButtonSelectionStateController mButtonSelectionStateController;
    private ComponentName mComponentName;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestView = (LinearLayout) LayoutInflater.from(mContext).inflate(
                R.layout.car_button_selection_state_controller_test, /* root= */ null);
        mButtonSelectionStateController = new ButtonSelectionStateController(mContext);
        mButtonSelectionStateController.addAllButtonsWithSelectionState(mTestView);
    }

    @Test
    public void onTaskChanged_buttonDetectableByComponentName_selectsAssociatedButton() {
        CarNavigationButton testButton = mTestView.findViewById(R.id.detectable_by_component_name);
        mComponentName = new ComponentName(TEST_COMPONENT_NAME_PACKAGE, TEST_COMPONENT_NAME_CLASS);
        List<ActivityManager.StackInfo> testStack = createTestStack(mComponentName);
        testButton.setSelected(false);
        mButtonSelectionStateController.taskChanged(testStack, /* validDisplay= */ -1);

        assertbuttonSelected(testButton);
    }

    @Test
    public void onTaskChanged_buttonDetectableByCategory_selectsAssociatedButton() {
        CarNavigationButton testButton = mTestView.findViewById(R.id.detectable_by_category);
        mComponentName = new ComponentName(TEST_CATEGORY, TEST_CATEGORY_CLASS);
        List<ActivityManager.StackInfo> testStack = createTestStack(mComponentName);
        testButton.setSelected(false);
        mButtonSelectionStateController.taskChanged(testStack, /* validDisplay= */ -1);

        assertbuttonSelected(testButton);
    }

    @Test
    public void onTaskChanged_buttonDetectableByPackage_selectsAssociatedButton() {
        CarNavigationButton testButton = mTestView.findViewById(R.id.detectable_by_package);
        mComponentName = new ComponentName(TEST_PACKAGE, TEST_PACKAGE_CLASS);
        List<ActivityManager.StackInfo> testStack = createTestStack(mComponentName);
        testButton.setSelected(false);
        mButtonSelectionStateController.taskChanged(testStack, /* validDisplay= */ -1);

        assertbuttonSelected(testButton);
    }

    @Test
    public void onTaskChanged_deselectsPreviouslySelectedButton() {
        CarNavigationButton oldButton = mTestView.findViewById(R.id.detectable_by_component_name);
        mComponentName = new ComponentName(TEST_COMPONENT_NAME_PACKAGE, TEST_COMPONENT_NAME_CLASS);
        List<ActivityManager.StackInfo> oldStack = createTestStack(mComponentName);
        oldButton.setSelected(false);
        mButtonSelectionStateController.taskChanged(oldStack, /* validDisplay= */ -1);

        mComponentName = new ComponentName(TEST_PACKAGE, TEST_PACKAGE_CLASS);
        List<ActivityManager.StackInfo> newStack = createTestStack(mComponentName);
        mButtonSelectionStateController.taskChanged(newStack, /* validDisplay= */ -1);

        assertButtonUnselected(oldButton);
    }

    // Comparing alpha is a valid way to verify button selection state because all test buttons use
    // highlightWhenSelected = true.
    private void assertbuttonSelected(CarNavigationButton button) {
        assertThat(button.getAlpha()).isEqualTo(CarNavigationButton.DEFAULT_SELECTED_ALPHA);
    }

    private void assertButtonUnselected(CarNavigationButton button) {
        assertThat(button.getAlpha()).isEqualTo(CarNavigationButton.DEFAULT_UNSELECTED_ALPHA);
    }

    private List<ActivityManager.StackInfo> createTestStack(ComponentName componentName) {
        ActivityManager.StackInfo validStackInfo = new ActivityManager.StackInfo();
        validStackInfo.displayId = -1; // No display is assigned to this test view
        validStackInfo.topActivity = componentName;

        List<ActivityManager.StackInfo> testStack = new ArrayList<>();
        testStack.add(validStackInfo);

        return testStack;
    }
}
