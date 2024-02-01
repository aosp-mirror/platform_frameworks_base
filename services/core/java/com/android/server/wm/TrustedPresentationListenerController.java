/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.wm;

import static android.graphics.Matrix.MSCALE_X;
import static android.graphics.Matrix.MSCALE_Y;
import static android.graphics.Matrix.MSKEW_X;
import static android.graphics.Matrix.MSKEW_Y;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_TPL;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.Pair;
import android.util.Size;
import android.view.InputWindowHandle;
import android.window.ITrustedPresentationListener;
import android.window.TrustedPresentationThresholds;
import android.window.WindowInfosListener;

import com.android.internal.protolog.common.ProtoLog;
import com.android.server.wm.utils.RegionUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Class to handle TrustedPresentationListener registrations in a thread safe manner. This class
 * also takes care of cleaning up listeners when the remote process dies.
 */
public class TrustedPresentationListenerController {

    // Should only be accessed by the posting to the handler
    private class Listeners {
        private final class ListenerDeathRecipient implements IBinder.DeathRecipient {
            IBinder mListenerBinder;
            int mInstances;

            ListenerDeathRecipient(IBinder listenerBinder) {
                mListenerBinder = listenerBinder;
                mInstances = 0;
                try {
                    mListenerBinder.linkToDeath(this, 0);
                } catch (RemoteException ignore) {
                }
            }

            void addInstance() {
                mInstances++;
            }

            // return true if there are no instances alive
            boolean removeInstance() {
                mInstances--;
                if (mInstances > 0) {
                    return false;
                }
                mListenerBinder.unlinkToDeath(this, 0);
                return true;
            }

            public void binderDied() {
                mHandler.post(() -> {
                    mUniqueListeners.remove(mListenerBinder);
                    removeListeners(mListenerBinder, Optional.empty());
                });
            }
        }

        // tracks binder deaths for cleanup
        ArrayMap<IBinder, ListenerDeathRecipient> mUniqueListeners = new ArrayMap<>();
        ArrayMap<IBinder /*window*/, ArrayList<TrustedPresentationInfo>> mWindowToListeners =
                new ArrayMap<>();

        void register(IBinder window, ITrustedPresentationListener listener,
                TrustedPresentationThresholds thresholds, int id) {
            var listenersForWindow = mWindowToListeners.computeIfAbsent(window,
                    iBinder -> new ArrayList<>());
            listenersForWindow.add(new TrustedPresentationInfo(thresholds, id, listener));

            // register death listener
            var listenerBinder = listener.asBinder();
            var deathRecipient = mUniqueListeners.computeIfAbsent(listenerBinder,
                    ListenerDeathRecipient::new);
            deathRecipient.addInstance();
        }

        void unregister(ITrustedPresentationListener trustedPresentationListener, int id) {
            var listenerBinder = trustedPresentationListener.asBinder();
            var deathRecipient = mUniqueListeners.get(listenerBinder);
            if (deathRecipient == null) {
                ProtoLog.e(WM_DEBUG_TPL, "unregister failed, couldn't find"
                        + " deathRecipient for %s with id=%d", trustedPresentationListener, id);
                return;
            }

            if (deathRecipient.removeInstance()) {
                mUniqueListeners.remove(listenerBinder);
            }
            removeListeners(listenerBinder, Optional.of(id));
        }

        boolean isEmpty() {
            return mWindowToListeners.isEmpty();
        }

        ArrayList<TrustedPresentationInfo> get(IBinder windowToken) {
            return mWindowToListeners.get(windowToken);
        }

        private void removeListeners(IBinder listenerBinder, Optional<Integer> id) {
            for (int i = mWindowToListeners.size() - 1; i >= 0; i--) {
                var listeners = mWindowToListeners.valueAt(i);
                for (int j = listeners.size() - 1; j >= 0; j--) {
                    var listener = listeners.get(j);
                    if (listener.mListener.asBinder() == listenerBinder && (id.isEmpty()
                            || listener.mId == id.get())) {
                        listeners.remove(j);
                    }
                }
                if (listeners.isEmpty()) {
                    mWindowToListeners.removeAt(i);
                }
            }
        }
    }

