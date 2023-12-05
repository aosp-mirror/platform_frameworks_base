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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.HardwareRenderer;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.view.inputmethod.InputMethodManager;
import android.window.ITrustedPresentationListener;
import android.window.TrustedPresentationThresholds;

import com.android.internal.util.FastPrintWriter;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

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
     * This is the first time the window is being drawn,
     * so the client must call drawingFinished() when done
     */
    public static final int RELAYOUT_RES_FIRST_TIME = 1;

    /**
     * The window manager has changed the surface from the last call.
     */
    public static final int RELAYOUT_RES_SURFACE_CHANGED = 1 << 1;

    /**
     * The window manager has changed the size of the surface from the last call.
     */
    public static final int RELAYOUT_RES_SURFACE_RESIZED = 1 << 2;

    /**
     * In multi-window we force show the system bars. Because we don't want that the surface size
     * changes in this mode, we instead have a flag whether the system bar sizes should always be
     * consumed, so the app is treated like there is no virtual system bars at all.
     */
    public static final int RELAYOUT_RES_CONSUME_ALWAYS_SYSTEM_BARS = 1 << 3;

    /**
     * The window manager has told the window it cannot draw this frame and should retry again.
     */
    public static final int RELAYOUT_RES_CANCEL_AND_REDRAW = 1 << 4;

    /**
     * Flag for relayout: the client will be later giving
     * internal insets; as a result, the window will not impact other window
     * layouts until the insets are given.
     */
    public static final int RELAYOUT_INSETS_PENDING = 0x1;

    public static final int ADD_FLAG_IN_TOUCH_MODE = 0x1;
    public static final int ADD_FLAG_APP_VISIBLE = 0x2;

    /**
     * Like {@link #RELAYOUT_RES_CONSUME_ALWAYS_SYSTEM_BARS}, but as a "hint" when adding the
     * window.
     */
    public static final int ADD_FLAG_ALWAYS_CONSUME_SYSTEM_BARS = 0x4;

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
    public static final int ADD_INVALID_USER = -11;

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

    private final ArrayList<ViewRootImpl> mWindowlessRoots = new ArrayList<ViewRootImpl>();

    /** A context token only has one remote registration to system. */
    private WeakHashMap<IBinder, ProposedRotationListenerDelegate> mProposedRotationListenerMap;

    private Runnable mSystemPropertyUpdater;

    private final TrustedPresentationListener mTrustedPresentationListener =
            new TrustedPresentationListener();

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

    @Nullable
    @UnsupportedAppUsage
    public static IWindowManager getWindowManagerService() {
        synchronized (WindowManagerGlobal.class) {
            if (sWindowManagerService == null) {
                sWindowManagerService = IWindowManager.Stub.asInterface(
                        ServiceManager.getService("window"));
                try {
                    // Can be null if this is called before WindowManagerService is initialized.
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
            final int windowlessRoots = mWindowlessRoots.size();
            String[] mViewRoots = new String[numRoots + windowlessRoots];
            for (int i = 0; i < numRoots; ++i) {
                mViewRoots[i] = getWindowName(mRoots.get(i));
            }
            for (int i = 0; i < windowlessRoots; ++i) {
                mViewRoots[i + numRoots] = getWindowName(mWindowlessRoots.get(i));
            }
            return mViewRoots;
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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

    /**
     * @return the list of all views attached to the global window manager
     */
    @NonNull
    public ArrayList<View> getWindowViews() {
        synchronized (mLock) {
            return new ArrayList<>(mViews);
        }
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
            for (int i = mWindowlessRoots.size() - 1; i >= 0; --i) {
                final ViewRootImpl root = mWindowlessRoots.get(i);
                if (name.equals(getWindowName(root))) return root.getView();
            }
        }

        return null;
    }

    public void addView(View view, ViewGroup.LayoutParams params,
            Display display, Window parentWindow, int userId) {
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

            IWindowSession windowlessSession = null;
            // If there is a parent set, but we can't find it, it may be coming
            // from a SurfaceControlViewHost hierarchy.
            if (wparams.token != null && panelParentView == null) {
                for (int i = 0; i < mWindowlessRoots.size(); i++) {
                    ViewRootImpl maybeParent = mWindowlessRoots.get(i);
                    if (maybeParent.getWindowToken() == wparams.token) {
                        windowlessSession = maybeParent.getWindowSession();
                        break;
                    }
                }
            }

            if (windowlessSession == null) {
                root = new ViewRootImpl(view.getContext(), display);
            } else {
                root = new ViewRootImpl(view.getContext(), display,
                        windowlessSession, new WindowlessWindowLayout());
            }

            view.setLayoutParams(wparams);

            mViews.add(view);
            mRoots.add(root);
            mParams.add(wparams);

            // do this last because it fires off messages to start doing things
            try {
                root.setView(view, wparams, panelParentView, userId);
            } catch (RuntimeException e) {
                final int viewIndex = (index >= 0) ? index : (mViews.size() - 1);
                // BadTokenException or InvalidDisplayException, clean up.
                if (viewIndex >= 0) {
                    removeViewLocked(viewIndex, true);
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

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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

        if (root != null) {
            root.getImeFocusController().onWindowDismissed();
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
        boolean allViewsRemoved;
        synchronized (mLock) {
            final int index = mRoots.indexOf(root);
            if (index >= 0) {
                mRoots.remove(index);
                mParams.remove(index);
                final View view = mViews.remove(index);
                mDyingViews.remove(view);
            }
            allViewsRemoved = mRoots.isEmpty();
        }

        // If we don't have any views anymore in our process, we no longer need the
        // InsetsAnimationThread to save some resources.
        if (allViewsRemoved) {
            InsetsAnimationThread.release();
        }
    }

    private int findViewLocked(View view, boolean required) {
        final int index = mViews.indexOf(view);
        if (required && index < 0) {
            throw new IllegalArgumentException("View=" + view + " not attached to window manager");
        }
        return index;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public void trimMemory(int level) {
        ThreadedRenderer.trimMemory(level);
    }

    /** @hide */
    public void trimCaches(@HardwareRenderer.CacheTrimLevel int level) {
        ThreadedRenderer.trimCaches(level);
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

                ViewRootImpl.GfxInfo totals = new ViewRootImpl.GfxInfo();

                for (int i = 0; i < count; i++) {
                    ViewRootImpl root = mRoots.get(i);
                    ViewRootImpl.GfxInfo info = root.getGfxInfo();
                    totals.add(info);

                    String name = getWindowName(root);
                    pw.printf("  %s\n  %d views, %.2f kB of render nodes",
                            name, info.viewCount, info.renderNodeMemoryUsage / 1024.f);
                    pw.printf("\n\n");
                }

                pw.printf("\nTotal %-15s: %d\n", "ViewRootImpl", count);
                pw.printf("Total %-15s: %d\n", "attached Views", totals.viewCount);
                pw.printf("Total %-15s: %.2f kB (used) / %.2f kB (capacity)\n\n", "RenderNode",
                        totals.renderNodeMemoryUsage / 1024.0f,
                        totals.renderNodeMemoryAllocated / 1024.0f);
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
        ArrayList<ViewRootImpl> nonCurrentThreadRoots = null;
        synchronized (mLock) {
            int count = mViews.size();
            for (int i = count - 1; i >= 0; i--) {
                if (token == null || mParams.get(i).token == token) {
                    ViewRootImpl root = mRoots.get(i);
                    // Client might remove the view by "stopped" event.
                    if (root.mThread == Thread.currentThread()) {
                        root.setWindowStopped(stopped);
                    } else {
                        if (nonCurrentThreadRoots == null) {
                            nonCurrentThreadRoots = new ArrayList<>();
                        }
                        nonCurrentThreadRoots.add(root);
                    }
                    // Recursively forward stopped state to View's attached
                    // to this Window rather than the root application token,
                    // e.g. PopupWindow's.
                    setStoppedState(root.mAttachInfo.mWindowToken, stopped);
                }
            }
        }

        // Update the stopped state synchronously to ensure the surface won't be used after server
        // side has destroyed it. This operation should be outside the lock to avoid any potential
        // paths from setWindowStopped to WindowManagerGlobal which may cause deadlocks.
        if (nonCurrentThreadRoots != null) {
            for (int i = nonCurrentThreadRoots.size() - 1; i >= 0; i--) {
                ViewRootImpl root = nonCurrentThreadRoots.get(i);
                root.mHandler.runWithScissors(() -> root.setWindowStopped(stopped), 0);
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

    /** @hide */
    @Nullable
    public SurfaceControl mirrorWallpaperSurface(int displayId) {
        try {
            return getWindowManagerService().mirrorWallpaperSurface(displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Registers the listener to the context token and returns the current proposed rotation. */
    public void registerProposedRotationListener(IBinder contextToken, Executor executor,
            IntConsumer listener) {
        ProposedRotationListenerDelegate delegate;
        synchronized (mLock) {
            if (mProposedRotationListenerMap == null) {
                mProposedRotationListenerMap = new WeakHashMap<>(1);
            }
            delegate = mProposedRotationListenerMap.get(contextToken);
            final ProposedRotationListenerDelegate existingDelegate = delegate;
            if (delegate == null) {
                mProposedRotationListenerMap.put(contextToken,
                        delegate = new ProposedRotationListenerDelegate());
            }
            if (!delegate.add(executor, listener)) {
                // Duplicated listener.
                return;
            }
            if (existingDelegate != null) {
                executor.execute(() -> listener.accept(existingDelegate.mLastRotation));
                return;
            }
        }
        try {
            final int currentRotation = getWindowManagerService().registerProposedRotationListener(
                    contextToken, delegate);
            delegate.onRotationChanged(currentRotation);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Unregisters the proposed rotation listener of the given token. */
    public void unregisterProposedRotationListener(IBinder contextToken, IntConsumer listener) {
        final ProposedRotationListenerDelegate delegate;
        synchronized (mLock) {
            if (mProposedRotationListenerMap == null) {
                return;
            }
            delegate = mProposedRotationListenerMap.get(contextToken);
            if (delegate == null) {
                return;
            }
            if (delegate.remove(listener)) {
                // The delegate becomes empty.
                mProposedRotationListenerMap.remove(contextToken);
            } else {
                // The delegate still contains other listeners.
                return;
            }
        }
        try {
            getWindowManagerService().removeRotationWatcher(delegate);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private static class ProposedRotationListenerDelegate extends IRotationWatcher.Stub {
        static class ListenerWrapper {
            final Executor mExecutor;
            final WeakReference<IntConsumer> mListener;

            ListenerWrapper(Executor executor, IntConsumer listener) {
                mExecutor = executor;
                mListener = new WeakReference<>(listener);
            }
        }

        /** The registered listeners. */
        private final ArrayList<ListenerWrapper> mListeners = new ArrayList<>(1);
        /** A thread-safe copy of registered listeners for dispatching events. */
        private volatile ListenerWrapper[] mListenerArray;
        int mLastRotation;

        boolean add(Executor executor, IntConsumer listener) {
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                if (mListeners.get(i).mListener.get() == listener) {
                    // Ignore adding duplicated listener.
                    return false;
                }
            }
            mListeners.add(new ListenerWrapper(executor, listener));
            mListenerArray = mListeners.toArray(new ListenerWrapper[0]);
            return true;
        }

        boolean remove(IntConsumer listener) {
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                if (mListeners.get(i).mListener.get() == listener) {
                    mListeners.remove(i);
                    mListenerArray = mListeners.toArray(new ListenerWrapper[0]);
                    return mListeners.isEmpty();
                }
            }
            return false;
        }

        @Override
        public void onRotationChanged(int rotation) {
            mLastRotation = rotation;
            boolean alive = false;
            for (ListenerWrapper listenerWrapper : mListenerArray) {
                final IntConsumer listener = listenerWrapper.mListener.get();
                if (listener != null) {
                    listenerWrapper.mExecutor.execute(() -> listener.accept(rotation));
                    alive = true;
                }
            }
            if (!alive) {
                // Unregister if there is no strong reference.
                try {
                    getWindowManagerService().removeRotationWatcher(this);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }
    }

    public void registerTrustedPresentationListener(@NonNull IBinder window,
            @NonNull TrustedPresentationThresholds thresholds, Executor executor,
            @NonNull Consumer<Boolean> listener) {
        mTrustedPresentationListener.addListener(window, thresholds, listener, executor);
    }

    public void unregisterTrustedPresentationListener(@NonNull Consumer<Boolean> listener) {
        mTrustedPresentationListener.removeListener(listener);
    }

    private final class TrustedPresentationListener extends
            ITrustedPresentationListener.Stub {
        private static int sId = 0;
        private final ArrayMap<Consumer<Boolean>, Pair<Integer, Executor>> mListeners =
                new ArrayMap<>();

        private final Object mTplLock = new Object();

        private void addListener(IBinder window, TrustedPresentationThresholds thresholds,
                Consumer<Boolean> listener, Executor executor) {
            synchronized (mTplLock) {
                if (mListeners.containsKey(listener)) {
                    Log.i(TAG, "Updating listener " + listener + " thresholds to " + thresholds);
                    removeListener(listener);
                }
                int id = sId++;
                mListeners.put(listener, new Pair<>(id, executor));
                try {
                    WindowManagerGlobal.getWindowManagerService()
                            .registerTrustedPresentationListener(window, this, thresholds, id);
                } catch (RemoteException e) {
                    e.rethrowAsRuntimeException();
                }
            }
        }

        private void removeListener(Consumer<Boolean> listener) {
            synchronized (mTplLock) {
                var removedListener = mListeners.remove(listener);
                if (removedListener == null) {
                    Log.i(TAG, "listener " + listener + " does not exist.");
                    return;
                }

                try {
                    WindowManagerGlobal.getWindowManagerService()
                            .unregisterTrustedPresentationListener(this, removedListener.first);
                } catch (RemoteException e) {
                    e.rethrowAsRuntimeException();
                }
            }
        }

        @Override
        public void onTrustedPresentationChanged(int[] inTrustedStateListenerIds,
                int[] outOfTrustedStateListenerIds) {
            ArrayList<Runnable> firedListeners = new ArrayList<>();
            synchronized (mTplLock) {
                mListeners.forEach((listener, idExecutorPair) -> {
                    final var listenerId =  idExecutorPair.first;
                    final var executor = idExecutorPair.second;
                    for (int id : inTrustedStateListenerIds) {
                        if (listenerId == id) {
                            firedListeners.add(() -> executor.execute(
                                    () -> listener.accept(/*presentationState*/true)));
                        }
                    }
                    for (int id : outOfTrustedStateListenerIds) {
                        if (listenerId == id) {
                            firedListeners.add(() -> executor.execute(
                                    () -> listener.accept(/*presentationState*/false)));
                        }
                    }
                });
            }
            for (int i = 0; i < firedListeners.size(); i++) {
                firedListeners.get(i).run();
            }
        }
    }

    /** @hide */
    public void addWindowlessRoot(ViewRootImpl impl) {
        synchronized (mLock) {
            mWindowlessRoots.add(impl);
        }
    }

    /** @hide */
    public void removeWindowlessRoot(ViewRootImpl impl) {
        synchronized (mLock) {
            mWindowlessRoots.remove(impl);
        }
    }

    public void setRecentsAppBehindSystemBars(boolean behindSystemBars) {
        try {
            getWindowManagerService().setRecentsAppBehindSystemBars(behindSystemBars);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}

final class WindowLeaked extends AndroidRuntimeException {
    @UnsupportedAppUsage
    public WindowLeaked(String msg) {
        super(msg);
    }
}
