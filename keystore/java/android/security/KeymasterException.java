package android.security;

/**
 * Keymaster exception.
 *
 * @hide
 */
public class KeymasterException extends Exception {

    private final int mErrorCode;

    public KeymasterException(int errorCode, String message) {
        super(message);
        mErrorCode = errorCode;
    }

    public int getErrorCode() {
        return mErrorCode;
    }
}
