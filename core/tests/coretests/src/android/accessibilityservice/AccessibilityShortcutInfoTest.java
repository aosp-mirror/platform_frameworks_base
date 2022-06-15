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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

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
    private static final String SETTINGS_ACTIVITY_NAME =
            "com.example.shortcut.target.SettingsActivity";

    private Context mTargetContext;
    private PackageManager mPackageManager;
    private ComponentName mComponentName;
    private AccessibilityShortcutInfo mShortcutInfo;

    @Before
    public void setUp() {
        mTargetContext = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        mPackageManager = mTargetContext.getPackageManager();
        mComponentName = new ComponentName(mTargetContext, AccessibilityTestActivity.class);
        mShortcutInfo = getAccessibilityShortcutInfo(mComponentName);
        assertNotNull("Can't find " + mComponentName, mShortcutInfo);
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

    @Test
    public void testAnimatedImageRes() {
        assertThat("Animated image resource id is not correct",
                mShortcutInfo.getAnimatedImageRes(), is(R.drawable.bitmap_drawable));
    }

    @Test
    public void testLoadAnimatedImage() {
        assertNotNull("Can't find animated image",
                mShortcutInfo.loadAnimatedImage(mTargetContext));
    }

    @Test
    public void testHtmlDescription() {
        final String htmlDescription = mTargetContext.getResources()
                .getString(R.string.accessibility_shortcut_html_description);

        assertNotNull("Can't find html description string", htmlDescription);
        assertThat("Html description is not correct",
                mShortcutInfo.loadHtmlDescription(mPackageManager), is(htmlDescription));
    }

    @Test
    public void testSettingsActivity() {
        assertThat("Settings Activity is not correct",
                mShortcutInfo.getSettingsActivityName(), is(SETTINGS_ACTIVITY_NAME));
    }

    @Test
    public void testEquals() {
        assertTrue(mShortcutInfo.equals(mShortcutInfo));
        assertFalse(mShortcutInfo.equals(null));
        assertFalse(mShortcutInfo.equals(new Object()));

        final AccessibilityShortcutInfo sameCopy = getAccessibilityShortcutInfo(
                mComponentName);
        assertTrue(mShortcutInfo != sameCopy);
        assertTrue(mShortcutInfo.hashCode() == sameCopy.hashCode());
        assertTrue(mShortcutInfo.getComponentName().equals(sameCopy.getComponentName()));
        assertTrue(mShortcutInfo.equals(sameCopy));
    }

    @Test
    public void testToString() {
        assertNotNull(mShortcutInfo.toString());
    }

    private AccessibilityShortcutInfo getAccessibilityShortcutInfo(ComponentName componentName) {
        final AccessibilityManager accessibilityManager = (AccessibilityManager) mTargetContext
                .getSystemService(Context.ACCESSIBILITY_SERVICE);
        final List<AccessibilityShortcutInfo> infoList = accessibilityManager
                .getInstalledAccessibilityShortcutListAsUser(
                        mTargetContext, mTargetContext.getUserId());
        for (AccessibilityShortcutInfo info : infoList) {
            final ActivityInfo activityInfo = info.getActivityInfo();
            if (componentName.equals(activityInfo.getComponentName())) {
                return info;
            }
        }
        return null;
    }
}
