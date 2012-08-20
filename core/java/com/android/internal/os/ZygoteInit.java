/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.os;

import static libcore.io.OsConstants.S_IRWXG;
import static libcore.io.OsConstants.S_IRWXO;

import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.net.LocalServerSocket;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.Log;

import dalvik.system.VMRuntime;
import dalvik.system.Zygote;

import libcore.io.IoUtils;
import libcore.io.Libcore;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/**
 * Startup class for the zygote process.
 *
 * Pre-initializes some classes, and then waits for commands on a UNIX domain
 * socket. Based on these commands, forks off child processes that inherit
 * the initial state of the VM.
 *
 * Please see {@link ZygoteConnection.Arguments} for documentation on the
 * client protocol.
 *
 * @hide
 */
public class ZygoteInit {

    private static final String TAG = "Zygote";

    private static final String ANDROID_SOCKET_ENV = "ANDROID_SOCKET_zygote";

    private static final int LOG_BOOT_PROGRESS_PRELOAD_START = 3020;
    private static final int LOG_BOOT_PROGRESS_PRELOAD_END = 3030;

    /** when preloading, GC after allocating this many bytes */
    private static final int PRELOAD_GC_THRESHOLD = 50000;

    public static final String USAGE_STRING =
            " <\"start-system-server\"|\"\" for startSystemServer>";

    private static LocalServerSocket sServerSocket;

    /**
     * Used to pre-load resources.  We hold a global reference on it so it
     * never gets destroyed.
     */
    private static Resources mResources;

    /**
     * The number of times that the main Zygote loop
     * should run before calling gc() again.
     */
    static final int GC_LOOP_COUNT = 10;

    /**
     * If true, zygote forks for each peer. If false, a select loop is used
     * inside a single process. The latter is preferred.
     */
    private static final boolean ZYGOTE_FORK_MODE = false;

    /**
     * The name of a resource file that contains classes to preload.
     */
    private static final String PRELOADED_CLASSES = "preloaded-classes";

    /** Controls whether we should preload resources during zygote init. */
    private static final boolean PRELOAD_RESOURCES = true;

    /**
     * Invokes a static "main(argv[]) method on class "className".
     * Converts various failing exceptions into RuntimeExceptions, with
     * the assumption that they will then cause the VM instance to exit.
     *
     * @param loader class loader to use
     * @param className Fully-qualified class name
     * @param argv Argument vector for main()
     */
    static void invokeStaticMain(ClassLoader loader,
            String className, String[] argv)
            throws ZygoteInit.MethodAndArgsCaller {
        Class<?> cl;

        try {
            cl = loader.loadClass(className);
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException(
                    "Missing class when invoking static main " + className,
                    ex);
        }

        Method m;
        try {
            m = cl.getMethod("main", new Class[] { String[].class });
        } catch (NoSuchMethodException ex) {
            throw new RuntimeException(
                    "Missing static main on " + className, ex);
        } catch (SecurityException ex) {
            throw new RuntimeException(
                    "Problem getting static main on " + className, ex);
        }

        int modifiers = m.getModifiers();
        if (! (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers))) {
            throw new RuntimeException(
                    "Main method is not public and static on " + className);
        }

