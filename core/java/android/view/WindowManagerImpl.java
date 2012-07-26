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

package android.view;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.opengl.ManagedEGLContext;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;

final class WindowLeaked extends AndroidRuntimeException {
    public WindowLeaked(String msg) {
        super(msg);
    }
}

/**
 * Low-level communication with the global system window manager.  It implements
 * the ViewManager interface, allowing you to add any View subclass as a
 * top-level window on the screen. Additional window manager specific layout
 * parameters are defined for control over how windows are displayed.
 * It also implements the WindowManager interface, allowing you to control the
 * displays attached to the device.
 * 
 * <p>Applications will not normally use WindowManager directly, instead relying
 * on the higher-level facilities in {@link android.app.Activity} and
 * {@link android.app.Dialog}.
 * 
 * <p>Even for low-level window manager access, it is almost never correct to use
 * this class.  For example, {@link android.app.Activity#getWindowManager}
 * provides a ViewManager for adding windows that are associated with that
 * activity -- the window manager will not normally allow you to add arbitrary
 * windows that are not associated with an activity.
 * 
 * @hide
 */
public class WindowManagerImpl implements WindowManager {
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
     * The window manager is currently animating.  It will call
     * IWindow.doneAnimating() when done.
     */
    public static final int RELAYOUT_RES_ANIMATING = 0x8;

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
    
    public static final int ADD_OKAY = 0;
    public static final int ADD_BAD_APP_TOKEN = -1;
    public static final int ADD_BAD_SUBWINDOW_TOKEN = -2;
    public static final int ADD_NOT_APP_TOKEN = -3;
    public static final int ADD_APP_EXITING = -4;
    public static final int ADD_DUPLICATE_ADD = -5;
    public static final int ADD_STARTING_NOT_NEEDED = -6;
    public static final int ADD_MULTIPLE_SINGLETON = -7;
    public static final int ADD_PERMISSION_DENIED = -8;

    private static WindowManagerImpl sDefaultWindowManager;
    private static IWindowManager sWindowManagerService;

    private final WindowManagerState mState;
    private final Window mParentWindow;
    private final CompatibilityInfoHolder mCompatibilityInfo;
    private final Display mDefaultDisplay;

    private WindowManagerImpl(WindowManagerState state, Window parentWindow,
            CompatibilityInfoHolder compatibilityInfo) {
        mState = state;
        mParentWindow = parentWindow;
        mCompatibilityInfo = compatibilityInfo;
        mDefaultDisplay = mState.getDefaultDisplay(mCompatibilityInfo);
    }

    public static WindowManagerImpl getDefault() {
        synchronized (WindowManagerImpl.class) {
            if (sDefaultWindowManager == null) {
                sDefaultWindowManager = new WindowManagerImpl(
                        new WindowManagerState(), null, null);
            }
            return sDefaultWindowManager;
        }
    }

    public static IWindowManager getWindowManagerService() {
        synchronized (WindowManagerImpl.class) {
            if (sWindowManagerService == null) {
                sWindowManagerService = IWindowManager.Stub.asInterface(
                        ServiceManager.getService("window"));
            }
            return sWindowManagerService;
        }
    }

    public WindowManagerImpl makeLocal(Window parentWindow) {
        return new WindowManagerImpl(mState, parentWindow, parentWindow.getCompatibilityInfo());
    }

    public WindowManagerImpl makeCompatible(CompatibilityInfoHolder compatInfo) {
        if (compatInfo == mCompatibilityInfo) {
            return this;
        }
        if (compatInfo == null && mParentWindow == null) {
            return getDefault();
        }
        return new WindowManagerImpl(mState, mParentWindow, compatInfo);
    }

    @Override
    public void addView(View view, ViewGroup.LayoutParams params) {
        mState.addView(view, params, mParentWindow, mCompatibilityInfo);
    }

