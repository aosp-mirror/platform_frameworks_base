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
import android.content.IntentSender;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.os.UserHandle;

import java.util.Map;

/**
 * Central application service that provides account management.
 * @hide
 */
interface IAccountManager {
    String getPassword(in Account account);
    String getUserData(in Account account, String key);
    AuthenticatorDescription[] getAuthenticatorTypes(int userId);
    Account[] getAccountsForPackage(String packageName, int uid, String opPackageName);
    Account[] getAccountsByTypeForPackage(String type, String packageName, String opPackageName);
    Account[] getAccountsAsUser(String accountType, int userId, String opPackageName);
    void hasFeatures(in IAccountManagerResponse response, in Account account, in String[] features,
        String opPackageName);
    void getAccountByTypeAndFeatures(in IAccountManagerResponse response, String accountType,
        in String[] features, String opPackageName);
    void getAccountsByFeatures(in IAccountManagerResponse response, String accountType,
        in String[] features, String opPackageName);
    boolean addAccountExplicitly(in Account account, String password, in Bundle extras);
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
    boolean accountAuthenticated(in Account account);
    void getAuthTokenLabel(in IAccountManagerResponse response, String accountType,
        String authTokenType);

    /* Shared accounts */
    void addSharedAccountsFromParentUser(int parentUserId, int userId, String opPackageName);

    /* Account renaming. */
    void renameAccount(in IAccountManagerResponse response, in Account accountToRename, String newName);
    String getPreviousName(in Account account);

    /* Add account in two steps. */
    void startAddAccountSession(in IAccountManagerResponse response, String accountType,
        String authTokenType, in String[] requiredFeatures, boolean expectActivityLaunch,
        in Bundle options);

    /* Update credentials in two steps. */
    void startUpdateCredentialsSession(in IAccountManagerResponse response, in Account account,
        String authTokenType, boolean expectActivityLaunch, in Bundle options);

    /* Finish session started by startAddAccountSession(...) or startUpdateCredentialsSession(...)
    for user */
    void finishSessionAsUser(in IAccountManagerResponse response, in Bundle sessionBundle,
        boolean expectActivityLaunch, in Bundle appInfo, int userId);

    /* Check if an account exists on any user on the device. */
    boolean someUserHasAccount(in Account account);

    /* Check if credentials update is suggested */
    void isCredentialsUpdateSuggested(in IAccountManagerResponse response, in Account account,
        String statusToken);

    /* Returns Map<String, Integer> from package name to visibility with all values stored for given account */
    @SuppressWarnings(value = {"untyped-collection"})
    Map getPackagesAndVisibilityForAccount(in Account account);
    @SuppressWarnings(value = {"untyped-collection"})
    boolean addAccountExplicitlyWithVisibility(in Account account, String password, in Bundle extras,
            in Map visibility);
    boolean setAccountVisibility(in Account a, in String packageName, int newVisibility);
    int getAccountVisibility(in Account a, in String packageName);
    /* Type may be null returns Map <Account, Integer>*/
    @SuppressWarnings(value = {"untyped-collection"})
    Map getAccountsAndVisibilityForPackage(in String packageName, in String accountType);

    void registerAccountListener(in String[] accountTypes, String opPackageName);
    void unregisterAccountListener(in String[] accountTypes, String opPackageName);

    /* Check if the package in a user can access an account */
    boolean hasAccountAccess(in Account account, String packageName, in UserHandle userHandle);
    /* Crate an intent to request account access for package and a given user id */
    IntentSender createRequestAccountAccessIntentSenderAsUser(in Account account,
        String packageName, in UserHandle userHandle);

    void onAccountAccessed(String token);
}
