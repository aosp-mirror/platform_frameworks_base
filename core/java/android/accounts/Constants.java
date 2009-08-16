/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.accounts;

public class Constants {
    // this should never be instantiated
    private Constants() {}

    public static final int ERROR_CODE_REMOTE_EXCEPTION = 1;
    public static final int ERROR_CODE_NETWORK_ERROR = 3;
    public static final int ERROR_CODE_CANCELED = 4;
    public static final int ERROR_CODE_INVALID_RESPONSE = 5;
    public static final int ERROR_CODE_UNSUPPORTED_OPERATION = 6;
    public static final int ERROR_CODE_BAD_ARGUMENTS = 7;
    public static final int ERROR_CODE_BAD_REQUEST = 8;

    public static final String ACCOUNTS_KEY = "accounts";
    public static final String AUTHENTICATOR_TYPES_KEY = "authenticator_types";
    public static final String USERDATA_KEY = "userdata";
    public static final String AUTHTOKEN_KEY = "authtoken";
    public static final String PASSWORD_KEY = "password";
    public static final String ACCOUNT_NAME_KEY = "authAccount";
    public static final String ACCOUNT_TYPE_KEY = "accountType";
    public static final String ERROR_CODE_KEY = "errorCode";
    public static final String ERROR_MESSAGE_KEY = "errorMessage";
    public static final String INTENT_KEY = "intent";
    public static final String BOOLEAN_RESULT_KEY = "booleanResult";
    public static final String ACCOUNT_AUTHENTICATOR_RESPONSE_KEY = "accountAuthenticatorResponse";
    public static final String ACCOUNT_MANAGER_RESPONSE_KEY = "accountManagerResponse";
    public static final String AUTH_FAILED_MESSAGE_KEY = "authFailedMessage";
    public static final String AUTH_TOKEN_LABEL_KEY = "authTokenLabelKey";

    public static final String AUTHENTICATOR_INTENT_ACTION =
            "android.accounts.AccountAuthenticator";
    public static final String AUTHENTICATOR_META_DATA_NAME =
            "android.accounts.AccountAuthenticator";
    public static final String AUTHENTICATOR_ATTRIBUTES_NAME = "account-authenticator";

    /**
     * Action sent as a broadcast Intent by the AccountsService
     * when accounts are added to and/or removed from the device's
     * database, or when the primary account is changed.
     */
    public static final String LOGIN_ACCOUNTS_CHANGED_ACTION =
        "android.accounts.LOGIN_ACCOUNTS_CHANGED";
}
