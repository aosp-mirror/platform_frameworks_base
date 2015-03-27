package android.security;

import android.security.keymaster.KeymasterDefs;

/**
 * @hide
 */
public abstract class KeymasterUtils {
    private KeymasterUtils() {}

    public static KeymasterException getExceptionForKeymasterError(int keymasterErrorCode) {
        switch (keymasterErrorCode) {
            case KeymasterDefs.KM_ERROR_INVALID_AUTHORIZATION_TIMEOUT:
                // The name of this parameter significantly differs between Keymaster and framework
                // APIs. Use the framework wording to make life easier for developers.
                return new KeymasterException("Invalid user authentication validity duration");
            default:
                return new KeymasterException(KeymasterDefs.getErrorMessage(keymasterErrorCode));
        }
    }
}
