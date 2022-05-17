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

package com.android.server.companion.virtual;

import static com.google.common.truth.Truth.assertThat;

import android.companion.virtual.VirtualDeviceParams;
import android.os.Parcel;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class VirtualDeviceParamsTest {

    @Test
    public void parcelable_shouldRecreateSuccessfully() {
        VirtualDeviceParams originalParams = new VirtualDeviceParams.Builder()
                .setLockState(VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED)
                .setUsersWithMatchingAccounts(Set.of(UserHandle.of(123), UserHandle.of(456)))
                .build();
        Parcel parcel = Parcel.obtain();
        originalParams.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VirtualDeviceParams params = VirtualDeviceParams.CREATOR.createFromParcel(parcel);
        assertThat(params).isEqualTo(originalParams);
        assertThat(params.getLockState()).isEqualTo(VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED);
        assertThat(params.getUsersWithMatchingAccounts())
                .containsExactly(UserHandle.of(123), UserHandle.of(456));
    }
}
