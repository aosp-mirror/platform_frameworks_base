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

package android.net.lowpan;

import android.os.DeadObjectException;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.AndroidException;

/**
 * <code>LowpanException</code> is thrown if an action to a LoWPAN interface could not be performed
 * or a LoWPAN interface property could not be fetched or changed.
 *
 * @see LowpanInterface
 * @hide
 */
//@SystemApi
public class LowpanException extends AndroidException {
    // Make the eclipse warning about serializable exceptions go away
    private static final long serialVersionUID = 0x31863cbe562b0e11l; // randomly generated

    public static final int LOWPAN_ERROR = 1;
    public static final int LOWPAN_CREDENTIAL_NEEDED = 2;
    public static final int LOWPAN_DEAD = 3;
    public static final int LOWPAN_DISABLED = 4;
    public static final int LOWPAN_WRONG_STATE = 5;
    public static final int LOWPAN_BUSY = 7;
    public static final int LOWPAN_NCP_PROBLEM = 8;
    public static final int LOWPAN_ALREADY = 9;
    public static final int LOWPAN_CANCELED = 10;
    public static final int LOWPAN_FEATURE_NOT_SUPPORTED = 12;
    public static final int LOWPAN_PROPERTY_NOT_FOUND = 13;
    public static final int LOWPAN_JOIN_FAILED_UNKNOWN = 14;
    public static final int LOWPAN_JOIN_FAILED_AT_SCAN = 15;
    public static final int LOWPAN_JOIN_FAILED_AT_AUTH = 16;
    public static final int LOWPAN_FORM_FAILED_AT_SCAN = 17;

    /**
     * Convert ServiceSpecificExceptions and Binder RemoteExceptions from LoWPAN binder interfaces
     * into the correct public exceptions.
     *
     * @hide
     */
    public static void throwAsPublicException(Throwable t) throws LowpanException {
        if (t instanceof ServiceSpecificException) {
            ServiceSpecificException e = (ServiceSpecificException) t;
            int reason;
            switch (e.errorCode) {
                case ILowpanInterface.ERROR_INVALID_ARGUMENT:
                case ILowpanInterface.ERROR_INVALID_TYPE:
                case ILowpanInterface.ERROR_INVALID_VALUE:
                    throw new IllegalArgumentException(e.getMessage(), e);

                case ILowpanInterface.ERROR_PERMISSION_DENIED:
                    throw new SecurityException(e.getMessage(), e);

                case ILowpanInterface.ERROR_DISABLED:
                    reason = LowpanException.LOWPAN_DISABLED;
                    break;

                case ILowpanInterface.ERROR_WRONG_STATE:
                    reason = LowpanException.LOWPAN_WRONG_STATE;
                    break;

                case ILowpanInterface.ERROR_BUSY:
                    reason = LowpanException.LOWPAN_BUSY;
                    break;

                case ILowpanInterface.ERROR_ALREADY:
                    reason = LowpanException.LOWPAN_ALREADY;
                    break;

                case ILowpanInterface.ERROR_CANCELED:
                    reason = LowpanException.LOWPAN_CANCELED;
                    break;

                case ILowpanInterface.ERROR_CREDENTIAL_NEEDED:
                    reason = LowpanException.LOWPAN_CREDENTIAL_NEEDED;
                    break;

                case ILowpanInterface.ERROR_FEATURE_NOT_SUPPORTED:
                    reason = LowpanException.LOWPAN_FEATURE_NOT_SUPPORTED;
                    break;

                case ILowpanInterface.ERROR_PROPERTY_NOT_FOUND:
                    reason = LowpanException.LOWPAN_PROPERTY_NOT_FOUND;
                    break;

                case ILowpanInterface.ERROR_JOIN_FAILED_UNKNOWN:
                    reason = LowpanException.LOWPAN_JOIN_FAILED_UNKNOWN;
                    break;

                case ILowpanInterface.ERROR_JOIN_FAILED_AT_SCAN:
                    reason = LowpanException.LOWPAN_JOIN_FAILED_AT_SCAN;
                    break;

                case ILowpanInterface.ERROR_JOIN_FAILED_AT_AUTH:
                    reason = LowpanException.LOWPAN_JOIN_FAILED_AT_AUTH;
                    break;

                case ILowpanInterface.ERROR_FORM_FAILED_AT_SCAN:
                    reason = LowpanException.LOWPAN_FORM_FAILED_AT_SCAN;
                    break;

                case ILowpanInterface.ERROR_TIMEOUT:
                case ILowpanInterface.ERROR_NCP_PROBLEM:
                    reason = LowpanException.LOWPAN_NCP_PROBLEM;
                    break;
                case ILowpanInterface.ERROR_UNSPECIFIED:
                default:
                    reason = LOWPAN_ERROR;
                    break;
            }
            throw new LowpanException(reason, e.getMessage(), e);
        } else if (t instanceof DeadObjectException) {
            throw new LowpanException(LOWPAN_DEAD, t);
        } else if (t instanceof RemoteException) {
            throw new UnsupportedOperationException(
                    "An unknown RemoteException was thrown" + " which should never happen.", t);
        } else if (t instanceof RuntimeException) {
            RuntimeException e = (RuntimeException) t;
            throw e;
        }
    }

