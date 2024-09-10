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

package com.android.systemui.people;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.people.ConversationChannel;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.widget.RemoteViews;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.widget.PeopleSpaceWidgetManager;
import com.android.systemui.shared.system.PeopleProviderUtils;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PeopleProviderTest extends SysuiTestCase {
    private static final String TAG = "PeopleProviderTest";

    private static final Uri URI = Uri.parse(PeopleProviderUtils.PEOPLE_PROVIDER_SCHEME
            + PeopleProviderUtils.PEOPLE_PROVIDER_AUTHORITY);

    private static final String SHORTCUT_ID_A = "shortcut_id_a";
    private static final String PACKAGE_NAME_A = "package_name_a";
    private static final UserHandle USER_HANDLE_A = UserHandle.of(1);
    private static final String USERNAME = "username";

    private final ShortcutInfo mShortcutInfo =
            new ShortcutInfo.Builder(mContext, SHORTCUT_ID_A).setLongLabel(USERNAME).build();
    private final ConversationChannel mConversationChannel =
            new ConversationChannel(mShortcutInfo, USER_HANDLE_A.getIdentifier(),
                    null, null, 0L, false);

    private Bundle mExtras = new Bundle();

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PeopleSpaceWidgetManager mPeopleSpaceWidgetManager;
    @Mock
    private RemoteViews mRemoteViews;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext.setMockPackageManager(mPackageManager);

        PeopleProviderTestable provider = new PeopleProviderTestable();
        provider.initializeForTesting(
                mContext, PeopleProviderUtils.PEOPLE_PROVIDER_AUTHORITY);
        provider.setPeopleSpaceWidgetManager(mPeopleSpaceWidgetManager);
        mContext.getContentResolver().addProvider(
                PeopleProviderUtils.PEOPLE_PROVIDER_AUTHORITY, provider);

        mContext.getTestablePermissions().setPermission(
                PeopleProviderUtils.GET_PEOPLE_TILE_PREVIEW_PERMISSION,
                PackageManager.PERMISSION_GRANTED);

        when(mPeopleSpaceWidgetManager.getPreview(
                eq(SHORTCUT_ID_A), eq(USER_HANDLE_A), eq(PACKAGE_NAME_A), any()))
                .thenReturn(mRemoteViews);

        mExtras.putString(PeopleProviderUtils.EXTRAS_KEY_SHORTCUT_ID, SHORTCUT_ID_A);
        mExtras.putString(PeopleProviderUtils.EXTRAS_KEY_PACKAGE_NAME, PACKAGE_NAME_A);
        mExtras.putParcelable(PeopleProviderUtils.EXTRAS_KEY_USER_HANDLE, USER_HANDLE_A);
    }

    @Test
    public void testPermissionDeniedThrowsSecurityException() throws RemoteException {
        mContext.getTestablePermissions().setPermission(
                PeopleProviderUtils.GET_PEOPLE_TILE_PREVIEW_PERMISSION,
                PackageManager.PERMISSION_DENIED);
        try {
            Bundle result = mContext.getContentResolver()
                    .call(URI, PeopleProviderUtils.GET_PEOPLE_TILE_PREVIEW_METHOD, null, null);
            Assert.fail("Call should have failed with SecurityException");
        } catch (SecurityException e) {
        } catch (Exception e) {
            Assert.fail("Call should have failed with SecurityException");
        }
    }

    @Test
    public void testPermissionGrantedNoExtraReturnsNull() throws RemoteException {
        try {
            Bundle result = mContext.getContentResolver()
                    .call(URI, PeopleProviderUtils.GET_PEOPLE_TILE_PREVIEW_METHOD, null, null);
            Assert.fail("Call should have failed with IllegalArgumentException");
        } catch (IllegalArgumentException e) {
        } catch (Exception e) {
            Assert.fail("Call should have failed with IllegalArgumentException");
        }
    }

    @Test
    public void testPermissionGrantedExtrasReturnsRemoteViews() throws RemoteException {
        try {
            Bundle result = mContext.getContentResolver().call(
                    URI, PeopleProviderUtils.GET_PEOPLE_TILE_PREVIEW_METHOD, null, mExtras);
            RemoteViews views = result.getParcelable(
                    PeopleProviderUtils.RESPONSE_KEY_REMOTE_VIEWS);
            assertThat(views).isNotNull();
        } catch (Exception e) {
            Assert.fail("Fail " + e);
        }
    }

    @Test
    public void testPermissionGrantedNoConversationForShortcutReturnsNull() throws RemoteException {
        when(mPeopleSpaceWidgetManager.getPreview(
                eq(SHORTCUT_ID_A), eq(USER_HANDLE_A), eq(PACKAGE_NAME_A), any()))
                .thenReturn(null);
        try {
            Bundle result = mContext.getContentResolver().call(
                    URI, PeopleProviderUtils.GET_PEOPLE_TILE_PREVIEW_METHOD, null, mExtras);
            assertThat(result).isNull();
        } catch (Exception e) {
            Assert.fail("Fail " + e);
        }
    }
}
