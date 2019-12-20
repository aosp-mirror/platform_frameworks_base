/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.wm;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.IDisplayWindowListener;
import android.view.IDisplayWindowRotationCallback;
import android.view.IDisplayWindowRotationController;
import android.view.IWindowManager;
import android.view.WindowContainerTransaction;

import com.android.systemui.dagger.qualifiers.MainHandler;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This module deals with display rotations coming from WM. When WM starts a rotation: after it has
 * frozen the screen, it will call into this class. This will then call all registered local
 * controllers and give them a chance to queue up task changes to be applied synchronously with that
 * rotation.
 */
@Singleton
public class DisplayWindowController {
    private static final String TAG = "DisplayWindowController";

    private final Handler mHandler;
    private final Context mContext;
    private final IWindowManager mWmService;

    private final ArrayList<OnDisplayWindowRotationController> mRotationControllers =
            new ArrayList<>();
    private final ArrayList<OnDisplayWindowRotationController> mTmpControllers = new ArrayList<>();

    private final SparseArray<DisplayRecord> mDisplays = new SparseArray<>();
    private final ArrayList<DisplayWindowListener> mDisplayChangedListeners = new ArrayList<>();

    private final IDisplayWindowRotationController mDisplayRotationController =
            new IDisplayWindowRotationController.Stub() {
                @Override
                public void onRotateDisplay(int displayId, final int fromRotation,
                        final int toRotation, IDisplayWindowRotationCallback callback) {
                    mHandler.post(() -> {
                        WindowContainerTransaction t = new WindowContainerTransaction();
                        synchronized (mRotationControllers) {
                            mTmpControllers.clear();
                            // Make a local copy in case the handlers add/remove themselves.
                            mTmpControllers.addAll(mRotationControllers);
                        }
                        for (OnDisplayWindowRotationController c : mTmpControllers) {
                            c.onRotateDisplay(displayId, fromRotation, toRotation, t);
                        }
                        try {
                            callback.continueRotateDisplay(toRotation, t);
                        } catch (RemoteException e) {
                        }
                    });
                }
            };

    /**
     * Get's a display by id from DisplayManager.
     */
    public Display getDisplay(int displayId) {
        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        return displayManager.getDisplay(displayId);
    }

