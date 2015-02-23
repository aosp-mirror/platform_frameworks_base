/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.os;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * UEventObserver is an abstract class that receives UEvents from the kernel.<p>
 *
 * Subclass UEventObserver, implementing onUEvent(UEvent event), then call
 * startObserving() with a match string. The UEvent thread will then call your
 * onUEvent() method when a UEvent occurs that contains your match string.<p>
 *
 * Call stopObserving() to stop receiving UEvents.<p>
 *
 * There is only one UEvent thread per process, even if that process has
 * multiple UEventObserver subclass instances. The UEvent thread starts when
 * the startObserving() is called for the first time in that process. Once
 * started the UEvent thread will not stop (although it can stop notifying
 * UEventObserver's via stopObserving()).<p>
 *
 * @hide
*/
public abstract class UEventObserver {
    private static final String TAG = "UEventObserver";
    private static final boolean DEBUG = false;

    private static UEventThread sThread;

    private static native void nativeSetup();
    private static native String nativeWaitForNextEvent();
    private static native void nativeAddMatch(String match);
    private static native void nativeRemoveMatch(String match);

    public UEventObserver() {
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            stopObserving();
        } finally {
            super.finalize();
        }
    }

    private static UEventThread getThread() {
        synchronized (UEventObserver.class) {
            if (sThread == null) {
                sThread = new UEventThread();
                sThread.start();
            }
            return sThread;
        }
    }

    private static UEventThread peekThread() {
        synchronized (UEventObserver.class) {
            return sThread;
        }
    }

    /**
     * Begin observation of UEvents.<p>
     * This method will cause the UEvent thread to start if this is the first
     * invocation of startObserving in this process.<p>
     * Once called, the UEvent thread will call onUEvent() when an incoming
     * UEvent matches the specified string.<p>
     * This method can be called multiple times to register multiple matches.
     * Only one call to stopObserving is required even with multiple registered
     * matches.
     *
     * @param match A substring of the UEvent to match.  Try to be as specific
     * as possible to avoid incurring unintended additional cost from processing
     * irrelevant messages.  Netlink messages can be moderately high bandwidth and
     * are expensive to parse.  For example, some devices may send one netlink message
     * for each vsync period.
     */
    public final void startObserving(String match) {
        if (match == null || match.isEmpty()) {
            throw new IllegalArgumentException("match substring must be non-empty");
        }

        final UEventThread t = getThread();
        t.addObserver(match, this);
    }

    /**
     * End observation of UEvents.<p>
     * This process's UEvent thread will never call onUEvent() on this
     * UEventObserver after this call. Repeated calls have no effect.
     */
    public final void stopObserving() {
        final UEventThread t = getThread();
        if (t != null) {
            t.removeObserver(this);
        }
    }

    /**
     * Subclasses of UEventObserver should override this method to handle
     * UEvents.
     */
    public abstract void onUEvent(UEvent event);

    /**
     * Representation of a UEvent.
     */
    public static final class UEvent {
        // collection of key=value pairs parsed from the uevent message
        private final HashMap<String,String> mMap = new HashMap<String,String>();

        public UEvent(String message) {
            int offset = 0;
            int length = message.length();

            while (offset < length) {
                int equals = message.indexOf('=', offset);
                int at = message.indexOf('\0', offset);
                if (at < 0) break;

                if (equals > offset && equals < at) {
                    // key is before the equals sign, and value is after
                    mMap.put(message.substring(offset, equals),
                            message.substring(equals + 1, at));
                }

                offset = at + 1;
            }
        }

        public String get(String key) {
            return mMap.get(key);
        }

        public String get(String key, String defaultValue) {
            String result = mMap.get(key);
            return (result == null ? defaultValue : result);
        }

        public String toString() {
            return mMap.toString();
        }
    }

    private static final class UEventThread extends Thread {
        /** Many to many mapping of string match to observer.
         *  Multimap would be better, but not available in android, so use
         *  an ArrayList where even elements are the String match and odd
         *  elements the corresponding UEventObserver observer */
        private final ArrayList<Object> mKeysAndObservers = new ArrayList<Object>();

        private final ArrayList<UEventObserver> mTempObserversToSignal =
                new ArrayList<UEventObserver>();

        public UEventThread() {
            super("UEventObserver");
        }

        @Override
        public void run() {
            nativeSetup();

            while (true) {
                String message = nativeWaitForNextEvent();
                if (message != null) {
                    if (DEBUG) {
                        Log.d(TAG, message);
                    }
                    sendEvent(message);
                }
            }
        }

        private void sendEvent(String message) {
            synchronized (mKeysAndObservers) {
                final int N = mKeysAndObservers.size();
                for (int i = 0; i < N; i += 2) {
                    final String key = (String)mKeysAndObservers.get(i);
                    if (message.contains(key)) {
                        final UEventObserver observer =
                                (UEventObserver)mKeysAndObservers.get(i + 1);
                        mTempObserversToSignal.add(observer);
                    }
                }
            }

            if (!mTempObserversToSignal.isEmpty()) {
                final UEvent event = new UEvent(message);
                final int N = mTempObserversToSignal.size();
                for (int i = 0; i < N; i++) {
                    final UEventObserver observer = mTempObserversToSignal.get(i);
                    observer.onUEvent(event);
                }
                mTempObserversToSignal.clear();
            }
        }

        public void addObserver(String match, UEventObserver observer) {
            synchronized (mKeysAndObservers) {
                mKeysAndObservers.add(match);
                mKeysAndObservers.add(observer);
                nativeAddMatch(match);
            }
        }

        /** Removes every key/value pair where value=observer from mObservers */
        public void removeObserver(UEventObserver observer) {
            synchronized (mKeysAndObservers) {
                for (int i = 0; i < mKeysAndObservers.size(); ) {
                    if (mKeysAndObservers.get(i + 1) == observer) {
                        mKeysAndObservers.remove(i + 1);
                        final String match = (String)mKeysAndObservers.remove(i);
                        nativeRemoveMatch(match);
                    } else {
                        i += 2;
                    }
                }
            }
        }
    }
}
