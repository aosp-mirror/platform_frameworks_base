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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Base class for implementing an Activity that is used to help implement an
 * AbstractAccountAuthenticator. If the AbstractAccountAuthenticator needs to return an Intent
 * that is to be used to launch an Activity that needs to return results to satisfy an
 * AbstractAccountAuthenticator request, it should store the AccountAuthenticatorResponse
 * inside of the Intent as follows:
 * <p>
 *      intent.putExtra(Constants.ACCOUNT_AUTHENTICATOR_RESPONSE_KEY, response);
 * <p>
 * The activity that it launches should extend the AccountAuthenticatorActivity. If this
 * activity has a result that satisfies the original request it sets it via:
 * <p>
 *       setAccountAuthenticatorResult(result)
 * <p>
 * This result will be sent as the result of the request when the activity finishes. If this
 * is never set or if it is set to null then the request will be canceled when the activity
 * finishes.
 */
public class AccountAuthenticatorActivity extends Activity {
    private AccountAuthenticatorResponse mAccountAuthenticatorResponse = null;
    private Bundle mResultBundle = null;

    /**
     * Set the result that is to be sent as the result of the request that caused this
     * Activity to be launched. If result is null or this method is never called then
     * the request will be canceled.
     * @param result this is returned as the result of the AbstractAccountAuthenticator request
     */
    public final void setAccountAuthenticatorResult(Bundle result) {
        mResultBundle = result;
    }

    /**
     * Retreives the AccountAuthenticatorResponse from either the intent of the icicle, if the
     * icicle is non-zero.
     * @param icicle the save instance data of this Activity, may be null
     */
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if (icicle == null) {
            Intent intent = getIntent();
            mAccountAuthenticatorResponse =
                    intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
        } else {
            mAccountAuthenticatorResponse =
                    icicle.getParcelable(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
        }

        if (mAccountAuthenticatorResponse != null) {
            mAccountAuthenticatorResponse.onRequestContinued();
        }
    }

    /**
     * Saves the AccountAuthenticatorResponse in the instance state.
     * @param outState where to store any instance data
     */
    protected void onSaveInstanceState(Bundle outState) {
        outState.putParcelable(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE,
                mAccountAuthenticatorResponse);
        super.onSaveInstanceState(outState);
    }

    /**
     * Sends the result or a Constants.ERROR_CODE_CANCELED error if a result isn't present.
     */
    public void finish() {
        if (mAccountAuthenticatorResponse != null) {
            // send the result bundle back if set, otherwise send an error.
            if (mResultBundle != null) {
                mAccountAuthenticatorResponse.onResult(mResultBundle);
            } else {
                mAccountAuthenticatorResponse.onError(AccountManager.ERROR_CODE_CANCELED, "canceled");
            }
            mAccountAuthenticatorResponse = null;
        }
        super.finish();
    }
}