    private final Object mHandlerThreadLock = new Object();
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private WindowInfosListener mWindowInfosListener;

    Listeners mRegisteredListeners = new Listeners();

    private InputWindowHandle[] mLastWindowHandles;

    private void startHandlerThreadIfNeeded() {
        synchronized (mHandlerThreadLock) {
            if (mHandler == null) {
                mHandlerThread = new HandlerThread("WindowInfosListenerForTpl");
                mHandlerThread.start();
                mHandler = new Handler(mHandlerThread.getLooper());
            }
        }
    }

    void registerListener(IBinder window, ITrustedPresentationListener listener,
            TrustedPresentationThresholds thresholds, int id) {
        startHandlerThreadIfNeeded();
        mHandler.post(() -> {
            ProtoLog.d(WM_DEBUG_TPL, "Registering listener=%s with id=%d for window=%s with %s",
                    listener, id, window, thresholds);

            mRegisteredListeners.register(window, listener, thresholds, id);
            registerWindowInfosListener();
            // Update the initial state for the new registered listener
            computeTpl(mLastWindowHandles);
        });
    }

    void unregisterListener(ITrustedPresentationListener listener, int id) {
        startHandlerThreadIfNeeded();
        mHandler.post(() -> {
            ProtoLog.d(WM_DEBUG_TPL, "Unregistering listener=%s with id=%d",
                    listener, id);

            mRegisteredListeners.unregister(listener, id);
            if (mRegisteredListeners.isEmpty()) {
                unregisterWindowInfosListener();
            }
        });
    }

    void dump(PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println("TrustedPresentationListenerController:");
        pw.println(innerPrefix + "Active unique listeners ("
                + mRegisteredListeners.mUniqueListeners.size() + "):");
        for (int i = 0; i < mRegisteredListeners.mWindowToListeners.size(); i++) {
            pw.println(
                    innerPrefix + "  window=" + mRegisteredListeners.mWindowToListeners.keyAt(i));
            final var listeners = mRegisteredListeners.mWindowToListeners.valueAt(i);
            for (int j = 0; j < listeners.size(); j++) {
                final var listener = listeners.get(j);
                pw.println(innerPrefix + innerPrefix + "  listener=" + listener.mListener.asBinder()
                        + " id=" + listener.mId
                        + " thresholds=" + listener.mThresholds);
            }
        }
    }

    private void registerWindowInfosListener() {
        if (mWindowInfosListener != null) {
            return;
        }

        mWindowInfosListener = new WindowInfosListener() {
            @Override
            public void onWindowInfosChanged(InputWindowHandle[] windowHandles,
                    DisplayInfo[] displayInfos) {
                mHandler.post(() -> computeTpl(windowHandles));
            }
        };
        mLastWindowHandles = mWindowInfosListener.register().first;
    }

    private void unregisterWindowInfosListener() {
        if (mWindowInfosListener == null) {
            return;
        }

        mWindowInfosListener.unregister();
        mWindowInfosListener = null;
        mLastWindowHandles = null;
    }

