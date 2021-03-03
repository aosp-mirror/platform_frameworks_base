/**
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

package android.view;

import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.Log;

import java.util.function.Consumer;

/**
 * Class that holds all registered {@link CrossWindowBlurEnabledListener}s. It listens
 * for updates from the WindowManagerService and updates all registered listeners.
 * @hide
 */
public final class CrossWindowBlurListeners {
    private static final String TAG = "CrossWindowBlurListeners";

    // property for background blur support in surface flinger
    private static final String BLUR_PROPERTY = "ro.surface_flinger.supports_background_blur";
    public static final boolean CROSS_WINDOW_BLUR_SUPPORTED =
            SystemProperties.get(BLUR_PROPERTY, "default").equals("1");

    private static volatile CrossWindowBlurListeners sInstance;
    private static final Object sLock = new Object();

    private final BlurEnabledListenerInternal mListenerInternal = new BlurEnabledListenerInternal();
    private final ArraySet<Consumer<Boolean>> mListeners = new ArraySet();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private boolean mInternalListenerAttached = false;
    private boolean mCrossWindowBlurEnabled;

    private CrossWindowBlurListeners() {}

    /**
     * Returns a CrossWindowBlurListeners instance
     */
    public static CrossWindowBlurListeners getInstance() {
        CrossWindowBlurListeners instance = sInstance;
        if (instance == null) {

            synchronized (sLock) {
                instance = sInstance;
                if (instance == null) {
                    instance = new CrossWindowBlurListeners();
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    boolean isCrossWindowBlurEnabled() {
        synchronized (sLock) {
            attachInternalListenerIfNeededLocked();
            return mCrossWindowBlurEnabled;
        }
    }

    void addListener(Consumer<Boolean> listener) {
        if (listener == null) return;

        synchronized (sLock) {
            attachInternalListenerIfNeededLocked();

            mListeners.add(listener);
            notifyListenerOnMain(listener, mCrossWindowBlurEnabled);
        }
    }


    void removeListener(Consumer<Boolean> listener) {
        if (listener == null) return;

        synchronized (sLock) {
            mListeners.remove(listener);

            if (mInternalListenerAttached && mListeners.size() == 0) {
                try {
                    WindowManagerGlobal.getWindowManagerService()
                            .unregisterCrossWindowBlurEnabledListener(mListenerInternal);
                    mInternalListenerAttached = false;
                } catch (RemoteException e) {
                    Log.d(TAG, "Could not unregister ICrossWindowBlurEnabledListener");
                }
            }
        }
    }

    private void attachInternalListenerIfNeededLocked() {
        if (!mInternalListenerAttached) {
            try {
                mCrossWindowBlurEnabled = WindowManagerGlobal.getWindowManagerService()
                        .registerCrossWindowBlurEnabledListener(mListenerInternal);
                mInternalListenerAttached = true;
            } catch (RemoteException e) {
                Log.d(TAG, "Could not register ICrossWindowBlurEnabledListener");
            }
        }
    }

    private void notifyListenerOnMain(Consumer<Boolean> listener, boolean enabled) {
        mMainHandler.post(() -> {
            listener.accept(enabled);
        });
    }

    private final class BlurEnabledListenerInternal extends ICrossWindowBlurEnabledListener.Stub {
        @Override
        public void onCrossWindowBlurEnabledChanged(boolean enabled) {
            synchronized (sLock) {
                mCrossWindowBlurEnabled = enabled;

                for (int i = 0; i < mListeners.size(); i++) {
                    notifyListenerOnMain(mListeners.valueAt(i), enabled);
                }
            }
        }
    }
}
