/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.people.widget;

import static com.android.systemui.people.PeopleBackupFollowUpJob.SHARED_FOLLOW_UP;
import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.widget.PeopleBackupHelper.ADD_USER_ID_TO_URI;
import static com.android.systemui.people.widget.PeopleBackupHelper.SHARED_BACKUP;
import static com.android.systemui.people.widget.PeopleBackupHelper.SharedFileEntryType;
import static com.android.systemui.people.widget.PeopleBackupHelper.getEntryType;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.app.people.IPeopleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.preference.PreferenceManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.SharedPreferencesHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PeopleBackupHelperTest extends SysuiTestCase {
    private static final String SHORTCUT_ID_1 = "101";
    private static final String PACKAGE_NAME_1 = "package_name";
    private static final int USER_ID_0 = 0;
    private static final int USER_ID_10 = 10;

    private static final PeopleTileKey PEOPLE_TILE_KEY =
            new PeopleTileKey(SHORTCUT_ID_1, USER_ID_0, PACKAGE_NAME_1);
    private static final PeopleTileKey OTHER_PEOPLE_TILE_KEY =
            new PeopleTileKey(SHORTCUT_ID_1, USER_ID_10, PACKAGE_NAME_1);
    private static final PeopleTileKey INVALID_USER_ID_PEOPLE_TILE_KEY =
            new PeopleTileKey(SHORTCUT_ID_1, INVALID_USER_ID, PACKAGE_NAME_1);

    private static final String WIDGET_ID_STRING = "3";
    private static final String SECOND_WIDGET_ID_STRING = "12";
    private static final String OTHER_WIDGET_ID_STRING = "7";
    private static final Set<String> WIDGET_IDS = new HashSet<>(
            Arrays.asList(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING));

    private static final String URI_STRING = "content://mms";
    private static final String URI_WITH_USER_ID_0 = "content://0@mms";
    private static final String URI_WITH_USER_ID_10 = "content://10@mms";

    private final SharedPreferences mBackupSp = mContext.getSharedPreferences(
            SHARED_BACKUP, Context.MODE_PRIVATE);
    private final SharedPreferences.Editor mBackupEditor = mBackupSp.edit();
    private final SharedPreferences mSp = PreferenceManager.getDefaultSharedPreferences(mContext);
    private final SharedPreferences.Editor mEditor = mSp.edit();
    private final SharedPreferences mFollowUpSp = mContext.getSharedPreferences(
            SHARED_FOLLOW_UP, Context.MODE_PRIVATE);
    private final SharedPreferences.Editor mFollowUpEditor = mFollowUpSp.edit();
    private final SharedPreferences mWidgetIdSp = mContext.getSharedPreferences(
            WIDGET_ID_STRING, Context.MODE_PRIVATE);
    private final SharedPreferences mSecondWidgetIdSp = mContext.getSharedPreferences(
            SECOND_WIDGET_ID_STRING, Context.MODE_PRIVATE);

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PackageInfo mPackageInfo;
    @Mock
    private IPeopleManager mIPeopleManager;

    private PeopleBackupHelper mHelper;
    private PeopleBackupHelper mOtherHelper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mHelper = new PeopleBackupHelper(mContext,
                UserHandle.of(0), new String[]{SHARED_BACKUP}, mPackageManager, mIPeopleManager);
        mOtherHelper = new PeopleBackupHelper(mContext,
                UserHandle.of(10), new String[]{SHARED_BACKUP}, mPackageManager, mIPeopleManager);

        when(mPackageManager.getPackageInfoAsUser(any(), anyInt(), anyInt()))
                .thenReturn(mPackageInfo);
        when(mIPeopleManager.isConversation(any(), anyInt(), any())).thenReturn(true);
    }

    @After
    public void tearDown() {
        mBackupEditor.clear().commit();
        mEditor.clear().commit();
        mFollowUpEditor.clear().commit();
        mWidgetIdSp.edit().clear().commit();
        mSecondWidgetIdSp.edit().clear().commit();
    }

    @Test
    public void testGetKeyType_widgetId() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(WIDGET_ID_STRING, "contact");
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.WIDGET_ID);
    }

    @Test
    public void testGetKeyType_widgetId_twoDigits() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                SECOND_WIDGET_ID_STRING, URI_STRING);
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.WIDGET_ID);
    }

    @Test
    public void testGetKeyType_peopleTileKey_valid() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                "shortcut_id/12/com.android.systemui", WIDGET_IDS);
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.PEOPLE_TILE_KEY);
    }

    @Test
    public void testGetKeyType_peopleTileKey_validWithSlashes() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                "shortcut_id/with/slashes/12/com.android.systemui2", WIDGET_IDS);
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.PEOPLE_TILE_KEY);
    }

    @Test
    public void testGetKeyType_peopleTileKey_negativeNumber() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                "shortcut_id/with/slashes/-1/com.android.systemui2", WIDGET_IDS);
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.PEOPLE_TILE_KEY);
    }

    @Test
    public void testGetKeyType_contactUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                "shortcut_id/1f/com.android.systemui2", WIDGET_IDS);
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.CONTACT_URI);
    }

    @Test
    public void testGetKeyType_contactUri_valid() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                "http://content.fake", WIDGET_IDS);
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.CONTACT_URI);
    }

    @Test
    public void testGetKeyType_contactUri_invalidPackageName() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                "shortcut_id/with/slashes/12/2r/com.android.systemui2", WIDGET_IDS);
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.CONTACT_URI);
    }

    @Test
    public void testGetKeyType_unknown_unexpectedValueForPeopleTileKey() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                "shortcut_id/12/com.android.systemui", URI_STRING);
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.UNKNOWN);
    }

    @Test
    public void testGetKeyType_unknown_unexpectedValueForContactUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                URI_STRING, "12");
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.UNKNOWN);
    }

    @Test
    public void testGetKeyType_unknown() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                null, WIDGET_IDS);
        assertThat(getEntryType(entry)).isEqualTo(SharedFileEntryType.UNKNOWN);
    }

    @Test
    public void testBackupKey_widgetIdKey_containsWidget_noUserIdInUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(WIDGET_ID_STRING, URI_STRING);

        mHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getString(WIDGET_ID_STRING, null)).isEqualTo(URI_STRING);
    }

    @Test
    public void testBackupKey_widgetIdKey_doesNotContainWidget_noUserIdInUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(WIDGET_ID_STRING, URI_STRING);

        mHelper.backupKey(entry, mBackupEditor, Collections.singletonList(OTHER_WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getString(WIDGET_ID_STRING, null)).isNull();
    }

    @Test
    public void testBackupKey_widgetIdKey_containsOneWidget_differentUserIdInUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(WIDGET_ID_STRING,
                URI_WITH_USER_ID_10);

        mHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getString(WIDGET_ID_STRING, null)).isEqualTo(URI_STRING);
        assertThat(mBackupSp.getInt(ADD_USER_ID_TO_URI + WIDGET_ID_STRING, INVALID_USER_ID))
                .isEqualTo(USER_ID_10);
    }

    @Test
    public void testBackupKey_widgetIdKey_containsWidget_SameUserIdInUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                WIDGET_ID_STRING, URI_WITH_USER_ID_10);

        mOtherHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getString(WIDGET_ID_STRING, null)).isEqualTo(URI_STRING);
        assertThat(mBackupSp.getInt(ADD_USER_ID_TO_URI + WIDGET_ID_STRING, INVALID_USER_ID))
                .isEqualTo(USER_ID_10);
    }

    @Test
    public void testBackupKey_contactUriKey_ignoresExistingWidgets() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(URI_STRING, WIDGET_IDS);

        mHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getStringSet(URI_STRING, new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
    }

    @Test
    public void testBackupKey_contactUriKey_ignoresExistingWidgets_otherWidget() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(URI_STRING, WIDGET_IDS);

        mHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getStringSet(URI_STRING, new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
    }

    @Test
    public void testBackupKey_contactUriKey_noUserId_otherUser_doesntBackup() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(URI_STRING, WIDGET_IDS);

        mOtherHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getStringSet(URI_STRING, new HashSet<>())).isEmpty();
    }

    @Test
    public void testBackupKey_contactUriKey_sameUserId() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(URI_WITH_USER_ID_10, WIDGET_IDS);

        mOtherHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getStringSet(URI_STRING, new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
        assertThat(mBackupSp.getInt(ADD_USER_ID_TO_URI + URI_STRING, INVALID_USER_ID))
                .isEqualTo(USER_ID_10);
    }

    @Test
    public void testBackupKey_contactUriKey_differentUserId_runningAsUser0() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(URI_WITH_USER_ID_10, WIDGET_IDS);

        mHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getStringSet(URI_STRING, new HashSet<>())).isEmpty();
        assertThat(mBackupSp.getInt(ADD_USER_ID_TO_URI + URI_STRING, INVALID_USER_ID))
                .isEqualTo(INVALID_USER_ID);
    }

    @Test
    public void testBackupKey_contactUriKey_differentUserId_runningAsUser10() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(URI_WITH_USER_ID_0, WIDGET_IDS);

        mOtherHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getStringSet(URI_STRING, new HashSet<>())).isEmpty();
        assertThat(mBackupSp.getInt(ADD_USER_ID_TO_URI + URI_STRING, INVALID_USER_ID))
                .isEqualTo(INVALID_USER_ID);
    }

    @Test
    public void testBackupKey_peopleTileKey_containsWidget() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                PEOPLE_TILE_KEY.toString(), WIDGET_IDS);

        mHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getStringSet(
                INVALID_USER_ID_PEOPLE_TILE_KEY.toString(), new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING);
    }

    @Test
    public void testBackupKey_peopleTileKey_containsBothWidgets() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                PEOPLE_TILE_KEY.toString(), WIDGET_IDS);

        mHelper.backupKey(entry, mBackupEditor,
                Arrays.asList(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(
                mBackupSp.getStringSet(INVALID_USER_ID_PEOPLE_TILE_KEY.toString(), new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
    }

    @Test
    public void testBackupKey_peopleTileKey_doesNotContainWidget() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                PEOPLE_TILE_KEY.toString(), WIDGET_IDS);

        mHelper.backupKey(entry, mBackupEditor, Collections.singletonList(OTHER_WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getStringSet(
                INVALID_USER_ID_PEOPLE_TILE_KEY.toString(), new HashSet<>())).isEmpty();
    }

    @Test
    public void testBackupKey_peopleTileKey_differentUserId() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                OTHER_PEOPLE_TILE_KEY.toString(), WIDGET_IDS);

        mHelper.backupKey(entry, mBackupEditor, Collections.singletonList(WIDGET_ID_STRING));
        mBackupEditor.apply();

        assertThat(mBackupSp.getStringSet(
                INVALID_USER_ID_PEOPLE_TILE_KEY.toString(), new HashSet<>())).isEmpty();
    }

    @Test
    public void testRestoreKey_widgetIdKey_noUserIdInUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(WIDGET_ID_STRING, URI_STRING);

        boolean restored = mHelper.restoreKey(entry, mEditor, mFollowUpEditor, mBackupSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(restored).isTrue();
        assertThat(mSp.getString(WIDGET_ID_STRING, null)).isEqualTo(URI_STRING);
        assertThat(mFollowUpSp.getAll()).isEmpty();
    }

    @Test
    public void testRestoreKey_widgetIdKey_sameUserInUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(WIDGET_ID_STRING, URI_STRING);
        mBackupEditor.putInt(ADD_USER_ID_TO_URI + WIDGET_ID_STRING, USER_ID_0);
        mBackupEditor.apply();

        boolean restored = mHelper.restoreKey(entry, mEditor, mFollowUpEditor, mBackupSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(restored).isTrue();
        assertThat(mSp.getString(WIDGET_ID_STRING, null)).isEqualTo(URI_WITH_USER_ID_0);
        assertThat(mFollowUpSp.getAll()).isEmpty();
    }

    @Test
    public void testRestoreKey_widgetIdKey_differentUserInUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(WIDGET_ID_STRING, URI_STRING);
        mBackupEditor.putInt(ADD_USER_ID_TO_URI + WIDGET_ID_STRING, USER_ID_10);
        mBackupEditor.apply();

        boolean restored = mHelper.restoreKey(entry, mEditor, mFollowUpEditor, mBackupSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(restored).isTrue();
        assertThat(mSp.getString(WIDGET_ID_STRING, null)).isEqualTo(URI_WITH_USER_ID_10);
        assertThat(mFollowUpSp.getAll()).isEmpty();
    }

    @Test
    public void testRestoreKey_widgetIdKey_nonSystemUser_differentUser() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(WIDGET_ID_STRING, URI_STRING);
        mBackupEditor.putInt(ADD_USER_ID_TO_URI + WIDGET_ID_STRING, USER_ID_0);
        mBackupEditor.apply();

        boolean restored = mOtherHelper.restoreKey(entry, mEditor, mFollowUpEditor, mBackupSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(restored).isTrue();
        assertThat(mSp.getString(WIDGET_ID_STRING, null)).isEqualTo(URI_WITH_USER_ID_0);
        assertThat(mFollowUpSp.getAll()).isEmpty();
    }

    @Test
    public void testRestoreKey_contactUriKey_noUserIdInUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(URI_STRING, WIDGET_IDS);

        boolean restored = mHelper.restoreKey(entry, mEditor, mFollowUpEditor, mBackupSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(restored).isTrue();
        assertThat(mSp.getStringSet(URI_STRING, new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
        assertThat(mFollowUpSp.getAll()).isEmpty();
    }

    @Test
    public void testRestoreKey_contactUriKey_sameUserInUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(URI_STRING, WIDGET_IDS);
        mBackupEditor.putInt(ADD_USER_ID_TO_URI + URI_STRING, USER_ID_0);
        mBackupEditor.apply();

        boolean restored = mHelper.restoreKey(entry, mEditor, mFollowUpEditor, mBackupSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(restored).isTrue();
        assertThat(mSp.getStringSet(URI_WITH_USER_ID_0, new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
        assertThat(mSp.getStringSet(URI_STRING, new HashSet<>())).isEmpty();
        assertThat(mFollowUpSp.getAll()).isEmpty();
    }

    @Test
    public void testRestoreKey_contactUriKey_differentUserInUri() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(URI_STRING, WIDGET_IDS);
        mBackupEditor.putInt(ADD_USER_ID_TO_URI + URI_STRING, USER_ID_10);
        mBackupEditor.apply();

        boolean restored = mHelper.restoreKey(entry, mEditor, mFollowUpEditor, mBackupSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(restored).isTrue();
        assertThat(mSp.getStringSet(URI_WITH_USER_ID_10, new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
        assertThat(mSp.getStringSet(URI_STRING, new HashSet<>())).isEmpty();
        assertThat(mFollowUpSp.getAll()).isEmpty();
    }

    @Test
    public void testRestoreKey_contactUriKey_nonSystemUser_differentUser() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(URI_STRING, WIDGET_IDS);
        mBackupEditor.putInt(ADD_USER_ID_TO_URI + URI_STRING, USER_ID_0);
        mBackupEditor.apply();

        boolean restored = mOtherHelper.restoreKey(entry, mEditor, mFollowUpEditor, mBackupSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(restored).isTrue();
        assertThat(mSp.getStringSet(URI_WITH_USER_ID_0, new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
        assertThat(mSp.getStringSet(URI_STRING, new HashSet<>())).isEmpty();
        assertThat(mFollowUpSp.getAll()).isEmpty();
    }

    @Test
    public void testRestoreKey_peopleTileKey_shouldNotFollowUp() {
        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                INVALID_USER_ID_PEOPLE_TILE_KEY.toString(), WIDGET_IDS);

        boolean restored = mHelper.restoreKey(entry, mEditor, mFollowUpEditor, mBackupSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(restored).isTrue();
        assertThat(mSp.getStringSet(PEOPLE_TILE_KEY.toString(), new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
        assertThat(SharedPreferencesHelper.getPeopleTileKey(mWidgetIdSp))
                .isEqualTo(PEOPLE_TILE_KEY);
        assertThat(SharedPreferencesHelper.getPeopleTileKey(mSecondWidgetIdSp))
                .isEqualTo(PEOPLE_TILE_KEY);
        assertThat(mFollowUpSp.getAll()).isEmpty();
    }

    @Test
    public void testRestoreKey_peopleTileKey_shortcutNotYetRestored_shouldFollowUpBoth()
            throws RemoteException {
        when(mIPeopleManager.isConversation(any(), anyInt(), any())).thenReturn(false);

        Map.Entry<String, ?> entry = new AbstractMap.SimpleEntry<>(
                INVALID_USER_ID_PEOPLE_TILE_KEY.toString(), WIDGET_IDS);

        boolean restored = mHelper.restoreKey(entry, mEditor, mFollowUpEditor, mBackupSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(restored).isFalse();
        assertThat(mSp.getStringSet(PEOPLE_TILE_KEY.toString(), new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
        assertThat(SharedPreferencesHelper.getPeopleTileKey(mWidgetIdSp))
                .isEqualTo(PEOPLE_TILE_KEY);
        assertThat(SharedPreferencesHelper.getPeopleTileKey(mSecondWidgetIdSp))
                .isEqualTo(PEOPLE_TILE_KEY);

        assertThat(mFollowUpSp.getStringSet(PEOPLE_TILE_KEY.toString(), new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
    }
}
