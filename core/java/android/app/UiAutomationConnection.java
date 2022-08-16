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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.permission.IPermissionManager;
import android.util.Log;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowAnimationFrameStats;
import android.view.WindowContentFrameStats;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.IAccessibilityManager;

import libcore.io.IoUtils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

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

    private static final String TAG = "UiAutomationConnection";

    private static final int INITIAL_FROZEN_ROTATION_UNSPECIFIED = -1;

    private final IWindowManager mWindowManager = IWindowManager.Stub.asInterface(
            ServiceManager.getService(Service.WINDOW_SERVICE));

    private final IAccessibilityManager mAccessibilityManager = IAccessibilityManager.Stub
            .asInterface(ServiceManager.getService(Service.ACCESSIBILITY_SERVICE));

    private final IPermissionManager mPermissionManager = IPermissionManager.Stub
            .asInterface(ServiceManager.getService("permissionmgr"));

    private final IActivityManager mActivityManager = IActivityManager.Stub
            .asInterface(ServiceManager.getService("activity"));

    private final Object mLock = new Object();

    private final Binder mToken = new Binder();

    private int mInitialFrozenRotation = INITIAL_FROZEN_ROTATION_UNSPECIFIED;

    private IAccessibilityServiceClient mClient;

    private boolean mIsShutdown;

    private int mOwningUid;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public UiAutomationConnection() {
    }

    @Override
    public void connect(IAccessibilityServiceClient client, int flags) {
        if (client == null) {
            throw new IllegalArgumentException("Client cannot be null!");
        }
        synchronized (mLock) {
            throwIfShutdownLocked();
            if (isConnectedLocked()) {
                throw new IllegalStateException("Already connected.");
            }
            mOwningUid = Binder.getCallingUid();
            registerUiTestAutomationServiceLocked(client, flags);
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
    public boolean injectInputEvent(InputEvent event, boolean sync, boolean waitForAnimations) {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }

        final boolean syncTransactionsBefore;
        final boolean syncTransactionsAfter;
        if (event instanceof KeyEvent) {
            KeyEvent keyEvent = (KeyEvent) event;
            syncTransactionsBefore = keyEvent.getAction() == KeyEvent.ACTION_DOWN;
            syncTransactionsAfter = keyEvent.getAction() == KeyEvent.ACTION_UP;
        } else {
            MotionEvent motionEvent = (MotionEvent) event;
            syncTransactionsBefore = motionEvent.getAction() == MotionEvent.ACTION_DOWN
                    || motionEvent.isFromSource(InputDevice.SOURCE_MOUSE);
            syncTransactionsAfter = motionEvent.getAction() == MotionEvent.ACTION_UP;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            if (syncTransactionsBefore) {
                mWindowManager.syncInputTransactions(waitForAnimations);
            }

            final boolean result = InputManager.getInstance().injectInputEvent(event,
                    sync ? InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
                            : InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);

            if (syncTransactionsAfter) {
                mWindowManager.syncInputTransactions(waitForAnimations);
            }
            return result;
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    @Override
    public void syncInputTransactions(boolean waitForAnimations) {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }

        try {
            mWindowManager.syncInputTransactions(waitForAnimations);
        } catch (RemoteException e) {
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
    public Bitmap takeScreenshot(Rect crop) {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            int width = crop.width();
            int height = crop.height();
            final IBinder displayToken = SurfaceControl.getInternalDisplayToken();
            final SurfaceControl.DisplayCaptureArgs captureArgs =
                    new SurfaceControl.DisplayCaptureArgs.Builder(displayToken)
                            .setSourceCrop(crop)
                            .setSize(width, height)
                            .build();
            final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
                    SurfaceControl.captureDisplay(captureArgs);
            return screenshotBuffer == null ? null : screenshotBuffer.asBitmap();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Nullable
    @Override
    public Bitmap takeSurfaceControlScreenshot(@NonNull SurfaceControl surfaceControl) {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }

        SurfaceControl.ScreenshotHardwareBuffer captureBuffer;
        final long identity = Binder.clearCallingIdentity();
        try {
            captureBuffer = SurfaceControl.captureLayers(
                    new SurfaceControl.LayerCaptureArgs.Builder(surfaceControl)
                            .setChildrenOnly(false)
                            .build());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        if (captureBuffer == null) {
            return null;
        }
        return captureBuffer.asBitmap();
    }

    @Override
    public boolean clearWindowContentFrameStats(int windowId) throws RemoteException {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        int callingUserId = UserHandle.getCallingUserId();
        final long identity = Binder.clearCallingIdentity();
        try {
            IBinder token = mAccessibilityManager.getWindowToken(windowId, callingUserId);
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
        int callingUserId = UserHandle.getCallingUserId();
        final long identity = Binder.clearCallingIdentity();
        try {
            IBinder token = mAccessibilityManager.getWindowToken(windowId, callingUserId);
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
    public void grantRuntimePermission(String packageName, String permission, int userId)
            throws RemoteException {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            mPermissionManager.grantRuntimePermission(packageName, permission, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void revokeRuntimePermission(String packageName, String permission, int userId)
            throws RemoteException {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            mPermissionManager.revokeRuntimePermission(packageName, permission, userId, null);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void adoptShellPermissionIdentity(int uid, @Nullable String[] permissions)
            throws RemoteException {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            mActivityManager.startDelegateShellPermissionIdentity(uid, permissions);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void dropShellPermissionIdentity() throws RemoteException {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            mActivityManager.stopDelegateShellPermissionIdentity();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    @Nullable
    public List<String> getAdoptedShellPermissions() throws RemoteException {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return mActivityManager.getDelegatedShellPermissions();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public class Repeater implements Runnable {
        // Continuously read readFrom and write back to writeTo until EOF is encountered
        private final InputStream readFrom;
        private final OutputStream writeTo;
        public Repeater (InputStream readFrom, OutputStream writeTo) {
            this.readFrom = readFrom;
            this.writeTo = writeTo;
        }
        @Override
        public void run() {
            try {
                final byte[] buffer = new byte[8192];
                int readByteCount;
                while (true) {
                    readByteCount = readFrom.read(buffer);
                    if (readByteCount < 0) {
                        break;
                    }
                    writeTo.write(buffer, 0, readByteCount);
                    writeTo.flush();
                }
            } catch (IOException ignored) {
            } finally {
                IoUtils.closeQuietly(readFrom);
                IoUtils.closeQuietly(writeTo);
            }
        }
    }

    @Override
    public void executeShellCommand(final String command, final ParcelFileDescriptor sink,
            final ParcelFileDescriptor source) throws RemoteException {
        executeShellCommandWithStderr(command, sink, source, null /* stderrSink */);
    }

    @Override
    public void executeShellCommandWithStderr(final String command, final ParcelFileDescriptor sink,
            final ParcelFileDescriptor source, final ParcelFileDescriptor stderrSink)
            throws RemoteException {
        synchronized (mLock) {
            throwIfCalledByNotTrustedUidLocked();
            throwIfShutdownLocked();
            throwIfNotConnectedLocked();
        }
        final java.lang.Process process;

        try {
            process = Runtime.getRuntime().exec(command);
        } catch (IOException exc) {
            throw new RuntimeException("Error running shell command '" + command + "'", exc);
        }

        // Read from process and write to pipe
        final Thread readFromProcess;
        if (sink != null) {
            InputStream sink_in = process.getInputStream();;
            OutputStream sink_out = new FileOutputStream(sink.getFileDescriptor());

            readFromProcess = new Thread(new Repeater(sink_in, sink_out));
            readFromProcess.start();
        } else {
            readFromProcess = null;
        }

        // Read from pipe and write to process
        final Thread writeToProcess;
        if (source != null) {
            OutputStream source_out = process.getOutputStream();
            InputStream source_in = new FileInputStream(source.getFileDescriptor());

            writeToProcess = new Thread(new Repeater(source_in, source_out));
            writeToProcess.start();
        } else {
            writeToProcess = null;
        }

        // Read from process stderr and write to pipe
        final Thread readStderrFromProcess;
        if (stderrSink != null) {
            InputStream sink_in = process.getErrorStream();
            OutputStream sink_out = new FileOutputStream(stderrSink.getFileDescriptor());

            readStderrFromProcess = new Thread(new Repeater(sink_in, sink_out));
            readStderrFromProcess.start();
        } else {
            readStderrFromProcess = null;
        }

        Thread cleanup = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (writeToProcess != null) {
                        writeToProcess.join();
                    }
                    if (readFromProcess != null) {
                        readFromProcess.join();
                    }
                    if (readStderrFromProcess != null) {
                        readStderrFromProcess.join();
                    }
                } catch (InterruptedException exc) {
                    Log.e(TAG, "At least one of the threads was interrupted");
                }
                IoUtils.closeQuietly(sink);
                IoUtils.closeQuietly(source);
                IoUtils.closeQuietly(stderrSink);
                process.destroy();
            }
        });
        cleanup.start();
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

    private void registerUiTestAutomationServiceLocked(IAccessibilityServiceClient client,
            int flags) {
        IAccessibilityManager manager = IAccessibilityManager.Stub.asInterface(
                ServiceManager.getService(Context.ACCESSIBILITY_SERVICE));
        final AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_FORCE_DIRECT_BOOT_AWARE;
        info.setCapabilities(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
                | AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION
                | AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_ENHANCED_WEB_ACCESSIBILITY
                | AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS);
        try {
            // Calling out with a lock held is fine since if the system
            // process is gone the client calling in will be killed.
            manager.registerUiTestAutomationService(mToken, client, info, flags);
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
                mInitialFrozenRotation = mWindowManager.getDefaultDisplayRotation();
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
