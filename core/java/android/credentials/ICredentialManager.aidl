package android.credentials;

/**
 * Mediator between apps and credential manager service implementations.
 *
 * {@hide}
 */
oneway interface ICredentialManager {
    void getCredential();
}