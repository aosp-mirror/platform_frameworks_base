package com.android.uiautomator.core;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.UiAutomation;
import android.app.UiAutomationConnection;
import android.content.Intent;
import android.os.HandlerThread;
import android.os.RemoteException;

/**
 * @hide
 */
public class UiAutomationShellWrapper {

    private static final String HANDLER_THREAD_NAME = "UiAutomatorHandlerThread";

    private final HandlerThread mHandlerThread = new HandlerThread(HANDLER_THREAD_NAME);

    private UiAutomation mUiAutomation;

    public void connect() {
        if (mHandlerThread.isAlive()) {
            throw new IllegalStateException("Already connected!");
        }
        mHandlerThread.start();
        mUiAutomation = new UiAutomation(mHandlerThread.getLooper(),
                new UiAutomationConnection());
        mUiAutomation.connect();
    }

    /**
     * Enable or disable monkey test mode.
     *
     * Setting test as "monkey" indicates to some applications that a test framework is
     * running as a "monkey" type. Such applications may choose not to perform actions that
     * do submits so to avoid allowing monkey tests from doing harm or performing annoying
     * actions such as dialing 911 or posting messages to public forums, etc.
     *
     * @param isSet True to set as monkey test. False to set as regular functional test (default).
     * @see ActivityManager#isUserAMonkey()
     */
    public void setRunAsMonkey(boolean isSet) {
        IActivityManager am = ActivityManager.getService();
        if (am == null) {
            throw new RuntimeException("Can't manage monkey status; is the system running?");
        }
        try {
            if (isSet) {
                am.setActivityController(new DummyActivityController(), true);
            } else {
                am.setActivityController(null, true);
            }
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public void disconnect() {
        if (!mHandlerThread.isAlive()) {
            throw new IllegalStateException("Already disconnected!");
        }
        mUiAutomation.disconnect();
        mHandlerThread.quit();
    }

    public UiAutomation getUiAutomation() {
        return mUiAutomation;
    }

    public void setCompressedLayoutHierarchy(boolean compressed) {
        AccessibilityServiceInfo info = mUiAutomation.getServiceInfo();
        if (compressed)
            info.flags &= ~AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        else
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        mUiAutomation.setServiceInfo(info);
    }

    /**
     * Dummy, no interference, activity controller.
     */
    private class DummyActivityController extends IActivityController.Stub {
        @Override
        public boolean activityStarting(Intent intent, String pkg) throws RemoteException {
            /* do nothing and let activity proceed normally */
            return true;
        }

        @Override
        public boolean activityResuming(String pkg) throws RemoteException {
            /* do nothing and let activity proceed normally */
            return true;
        }

        @Override
        public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg,
                long timeMillis, String stackTrace) throws RemoteException {
            /* do nothing and let activity proceed normally */
            return true;
        }

        @Override
        public int appEarlyNotResponding(String processName, int pid, String annotation)
                throws RemoteException {
            /* do nothing and let activity proceed normally */
            return 0;
        }

        @Override
        public int appNotResponding(String processName, int pid, String processStats)
                throws RemoteException {
            /* do nothing and let activity proceed normally */
            return 0;
        }

        @Override
        public int systemNotResponding(String message)
                throws RemoteException {
            /* do nothing and let system proceed normally */
            return 0;
        }
    }
}
