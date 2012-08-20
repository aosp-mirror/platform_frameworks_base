/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.display;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.CompatibilityInfoHolder;
import android.view.Display;
import android.view.DisplayInfo;

import java.util.ArrayList;

/**
 * Manages the properties, media routing and power state of attached displays.
 * <p>
 * Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String)
 * Context.getSystemService()} with the argument
 * {@link android.content.Context#DISPLAY_SERVICE}.
 * </p>
 */
public final class DisplayManager {
    private static final String TAG = "DisplayManager";
    private static final boolean DEBUG = false;

    private static final int MSG_DISPLAY_ADDED = 1;
    private static final int MSG_DISPLAY_REMOVED = 2;
    private static final int MSG_DISPLAY_CHANGED = 3;

    private static DisplayManager sInstance;

    private final IDisplayManager mDm;

    // Guarded by mDisplayLock
    private final Object mDisplayLock = new Object();
    private final ArrayList<DisplayListenerDelegate> mDisplayListeners =
            new ArrayList<DisplayListenerDelegate>();


    private DisplayManager(IDisplayManager dm) {
        mDm = dm;
    }

    /**
     * Gets an instance of the display manager.
     *
     * @return The display manager instance, may be null early in system startup
     * before the display manager has been fully initialized.
     *
     * @hide
     */
    public static DisplayManager getInstance() {
        synchronized (DisplayManager.class) {
            if (sInstance == null) {
                IBinder b = ServiceManager.getService(Context.DISPLAY_SERVICE);
                if (b != null) {
                    sInstance = new DisplayManager(IDisplayManager.Stub.asInterface(b));
                }
            }
            return sInstance;
        }
    }

    /**
     * Get information about a particular logical display.
     *
     * @param displayId The logical display id.
     * @param outInfo A structure to populate with the display info.
     * @return True if the logical display exists, false otherwise.
     * @hide
     */
    public boolean getDisplayInfo(int displayId, DisplayInfo outInfo) {
        try {
            return mDm.getDisplayInfo(displayId, outInfo);
        } catch (RemoteException ex) {
            Log.e(TAG, "Could not get display information from display manager.", ex);
            return false;
        }
    }

    /**
     * Gets information about a logical display.
     *
     * The display metrics may be adjusted to provide compatibility
     * for legacy applications.
     *
     * @param displayId The logical display id.
     * @param applicationContext The application context from which to obtain
     * compatible metrics.
     * @return The display object.
     */
    public Display getDisplay(int displayId, Context applicationContext) {
        if (applicationContext == null) {
            throw new IllegalArgumentException("applicationContext must not be null");
        }

        CompatibilityInfoHolder cih = null;
        if (displayId == Display.DEFAULT_DISPLAY) {
            cih = applicationContext.getCompatibilityInfo();
        }
        return getCompatibleDisplay(displayId, cih);
    }

    /**
     * Gets information about a logical display.
     *
     * The display metrics may be adjusted to provide compatibility
     * for legacy applications.
     *
     * @param displayId The logical display id.
     * @param cih The compatibility info, or null if none is required.
     * @return The display object.
     *
     * @hide
     */
    public Display getCompatibleDisplay(int displayId, CompatibilityInfoHolder cih) {
        return new Display(displayId, cih);
    }

    /**
     * Gets information about a logical display without applying any compatibility metrics.
     *
     * @param displayId The logical display id.
     * @return The display object.
     *
     * @hide
     */
    public Display getRealDisplay(int displayId) {
        return getCompatibleDisplay(displayId, null);
    }

    /**
     * Registers an display listener to receive notifications about when
     * displays are added, removed or changed.
     *
     * @param listener The listener to register.
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     *
     * @see #unregisterDisplayListener
     */
    public void registerDisplayListener(DisplayListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (mDisplayLock) {
            int index = findDisplayListenerLocked(listener);
            if (index < 0) {
                mDisplayListeners.add(new DisplayListenerDelegate(listener, handler));
            }
        }
    }

    /**
     * Unregisters an input device listener.
     *
     * @param listener The listener to unregister.
     *
     * @see #registerDisplayListener
     */
    public void unregisterDisplayListener(DisplayListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (mDisplayLock) {
            int index = findDisplayListenerLocked(listener);
            if (index >= 0) {
                DisplayListenerDelegate d = mDisplayListeners.get(index);
                d.removeCallbacksAndMessages(null);
                mDisplayListeners.remove(index);
            }
        }
    }

    private int findDisplayListenerLocked(DisplayListener listener) {
        final int numListeners = mDisplayListeners.size();
        for (int i = 0; i < numListeners; i++) {
            if (mDisplayListeners.get(i).mListener == listener) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Listens for changes in available display devices.
     */
    public interface DisplayListener {
        /**
         * Called whenever a logical display has been added to the system.
         * Use {@link DisplayManager#getDisplay} to get more information about the display.
         *
         * @param displayId The id of the logical display that was added.
         */
        void onDisplayAdded(int displayId);

        /**
         * Called whenever a logical display has been removed from the system.
         *
         * @param displayId The id of the logical display that was removed.
         */
        void onDisplayRemoved(int displayId);

        /**
         * Called whenever the properties of a logical display have changed.
         *
         * @param displayId The id of the logical display that changed.
         */
        void onDisplayChanged(int displayId);
    }

    private static final class DisplayListenerDelegate extends Handler {
        public final DisplayListener mListener;

        public DisplayListenerDelegate(DisplayListener listener, Handler handler) {
            super(handler != null ? handler.getLooper() : Looper.myLooper());
            mListener = listener;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DISPLAY_ADDED:
                    mListener.onDisplayAdded(msg.arg1);
                    break;
                case MSG_DISPLAY_REMOVED:
                    mListener.onDisplayRemoved(msg.arg1);
                    break;
                case MSG_DISPLAY_CHANGED:
                    mListener.onDisplayChanged(msg.arg1);
                    break;
            }
        }
    }
}
