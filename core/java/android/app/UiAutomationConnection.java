/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.IWindowManager;
import android.view.InputEvent;
import android.view.SurfaceControl;
import android.view.WindowAnimationFrameStats;
import android.view.WindowContentFrameStats;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.IAccessibilityManager;
import libcore.io.IoUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * This is a remote object that is passed from the shell to an instrumentation
 * for enabling access to privileged operations which the shell can do and the
 * instrumentation cannot. These privileged operations are needed for implementing
 * a {@link UiAutomation} that enables across application testing by simulating
 * user actions and performing screen introspection.
 *
 * @hide
 */
public final class UiAutomationConnection extends IUiAutomationConnection.Stub {

    private static final int INITIAL_FROZEN_ROTATION_UNSPECIFIED = -1;

    private final IWindowManager mWindowManager = IWindowManager.Stub.asInterface(
            ServiceManager.getService(Service.WINDOW_SERVICE));

    private final IAccessibilityManager mAccessibilityManager = IAccessibilityManager.Stub
            .asInterface(ServiceManager.getService(Service.ACCESSIBILITY_SERVICE));

    private final Object mLock = new Object();

    private final Binder mToken = new Binder();

    private int mInitialFrozenRotation = INITIAL_FROZEN_ROTATION_UNSPECIFIED;

    private IAccessibilityServiceClient mClient;

    private boolean mIsShutdown;

    private int mOwningUid;

    public void connect(IAccessibilityServiceClient client) {
        if (client == null) {
            throw new IllegalArgumentException("Client cannot be null!");
        }
        synchronized (mLock) {
            throwIfShutdownLocked();
            if (isConnectedLocked()) {
                throw new IllegalStateException("Already connected.");
            }
            mOwningUid = Binder.getCallingUid();
            registerUiTestAutomationServiceLocked(client);
            storeRotationStateLocked();
        }
    }

    @Override
    public void disconnect() {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            if (!isConnectedLocked()) {
                throw new IllegalStateException("Already disconnected.");
            }
            mOwningUid = -1;
            unregisterUiTestAutomationServiceLocked();
            restoreRotationStateLocked();
        }
    }

    @Override
    public boolean injectInputEvent(InputEvent event, boolean sync) {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final int mode = (sync) ? InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
                : InputManager.INJECT_INPUT_EVENT_MODE_ASYNC;
        final long identity = Binder.clearCallingIdentity();
        try {
            return InputManager.getInstance().injectInputEvent(event, mode);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean setRotation(int rotation) {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            if (rotation == UiAutomation.ROTATION_UNFREEZE) {
                mWindowManager.thawRotation();
            } else {
                mWindowManager.freezeRotation(rotation);
            }
            return true;
        } catch (RemoteException re) {
            /* ignore */
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    @Override
    public Bitmap takeScreenshot(int width, int height) {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return SurfaceControl.screenshot(width, height);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean clearWindowContentFrameStats(int windowId) throws RemoteException {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            IBinder token = mAccessibilityManager.getWindowToken(windowId);
            if (token == null) {
                return false;
            }
            return mWindowManager.clearWindowContentFrameStats(token);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public WindowContentFrameStats getWindowContentFrameStats(int windowId) throws RemoteException {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            IBinder token = mAccessibilityManager.getWindowToken(windowId);
            if (token == null) {
                return null;
            }
            return mWindowManager.getWindowContentFrameStats(token);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void clearWindowAnimationFrameStats() {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            SurfaceControl.clearAnimationFrameStats();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public WindowAnimationFrameStats getWindowAnimationFrameStats() {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            WindowAnimationFrameStats stats = new WindowAnimationFrameStats();
            SurfaceControl.getAnimationFrameStats(stats);
            return stats;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void executeShellCommand(String command, ParcelFileDescriptor sink)
            throws RemoteException {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }

        InputStream in = null;
        OutputStream out = null;

        try {
            java.lang.Process process = Runtime.getRuntime().exec(command);

            in = process.getInputStream();
            out = new FileOutputStream(sink.getFileDescriptor());

            final byte[] buffer = new byte[8192];
            while (true) {
                final int readByteCount = in.read(buffer);
                if (readByteCount < 0) {
                    break;
                }
                out.write(buffer, 0, readByteCount);
            }
        } catch (IOException ioe) {
            throw new RuntimeException("Error running shell command", ioe);
        } finally {
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
            IoUtils.closeQuietly(sink);
        }
    }

    @Override
    public void shutdown() {
        synchronized (mLock) {
            if (isConnectedLocked()) {
                throwIfCalledByNotTrustedUidLocked();
            }
            throwIfShutdownLocked();
            mIsShutdown = true;
            if (isConnectedLocked()) {
                disconnect();
            }
        }
    }

    private void registerUiTestAutomationServiceLocked(IAccessibilityServiceClient client) {
        IAccessibilityManager manager = IAccessibilityManager.Stub.asInterface(
                ServiceManager.getService(Context.ACCESSIBILITY_SERVICE));
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.setCapabilities(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
                | AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION
                | AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_ENHANCED_WEB_ACCESSIBILITY
                | AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS);
        try {
            // Calling out with a lock held is fine since if the system
            // process is gone the client calling in will be killed.
            manager.registerUiTestAutomationService(mToken, client, info);
            mClient = client;
        } catch (RemoteException re) {
            throw new IllegalStateException("Error while registering UiTestAutomationService.", re);
        }
    }

    private void unregisterUiTestAutomationServiceLocked() {
        IAccessibilityManager manager = IAccessibilityManager.Stub.asInterface(
              ServiceManager.getService(Context.ACCESSIBILITY_SERVICE));
        try {
            // Calling out with a lock held is fine since if the system
            // process is gone the client calling in will be killed.
            manager.unregisterUiTestAutomationService(mClient);
            mClient = null;
        } catch (RemoteException re) {
            throw new IllegalStateException("Error while unregistering UiTestAutomationService",
                    re);
        }
    }

    private void storeRotationStateLocked() {
        try {
            if (mWindowManager.isRotationFrozen()) {
                // Calling out with a lock held is fine since if the system
                // process is gone the client calling in will be killed.
                mInitialFrozenRotation = mWindowManager.getRotation();
            }
        } catch (RemoteException re) {
            /* ignore */
        }
    }

    private void restoreRotationStateLocked() {
        try {
            if (mInitialFrozenRotation != INITIAL_FROZEN_ROTATION_UNSPECIFIED) {
                // Calling out with a lock held is fine since if the system
                // process is gone the client calling in will be killed.
                mWindowManager.freezeRotation(mInitialFrozenRotation);
            } else {
                // Calling out with a lock held is fine since if the system
                // process is gone the client calling in will be killed.
                mWindowManager.thawRotation();
            }
        } catch (RemoteException re) {
            /* ignore */
        }
    }

    private boolean isConnectedLocked() {
        return mClient != null;
    }

    private void throwIfShutdownLocked() {
        if (mIsShutdown) {
            throw new IllegalStateException("Connection shutdown!");
        }
    }

    private void throwIfNotConnectedLocked() {
        if (!isConnectedLocked()) {
            throw new IllegalStateException("Not connected!");
        }
    }

    private void throwIfCalledByNotTrustedUidLocked() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != mOwningUid && mOwningUid != Process.SYSTEM_UID
                && callingUid != 0 /*root*/) {
            throw new SecurityException("Calling from not trusted UID!");
        }
    }
}
