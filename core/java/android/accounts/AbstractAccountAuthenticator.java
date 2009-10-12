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

import android.os.Bundle;
import android.os.RemoteException;
import android.os.Binder;
import android.os.IBinder;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.Manifest;

/**
 * Base class for creating AccountAuthenticators. This implements the IAccountAuthenticator
 * binder interface and also provides helper libraries to simplify the creation of
 * AccountAuthenticators.
 */
public abstract class AbstractAccountAuthenticator {
    private final Context mContext;

    public AbstractAccountAuthenticator(Context context) {
        mContext = context;
    }

    private class Transport extends IAccountAuthenticator.Stub {
        public void addAccount(IAccountAuthenticatorResponse response, String accountType,
                String authTokenType, String[] requiredFeatures, Bundle options)
                throws RemoteException {
            checkBinderPermission();
            final Bundle result;
            try {
                result = AbstractAccountAuthenticator.this.addAccount(
                    new AccountAuthenticatorResponse(response),
                        accountType, authTokenType, requiredFeatures, options);
            } catch (NetworkErrorException e) {
                response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
                return;
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "addAccount not supported");
                return;
            }
            if (result != null) {
                response.onResult(result);
            } else {
                response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                        "no response from the authenticator");
            }
        }

        public void confirmCredentials(IAccountAuthenticatorResponse response,
                Account account, Bundle options) throws RemoteException {
            checkBinderPermission();
            final Bundle result;
            try {
                result = AbstractAccountAuthenticator.this.confirmCredentials(
                    new AccountAuthenticatorResponse(response), account, options);
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "confirmCredentials not supported");
                return;
            }
            if (result != null) {
                response.onResult(result);
            } else {
                response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                        "no response from the authenticator");
            }
        }

        public void getAuthTokenLabel(IAccountAuthenticatorResponse response,
                String authTokenType)
                throws RemoteException {
            checkBinderPermission();
            try {
                Bundle result = new Bundle();
                result.putString(AccountManager.KEY_AUTH_TOKEN_LABEL,
                        AbstractAccountAuthenticator.this.getAuthTokenLabel(authTokenType));
                response.onResult(result);
            } catch (IllegalArgumentException e) {
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS,
                        "unknown authTokenType");
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "getAuthTokenTypeLabel not supported");
            }
        }

        public void getAuthToken(IAccountAuthenticatorResponse response,
                Account account, String authTokenType, Bundle loginOptions)
                throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.getAuthToken(
                        new AccountAuthenticatorResponse(response), account,
                        authTokenType, loginOptions);
                if (result != null) {
                    response.onResult(result);
                } else {
                    response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                            "no response from the authenticator");
                }
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "getAuthToken not supported");
            } catch (NetworkErrorException e) {
                response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
            }
        }

        public void updateCredentials(IAccountAuthenticatorResponse response, Account account,
                String authTokenType, Bundle loginOptions) throws RemoteException {
            checkBinderPermission();
            final Bundle result;
            try {
                result = AbstractAccountAuthenticator.this.updateCredentials(
                    new AccountAuthenticatorResponse(response), account,
                        authTokenType, loginOptions);
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "updateCredentials not supported");
                return;
            }
            if (result != null) {
                response.onResult(result);
            } else {
                response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                        "no response from the authenticator");
            }
        }

        public void editProperties(IAccountAuthenticatorResponse response,
                String accountType) throws RemoteException {
            checkBinderPermission();
            final Bundle result;
            try {
                result = AbstractAccountAuthenticator.this.editProperties(
                    new AccountAuthenticatorResponse(response), accountType);
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "editProperties not supported");
                return;
            }
            if (result != null) {
                response.onResult(result);
            } else {
                response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                        "no response from the authenticator");
            }
        }

        public void hasFeatures(IAccountAuthenticatorResponse response,
                Account account, String[] features) throws RemoteException {
            checkBinderPermission();
            final Bundle result;
            try {
                result = AbstractAccountAuthenticator.this.hasFeatures(
                    new AccountAuthenticatorResponse(response), account, features);
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "hasFeatures not supported");
                return;
            } catch (NetworkErrorException e) {
                response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
                return;
            }
            if (result != null) {
                response.onResult(result);
            } else {
                response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                        "no response from the authenticator");
            }
        }

        public void getAccountRemovalAllowed(IAccountAuthenticatorResponse response,
                Account account) throws RemoteException {
            checkBinderPermission();
            try {
                final Bundle result = AbstractAccountAuthenticator.this.getAccountRemovalAllowed(
                    new AccountAuthenticatorResponse(response), account);
                if (result != null) {
                    response.onResult(result);
                } else {
                    response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                            "no response from the authenticator");
                }
            } catch (UnsupportedOperationException e) {
                response.onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                        "getAccountRemovalAllowed not supported");
            } catch (NetworkErrorException e) {
                response.onError(AccountManager.ERROR_CODE_NETWORK_ERROR, e.getMessage());
            }
        }
    }

    private void checkBinderPermission() {
        final int uid = Binder.getCallingUid();
        final String perm = Manifest.permission.ACCOUNT_MANAGER;
        if (mContext.checkCallingOrSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("caller uid " + uid + " lacks " + perm);
        }
    }

    private Transport mTransport = new Transport();

    /**
     * @return the IBinder for the AccountAuthenticator
     */
    public final IBinder getIBinder() {
        return mTransport.asBinder();
    }

    /**
     * Returns a Bundle that contains the Intent of the activity that can be used to edit the
     * properties. In order to indicate success the activity should call response.setResult()
     * with a non-null Bundle.
     * @param response used to set the result for the request. If the Constants.INTENT_KEY
     *   is set in the bundle then this response field is to be used for sending future
     *   results if and when the Intent is started.
     * @param accountType the AccountType whose properties are to be edited.
     * @return a Bundle containing the result or the Intent to start to continue the request.
     *   If this is null then the request is considered to still be active and the result should
     *   sent later using response.
     */
    public abstract Bundle editProperties(AccountAuthenticatorResponse response,
            String accountType);
    public abstract Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
            String authTokenType, String[] requiredFeatures, Bundle options)
            throws NetworkErrorException;
    public abstract Bundle confirmCredentials(AccountAuthenticatorResponse response,
            Account account, Bundle options);
    public abstract Bundle getAuthToken(AccountAuthenticatorResponse response,
            Account account, String authTokenType, Bundle loginOptions)
            throws NetworkErrorException;
    public abstract String getAuthTokenLabel(String authTokenType);
    public abstract Bundle updateCredentials(AccountAuthenticatorResponse response,
            Account account, String authTokenType, Bundle loginOptions);
    public abstract Bundle hasFeatures(AccountAuthenticatorResponse response,
            Account account, String[] features) throws NetworkErrorException;
    public Bundle getAccountRemovalAllowed(AccountAuthenticatorResponse response,
            Account account) throws NetworkErrorException {
        final Bundle result = new Bundle();
        result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true);
        return result;
    }
}
