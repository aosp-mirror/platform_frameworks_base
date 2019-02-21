package com.android.internal.widget;

import android.os.AsyncTask;

import com.android.internal.widget.LockPatternUtils.RequestThrottledException;

import java.util.ArrayList;
import java.util.List;

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
         * @param attestation The attestation that the challenge was verified, or null.
         * @param throttleTimeoutMs The amount of time in ms to wait before reattempting
         * the call. Only non-0 if attestation is null.
         */
        void onVerified(byte[] attestation, int throttleTimeoutMs);
    }

    /**
     * Verify a pattern asynchronously.
     *
     * @param utils The LockPatternUtils instance to use.
     * @param pattern The pattern to check.
     * @param challenge The challenge to verify against the pattern.
     * @param userId The user to check against the pattern.
     * @param callback The callback to be invoked with the verification result.
     */
    public static AsyncTask<?, ?, ?> verifyPattern(final LockPatternUtils utils,
            final List<LockPatternView.Cell> pattern,
            final long challenge,
            final int userId,
            final OnVerifyCallback callback) {
        AsyncTask<Void, Void, byte[]> task = new AsyncTask<Void, Void, byte[]>() {
            private int mThrottleTimeout;
            private List<LockPatternView.Cell> patternCopy;

            @Override
            protected void onPreExecute() {
                // Make a copy of the pattern to prevent race conditions.
                // No need to clone the individual cells because they are immutable.
                patternCopy = new ArrayList(pattern);
            }

            @Override
            protected byte[] doInBackground(Void... args) {
                try {
                    return utils.verifyPattern(patternCopy, challenge, userId);
                } catch (RequestThrottledException ex) {
                    mThrottleTimeout = ex.getTimeoutMs();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(byte[] result) {
                callback.onVerified(result, mThrottleTimeout);
            }
        };
        task.execute();
        return task;
    }

    /**
     * Checks a pattern asynchronously.
     *
     * @param utils The LockPatternUtils instance to use.
     * @param pattern The pattern to check.
     * @param userId The user to check against the pattern.
     * @param callback The callback to be invoked with the check result.
     */
    public static AsyncTask<?, ?, ?> checkPattern(final LockPatternUtils utils,
            final List<LockPatternView.Cell> pattern,
            final int userId,
            final OnCheckCallback callback) {
        AsyncTask<Void, Void, Boolean> task = new AsyncTask<Void, Void, Boolean>() {
            private int mThrottleTimeout;
            private List<LockPatternView.Cell> patternCopy;

            @Override
            protected void onPreExecute() {
                // Make a copy of the pattern to prevent race conditions.
                // No need to clone the individual cells because they are immutable.
                patternCopy = new ArrayList(pattern);
            }

            @Override
            protected Boolean doInBackground(Void... args) {
                try {
                    return utils.checkPattern(patternCopy, userId, callback::onEarlyMatched);
                } catch (RequestThrottledException ex) {
                    mThrottleTimeout = ex.getTimeoutMs();
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean result) {
                callback.onChecked(result, mThrottleTimeout);
            }

            @Override
            protected void onCancelled() {
                callback.onCancelled();
            }
        };
        task.execute();
        return task;
    }

    /**
     * Verify a password asynchronously.
     *
     * @param utils The LockPatternUtils instance to use.
     * @param password The password to check.
     * @param challenge The challenge to verify against the pattern.
     * @param userId The user to check against the pattern.
     * @param callback The callback to be invoked with the verification result.
     *
     * @deprecated Pass the password as a byte array.
     */
    @Deprecated
    public static AsyncTask<?, ?, ?> verifyPassword(final LockPatternUtils utils,
            final String password,
            final long challenge,
            final int userId,
            final OnVerifyCallback callback) {
        byte[] passwordBytes = password != null ? password.getBytes() : null;
        return verifyPassword(utils, passwordBytes, challenge, userId, callback);
    }

    /**
     * Verify a password asynchronously.
     *
     * @param utils The LockPatternUtils instance to use.
     * @param password The password to check.
     * @param challenge The challenge to verify against the pattern.
     * @param userId The user to check against the pattern.
     * @param callback The callback to be invoked with the verification result.
     */
    public static AsyncTask<?, ?, ?> verifyPassword(final LockPatternUtils utils,
            final byte[] password,
            final long challenge,
            final int userId,
            final OnVerifyCallback callback) {
        AsyncTask<Void, Void, byte[]> task = new AsyncTask<Void, Void, byte[]>() {
            private int mThrottleTimeout;

            @Override
            protected byte[] doInBackground(Void... args) {
                try {
                    return utils.verifyPassword(password, challenge, userId);
                } catch (RequestThrottledException ex) {
                    mThrottleTimeout = ex.getTimeoutMs();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(byte[] result) {
                callback.onVerified(result, mThrottleTimeout);
            }
        };
        task.execute();
        return task;
    }

    /**
     * Verify a password asynchronously.
     *
     * @param utils The LockPatternUtils instance to use.
     * @param password The password to check.
     * @param challenge The challenge to verify against the pattern.
     * @param userId The user to check against the pattern.
     * @param callback The callback to be invoked with the verification result.
     */
    public static AsyncTask<?, ?, ?> verifyTiedProfileChallenge(final LockPatternUtils utils,
            final byte[] password,
            final boolean isPattern,
            final long challenge,
            final int userId,
            final OnVerifyCallback callback) {
        AsyncTask<Void, Void, byte[]> task = new AsyncTask<Void, Void, byte[]>() {
            private int mThrottleTimeout;

            @Override
            protected byte[] doInBackground(Void... args) {
                try {
                    return utils.verifyTiedProfileChallenge(password, isPattern, challenge, userId);
                } catch (RequestThrottledException ex) {
                    mThrottleTimeout = ex.getTimeoutMs();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(byte[] result) {
                callback.onVerified(result, mThrottleTimeout);
            }
        };
        task.execute();
        return task;
    }

    /**
     * Checks a password asynchronously.
     *
     * @param utils The LockPatternUtils instance to use.
     * @param password The password to check.
     * @param userId The user to check against the pattern.
     * @param callback The callback to be invoked with the check result.
     * @deprecated Pass passwords as byte[]
     */
    @Deprecated
    public static AsyncTask<?, ?, ?> checkPassword(final LockPatternUtils utils,
            final String password,
            final int userId,
            final OnCheckCallback callback) {
        byte[] passwordBytes = password != null ? password.getBytes() : null;
        return checkPassword(utils, passwordBytes, userId, callback);
    }

    /**
     * Checks a password asynchronously.
     *
     * @param utils The LockPatternUtils instance to use.
     * @param passwordBytes The password to check.
     * @param userId The user to check against the pattern.
     * @param callback The callback to be invoked with the check result.
     */
    public static AsyncTask<?, ?, ?> checkPassword(final LockPatternUtils utils,
            final byte[] passwordBytes,
            final int userId,
            final OnCheckCallback callback) {
        AsyncTask<Void, Void, Boolean> task = new AsyncTask<Void, Void, Boolean>() {
            private int mThrottleTimeout;

            @Override
            protected Boolean doInBackground(Void... args) {
                try {
                    return utils.checkPassword(passwordBytes, userId, callback::onEarlyMatched);
                } catch (RequestThrottledException ex) {
                    mThrottleTimeout = ex.getTimeoutMs();
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean result) {
                callback.onChecked(result, mThrottleTimeout);
            }

            @Override
            protected void onCancelled() {
                callback.onCancelled();
            }
        };
        task.execute();
        return task;
    }
}
