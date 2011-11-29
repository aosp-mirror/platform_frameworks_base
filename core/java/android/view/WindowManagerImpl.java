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
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.opengl.ManagedEGLContext;
import android.os.IBinder;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.HashMap;

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
 * It also implemens the WindowManager interface, allowing you to control the
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

    private View[] mViews;
    private ViewRootImpl[] mRoots;
    private WindowManager.LayoutParams[] mParams;

    private final static Object sLock = new Object();
    private final static WindowManagerImpl sWindowManager = new WindowManagerImpl();
    private final static HashMap<CompatibilityInfo, WindowManager> sCompatWindowManagers
            = new HashMap<CompatibilityInfo, WindowManager>();

    static class CompatModeWrapper implements WindowManager {
        private final WindowManagerImpl mWindowManager;
        private final Display mDefaultDisplay;
        private final CompatibilityInfoHolder mCompatibilityInfo;

        CompatModeWrapper(WindowManager wm, CompatibilityInfoHolder ci) {
            mWindowManager = wm instanceof CompatModeWrapper
                    ? ((CompatModeWrapper)wm).mWindowManager : (WindowManagerImpl)wm;

            // Use the original display if there is no compatibility mode
            // to apply, or the underlying window manager is already a
            // compatibility mode wrapper.  (We assume that if it is a
            // wrapper, it is applying the same compatibility mode.)
            if (ci == null) {
                mDefaultDisplay = mWindowManager.getDefaultDisplay();
            } else {
                //mDefaultDisplay = mWindowManager.getDefaultDisplay();
                mDefaultDisplay = Display.createCompatibleDisplay(
                        mWindowManager.getDefaultDisplay().getDisplayId(), ci);
            }

            mCompatibilityInfo = ci;
        }

        @Override
        public void addView(View view, android.view.ViewGroup.LayoutParams params) {
            mWindowManager.addView(view, params, mCompatibilityInfo);
        }

        @Override
        public void updateViewLayout(View view, android.view.ViewGroup.LayoutParams params) {
            mWindowManager.updateViewLayout(view, params);

        }

        @Override
        public void removeView(View view) {
            mWindowManager.removeView(view);
        }

        @Override
        public Display getDefaultDisplay() {
            return mDefaultDisplay;
        }

        @Override
        public void removeViewImmediate(View view) {
            mWindowManager.removeViewImmediate(view);
        }

        @Override
        public boolean isHardwareAccelerated() {
            return mWindowManager.isHardwareAccelerated();
        }

    }

    public static WindowManagerImpl getDefault() {
        return sWindowManager;
    }

    public static WindowManager getDefault(CompatibilityInfo compatInfo) {
        CompatibilityInfoHolder cih = new CompatibilityInfoHolder();
        cih.set(compatInfo);
        if (cih.getIfNeeded() == null) {
            return sWindowManager;
        }

        synchronized (sLock) {
            // NOTE: It would be cleaner to move the implementation of
            // WindowManagerImpl into a static inner class, and have this
            // public impl just call into that.  Then we can make multiple
            // instances of WindowManagerImpl for compat mode rather than
            // having to make wrappers.
            WindowManager wm = sCompatWindowManagers.get(compatInfo);
            if (wm == null) {
                wm = new CompatModeWrapper(sWindowManager, cih);
                sCompatWindowManagers.put(compatInfo, wm);
            }
            return wm;
        }
    }

    public static WindowManager getDefault(CompatibilityInfoHolder compatInfo) {
        return new CompatModeWrapper(sWindowManager, compatInfo);
    }
    
    public boolean isHardwareAccelerated() {
        return false;
    }
    
    public void addView(View view) {
        addView(view, new WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.OPAQUE));
    }

    public void addView(View view, ViewGroup.LayoutParams params) {
        addView(view, params, null, false);
    }
    
    public void addView(View view, ViewGroup.LayoutParams params, CompatibilityInfoHolder cih) {
        addView(view, params, cih, false);
    }
    
    private void addView(View view, ViewGroup.LayoutParams params,
            CompatibilityInfoHolder cih, boolean nest) {
        if (false) Log.v("WindowManager", "addView view=" + view);

        if (!(params instanceof WindowManager.LayoutParams)) {
            throw new IllegalArgumentException(
                    "Params must be WindowManager.LayoutParams");
        }

        final WindowManager.LayoutParams wparams
                = (WindowManager.LayoutParams)params;
        
        ViewRootImpl root;
        View panelParentView = null;
        
        synchronized (this) {
            // Here's an odd/questionable case: if someone tries to add a
            // view multiple times, then we simply bump up a nesting count
            // and they need to remove the view the corresponding number of
            // times to have it actually removed from the window manager.
            // This is useful specifically for the notification manager,
            // which can continually add/remove the same view as a
            // notification gets updated.
            int index = findViewLocked(view, false);
            if (index >= 0) {
                if (!nest) {
                    throw new IllegalStateException("View " + view
                            + " has already been added to the window manager.");
                }
                root = mRoots[index];
                root.mAddNesting++;
                // Update layout parameters.
                view.setLayoutParams(wparams);
                root.setLayoutParams(wparams, true);
                return;
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
            root.mAddNesting = 1;
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

        final WindowManager.LayoutParams wparams
                = (WindowManager.LayoutParams)params;
        
        view.setLayoutParams(wparams);

        synchronized (this) {
            int index = findViewLocked(view, true);
            ViewRootImpl root = mRoots[index];
            mParams[index] = wparams;
            root.setLayoutParams(wparams, false);
        }
    }

    public void removeView(View view) {
        synchronized (this) {
            int index = findViewLocked(view, true);
            View curView = removeViewLocked(index);
            if (curView == view) {
                return;
            }
            
            throw new IllegalStateException("Calling with view " + view
                    + " but the ViewAncestor is attached to " + curView);
        }
    }

    public void removeViewImmediate(View view) {
        synchronized (this) {
            int index = findViewLocked(view, true);
            ViewRootImpl root = mRoots[index];
            View curView = root.getView();
            
            root.mAddNesting = 0;
            root.die(true);
            finishRemoveViewLocked(curView, index);
            if (curView == view) {
                return;
            }
            
            throw new IllegalStateException("Calling with view " + view
                    + " but the ViewAncestor is attached to " + curView);
        }
    }
    
    View removeViewLocked(int index) {
        ViewRootImpl root = mRoots[index];
        View view = root.getView();
        
        // Don't really remove until we have matched all calls to add().
        root.mAddNesting--;
        if (root.mAddNesting > 0) {
            return view;
        }

        if (view != null) {
            InputMethodManager imm = InputMethodManager.getInstance(view.getContext());
            if (imm != null) {
                imm.windowDismissed(mViews[index].getWindowToken());
            }
        }
        root.die(false);
        finishRemoveViewLocked(view, index);
        return view;
    }
    
    void finishRemoveViewLocked(View view, int index) {
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
                    root.mAddNesting = 1;
                    
                    //Log.i("foo", "Force closing " + root);
                    if (who != null) {
                        WindowLeaked leak = new WindowLeaked(
                                what + " " + who + " has leaked window "
                                + root.getView() + " that was originally added here");
                        leak.setStackTrace(root.getLocation().getStackTrace());
                        Log.e("WindowManager", leak.getMessage(), leak);
                    }

                    removeViewLocked(i);
                    i--;
                    count--;
                }
            }
        }
    }

    /**
     * @param level See {@link android.content.ComponentCallbacks}
     */
    public void trimMemory(int level) {
        if (HardwareRenderer.isAvailable()) {
            switch (level) {
                case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                    // On low and medium end gfx devices
                    if (!ActivityManager.isHighEndGfx(getDefaultDisplay())) {
                        // Force a full memory flush
                        HardwareRenderer.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
                        // Destroy all hardware surfaces and resources associated to
                        // known windows
                        synchronized (this) {
                            if (mViews == null) return;
                            int count = mViews.length;
                            for (int i = 0; i < count; i++) {
                                mRoots[i].terminateHardwareResources();
                            }
                        }
                        // Terminate the hardware renderer to free all resources
                        ManagedEGLContext.doTerminate();
                        break;
                    }
                    // high end gfx devices fall through to next case
                default:
                    HardwareRenderer.trimMemory(level);
            }
        }
    }

    /**
     * @hide
     */
    public void trimLocalMemory() {
        synchronized (this) {
            if (mViews == null) return;
            int count = mViews.length;
            for (int i = 0; i < count; i++) {
                mRoots[i].destroyHardwareLayers();
            }
        }
    }

    /**
     * @hide
     */
    public void dumpGfxInfo(FileDescriptor fd) {
        FileOutputStream fout = new FileOutputStream(fd);
        PrintWriter pw = new PrintWriter(fout);
        try {
            synchronized (this) {
                if (mViews != null) {
                    pw.println("View hierarchy:");

                    final int count = mViews.length;

                    int viewsCount = 0;
                    int displayListsSize = 0;
                    int[] info = new int[2];

                    for (int i = 0; i < count; i++) {
                        ViewRootImpl root = mRoots[i];
                        root.dumpGfxInfo(pw, info);

                        String name = root.getClass().getName() + '@' +
                                Integer.toHexString(hashCode());                        
                        pw.printf("  %s: %d views, %.2f kB (display lists)\n",
                                name, info[0], info[1] / 1024.0f);

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

    public void setStoppedState(IBinder token, boolean stopped) {
        synchronized (this) {
            if (mViews == null)
                return;
            int count = mViews.length;
            for (int i=0; i<count; i++) {
                if (token == null || mParams[i].token == token) {
                    ViewRootImpl root = mRoots[i];
                    root.setStopped(stopped);
                }
            }
        }
    }
    
    public void reportNewConfiguration(Configuration config) {
        synchronized (this) {
            int count = mViews.length;
            config = new Configuration(config);
            for (int i=0; i<count; i++) {
                ViewRootImpl root = mRoots[i];
                root.requestUpdateConfiguration(config);
            }
        }
    }

    public WindowManager.LayoutParams getRootViewLayoutParameter(View view) {
        ViewParent vp = view.getParent();
        while (vp != null && !(vp instanceof ViewRootImpl)) {
            vp = vp.getParent();
        }
        
        if (vp == null) return null;
        
        ViewRootImpl vr = (ViewRootImpl)vp;
        
        int N = mRoots.length;
        for (int i = 0; i < N; ++i) {
            if (mRoots[i] == vr) {
                return mParams[i];
            }
        }
        
        return null;
    }
    
    public void closeAll() {
        closeAll(null, null, null);
    }
    
    public Display getDefaultDisplay() {
        return new Display(Display.DEFAULT_DISPLAY, null);
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
            final int count = mViews != null ? mViews.length : 0;
            for (int i=0; i<count; i++) {
                if (mViews[i] == view) {
                    return i;
                }
            }
            if (required) {
                throw new IllegalArgumentException(
                        "View not attached to window manager");
            }
            return -1;
        }
    }
}
