package android.service.controls;

/** @hide */
interface TokenProvider {
    void setAuthToken(String token);
    String getAccountName();
}