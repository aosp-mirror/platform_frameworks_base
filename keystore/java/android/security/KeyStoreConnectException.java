package android.security;

/**
 * Indicates a communications error with keystore service.
 *
 * @hide
 */
public class KeyStoreConnectException extends CryptoOperationException {
    public KeyStoreConnectException() {
        super("Failed to communicate with keystore service");
    }
}
