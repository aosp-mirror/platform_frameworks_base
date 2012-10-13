package com.android.server.wm;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Pools.SynchronizedPool;
import android.view.DisplayInfo;
import android.view.IDisplayMagnificationController;
import android.view.IDisplayMagnificationMediator;
import android.view.MagnificationSpec;
import android.view.Surface;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import com.android.internal.os.SomeArgs;
import com.android.internal.policy.impl.PhoneWindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

final class DisplayMagnificationMediator extends IDisplayMagnificationMediator.Stub
        implements DisplayListener {
    private static final String LOG_TAG = DisplayMagnificationMediator.class.getSimpleName();

    private static final boolean DEBUG_WINDOW_TRANSITIONS = false;
    private static final boolean DEBUG_ROTATION = false;
    private static final boolean DEBUG_LAYERS = false;
    private static final boolean DEBUG_RECTANGLE_REQUESTED = false;

    private static final int MESSAGE_NOTIFY_MAGNIFIED_FRAME_CHANGED = 1;
    private static final int MESSAGE_NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED = 2;
    private static final int MESSAGE_NOTIFY_USER_CONTEXT_CHANGED = 3;
    private static final int MESSAGE_NOTIFY_ROTATION_CHANGED = 4;

    private static final String METHOD_SIGNATURE_ADD_CONTROLLER =
            "addController(int, IDisplayMagnificationController)";
    private static final String METHOD_SIGNATURE_REMOVE_CONTROLLER =
            "removeController(IDisplayMagnificationController, int)";
    private static final String METHOD_SIGNATURE_SET_MAGNIFICATION_SPEC =
            "setMagnificationSpec(IDisplayMagnificationController, MagnificationSpec)";

    private final Rect mTempRect = new Rect();
    private final Rect mTempRect1 = new Rect();
    private final Region mTempRegion = new Region();

    private final SparseArray<DisplayState> mDisplayStates =
            new SparseArray<DisplayMagnificationMediator.DisplayState>();

    private final Context mContext;
    private final WindowManagerService mWindowManagerService;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MESSAGE_NOTIFY_MAGNIFIED_FRAME_CHANGED: {
                    SomeArgs args = (SomeArgs) message.obj;
                    IDisplayMagnificationController client =
                            (IDisplayMagnificationController) args.arg1;
                    final int left = args.argi1;
                    final int top = args.argi2;
                    final int right = args.argi3;
                    final int bottom = args.argi4;
                    try {
                        client.onMagnifedFrameChanged(left, top, right, bottom);
                    } catch (RemoteException re) {
                        /* ignore */
                    } finally {
                        args.recycle();
                    }
                } break;
                case MESSAGE_NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED: {
                    SomeArgs args = (SomeArgs) message.obj;
                    IDisplayMagnificationController client =
                            (IDisplayMagnificationController) args.arg1;
                    final int left = args.argi1;
                    final int top = args.argi2;
                    final int right = args.argi3;
                    final int bottom = args.argi4;
                    try {
                        client.onRectangleOnScreenRequested(left, top, right, bottom);
                    } catch (RemoteException re) {
                        /* ignore */
                    } finally {
                        args.recycle();
                    }
                } break;
                case MESSAGE_NOTIFY_USER_CONTEXT_CHANGED: {
                    IDisplayMagnificationController client =
                            (IDisplayMagnificationController) message.obj;
                    try {
                        client.onUserContextChanged();
                    } catch (RemoteException re) {
                        /* ignore */
                    }
                } break;
                case MESSAGE_NOTIFY_ROTATION_CHANGED: {
                    IDisplayMagnificationController client =
                            (IDisplayMagnificationController) message.obj;
                    final int rotation = message.arg1;
                    try {
                        client.onRotationChanged(rotation);
                    } catch (RemoteException re) {
                        /* ignore */
                    }
                } break;
            }
        }
    };

    public DisplayMagnificationMediator(WindowManagerService windowManagerService) {
        mContext = windowManagerService.mContext;
        mWindowManagerService = windowManagerService;
        DisplayManager displayManager = (DisplayManager)
                mContext.getSystemService(Context.DISPLAY_SERVICE);
        displayManager.registerDisplayListener(this, mHandler);
    }

    @Override
    public void addController(int displayId, IDisplayMagnificationController controller) {
        enforceCallingPermission(Manifest.permission.MAGNIFY_DISPLAY,
                METHOD_SIGNATURE_ADD_CONTROLLER);
        synchronized (mWindowManagerService.mWindowMap) {
            DisplayState displayState = mDisplayStates.get(displayId);
            if (displayState != null) {
                displayState.clearLw();
            }
            mDisplayStates.remove(displayId);
            mDisplayStates.put(displayId, new DisplayState(displayId, controller));
        }
    }

    @Override
    public void removeController(IDisplayMagnificationController controller) {
        enforceCallingPermission(Manifest.permission.MAGNIFY_DISPLAY,
                METHOD_SIGNATURE_REMOVE_CONTROLLER);
        synchronized (mWindowManagerService.mWindowMap) {
            final int displayStateCount = mDisplayStates.size();
            for (int i = 0; i < displayStateCount; i++) {
                DisplayState displayState = mDisplayStates.valueAt(i);
                if (displayState.mClient.asBinder() == controller.asBinder()) {
                    displayState.clearLw();
                    mDisplayStates.removeAt(i);
                    return;
                }
            }
        }
    }

    @Override
    public void setMagnificationSpec(IDisplayMagnificationController controller,
            MagnificationSpec spec) {
        enforceCallingPermission(Manifest.permission.MAGNIFY_DISPLAY,
                METHOD_SIGNATURE_SET_MAGNIFICATION_SPEC);
        synchronized (mWindowManagerService.mWindowMap) {
            DisplayState displayState = null;
            final int displayStateCount = mDisplayStates.size();
            for (int i = 0; i < displayStateCount; i++) {
                DisplayState candidate = mDisplayStates.valueAt(i);
                if (candidate.mClient.asBinder() == controller.asBinder()) {
                    displayState = candidate;
                    break;
                }
            }
            if (displayState == null) {
                Slog.e(LOG_TAG, "Setting magnification spec for unregistered controller "
                        + controller);
                return;
            }
            displayState.mMagnificationSpec.initialize(spec.scale, spec.offsetX,
                    spec.offsetY);
            spec.recycle();
        }
        synchronized (mWindowManagerService.mLayoutToAnim) {
            mWindowManagerService.scheduleAnimationLocked();
        }
    }

    @Override
    public MagnificationSpec getCompatibleMagnificationSpec(IBinder windowToken) {
        synchronized (mWindowManagerService.mWindowMap) {
            WindowState windowState = mWindowManagerService.mWindowMap.get(windowToken);
            if (windowState == null) {
                return null;
            }
            MagnificationSpec spec = getMagnificationSpecLw(windowState);
            if ((spec == null || spec.isNop()) && windowState.mGlobalScale == 1.0f) {
                return null;
            }
            if (spec == null) {
                spec = MagnificationSpec.obtain();
            } else {
                spec = MagnificationSpec.obtain(spec);
            }
            spec.scale *= windowState.mGlobalScale;
            return spec;
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        /* do nothing */
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        synchronized (mWindowManagerService.mWindowMap) {
            DisplayState displayState = mDisplayStates.get(displayId);
            if (displayState != null) {
                displayState.clearLw();
                mDisplayStates.remove(displayId);
            }
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        /* do nothing */
    }

    public void onRectangleOnScreenRequestedLw(WindowState windowState, Rect rectangle,
            boolean immediate) {
        DisplayState displayState = mDisplayStates.get(windowState.getDisplayId());
        if (displayState == null) {
            return;
        }
        if (DEBUG_RECTANGLE_REQUESTED) {
            Slog.i(LOG_TAG, "Rectangle on screen requested: " + rectangle
                    + " displayId: " + windowState.getDisplayId());
        }
        if (!displayState.isMagnifyingLw()) {
            return;
        }
        Rect magnifiedRegionBounds = mTempRect1;
        displayState.getMagnifiedFrameInContentCoordsLw(magnifiedRegionBounds);
        if (magnifiedRegionBounds.contains(rectangle)) {
            return;
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = displayState.mClient;
        args.argi1 = rectangle.left;
        args.argi2 = rectangle.top;
        args.argi3 = rectangle.right;
        args.argi4 = rectangle.bottom;
        mHandler.obtainMessage(MESSAGE_NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED,
                args).sendToTarget();
    }

    public void onWindowLayersChangedLw(int displayId) {
        DisplayState displayState = mDisplayStates.get(displayId);
        if (displayState == null) {
            return;
        }
        if (DEBUG_LAYERS) {
            Slog.i(LOG_TAG, "Layers changed displayId: " + displayId);
        }
        displayState.mViewport.recomputeBoundsLw();
    }

    public void onRotationChangedLw(int displayId, int rotation) {
        DisplayState displayState = mDisplayStates.get(displayId);
        if (displayState == null) {
            return;
        }
        if (DEBUG_ROTATION) {
            Slog.i(LOG_TAG, "Rotaton: " + Surface.rotationToString(rotation)
                    + " displayId: " + displayId);
        }
        displayState.mViewport.recomputeBoundsLw();
        mHandler.obtainMessage(MESSAGE_NOTIFY_ROTATION_CHANGED, rotation, 0,
                displayState.mClient).sendToTarget();
    }

    public void onWindowTransitionLw(WindowState windowState, int transition) {
        DisplayState displayState = mDisplayStates.get(windowState.getDisplayId());
        if (displayState == null) {
            return;
        }
        if (DEBUG_WINDOW_TRANSITIONS) {
            Slog.i(LOG_TAG, "Window transition: "
                    + PhoneWindowManager.windowTransitionToString(transition)
                    + " displayId: " + windowState.getDisplayId());
        }
        final boolean magnifying = displayState.isMagnifyingLw();
        if (magnifying) {
            switch (transition) {
                case WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN:
                case WindowManagerPolicy.TRANSIT_TASK_OPEN:
                case WindowManagerPolicy.TRANSIT_TASK_TO_FRONT:
                case WindowManagerPolicy.TRANSIT_WALLPAPER_OPEN:
                case WindowManagerPolicy.TRANSIT_WALLPAPER_CLOSE:
                case WindowManagerPolicy.TRANSIT_WALLPAPER_INTRA_OPEN: {
                    mHandler.obtainMessage(MESSAGE_NOTIFY_USER_CONTEXT_CHANGED,
                            displayState.mClient).sendToTarget();
                }
            }
        }
        final int type = windowState.mAttrs.type;
        if (type == WindowManager.LayoutParams.TYPE_NAVIGATION_BAR
                || type == WindowManager.LayoutParams.TYPE_INPUT_METHOD
                || type == WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
                || type == WindowManager.LayoutParams.TYPE_KEYGUARD
                || type == WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG) {
            switch (transition) {
                case WindowManagerPolicy.TRANSIT_ENTER:
                case WindowManagerPolicy.TRANSIT_SHOW:
                case WindowManagerPolicy.TRANSIT_EXIT:
                case WindowManagerPolicy.TRANSIT_HIDE: {
                    displayState.mViewport.recomputeBoundsLw();
                } break;
            }
        }
        switch (transition) {
            case WindowManagerPolicy.TRANSIT_ENTER:
            case WindowManagerPolicy.TRANSIT_SHOW: {
                if (!magnifying) {
                    break;
                }
                switch (type) {
                    case WindowManager.LayoutParams.TYPE_APPLICATION:
                    case WindowManager.LayoutParams.TYPE_APPLICATION_PANEL:
                    case WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA:
                    case WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL:
                    case WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG:
                    case WindowManager.LayoutParams.TYPE_SEARCH_BAR:
                    case WindowManager.LayoutParams.TYPE_PHONE:
                    case WindowManager.LayoutParams.TYPE_SYSTEM_ALERT:
                    case WindowManager.LayoutParams.TYPE_TOAST:
                    case WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY:
                    case WindowManager.LayoutParams.TYPE_PRIORITY_PHONE:
                    case WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG:
                    case WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG:
                    case WindowManager.LayoutParams.TYPE_SYSTEM_ERROR:
                    case WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY:
                    case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL:
                    case WindowManager.LayoutParams.TYPE_RECENTS_OVERLAY: {
                        Rect magnifiedRegionBounds = mTempRect1;
                        displayState.getMagnifiedFrameInContentCoordsLw(magnifiedRegionBounds);
                        Rect touchableRegionBounds = mTempRect;
                        windowState.getTouchableRegion(mTempRegion);
                        mTempRegion.getBounds(touchableRegionBounds);
                        if (!magnifiedRegionBounds.intersect(touchableRegionBounds)) {
                            SomeArgs args = SomeArgs.obtain();
                            args.arg1 = displayState.mClient;
                            args.argi1 = touchableRegionBounds.left;
                            args.argi2 = touchableRegionBounds.top;
                            args.argi3 = touchableRegionBounds.right;
                            args.argi4 = touchableRegionBounds.bottom;
                            mHandler.obtainMessage(MESSAGE_NOTIFY_RECTANGLE_ON_SCREEN_REQUESTED,
                                    args).sendToTarget();
                        }
                    } break;
                } break;
            }
        }
    }

    public MagnificationSpec getMagnificationSpecLw(WindowState windowState) {
        DisplayState displayState = mDisplayStates.get(windowState.getDisplayId());
        if (displayState == null) {
            return null;
        }
        MagnificationSpec spec = displayState.mMagnificationSpec;
        if (spec != null && !spec.isNop()) {
            if (windowState.mAttachedWindow != null) {
                if (!canMagnifyWindow(windowState.mAttachedWindow.mAttrs.type)) {
                    return null;
                }
            }
            if (!canMagnifyWindow(windowState.mAttrs.type)) {
                return null;
            }
        }
        return spec;
    }

    private void enforceCallingPermission(String permission, String function) {
        if (Process.myPid() == Binder.getCallingPid()) {
            return;
        }
        if (mContext.checkCallingPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("The caller does not have " + permission
                    + " required to call " + function);
        }
    }

    private static boolean canMagnifyWindow(int type) {
        switch (type) {
            case WindowManager.LayoutParams.TYPE_INPUT_METHOD:
            case WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG:
            case WindowManager.LayoutParams.TYPE_NAVIGATION_BAR:
            case WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY: {
                return false;
            }
        }
        return true;
    }

    private static final class Viewport {

        private final ArrayList<WindowStateInfo> mTempWindowStateInfoList =
                new ArrayList<WindowStateInfo>();

        private final Rect mTempRect1 = new Rect();
        private final Rect mTempRect2 = new Rect();
        private final Rect mTempRect3 = new Rect();

        private final Rect mBounds = new Rect();
        private final Handler mHandler;
        private final IDisplayMagnificationController mClient;
        private final WindowManagerService mWindowManagerService;
        private final DisplayInfo mDisplayInfo;

        private final int mDisplayId;

        public Viewport(Context context, int displayId, Handler handler,
                IDisplayMagnificationController client, WindowManagerService windowManagerService) {
            mDisplayId = displayId;
            mHandler = handler;
            mWindowManagerService = windowManagerService;
            mDisplayInfo = mWindowManagerService.getDisplayContentLocked(displayId)
                    .getDisplayInfo();
            mClient = client;
            recomputeBoundsLw();
        }

        private final Comparator<WindowStateInfo> mWindowInfoInverseComparator =
                new Comparator<WindowStateInfo>() {
            @Override
            public int compare(WindowStateInfo lhs, WindowStateInfo rhs) {
                if (lhs.mWindowState.mLayer != rhs.mWindowState.mLayer) {
                    return rhs.mWindowState.mLayer - lhs.mWindowState.mLayer;
                }
                if (lhs.mTouchableRegion.top != rhs.mTouchableRegion.top) {
                    return rhs.mTouchableRegion.top - lhs.mTouchableRegion.top;
                }
                if (lhs.mTouchableRegion.left != rhs.mTouchableRegion.left) {
                    return rhs.mTouchableRegion.left - lhs.mTouchableRegion.left;
                }
                if (lhs.mTouchableRegion.right != rhs.mTouchableRegion.right) {
                    return rhs.mTouchableRegion.right - lhs.mTouchableRegion.right;
                }
                if (lhs.mTouchableRegion.bottom != rhs.mTouchableRegion.bottom) {
                    return rhs.mTouchableRegion.bottom - lhs.mTouchableRegion.bottom;
                }
                return 0;
            }
        };

        public void recomputeBoundsLw() {
            Rect magnifiedFrame = mBounds;
            magnifiedFrame.set(0, 0, 0, 0);

            Rect oldmagnifiedFrame = mTempRect3;
            oldmagnifiedFrame.set(magnifiedFrame);

            Rect availableFrame = mTempRect1;
            availableFrame.set(0, 0, mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);

            ArrayList<WindowStateInfo> visibleWindows = mTempWindowStateInfoList;
            visibleWindows.clear();
            getVisibleWindowsLw(visibleWindows);

            Collections.sort(visibleWindows, mWindowInfoInverseComparator);

            final int visibleWindowCount = visibleWindows.size();
            for (int i = 0; i < visibleWindowCount; i++) {
                WindowStateInfo info = visibleWindows.get(i);
                if (info.mWindowState.mAttrs.type == WindowManager
                        .LayoutParams.TYPE_MAGNIFICATION_OVERLAY) {
                    continue;
                }
                Rect windowFrame = mTempRect2;
                windowFrame.set(info.mTouchableRegion);
                if (canMagnifyWindow(info.mWindowState.mAttrs.type)) {
                    magnifiedFrame.union(windowFrame);
                    magnifiedFrame.intersect(availableFrame);
                } else {
                    subtract(windowFrame, magnifiedFrame);
                    subtract(availableFrame, windowFrame);
                }
                if (availableFrame.equals(magnifiedFrame)) {
                    break;
                }
            }
            for (int i = visibleWindowCount - 1; i >= 0; i--) {
                visibleWindows.remove(i).recycle();
            }

            final int displayWidth = mDisplayInfo.logicalWidth;
            final int displayHeight = mDisplayInfo.logicalHeight;
            magnifiedFrame.intersect(0, 0, displayWidth, displayHeight);

            if (!oldmagnifiedFrame.equals(magnifiedFrame)) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = mClient;
                args.argi1 = magnifiedFrame.left;
                args.argi2 = magnifiedFrame.top;
                args.argi3 = magnifiedFrame.right;
                args.argi4 = magnifiedFrame.bottom;
                mHandler.obtainMessage(MESSAGE_NOTIFY_MAGNIFIED_FRAME_CHANGED, args)
                        .sendToTarget();
            }
        }

        private void getVisibleWindowsLw(ArrayList<WindowStateInfo> outWindowStates) {
            DisplayContent displayContent = mWindowManagerService.getDisplayContentLocked(
                    mDisplayId);
            WindowList windowList = displayContent.getWindowList();
            final int windowCount = windowList.size();
            for (int i = 0; i < windowCount; i++) {
                WindowState windowState = windowList.get(i);
                if (windowState.isVisibleLw() || windowState.mAttrs.type == WindowManager
                        .LayoutParams.TYPE_UNIVERSE_BACKGROUND) {
                    outWindowStates.add(WindowStateInfo.obtain(windowState));
                }
            }
        }

        public Rect getBoundsLw() {
            return mBounds;
        }

        private static boolean subtract(Rect lhs, Rect rhs) {
            if (lhs.right < rhs.left || lhs.left  > rhs.right
                    || lhs.bottom < rhs.top || lhs.top > rhs.bottom) {
                return false;
            }
            if (lhs.left < rhs.left) {
                lhs.right = rhs.left;
            }
            if (lhs.top < rhs.top) {
                lhs.bottom = rhs.top;
            }
            if (lhs.right > rhs.right) {
                lhs.left = rhs.right;
            }
            if (lhs.bottom > rhs.bottom) {
                lhs.top = rhs.bottom;
            }
            return true;
        }

        private static final class WindowStateInfo {
            private static final int MAX_POOL_SIZE = 30;

            private static final SynchronizedPool<WindowStateInfo> sPool =
                    new SynchronizedPool<WindowStateInfo>(MAX_POOL_SIZE);

            private static final Region mTempRegion = new Region();

            public WindowState mWindowState;
            public final Rect mTouchableRegion = new Rect();

            public static WindowStateInfo obtain(WindowState windowState) {
                WindowStateInfo info = sPool.acquire();
                if (info == null) {
                    info = new WindowStateInfo();
                }
                info.mWindowState = windowState;
                windowState.getTouchableRegion(mTempRegion);
                mTempRegion.getBounds(info.mTouchableRegion);
                return info;
            }

            public void recycle() {
                mWindowState = null;
                mTouchableRegion.setEmpty();
                sPool.release(this);
            }
        }
    }

    private final class DisplayState {
        final int mDisplayId;
        final MagnificationSpec mMagnificationSpec;
        final Viewport mViewport;
        final IDisplayMagnificationController mClient;

        DisplayState(int displayId, IDisplayMagnificationController client) {
            mDisplayId = displayId;
            mClient = client;
            mMagnificationSpec = MagnificationSpec.obtain();
            mViewport = new Viewport(mContext, mDisplayId, mHandler,
                    mClient, mWindowManagerService);
        }

        public boolean isMagnifyingLw() {
            return mMagnificationSpec.scale > 1.0f;
        }

        private void getMagnifiedFrameInContentCoordsLw(Rect rect) {
            MagnificationSpec spec = mMagnificationSpec;
            rect.set(mViewport.getBoundsLw());
            rect.offset((int) -spec.offsetX, (int) -spec.offsetY);
            rect.scale(1.0f / spec.scale);
        }

        public void clearLw() {
            mMagnificationSpec.recycle();
        }
    }
}
