package com.android.internal.custom.longshot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.WindowManagerGlobal;
import java.util.ArrayList;
import java.util.List;

public class LongScreenshotManagerService extends ILongScreenshotManager.Stub {
    public static final String PACKAGENAME_LONGSHOT = "com.android.screenshot";
    public static final ComponentName TAKE_SCREENSHOT_COMPONENT = new ComponentName(PACKAGENAME_LONGSHOT, PACKAGENAME_LONGSHOT + ".TakeScreenshotService");
    private static final ComponentName COMPONENT_LONGSHOT = new ComponentName(PACKAGENAME_LONGSHOT, PACKAGENAME_LONGSHOT + ".LongshotService");
    private static final String TAG = "Longshot.ManagerService";
    private static LongScreenshotManagerService sInstance = null;
    public Context mContext = null;
    private LongshotConnection mLongshot = new LongshotConnection();

    private class LongshotConnection extends ILongScreenshotCallback.Stub implements ServiceConnection {
        private List<ILongScreenshotListener> mListeners;
        public ILongScreenshot mService;

        private LongshotConnection() {
            mService = null;
            mListeners = new ArrayList();
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = ILongScreenshot.Stub.asInterface(service);
            try {
                mService.start(this);
            } catch (NullPointerException ignored) {
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception in onServiceConnected: ", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            stop();
        }

        @Override
        public void stop() {
            mContext.unbindService(this);
            mService = null;
            try {
                WindowManagerGlobal.getWindowManagerService().stopLongshotConnection();
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception in stop: ", e);
            }
        }

        @Override
        public void notifyMove() {
            synchronized (mListeners) {
                for (ILongScreenshotListener listener : mListeners) {
                    try {
                        listener.onMove();
                    } catch (RemoteException e) {
                        Log.w(TAG, "Remote exception in notifyMove: ", e);
                    }
                }
            }
        }

        public void registerListener(ILongScreenshotListener listener) {
            synchronized (mListeners) {
                mListeners.add(listener);
            }
        }

        public void unregisterListener(ILongScreenshotListener listener) {
            synchronized (mListeners) {
                mListeners.remove(listener);
            }
        }
    }

    private LongScreenshotManagerService(Context context) {
        mContext = context;
    }

    public static LongScreenshotManagerService getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new LongScreenshotManagerService(context);
        }
        return sInstance;
    }

    @Override
    public void takeLongshot(boolean statusBarVisible, boolean navBarVisible) {
        stopLongshot();
        bindService(createLongshotIntent(statusBarVisible, navBarVisible), mLongshot, 1);
    }

    @Override
    public void registerLongshotListener(ILongScreenshotListener listener) {
        mLongshot.registerListener(listener);
    }

    @Override
    public void unregisterLongshotListener(ILongScreenshotListener listener) {
        mLongshot.unregisterListener(listener);
    }

    @Override
    public void notifyLongshotScroll(boolean isOverScroll) {
        try {
            mLongshot.mService.notifyScroll(isOverScroll);
        } catch (NullPointerException ignored) {
        } catch (RemoteException e) {
            Log.w(TAG, "Remote exception in notifyLongshotScroll: ", e);
        }
    }

    @Override
    public boolean isLongshotMoveState() {
        try {
            return mLongshot.mService.isMoveState();
        } catch (NullPointerException ignored) {
        } catch (RemoteException e) {
            Log.w(TAG, "Remote exception in isLongshotMoveState: ", e);
        }
        return false;
    }

    @Override
    public boolean isLongshotHandleState() {
        try {
            return mLongshot.mService.isHandleState();
        } catch (NullPointerException ignored) {
        } catch (RemoteException e) {
            Log.w(TAG, "Remote exception in isHandleState: ", e);
        }
        return false;
    }

    @Override
    public void notifyScrollViewTop(int viewTop) {
        try {
            mLongshot.mService.notifyScrollViewTop(viewTop);
        } catch (NullPointerException ignored) {
        } catch (RemoteException e) {
            Log.w(TAG, "Remote exception in notifyScrollViewTop: ", e);
        }
    }

    @Override
    public void onUnscrollableView() {
        try {
            mLongshot.mService.onUnscrollableView();
        } catch (NullPointerException ignored) {
        } catch (RemoteException e) {
            Log.w(TAG, "Remote exception in onUnscrollableView: ", e);
        }
    }

    @Override
    public boolean isLongshotMode() {
        return mLongshot.mService != null;
    }

    @Override
    public void stopLongshot() {
        if (mLongshot.mService != null) {
            try {
                mLongshot.mService.stopLongshot();
            } catch (RemoteException e) {
                Log.w(TAG, "Remote exception in stopLongshot: ", e);
            }
        }
    }

    private Intent createIntent(ComponentName component) {
        return new Intent().setComponent(component);
    }

    private Intent createLongshotIntent(boolean statusBarVisible, boolean navBarVisible) {
        return createIntent(COMPONENT_LONGSHOT).putExtra(LongScreenshotManager.STATUSBAR_VISIBLE, statusBarVisible).putExtra(LongScreenshotManager.NAVIGATIONBAR_VISIBLE, navBarVisible);
    }

    private boolean bindService(Intent service, ServiceConnection conn, int flags) {
        if (service != null && conn != null) {
            return mContext.bindServiceAsUser(service, conn, flags, UserHandle.CURRENT);
        }
        return false;
    }
}
