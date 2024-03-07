/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.IBinder;
import android.os.InputConfig;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.InputWindowHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Wrapper class to provide access to WindowInfosListener within tests.
 *
 * @hide
 */
@TestApi
public class WindowInfosListenerForTest {

    /**
     * Window properties passed to {@code @WindowInfosListenerForTest#onWindowInfosChanged}.
     */
    public static class WindowInfo {
        /**
         * The window's token.
         */
        @NonNull
        public final IBinder windowToken;

        /**
         * The window's name.
         */
        @NonNull
        public final String name;

        /**
         * The display id the window is on.
         */
        public final int displayId;

        /**
         * The window's position and size in display space.
         */
        @NonNull
        public final Rect bounds;

        /**
         * True if the window is a trusted overlay.
         */
        public final boolean isTrustedOverlay;

        /**
         * True if the window is visible.
         */
        public final boolean isVisible;

        /**
         * Return the transform to get the bounds from display space into window space.
         */
        @NonNull
        public final Matrix transform;

        /**
         * True if the window is touchable.
         */
        @SuppressLint("UnflaggedApi") // The API is only used for tests.
        public final boolean isTouchable;

        /**
         * True if the window is focusable.
         */
        @SuppressLint("UnflaggedApi") // The API is only used for tests.
        public final boolean isFocusable;

        /**
         * True if the window is preventing splitting
         */
        @SuppressLint("UnflaggedApi") // The API is only used for tests.
        public final boolean isPreventSplitting;

        /**
         * True if the window duplicates touches received to wallpaper.
         */
        @SuppressLint("UnflaggedApi") // The API is only used for tests.
        public final boolean isDuplicateTouchToWallpaper;

        /**
         * True if the window is listening for when there is a touch DOWN event
         * occurring outside its touchable bounds. When such an event occurs,
         * this window will receive a MotionEvent with ACTION_OUTSIDE.
         */
        @SuppressLint("UnflaggedApi") // The API is only used for tests.
        public final boolean isWatchOutsideTouch;

        WindowInfo(@NonNull IBinder windowToken, @NonNull String name, int displayId,
                @NonNull Rect bounds, int inputConfig, @NonNull Matrix transform) {
            this.windowToken = windowToken;
            this.name = name;
            this.displayId = displayId;
            this.bounds = bounds;
            this.isTrustedOverlay = (inputConfig & InputConfig.TRUSTED_OVERLAY) != 0;
            this.isVisible = (inputConfig & InputConfig.NOT_VISIBLE) == 0;
            this.transform = transform;
            this.isTouchable = (inputConfig & InputConfig.NOT_TOUCHABLE) == 0;
            this.isFocusable = (inputConfig & InputConfig.NOT_FOCUSABLE) == 0;
            this.isPreventSplitting = (inputConfig
                            & InputConfig.PREVENT_SPLITTING) != 0;
            this.isDuplicateTouchToWallpaper = (inputConfig
                            & InputConfig.DUPLICATE_TOUCH_TO_WALLPAPER) != 0;
            this.isWatchOutsideTouch = (inputConfig
                            & InputConfig.WATCH_OUTSIDE_TOUCH) != 0;
        }

        @Override
        public String toString() {
            return name + ", displayId=" + displayId
                    + ", frame=" + bounds
                    + ", isVisible=" + isVisible
                    + ", isTrustedOverlay=" + isTrustedOverlay
                    + ", token=" + windowToken
                    + ", transform=" + transform;
        }
    }

    private static final String TAG = "WindowInfosListenerForTest";

    private ArrayMap<Consumer<List<WindowInfo>>, WindowInfosListener> mListeners;

    public WindowInfosListenerForTest() {
        mListeners = new ArrayMap<>();
    }

    /**
     * Register a listener that is called when the system's list of visible windows has changes in
     * position or visibility.
     *
     * @param consumer Consumer that is called with reverse Z ordered lists of WindowInfo instances
     *                 where the first value is the topmost window.
     */
    @RequiresPermission(Manifest.permission.ACCESS_SURFACE_FLINGER)
    public void addWindowInfosListener(
            @NonNull Consumer<List<WindowInfo>> consumer) {
        var calledWithInitialState = new CountDownLatch(1);
        var listener = new WindowInfosListener() {
            @Override
            public void onWindowInfosChanged(InputWindowHandle[] windowHandles,
                    DisplayInfo[] displayInfos) {
                try {
                    calledWithInitialState.await();
                } catch (InterruptedException exception) {
                    Log.e(TAG,
                            "Exception thrown while waiting for listener to be called with "
                                    + "initial state");
                }
                consumer.accept(buildWindowInfos(windowHandles, displayInfos));
            }
        };
        mListeners.put(consumer, listener);
        Pair<InputWindowHandle[], WindowInfosListener.DisplayInfo[]> initialState =
                listener.register();
        consumer.accept(buildWindowInfos(initialState.first, initialState.second));
        calledWithInitialState.countDown();
    }

    /**
     * Unregisters the listener.
     */
    public void removeWindowInfosListener(@NonNull Consumer<List<WindowInfo>> consumer) {
        WindowInfosListener listener = mListeners.remove(consumer);
        if (listener == null) {
            return;
        }
        listener.unregister();
    }

    private static List<WindowInfo> buildWindowInfos(
            InputWindowHandle[] windowHandles, WindowInfosListener.DisplayInfo[] displayInfos) {
        var windowInfos = new ArrayList<WindowInfo>(windowHandles.length);

        var displayInfoById = new SparseArray<WindowInfosListener.DisplayInfo>(displayInfos.length);
        for (var displayInfo : displayInfos) {
            displayInfoById.put(displayInfo.mDisplayId, displayInfo);
        }

        var tmp = new RectF();
        for (var handle : windowHandles) {
            var bounds = new Rect(handle.frame);

            // Transform bounds from physical display coordinates to logical display coordinates.
            var display = displayInfoById.get(handle.displayId);
            if (display != null) {
                tmp.set(bounds);
                display.mTransform.mapRect(tmp);
                tmp.round(bounds);
            }

            windowInfos.add(new WindowInfo(handle.getWindowToken(), handle.name, handle.displayId,
                    bounds, handle.inputConfig, handle.transform));
        }
        return windowInfos;
    }
}
