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

import android.accounts.IAccountManagerResponse;
import android.accounts.Account;
import android.accounts.AuthenticatorDescription;
import android.os.Bundle;


/**
 * Central application service that provides account management.
 * @hide
 */
interface IAccountManager {
    String getPassword(in Account account);
    String getUserData(in Account account, String key);
    AuthenticatorDescription[] getAuthenticatorTypes(int userId);
    Account[] getAccounts(String accountType);
    Account[] getAccountsForPackage(String packageName, int uid);
    Account[] getAccountsByTypeForPackage(String type, String packageName);
    Account[] getAccountsAsUser(String accountType, int userId);
    void hasFeatures(in IAccountManagerResponse response, in Account account, in String[] features);
    void getAccountsByFeatures(in IAccountManagerResponse response, String accountType, in String[] features);
    boolean addAccountExplicitly(in Account account, String password, in Bundle extras);
    void removeAccount(in IAccountManagerResponse response, in Account account,
        boolean expectActivityLaunch);
    void removeAccountAsUser(in IAccountManagerResponse response, in Account account,
        boolean expectActivityLaunch, int userId);
    boolean removeAccountExplicitly(in Account account);
    void copyAccountToUser(in IAccountManagerResponse response, in Account account,
        int userFrom, int userTo);
    void invalidateAuthToken(String accountType, String authToken);
    String peekAuthToken(in Account account, String authTokenType);
    void setAuthToken(in Account account, String authTokenType, String authToken);
    void setPassword(in Account account, String password);
    void clearPassword(in Account account);
    void setUserData(in Account account, String key, String value);
    void updateAppPermission(in Account account, String authTokenType, int uid, boolean value);

    void getAuthToken(in IAccountManagerResponse response, in Account account,
        String authTokenType, boolean notifyOnAuthFailure, boolean expectActivityLaunch,
        in Bundle options);
    void addAccount(in IAccountManagerResponse response, String accountType,
        String authTokenType, in String[] requiredFeatures, boolean expectActivityLaunch,
        in Bundle options);
    void addAccountAsUser(in IAccountManagerResponse response, String accountType,
        String authTokenType, in String[] requiredFeatures, boolean expectActivityLaunch,
        in Bundle options, int userId);
    void updateCredentials(in IAccountManagerResponse response, in Account account,
        String authTokenType, boolean expectActivityLaunch, in Bundle options);
    void editProperties(in IAccountManagerResponse response, String accountType,
        boolean expectActivityLaunch);
    void confirmCredentialsAsUser(in IAccountManagerResponse response, in Account account,
        in Bundle options, boolean expectActivityLaunch, int userId);
    void getAuthTokenLabel(in IAccountManagerResponse response, String accountType,
        String authTokenType);

    /* Shared accounts */
    boolean addSharedAccountAsUser(in Account account, int userId);
    Account[] getSharedAccountsAsUser(int userId);
    boolean removeSharedAccountAsUser(in Account account, int userId);

    /* Account renaming. */
    void renameAccount(in IAccountManagerResponse response, in Account accountToRename, String newName);
    String getPreviousName(in Account account);
    boolean renameSharedAccountAsUser(in Account accountToRename, String newName, int userId);

}
