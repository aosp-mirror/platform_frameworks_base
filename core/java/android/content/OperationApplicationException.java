/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

/**
 * Thrown when an application of a {@link ContentProviderOperation} fails due the specified
 * constraints.
 */
public class OperationApplicationException extends Exception {
    private final int mNumSuccessfulYieldPoints;

    public OperationApplicationException() {
        super();
        mNumSuccessfulYieldPoints = 0;
    }
    public OperationApplicationException(String message) {
        super(message);
        mNumSuccessfulYieldPoints = 0;
    }
    public OperationApplicationException(String message, Throwable cause) {
        super(message, cause);
        mNumSuccessfulYieldPoints = 0;
    }
    public OperationApplicationException(Throwable cause) {
        super(cause);
        mNumSuccessfulYieldPoints = 0;
    }
    public OperationApplicationException(int numSuccessfulYieldPoints) {
        super();
        mNumSuccessfulYieldPoints = numSuccessfulYieldPoints;
    }
    public OperationApplicationException(String message, int numSuccessfulYieldPoints) {
        super(message);
        mNumSuccessfulYieldPoints = numSuccessfulYieldPoints;
    }

    public int getNumSuccessfulYieldPoints() {
        return mNumSuccessfulYieldPoints;
    }
}
