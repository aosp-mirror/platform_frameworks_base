/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.applications;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.pm.ApplicationInfo;
import android.os.Flags;
import android.os.UserManager;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ApplicationsStateTest {
    private static final int APP_ENTRY_ID = 1;
    private ApplicationsState.AppEntry mEntry;
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        mEntry = new ApplicationsState.AppEntry(
                ApplicationProvider.getApplicationContext(),
                mock(ApplicationInfo.class),
                APP_ENTRY_ID);
    }

    @Test
    public void testGamesFilterAcceptsGameDeprecated() {
        mEntry.info.flags = ApplicationInfo.FLAG_IS_GAME;

        assertThat(ApplicationsState.FILTER_GAMES.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testGameFilterAcceptsCategorizedGame() {
        mEntry.info.category = ApplicationInfo.CATEGORY_GAME;

        assertThat(ApplicationsState.FILTER_GAMES.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testGameFilterAcceptsCategorizedGameAndDeprecatedIsGame() {
        mEntry.info.flags = ApplicationInfo.FLAG_IS_GAME;
        mEntry.info.category = ApplicationInfo.CATEGORY_GAME;

        assertThat(ApplicationsState.FILTER_GAMES.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testGamesFilterRejectsNotGame() {
        mEntry.info.category = ApplicationInfo.CATEGORY_UNDEFINED;

        assertThat(ApplicationsState.FILTER_GAMES.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testAudioFilterAcceptsCategorizedAudio() {
        mEntry.info.category = ApplicationInfo.CATEGORY_AUDIO;

        assertThat(ApplicationsState.FILTER_AUDIO.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testAudiosFilterRejectsNotAudio() {
        mEntry.info.category = ApplicationInfo.CATEGORY_GAME;

        assertThat(ApplicationsState.FILTER_AUDIO.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testAudiosFilterRejectsDefaultCategory() {
        mEntry.info.category = ApplicationInfo.CATEGORY_UNDEFINED;

        assertThat(ApplicationsState.FILTER_AUDIO.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testOtherAppsRejectsAudio() {
        mEntry.info.category = ApplicationInfo.CATEGORY_AUDIO;

        assertThat(ApplicationsState.FILTER_OTHER_APPS.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testOtherAppsRejectsGame() {
        mEntry.info.category = ApplicationInfo.CATEGORY_GAME;

        assertThat(ApplicationsState.FILTER_OTHER_APPS.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testOtherAppsRejectsImageApp() {
        mEntry.info.category = ApplicationInfo.CATEGORY_IMAGE;

        assertThat(ApplicationsState.FILTER_OTHER_APPS.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testOtherAppsAcceptsDefaultCategory() {
        mEntry.info.category = ApplicationInfo.CATEGORY_UNDEFINED;

        assertThat(ApplicationsState.FILTER_OTHER_APPS.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testPhotosFilterAcceptsFilter() {
        mEntry.info.category = ApplicationInfo.CATEGORY_IMAGE;

        assertThat(ApplicationsState.FILTER_PHOTOS.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testPhotosFilterRejectsNotPhotos() {
        mEntry.info.category = ApplicationInfo.CATEGORY_VIDEO;

        assertThat(ApplicationsState.FILTER_PHOTOS.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testPhotosFilterRejectsDefaultCategory() {
        mEntry.info.category = ApplicationInfo.CATEGORY_UNDEFINED;

        assertThat(ApplicationsState.FILTER_PHOTOS.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testAppsExceptGamesFilterRejectsGame() {
        mEntry.info.category = ApplicationInfo.CATEGORY_GAME;

        assertThat(ApplicationsState.FILTER_APPS_EXCEPT_GAMES.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testAppsExceptGamesFilterAcceptsImage() {
        mEntry.info.category = ApplicationInfo.CATEGORY_IMAGE;

        assertThat(ApplicationsState.FILTER_APPS_EXCEPT_GAMES.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testAppsExceptGamesFilterAcceptsVideo() {
        mEntry.info.category = ApplicationInfo.CATEGORY_VIDEO;

        assertThat(ApplicationsState.FILTER_APPS_EXCEPT_GAMES.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testAppsExceptGamesFilterAcceptsAudio() {
        mEntry.info.category = ApplicationInfo.CATEGORY_AUDIO;

        assertThat(ApplicationsState.FILTER_APPS_EXCEPT_GAMES.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testDownloadAndLauncherAndInstantAcceptsCorrectApps() {
        // should include instant apps
        mEntry.isHomeApp = false;
        mEntry.hasLauncherEntry = false;
        when(mEntry.info.isInstantApp()).thenReturn(true);
        assertThat(ApplicationsState.FILTER_DOWNLOADED_AND_LAUNCHER_AND_INSTANT.filterApp(mEntry))
                .isTrue();

        // should included updated system apps
        when(mEntry.info.isInstantApp()).thenReturn(false);
        mEntry.info.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        assertThat(ApplicationsState.FILTER_DOWNLOADED_AND_LAUNCHER_AND_INSTANT.filterApp(mEntry))
                .isTrue();

        // should not include system apps other than the home app
        mEntry.info.flags = ApplicationInfo.FLAG_SYSTEM;
        mEntry.isHomeApp = false;
        mEntry.hasLauncherEntry = false;
        assertThat(ApplicationsState.FILTER_DOWNLOADED_AND_LAUNCHER_AND_INSTANT.filterApp(mEntry))
                .isFalse();

        // should include the home app
        mEntry.isHomeApp = true;
        assertThat(ApplicationsState.FILTER_DOWNLOADED_AND_LAUNCHER_AND_INSTANT.filterApp(mEntry))
                .isTrue();

        // should include any System app with a launcher entry
        mEntry.isHomeApp = false;
        mEntry.hasLauncherEntry = true;
        assertThat(ApplicationsState.FILTER_DOWNLOADED_AND_LAUNCHER_AND_INSTANT.filterApp(mEntry))
                .isTrue();
    }

    @Test
    public void testDownloadAndLauncherAcceptsCorrectApps() {
        mEntry.isHomeApp = false;
        mEntry.hasLauncherEntry = false;

        // should included updated system apps
        when(mEntry.info.isInstantApp()).thenReturn(false);
        mEntry.info.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        assertThat(ApplicationsState.FILTER_DOWNLOADED_AND_LAUNCHER.filterApp(mEntry))
                .isTrue();

        // should not include system apps other than the home app
        mEntry.info.flags = ApplicationInfo.FLAG_SYSTEM;
        mEntry.isHomeApp = false;
        mEntry.hasLauncherEntry = false;
        assertThat(ApplicationsState.FILTER_DOWNLOADED_AND_LAUNCHER.filterApp(mEntry))
                .isFalse();

        // should include the home app
        mEntry.isHomeApp = true;
        assertThat(ApplicationsState.FILTER_DOWNLOADED_AND_LAUNCHER.filterApp(mEntry))
                .isTrue();

        // should include any System app with a launcher entry
        mEntry.isHomeApp = false;
        mEntry.hasLauncherEntry = true;
        assertThat(ApplicationsState.FILTER_DOWNLOADED_AND_LAUNCHER.filterApp(mEntry))
                .isTrue();
    }

    @Test
    public void testOtherAppsRejectsLegacyGame() {
        mEntry.info.flags = ApplicationInfo.FLAG_IS_GAME;

        assertThat(ApplicationsState.FILTER_OTHER_APPS.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testInstantFilterAcceptsInstantApp() {
        when(mEntry.info.isInstantApp()).thenReturn(true);
        assertThat(ApplicationsState.FILTER_INSTANT.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testInstantFilterRejectsNonInstantApp() {
        when(mEntry.info.isInstantApp()).thenReturn(false);
        assertThat(ApplicationsState.FILTER_INSTANT.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testEnabledFilterRejectsInstantApp() {
        mEntry.info.enabled = true;
        assertThat(ApplicationsState.FILTER_ALL_ENABLED.filterApp(mEntry)).isTrue();
        when(mEntry.info.isInstantApp()).thenReturn(true);
        assertThat(ApplicationsState.FILTER_ALL_ENABLED.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testFilterWithDomainUrls() {
        mEntry.info.privateFlags |= ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS;
        // should included updated system apps
        when(mEntry.info.isInstantApp()).thenReturn(false);
        assertThat(ApplicationsState.FILTER_WITH_DOMAIN_URLS.filterApp(mEntry))
                .isTrue();
        mEntry.info.privateFlags &= ~ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS;
        assertThat(ApplicationsState.FILTER_WITH_DOMAIN_URLS.filterApp(mEntry))
                .isFalse();
        mEntry.info.privateFlags |= ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS;
        when(mEntry.info.isInstantApp()).thenReturn(true);
        assertThat(ApplicationsState.FILTER_WITH_DOMAIN_URLS.filterApp(mEntry))
                .isFalse();
    }

    @Test
    public void testDisabledFilterRejectsInstantApp() {
        mEntry.info.enabled = false;
        assertThat(ApplicationsState.FILTER_DISABLED.filterApp(mEntry)).isTrue();
        when(mEntry.info.isInstantApp()).thenReturn(true);
        assertThat(ApplicationsState.FILTER_DISABLED.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testVideoFilterAcceptsCategorizedVideo() {
        mEntry.info.category = ApplicationInfo.CATEGORY_VIDEO;

        assertThat(ApplicationsState.FILTER_MOVIES.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testVideosFilterRejectsNotVideo() {
        mEntry.info.category = ApplicationInfo.CATEGORY_GAME;

        assertThat(ApplicationsState.FILTER_MOVIES.filterApp(mEntry)).isFalse();
    }

    @Test
    public void testPersonalAndWorkFiltersDisplaysCorrectApps() {
        mEntry.showInPersonalTab = true;
        mEntry.mProfileType = UserManager.USER_TYPE_FULL_SYSTEM;
        assertThat(ApplicationsState.FILTER_PERSONAL.filterApp(mEntry)).isTrue();
        assertThat(ApplicationsState.FILTER_WORK.filterApp(mEntry)).isFalse();

        mEntry.showInPersonalTab = false;
        mEntry.mProfileType = UserManager.USER_TYPE_PROFILE_MANAGED;
        assertThat(ApplicationsState.FILTER_PERSONAL.filterApp(mEntry)).isFalse();
        assertThat(ApplicationsState.FILTER_WORK.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testPrivateProfileFilterDisplaysCorrectApps() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ALLOW_PRIVATE_PROFILE);

        mEntry.showInPersonalTab = true;
        mEntry.mProfileType = UserManager.USER_TYPE_FULL_SYSTEM;
        assertThat(ApplicationsState.FILTER_PERSONAL.filterApp(mEntry)).isTrue();
        assertThat(ApplicationsState.FILTER_PRIVATE_PROFILE.filterApp(mEntry)).isFalse();

        mEntry.showInPersonalTab = false;
        mEntry.mProfileType = UserManager.USER_TYPE_PROFILE_PRIVATE;
        assertThat(ApplicationsState.FILTER_PERSONAL.filterApp(mEntry)).isFalse();
        assertThat(ApplicationsState.FILTER_PRIVATE_PROFILE.filterApp(mEntry)).isTrue();
    }

    @Test
    public void testPrivateProfileFilterDisplaysCorrectAppsWhenFlagDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_ALLOW_PRIVATE_PROFILE);

        mEntry.showInPersonalTab = false;
        mEntry.mProfileType = UserManager.USER_TYPE_PROFILE_PRIVATE;
        assertThat(ApplicationsState.FILTER_PERSONAL.filterApp(mEntry)).isFalse();
        assertThat(ApplicationsState.FILTER_PRIVATE_PROFILE.filterApp(mEntry)).isFalse();
    }
}