    private final IDisplayWindowListener mDisplayContainerListener =
            new IDisplayWindowListener.Stub() {
                @Override
                public void onDisplayAdded(int displayId) {
                    mHandler.post(() -> {
                        synchronized (mDisplays) {
                            if (mDisplays.get(displayId) != null) {
                                return;
                            }
                            Display display = getDisplay(displayId);
                            if (display == null) {
                                // It's likely that the display is private to some app and thus not
                                // accessible by system-ui.
                                return;
                            }
                            DisplayRecord record = new DisplayRecord();
                            record.mDisplayId = displayId;
                            record.mContext = (displayId == Display.DEFAULT_DISPLAY) ? mContext
                                    : mContext.createDisplayContext(display);
                            record.mDisplayLayout = new DisplayLayout(record.mContext, display);
                            mDisplays.put(displayId, record);
                            for (int i = 0; i < mDisplayChangedListeners.size(); ++i) {
                                mDisplayChangedListeners.get(i).onDisplayAdded(displayId);
                            }
                        }
                    });
                }

                @Override
                public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
                    mHandler.post(() -> {
                        synchronized (mDisplays) {
                            DisplayRecord dr = mDisplays.get(displayId);
                            if (dr == null) {
                                Slog.w(TAG, "Skipping Display Configuration change on non-added"
                                        + " display.");
                                return;
                            }
                            Display display = getDisplay(displayId);
                            Context perDisplayContext = mContext;
                            if (displayId != Display.DEFAULT_DISPLAY) {
                                perDisplayContext = mContext.createDisplayContext(display);
                            }
                            dr.mContext = perDisplayContext.createConfigurationContext(newConfig);
                            dr.mDisplayLayout = new DisplayLayout(dr.mContext, display);
                            for (int i = 0; i < mDisplayChangedListeners.size(); ++i) {
                                mDisplayChangedListeners.get(i).onDisplayConfigurationChanged(
                                        displayId, newConfig);
                            }
                        }
                    });
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    mHandler.post(() -> {
                        synchronized (mDisplays) {
                            if (mDisplays.get(displayId) == null) {
                                return;
                            }
                            for (int i = mDisplayChangedListeners.size() - 1; i >= 0; --i) {
                                mDisplayChangedListeners.get(i).onDisplayRemoved(displayId);
                            }
                            mDisplays.remove(displayId);
                        }
                    });
                }
            };

    @Inject
    public DisplayWindowController(Context context, @MainHandler Handler mainHandler,
            IWindowManager wmService) {
        mHandler = mainHandler;
        mContext = context;
        mWmService = wmService;
        try {
            mWmService.registerDisplayWindowListener(mDisplayContainerListener);
            mWmService.setDisplayWindowRotationController(mDisplayRotationController);
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to register hierarchy listener");
        }
    }

    /**
     * Gets the DisplayLayout associated with a display.
     */
    public @Nullable DisplayLayout getDisplayLayout(int displayId) {
        final DisplayRecord r = mDisplays.get(displayId);
        return r != null ? r.mDisplayLayout : null;
    }

    /**
     * Gets a display-specific context for a display.
     */
    public @Nullable Context getDisplayContext(int displayId) {
        final DisplayRecord r = mDisplays.get(displayId);
        return r != null ? r.mContext : null;
    }

    /**
     * Add a display window-container listener. It will get notified whenever a display's
     * configuration changes or when displays are added/removed from the WM hierarchy.
     */
    public void addDisplayWindowListener(DisplayWindowListener listener) {
        synchronized (mDisplays) {
            if (mDisplayChangedListeners.contains(listener)) {
                return;
            }
            mDisplayChangedListeners.add(listener);
            for (int i = 0; i < mDisplays.size(); ++i) {
                listener.onDisplayAdded(mDisplays.keyAt(i));
            }
        }
    }

    /**
     * Remove a display window-container listener.
     */
    public void removeDisplayWindowListener(DisplayWindowListener listener) {
        synchronized (mDisplays) {
            mDisplayChangedListeners.remove(listener);
        }
    }

    /**
     * Adds a display rotation controller.
     */
    public void addRotationController(OnDisplayWindowRotationController controller) {
        synchronized (mRotationControllers) {
            mRotationControllers.add(controller);
        }
    }

    /**
     * Removes a display rotation controller.
     */
    public void removeRotationController(OnDisplayWindowRotationController controller) {
        synchronized (mRotationControllers) {
            mRotationControllers.remove(controller);
        }
    }

    private static class DisplayRecord {
        int mDisplayId;
        Context mContext;
        DisplayLayout mDisplayLayout;
    }

    /**
     * Gets notified when a display is added/removed to the WM hierarchy and when a display's
     * window-configuration changes.
     *
     * @see IDisplayWindowListener
     */
    public interface DisplayWindowListener {
        /**
         * Called when a display has been added to the WM hierarchy.
         */
        void onDisplayAdded(int displayId);

        /**
         * Called when a display's window-container configuration changes.
         */
        void onDisplayConfigurationChanged(int displayId, Configuration newConfig);

        /**
         * Called when a display is removed.
         */
        void onDisplayRemoved(int displayId);
    }

    /**
     * Give a controller a chance to queue up configuration changes to execute as part of a
     * display rotation. The contents of {@link #onRotateDisplay} must run synchronously.
     */
    public interface OnDisplayWindowRotationController {
        /**
         * Called before the display is rotated. Contents of this method must run synchronously.
         * @param displayId Id of display that is rotating.
         * @param fromRotation starting rotation of the display.
         * @param toRotation target rotation of the display (after rotating).
         * @param t A task transaction to populate.
         */
        void onRotateDisplay(int displayId, int fromRotation, int toRotation,
                WindowContainerTransaction t);
    }
}
