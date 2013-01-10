package com.android.internal.policy.impl.keyguard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.view.WindowManagerPolicy.OnKeyguardExitResult;

import com.android.internal.policy.IKeyguardResult;
import com.android.internal.policy.IKeyguardService;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.policy.impl.KeyguardServiceWrapper;
import com.android.internal.policy.impl.keyguard.KeyguardServiceDelegate.ShowListener;

/**
 * A local class that keeps a cache of keyguard state that can be restored in the event
 * keyguard crashes. It currently also allows runtime-selectable
 * local or remote instances of keyguard.
 */
public class KeyguardServiceDelegate {
    private static final String KEYGUARD_PACKAGE = "com.android.keyguard";
    private static final String KEYGUARD_CLASS = "com.android.keyguard.KeyguardService";
    private static final String TAG = "KeyguardServiceDelegate";
    private final static boolean DEBUG = true;
    private ServiceConnection mKeyguardConnection;
    protected KeyguardServiceWrapper mKeyguardService;
    private KeyguardState mKeyguardState = new KeyguardState();

    /* package */ class KeyguardState {
        boolean showing;
        boolean showingAndNotHidden;
        boolean inputRestricted;
        boolean hidden;
        boolean secure;
        boolean dreaming;
        boolean systemIsReady;
        public boolean enabled;
        public boolean dismissable;
        public int offReason;
        public int currentUser;
        public boolean screenIsOn;
        public boolean restoreStateWhenConnected;
        public ShowListener showListener;
    };

    public interface ShowListener {
        public void onShown(IBinder windowToken);
    }

    private class KeyguardResult extends IKeyguardResult.Stub {
        private ShowListener mShowListener;
        private OnKeyguardExitResult mOnKeyguardExitResult;

        KeyguardResult(ShowListener showListener, OnKeyguardExitResult onKeyguardExitResult) {
            mShowListener = showListener;
            mOnKeyguardExitResult = onKeyguardExitResult;
        }

        @Override
        public IBinder asBinder() {
            if (DEBUG) Log.v(TAG, "asBinder() called for KeyguardResult, "
                    + "mShowListener = " + mShowListener
                    + ", mOnKeyguardExitResult = " + mOnKeyguardExitResult);
            return super.asBinder();
        }

        @Override
        public void onShown(IBinder windowToken) throws RemoteException {
            if (DEBUG) Log.v(TAG, "**** SHOWN CALLED ****");
            if (mShowListener != null) {
                mShowListener.onShown(windowToken);
            }
        }

        @Override
        public void onKeyguardExitResult(boolean success) throws RemoteException {
            if (DEBUG) Log.v(TAG, "**** onKeyguardExitResult(" + success +") CALLED ****");
            if (mOnKeyguardExitResult != null) {
                mOnKeyguardExitResult.onKeyguardExitResult(success);
            }
        }
    };

    public KeyguardServiceDelegate(Context context, LockPatternUtils lockPatternUtils) {
        mKeyguardConnection = createServiceConnection();
        Intent intent = new Intent();
        intent.setClassName(KEYGUARD_PACKAGE, KEYGUARD_CLASS);
        if (!context.bindServiceAsUser(intent, mKeyguardConnection,
                Context.BIND_AUTO_CREATE, UserHandle.OWNER)) {
            if (DEBUG) Log.v(TAG, "*** Keyguard: can't bind to " + KEYGUARD_CLASS);
        } else {
            if (DEBUG) Log.v(TAG, "*** Keyguard started");
        }
    }

