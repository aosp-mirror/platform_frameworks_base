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

package android.view;

import static android.view.ViewRootRefreshRateController.RefreshRatePref.LOWER;
import static android.view.ViewRootRefreshRateController.RefreshRatePref.RESTORE;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.platform.test.annotations.Presubmit;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ViewRootRefreshRateControllerTest {

    private static final float TARGET_REFRESH_RATE_UPPER_BOUND = 60f;

    private boolean mUseVariableRefreshRateWhenTyping;

    private ViewRootRefreshRateController mRefreshRateController;

    @Rule
    public ActivityTestRule<ViewRefreshRateTestActivity> mActivityRule =
            new ActivityTestRule<>(ViewRefreshRateTestActivity.class);

    private ViewRefreshRateTestActivity mActivity;

    private float mLowestSupportRefreshRate;

    private Instrumentation mInstrumentation;

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivity = mActivityRule.getActivity();
        mLowestSupportRefreshRate = getLowerSupportedRefreshRate();
        mUseVariableRefreshRateWhenTyping = mInstrumentation.getContext().getResources()
                .getBoolean(com.android.internal.R.bool.config_variableRefreshRateTypingSupported);
    }

    @Test
    public void testUpdateRefreshRatePreference_shouldLowerThenRestore() throws Throwable {
        // Ignored if the feature is not enabled.
        assumeTrue(mUseVariableRefreshRateWhenTyping);

        final ViewGroup viewGroup = mActivity.findViewById(R.id.layout);
        final EditText editText = new EditText(mActivity);

        mActivityRule.runOnUiThread(() -> viewGroup.addView(editText));
        mInstrumentation.waitForIdleSync();

        final ViewRootImpl viewRootImpl = editText.getViewRootImpl();
        mRefreshRateController = new ViewRootRefreshRateController(viewRootImpl);
        final float originalPreferredMaxDisplayRefreshRate =
                viewRootImpl.mWindowAttributes.preferredMaxDisplayRefreshRate;

        mRefreshRateController.updateRefreshRatePreference(LOWER);

        // Update to lower rate.
        assertEquals(viewRootImpl.mWindowAttributes.preferredMaxDisplayRefreshRate,
                mLowestSupportRefreshRate);

        mRefreshRateController.updateRefreshRatePreference(RESTORE);

        // Restore to previous preferred rate.
        assertEquals(viewRootImpl.mWindowAttributes.preferredMaxDisplayRefreshRate,
                originalPreferredMaxDisplayRefreshRate);
    }

    private float getLowerSupportedRefreshRate() {
        final Display display = mActivity.getDisplay();
        final Display.Mode defaultMode = display.getDefaultMode();
        float targetRefreshRate = defaultMode.getRefreshRate();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getRefreshRate() < targetRefreshRate) {
                targetRefreshRate = mode.getRefreshRate();
            }
        }
        if (targetRefreshRate < TARGET_REFRESH_RATE_UPPER_BOUND) {
            targetRefreshRate = TARGET_REFRESH_RATE_UPPER_BOUND;
        }
        return targetRefreshRate;
    }
}
