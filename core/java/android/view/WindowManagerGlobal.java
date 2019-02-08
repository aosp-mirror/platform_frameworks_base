/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.animation.ValueAnimator;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.AndroidRuntimeException;
import android.util.ArraySet;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.util.FastPrintWriter;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Provides low-level communication with the system window manager for
 * operations that are not associated with any particular context.
 *
 * This class is only used internally to implement global functions where
 * the caller already knows the display and relevant compatibility information
 * for the operation.  For most purposes, you should use {@link WindowManager} instead
 * since it is bound to a context.
 *
 * @see WindowManagerImpl
 * @hide
 */
public final class WindowManagerGlobal {
    private static final String TAG = "WindowManager";

    /**
     * The user is navigating with keys (not the touch screen), so
     * navigational focus should be shown.
     */
    public static final int RELAYOUT_RES_IN_TOUCH_MODE = 0x1;

    /**
     * This is the first time the window is being drawn,
     * so the client must call drawingFinished() when done
     */
    public static final int RELAYOUT_RES_FIRST_TIME = 0x2;

    /**
     * The window manager has changed the surface from the last call.
     */
    public static final int RELAYOUT_RES_SURFACE_CHANGED = 0x4;

    /**
     * The window is being resized by dragging on the docked divider. The client should render
     * at (0, 0) and extend its background to the background frame passed into
     * {@link IWindow#resized}.
     */
    public static final int RELAYOUT_RES_DRAG_RESIZING_DOCKED = 0x8;

    /**
     * The window is being resized by dragging one of the window corners,
     * in this case the surface would be fullscreen-sized. The client should
     * render to the actual frame location (instead of (0,curScrollY)).
     */
    public static final int RELAYOUT_RES_DRAG_RESIZING_FREEFORM = 0x10;

    /**
     * The window manager has changed the size of the surface from the last call.
     */
    public static final int RELAYOUT_RES_SURFACE_RESIZED = 0x20;

    /**
     * In multi-window we force show the navigation bar. Because we don't want that the surface size
     * changes in this mode, we instead have a flag whether the navigation bar size should always be
     * consumed, so the app is treated like there is no virtual navigation bar at all.
     */
    public static final int RELAYOUT_RES_CONSUME_ALWAYS_NAV_BAR = 0x40;

    /**
     * Flag for relayout: the client will be later giving
     * internal insets; as a result, the window will not impact other window
     * layouts until the insets are given.
     */
    public static final int RELAYOUT_INSETS_PENDING = 0x1;

    /**
     * Flag for relayout: the client may be currently using the current surface,
     * so if it is to be destroyed as a part of the relayout the destroy must
     * be deferred until later.  The client will call performDeferredDestroy()
     * when it is okay.
     */
    public static final int RELAYOUT_DEFER_SURFACE_DESTROY = 0x2;

    public static final int ADD_FLAG_APP_VISIBLE = 0x2;
    public static final int ADD_FLAG_IN_TOUCH_MODE = RELAYOUT_RES_IN_TOUCH_MODE;

    /**
     * Like {@link #RELAYOUT_RES_CONSUME_ALWAYS_NAV_BAR}, but as a "hint" when adding the window.
     */
    public static final int ADD_FLAG_ALWAYS_CONSUME_NAV_BAR = 0x4;

    public static final int ADD_OKAY = 0;
    public static final int ADD_BAD_APP_TOKEN = -1;
    public static final int ADD_BAD_SUBWINDOW_TOKEN = -2;
    public static final int ADD_NOT_APP_TOKEN = -3;
    public static final int ADD_APP_EXITING = -4;
    public static final int ADD_DUPLICATE_ADD = -5;
    public static final int ADD_STARTING_NOT_NEEDED = -6;
    public static final int ADD_MULTIPLE_SINGLETON = -7;
    public static final int ADD_PERMISSION_DENIED = -8;
    public static final int ADD_INVALID_DISPLAY = -9;
    public static final int ADD_INVALID_TYPE = -10;

    @UnsupportedAppUsage
    private static WindowManagerGlobal sDefaultWindowManager;
    @UnsupportedAppUsage
    private static IWindowManager sWindowManagerService;
    @UnsupportedAppUsage
    private static IWindowSession sWindowSession;

    @UnsupportedAppUsage
    private final Object mLock = new Object();

    @UnsupportedAppUsage
    private final ArrayList<View> mViews = new ArrayList<View>();
    @UnsupportedAppUsage
    private final ArrayList<ViewRootImpl> mRoots = new ArrayList<ViewRootImpl>();
    @UnsupportedAppUsage
    private final ArrayList<WindowManager.LayoutParams> mParams =
            new ArrayList<WindowManager.LayoutParams>();
    private final ArraySet<View> mDyingViews = new ArraySet<View>();

    private Runnable mSystemPropertyUpdater;