    private void computeTpl(InputWindowHandle[] windowHandles) {
        mLastWindowHandles = windowHandles;
        if (mLastWindowHandles == null || mLastWindowHandles.length == 0
                || mRegisteredListeners.isEmpty()) {
            return;
        }

        Rect tmpRect = new Rect();
        Matrix tmpInverseMatrix = new Matrix();
        float[] tmpMatrix = new float[9];
        Region coveredRegionsAbove = new Region();
        long currTimeMs = System.currentTimeMillis();
        ProtoLog.v(WM_DEBUG_TPL, "Checking %d windows", mLastWindowHandles.length);

        ArrayMap<ITrustedPresentationListener, Pair<IntArray, IntArray>> listenerUpdates =
                new ArrayMap<>();
        for (var windowHandle : mLastWindowHandles) {
            if (!windowHandle.canOccludePresentation) {
                ProtoLog.v(WM_DEBUG_TPL, "Skipping %s", windowHandle.name);
                continue;
            }
            tmpRect.set(windowHandle.frame);
            var listeners = mRegisteredListeners.get(windowHandle.getWindowToken());
            if (listeners != null) {
                Region region = new Region();
                region.op(tmpRect, coveredRegionsAbove, Region.Op.DIFFERENCE);
                windowHandle.transform.invert(tmpInverseMatrix);
                tmpInverseMatrix.getValues(tmpMatrix);
                float scaleX = (float) Math.sqrt(tmpMatrix[MSCALE_X] * tmpMatrix[MSCALE_X]
                        + tmpMatrix[MSKEW_X] * tmpMatrix[MSKEW_X]);
                float scaleY = (float) Math.sqrt(tmpMatrix[MSCALE_Y] * tmpMatrix[MSCALE_Y]
                        + tmpMatrix[MSKEW_Y] * tmpMatrix[MSKEW_Y]);

                float fractionRendered = computeFractionRendered(region, new RectF(tmpRect),
                        windowHandle.contentSize,
                        scaleX, scaleY);

                checkIfInThreshold(listeners, listenerUpdates, fractionRendered, windowHandle.alpha,
                        currTimeMs);
            }

            coveredRegionsAbove.op(tmpRect, Region.Op.UNION);
            ProtoLog.v(WM_DEBUG_TPL, "coveredRegionsAbove updated with %s frame:%s region:%s",
                    windowHandle.name, tmpRect.toShortString(), coveredRegionsAbove);
        }

        for (int i = 0; i < listenerUpdates.size(); i++) {
            var updates = listenerUpdates.valueAt(i);
            var listener = listenerUpdates.keyAt(i);
            try {
                listener.onTrustedPresentationChanged(updates.first.toArray(),
                        updates.second.toArray());
            } catch (RemoteException ignore) {
            }
        }
    }

    private void addListenerUpdate(
            ArrayMap<ITrustedPresentationListener, Pair<IntArray, IntArray>> listenerUpdates,
            ITrustedPresentationListener listener, int id, boolean presentationState) {
        var updates = listenerUpdates.get(listener);
        if (updates == null) {
            updates = new Pair<>(new IntArray(), new IntArray());
            listenerUpdates.put(listener, updates);
        }
        if (presentationState) {
            updates.first.add(id);
        } else {
            updates.second.add(id);
        }
    }


    private void checkIfInThreshold(
            ArrayList<TrustedPresentationInfo> listeners,
            ArrayMap<ITrustedPresentationListener, Pair<IntArray, IntArray>> listenerUpdates,
            float fractionRendered, float alpha, long currTimeMs) {
        ProtoLog.v(WM_DEBUG_TPL, "checkIfInThreshold fractionRendered=%f alpha=%f currTimeMs=%d",
                fractionRendered, alpha, currTimeMs);
        for (int i = 0; i < listeners.size(); i++) {
            var trustedPresentationInfo = listeners.get(i);
            var listener = trustedPresentationInfo.mListener;
            boolean lastState = trustedPresentationInfo.mLastComputedTrustedPresentationState;
            boolean newState =
                    (alpha >= trustedPresentationInfo.mThresholds.minAlpha) && (fractionRendered
                            >= trustedPresentationInfo.mThresholds.minFractionRendered);
            trustedPresentationInfo.mLastComputedTrustedPresentationState = newState;

            ProtoLog.v(WM_DEBUG_TPL,
                    "lastState=%s newState=%s alpha=%f minAlpha=%f fractionRendered=%f "
                            + "minFractionRendered=%f",
                    lastState, newState, alpha, trustedPresentationInfo.mThresholds.minAlpha,
                    fractionRendered, trustedPresentationInfo.mThresholds.minFractionRendered);

            if (lastState && !newState) {
                // We were in the trusted presentation state, but now we left it,
                // emit the callback if needed
                if (trustedPresentationInfo.mLastReportedTrustedPresentationState) {
                    trustedPresentationInfo.mLastReportedTrustedPresentationState = false;
                    addListenerUpdate(listenerUpdates, listener,
                            trustedPresentationInfo.mId, /*presentationState*/ false);
                    ProtoLog.d(WM_DEBUG_TPL, "Adding untrusted state listener=%s with id=%d",
                            listener, trustedPresentationInfo.mId);
                }
                // Reset the timer
                trustedPresentationInfo.mEnteredTrustedPresentationStateTime = -1;
            } else if (!lastState && newState) {
                // We were not in the trusted presentation state, but we entered it, begin the timer
                // and make sure this gets called at least once more!
                trustedPresentationInfo.mEnteredTrustedPresentationStateTime = currTimeMs;
                mHandler.postDelayed(() -> {
                    computeTpl(mLastWindowHandles);
                }, (long) (trustedPresentationInfo.mThresholds.stabilityRequirementMs * 1.5));
            }

            // Has the timer elapsed, but we are still in the state? Emit a callback if needed
            if (!trustedPresentationInfo.mLastReportedTrustedPresentationState && newState && (
                    currTimeMs - trustedPresentationInfo.mEnteredTrustedPresentationStateTime
                            > trustedPresentationInfo.mThresholds.stabilityRequirementMs)) {
                trustedPresentationInfo.mLastReportedTrustedPresentationState = true;
                addListenerUpdate(listenerUpdates, listener,
                        trustedPresentationInfo.mId, /*presentationState*/ true);
                ProtoLog.d(WM_DEBUG_TPL, "Adding trusted state listener=%s with id=%d",
                        listener, trustedPresentationInfo.mId);
            }
        }
    }

