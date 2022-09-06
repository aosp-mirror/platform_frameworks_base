package android.service.credentials;

import android.service.credentials.GetCredentialsResponse;

/**
 * Interface from the system to a credential provider service.
 *
 * @hide
 */
oneway interface IGetCredentialsCallback {
    void onSuccess(in GetCredentialsResponse response);
    void onFailure(int errorCode, in CharSequence message);
}