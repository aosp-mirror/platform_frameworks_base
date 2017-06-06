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

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.android.frameworks.servicestests.R;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * This authenticator is to mock account authenticator to test AccountManagerService.
 */
public class TestAccountType1Authenticator extends AbstractAccountAuthenticator {
    private final AtomicInteger mTokenCounter  = new AtomicInteger(0);

    private final String mAccountType;
    private final Context mContext;

    public TestAccountType1Authenticator(Context context, String accountType) {
        super(context);
        mAccountType = accountType;
        mContext = context;
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        Bundle result = new Bundle();
        result.putString(AccountManager.KEY_ACCOUNT_NAME,
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS);
        result.putString(AccountManager.KEY_ACCOUNT_TYPE,
                AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
        result.putString(
                AccountManager.KEY_AUTHTOKEN,
                Integer.toString(mTokenCounter.incrementAndGet()));
        return result;
    }

    @Override
    public Bundle addAccount(
            AccountAuthenticatorResponse response,
            String accountType,
            String authTokenType,
            String[] requiredFeatures,
            Bundle options) throws NetworkErrorException {
        if (!mAccountType.equals(accountType)) {
            throw new IllegalArgumentException("Request to the wrong authenticator!");
        }
        String accountName = null;

        if (options != null) {
            accountName = options.getString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_NAME);
        }

        Bundle result = new Bundle();
        if (accountName.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS)) {
            // fill bundle with a success result.
            result.putString(AccountManager.KEY_ACCOUNT_NAME, accountName);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, mAccountType);
            result.putString(AccountManager.KEY_AUTHTOKEN,
                    Integer.toString(mTokenCounter.incrementAndGet()));
            result.putParcelable(AccountManagerServiceTestFixtures.KEY_OPTIONS_BUNDLE, options);
        } else if (accountName.equals(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE)) {
            // Specify data to be returned by the eventual activity.
            Intent eventualActivityResultData = new Intent();
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_NAME, accountName);
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_TYPE, accountType);
            // Fill result with Intent.
            Intent intent = new Intent(mContext, AccountAuthenticatorDummyActivity.class);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_RESULT, eventualActivityResultData);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK, response);

            result.putParcelable(AccountManager.KEY_INTENT, intent);
        } else {
            fillResultWithError(
                    result,
                    AccountManager.ERROR_CODE_INVALID_RESPONSE,
                    AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        }
        return result;
    }

    @Override
    public Bundle confirmCredentials(
            AccountAuthenticatorResponse response,
            Account account,
            Bundle options) throws NetworkErrorException {
        if (!mAccountType.equals(account.type)) {
            throw new IllegalArgumentException("Request to the wrong authenticator!");
        }
        Bundle result = new Bundle();

        if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS)) {
            // fill bundle with a success result.
            result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true);
            result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        } else if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE)) {
            // Specify data to be returned by the eventual activity.
            Intent eventualActivityResultData = new Intent();
            eventualActivityResultData.putExtra(AccountManager.KEY_BOOLEAN_RESULT, true);
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_NAME, account.name);
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_TYPE, account.type);

            // Fill result with Intent.
            Intent intent = new Intent(mContext, AccountAuthenticatorDummyActivity.class);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_RESULT,
                    eventualActivityResultData);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK, response);

            result.putParcelable(AccountManager.KEY_INTENT, intent);
        } else {
            // fill with error
            fillResultWithError(
                    result,
                    AccountManager.ERROR_CODE_INVALID_RESPONSE,
                    AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        }
        return result;
    }

    @Override
    public Bundle getAuthToken(
            AccountAuthenticatorResponse response,
            Account account,
            String authTokenType,
            Bundle options) throws NetworkErrorException {
        if (!mAccountType.equals(account.type)) {
            throw new IllegalArgumentException("Request to the wrong authenticator!");
        }
        Bundle result = new Bundle();

        long expiryMillis = (options == null)
                ? 0 : options.getLong(AccountManagerServiceTestFixtures.KEY_TOKEN_EXPIRY);
        if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS)) {
            // fill bundle with a success result.
            result.putString(
                    AccountManager.KEY_AUTHTOKEN, AccountManagerServiceTestFixtures.AUTH_TOKEN);
            result.putLong(
                    AbstractAccountAuthenticator.KEY_CUSTOM_TOKEN_EXPIRY,
                    expiryMillis);
            result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        } else if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE)) {
            // Specify data to be returned by the eventual activity.
            Intent eventualActivityResultData = new Intent();
            eventualActivityResultData.putExtra(
                    AccountManager.KEY_AUTHTOKEN, AccountManagerServiceTestFixtures.AUTH_TOKEN);
            eventualActivityResultData.putExtra(
                    AbstractAccountAuthenticator.KEY_CUSTOM_TOKEN_EXPIRY,
                    expiryMillis);
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_NAME, account.name);
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_TYPE, account.type);

            // Fill result with Intent.
            Intent intent = new Intent(mContext, AccountAuthenticatorDummyActivity.class);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_RESULT,
                    eventualActivityResultData);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK, response);

            result.putParcelable(AccountManager.KEY_INTENT, intent);

        } else {
            fillResultWithError(
                    result,
                    AccountManager.ERROR_CODE_INVALID_RESPONSE,
                    AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        }
        return result;
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        return AccountManagerServiceTestFixtures.AUTH_TOKEN_LABEL;
    }

    @Override
    public Bundle updateCredentials(
            AccountAuthenticatorResponse response,
            Account account,
            String authTokenType,
            Bundle options) throws NetworkErrorException {
        if (!mAccountType.equals(account.type)) {
            throw new IllegalArgumentException("Request to the wrong authenticator!");
        }
        Bundle result = new Bundle();

        if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS)) {
            // fill bundle with a success result.
            result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        } else if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE)) {
            // Specify data to be returned by the eventual activity.
            Intent eventualActivityResultData = new Intent();
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_NAME, account.name);
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_TYPE, account.type);

            // Fill result with Intent.
            Intent intent = new Intent(mContext, AccountAuthenticatorDummyActivity.class);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_RESULT,
                    eventualActivityResultData);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK, response);

            result.putParcelable(AccountManager.KEY_INTENT, intent);
        } else {
            // fill with error
            fillResultWithError(
                    result,
                    AccountManager.ERROR_CODE_INVALID_RESPONSE,
                    AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        }
        return result;
    }

    @Override
    public Bundle hasFeatures(
            AccountAuthenticatorResponse response,
            Account account,
            String[] features) throws NetworkErrorException {
        Bundle result = new Bundle();
        if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS)) {
            // fill bundle with true.
            result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true);
        } else if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS_2)) {
            result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true);
        } else if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE)) {
            // fill bundle with false.
            result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
        } else {
            // return null for error
            result = null;
        }

        response.onResult(result);
        return null;
    }

    @Override
    public Bundle startAddAccountSession(
            AccountAuthenticatorResponse response,
            String accountType,
            String authTokenType,
            String[] requiredFeatures,
            Bundle options) throws NetworkErrorException {
        if (!mAccountType.equals(accountType)) {
            throw new IllegalArgumentException("Request to the wrong authenticator!");
        }

        String accountName = null;
        Bundle sessionBundle = null;
        String password = null;
        if (options != null) {
            accountName = options.getString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_NAME);
            sessionBundle = options.getBundle(
                    AccountManagerServiceTestFixtures.KEY_ACCOUNT_SESSION_BUNDLE);
            password = options.getString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_PASSWORD);
        }

        Bundle result = new Bundle();
        if (accountName.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS)) {
            // fill bundle with a success result.
            result.putBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE, sessionBundle);
            result.putString(AccountManager.KEY_ACCOUNT_STATUS_TOKEN,
                    AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN);
            result.putString(AccountManager.KEY_PASSWORD, password);
            result.putString(AccountManager.KEY_AUTHTOKEN,
                    Integer.toString(mTokenCounter.incrementAndGet()));
        } else if (accountName.equals(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE)) {
            // Specify data to be returned by the eventual activity.
            Intent eventualActivityResultData = new Intent();
            eventualActivityResultData.putExtra(AccountManager.KEY_AUTHTOKEN,
                    Integer.toString(mTokenCounter.incrementAndGet()));
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_STATUS_TOKEN,
                    AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN);
            eventualActivityResultData.putExtra(AccountManager.KEY_PASSWORD, password);
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE,
                    sessionBundle);
            // Fill result with Intent.
            Intent intent = new Intent(mContext, AccountAuthenticatorDummyActivity.class);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_RESULT,
                    eventualActivityResultData);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK, response);

            result.putParcelable(AccountManager.KEY_INTENT, intent);
        } else {
            // fill with error
            fillResultWithError(result, options);
        }

        return result;
    }

    @Override
    public Bundle startUpdateCredentialsSession(
            AccountAuthenticatorResponse response,
            Account account,
            String authTokenType,
            Bundle options)
            throws NetworkErrorException {

        if (!mAccountType.equals(account.type)) {
            throw new IllegalArgumentException("Request to the wrong authenticator!");
        }

        String accountName = null;
        Bundle sessionBundle = null;
        if (options != null) {
            accountName = options.getString(AccountManagerServiceTestFixtures.KEY_ACCOUNT_NAME);
            sessionBundle = options.getBundle(
            AccountManagerServiceTestFixtures.KEY_ACCOUNT_SESSION_BUNDLE);
        }

        Bundle result = new Bundle();
        if (accountName.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS)) {
            // fill bundle with a success result.
            result.putBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE, sessionBundle);
            result.putString(AccountManager.KEY_ACCOUNT_STATUS_TOKEN,
                    AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN);
            result.putString(AccountManager.KEY_PASSWORD,
                    AccountManagerServiceTestFixtures.ACCOUNT_PASSWORD);
            result.putString(AccountManager.KEY_AUTHTOKEN,
                    Integer.toString(mTokenCounter.incrementAndGet()));
        } else if (accountName.equals(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE)) {
            // Specify data to be returned by the eventual activity.
            Intent eventualActivityResultData = new Intent();
            eventualActivityResultData.putExtra(AccountManager.KEY_AUTHTOKEN,
                    Integer.toString(mTokenCounter.incrementAndGet()));
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_STATUS_TOKEN,
                    AccountManagerServiceTestFixtures.ACCOUNT_STATUS_TOKEN);
            eventualActivityResultData.putExtra(AccountManager.KEY_PASSWORD,
                    AccountManagerServiceTestFixtures.ACCOUNT_PASSWORD);
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE,
                    sessionBundle);
            // Fill result with Intent.
            Intent intent = new Intent(mContext, AccountAuthenticatorDummyActivity.class);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_RESULT,
                    eventualActivityResultData);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK, response);

            result.putParcelable(AccountManager.KEY_INTENT, intent);
        } else {
            // fill with error
            fillResultWithError(result, options);
        }
        return result;
    }

    @Override
    public Bundle finishSession(AccountAuthenticatorResponse response,
            String accountType,
            Bundle sessionBundle) throws NetworkErrorException {

        if (!mAccountType.equals(accountType)) {
            throw new IllegalArgumentException("Request to the wrong authenticator!");
        }

        String accountName = null;
        if (sessionBundle != null) {
            accountName = sessionBundle.getString(
            AccountManagerServiceTestFixtures.KEY_ACCOUNT_NAME);
        }

        Bundle result = new Bundle();
        if (accountName.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS)) {
            // add sessionBundle into result for verification purpose
            result.putBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE, sessionBundle);
            // fill bundle with a success result.
            result.putString(AccountManager.KEY_ACCOUNT_NAME,
                    AccountManagerServiceTestFixtures.ACCOUNT_NAME);
            result.putString(AccountManager.KEY_ACCOUNT_TYPE,
                    AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
            result.putString(AccountManager.KEY_AUTHTOKEN,
                    Integer.toString(mTokenCounter.incrementAndGet()));
        } else if (accountName.equals(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE)) {
            // Specify data to be returned by the eventual activity.
            Intent eventualActivityResultData = new Intent();
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_NAME,
                    AccountManagerServiceTestFixtures.ACCOUNT_NAME);
            eventualActivityResultData.putExtra(AccountManager.KEY_ACCOUNT_TYPE,
                    AccountManagerServiceTestFixtures.ACCOUNT_TYPE_1);
            eventualActivityResultData.putExtra(AccountManager.KEY_AUTHTOKEN,
                    Integer.toString(mTokenCounter.incrementAndGet()));

            // Fill result with Intent.
            Intent intent = new Intent(mContext, AccountAuthenticatorDummyActivity.class);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_RESULT,
                    eventualActivityResultData);
            intent.putExtra(AccountManagerServiceTestFixtures.KEY_CALLBACK, response);

            result.putParcelable(AccountManager.KEY_INTENT, intent);
        } else {
            // fill with error
            fillResultWithError(result, sessionBundle);
        }
        return result;
    }

    @Override
    public Bundle isCredentialsUpdateSuggested(
            final AccountAuthenticatorResponse response,
            Account account,
            String statusToken) throws NetworkErrorException {

        Bundle result = new Bundle();
        if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS)) {
            // fill bundle with a success result.
            result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true);
        } else {
            // fill with error
            fillResultWithError(
                    result,
                    AccountManager.ERROR_CODE_INVALID_RESPONSE,
                    AccountManagerServiceTestFixtures.ERROR_MESSAGE);
        }

        response.onResult(result);
        return null;
    }

    @Override
    public Bundle getAccountRemovalAllowed(
            AccountAuthenticatorResponse response, Account account) throws NetworkErrorException {
        Bundle result = new Bundle();
        if (account.name.equals(AccountManagerServiceTestFixtures.ACCOUNT_NAME_SUCCESS)) {
            result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true);
        } else if (account.name.equals(
                AccountManagerServiceTestFixtures.ACCOUNT_NAME_INTERVENE)) {
            Intent intent = new Intent(mContext, AccountAuthenticatorDummyActivity.class);
            result.putParcelable(AccountManager.KEY_INTENT, intent);
        } else {
            result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
        }
        return result;
    }

    private void fillResultWithError(Bundle result, Bundle options) {
        int errorCode = AccountManager.ERROR_CODE_INVALID_RESPONSE;
        String errorMsg = "Default Error Message";
        if (options != null) {
            errorCode = options.getInt(AccountManager.KEY_ERROR_CODE);
            errorMsg = options.getString(AccountManager.KEY_ERROR_MESSAGE);
        }
        fillResultWithError(result, errorCode, errorMsg);
    }

    private void fillResultWithError(Bundle result, int errorCode, String errorMsg) {
        result.putInt(AccountManager.KEY_ERROR_CODE, errorCode);
        result.putString(AccountManager.KEY_ERROR_MESSAGE, errorMsg);
    }
}