/*
 ** Copyright 2011, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package android.view.accessibility;

import android.accessibilityservice.IAccessibilityServiceConnection;
import android.graphics.Rect;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is a singleton that performs accessibility interaction
 * which is it queries remote view hierarchies about snapshots of their
 * views as well requests from these hierarchies to perform certain
 * actions on their views.
 *
 * Rationale: The content retrieval APIs are synchronous from a client's
 *     perspective but internally they are asynchronous. The client thread
 *     calls into the system requesting an action and providing a callback
 *     to receive the result after which it waits up to a timeout for that
 *     result. The system enforces security and the delegates the request
 *     to a given view hierarchy where a message is posted (from a binder
 *     thread) describing what to be performed by the main UI thread the
 *     result of which it delivered via the mentioned callback. However,
 *     the blocked client thread and the main UI thread of the target view
 *     hierarchy can be the same thread, for example an accessibility service
 *     and an activity run in the same process, thus they are executed on the
 *     same main thread. In such a case the retrieval will fail since the UI
 *     thread that has to process the message describing the work to be done
 *     is blocked waiting for a result is has to compute! To avoid this scenario
 *     when making a call the client also passes its process and thread ids so
 *     the accessed view hierarchy can detect if the client making the request
 *     is running in its main UI thread. In such a case the view hierarchy,
 *     specifically the binder thread performing the IPC to it, does not post a
 *     message to be run on the UI thread but passes it to the singleton
 *     interaction client through which all interactions occur and the latter is
 *     responsible to execute the message before starting to wait for the
 *     asynchronous result delivered via the callback. In this case the expected
 *     result is already received so no waiting is performed.
 *
 * @hide
 */