    private float computeFractionRendered(Region visibleRegion, RectF screenBounds,
            Size contentSize,
            float sx, float sy) {
        ProtoLog.v(WM_DEBUG_TPL,
                "computeFractionRendered: visibleRegion=%s screenBounds=%s contentSize=%s "
                        + "scale=%f,%f",
                visibleRegion, screenBounds, contentSize, sx, sy);

        if (contentSize.getWidth() == 0 || contentSize.getHeight() == 0) {
            return -1;
        }
        if (screenBounds.width() == 0 || screenBounds.height() == 0) {
            return -1;
        }

        float fractionRendered = Math.min(sx * sy, 1.0f);
        ProtoLog.v(WM_DEBUG_TPL, "fractionRendered scale=%f", fractionRendered);

        float boundsOverSourceW = screenBounds.width() / (float) contentSize.getWidth();
        float boundsOverSourceH = screenBounds.height() / (float) contentSize.getHeight();
        fractionRendered *= boundsOverSourceW * boundsOverSourceH;
        ProtoLog.v(WM_DEBUG_TPL, "fractionRendered boundsOverSource=%f", fractionRendered);
        // Compute the size of all the rects since they may be disconnected.
        float[] visibleSize = new float[1];
        RegionUtils.forEachRect(visibleRegion, rect -> {
            float size = rect.width() * rect.height();
            visibleSize[0] += size;
        });

        fractionRendered *= visibleSize[0] / (screenBounds.width() * screenBounds.height());
        return fractionRendered;
    }

    private static class TrustedPresentationInfo {
        boolean mLastComputedTrustedPresentationState = false;
        boolean mLastReportedTrustedPresentationState = false;
        long mEnteredTrustedPresentationStateTime = -1;
        final TrustedPresentationThresholds mThresholds;

        final ITrustedPresentationListener mListener;
        final int mId;

        private TrustedPresentationInfo(TrustedPresentationThresholds thresholds, int id,
                ITrustedPresentationListener listener) {
            mThresholds = thresholds;
            mId = id;
            mListener = listener;
            checkValid(thresholds);
        }

        private void checkValid(TrustedPresentationThresholds thresholds) {
            if (thresholds.minAlpha <= 0 || thresholds.minFractionRendered <= 0
                    || thresholds.stabilityRequirementMs < 1) {
                throw new IllegalArgumentException(
                        "TrustedPresentationThresholds values are invalid");
            }
        }
    }
}
