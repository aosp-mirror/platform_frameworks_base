/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.networkstack.tethering;

import static android.Manifest.permission.WRITE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.Intent;
import android.net.ITetheringConnector;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class MockTetheringService extends TetheringService {
    private final Tethering mTethering = mock(Tethering.class);

    @Override
    public IBinder onBind(Intent intent) {
        return new MockTetheringConnector(super.onBind(intent));
    }

    @Override
    public Tethering makeTethering(TetheringDependencies deps) {
        return mTethering;
    }

    @Override
    boolean checkAndNoteWriteSettingsOperation(@NonNull Context context, int uid,
            @NonNull String callingPackage, @Nullable String callingAttributionTag,
            boolean throwException) {
        // Test this does not verify the calling package / UID, as calling package could be shell
        // and not match the UID.
        return context.checkCallingOrSelfPermission(WRITE_SETTINGS) == PERMISSION_GRANTED;
    }

    public Tethering getTethering() {
        return mTethering;
    }

    public class MockTetheringConnector extends Binder {
        final IBinder mBase;
        MockTetheringConnector(IBinder base) {
            mBase = base;
        }

        public ITetheringConnector getTetheringConnector() {
            return ITetheringConnector.Stub.asInterface(mBase);
        }

        public MockTetheringService getService() {
            return MockTetheringService.this;
        }
    }
}
