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

package com.android.server.recoverysystem;

import android.content.Context;
import android.os.PowerManager;

import com.android.internal.widget.LockSettingsInternal;

import java.io.FileWriter;

public class RecoverySystemServiceTestable extends RecoverySystemService {
    private static class MockInjector extends RecoverySystemService.Injector {
        private final FakeSystemProperties mSystemProperties;
        private final PowerManager mPowerManager;
        private final FileWriter mUncryptPackageFileWriter;
        private final UncryptSocket mUncryptSocket;
        private final LockSettingsInternal mLockSettingsInternal;

        MockInjector(Context context, FakeSystemProperties systemProperties,
                PowerManager powerManager, FileWriter uncryptPackageFileWriter,
                UncryptSocket uncryptSocket, LockSettingsInternal lockSettingsInternal) {
            super(context);
            mSystemProperties = systemProperties;
            mPowerManager = powerManager;
            mUncryptPackageFileWriter = uncryptPackageFileWriter;
            mUncryptSocket = uncryptSocket;
            mLockSettingsInternal = lockSettingsInternal;
        }

        @Override
        public PowerManager getPowerManager() {
            return mPowerManager;
        }

        @Override
        public String systemPropertiesGet(String key) {
            return mSystemProperties.get(key);
        }

        @Override
        public void systemPropertiesSet(String key, String value) {
            mSystemProperties.set(key, value);
        }

        @Override
        public boolean uncryptPackageFileDelete() {
            return true;
        }

        @Override
        public String getUncryptPackageFileName() {
            return "mock-file.txt";
        }

        @Override
        public FileWriter getUncryptPackageFileWriter() {
            return mUncryptPackageFileWriter;
        }

        @Override
        public UncryptSocket connectService() {
            return mUncryptSocket;
        }

        @Override
        public void threadSleep(long millis) {
        }

        @Override
        public LockSettingsInternal getLockSettingsService() {
            return mLockSettingsInternal;
        }
    }

    RecoverySystemServiceTestable(Context context, FakeSystemProperties systemProperties,
            PowerManager powerManager, FileWriter uncryptPackageFileWriter,
            UncryptSocket uncryptSocket, LockSettingsInternal lockSettingsInternal) {
        super(new MockInjector(context, systemProperties, powerManager, uncryptPackageFileWriter,
                uncryptSocket, lockSettingsInternal));
    }

    public static class FakeSystemProperties {
        private String mCtlStart = null;

        public String get(String key) {
            if (RecoverySystemService.INIT_SERVICE_UNCRYPT.equals(key)
                    || RecoverySystemService.INIT_SERVICE_SETUP_BCB.equals(key)
                    || RecoverySystemService.INIT_SERVICE_CLEAR_BCB.equals(key)) {
                return null;
            } else {
                throw new IllegalArgumentException("unexpected test key: " + key);
            }
        }

        public void set(String key, String value) {
            if ("ctl.start".equals(key)) {
                mCtlStart = value;
            } else {
                throw new IllegalArgumentException("unexpected test key: " + key);
            }
        }

        public String getCtlStart() {
            return mCtlStart;
        }
    }
}
