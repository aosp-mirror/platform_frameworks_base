/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.attention;

/**
 * Attention manager local system server interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class AttentionManagerInternal {
    /**
     * Returns {@code true} if attention service is supported on this device.
     */
    public abstract boolean isAttentionServiceSupported();

    /**
     * Checks whether user attention is at the screen and calls in the provided callback.
     *
     * @param timeoutMillis a budget for the attention check; if it takes longer - {@link
     *                      AttentionCallbackInternal#onFailure} would be called with the {@link
     *                      android.service.attention.AttentionService#ATTENTION_FAILURE_TIMED_OUT}
     *                      code
     * @param callback      a callback for when the attention check has completed
     * @return {@code true} if the attention check should succeed.
     */
    public abstract boolean checkAttention(long timeoutMillis, AttentionCallbackInternal callback);

    /**
     * Cancels the specified attention check in case it's no longer needed.
     *
     * @param callback a callback that was used in {@link #checkAttention}
     */
    public abstract void cancelAttentionCheck(AttentionCallbackInternal callback);

    /**
     * Disables the dependants.
     *
     * Example: called if the service does not have sufficient permissions to perform the task.
     */
    public abstract void disableSelf();

    /** Internal interface for attention callback. */
    public abstract static class AttentionCallbackInternal {
        /**
         * Provides the result of the attention check, if the check was successful.
         *
         * @param result      an int with the result of the check
         * @param timestamp   a {@code SystemClock.uptimeMillis()} timestamp associated with the
         *                    attention check
         */
        public abstract void onSuccess(int result, long timestamp);

        /**
         * Provides the explanation for why the attention check had failed.
         *
         * @param error       an int with the reason for failure
         */
        public abstract void onFailure(int error);
    }
}
