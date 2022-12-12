package android.service.credentials;

import android.service.credentials.BeginGetCredentialsResponse;

/**
 * Interface from the system to a credential provider service.
 *
 * @hide
 */
oneway interface IBeginGetCredentialsCallback {
    void onSuccess(in BeginGetCredentialsResponse response);
    void onFailure(int errorCode, in CharSequence message);
}