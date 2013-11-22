package android.hardware.camera2.utils;

import android.hardware.camera2.CameraAccessException;

/**
 * @hide
 */
public class CameraRuntimeException extends RuntimeException {

    private final int mReason;
    private String mMessage;
    private Throwable mCause;

    public final int getReason() {
        return mReason;
    }

    public CameraRuntimeException(int problem) {
        super();
        mReason = problem;
    }

    public CameraRuntimeException(int problem, String message) {
        super(message);
        mReason = problem;
        mMessage = message;
    }

    public CameraRuntimeException(int problem, String message, Throwable cause) {
        super(message, cause);
        mReason = problem;
        mMessage = message;
        mCause = cause;
    }

    public CameraRuntimeException(int problem, Throwable cause) {
        super(cause);
        mReason = problem;
        mCause = cause;
    }

    /**
     * Recreate this exception as the CameraAccessException equivalent.
     * @return CameraAccessException
     */
    public CameraAccessException asChecked() {
        CameraAccessException e;

        if (mMessage != null && mCause != null) {
            e = new CameraAccessException(mReason, mMessage, mCause);
        } else if (mMessage != null) {
            e = new CameraAccessException(mReason, mMessage);
        } else if (mCause != null) {
            e = new CameraAccessException(mReason, mCause);
        } else {
            e = new CameraAccessException(mReason);
        }
        // throw and catch, so java has a chance to fill out the stack trace
        e.setStackTrace(this.getStackTrace());

        return e;
    }
}
