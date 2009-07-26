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

import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.AndroidRuntimeException;
import android.util.Config;
import android.util.Log;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

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
    public static final int RELAYOUT_IN_TOUCH_MODE = 0x1;
    /**
     * This is the first time the window is being drawn,
     * so the client must call drawingFinished() when done
     */
    public static final int RELAYOUT_FIRST_TIME = 0x2;
    
    public static final int ADD_FLAG_APP_VISIBLE = 0x2;
    public static final int ADD_FLAG_IN_TOUCH_MODE = RELAYOUT_IN_TOUCH_MODE;
    
    public static final int ADD_OKAY = 0;
    public static final int ADD_BAD_APP_TOKEN = -1;
    public static final int ADD_BAD_SUBWINDOW_TOKEN = -2;
    public static final int ADD_NOT_APP_TOKEN = -3;
    public static final int ADD_APP_EXITING = -4;
    public static final int ADD_DUPLICATE_ADD = -5;
    public static final int ADD_STARTING_NOT_NEEDED = -6;
    public static final int ADD_MULTIPLE_SINGLETON = -7;
    public static final int ADD_PERMISSION_DENIED = -8;

    public static WindowManagerImpl getDefault()
    {
        return mWindowManager;
    }
    
    public void addView(View view)
    {
        addView(view, new WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.OPAQUE));
    }

    public void addView(View view, ViewGroup.LayoutParams params)
    {
        addView(view, params, false);
    }
    
    public void addViewNesting(View view, ViewGroup.LayoutParams params)
    {
        addView(view, params, false);
    }
    
    private void addView(View view, ViewGroup.LayoutParams params, boolean nest)
    {
        if (Config.LOGV) Log.v("WindowManager", "addView view=" + view);

        if (!(params instanceof WindowManager.LayoutParams)) {
            throw new IllegalArgumentException(
                    "Params must be WindowManager.LayoutParams");
        }

        final WindowManager.LayoutParams wparams
                = (WindowManager.LayoutParams)params;
        
        ViewRoot root;
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
            
            root = new ViewRoot(view.getContext());
            root.mAddNesting = 1;

            view.setLayoutParams(wparams);
            
            if (mViews == null) {
                index = 1;
                mViews = new View[1];
                mRoots = new ViewRoot[1];
                mParams = new WindowManager.LayoutParams[1];
            } else {
                index = mViews.length + 1;
                Object[] old = mViews;
                mViews = new View[index];
                System.arraycopy(old, 0, mViews, 0, index-1);
                old = mRoots;
                mRoots = new ViewRoot[index];
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
            ViewRoot root = mRoots[index];
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
                    + " but the ViewRoot is attached to " + curView);
        }
    }

    public void removeViewImmediate(View view) {
        synchronized (this) {
            int index = findViewLocked(view, true);
            ViewRoot root = mRoots[index];
            View curView = root.getView();
            
            root.mAddNesting = 0;
            root.die(true);
            finishRemoveViewLocked(curView, index);
            if (curView == view) {
                return;
            }
            
            throw new IllegalStateException("Calling with view " + view
                    + " but the ViewRoot is attached to " + curView);
        }
    }
    
    View removeViewLocked(int index) {
        ViewRoot root = mRoots[index];
        View view = root.getView();
        
        // Don't really remove until we have matched all calls to add().
        root.mAddNesting--;
        if (root.mAddNesting > 0) {
            return view;
        }

        InputMethodManager imm = InputMethodManager.getInstance(view.getContext());
        if (imm != null) {
            imm.windowDismissed(mViews[index].getWindowToken());
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
        
        ViewRoot[] tmpRoots = new ViewRoot[count-1];
        removeItem(tmpRoots, mRoots, index);
        mRoots = tmpRoots;
        
        WindowManager.LayoutParams[] tmpParams
                = new WindowManager.LayoutParams[count-1];
        removeItem(tmpParams, mParams, index);
        mParams = tmpParams;

        view.assignParent(null);
        // func doesn't allow null...  does it matter if we clear them?
        //view.setLayoutParams(null);
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
                    ViewRoot root = mRoots[i];
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
    
    public WindowManager.LayoutParams getRootViewLayoutParameter(View view) {
        ViewParent vp = view.getParent();
        while (vp != null && !(vp instanceof ViewRoot)) {
            vp = vp.getParent();
        }
        
        if (vp == null) return null;
        
        ViewRoot vr = (ViewRoot)vp;
        
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
        return new Display(Display.DEFAULT_DISPLAY);
    }

    private View[] mViews;
    private ViewRoot[] mRoots;
    private WindowManager.LayoutParams[] mParams;

    private static void removeItem(Object[] dst, Object[] src, int index)
    {
        if (dst.length > 0) {
            if (index > 0) {
                System.arraycopy(src, 0, dst, 0, index);
            }
            if (index < dst.length) {
                System.arraycopy(src, index+1, dst, index, src.length-index-1);
            }
        }
    }

    private int findViewLocked(View view, boolean required)
    {
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

    private static WindowManagerImpl mWindowManager = new WindowManagerImpl();
}
