package android.service.credentials;

import android.service.credentials.BeginCreateCredentialResponse;
import android.os.ICancellationSignal;

/**
 * Interface from the system to a credential provider service.
 *
 * @hide
 */
oneway interface IBeginCreateCredentialCallback {
    void onSuccess(in BeginCreateCredentialResponse request);
    void onFailure(String errorType, in CharSequence message);
    void onCancellable(in ICancellationSignal cancellation);
}