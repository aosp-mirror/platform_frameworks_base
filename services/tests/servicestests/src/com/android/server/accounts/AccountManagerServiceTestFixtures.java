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

import android.accounts.Account;

import java.util.ArrayList;
import java.util.List;

/**
 * Constants shared between test AccountAuthenticators and AccountManagerServiceTest.
 */
public final class AccountManagerServiceTestFixtures {
    public static final String KEY_ACCOUNT_NAME = "account_manager_service_test:account_name_key";
    public static final String KEY_ACCOUNT_SESSION_BUNDLE =
            "account_manager_service_test:account_session_bundle_key";
    public static final String KEY_ACCOUNT_STATUS_TOKEN =
            "account_manager_service_test:account_status_token_key";
    public static final String KEY_ACCOUNT_PASSWORD =
            "account_manager_service_test:account_password_key";
    public static final String KEY_OPTIONS_BUNDLE =
            "account_manager_service_test:option_bundle_key";
    public static final String ACCOUNT_NAME_SUCCESS = "success_on_return@fixture.com";
    public static final String ACCOUNT_NAME_SUCCESS_2 = "success_on_return_2@fixture.com";
    public static final String ACCOUNT_NAME_INTERVENE = "intervene@fixture.com";
    public static final String ACCOUNT_NAME_ERROR = "error@fixture.com";

    public static final String ACCOUNT_NAME =
            "com.android.server.accounts.account_manager_service_test.account.name";
    public static final String ACCOUNT_TYPE_1 =
            "com.android.server.accounts.account_manager_service_test.account.type1";
    public static final String ACCOUNT_TYPE_2 =
            "com.android.server.accounts.account_manager_service_test.account.type2";
    public static final String ACCOUNT_FAKE_TYPE =
            "com.android.server.accounts.account_manager_service_test.account.type.fake";

    public static final String ACCOUNT_STATUS_TOKEN =
            "com.android.server.accounts.account_manager_service_test.account.status.token";
    public static final String AUTH_TOKEN_LABEL =
            "com.android.server.accounts.account_manager_service_test.auth.token.label";
    public static final String AUTH_TOKEN =
            "com.android.server.accounts.account_manager_service_test.auth.token";
    public static final String KEY_TOKEN_EXPIRY =
            "com.android.server.accounts.account_manager_service_test.auth.token.expiry";
    public static final String ACCOUNT_FEATURE1 =
            "com.android.server.accounts.account_manager_service_test.feature1";
    public static final String ACCOUNT_FEATURE2 =
            "com.android.server.accounts.account_manager_service_test.feature2";
    public static final String[] ACCOUNT_FEATURES =
            new String[]{ACCOUNT_FEATURE1, ACCOUNT_FEATURE2};
    public static final String CALLER_PACKAGE =
            "com.android.server.accounts.account_manager_service_test.caller.package";
    public static final String ACCOUNT_PASSWORD =
            "com.android.server.accounts.account_manager_service_test.account.password";
    public static final String KEY_RESULT = "account_manager_service_test:result";
    public static final String KEY_CALLBACK = "account_manager_service_test:callback";

    public static final Account ACCOUNT_SUCCESS =
            new Account(ACCOUNT_NAME_SUCCESS, ACCOUNT_TYPE_1);
    public static final Account ACCOUNT_SUCCESS_2 =
            new Account(ACCOUNT_NAME_SUCCESS_2, ACCOUNT_TYPE_1);
    public static final Account ACCOUNT_INTERVENE =
            new Account(ACCOUNT_NAME_INTERVENE, ACCOUNT_TYPE_1);
    public static final Account ACCOUNT_ERROR =
            new Account(ACCOUNT_NAME_ERROR, ACCOUNT_TYPE_1);
    public static final Account ACCOUNT_SUCCESS_TYPE_2 =
            new Account(ACCOUNT_NAME_SUCCESS, ACCOUNT_TYPE_2);

    public static final String SESSION_DATA_NAME_1 = "session.data.name.1";
    public static final String SESSION_DATA_VALUE_1 = "session.data.value.1";

    public static final String ERROR_MESSAGE =
        "com.android.server.accounts.account_manager_service_test.error.message";

    private AccountManagerServiceTestFixtures() {}
}
