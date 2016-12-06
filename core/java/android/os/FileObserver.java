/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.annotation.Nullable;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.HashMap;

/**
 * Monitors files (using <a href="http://en.wikipedia.org/wiki/Inotify">inotify</a>)
 * to fire an event after files are accessed or changed by by any process on
 * the device (including this one).  FileObserver is an abstract class;
 * subclasses must implement the event handler {@link #onEvent(int, String)}.
 *
 * <p>Each FileObserver instance monitors a single file or directory.
 * If a directory is monitored, events will be triggered for all files and
 * subdirectories inside the monitored directory.</p>
 *
 * <p>An event mask is used to specify which changes or actions to report.
 * Event type constants are used to describe the possible changes in the
 * event mask as well as what actually happened in event callbacks.</p>
 *
 * <p class="caution"><b>Warning</b>: If a FileObserver is garbage collected, it
 * will stop sending events.  To ensure you keep receiving events, you must
 * keep a reference to the FileObserver instance from some other live object.</p>
 */
public abstract class FileObserver {
    /** Event type: Data was read from a file */
    public static final int ACCESS = 0x00000001;
    /** Event type: Data was written to a file */
    public static final int MODIFY = 0x00000002;
    /** Event type: Metadata (permissions, owner, timestamp) was changed explicitly */
    public static final int ATTRIB = 0x00000004;
    /** Event type: Someone had a file or directory open for writing, and closed it */
    public static final int CLOSE_WRITE = 0x00000008;
    /** Event type: Someone had a file or directory open read-only, and closed it */
    public static final int CLOSE_NOWRITE = 0x00000010;
    /** Event type: A file or directory was opened */
    public static final int OPEN = 0x00000020;
    /** Event type: A file or subdirectory was moved from the monitored directory */
    public static final int MOVED_FROM = 0x00000040;
    /** Event type: A file or subdirectory was moved to the monitored directory */
    public static final int MOVED_TO = 0x00000080;
    /** Event type: A new file or subdirectory was created under the monitored directory */
    public static final int CREATE = 0x00000100;
    /** Event type: A file was deleted from the monitored directory */
    public static final int DELETE = 0x00000200;
    /** Event type: The monitored file or directory was deleted; monitoring effectively stops */
    public static final int DELETE_SELF = 0x00000400;
    /** Event type: The monitored file or directory was moved; monitoring continues */
    public static final int MOVE_SELF = 0x00000800;

    /** Event mask: All valid event types, combined */
    public static final int ALL_EVENTS = ACCESS | MODIFY | ATTRIB | CLOSE_WRITE
            | CLOSE_NOWRITE | OPEN | MOVED_FROM | MOVED_TO | DELETE | CREATE
            | DELETE_SELF | MOVE_SELF;

    private static final String LOG_TAG = "FileObserver";

    private static class ObserverThread extends Thread {
        private HashMap<Integer, WeakReference> m_observers = new HashMap<Integer, WeakReference>();
        private int m_fd;

        public ObserverThread() {
            super("FileObserver");
            m_fd = init();
        }

        public void run() {
            observe(m_fd);
        }

        public int startWatching(String path, int mask, FileObserver observer) {
            int wfd = startWatching(m_fd, path, mask);

            Integer i = new Integer(wfd);
            if (wfd >= 0) {
                synchronized (m_observers) {
                    m_observers.put(i, new WeakReference(observer));
                }
            }

            return i;
        }

        public void stopWatching(int descriptor) {
            stopWatching(m_fd, descriptor);
        }

        public void onEvent(int wfd, int mask, String path) {
            // look up our observer, fixing up the map if necessary...
            FileObserver observer = null;

            synchronized (m_observers) {
                WeakReference weak = m_observers.get(wfd);
                if (weak != null) {  // can happen with lots of events from a dead wfd
                    observer = (FileObserver) weak.get();
                    if (observer == null) {
                        m_observers.remove(wfd);
                    }
                }
            }

            // ...then call out to the observer without the sync lock held
            if (observer != null) {
                try {
                    observer.onEvent(mask, path);
                } catch (Throwable throwable) {
                    Log.wtf(LOG_TAG, "Unhandled exception in FileObserver " + observer, throwable);
                }
            }
        }

        private native int init();
        private native void observe(int fd);
        private native int startWatching(int fd, String path, int mask);
        private native void stopWatching(int fd, int wfd);
    }

    private static ObserverThread s_observerThread;

    static {
        s_observerThread = new ObserverThread();
        s_observerThread.start();
    }

    // instance
    private String m_path;
    private Integer m_descriptor;
    private int m_mask;

    /**
     * Equivalent to FileObserver(path, FileObserver.ALL_EVENTS).
     */
    public FileObserver(String path) {
        this(path, ALL_EVENTS);
    }

    /**
     * Create a new file observer for a certain file or directory.
     * Monitoring does not start on creation!  You must call
     * {@link #startWatching()} before you will receive events.
     *
     * @param path The file or directory to monitor
     * @param mask The event or events (added together) to watch for
     */
    public FileObserver(String path, int mask) {
        m_path = path;
        m_mask = mask;
        m_descriptor = -1;
    }

    protected void finalize() {
        stopWatching();
    }

    /**
     * Start watching for events.  The monitored file or directory must exist at
     * this time, or else no events will be reported (even if it appears later).
     * If monitoring is already started, this call has no effect.
     */
    public void startWatching() {
        if (m_descriptor < 0) {
            m_descriptor = s_observerThread.startWatching(m_path, m_mask, this);
        }
    }

    /**
     * Stop watching for events.  Some events may be in process, so events
     * may continue to be reported even after this method completes.  If
     * monitoring is already stopped, this call has no effect.
     */
    public void stopWatching() {
        if (m_descriptor >= 0) {
            s_observerThread.stopWatching(m_descriptor);
            m_descriptor = -1;
        }
    }

    /**
     * The event handler, which must be implemented by subclasses.
     *
     * <p class="note">This method is invoked on a special FileObserver thread.
     * It runs independently of any threads, so take care to use appropriate
     * synchronization!  Consider using {@link Handler#post(Runnable)} to shift
     * event handling work to the main thread to avoid concurrency problems.</p>
     *
     * <p>Event handlers must not throw exceptions.</p>
     *
     * @param event The type of event which happened
     * @param path The path, relative to the main monitored file or directory,
     *     of the file or directory which triggered the event.  This value can
     *     be {@code null} for certain events, such as {@link #MOVE_SELF}.
     */
    public abstract void onEvent(int event, @Nullable String path);
}
