/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.UiThread;
import android.app.Activity;
import android.app.ActivityThread;
import android.content.Context;
import android.os.IBinder;
import android.util.Singleton;
import android.util.Slog;

import java.util.ArrayList;

/**
 * The interface that apps use to talk to the splash screen.
 * <p>
 * Each splash screen instance is bound to a particular {@link Activity}.
 * To obtain a {@link SplashScreen} for an Activity, use
 * <code>Activity.getSplashScreen()</code> to get the SplashScreen.</p>
 */
public interface SplashScreen {
    /**
     * <p>Specifies whether an {@link Activity} wants to handle the splash screen animation on its
     * own. Normally the splash screen will show on screen before the content of the activity has
     * been drawn, and disappear when the activity is showing on the screen. With this listener set,
     * the activity will receive {@link OnExitAnimationListener#onSplashScreenExit} callback if
     * splash screen is showed, then the activity can create its own exit animation based on the
     * SplashScreenView.</p>
     *
     * <p> Note that this method must be called before splash screen leave, so it only takes effect
     * during or before {@link Activity#onResume}.</p>
     *
     * @param listener the listener for receive the splash screen with
     *
     * @see OnExitAnimationListener#onSplashScreenExit(SplashScreenView)
     */
    @SuppressLint("ExecutorRegistration")
    void setOnExitAnimationListener(@NonNull SplashScreen.OnExitAnimationListener listener);

    /**
     * Clear exist listener
     * @see #setOnExitAnimationListener
     */
    void clearOnExitAnimationListener();

    /**
     * Listens for the splash screen exit event.
     */
    interface OnExitAnimationListener {
        /**
         * When receiving this callback, the {@link SplashScreenView} object will be drawing on top
         * of the activity. The {@link SplashScreenView} represents the splash screen view
         * object, developer can make an exit animation based on this view.</p>
         *
         * <p>This method is never invoked if your activity clear the listener by
         * {@link #clearOnExitAnimationListener}.
         *
         * @param view The view object which on top of this Activity.
         * @see #setOnExitAnimationListener
         * @see #clearOnExitAnimationListener
         */
        @UiThread
        void onSplashScreenExit(@NonNull SplashScreenView view);
    }

    /**
     * @hide
     */
    class SplashScreenImpl implements SplashScreen {
        private OnExitAnimationListener mExitAnimationListener;
        private final IBinder mActivityToken;
        private final SplashScreenManagerGlobal mGlobal;

        public SplashScreenImpl(Context context) {
            mActivityToken = context.getActivityToken();
            mGlobal = SplashScreenManagerGlobal.getInstance();
        }

        @Override
        public void setOnExitAnimationListener(
                @NonNull SplashScreen.OnExitAnimationListener listener) {
            if (mActivityToken == null) {
                // This is not an activity.
                return;
            }
            synchronized (mGlobal.mGlobalLock) {
                if (listener != null) {
                    mExitAnimationListener = listener;
                    mGlobal.addImpl(this);
                }
            }
        }

        @Override
        public void clearOnExitAnimationListener() {
            if (mActivityToken == null) {
                // This is not an activity.
                return;
            }
            synchronized (mGlobal.mGlobalLock) {
                mExitAnimationListener = null;
                mGlobal.removeImpl(this);
            }
        }
    }

    /**
     * This class is only used internally to manage the activities for this process.
     *
     * @hide
     */
    class SplashScreenManagerGlobal {
        private static final String TAG = SplashScreen.class.getSimpleName();
        private final Object mGlobalLock = new Object();
        private final ArrayList<SplashScreenImpl> mImpls = new ArrayList<>();

        private SplashScreenManagerGlobal() {
            ActivityThread.currentActivityThread().registerSplashScreenManager(this);
        }

        public static SplashScreenManagerGlobal getInstance() {
            return sInstance.get();
        }

        private static final Singleton<SplashScreenManagerGlobal> sInstance =
                new Singleton<SplashScreenManagerGlobal>() {
                    @Override
                    protected SplashScreenManagerGlobal create() {
                        return new SplashScreenManagerGlobal();
                    }
                };

        private void addImpl(SplashScreenImpl impl) {
            synchronized (mGlobalLock) {
                mImpls.add(impl);
            }
        }

        private void removeImpl(SplashScreenImpl impl) {
            synchronized (mGlobalLock) {
                mImpls.remove(impl);
            }
        }

        private SplashScreenImpl findImpl(IBinder token) {
            synchronized (mGlobalLock) {
                for (SplashScreenImpl impl : mImpls) {
                    if (impl.mActivityToken == token) {
                        return impl;
                    }
                }
            }
            return null;
        }

        public void tokenDestroyed(IBinder token) {
            synchronized (mGlobalLock) {
                final SplashScreenImpl impl = findImpl(token);
                if (impl != null) {
                    removeImpl(impl);
                }
            }
        }

        public void dispatchOnExitAnimation(IBinder token, SplashScreenView view) {
            synchronized (mGlobalLock) {
                final SplashScreenImpl impl = findImpl(token);
                if (impl == null) {
                    return;
                }
                if (impl.mExitAnimationListener == null) {
                    Slog.e(TAG, "cannot dispatch onExitAnimation to listener " + token);
                    return;
                }
                impl.mExitAnimationListener.onSplashScreenExit(view);
            }
        }

        public boolean containsExitListener(IBinder token) {
            synchronized (mGlobalLock) {
                final SplashScreenImpl impl = findImpl(token);
                return impl != null && impl.mExitAnimationListener != null;
            }
        }
    }
}
