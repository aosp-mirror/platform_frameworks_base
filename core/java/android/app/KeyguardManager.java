/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.ServiceManager;
import android.view.IWindowManager;
import android.view.IOnKeyguardExitResult;

/**
 * Class that can be used to lock and unlock the keyboard. Get an instance of this 
 * class by calling {@link android.content.Context#getSystemService(java.lang.String)}
 * with argument {@link android.content.Context#KEYGUARD_SERVICE}. The
 * actual class to control the keyboard locking is
 * {@link android.app.KeyguardManager.KeyguardLock}.
 */
public class KeyguardManager {
    private IWindowManager mWM;

    /**
     * @deprecated Use {@link android.view.WindowManager.LayoutParams#FLAG_DISMISS_KEYGUARD}
     * and/or {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED}
     * instead; this allows you to seamlessly hide the keyguard as your application
     * moves in and out of the foreground and does not require that any special
     * permissions be requested.
     *
     * Handle returned by {@link KeyguardManager#newKeyguardLock} that allows
     * you to disable / reenable the keyguard.
     */
    public class KeyguardLock {
        private IBinder mToken = new Binder();
        private String mTag;

        KeyguardLock(String tag) {
            mTag = tag;
        }

        /**
         * Disable the keyguard from showing.  If the keyguard is currently
         * showing, hide it.  The keyguard will be prevented from showing again
         * until {@link #reenableKeyguard()} is called.
         *
         * A good place to call this is from {@link android.app.Activity#onResume()}
         *
         * Note: This call has no effect while any {@link android.app.admin.DevicePolicyManager} 
         * is enabled that requires a password.
         *
         * <p>This method requires the caller to hold the permission
         * {@link android.Manifest.permission#DISABLE_KEYGUARD}.
         *
         * @see #reenableKeyguard()
         */
        public void disableKeyguard() {
            try {
                mWM.disableKeyguard(mToken, mTag);
            } catch (RemoteException ex) {
            }
        }

        /**
         * Reenable the keyguard.  The keyguard will reappear if the previous
         * call to {@link #disableKeyguard()} caused it to be hidden.
         *
         * A good place to call this is from {@link android.app.Activity#onPause()}
         *
         * Note: This call has no effect while any {@link android.app.admin.DevicePolicyManager}
         * is enabled that requires a password.
         *
         * <p>This method requires the caller to hold the permission
         * {@link android.Manifest.permission#DISABLE_KEYGUARD}.
         *
         * @see #disableKeyguard()
         */
        public void reenableKeyguard() {
            try {
                mWM.reenableKeyguard(mToken);
            } catch (RemoteException ex) {
            }
        }
    }

    /**
     * Callback passed to {@link KeyguardManager#exitKeyguardSecurely} to notify
     * caller of result.
     */
    public interface OnKeyguardExitResult {

        /**
         * @param success True if the user was able to authenticate, false if
         *   not.
         */
        void onKeyguardExitResult(boolean success);
    }


    KeyguardManager() {
        mWM = IWindowManager.Stub.asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));
    }

    /**
     * @deprecated Use {@link android.view.WindowManager.LayoutParams#FLAG_DISMISS_KEYGUARD}
     * and/or {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED}
     * instead; this allows you to seamlessly hide the keyguard as your application
     * moves in and out of the foreground and does not require that any special
     * permissions be requested.
     *
     * Enables you to lock or unlock the keyboard. Get an instance of this class by
     * calling {@link android.content.Context#getSystemService(java.lang.String) Context.getSystemService()}. 
     * This class is wrapped by {@link android.app.KeyguardManager KeyguardManager}.
     * @param tag A tag that informally identifies who you are (for debugging who
     *   is disabling he keyguard).
     *
     * @return A {@link KeyguardLock} handle to use to disable and reenable the
     *   keyguard.
     */
    @Deprecated
    public KeyguardLock newKeyguardLock(String tag) {
        return new KeyguardLock(tag);
    }

    /**
     * Return whether the keyguard is currently locked.
     *
     * @return true if keyguard is locked.
     */
    public boolean isKeyguardLocked() {
        try {
            return mWM.isKeyguardLocked();
        } catch (RemoteException ex) {
            return false;
        }
    }

    /**
     * Return whether the keyguard requires a password to unlock.
     *
     * @return true if keyguard is secure.
     */
    public boolean isKeyguardSecure() {
        try {
            return mWM.isKeyguardSecure();
        } catch (RemoteException ex) {
            return false;
        }
    }

    /**
     * If keyguard screen is showing or in restricted key input mode (i.e. in
     * keyguard password emergency screen). When in such mode, certain keys,
     * such as the Home key and the right soft keys, don't work.
     *
     * @return true if in keyguard restricted input mode.
     *
     * @see android.view.WindowManagerPolicy#inKeyguardRestrictedKeyInputMode
     */
    public boolean inKeyguardRestrictedInputMode() {
        try {
            return mWM.inKeyguardRestrictedInputMode();
        } catch (RemoteException ex) {
            return false;
        }
    }

    /**
     * @deprecated Use {@link android.view.WindowManager.LayoutParams#FLAG_DISMISS_KEYGUARD}
     * and/or {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED}
     * instead; this allows you to seamlessly hide the keyguard as your application
     * moves in and out of the foreground and does not require that any special
     * permissions be requested.
     *
     * Exit the keyguard securely.  The use case for this api is that, after
     * disabling the keyguard, your app, which was granted permission to
     * disable the keyguard and show a limited amount of information deemed
     * safe without the user getting past the keyguard, needs to navigate to
     * something that is not safe to view without getting past the keyguard.
     *
     * This will, if the keyguard is secure, bring up the unlock screen of
     * the keyguard.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#DISABLE_KEYGUARD}.
     *
     * @param callback Let's you know whether the operation was succesful and
     *   it is safe to launch anything that would normally be considered safe
     *   once the user has gotten past the keyguard.
     */
    @Deprecated
    public void exitKeyguardSecurely(final OnKeyguardExitResult callback) {
        try {
            mWM.exitKeyguardSecurely(new IOnKeyguardExitResult.Stub() {
                public void onKeyguardExitResult(boolean success) throws RemoteException {
                    callback.onKeyguardExitResult(success);
                }
            });
        } catch (RemoteException e) {

        }
    }
}
