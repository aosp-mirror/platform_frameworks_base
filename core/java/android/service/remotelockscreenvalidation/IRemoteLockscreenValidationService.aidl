package android.service.remotelockscreenvalidation;

import android.app.RemoteLockscreenValidationResult;
import android.service.remotelockscreenvalidation.IRemoteLockscreenValidationCallback;

/**
* Interface used by the System to validate remote device lockscreen.
* {@hide}
*/
interface IRemoteLockscreenValidationService {
    void validateLockscreenGuess(in byte[] guess, in IRemoteLockscreenValidationCallback callback);
}
