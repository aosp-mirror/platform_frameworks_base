/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.os.Looper;
import android.os.MessageQueue;
import android.util.LongSparseArray;
import android.util.Pools.Pool;
import android.util.Pools.SimplePool;

import dalvik.system.CloseGuard;

import java.lang.ref.WeakReference;

/**
 * An input queue provides a mechanism for an application to receive incoming
 * input events.  Currently only usable from native code.
 */
public final class InputQueue {
    private final LongSparseArray<ActiveInputEvent> mActiveEventArray =
            new LongSparseArray<ActiveInputEvent>(20);
    private final Pool<ActiveInputEvent> mActiveInputEventPool =
            new SimplePool<ActiveInputEvent>(20);

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private long mPtr;

    private static native long nativeInit(WeakReference<InputQueue> weakQueue,
            MessageQueue messageQueue);
    private static native long nativeSendKeyEvent(long ptr, KeyEvent e, boolean preDispatch);
    private static native long nativeSendMotionEvent(long ptr, MotionEvent e);
    private static native void nativeDispose(long ptr);

    /** @hide */
    public InputQueue() {
        mPtr = nativeInit(new WeakReference<InputQueue>(this), Looper.myQueue());

        mCloseGuard.open("dispose");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            dispose(true);
        } finally {
            super.finalize();
        }
    }

    /** @hide */
    public void dispose() {
        dispose(false);
    }

    /** @hide */
    public void dispose(boolean finalized) {
        if (mCloseGuard != null) {
            if (finalized) {
                mCloseGuard.warnIfOpen();
            }
            mCloseGuard.close();
        }

        if (mPtr != 0) {
            nativeDispose(mPtr);
            mPtr = 0;
        }
    }

    /** @hide */
    public long getNativePtr() {
        return mPtr;
    }

    /** @hide */
    public void sendInputEvent(InputEvent e, Object token, boolean predispatch,
            FinishedInputEventCallback callback) {
        ActiveInputEvent event = obtainActiveInputEvent(token, callback);
        long id;
        if (e instanceof KeyEvent) {
            id = nativeSendKeyEvent(mPtr, (KeyEvent) e, predispatch);
        } else {
            id = nativeSendMotionEvent(mPtr, (MotionEvent) e);
        }
        mActiveEventArray.put(id, event);
    }

    private void finishInputEvent(long id, boolean handled) {
        int index = mActiveEventArray.indexOfKey(id);
        if (index >= 0) {
            ActiveInputEvent e = mActiveEventArray.valueAt(index);
            mActiveEventArray.removeAt(index);
            e.mCallback.onFinishedInputEvent(e.mToken, handled);
            recycleActiveInputEvent(e);
        }
    }

    private ActiveInputEvent obtainActiveInputEvent(Object token,
            FinishedInputEventCallback callback) {
        ActiveInputEvent e = mActiveInputEventPool.acquire();
        if (e == null) {
            e = new ActiveInputEvent();
        }
        e.mToken = token;
        e.mCallback = callback;
        return e;
    }

    private void recycleActiveInputEvent(ActiveInputEvent e) {
        e.recycle();
        mActiveInputEventPool.release(e);
    }

    private final class ActiveInputEvent {
        public Object mToken;
        public FinishedInputEventCallback mCallback;

        public void recycle() {
            mToken = null;
            mCallback = null;
        }
    }

    /**
     * Interface to receive notification of when an InputQueue is associated
     * and dissociated with a thread.
     */
    public static interface Callback {
        /**
         * Called when the given InputQueue is now associated with the
         * thread making this call, so it can start receiving events from it.
         */
        void onInputQueueCreated(InputQueue queue);

        /**
         * Called when the given InputQueue is no longer associated with
         * the thread and thus not dispatching events.
         */
        void onInputQueueDestroyed(InputQueue queue);
    }

    /** @hide */
    public static interface FinishedInputEventCallback {
        void onFinishedInputEvent(Object token, boolean handled);
    }

}
