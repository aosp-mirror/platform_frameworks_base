package android.service.credentials;

import android.service.credentials.CreateCredentialResponse;

/**
 * Interface from the system to a credential provider service.
 *
 * @hide
 */
oneway interface ICreateCredentialCallback {
    void onSuccess(in CreateCredentialResponse request);
    void onFailure(int errorCode, in CharSequence message);
}