public final class AccessibilityInteractionClient
        extends IAccessibilityInteractionConnectionCallback.Stub {

    private static final long TIMEOUT_INTERACTION_MILLIS = 5000;

    private static final Object sStaticLock = new Object();

    private static AccessibilityInteractionClient sInstance;

    private final AtomicInteger mInteractionIdCounter = new AtomicInteger();

    private final Object mInstanceLock = new Object();

    private int mInteractionId = -1;

    private AccessibilityNodeInfo mFindAccessibilityNodeInfoResult;

    private List<AccessibilityNodeInfo> mFindAccessibilityNodeInfosResult;

    private boolean mPerformAccessibilityActionResult;

    private Message mSameThreadMessage;

    private final Rect mTempBounds = new Rect();

    /**
     * @return The singleton of this class.
     */
    public static AccessibilityInteractionClient getInstance() {
        synchronized (sStaticLock) {
            if (sInstance == null) {
                sInstance = new AccessibilityInteractionClient();
            }
            return sInstance;
        }
    }

    /**
     * Sets the message to be processed if the interacted view hierarchy
     * and the interacting client are running in the same thread.
     *
     * @param message The message.
     */
    public void setSameThreadMessage(Message message) {
        synchronized (mInstanceLock) {
            mSameThreadMessage = message;
        }
    }

    /**
     * Finds an {@link AccessibilityNodeInfo} by accessibility id.
     *
     * @param connection A connection for interacting with the system.
     * @param accessibilityWindowId A unique window id.
     * @param accessibilityViewId A unique View accessibility id.
     * @return An {@link AccessibilityNodeInfo} if found, null otherwise.
     */
    public AccessibilityNodeInfo findAccessibilityNodeInfoByAccessibilityId(
            IAccessibilityServiceConnection connection, int accessibilityWindowId,
            int accessibilityViewId) {
        try {
            final int interactionId = mInteractionIdCounter.getAndIncrement();
            final float windowScale = connection.findAccessibilityNodeInfoByAccessibilityId(
                    accessibilityWindowId, accessibilityViewId, interactionId, this,
                    Thread.currentThread().getId());
            // If the scale is zero the call has failed.
            if (windowScale > 0) {
                handleSameThreadMessageIfNeeded();
                AccessibilityNodeInfo info = getFindAccessibilityNodeInfoResultAndClear(
                        interactionId);
                finalizeAccessibilityNodeInfo(info, connection, windowScale);
                return info;
            }
        } catch (RemoteException re) {
            /* ignore */
        }
        return null;
    }

    /**
     * Finds an {@link AccessibilityNodeInfo} by View id. The search is performed
     * in the currently active window and starts from the root View in the window.
     *
     * @param connection A connection for interacting with the system.
     * @param id The id of the node.
     * @return An {@link AccessibilityNodeInfo} if found, null otherwise.
     */
    public AccessibilityNodeInfo findAccessibilityNodeInfoByViewIdInActiveWindow(
            IAccessibilityServiceConnection connection, int viewId) {
        try {
            final int interactionId = mInteractionIdCounter.getAndIncrement();
            final float windowScale = connection.findAccessibilityNodeInfoByViewIdInActiveWindow(
                    viewId, interactionId, this, Thread.currentThread().getId());
            // If the scale is zero the call has failed.
            if (windowScale > 0) {
                handleSameThreadMessageIfNeeded();
                AccessibilityNodeInfo info = getFindAccessibilityNodeInfoResultAndClear(
                        interactionId);
                finalizeAccessibilityNodeInfo(info, connection, windowScale);
                return info;
            }
        } catch (RemoteException re) {
            /* ignore */
        }
        return null;
    }

    /**
     * Finds {@link AccessibilityNodeInfo}s by View text. The match is case
     * insensitive containment. The search is performed in the currently
     * active window and starts from the root View in the window.
     *
     * @param connection A connection for interacting with the system.
     * @param text The searched text.
     * @return A list of found {@link AccessibilityNodeInfo}s.
     */
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewTextInActiveWindow(
            IAccessibilityServiceConnection connection, String text) {
        try {
            final int interactionId = mInteractionIdCounter.getAndIncrement();
            final float windowScale = connection.findAccessibilityNodeInfosByViewTextInActiveWindow(
                    text, interactionId, this, Thread.currentThread().getId());
            // If the scale is zero the call has failed.
            if (windowScale > 0) {
                handleSameThreadMessageIfNeeded();
                List<AccessibilityNodeInfo> infos = getFindAccessibilityNodeInfosResultAndClear(
                        interactionId);
                finalizeAccessibilityNodeInfos(infos, connection, windowScale);
                return infos;
            }
        } catch (RemoteException re) {
            /* ignore */
        }
        return null;
    }

    /**
     * Finds {@link AccessibilityNodeInfo}s by View text. The match is case
     * insensitive containment. The search is performed in the window whose
     * id is specified and starts from the View whose accessibility id is
     * specified.
     *
     * @param connection A connection for interacting with the system.
     * @param text The searched text.
     * @param accessibilityWindowId A unique window id.
     * @param accessibilityViewId A unique View accessibility id from where to start the search.
     *        Use {@link android.view.View#NO_ID} to start from the root.
     * @return A list of found {@link AccessibilityNodeInfo}s.
     */
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewText(
            IAccessibilityServiceConnection connection, String text, int accessibilityWindowId,
            int accessibilityViewId) {
        try {
            final int interactionId = mInteractionIdCounter.getAndIncrement();
            final float windowScale = connection.findAccessibilityNodeInfosByViewText(text,
                    accessibilityWindowId, accessibilityViewId, interactionId, this,
                    Thread.currentThread().getId());
            // If the scale is zero the call has failed.
            if (windowScale > 0) {
                handleSameThreadMessageIfNeeded();
                List<AccessibilityNodeInfo> infos = getFindAccessibilityNodeInfosResultAndClear(
                        interactionId);
                finalizeAccessibilityNodeInfos(infos, connection, windowScale);
                return infos;
            }
        } catch (RemoteException re) {
            /* ignore */
        }
        return Collections.emptyList();
    }

    /**
     * Performs an accessibility action on an {@link AccessibilityNodeInfo}.
     *
     * @param connection A connection for interacting with the system.
     * @param accessibilityWindowId The id of the window.
     * @param accessibilityViewId A unique View accessibility id.
     * @param action The action to perform.
     * @return Whether the action was performed.
     */
    public boolean performAccessibilityAction(IAccessibilityServiceConnection connection,
            int accessibilityWindowId, int accessibilityViewId, int action) {
        try {
            final int interactionId = mInteractionIdCounter.getAndIncrement();
            final boolean success = connection.performAccessibilityAction(
                    accessibilityWindowId, accessibilityViewId, action, interactionId, this,
                    Thread.currentThread().getId());
            if (success) {
                handleSameThreadMessageIfNeeded();
                return getPerformAccessibilityActionResult(interactionId);
            }
        } catch (RemoteException re) {
            /* ignore */
        }
        return false;
    }

    /**
     * Gets the the result of an async request that returns an {@link AccessibilityNodeInfo}.
     *
     * @param interactionId The interaction id to match the result with the request.
     * @return The result {@link AccessibilityNodeInfo}.
     */
    private AccessibilityNodeInfo getFindAccessibilityNodeInfoResultAndClear(int interactionId) {
        synchronized (mInstanceLock) {
            final boolean success = waitForResultTimedLocked(interactionId);
            AccessibilityNodeInfo result = success ? mFindAccessibilityNodeInfoResult : null;
            clearResultLocked();
            return result;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setFindAccessibilityNodeInfoResult(AccessibilityNodeInfo info,
                int interactionId) {
        synchronized (mInstanceLock) {
            if (interactionId > mInteractionId) {
                mFindAccessibilityNodeInfoResult = info;
                mInteractionId = interactionId;
            }
            mInstanceLock.notifyAll();
        }
    }

    /**
     * Gets the the result of an async request that returns {@link AccessibilityNodeInfo}s.
     *
     * @param interactionId The interaction id to match the result with the request.
     * @return The result {@link AccessibilityNodeInfo}s.
     */
    private List<AccessibilityNodeInfo> getFindAccessibilityNodeInfosResultAndClear(
                int interactionId) {
        synchronized (mInstanceLock) {
            final boolean success = waitForResultTimedLocked(interactionId);
            List<AccessibilityNodeInfo> result = success ? mFindAccessibilityNodeInfosResult : null;
            clearResultLocked();
            return result;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setFindAccessibilityNodeInfosResult(List<AccessibilityNodeInfo> infos,
                int interactionId) {
        synchronized (mInstanceLock) {
            if (interactionId > mInteractionId) {
                mFindAccessibilityNodeInfosResult = infos;
                mInteractionId = interactionId;
            }
            mInstanceLock.notifyAll();
        }
    }

    /**
     * Gets the result of a request to perform an accessibility action.
     *
     * @param interactionId The interaction id to match the result with the request.
     * @return Whether the action was performed.
     */
    private boolean getPerformAccessibilityActionResult(int interactionId) {
        synchronized (mInstanceLock) {
            final boolean success = waitForResultTimedLocked(interactionId);
            final boolean result = success ? mPerformAccessibilityActionResult : false;
            clearResultLocked();
            return result;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setPerformAccessibilityActionResult(boolean succeeded, int interactionId) {
        synchronized (mInstanceLock) {
            if (interactionId > mInteractionId) {
                mPerformAccessibilityActionResult = succeeded;
                mInteractionId = interactionId;
            }
            mInstanceLock.notifyAll();
        }
    }

    /**
     * Clears the result state.
     */
    private void clearResultLocked() {
        mInteractionId = -1;
        mFindAccessibilityNodeInfoResult = null;
        mFindAccessibilityNodeInfosResult = null;
        mPerformAccessibilityActionResult = false;
    }

    /**
     * Waits up to a given bound for a result of a request and returns it.
     *
     * @param interactionId The interaction id to match the result with the request.
     * @return Whether the result was received.
     */
    private boolean waitForResultTimedLocked(int interactionId) {
        long waitTimeMillis = TIMEOUT_INTERACTION_MILLIS;
        final long startTimeMillis = SystemClock.uptimeMillis();
        while (true) {
            try {
                if (mInteractionId == interactionId) {
                    return true;
                }
                if (mInteractionId > interactionId) {
                    return false;
                }
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                waitTimeMillis = TIMEOUT_INTERACTION_MILLIS - elapsedTimeMillis;
                if (waitTimeMillis <= 0) {
                    return false;
                }
                mInstanceLock.wait(waitTimeMillis);
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
    }

    /**
     * Applies compatibility scale to the info bounds if it is not equal to one.
     *
     * @param info The info whose bounds to scale.
     * @param scale The scale to apply.
     */
    private void applyCompatibilityScaleIfNeeded(AccessibilityNodeInfo info, float scale) {
        if (scale == 1.0f) {
            return;
        }
        Rect bounds = mTempBounds;
        info.getBoundsInParent(bounds);
        bounds.scale(scale);
        info.setBoundsInParent(bounds);

        info.getBoundsInScreen(bounds);
        bounds.scale(scale);
        info.setBoundsInScreen(bounds);
    }

    /**
     * Handles the message stored if the interacted and interacting
     * threads are the same otherwise this is a NOP.
     */
    private void handleSameThreadMessageIfNeeded() {
        Message sameProcessMessage = getSameProcessMessageAndClear();
        if (sameProcessMessage != null) {
            sameProcessMessage.getTarget().handleMessage(sameProcessMessage);
        }
    }

    /**
     * Finalize an {@link AccessibilityNodeInfo} before passing it to the client.
     *
     * @param info The info.
     * @param connection The current connection to the system.
     * @param windowScale The source window compatibility scale.
     */
    private void finalizeAccessibilityNodeInfo(AccessibilityNodeInfo info,
            IAccessibilityServiceConnection connection, float windowScale) {
        if (info != null) {
            applyCompatibilityScaleIfNeeded(info, windowScale);
            info.setConnection(connection);
            info.setSealed(true);
        }
    }

    /**
     * Finalize {@link AccessibilityNodeInfo}s before passing them to the client.
     *
     * @param infos The {@link AccessibilityNodeInfo}s.
     * @param connection The current connection to the system.
     * @param windowScale The source window compatibility scale.
     */
    private void finalizeAccessibilityNodeInfos(List<AccessibilityNodeInfo> infos,
            IAccessibilityServiceConnection connection, float windowScale) {
        if (infos != null) {
            final int infosCount = infos.size();
            for (int i = 0; i < infosCount; i++) {
                AccessibilityNodeInfo info = infos.get(i);
                finalizeAccessibilityNodeInfo(info, connection, windowScale);
            }
        }
    }

    /**
     * Gets the message stored if the interacted and interacting
     * threads are the same.
     *
     * @return The message.
     */
    private Message getSameProcessMessageAndClear() {
        synchronized (mInstanceLock) {
            Message result = mSameThreadMessage;
            mSameThreadMessage = null;
            return result;
        }
    }
}