        /*
         * This throw gets caught in ZygoteInit.main(), which responds
         * by invoking the exception's run() method. This arrangement
         * clears up all the stack frames that were required in setting
         * up the process.
         */
        throw new ZygoteInit.MethodAndArgsCaller(m, argv);
    }

    /**
     * Registers a server socket for zygote command connections
     *
     * @throws RuntimeException when open fails
     */
    private static void registerZygoteSocket() {
        if (sServerSocket == null) {
            int fileDesc;
            try {
                String env = System.getenv(ANDROID_SOCKET_ENV);
                fileDesc = Integer.parseInt(env);
            } catch (RuntimeException ex) {
                throw new RuntimeException(
                        ANDROID_SOCKET_ENV + " unset or invalid", ex);
            }

            try {
                sServerSocket = new LocalServerSocket(
                        createFileDescriptor(fileDesc));
            } catch (IOException ex) {
                throw new RuntimeException(
                        "Error binding to local socket '" + fileDesc + "'", ex);
            }
        }
    }

    /**
     * Waits for and accepts a single command connection. Throws
     * RuntimeException on failure.
     */
    private static ZygoteConnection acceptCommandPeer() {
        try {
            return new ZygoteConnection(sServerSocket.accept());
        } catch (IOException ex) {
            throw new RuntimeException(
                    "IOException during accept()", ex);
        }
    }

    /**
     * Close and clean up zygote sockets. Called on shutdown and on the
     * child's exit path.
     */
    static void closeServerSocket() {
        try {
            if (sServerSocket != null) {
                sServerSocket.close();
            }
        } catch (IOException ex) {
            Log.e(TAG, "Zygote:  error closing sockets", ex);
        }

        sServerSocket = null;
    }

    private static final int UNPRIVILEGED_UID = 9999;
    private static final int UNPRIVILEGED_GID = 9999;

    private static final int ROOT_UID = 0;
    private static final int ROOT_GID = 0;

    /**
     * Sets effective user ID.
     */
    private static void setEffectiveUser(int uid) {
        int errno = setreuid(ROOT_UID, uid);
        if (errno != 0) {
            Log.e(TAG, "setreuid() failed. errno: " + errno);
        }
    }

    /**
     * Sets effective group ID.
     */
    private static void setEffectiveGroup(int gid) {
        int errno = setregid(ROOT_GID, gid);
        if (errno != 0) {
            Log.e(TAG, "setregid() failed. errno: " + errno);
        }
    }

    static void preload() {
        preloadClasses();
        preloadResources();
    }

    /**
     * Performs Zygote process initialization. Loads and initializes
     * commonly used classes.
     *
     * Most classes only cause a few hundred bytes to be allocated, but
     * a few will allocate a dozen Kbytes (in one case, 500+K).
     */
    private static void preloadClasses() {
        final VMRuntime runtime = VMRuntime.getRuntime();

        InputStream is = ClassLoader.getSystemClassLoader().getResourceAsStream(
                PRELOADED_CLASSES);
        if (is == null) {
            Log.e(TAG, "Couldn't find " + PRELOADED_CLASSES + ".");
        } else {
            Log.i(TAG, "Preloading classes...");
            long startTime = SystemClock.uptimeMillis();

            // Drop root perms while running static initializers.
            setEffectiveGroup(UNPRIVILEGED_GID);
            setEffectiveUser(UNPRIVILEGED_UID);

            // Alter the target heap utilization.  With explicit GCs this
            // is not likely to have any effect.
            float defaultUtilization = runtime.getTargetHeapUtilization();
            runtime.setTargetHeapUtilization(0.8f);

            // Start with a clean slate.
            System.gc();
            runtime.runFinalizationSync();
            Debug.startAllocCounting();

            try {
                BufferedReader br
                    = new BufferedReader(new InputStreamReader(is), 256);

                int count = 0;
                String line;
                while ((line = br.readLine()) != null) {
                    // Skip comments and blank lines.
                    line = line.trim();
                    if (line.startsWith("#") || line.equals("")) {
                        continue;
                    }

                    try {
                        if (false) {
                            Log.v(TAG, "Preloading " + line + "...");
                        }
                        Class.forName(line);
                        if (Debug.getGlobalAllocSize() > PRELOAD_GC_THRESHOLD) {
                            if (false) {
                                Log.v(TAG,
                                    " GC at " + Debug.getGlobalAllocSize());
                            }
                            System.gc();
                            runtime.runFinalizationSync();
                            Debug.resetGlobalAllocSize();
                        }
                        count++;
                    } catch (ClassNotFoundException e) {
                        Log.w(TAG, "Class not found for preloading: " + line);
                    } catch (Throwable t) {
                        Log.e(TAG, "Error preloading " + line + ".", t);
                        if (t instanceof Error) {
                            throw (Error) t;
                        }
                        if (t instanceof RuntimeException) {
                            throw (RuntimeException) t;
                        }
                        throw new RuntimeException(t);
                    }
                }

                Log.i(TAG, "...preloaded " + count + " classes in "
                        + (SystemClock.uptimeMillis()-startTime) + "ms.");
            } catch (IOException e) {
                Log.e(TAG, "Error reading " + PRELOADED_CLASSES + ".", e);
            } finally {
                IoUtils.closeQuietly(is);
                // Restore default.
                runtime.setTargetHeapUtilization(defaultUtilization);

                Debug.stopAllocCounting();

                // Bring back root. We'll need it later.
                setEffectiveUser(ROOT_UID);
                setEffectiveGroup(ROOT_GID);
            }
        }
    }

    /**
     * Load in commonly used resources, so they can be shared across
     * processes.
     *
     * These tend to be a few Kbytes, but are frequently in the 20-40K
     * range, and occasionally even larger.
     */
    private static void preloadResources() {
        final VMRuntime runtime = VMRuntime.getRuntime();

        Debug.startAllocCounting();
        try {
            System.gc();
            runtime.runFinalizationSync();
            mResources = Resources.getSystem();
            mResources.startPreloading();
            if (PRELOAD_RESOURCES) {
                Log.i(TAG, "Preloading resources...");

                long startTime = SystemClock.uptimeMillis();
                TypedArray ar = mResources.obtainTypedArray(
                        com.android.internal.R.array.preloaded_drawables);
                int N = preloadDrawables(runtime, ar);
                ar.recycle();
                Log.i(TAG, "...preloaded " + N + " resources in "
                        + (SystemClock.uptimeMillis()-startTime) + "ms.");

                startTime = SystemClock.uptimeMillis();
                ar = mResources.obtainTypedArray(
                        com.android.internal.R.array.preloaded_color_state_lists);
                N = preloadColorStateLists(runtime, ar);
                ar.recycle();
                Log.i(TAG, "...preloaded " + N + " resources in "
                        + (SystemClock.uptimeMillis()-startTime) + "ms.");
            }
            mResources.finishPreloading();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failure preloading resources", e);
        } finally {
            Debug.stopAllocCounting();
        }
    }

    private static int preloadColorStateLists(VMRuntime runtime, TypedArray ar) {
        int N = ar.length();
        for (int i=0; i<N; i++) {
            if (Debug.getGlobalAllocSize() > PRELOAD_GC_THRESHOLD) {
                if (false) {
                    Log.v(TAG, " GC at " + Debug.getGlobalAllocSize());
                }
                System.gc();
                runtime.runFinalizationSync();
                Debug.resetGlobalAllocSize();
            }
            int id = ar.getResourceId(i, 0);
            if (false) {
                Log.v(TAG, "Preloading resource #" + Integer.toHexString(id));
            }
            if (id != 0) {
                if (mResources.getColorStateList(id) == null) {
                    throw new IllegalArgumentException(
                            "Unable to find preloaded color resource #0x"
                            + Integer.toHexString(id)
                            + " (" + ar.getString(i) + ")");
                }
            }
        }
        return N;
    }


    private static int preloadDrawables(VMRuntime runtime, TypedArray ar) {
        int N = ar.length();
        for (int i=0; i<N; i++) {
            if (Debug.getGlobalAllocSize() > PRELOAD_GC_THRESHOLD) {
                if (false) {
                    Log.v(TAG, " GC at " + Debug.getGlobalAllocSize());
                }
                System.gc();
                runtime.runFinalizationSync();
                Debug.resetGlobalAllocSize();
            }
            int id = ar.getResourceId(i, 0);
            if (false) {
                Log.v(TAG, "Preloading resource #" + Integer.toHexString(id));
            }
            if (id != 0) {
                if (mResources.getDrawable(id) == null) {
                    throw new IllegalArgumentException(
                            "Unable to find preloaded drawable resource #0x"
                            + Integer.toHexString(id)
                            + " (" + ar.getString(i) + ")");
                }
            }
        }
        return N;
    }

    /**
     * Runs several special GCs to try to clean up a few generations of
     * softly- and final-reachable objects, along with any other garbage.
     * This is only useful just before a fork().
     */
    /*package*/ static void gc() {
        final VMRuntime runtime = VMRuntime.getRuntime();

        /* runFinalizationSync() lets finalizers be called in Zygote,
         * which doesn't have a HeapWorker thread.
         */
        System.gc();
        runtime.runFinalizationSync();
        System.gc();
        runtime.runFinalizationSync();
        System.gc();
        runtime.runFinalizationSync();
    }

    /**
     * Finish remaining work for the newly forked system server process.
     */
    private static void handleSystemServerProcess(
            ZygoteConnection.Arguments parsedArgs)
            throws ZygoteInit.MethodAndArgsCaller {

        closeServerSocket();

        // set umask to 0077 so new files and directories will default to owner-only permissions.
        Libcore.os.umask(S_IRWXG | S_IRWXO);

        if (parsedArgs.niceName != null) {
            Process.setArgV0(parsedArgs.niceName);
        }

        if (parsedArgs.invokeWith != null) {
            WrapperInit.execApplication(parsedArgs.invokeWith,
                    parsedArgs.niceName, parsedArgs.targetSdkVersion,
                    null, parsedArgs.remainingArgs);
        } else {
            /*
             * Pass the remaining arguments to SystemServer.
             */
            RuntimeInit.zygoteInit(parsedArgs.targetSdkVersion, parsedArgs.remainingArgs);
        }

        /* should never reach here */
    }

    /**
     * Prepare the arguments and fork for the system server process.
     */
    private static boolean startSystemServer()
            throws MethodAndArgsCaller, RuntimeException {
        /* Hardcoded command line to start the system server */
        String args[] = {
            "--setuid=1000",
            "--setgid=1000",
            "--setgroups=1001,1002,1003,1004,1005,1006,1007,1008,1009,1010,1018,3001,3002,3003,3006,3007",
            "--capabilities=130104352,130104352",
            "--runtime-init",
            "--nice-name=system_server",
            "com.android.server.SystemServer",
        };
        ZygoteConnection.Arguments parsedArgs = null;

        int pid;

        try {
            parsedArgs = new ZygoteConnection.Arguments(args);
            ZygoteConnection.applyDebuggerSystemProperty(parsedArgs);
            ZygoteConnection.applyInvokeWithSystemProperty(parsedArgs);

            /* Request to fork the system server process */
            pid = Zygote.forkSystemServer(
                    parsedArgs.uid, parsedArgs.gid,
                    parsedArgs.gids,
                    parsedArgs.debugFlags,
                    null,
                    parsedArgs.permittedCapabilities,
                    parsedArgs.effectiveCapabilities);
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException(ex);
        }

        /* For child process */
        if (pid == 0) {
            handleSystemServerProcess(parsedArgs);
        }

        return true;
    }

    public static void main(String argv[]) {
        try {
            // Start profiling the zygote initialization.
            SamplingProfilerIntegration.start();

            registerZygoteSocket();
            EventLog.writeEvent(LOG_BOOT_PROGRESS_PRELOAD_START,
                SystemClock.uptimeMillis());
            preload();
            EventLog.writeEvent(LOG_BOOT_PROGRESS_PRELOAD_END,
                SystemClock.uptimeMillis());

            // Finish profiling the zygote initialization.
            SamplingProfilerIntegration.writeZygoteSnapshot();

            // Do an initial gc to clean up after startup
            gc();

            // If requested, start system server directly from Zygote
            if (argv.length != 2) {
                throw new RuntimeException(argv[0] + USAGE_STRING);
            }

            if (argv[1].equals("start-system-server")) {
                startSystemServer();
            } else if (!argv[1].equals("")) {
                throw new RuntimeException(argv[0] + USAGE_STRING);
            }

            Log.i(TAG, "Accepting command socket connections");

            if (ZYGOTE_FORK_MODE) {
                runForkMode();
            } else {
                runSelectLoopMode();
            }

            closeServerSocket();
        } catch (MethodAndArgsCaller caller) {
            caller.run();
        } catch (RuntimeException ex) {
            Log.e(TAG, "Zygote died with exception", ex);
            closeServerSocket();
            throw ex;
        }
    }

    /**
     * Runs the zygote in accept-and-fork mode. In this mode, each peer
     * gets its own zygote spawner process. This code is retained for
     * reference only.
     *
     * @throws MethodAndArgsCaller in a child process when a main() should
     * be executed.
     */
    private static void runForkMode() throws MethodAndArgsCaller {
        while (true) {
            ZygoteConnection peer = acceptCommandPeer();

            int pid;

            pid = Zygote.fork();

            if (pid == 0) {
                // The child process should handle the peer requests

                // The child does not accept any more connections
                try {
                    sServerSocket.close();
                } catch (IOException ex) {
                    Log.e(TAG, "Zygote Child: error closing sockets", ex);
                } finally {
                    sServerSocket = null;
                }

                peer.run();
                break;
            } else if (pid > 0) {
                peer.closeSocket();
            } else {
                throw new RuntimeException("Error invoking fork()");
            }
        }
    }

    /**
     * Runs the zygote process's select loop. Accepts new connections as
     * they happen, and reads commands from connections one spawn-request's
     * worth at a time.
     *
     * @throws MethodAndArgsCaller in a child process when a main() should
     * be executed.
     */
    private static void runSelectLoopMode() throws MethodAndArgsCaller {
        ArrayList<FileDescriptor> fds = new ArrayList();
        ArrayList<ZygoteConnection> peers = new ArrayList();
        FileDescriptor[] fdArray = new FileDescriptor[4];

        fds.add(sServerSocket.getFileDescriptor());
        peers.add(null);

        int loopCount = GC_LOOP_COUNT;
        while (true) {
            int index;

            /*
             * Call gc() before we block in select().
             * It's work that has to be done anyway, and it's better
             * to avoid making every child do it.  It will also
             * madvise() any free memory as a side-effect.
             *
             * Don't call it every time, because walking the entire
             * heap is a lot of overhead to free a few hundred bytes.
             */
            if (loopCount <= 0) {
                gc();
                loopCount = GC_LOOP_COUNT;
            } else {
                loopCount--;
            }


            try {
                fdArray = fds.toArray(fdArray);
                index = selectReadable(fdArray);
            } catch (IOException ex) {
                throw new RuntimeException("Error in select()", ex);
            }

            if (index < 0) {
                throw new RuntimeException("Error in select()");
            } else if (index == 0) {
                ZygoteConnection newPeer = acceptCommandPeer();
                peers.add(newPeer);
                fds.add(newPeer.getFileDesciptor());
            } else {
                boolean done;
                done = peers.get(index).runOnce();

                if (done) {
                    peers.remove(index);
                    fds.remove(index);
                }
            }
        }
    }

    /**
     * The Linux syscall "setreuid()"
     * @param ruid real uid
     * @param euid effective uid
     * @return 0 on success, non-zero errno on fail
     */
    static native int setreuid(int ruid, int euid);

    /**
     * The Linux syscall "setregid()"
     * @param rgid real gid
     * @param egid effective gid
     * @return 0 on success, non-zero errno on fail
     */
    static native int setregid(int rgid, int egid);

    /**
     * Invokes the linux syscall "setpgid"
     *
     * @param pid pid to change
     * @param pgid new process group of pid
     * @return 0 on success or non-zero errno on fail
     */
    static native int setpgid(int pid, int pgid);

    /**
     * Invokes the linux syscall "getpgid"
     *
     * @param pid pid to query
     * @return pgid of pid in question
     * @throws IOException on error
     */
    static native int getpgid(int pid) throws IOException;

    /**
     * Invokes the syscall dup2() to copy the specified descriptors into
     * stdin, stdout, and stderr. The existing stdio descriptors will be
     * closed and errors during close will be ignored. The specified
     * descriptors will also remain open at their original descriptor numbers,
     * so the caller may want to close the original descriptors.
     *
     * @param in new stdin
     * @param out new stdout
     * @param err new stderr
     * @throws IOException
     */
    static native void reopenStdio(FileDescriptor in,
            FileDescriptor out, FileDescriptor err) throws IOException;

    /**
     * Toggles the close-on-exec flag for the specified file descriptor.
     *
     * @param fd non-null; file descriptor
     * @param flag desired close-on-exec flag state
     * @throws IOException
     */
    static native void setCloseOnExec(FileDescriptor fd, boolean flag)
            throws IOException;

    /**
     * Retrieves the permitted capability set from another process.
     *
     * @param pid &gt;=0 process ID or 0 for this process
     * @throws IOException on error
     */
    static native long capgetPermitted(int pid)
            throws IOException;

    /**
     * Sets the permitted and effective capability sets of this process.
     *
     * @param permittedCapabilities permitted set
     * @param effectiveCapabilities effective set
     * @throws IOException on error
     */
    static native void setCapabilities(
            long permittedCapabilities,
            long effectiveCapabilities) throws IOException;

    /**
     * Invokes select() on the provider array of file descriptors (selecting
     * for readability only). Array elements of null are ignored.
     *
     * @param fds non-null; array of readable file descriptors
     * @return index of descriptor that is now readable or -1 for empty array.
     * @throws IOException if an error occurs
     */
    static native int selectReadable(FileDescriptor[] fds) throws IOException;

    /**
     * Creates a file descriptor from an int fd.
     *
     * @param fd integer OS file descriptor
     * @return non-null; FileDescriptor instance
     * @throws IOException if fd is invalid
     */
    static native FileDescriptor createFileDescriptor(int fd)
            throws IOException;

    /**
     * Class not instantiable.
     */
    private ZygoteInit() {
    }

    /**
     * Helper exception class which holds a method and arguments and
     * can call them. This is used as part of a trampoline to get rid of
     * the initial process setup stack frames.
     */
    public static class MethodAndArgsCaller extends Exception
            implements Runnable {
        /** method to call */
        private final Method mMethod;

        /** argument array */
        private final String[] mArgs;

        public MethodAndArgsCaller(Method method, String[] args) {
            mMethod = method;
            mArgs = args;
        }

        public void run() {
            try {
                mMethod.invoke(null, new Object[] { mArgs });
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(ex);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                } else if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new RuntimeException(ex);
            }
        }
    }
}
