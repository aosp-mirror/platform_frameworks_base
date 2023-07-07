package android.service.credentials;

import android.service.credentials.BeginGetCredentialResponse;
import android.os.ICancellationSignal;


/**
 * Interface from the system to a credential provider service.
 *
 * @hide
 */
oneway interface IBeginGetCredentialCallback {
    void onSuccess(in BeginGetCredentialResponse response);
    void onFailure(String errorType, in CharSequence message);
    void onCancellable(in ICancellationSignal cancellation);
}