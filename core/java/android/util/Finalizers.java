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

package android.util;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;

/**
 * This class can be used to implement reliable finalizers.
 * 
 * @hide
 */
public final class Finalizers {
    private static final String LOG_TAG = "Finalizers";
    
    private static final Object[] sLock = new Object[0];
    private static boolean sInit;
    private static Reclaimer sReclaimer;

    /**
     * Subclass of PhantomReference used to reclaim resources.
     */
    public static abstract class ReclaimableReference<T> extends PhantomReference<T> {
        public ReclaimableReference(T r, ReferenceQueue<Object> q) {
            super(r, q);
        }
        
        public abstract void reclaim();
    }

    /**
     * Returns the queue used to reclaim ReclaimableReferences.
     * 
     * @return A reference queue or null before initialization
     */
    public static ReferenceQueue<Object> getQueue() {
        synchronized (sLock) {
            if (!sInit) {
                return null;
            }
            if (!sReclaimer.isRunning()) {
                sReclaimer = new Reclaimer(sReclaimer.mQueue);
                sReclaimer.start();
            }
            return sReclaimer.mQueue;
        }
    }

    /**
     * Invoked by Zygote. Don't touch!
     */
    public static void init() {
        synchronized (sLock) {
            if (!sInit && sReclaimer == null) {
                sReclaimer = new Reclaimer();
                sReclaimer.start();
                sInit = true;
            }
        }
    }
    
    private static class Reclaimer extends Thread {
        ReferenceQueue<Object> mQueue;

        private volatile boolean mRunning = false;

        Reclaimer() {
            this(new ReferenceQueue<Object>());
        }

        Reclaimer(ReferenceQueue<Object> queue) {
            super("Reclaimer");
            setDaemon(true);
            mQueue = queue;            
        }

        @Override
        public void start() {
            mRunning = true;
            super.start();
        }

        boolean isRunning() {
            return mRunning;
        }

        @SuppressWarnings({"InfiniteLoopStatement"})
        @Override
        public void run() {
            try {
                while (true) {
                    try {
                        cleanUp(mQueue.remove());
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Reclaimer thread exiting: ", e);
            } finally {
                mRunning = false;
            }
        }

        private void cleanUp(Reference<?> reference) {
            do {
                reference.clear();
                ((ReclaimableReference<?>) reference).reclaim();
            } while ((reference = mQueue.poll()) != null);
        }
    }
}
