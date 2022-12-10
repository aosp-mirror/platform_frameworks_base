package android.service.credentials;

import android.service.credentials.BeginGetCredentialResponse;

/**
 * Interface from the system to a credential provider service.
 *
 * @hide
 */
oneway interface IBeginGetCredentialCallback {
    void onSuccess(in BeginGetCredentialResponse response);
    void onFailure(String errorType, in CharSequence message);
}