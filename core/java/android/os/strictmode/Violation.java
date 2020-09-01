/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.os.strictmode;

/** Root class for all StrictMode violations. */
public abstract class Violation extends Throwable {
    private int mHashCode;
    private boolean mHashCodeValid;

    Violation(String message) {
        super(message);
    }

    @Override
    public int hashCode() {
        synchronized (this) {
            if (mHashCodeValid) {
                return mHashCode;
            }
            final String message = getMessage();
            final Throwable cause = getCause();
            int hashCode = message != null ? message.hashCode() : getClass().hashCode();
            hashCode = hashCode * 37 + calcStackTraceHashCode(getStackTrace());
            hashCode = hashCode * 37 + (cause != null ? cause.toString().hashCode() : 0);
            mHashCodeValid = true;
            return mHashCode = hashCode;
        }
    }

    @Override
    public synchronized Throwable initCause(Throwable cause) {
        mHashCodeValid = false;
        return super.initCause(cause);
    }

    @Override
    public void setStackTrace(StackTraceElement[] stackTrace) {
        super.setStackTrace(stackTrace);
        synchronized (this) {
            mHashCodeValid = false;
        }
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        mHashCodeValid = false;
        return super.fillInStackTrace();
    }

    private static int calcStackTraceHashCode(final StackTraceElement[] stackTrace) {
        int hashCode = 17;
        if (stackTrace != null) {
            for (int i = 0; i < stackTrace.length; i++) {
                if (stackTrace[i] != null) {
                    hashCode = hashCode * 37 + stackTrace[i].hashCode();
                }
            }
        }
        return hashCode;
    }
}
