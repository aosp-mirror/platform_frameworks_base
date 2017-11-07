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
package com.android.settingslib.core;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceGroup;
import android.support.v7.preference.PreferenceManager;
import android.support.v7.preference.PreferenceScreen;

import com.android.settingslib.TestConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class AbstractPreferenceControllerTest {

    @Mock
    private Context mContext;
    @Mock
    private PreferenceScreen mScreen;

    private Preference mPreference;
    private TestPrefController mTestPrefController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mPreference = new Preference(RuntimeEnvironment.application);
        mPreference.setKey(TestPrefController.KEY_PREF);
        mTestPrefController = new TestPrefController(mContext);
    }

    @Test
    public void removeExistingPref_shouldBeRemoved() {
        when(mScreen.getPreferenceCount()).thenReturn(1);
        when(mScreen.getPreference(0)).thenReturn(mPreference);

        mTestPrefController.removePreference(mScreen, TestPrefController.KEY_PREF);

        verify(mScreen).removePreference(mPreference);
    }

    @Test
    public void removeNonExistingPref_shouldNotRemoveAnything() {
        mTestPrefController.removePreference(mScreen, TestPrefController.KEY_PREF);

        verify(mScreen, never()).removePreference(any(Preference.class));
    }

    @Test
    public void displayPref_ifAvailable() {
        mTestPrefController.isAvailable = true;

        mTestPrefController.displayPreference(mScreen);

        verify(mScreen, never()).removePreference(any(Preference.class));
    }

    @Test
    public void setVisible_prefIsVisible_shouldSetToVisible() {
        when(mScreen.findPreference(TestPrefController.KEY_PREF)).thenReturn(mPreference);

        mTestPrefController.setVisible(mScreen, TestPrefController.KEY_PREF, true /* visible */);
        assertThat(mPreference.isVisible()).isTrue();
    }

    @Test
    public void setVisible_prefNotVisible_shouldSetToInvisible() {
        when(mScreen.findPreference(TestPrefController.KEY_PREF)).thenReturn(mPreference);

        mTestPrefController.setVisible(mScreen, TestPrefController.KEY_PREF, false /* visible */);
        assertThat(mPreference.isVisible()).isFalse();
    }

    @Test
    public void doNotDisplayPref_ifNotAvailable() {
        when(mScreen.getPreferenceCount()).thenReturn(1);
        when(mScreen.getPreference(0)).thenReturn(mPreference);
        mTestPrefController.isAvailable = false;

        mTestPrefController.displayPreference(mScreen);

        verify(mScreen).removePreference(any(Preference.class));
    }

    @Test
    public void removePreference_shouldRemoveRecursively() {
        final Context context = ShadowApplication.getInstance().getApplicationContext();
        final PreferenceManager preferenceManager = mock(PreferenceManager.class);
        // Top level
        PreferenceScreen prefRoot = spy(new PreferenceScreen(context, null));
        when(prefRoot.getPreferenceManager()).thenReturn(preferenceManager);
        Preference pref1 = mock(Preference.class);
        when(pref1.getKey()).thenReturn("key1");
        PreferenceGroup prefGroup2 = spy(new PreferenceScreen(context, null));
        when(prefGroup2.getPreferenceManager()).thenReturn(preferenceManager);
        when(prefGroup2.getKey()).thenReturn("group2");
        Preference pref3 = mock(Preference.class);
        when(pref3.getKey()).thenReturn("key3");
        PreferenceGroup prefGroup4 = spy(new PreferenceScreen(context, null));
        when(prefGroup4.getPreferenceManager()).thenReturn(preferenceManager);
        when(prefGroup4.getKey()).thenReturn("group4");
        prefRoot.addPreference(pref1);
        prefRoot.addPreference(prefGroup2);
        prefRoot.addPreference(pref3);
        prefRoot.addPreference(prefGroup4);

        // 2nd level
        Preference pref21 = mock(Preference.class);
        when(pref21.getKey()).thenReturn("key21");
        Preference pref22 = mock(Preference.class);
        when(pref22.getKey()).thenReturn("key22");
        prefGroup2.addPreference(pref21);
        prefGroup2.addPreference(pref22);
        PreferenceGroup prefGroup41 = spy(new PreferenceScreen(context, null));
        when(prefGroup41.getKey()).thenReturn("group41");
        when(prefGroup41.getPreferenceManager()).thenReturn(preferenceManager);
        Preference pref42 = mock(Preference.class);
        when(pref42.getKey()).thenReturn("key42");
        prefGroup4.addPreference(prefGroup41);
        prefGroup4.addPreference(pref42);

        // 3rd level
        Preference pref411 = mock(Preference.class);
        when(pref411.getKey()).thenReturn("key411");
        Preference pref412 = mock(Preference.class);
        when(pref412.getKey()).thenReturn("key412");
        prefGroup41.addPreference(pref411);
        prefGroup41.addPreference(pref412);

        mTestPrefController.removePreference(prefRoot, "key1");
        verify(prefRoot).removePreference(pref1);

        mTestPrefController.removePreference(prefRoot, "key411");
        verify(prefGroup41).removePreference(pref411);

        mTestPrefController.removePreference(prefRoot, "group41");
        verify(prefGroup4).removePreference(prefGroup41);
    }

    private class TestPrefController extends AbstractPreferenceController {
        private static final String KEY_PREF = "test_pref";
        public boolean isAvailable;

        public TestPrefController(Context context) {
            super(context);
        }

        @Override
        public boolean handlePreferenceTreeClick(Preference preference) {
            return false;
        }

        @Override
        public boolean isAvailable() {
            return isAvailable;
        }

        @Override
        public String getPreferenceKey() {
            return KEY_PREF;
        }
    }

}
