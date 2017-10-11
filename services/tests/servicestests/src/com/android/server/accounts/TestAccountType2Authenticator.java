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
public class TestAccountType2Authenticator extends AbstractAccountAuthenticator {
    private final AtomicInteger mTokenCounter  = new AtomicInteger(0);

    private final String mAccountType;
    private final Context mContext;

    public TestAccountType2Authenticator(Context context, String accountType) {
        super(context);
        mAccountType = accountType;
        mContext = context;
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        throw new UnsupportedOperationException(
                "editProperties is not supported by the TestAccountType2Authenticator");
    }

    @Override
    public Bundle addAccount(
            AccountAuthenticatorResponse response,
            String accountType,
            String authTokenType,
            String[] requiredFeatures,
            Bundle options) throws NetworkErrorException {
        throw new UnsupportedOperationException(
                "addAccount is not supported by the TestAccountType2Authenticator");
    }

    @Override
    public Bundle confirmCredentials(
            AccountAuthenticatorResponse response,
            Account account,
            Bundle options) throws NetworkErrorException {
        throw new UnsupportedOperationException(
                "confirmCredentials is not supported by the TestAccountType2Authenticator");
    }

    @Override
    public Bundle getAuthToken(
            AccountAuthenticatorResponse response,
            Account account,
            String authTokenType,
            Bundle options) throws NetworkErrorException {
        throw new UnsupportedOperationException(
                "getAuthToken is not supported by the TestAccountType2Authenticator");
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        throw new UnsupportedOperationException(
                "getAuthTokenLabel is not supported by the TestAccountType2Authenticator");
    }

    @Override
    public Bundle updateCredentials(
            AccountAuthenticatorResponse response,
            Account account,
            String authTokenType,
            Bundle options) throws NetworkErrorException {
        throw new UnsupportedOperationException(
                "updateCredentials is not supported by the TestAccountType2Authenticator");
    }

    @Override
    public Bundle hasFeatures(
            AccountAuthenticatorResponse response,
            Account account,
            String[] features) throws NetworkErrorException {
        throw new UnsupportedOperationException(
                "hasFeatures is not supported by the TestAccountType2Authenticator");
    }

    @Override
    public Bundle startAddAccountSession(
            AccountAuthenticatorResponse response,
            String accountType,
            String authTokenType,
            String[] requiredFeatures,
            Bundle options) throws NetworkErrorException {
        throw new UnsupportedOperationException(
                "startAddAccountSession is not supported by the TestAccountType2Authenticator");
    }

    @Override
    public Bundle startUpdateCredentialsSession(
            AccountAuthenticatorResponse response,
            Account account,
            String authTokenType,
            Bundle options)
            throws NetworkErrorException {
        throw new UnsupportedOperationException(
                "startUpdateCredentialsSession is not supported " +
                "by the TestAccountType2Authenticator");
    }

    @Override
    public Bundle finishSession(AccountAuthenticatorResponse response,
            String accountType,
            Bundle sessionBundle) throws NetworkErrorException {
        throw new UnsupportedOperationException(
                "finishSession is not supported by the TestAccountType2Authenticator");
    }

    @Override
    public Bundle isCredentialsUpdateSuggested(
            final AccountAuthenticatorResponse response,
            Account account,
            String statusToken) throws NetworkErrorException {
        throw new UnsupportedOperationException(
                "isCredentialsUpdateSuggested is not supported " +
                "by the TestAccountType2Authenticator");
    }
}