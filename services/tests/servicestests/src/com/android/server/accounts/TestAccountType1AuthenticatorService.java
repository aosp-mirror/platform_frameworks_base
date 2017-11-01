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
package com.android.server.accounts;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * a test Mock Service for wrapping the TestAccountType1Authenticator
 */
public class TestAccountType1AuthenticatorService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        TestAccountType1Authenticator authenticator = new TestAccountType1Authenticator(
                this, AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        return authenticator.getIBinder();
    }
}