    private final int mReason;

    public final int getReason() {
        return mReason;
    }

    public LowpanException(int problem) {
        super(getDefaultMessage(problem));
        mReason = problem;
    }

    public LowpanException(String message) {
        super(getCombinedMessage(LOWPAN_ERROR, message));
        mReason = LOWPAN_ERROR;
    }

    public LowpanException(int problem, String message, Throwable cause) {
        super(getCombinedMessage(problem, message), cause);
        mReason = problem;
    }

    public LowpanException(int problem, Throwable cause) {
        super(getDefaultMessage(problem), cause);
        mReason = problem;
    }

    /** @hide */
    public static String getDefaultMessage(int problem) {
        String problemString;

        // TODO: Does this need localization?

        switch (problem) {
            case LOWPAN_DEAD:
                problemString = "LoWPAN interface is no longer alive";
                break;
            case LOWPAN_DISABLED:
                problemString = "LoWPAN interface is disabled";
                break;
            case LOWPAN_WRONG_STATE:
                problemString = "LoWPAN interface in wrong state to perfom requested action";
                break;
            case LOWPAN_BUSY:
                problemString =
                        "LoWPAN interface was unable to perform the requestion action because it was busy";
                break;
            case LOWPAN_NCP_PROBLEM:
                problemString =
                        "The Network Co-Processor associated with this interface has experienced a problem";
                break;
            case LOWPAN_ALREADY:
                problemString = "The LoWPAN interface is already in the given state";
                break;
            case LOWPAN_CANCELED:
                problemString = "This operation was canceled";
                break;
            case LOWPAN_CREDENTIAL_NEEDED:
                problemString = "Additional credentials are required to complete this operation";
                break;
            case LOWPAN_FEATURE_NOT_SUPPORTED:
                problemString =
                        "A dependent feature required to perform the given action is not currently supported";
                break;
            case LOWPAN_PROPERTY_NOT_FOUND:
                problemString = "The given property was not found";
                break;
            case LOWPAN_JOIN_FAILED_UNKNOWN:
                problemString = "The join operation failed for an unspecified reason";
                break;
            case LOWPAN_JOIN_FAILED_AT_SCAN:
                problemString =
                        "The join operation failed because it could not communicate with any peers";
                break;
            case LOWPAN_JOIN_FAILED_AT_AUTH:
                problemString =
                        "The join operation failed because the credentials were not accepted by any peers";
                break;
            case LOWPAN_FORM_FAILED_AT_SCAN:
                problemString = "Network form failed";
                break;
            case LOWPAN_ERROR:
            default:
                problemString = "The requested LoWPAN operation failed";
                break;
        }

        return problemString;
    }

    private static String getCombinedMessage(int problem, String message) {
        String problemString = getProblemString(problem);
        return String.format("%s (%d): %s", problemString, problem, message);
    }

    private static String getProblemString(int problem) {
        String problemString;

        switch (problem) {
            case LOWPAN_ERROR:
                problemString = "LOWPAN_ERROR";
                break;
            case LOWPAN_DEAD:
                problemString = "LOWPAN_DEAD";
                break;
            case LOWPAN_DISABLED:
                problemString = "LOWPAN_DISABLED";
                break;
            case LOWPAN_WRONG_STATE:
                problemString = "LOWPAN_WRONG_STATE";
                break;
            case LOWPAN_BUSY:
                problemString = "LOWPAN_BUSY";
                break;
            case LOWPAN_NCP_PROBLEM:
                problemString = "LOWPAN_NCP_PROBLEM";
                break;
            case LOWPAN_ALREADY:
                problemString = "LOWPAN_ALREADY";
                break;
            case LOWPAN_CANCELED:
                problemString = "LOWPAN_CANCELED";
                break;
            case LOWPAN_CREDENTIAL_NEEDED:
                problemString = "LOWPAN_CREDENTIAL_NEEDED";
                break;
            case LOWPAN_FEATURE_NOT_SUPPORTED:
                problemString = "LOWPAN_FEATURE_NOT_SUPPORTED";
                break;
            case LOWPAN_PROPERTY_NOT_FOUND:
                problemString = "LOWPAN_PROPERTY_NOT_FOUND";
                break;
            case LOWPAN_JOIN_FAILED_UNKNOWN:
                problemString = "LOWPAN_JOIN_FAILED_UNKNOWN";
                break;
            case LOWPAN_JOIN_FAILED_AT_SCAN:
                problemString = "LOWPAN_JOIN_FAILED_AT_SCAN";
                break;
            case LOWPAN_JOIN_FAILED_AT_AUTH:
                problemString = "LOWPAN_JOIN_FAILED_AT_AUTH";
                break;
            case LOWPAN_FORM_FAILED_AT_SCAN:
                problemString = "LOWPAN_FORM_FAILED_AT_SCAN";
                break;
            default:
                problemString = "LOWPAN_ERROR_CODE_" + problem;
                break;
        }

        return problemString;
    }
}
