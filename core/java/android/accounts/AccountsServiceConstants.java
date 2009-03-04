/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Intent;

/**
 * Miscellaneous constants used by the AccountsService and its
 * clients.
 */
// TODO: These constants *could* come directly from the
// IAccountsService interface, but that's not possible since the
// aidl compiler doesn't let you define constants (yet.)
public class AccountsServiceConstants {
    /** This class is never instantiated. */
    private AccountsServiceConstants() {
    }

    /**
     * Action sent as a broadcast Intent by the AccountsService
     * when accounts are added to and/or removed from the device's
     * database, or when the primary account is changed.
     */
    public static final String LOGIN_ACCOUNTS_CHANGED_ACTION =
        "android.accounts.LOGIN_ACCOUNTS_CHANGED";

    /**
     * Action sent as a broadcast Intent by the AccountsService
     * when it starts up and no accounts are available (so some should be added).
     */
    public static final String LOGIN_ACCOUNTS_MISSING_ACTION =
        "android.accounts.LOGIN_ACCOUNTS_MISSING";

    /**
     * Action on the intent used to bind to the IAccountsService interface. This
     * is used for services that have multiple interfaces (allowing
     * them to differentiate the interface intended, and return the proper
     * Binder.)
     */
    private static final String ACCOUNTS_SERVICE_ACTION = "android.accounts.IAccountsService";

    /*
     * The intent uses a component in addition to the action to ensure the actual
     * accounts service is bound to (a malicious third-party app could
     * theoretically have a service with the same action).
     */
    /** The intent used to bind to the accounts service. */
    public static final Intent SERVICE_INTENT =
        new Intent()
            .setClassName("com.google.android.googleapps",
                    "com.google.android.googleapps.GoogleLoginService")
            .setAction(ACCOUNTS_SERVICE_ACTION);

    /**
     * Checks whether the intent is to bind to the accounts service.
     * 
     * @param bindIntent The Intent used to bind to the service. 
     * @return Whether the intent is to bind to the accounts service.
     */
    public static final boolean isForAccountsService(Intent bindIntent) {
        String otherAction = bindIntent.getAction();
        return otherAction != null && otherAction.equals(ACCOUNTS_SERVICE_ACTION);
    }
}