    private WindowManagerGlobal() {
    }

    @UnsupportedAppUsage
    public static void initialize() {
        getWindowManagerService();
    }

    @UnsupportedAppUsage
    public static WindowManagerGlobal getInstance() {
        synchronized (WindowManagerGlobal.class) {
            if (sDefaultWindowManager == null) {
                sDefaultWindowManager = new WindowManagerGlobal();
            }
            return sDefaultWindowManager;
        }
    }

    @UnsupportedAppUsage
    public static IWindowManager getWindowManagerService() {
        synchronized (WindowManagerGlobal.class) {
            if (sWindowManagerService == null) {
                sWindowManagerService = IWindowManager.Stub.asInterface(
                        ServiceManager.getService("window"));
                try {
                    if (sWindowManagerService != null) {
                        ValueAnimator.setDurationScale(
                                sWindowManagerService.getCurrentAnimatorScale());
                    }
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            return sWindowManagerService;
        }
    }

    @UnsupportedAppUsage
    public static IWindowSession getWindowSession() {
        synchronized (WindowManagerGlobal.class) {
            if (sWindowSession == null) {
                try {
                    // Emulate the legacy behavior.  The global instance of InputMethodManager
                    // was instantiated here.
                    // TODO(b/116157766): Remove this hack after cleaning up @UnsupportedAppUsage
                    InputMethodManager.ensureDefaultInstanceForDefaultDisplayIfNecessary();
                    IWindowManager windowManager = getWindowManagerService();
                    sWindowSession = windowManager.openSession(
                            new IWindowSessionCallback.Stub() {
                                @Override
                                public void onAnimatorScaleChanged(float scale) {
                                    ValueAnimator.setDurationScale(scale);
                                }
                            });
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            return sWindowSession;
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static IWindowSession peekWindowSession() {
        synchronized (WindowManagerGlobal.class) {
            return sWindowSession;
        }
    }

    @UnsupportedAppUsage
    public String[] getViewRootNames() {
        synchronized (mLock) {
            final int numRoots = mRoots.size();
            String[] mViewRoots = new String[numRoots];
            for (int i = 0; i < numRoots; ++i) {
                mViewRoots[i] = getWindowName(mRoots.get(i));
            }
            return mViewRoots;
        }
    }

    @UnsupportedAppUsage
    public ArrayList<ViewRootImpl> getRootViews(IBinder token) {
        ArrayList<ViewRootImpl> views = new ArrayList<>();
        synchronized (mLock) {
            final int numRoots = mRoots.size();
            for (int i = 0; i < numRoots; ++i) {
                WindowManager.LayoutParams params = mParams.get(i);
                if (params.token == null) {
                    continue;
                }
                if (params.token != token) {
                    boolean isChild = false;
                    if (params.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW
                            && params.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) {
                        for (int j = 0 ; j < numRoots; ++j) {
                            View viewj = mViews.get(j);
                            WindowManager.LayoutParams paramsj = mParams.get(j);
                            if (params.token == viewj.getWindowToken()
                                    && paramsj.token == token) {
                                isChild = true;
                                break;
                            }
                        }
                    }
                    if (!isChild) {
                        continue;
                    }
                }
                views.add(mRoots.get(i));
            }
        }
        return views;
    }

    public View getWindowView(IBinder windowToken) {
        synchronized (mLock) {
            final int numViews = mViews.size();
            for (int i = 0; i < numViews; ++i) {
                final View view = mViews.get(i);
                if (view.getWindowToken() == windowToken) {
                    return view;
                }
            }
        }
        return null;
    }

    @UnsupportedAppUsage
    public View getRootView(String name) {
        synchronized (mLock) {
            for (int i = mRoots.size() - 1; i >= 0; --i) {
                final ViewRootImpl root = mRoots.get(i);
                if (name.equals(getWindowName(root))) return root.getView();
            }
        }

        return null;
    }

    public void addView(View view, ViewGroup.LayoutParams params,
            Display display, Window parentWindow) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }
        if (!(params instanceof WindowManager.LayoutParams)) {
            throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
        }

        final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams) params;
        if (parentWindow != null) {
            parentWindow.adjustLayoutParamsForSubWindow(wparams);
        } else {
            // If there's no parent, then hardware acceleration for this view is
            // set from the application's hardware acceleration setting.
            final Context context = view.getContext();
            if (context != null
                    && (context.getApplicationInfo().flags
                            & ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0) {
                wparams.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            }
        }

        ViewRootImpl root;
        View panelParentView = null;

        synchronized (mLock) {
            // Start watching for system property changes.
            if (mSystemPropertyUpdater == null) {
                mSystemPropertyUpdater = new Runnable() {
                    @Override public void run() {
                        synchronized (mLock) {
                            for (int i = mRoots.size() - 1; i >= 0; --i) {
                                mRoots.get(i).loadSystemProperties();
                            }
                        }
                    }
                };
                SystemProperties.addChangeCallback(mSystemPropertyUpdater);
            }

            int index = findViewLocked(view, false);
            if (index >= 0) {
                if (mDyingViews.contains(view)) {
                    // Don't wait for MSG_DIE to make it's way through root's queue.
                    mRoots.get(index).doDie();
                } else {
                    throw new IllegalStateException("View " + view
                            + " has already been added to the window manager.");
                }
                // The previous removeView() had not completed executing. Now it has.
            }

            // If this is a panel window, then find the window it is being
            // attached to for future reference.
            if (wparams.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW &&
                    wparams.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) {
                final int count = mViews.size();
                for (int i = 0; i < count; i++) {
                    if (mRoots.get(i).mWindow.asBinder() == wparams.token) {
                        panelParentView = mViews.get(i);
                    }
                }
            }

            root = new ViewRootImpl(view.getContext(), display);

            view.setLayoutParams(wparams);

            mViews.add(view);
            mRoots.add(root);
            mParams.add(wparams);

            // do this last because it fires off messages to start doing things
            try {
                root.setView(view, wparams, panelParentView);
            } catch (RuntimeException e) {
                // BadTokenException or InvalidDisplayException, clean up.
                if (index >= 0) {
                    removeViewLocked(index, true);
                }
                throw e;
            }
        }
    }

    public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        if (!(params instanceof WindowManager.LayoutParams)) {
            throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
        }

        final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams)params;

        view.setLayoutParams(wparams);

        synchronized (mLock) {
            int index = findViewLocked(view, true);
            ViewRootImpl root = mRoots.get(index);
            mParams.remove(index);
            mParams.add(index, wparams);
            root.setLayoutParams(wparams, false);
        }
    }

    @UnsupportedAppUsage
    public void removeView(View view, boolean immediate) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }

        synchronized (mLock) {
            int index = findViewLocked(view, true);
            View curView = mRoots.get(index).getView();
            removeViewLocked(index, immediate);
            if (curView == view) {
                return;
            }

            throw new IllegalStateException("Calling with view " + view
                    + " but the ViewAncestor is attached to " + curView);
        }
    }

