package com.android.internal.widget;

import android.annotation.NonNull;
import android.os.AsyncTask;

import com.android.internal.widget.LockPatternUtils.RequestThrottledException;

/**
 * Helper class to check/verify PIN/Password/Pattern asynchronously.
 */
public final class LockPatternChecker {
    /**
     * Interface for a callback to be invoked after security check.
     */
    public interface OnCheckCallback {

        /**
         * Invoked as soon as possible we know that the credentials match. This will be called
         * earlier than {@link #onChecked} but only if the credentials match.
         */
        default void onEarlyMatched() {}

        /**
         * Invoked when a security check is finished.
         *
         * @param matched Whether the PIN/Password/Pattern matches the stored one.
         * @param throttleTimeoutMs The amount of time in ms to wait before reattempting
         * the call. Only non-0 if matched is false.
         */
        void onChecked(boolean matched, int throttleTimeoutMs);

        /**
         * Called when the underlying AsyncTask was cancelled.
         */
        default void onCancelled() {}
    }

    /**
     * Interface for a callback to be invoked after security verification.
     */
    public interface OnVerifyCallback {
        /**
         * Invoked when a security verification is finished.
         *
         * @param response The response, optionally containing Gatekeeper HAT or Gatekeeper Password
         * @param throttleTimeoutMs The amount of time in ms to wait before reattempting
         * the call. Only non-0 if the response is {@link VerifyCredentialResponse#RESPONSE_RETRY}.
         */
        void onVerified(@NonNull VerifyCredentialResponse response, int throttleTimeoutMs);
    }

    /**
     * Verify a lockscreen credential asynchronously.
     *
     * @param utils The LockPatternUtils instance to use.
     * @param credential The credential to check.
     * @param userId The user to check against the credential.
     * @param flags See {@link LockPatternUtils.VerifyFlag}
     * @param callback The callback to be invoked with the verification result.
     */
    public static AsyncTask<?, ?, ?> verifyCredential(final LockPatternUtils utils,
            final LockscreenCredential credential,
            final int userId,
            final @LockPatternUtils.VerifyFlag int flags,
            final OnVerifyCallback callback) {
        // Create a copy of the credential since checking credential is asynchrounous.
        final LockscreenCredential credentialCopy = credential.duplicate();
        AsyncTask<Void, Void, VerifyCredentialResponse> task =
                new AsyncTask<Void, Void, VerifyCredentialResponse>() {
            @Override
            protected VerifyCredentialResponse doInBackground(Void... args) {
                return utils.verifyCredential(credentialCopy, userId, flags);
            }

            @Override
            protected void onPostExecute(@NonNull VerifyCredentialResponse result) {
                callback.onVerified(result, result.getTimeout());
                credentialCopy.zeroize();
            }

            @Override
            protected void onCancelled() {
                credentialCopy.zeroize();
            }
        };
        task.execute();
        return task;
    }

    /**
     * Checks a lockscreen credential asynchronously.
     *
     * @param utils The LockPatternUtils instance to use.
     * @param credential The credential to check.
     * @param userId The user to check against the credential.
     * @param callback The callback to be invoked with the check result.
     */
    public static AsyncTask<?, ?, ?> checkCredential(final LockPatternUtils utils,
            final LockscreenCredential credential,
            final int userId,
            final OnCheckCallback callback) {
        // Create a copy of the credential since checking credential is asynchrounous.
        final LockscreenCredential credentialCopy = credential.duplicate();
        AsyncTask<Void, Void, Boolean> task = new AsyncTask<Void, Void, Boolean>() {
            private int mThrottleTimeout;

            @Override
            protected Boolean doInBackground(Void... args) {
                try {
                    return utils.checkCredential(credentialCopy, userId, callback::onEarlyMatched);
                } catch (RequestThrottledException ex) {
                    mThrottleTimeout = ex.getTimeoutMs();
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean result) {
                callback.onChecked(result, mThrottleTimeout);
                credentialCopy.zeroize();
            }

            @Override
            protected void onCancelled() {
                callback.onCancelled();
                credentialCopy.zeroize();
            }
        };
        task.execute();
        return task;
    }

    /**
     * Perform a lockscreen credential verification explicitly on a managed profile with unified
     * challenge, using the parent user's credential.
     *
     * @param utils The LockPatternUtils instance to use.
     * @param credential The credential to check.
     * @param userId The user to check against the credential.
     * @param flags See {@link LockPatternUtils.VerifyFlag}
     * @param callback The callback to be invoked with the verification result.
     */
    public static AsyncTask<?, ?, ?> verifyTiedProfileChallenge(final LockPatternUtils utils,
            final LockscreenCredential credential,
            final int userId,
            final @LockPatternUtils.VerifyFlag int flags,
            final OnVerifyCallback callback) {
        // Create a copy of the credential since checking credential is asynchronous.
        final LockscreenCredential credentialCopy = credential.duplicate();
        AsyncTask<Void, Void, VerifyCredentialResponse> task =
                new AsyncTask<Void, Void, VerifyCredentialResponse>() {
            @Override
            protected VerifyCredentialResponse doInBackground(Void... args) {
                return utils.verifyTiedProfileChallenge(credentialCopy, userId, flags);
            }

            @Override
            protected void onPostExecute(@NonNull VerifyCredentialResponse response) {
                callback.onVerified(response, response.getTimeout());
                credentialCopy.zeroize();
            }

            @Override
            protected void onCancelled() {
                credentialCopy.zeroize();
            }
        };
        task.execute();
        return task;
    }
}