    private ServiceConnection createServiceConnection() {
        return new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (DEBUG) Log.v(TAG, "*** Keyguard connected (yay!)");
                mKeyguardService = new KeyguardServiceWrapper(
                        IKeyguardService.Stub.asInterface(service));
                if (mKeyguardState.systemIsReady) {
                    mKeyguardService.onSystemReady();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (DEBUG) Log.v(TAG, "*** Keyguard disconnected (boo!)");
                mKeyguardService = null;
            }

        };
    }

    public boolean isShowing() {
        if (mKeyguardService != null) {
            mKeyguardState.showing = mKeyguardService.isShowing();
        }
        return mKeyguardState.showing;
    }

    public boolean isShowingAndNotHidden() {
        if (mKeyguardService != null) {
            mKeyguardState.showingAndNotHidden = mKeyguardService.isShowingAndNotHidden();
        }
        return mKeyguardState.showingAndNotHidden;
    }

    public boolean isInputRestricted() {
        if (mKeyguardService != null) {
            mKeyguardState.inputRestricted = mKeyguardService.isInputRestricted();
        }
        return mKeyguardState.inputRestricted;
    }

    public void verifyUnlock(final OnKeyguardExitResult onKeyguardExitResult) {
        if (mKeyguardService != null) {
            mKeyguardService.verifyUnlock(new KeyguardResult(null, onKeyguardExitResult));
        }
    }

    public void keyguardDone(boolean authenticated, boolean wakeup) {
        if (mKeyguardService != null) {
            mKeyguardService.keyguardDone(authenticated, wakeup);
        }
    }

    public void setHidden(boolean isHidden) {
        if (mKeyguardService != null) {
            mKeyguardService.setHidden(isHidden);
        }
        mKeyguardState.hidden = isHidden;
    }

    public void dismiss() {
        if (mKeyguardService != null) {
            mKeyguardService.dismiss();
        }
    }

    public boolean isSecure() {
        if (mKeyguardService != null) {
            mKeyguardState.secure = mKeyguardService.isSecure();
        }
        return mKeyguardState.secure;
    }

    public void onWakeKeyWhenKeyguardShowingTq(int keycodePower) {
        if (mKeyguardService != null) {
            mKeyguardService.onWakeKeyWhenKeyguardShowingTq(keycodePower);
        }
    }

    public void onWakeMotionWhenKeyguardShowingTq() {
        if (mKeyguardService != null) {
            mKeyguardService.onWakeMotionWhenKeyguardShowingTq();
        }
    }

    public void onDreamingStarted() {
        if (mKeyguardService != null) {
            mKeyguardService.onDreamingStarted();
        }
        mKeyguardState.dreaming = true;
    }

    public void onDreamingStopped() {
        if (mKeyguardService != null) {
            mKeyguardService.onDreamingStopped();
        }
        mKeyguardState.dreaming = false;
    }

    public void onScreenTurnedOn(final ShowListener showListener) {
        if (mKeyguardService != null) {
            if (DEBUG) Log.v(TAG, "onScreenTurnedOn(showListener = " + showListener + ")");
            mKeyguardService.onScreenTurnedOn(new KeyguardResult(showListener, null));
        } else {
            // try again when we establish a connection
            if (DEBUG) Log.w(TAG, "onScreenTurnedOn(): no keyguard service!");
            mKeyguardState.showListener = showListener;
            mKeyguardState.restoreStateWhenConnected = true;
        }
        mKeyguardState.screenIsOn = true;
    }

    public void onScreenTurnedOff(int why) {
        if (mKeyguardService != null) {
            mKeyguardService.onScreenTurnedOff(why);
        }
        mKeyguardState.offReason = why;
        mKeyguardState.screenIsOn = false;
    }

    public void setKeyguardEnabled(boolean enabled) {
        if (mKeyguardService != null) {
            mKeyguardService.setKeyguardEnabled(enabled);
        }
        mKeyguardState.enabled = enabled;
    }

    public boolean isDismissable() {
        if (mKeyguardService != null) {
            mKeyguardState.dismissable = mKeyguardService.isDismissable();
        }
        return mKeyguardState.dismissable;
    }

    public void onSystemReady() {
        if (mKeyguardService != null) {
            mKeyguardService.onSystemReady();
        } else {
            if (DEBUG) Log.v(TAG, "onSystemReady() called before keyguard service was ready");
            mKeyguardState.systemIsReady = true;
        }
    }

    public void doKeyguardTimeout(Bundle options) {
        if (mKeyguardService != null) {
            mKeyguardService.doKeyguardTimeout(options);
        }
    }

    public void showAssistant() {
        if (mKeyguardService != null) {
            mKeyguardService.showAssistant();
        }
    }

    public void setCurrentUser(int newUserId) {
        if (mKeyguardService != null) {
            mKeyguardService.setCurrentUser(newUserId);
        }
        mKeyguardState.currentUser = newUserId;
    }

    public void userActivity() {
        if (mKeyguardService != null) {
            mKeyguardService.userActivity();
        }
    }

}
