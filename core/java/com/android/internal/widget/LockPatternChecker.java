package com.android.internal.widget;

import android.os.AsyncTask;

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
         * Invoked when a security check is finished.
         *
         * @param matched Whether the PIN/Password/Pattern matches the stored one.
         */
        void onChecked(boolean matched);
    }

    /**
     * Interface for a callback to be invoked after security verification.
     */
    public interface OnVerifyCallback {
        /**
         * Invoked when a security verification is finished.
         *
         * @param attestation The attestation that the challenge was verified, or null.
         */
        void onVerified(byte[] attestation);
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
            @Override
            protected byte[] doInBackground(Void... args) {
                return utils.verifyPattern(pattern, challenge, userId);
            }

            @Override
            protected void onPostExecute(byte[] result) {
                callback.onVerified(result);
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
            @Override
            protected Boolean doInBackground(Void... args) {
                return utils.checkPattern(pattern, userId);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                callback.onChecked(result);
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
    public static AsyncTask<?, ?, ?> verifyPassword(final LockPatternUtils utils,
            final String password,
            final long challenge,
            final int userId,
            final OnVerifyCallback callback) {
        AsyncTask<Void, Void, byte[]> task = new AsyncTask<Void, Void, byte[]>() {
            @Override
            protected byte[] doInBackground(Void... args) {
                return utils.verifyPassword(password, challenge, userId);
            }

            @Override
            protected void onPostExecute(byte[] result) {
                callback.onVerified(result);
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
     */
    public static AsyncTask<?, ?, ?> checkPassword(final LockPatternUtils utils,
            final String password,
            final int userId,
            final OnCheckCallback callback) {
        AsyncTask<Void, Void, Boolean> task = new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... args) {
                return utils.checkPassword(password, userId);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                callback.onChecked(result);
            }
        };
        task.execute();
        return task;
    }
}
