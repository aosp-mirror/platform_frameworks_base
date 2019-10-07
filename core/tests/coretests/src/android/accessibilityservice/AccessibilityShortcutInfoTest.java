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

package android.accessibilityservice;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityTestActivity;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * AccessibilityShortcutInfo can only be created by system. Verify the instance creation and
 * basic function here.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AccessibilityShortcutInfoTest {
    private Context mTargetContext;
    private PackageManager mPackageManager;
    private AccessibilityShortcutInfo mShortcutInfo;

    @Before
    public void setUp() {
        mTargetContext = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        mPackageManager = mTargetContext.getPackageManager();

        final ComponentName testShortcutName = new ComponentName(mTargetContext,
                AccessibilityTestActivity.class);
        final AccessibilityManager accessibilityManager = (AccessibilityManager) mTargetContext
                .getSystemService(Context.ACCESSIBILITY_SERVICE);
        final List<AccessibilityShortcutInfo> infoList = accessibilityManager
                .getInstalledAccessibilityShortcutListAsUser(
                        mTargetContext, mTargetContext.getUserId());
        for (AccessibilityShortcutInfo info : infoList) {
            final ActivityInfo activityInfo = info.getActivityInfo();
            final ComponentName name = new ComponentName(
                    activityInfo.packageName, activityInfo.name);
            if (name.equals(testShortcutName)) {
                mShortcutInfo = info;
                break;
            }
        }

        assertNotNull("Can't find " + testShortcutName, mShortcutInfo);
    }

    @Test
    public void testDescription() {
        final String description = mTargetContext.getResources()
                .getString(R.string.accessibility_shortcut_description);

        assertNotNull("Can't find description string", description);
        assertThat("Description is not correct",
                mShortcutInfo.loadDescription(mPackageManager), is(description));
    }

    @Test
    public void testSummary() {
        final String summary = mTargetContext.getResources()
                .getString(R.string.accessibility_shortcut_summary);

        assertNotNull("Can't find summary string", summary);
        assertThat("Summary is not correct",
                mShortcutInfo.loadSummary(mPackageManager), is(summary));
    }
}
