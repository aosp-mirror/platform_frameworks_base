package android.service.remotelockscreenvalidation;

import android.app.RemoteLockscreenValidationResult;

/**
* Callback interface for remote device lockscreen validation
* @hide
*/
interface IRemoteLockscreenValidationCallback {
    oneway void onSuccess(in RemoteLockscreenValidationResult result);
    oneway void onFailure(in String message);
}