    /**
     * Remove all roots with specified token.
     *
     * @param token app or window token.
     * @param who name of caller, used in logs.
     * @param what type of caller, used in logs.
     */
    public void closeAll(IBinder token, String who, String what) {
        closeAllExceptView(token, null /* view */, who, what);
    }

    /**
     * Remove all roots with specified token, except maybe one view.
     *
     * @param token app or window token.
     * @param view view that should be should be preserved along with it's root.
     *             Pass null if everything should be removed.
     * @param who name of caller, used in logs.
     * @param what type of caller, used in logs.
     */
    public void closeAllExceptView(IBinder token, View view, String who, String what) {
        synchronized (mLock) {
            int count = mViews.size();
            for (int i = 0; i < count; i++) {
                if ((view == null || mViews.get(i) != view)
                        && (token == null || mParams.get(i).token == token)) {
                    ViewRootImpl root = mRoots.get(i);

                    if (who != null) {
                        WindowLeaked leak = new WindowLeaked(
                                what + " " + who + " has leaked window "
                                + root.getView() + " that was originally added here");
                        leak.setStackTrace(root.getLocation().getStackTrace());
                        Log.e(TAG, "", leak);
                    }

                    removeViewLocked(i, false);
                }
            }
        }
    }

    private void removeViewLocked(int index, boolean immediate) {
        ViewRootImpl root = mRoots.get(index);
        View view = root.getView();

        if (view != null) {
            InputMethodManager imm = view.getContext().getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.windowDismissed(mViews.get(index).getWindowToken());
            }
        }
        boolean deferred = root.die(immediate);
        if (view != null) {
            view.assignParent(null);
            if (deferred) {
                mDyingViews.add(view);
            }
        }
    }

    void doRemoveView(ViewRootImpl root) {
        synchronized (mLock) {
            final int index = mRoots.indexOf(root);
            if (index >= 0) {
                mRoots.remove(index);
                mParams.remove(index);
                final View view = mViews.remove(index);
                mDyingViews.remove(view);
            }
        }
        if (ThreadedRenderer.sTrimForeground && ThreadedRenderer.isAvailable()) {
            doTrimForeground();
        }
    }

    private int findViewLocked(View view, boolean required) {
        final int index = mViews.indexOf(view);
        if (required && index < 0) {
            throw new IllegalArgumentException("View=" + view + " not attached to window manager");
        }
        return index;
    }

    public static boolean shouldDestroyEglContext(int trimLevel) {
        // On low-end gfx devices we trim when memory is moderate;
        // on high-end devices we do this when low.
        if (trimLevel >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            return true;
        }
        if (trimLevel >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
                && !ActivityManager.isHighEndGfx()) {
            return true;
        }
        return false;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public void trimMemory(int level) {
        if (ThreadedRenderer.isAvailable()) {
            if (shouldDestroyEglContext(level)) {
                // Destroy all hardware surfaces and resources associated to
                // known windows
                synchronized (mLock) {
                    for (int i = mRoots.size() - 1; i >= 0; --i) {
                        mRoots.get(i).destroyHardwareResources();
                    }
                }
                // Force a full memory flush
                level = ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
            }

            ThreadedRenderer.trimMemory(level);

            if (ThreadedRenderer.sTrimForeground) {
                doTrimForeground();
            }
        }
    }

    public static void trimForeground() {
        if (ThreadedRenderer.sTrimForeground && ThreadedRenderer.isAvailable()) {
            WindowManagerGlobal wm = WindowManagerGlobal.getInstance();
            wm.doTrimForeground();
        }
    }

    private void doTrimForeground() {
        boolean hasVisibleWindows = false;
        synchronized (mLock) {
            for (int i = mRoots.size() - 1; i >= 0; --i) {
                final ViewRootImpl root = mRoots.get(i);
                if (root.mView != null && root.getHostVisibility() == View.VISIBLE
                        && root.mAttachInfo.mThreadedRenderer != null) {
                    hasVisibleWindows = true;
                } else {
                    root.destroyHardwareResources();
                }
            }
        }
        if (!hasVisibleWindows) {
            ThreadedRenderer.trimMemory(
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
        }
    }

    public void dumpGfxInfo(FileDescriptor fd, String[] args) {
        FileOutputStream fout = new FileOutputStream(fd);
        PrintWriter pw = new FastPrintWriter(fout);
        try {
            synchronized (mLock) {
                final int count = mViews.size();

                pw.println("Profile data in ms:");

                for (int i = 0; i < count; i++) {
                    ViewRootImpl root = mRoots.get(i);
                    String name = getWindowName(root);
                    pw.printf("\n\t%s (visibility=%d)", name, root.getHostVisibility());

                    ThreadedRenderer renderer =
                            root.getView().mAttachInfo.mThreadedRenderer;
                    if (renderer != null) {
                        renderer.dumpGfxInfo(pw, fd, args);
                    }
                }

                pw.println("\nView hierarchy:\n");

                int viewsCount = 0;
                int displayListsSize = 0;
                int[] info = new int[2];

                for (int i = 0; i < count; i++) {
                    ViewRootImpl root = mRoots.get(i);
                    root.dumpGfxInfo(info);

                    String name = getWindowName(root);
                    pw.printf("  %s\n  %d views, %.2f kB of display lists",
                            name, info[0], info[1] / 1024.0f);
                    pw.printf("\n\n");

                    viewsCount += info[0];
                    displayListsSize += info[1];
                }

                pw.printf("\nTotal ViewRootImpl: %d\n", count);
                pw.printf("Total Views:        %d\n", viewsCount);
                pw.printf("Total DisplayList:  %.2f kB\n\n", displayListsSize / 1024.0f);
            }
        } finally {
            pw.flush();
        }
    }

    private static String getWindowName(ViewRootImpl root) {
        return root.mWindowAttributes.getTitle() + "/" +
                root.getClass().getName() + '@' + Integer.toHexString(root.hashCode());
    }

    public void setStoppedState(IBinder token, boolean stopped) {
        synchronized (mLock) {
            int count = mViews.size();
            for (int i = count - 1; i >= 0; i--) {
                if (token == null || mParams.get(i).token == token) {
                    ViewRootImpl root = mRoots.get(i);
                    // Client might remove the view by "stopped" event.
                    root.setWindowStopped(stopped);
                    // Recursively forward stopped state to View's attached
                    // to this Window rather than the root application token,
                    // e.g. PopupWindow's.
                    setStoppedState(root.mAttachInfo.mWindowToken, stopped);
                }
            }
        }
    }

    public void reportNewConfiguration(Configuration config) {
        synchronized (mLock) {
            int count = mViews.size();
            config = new Configuration(config);
            for (int i=0; i < count; i++) {
                ViewRootImpl root = mRoots.get(i);
                root.requestUpdateConfiguration(config);
            }
        }
    }

    /** @hide */
    public void changeCanvasOpacity(IBinder token, boolean opaque) {
        if (token == null) {
            return;
        }
        synchronized (mLock) {
            for (int i = mParams.size() - 1; i >= 0; --i) {
                if (mParams.get(i).token == token) {
                    mRoots.get(i).changeCanvasOpacity(opaque);
                    return;
                }
            }
        }
    }
}

final class WindowLeaked extends AndroidRuntimeException {
    @UnsupportedAppUsage
    public WindowLeaked(String msg) {
        super(msg);
    }
}
