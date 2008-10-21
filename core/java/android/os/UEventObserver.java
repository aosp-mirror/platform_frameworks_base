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

import java.util.ArrayList;
import java.util.HashMap;

/**
 * UEventObserver is an abstract class that receives UEvent's from the kernel.<p>
 *
 * Subclass UEventObserver, implementing onUEvent(UEvent event), then call
 * startObserving() with a match string. The UEvent thread will then call your
 * onUEvent() method when a UEvent occurs that contains your match string.<p>
 *
 * Call stopObserving() to stop receiving UEvent's.<p>
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
    private static final String TAG = UEventObserver.class.getSimpleName();

    /**
     * Representation of a UEvent.
     */
    static public class UEvent {
        // collection of key=value pairs parsed from the uevent message
        public HashMap<String,String> mMap = new HashMap<String,String>();

        public UEvent(String message) {
            int offset = 0;
            int length = message.length();

            while (offset < length) {
                int equals = message.indexOf('=', offset);
                int at = message.indexOf(0, offset);
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

    private static UEventThread sThread;
    private static boolean sThreadStarted = false;

    private static class UEventThread extends Thread {
        /** Many to many mapping of string match to observer.
         *  Multimap would be better, but not available in android, so use
         *  an ArrayList where even elements are the String match and odd
         *  elements the corresponding UEventObserver observer */
        private ArrayList<Object> mObservers = new ArrayList<Object>();
        
        UEventThread() {
            super("UEventObserver");
        }
        
        public void run() {
            native_setup();

            byte[] buffer = new byte[1024];
            int len;
            while (true) {
                len = next_event(buffer);
                if (len > 0) {
                    String bufferStr = new String(buffer, 0, len);  // easier to search a String
                    synchronized (mObservers) {
                        for (int i = 0; i < mObservers.size(); i += 2) {
                            if (bufferStr.indexOf((String)mObservers.get(i)) != -1) {
                                ((UEventObserver)mObservers.get(i+1))
                                        .onUEvent(new UEvent(bufferStr));
                            }
                        }
                    }
                }
            }
        }
        public void addObserver(String match, UEventObserver observer) {
            synchronized(mObservers) {
                mObservers.add(match);
                mObservers.add(observer);
            }
        }
        /** Removes every key/value pair where value=observer from mObservers */
        public void removeObserver(UEventObserver observer) {
            synchronized(mObservers) {
                boolean found = true;
                while (found) {
                    found = false;
                    for (int i = 0; i < mObservers.size(); i += 2) {
                        if (mObservers.get(i+1) == observer) {
                            mObservers.remove(i+1);
                            mObservers.remove(i);
                            found = true;
                            break;
                        }
                    }
                }
            }
        }
    }

    private static native void native_setup();
    private static native int next_event(byte[] buffer);

    private static final synchronized void ensureThreadStarted() {
        if (sThreadStarted == false) {
            sThread = new UEventThread();
            sThread.start();
            sThreadStarted = true;
        }
    }

    /**
     * Begin observation of UEvent's.<p>
     * This method will cause the UEvent thread to start if this is the first
     * invocation of startObserving in this process.<p>
     * Once called, the UEvent thread will call onUEvent() when an incoming
     * UEvent matches the specified string.<p>
     * This method can be called multiple times to register multiple matches.
     * Only one call to stopObserving is required even with multiple registered
     * matches.
     * @param match A substring of the UEvent to match. Use "" to match all
     *              UEvent's
     */
    public final synchronized void startObserving(String match) {
        ensureThreadStarted();
        sThread.addObserver(match, this);
    }

    /**
     * End observation of UEvent's.<p>
     * This process's UEvent thread will never call onUEvent() on this
     * UEventObserver after this call. Repeated calls have no effect.
     */
    public final synchronized void stopObserving() {
        sThread.removeObserver(this);
    }

    /**
     * Subclasses of UEventObserver should override this method to handle
     * UEvents.
     */
    public abstract void onUEvent(UEvent event);

    protected void finalize() throws Throwable {
        try {
            stopObserving();
        } finally {
            super.finalize();
        }
    }
}
