package com.android.internal.policy.impl.keyguard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerPolicy.OnKeyguardExitResult;

import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.policy.IKeyguardService;
import com.android.internal.widget.LockPatternUtils;

/**
 * A local class that keeps a cache of keyguard state that can be restored in the event
 * keyguard crashes. It currently also allows runtime-selectable
 * local or remote instances of keyguard.
 */
public class KeyguardServiceDelegate {
    // TODO: propagate changes to these to {@link KeyguardTouchDelegate}
    public static final String KEYGUARD_PACKAGE = "com.android.systemui";
    public static final String KEYGUARD_CLASS = "com.android.systemui.keyguard.KeyguardService";

    private static final String TAG = "KeyguardServiceDelegate";
    private static final boolean DEBUG = true;
    protected KeyguardServiceWrapper mKeyguardService;
    private View mScrim; // shown if keyguard crashes
    private KeyguardState mKeyguardState = new KeyguardState();

    /* package */ static final class KeyguardState {
        KeyguardState() {
            // Assume keyguard is showing and secure until we know for sure. This is here in
            // the event something checks before the service is actually started.
            // KeyguardService itself should default to this state until the real state is known.
            showing = true;
            showingAndNotOccluded = true;
            secure = true;
        }
        boolean showing;
        boolean showingAndNotOccluded;
        boolean inputRestricted;
        boolean occluded;
        boolean secure;
        boolean dreaming;
        boolean systemIsReady;
        public boolean enabled;
        public boolean dismissable;
        public int offReason;
        public int currentUser;
        public boolean screenIsOn;
        public boolean bootCompleted;
    };

    public interface ShowListener {
        public void onShown(IBinder windowToken);
    }

    // A delegate class to map a particular invocation with a ShowListener object.
    private final class KeyguardShowDelegate extends IKeyguardShowCallback.Stub {
        private ShowListener mShowListener;

        KeyguardShowDelegate(ShowListener showListener) {
            mShowListener = showListener;
        }

        @Override
        public void onShown(IBinder windowToken) throws RemoteException {
            if (DEBUG) Log.v(TAG, "**** SHOWN CALLED ****");
            if (mShowListener != null) {
                mShowListener.onShown(windowToken);
            }
            hideScrim();
        }
    };

    // A delegate class to map a particular invocation with an OnKeyguardExitResult object.
    private final class KeyguardExitDelegate extends IKeyguardExitCallback.Stub {
        private OnKeyguardExitResult mOnKeyguardExitResult;

        KeyguardExitDelegate(OnKeyguardExitResult onKeyguardExitResult) {
            mOnKeyguardExitResult = onKeyguardExitResult;
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
        mScrim = createScrim(context);
    }

    public void bindService(Context context) {
        Intent intent = new Intent();
        intent.setClassName(KEYGUARD_PACKAGE, KEYGUARD_CLASS);
        if (!context.bindServiceAsUser(intent, mKeyguardConnection,
                Context.BIND_AUTO_CREATE, UserHandle.OWNER)) {
            if (DEBUG) Log.v(TAG, "*** Keyguard: can't bind to " + KEYGUARD_CLASS);
            mKeyguardState.showing = false;
            mKeyguardState.showingAndNotOccluded = false;
            mKeyguardState.secure = false;
        } else {
            if (DEBUG) Log.v(TAG, "*** Keyguard started");
        }
    }

    private final ServiceConnection mKeyguardConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Log.v(TAG, "*** Keyguard connected (yay!)");
            mKeyguardService = new KeyguardServiceWrapper(
                    IKeyguardService.Stub.asInterface(service));
            if (mKeyguardState.systemIsReady) {
                // If the system is ready, it means keyguard crashed and restarted.
                mKeyguardService.onSystemReady();
                // This is used to hide the scrim once keyguard displays.
                mKeyguardService.onScreenTurnedOn(new KeyguardShowDelegate(null));
            }
            if (mKeyguardState.bootCompleted) {
                mKeyguardService.onBootCompleted();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Log.v(TAG, "*** Keyguard disconnected (boo!)");
            mKeyguardService = null;
        }

    };

    public boolean isShowing() {
        if (mKeyguardService != null) {
            mKeyguardState.showing = mKeyguardService.isShowing();
        }
        return mKeyguardState.showing;
    }

    public boolean isShowingAndNotOccluded() {
        if (mKeyguardService != null) {
            mKeyguardState.showingAndNotOccluded = mKeyguardService.isShowingAndNotOccluded();
        }
        return mKeyguardState.showingAndNotOccluded;
    }

    public boolean isInputRestricted() {
        if (mKeyguardService != null) {
            mKeyguardState.inputRestricted = mKeyguardService.isInputRestricted();
        }
        return mKeyguardState.inputRestricted;
    }

    public void verifyUnlock(final OnKeyguardExitResult onKeyguardExitResult) {
        if (mKeyguardService != null) {
            mKeyguardService.verifyUnlock(new KeyguardExitDelegate(onKeyguardExitResult));
        }
    }

    public void keyguardDone(boolean authenticated, boolean wakeup) {
        if (mKeyguardService != null) {
            mKeyguardService.keyguardDone(authenticated, wakeup);
        }
    }

    public int setOccluded(boolean isOccluded) {
        int result = 0;
        if (mKeyguardService != null) {
            result = mKeyguardService.setOccluded(isOccluded);
        }
        mKeyguardState.occluded = isOccluded;
        return result;
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
            mKeyguardService.onScreenTurnedOn(new KeyguardShowDelegate(showListener));
        } else {
            // try again when we establish a connection
            Slog.w(TAG, "onScreenTurnedOn(): no keyguard service!");
            // This shouldn't happen, but if it does, invoke the listener immediately
            // to avoid a dark screen...
            showListener.onShown(null);
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

    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
        if (mKeyguardService != null) {
            mKeyguardService.startKeyguardExitAnimation(startTime, fadeoutDuration);
        }
    }

    private static final View createScrim(Context context) {
        View view = new View(context);

        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                | WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
                ;

        final int stretch = ViewGroup.LayoutParams.MATCH_PARENT;
        final int type = WindowManager.LayoutParams.TYPE_KEYGUARD_SCRIM;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                stretch, stretch, type, flags, PixelFormat.TRANSLUCENT);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_FAKE_HARDWARE_ACCELERATED;
        lp.setTitle("KeyguardScrim");
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.addView(view, lp);
        view.setVisibility(View.GONE);
        // Disable pretty much everything in statusbar until keyguard comes back and we know
        // the state of the world.
        view.setSystemUiVisibility(View.STATUS_BAR_DISABLE_HOME
                | View.STATUS_BAR_DISABLE_BACK
                | View.STATUS_BAR_DISABLE_RECENT
                | View.STATUS_BAR_DISABLE_EXPAND
                | View.STATUS_BAR_DISABLE_SEARCH);
        return view;
    }

    public void showScrim() {
        mScrim.post(new Runnable() {
            @Override
            public void run() {
                mScrim.setVisibility(View.VISIBLE);
            }
        });
    }

    public void hideScrim() {
        mScrim.post(new Runnable() {
            @Override
            public void run() {
                mScrim.setVisibility(View.GONE);
            }
        });
    }

    public void onBootCompleted() {
        if (mKeyguardService != null) {
            mKeyguardService.onBootCompleted();
        }
        mKeyguardState.bootCompleted = true;
    }

    public void onActivityDrawn() {
        if (mKeyguardService != null) {
            mKeyguardService.onActivityDrawn();
        }
    }
}
