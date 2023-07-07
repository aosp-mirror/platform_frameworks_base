/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.graphics.Matrix;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.accessibility.IAccessibilityEmbeddedConnection;

import java.lang.ref.WeakReference;

class RemoteAccessibilityController {
    private static final String TAG = "RemoteAccessibilityController";
    private int mHostId;
    private RemoteAccessibilityEmbeddedConnection mConnectionWrapper;
    private Matrix mWindowMatrixForEmbeddedHierarchy = new Matrix();
    private final float[] mMatrixValues = new float[9];
    private View mHostView;

    RemoteAccessibilityController(View v) {
        mHostView = v;
    }

    private void runOnUiThread(Runnable runnable) {
        final Handler h = mHostView.getHandler();
        if (h != null && h.getLooper() != Looper.myLooper()) {
            h.post(runnable);
        } else {
            runnable.run();
        }
    }

    void assosciateHierarchy(IAccessibilityEmbeddedConnection connection,
        IBinder leashToken, int hostId) {
        mHostId = hostId;

        try {
            leashToken = connection.associateEmbeddedHierarchy(
                leashToken, mHostId);
            setRemoteAccessibilityEmbeddedConnection(connection, leashToken);
        } catch (RemoteException e) {
            Log.d(TAG, "Error in associateEmbeddedHierarchy " + e);
        }
    }

    void disassosciateHierarchy() {
        setRemoteAccessibilityEmbeddedConnection(null, null);
    }

    boolean alreadyAssociated(IAccessibilityEmbeddedConnection connection) {
        if (mConnectionWrapper == null) {
            return false;
        }
        return mConnectionWrapper.mConnection.equals(connection);
    }

    boolean connected() {
      return mConnectionWrapper != null;
    }

    IBinder getLeashToken() {
        return mConnectionWrapper.getLeashToken();
    }

    /**
     * Wrapper of accessibility embedded connection for embedded view hierarchy.
     */
    private static final class RemoteAccessibilityEmbeddedConnection
            implements IBinder.DeathRecipient {
        private final WeakReference<RemoteAccessibilityController> mController;
        private final IAccessibilityEmbeddedConnection mConnection;
        private final IBinder mLeashToken;

        RemoteAccessibilityEmbeddedConnection(
                RemoteAccessibilityController controller,
                IAccessibilityEmbeddedConnection connection,
                IBinder leashToken) {
            mController = new WeakReference<>(controller);
            mConnection = connection;
            mLeashToken = leashToken;
        }

        IAccessibilityEmbeddedConnection getConnection() {
            return mConnection;
        }

        IBinder getLeashToken() {
            return mLeashToken;
        }

        void linkToDeath() throws RemoteException {
            mConnection.asBinder().linkToDeath(this, 0);
        }

        void unlinkToDeath() {
            mConnection.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            unlinkToDeath();
            RemoteAccessibilityController controller = mController.get();
            if (controller == null) {
                return;
            }
            controller.runOnUiThread(() -> {
                if (controller.mConnectionWrapper == this) {
                    controller.mConnectionWrapper = null;
                }
            });
        }
    }

    private void setRemoteAccessibilityEmbeddedConnection(
          IAccessibilityEmbeddedConnection connection, IBinder leashToken) {
        try {
            if (mConnectionWrapper != null) {
                mConnectionWrapper.getConnection()
                    .disassociateEmbeddedHierarchy();
                mConnectionWrapper.unlinkToDeath();
                mConnectionWrapper = null;
            }
            if (connection != null && leashToken != null) {
                mConnectionWrapper =
                    new RemoteAccessibilityEmbeddedConnection(this, connection, leashToken);
                mConnectionWrapper.linkToDeath();
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Error while setRemoteEmbeddedConnection " + e);
        }
    }

    private RemoteAccessibilityEmbeddedConnection getRemoteAccessibilityEmbeddedConnection() {
        return mConnectionWrapper;
    }

    void setWindowMatrix(Matrix m, boolean force) {
        // If the window matrix doesn't change, do nothing.
        if (!force && m.equals(mWindowMatrixForEmbeddedHierarchy)) {
            return;
        }

        try {
            final RemoteAccessibilityEmbeddedConnection wrapper =
                    getRemoteAccessibilityEmbeddedConnection();
            if (wrapper == null) {
                return;
            }
            m.getValues(mMatrixValues);
            wrapper.getConnection().setWindowMatrix(mMatrixValues);
            mWindowMatrixForEmbeddedHierarchy.set(m);
        } catch (RemoteException e) {
            Log.d(TAG, "Error while setScreenMatrix " + e);
        }
    }






}
