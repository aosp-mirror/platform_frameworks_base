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

import android.util.Log;

import com.android.internal.os.RuntimeInit;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;

public abstract class FileObserver {
    public static final int ACCESS = 0x00000001; /* File was accessed */
    public static final int MODIFY = 0x00000002; /* File was modified */
    public static final int ATTRIB = 0x00000004; /* Metadata changed */
    public static final int CLOSE_WRITE = 0x00000008; /*  Writtable file was  closed */
    public static final int CLOSE_NOWRITE = 0x00000010; /* Unwrittable file closed */
    public static final int OPEN = 0x00000020; /* File was opened */
    public static final int MOVED_FROM = 0x00000040; /* File was moved from X */
    public static final int MOVED_TO = 0x00000080; /* File was moved to Y */
    public static final int CREATE = 0x00000100; /* Subfile was created */
    public static final int DELETE = 0x00000200; /* Subfile was deleted */
    public static final int DELETE_SELF = 0x00000400; /* Self was deleted */
    public static final int MOVE_SELF = 0x00000800; /* Self was moved */
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
        FileObserver observer;

        synchronized (m_observers) {
            WeakReference weak = m_observers.get(wfd);
            observer = (FileObserver) weak.get();
            if (observer == null) {
                m_observers.remove(wfd);
            }
        }

        // ...then call out to the observer without the sync lock held
        if (observer != null) {
            try {
                observer.onEvent(mask, path);
            } catch (Throwable throwable) {
                Log.e(LOG_TAG, "Unhandled throwable " + throwable.toString() + 
                        " (returned by observer " + observer + ")", throwable);
                RuntimeInit.crash("FileObserver", throwable);
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

    public FileObserver(String path) {
	this(path, ALL_EVENTS);
    }

    public FileObserver(String path, int mask) {
	m_path = path;
	m_mask = mask;
	m_descriptor = -1;
    }

    protected void finalize() {
	stopWatching();
    }

    public void startWatching() {
	if (m_descriptor < 0) {
	    m_descriptor = s_observerThread.startWatching(m_path, m_mask, this);
	}
    }

    public void stopWatching() {
	if (m_descriptor >= 0) {
	    s_observerThread.stopWatching(m_descriptor);
	    m_descriptor = -1;
	}
    }

    public abstract void onEvent(int event, String path);
}
