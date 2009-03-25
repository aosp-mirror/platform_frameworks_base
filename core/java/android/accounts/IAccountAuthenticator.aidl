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

import android.accounts.IAccountAuthenticatorResponse;

/**
 * Service that allows the interaction with an authentication server.
 */
oneway interface IAccountAuthenticator {
    /**
     * prompts the user for account information and adds the result to the IAccountManager
     */
    void addAccount(in IAccountAuthenticatorResponse response, String accountType);

    /**
     * prompts the user for the credentials of the account
     */
    void authenticateAccount(in IAccountAuthenticatorResponse response, String name,
        String type, String password);

    /**
     * gets the password by either prompting the user or querying the IAccountManager
     */
    void getAuthToken(in IAccountAuthenticatorResponse response,
        String name, String type, String authTokenType);

    /**
     * does local analysis or uses a service in the cloud
     */
    void getPasswordStrength(in IAccountAuthenticatorResponse response,
        String accountType, String password);

    /**
     * checks with the login service in the cloud
     */
    void checkUsernameExistence(in IAccountAuthenticatorResponse response,
        String accountType, String username);

    /**
     * prompts the user for a new password and writes it to the IAccountManager
     */
    void updatePassword(in IAccountAuthenticatorResponse response, String name, String type);

    /**
     * launches an activity that lets the user edit and set the properties for an authenticator
     */
    void editProperties(in IAccountAuthenticatorResponse response, String accountType);
}
