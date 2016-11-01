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
 * limitations under the License.
 */

package com.android.settingslib.drawer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static junit.framework.Assert.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ProfileSelectDialogTest {

    @Mock
    private Context mContext;
    @Mock
    private UserManager mUserManager;
    private static final UserHandle NORMAL_USER = UserHandle.of(1111);
    private static final UserHandle REMOVED_USER = UserHandle.of(2222);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        final UserInfo userInfo = new UserInfo(
                NORMAL_USER.getIdentifier(), "test_user", UserInfo.FLAG_RESTRICTED);
        when(mUserManager.getUserInfo(NORMAL_USER.getIdentifier())).thenReturn(userInfo);
    }

    @Test
    public void testUpdateUserHandlesIfNeeded_Normal() {
        final Tile tile = new Tile();
        tile.intent = new Intent();
        tile.userHandle.add(NORMAL_USER);

        ProfileSelectDialog.updateUserHandlesIfNeeded(mContext, tile);

        assertEquals(tile.userHandle.size(), 1);
        assertEquals(tile.userHandle.get(0).getIdentifier(), NORMAL_USER.getIdentifier());
        verify(mUserManager, never()).getUserInfo(NORMAL_USER.getIdentifier());
    }

    @Test
    public void testUpdateUserHandlesIfNeeded_Remove() {
        final Tile tile = new Tile();
        tile.intent = new Intent();
        tile.userHandle.add(REMOVED_USER);
        tile.userHandle.add(NORMAL_USER);
        tile.userHandle.add(REMOVED_USER);

        ProfileSelectDialog.updateUserHandlesIfNeeded(mContext, tile);

        assertEquals(tile.userHandle.size(), 1);
        assertEquals(tile.userHandle.get(0).getIdentifier(), NORMAL_USER.getIdentifier());
        verify(mUserManager, times(1)).getUserInfo(NORMAL_USER.getIdentifier());
        verify(mUserManager, times(2)).getUserInfo(REMOVED_USER.getIdentifier());
    }
}
