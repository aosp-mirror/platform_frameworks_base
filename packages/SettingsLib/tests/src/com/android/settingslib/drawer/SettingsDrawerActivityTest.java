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
 * limitations under the License
 */

package com.android.settingslib.drawer;

import android.app.Instrumentation;
import android.content.Intent;
import android.support.test.InstrumentationRegistry;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.test.filters.SmallTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.android.settingslib.drawer.SettingsDrawerActivity;
import com.android.settingslib.drawer.Tile;
import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.assertion.ViewAssertions.doesNotExist;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withContentDescription;
import static junit.framework.Assert.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SettingsDrawerActivityTest {
    @Mock
    private UserManager mUserManager;

    @Rule
    public ActivityTestRule<TestActivity> mActivityRule =
            new ActivityTestRule<>(TestActivity.class, true, true);

    private static final UserHandle NORMAL_USER = UserHandle.of(1111);
    private static final UserHandle REMOVED_USER = UserHandle.of(2222);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        final UserInfo userInfo = new UserInfo(
                NORMAL_USER.getIdentifier(), "test_user", UserInfo.FLAG_RESTRICTED);
        when(mUserManager.getUserInfo(NORMAL_USER.getIdentifier())).thenReturn(userInfo);
    }

    @Test
    public void testUpdateUserHandlesIfNeeded_Normal() {
        TestActivity activity = mActivityRule.getActivity();
        activity.setUserManager(mUserManager);

        Tile tile = new Tile();
        tile.intent = new Intent();
        tile.userHandle.add(NORMAL_USER);

        activity.openTile(tile);

        assertEquals(tile.userHandle.size(), 1);
        assertEquals(tile.userHandle.get(0).getIdentifier(), NORMAL_USER.getIdentifier());
        verify(mUserManager, times(1)).getUserInfo(NORMAL_USER.getIdentifier());
    }

    @Test
    public void testUpdateUserHandlesIfNeeded_Remove() {
        TestActivity activity = mActivityRule.getActivity();
        activity.setUserManager(mUserManager);

        Tile tile = new Tile();
        tile.intent = new Intent();
        tile.userHandle.add(REMOVED_USER);
        tile.userHandle.add(NORMAL_USER);
        tile.userHandle.add(REMOVED_USER);

        activity.openTile(tile);

        assertEquals(tile.userHandle.size(), 1);
        assertEquals(tile.userHandle.get(0).getIdentifier(), NORMAL_USER.getIdentifier());
        verify(mUserManager, times(1)).getUserInfo(NORMAL_USER.getIdentifier());
        verify(mUserManager, times(2)).getUserInfo(REMOVED_USER.getIdentifier());
    }

    @Test
    public void startActivityWithNoExtra_showNoHamburgerMenu() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                TestActivity.class));

        onView(withContentDescription(R.string.content_description_menu_button))
                .check(doesNotExist());
    }

    @Test
    public void startActivityWithExtraToHideMenu_showNoHamburgerMenu() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), TestActivity.class)
                .putExtra(TestActivity.EXTRA_SHOW_MENU, false);
        instrumentation.startActivitySync(intent);

        onView(withContentDescription(R.string.content_description_menu_button))
                .check(doesNotExist());
    }

    @Test
    public void startActivityWithExtraToShowMenu_showHamburgerMenu() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), TestActivity.class)
                .putExtra(TestActivity.EXTRA_SHOW_MENU, true);
        instrumentation.startActivitySync(intent);

        onView(withContentDescription(R.string.content_description_menu_button))
                .check(matches(isDisplayed()));
    }

    /**
     * Test Activity in this test.
     *
     * Use this activity because SettingsDrawerActivity hasn't been registered in its
     * AndroidManifest.xml
     */
    public static class TestActivity extends SettingsDrawerActivity {}
}