    @Override
    public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
        mState.updateViewLayout(view, params);
    }

    @Override
    public void removeView(View view) {
        mState.removeView(view, false);
    }

    @Override
    public void removeViewImmediate(View view) {
        mState.removeView(view, true);
    }

    @Override
    public Display getDefaultDisplay() {
        return mDefaultDisplay;
    }

    public void closeAll(IBinder token, String who, String what) {
        mState.closeAll(token, who, what);
    }

    public void startTrimMemory(int level) {
        mState.startTrimMemory(level);
    }

    public void endTrimMemory() {
        mState.endTrimMemory();
    }

    public void trimLocalMemory() {
        mState.trimLocalMemory();
    }

    public void dumpGfxInfo(FileDescriptor fd) {
        mState.dumpGfxInfo(fd);
    }

    public void setStoppedState(IBinder token, boolean stopped) {
        mState.setStoppedState(token, stopped);
    }

    public void reportNewConfiguration(Configuration config) {
        mState.reportNewConfiguration(config);
    }

    static final class WindowManagerState {
        private final Display mDefaultDisplay;

        private View[] mViews;
        private ViewRootImpl[] mRoots;
        private WindowManager.LayoutParams[] mParams;
        private boolean mNeedsEglTerminate;

        private Runnable mSystemPropertyUpdater;

        public WindowManagerState() {
            mDefaultDisplay = new Display(Display.DEFAULT_DISPLAY, null);
        }

        public Display getDefaultDisplay(CompatibilityInfoHolder compatInfo) {
            if (compatInfo == null) {
                return mDefaultDisplay;
            }
            return new Display(Display.DEFAULT_DISPLAY, compatInfo);
        }

        public void addView(View view, ViewGroup.LayoutParams params, Window parentWindow,
                CompatibilityInfoHolder cih) {
            if (!(params instanceof WindowManager.LayoutParams)) {
                throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
            }

            final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams)params;
            if (parentWindow != null) {
                parentWindow.adjustLayoutParamsForSubWindow(wparams);
            }

            ViewRootImpl root;
            View panelParentView = null;

            synchronized (this) {
                // Start watching for system property changes.
                if (mSystemPropertyUpdater == null) {
                    mSystemPropertyUpdater = new Runnable() {
                        @Override public void run() {
                            synchronized (this) {
                                synchronized (this) {
                                    for (ViewRootImpl root : mRoots) {
                                        root.loadSystemProperties();
                                    }
                                }
                            }
                        }
                    };
                    SystemProperties.addChangeCallback(mSystemPropertyUpdater);
                }

                int index = findViewLocked(view, false);
                if (index >= 0) {
                    throw new IllegalStateException("View " + view
                            + " has already been added to the window manager.");
                }

                // If this is a panel window, then find the window it is being
                // attached to for future reference.
                if (wparams.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW &&
                        wparams.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) {
                    final int count = mViews != null ? mViews.length : 0;
                    for (int i=0; i<count; i++) {
                        if (mRoots[i].mWindow.asBinder() == wparams.token) {
                            panelParentView = mViews[i];
                        }
                    }
                }

                root = new ViewRootImpl(view.getContext());
                if (cih == null) {
                    root.mCompatibilityInfo = new CompatibilityInfoHolder();
                } else {
                    root.mCompatibilityInfo = cih;
                }

                view.setLayoutParams(wparams);

                if (mViews == null) {
                    index = 1;
                    mViews = new View[1];
                    mRoots = new ViewRootImpl[1];
                    mParams = new WindowManager.LayoutParams[1];
                } else {
                    index = mViews.length + 1;
                    Object[] old = mViews;
                    mViews = new View[index];
                    System.arraycopy(old, 0, mViews, 0, index-1);
                    old = mRoots;
                    mRoots = new ViewRootImpl[index];
                    System.arraycopy(old, 0, mRoots, 0, index-1);
                    old = mParams;
                    mParams = new WindowManager.LayoutParams[index];
                    System.arraycopy(old, 0, mParams, 0, index-1);
                }
                index--;

                mViews[index] = view;
                mRoots[index] = root;
                mParams[index] = wparams;
            }

            // do this last because it fires off messages to start doing things
            root.setView(view, wparams, panelParentView);
        }

        public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
            if (!(params instanceof WindowManager.LayoutParams)) {
                throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
            }

            final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams)params;

            view.setLayoutParams(wparams);

            synchronized (this) {
                int index = findViewLocked(view, true);
                ViewRootImpl root = mRoots[index];
                mParams[index] = wparams;
                root.setLayoutParams(wparams, false);
            }
        }

        public void removeView(View view, boolean immediate) {
            synchronized (this) {
                int index = findViewLocked(view, true);
                View curView = removeViewLocked(index, immediate);
                if (curView == view) {
                    return;
                }

                throw new IllegalStateException("Calling with view " + view
                        + " but the ViewAncestor is attached to " + curView);
            }
        }

        public void closeAll(IBinder token, String who, String what) {
            synchronized (this) {
                if (mViews == null)
                    return;

                int count = mViews.length;
                //Log.i("foo", "Closing all windows of " + token);
                for (int i=0; i<count; i++) {
                    //Log.i("foo", "@ " + i + " token " + mParams[i].token
                    //        + " view " + mRoots[i].getView());
                    if (token == null || mParams[i].token == token) {
                        ViewRootImpl root = mRoots[i];

                        //Log.i("foo", "Force closing " + root);
                        if (who != null) {
                            WindowLeaked leak = new WindowLeaked(
                                    what + " " + who + " has leaked window "
                                    + root.getView() + " that was originally added here");
                            leak.setStackTrace(root.getLocation().getStackTrace());
                            Log.e(TAG, leak.getMessage(), leak);
                        }

                        removeViewLocked(i, false);
                        i--;
                        count--;
                    }
                }
            }
        }

        private View removeViewLocked(int index, boolean immediate) {
            ViewRootImpl root = mRoots[index];
            View view = root.getView();

            if (view != null) {
                InputMethodManager imm = InputMethodManager.getInstance(view.getContext());
                if (imm != null) {
                    imm.windowDismissed(mViews[index].getWindowToken());
                }
            }
            root.die(immediate);

            final int count = mViews.length;

            // remove it from the list
            View[] tmpViews = new View[count-1];
            removeItem(tmpViews, mViews, index);
            mViews = tmpViews;

            ViewRootImpl[] tmpRoots = new ViewRootImpl[count-1];
            removeItem(tmpRoots, mRoots, index);
            mRoots = tmpRoots;

            WindowManager.LayoutParams[] tmpParams
                    = new WindowManager.LayoutParams[count-1];
            removeItem(tmpParams, mParams, index);
            mParams = tmpParams;

            if (view != null) {
                view.assignParent(null);
                // func doesn't allow null...  does it matter if we clear them?
                //view.setLayoutParams(null);
            }
            return view;
        }

        private static void removeItem(Object[] dst, Object[] src, int index) {
            if (dst.length > 0) {
                if (index > 0) {
                    System.arraycopy(src, 0, dst, 0, index);
                }
                if (index < dst.length) {
                    System.arraycopy(src, index+1, dst, index, src.length-index-1);
                }
            }
        }

        private int findViewLocked(View view, boolean required) {
            synchronized (this) {
                if (mViews != null) {
                    final int count = mViews.length;
                    for (int i = 0; i < count; i++) {
                        if (mViews[i] == view) {
                            return i;
                        }
                    }
                }
                if (required) {
                    throw new IllegalArgumentException("View not attached to window manager");
                }
                return -1;
            }
        }

        public void startTrimMemory(int level) {
            if (HardwareRenderer.isAvailable()) {
                // On low-end gfx devices we trim when memory is moderate;
                // on high-end devices we do this when low.
                if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                        || (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
                                && !ActivityManager.isHighEndGfx(mDefaultDisplay))) {
                    // Destroy all hardware surfaces and resources associated to
                    // known windows
                    synchronized (this) {
                        if (mViews == null) return;
                        int count = mViews.length;
                        for (int i = 0; i < count; i++) {
                            mRoots[i].terminateHardwareResources();
                        }
                    }
                    // Force a full memory flush
                    mNeedsEglTerminate = true;
                    HardwareRenderer.startTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
                    return;
                }

                HardwareRenderer.startTrimMemory(level);
            }
        }

        public void endTrimMemory() {
            HardwareRenderer.endTrimMemory();

            if (mNeedsEglTerminate) {
                ManagedEGLContext.doTerminate();
                mNeedsEglTerminate = false;
            }
        }

        public void trimLocalMemory() {
            synchronized (this) {
                if (mViews == null) return;
                int count = mViews.length;
                for (int i = 0; i < count; i++) {
                    mRoots[i].destroyHardwareLayers();
                }
            }
        }

        public void dumpGfxInfo(FileDescriptor fd) {
            FileOutputStream fout = new FileOutputStream(fd);
            PrintWriter pw = new PrintWriter(fout);
            try {
                synchronized (this) {
                    if (mViews != null) {
                        final int count = mViews.length;

                        pw.println("Profile data in ms:");

                        for (int i = 0; i < count; i++) {
                            ViewRootImpl root = mRoots[i];
                            String name = getWindowName(root);
                            pw.printf("\n\t%s", name);

                            HardwareRenderer renderer =
                                    root.getView().mAttachInfo.mHardwareRenderer;
                            if (renderer != null) {
                                renderer.dumpGfxInfo(pw);
                            }
                        }

                        pw.println("\nView hierarchy:\n");

                        int viewsCount = 0;
                        int displayListsSize = 0;
                        int[] info = new int[2];

                        for (int i = 0; i < count; i++) {
                            ViewRootImpl root = mRoots[i];
                            root.dumpGfxInfo(info);

                            String name = getWindowName(root);
                            pw.printf("  %s\n  %d views, %.2f kB of display lists",
                                    name, info[0], info[1] / 1024.0f);
                            HardwareRenderer renderer =
                                    root.getView().mAttachInfo.mHardwareRenderer;
                            if (renderer != null) {
                                pw.printf(", %d frames rendered", renderer.getFrameCount());
                            }
                            pw.printf("\n\n");

                            viewsCount += info[0];
                            displayListsSize += info[1];
                        }

                        pw.printf("\nTotal ViewRootImpl: %d\n", count);
                        pw.printf("Total Views:        %d\n", viewsCount);
                        pw.printf("Total DisplayList:  %.2f kB\n\n", displayListsSize / 1024.0f);
                    }
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
            synchronized (this) {
                if (mViews != null) {
                    int count = mViews.length;
                    for (int i=0; i < count; i++) {
                        if (token == null || mParams[i].token == token) {
                            ViewRootImpl root = mRoots[i];
                            root.setStopped(stopped);
                        }
                    }
                }
            }
        }

        public void reportNewConfiguration(Configuration config) {
            synchronized (this) {
                if (mViews != null) {
                    int count = mViews.length;
                    config = new Configuration(config);
                    for (int i=0; i < count; i++) {
                        ViewRootImpl root = mRoots[i];
                        root.requestUpdateConfiguration(config);
                    }
                }
            }
        }
    }
}
