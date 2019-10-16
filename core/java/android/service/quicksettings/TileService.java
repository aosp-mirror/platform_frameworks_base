/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.service.quicksettings;

import android.Manifest;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Dialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.WindowManager;

import com.android.internal.R;

/**
 * A TileService provides the user a tile that can be added to Quick Settings.
 * Quick Settings is a space provided that allows the user to change settings and
 * take quick actions without leaving the context of their current app.
 *
 * <p>The lifecycle of a TileService is different from some other services in
 * that it may be unbound during parts of its lifecycle.  Any of the following
 * lifecycle events can happen indepently in a separate binding/creation of the
 * service.</p>
 *
 * <ul>
 * <li>When a tile is added by the user its TileService will be bound to and
 * {@link #onTileAdded()} will be called.</li>
 *
 * <li>When a tile should be up to date and listing will be indicated by
 * {@link #onStartListening()} and {@link #onStopListening()}.</li>
 *
 * <li>When the user removes a tile from Quick Settings {@link #onTileRemoved()}
 * will be called.</li>
 * </ul>
 * <p>TileService will be detected by tiles that match the {@value #ACTION_QS_TILE}
 * and require the permission "android.permission.BIND_QUICK_SETTINGS_TILE".
 * The label and icon for the service will be used as the default label and
 * icon for the tile. Here is an example TileService declaration.</p>
 * <pre class="prettyprint">
 * {@literal
 * <service
 *     android:name=".MyQSTileService"
 *     android:label="@string/my_default_tile_label"
 *     android:icon="@drawable/my_default_icon_label"
 *     android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
 *     <intent-filter>
 *         <action android:name="android.service.quicksettings.action.QS_TILE" />
 *     </intent-filter>
 * </service>}
 * </pre>
 *
 * @see Tile Tile for details about the UI of a Quick Settings Tile.
 */
public class TileService extends Service {

    private static final String TAG = "TileService";
    private static final boolean DEBUG = false;

    /**
     * An activity that provides a user interface for adjusting TileService
     * preferences. Optional but recommended for apps that implement a
     * TileService.
     * <p>
     * This intent may also define a {@link Intent#EXTRA_COMPONENT_NAME} value
     * to indicate the {@link ComponentName} that caused the preferences to be
     * opened.
     * <p>
     * To ensure that the activity can only be launched through quick settings
     * UI provided by this service, apps can protect it with the
     * BIND_QUICK_SETTINGS_TILE permission.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String ACTION_QS_TILE_PREFERENCES
            = "android.service.quicksettings.action.QS_TILE_PREFERENCES";

    /**
     * Action that identifies a Service as being a TileService.
     */
    public static final String ACTION_QS_TILE = "android.service.quicksettings.action.QS_TILE";

    /**
     * Meta-data for tile definition to set a tile into active mode.
     * <p>
     * Active mode is for tiles which already listen and keep track of their state in their
     * own process.  These tiles may request to send an update to the System while their process
     * is alive using {@link #requestListeningState}.  The System will only bind these tiles
     * on its own when a click needs to occur.
     *
     * To make a TileService an active tile, set this meta-data to true on the TileService's
     * manifest declaration.
     * <pre class="prettyprint">
     * {@literal
     * <meta-data android:name="android.service.quicksettings.ACTIVE_TILE"
     *      android:value="true" />
     * }
     * </pre>
     */
    public static final String META_DATA_ACTIVE_TILE
            = "android.service.quicksettings.ACTIVE_TILE";

    /**
     * Meta-data for a tile to support {@code BooleanState}.
     * <p>
     * BooleanState is for tiles that should support switch tile behavior in accessibility. This is
     * the behavior of most of the framework tiles.
     *
     * To make a TileService support BooleanState, set this meta-data to true on the TileService's
     * manifest declaration.
     * <pre class="prettyprint">
     * {@literal
     * <meta-data android:name="android.service.quicksettings.BOOLEAN_TILE"
     *      android:value="true" />
     * }
     * </pre>
     */
    public static final String META_DATA_BOOLEAN_TILE =
            "android.service.quicksettings.BOOLEAN_TILE";

    /**
     * Used to notify SysUI that Listening has be requested.
     * @hide
     */
    public static final String ACTION_REQUEST_LISTENING =
            "android.service.quicksettings.action.REQUEST_LISTENING";

    /**
     * @hide
     */
    public static final String EXTRA_SERVICE = "service";

    /**
     * @hide
     */
    public static final String EXTRA_TOKEN = "token";

    /**
     * @hide
     */
    public static final String EXTRA_STATE = "state";

    private final H mHandler = new H(Looper.getMainLooper());

    private boolean mListening = false;
    private Tile mTile;
    private IBinder mToken;
    private IQSService mService;
    private Runnable mUnlockRunnable;
    private IBinder mTileToken;

    @Override
    public void onDestroy() {
        if (mListening) {
            onStopListening();
            mListening = false;
        }
        super.onDestroy();
    }

    /**
     * Called when the user adds this tile to Quick Settings.
     * <p/>
     * Note that this is not guaranteed to be called between {@link #onCreate()}
     * and {@link #onStartListening()}, it will only be called when the tile is added
     * and not on subsequent binds.
     */
    public void onTileAdded() {
    }

    /**
     * Called when the user removes this tile from Quick Settings.
     */
    public void onTileRemoved() {
    }

    /**
     * Called when this tile moves into a listening state.
     * <p/>
     * When this tile is in a listening state it is expected to keep the
     * UI up to date.  Any listeners or callbacks needed to keep this tile
     * up to date should be registered here and unregistered in {@link #onStopListening()}.
     *
     * @see #getQsTile()
     * @see Tile#updateTile()
     */
    public void onStartListening() {
    }

    /**
     * Called when this tile moves out of the listening state.
     */
    public void onStopListening() {
    }

    /**
     * Called when the user clicks on this tile.
     */
    public void onClick() {
    }

    /**
     * Sets an icon to be shown in the status bar.
     * <p>
     * The icon will be displayed before all other icons.  Can only be called between
     * {@link #onStartListening} and {@link #onStopListening}.  Can only be called by system apps.
     *
     * @param icon The icon to be displayed, null to hide
     * @param contentDescription Content description of the icon to be displayed
     * @hide
     */
    @SystemApi
    public final void setStatusIcon(Icon icon, String contentDescription) {
        if (mService != null) {
            try {
                mService.updateStatusIcon(mTileToken, icon, contentDescription);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Used to show a dialog.
     *
     * This will collapse the Quick Settings panel and show the dialog.
     *
     * @param dialog Dialog to show.
     *
     * @see #isLocked()
     */
    public final void showDialog(Dialog dialog) {
        dialog.getWindow().getAttributes().token = mToken;
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_QS_DIALOG);
        dialog.getWindow().getDecorView().addOnAttachStateChangeListener(
                new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                try {
                    mService.onDialogHidden(mTileToken);
                } catch (RemoteException e) {
                }
            }
        });
        dialog.show();
        try {
            mService.onShowDialog(mTileToken);
        } catch (RemoteException e) {
        }
    }

    /**
     * Prompts the user to unlock the device before executing the Runnable.
     * <p>
     * The user will be prompted for their current security method if applicable
     * and if successful, runnable will be executed.  The Runnable will not be
     * executed if the user fails to unlock the device or cancels the operation.
     */
    public final void unlockAndRun(Runnable runnable) {
        mUnlockRunnable = runnable;
        try {
            mService.startUnlockAndRun(mTileToken);
        } catch (RemoteException e) {
        }
    }

    /**
     * Checks if the device is in a secure state.
     *
     * TileServices should detect when the device is secure and change their behavior
     * accordingly.
     *
     * @return true if the device is secure.
     */
    public final boolean isSecure() {
        try {
            return mService.isSecure();
        } catch (RemoteException e) {
            return true;
        }
    }

    /**
     * Checks if the lock screen is showing.
     *
     * When a device is locked, then {@link #showDialog} will not present a dialog, as it will
     * be under the lock screen. If the behavior of the Tile is safe to do while locked,
     * then the user should use {@link #startActivity} to launch an activity on top of the lock
     * screen, otherwise the tile should use {@link #unlockAndRun(Runnable)} to give the
     * user their security challenge.
     *
     * @return true if the device is locked.
     */
    public final boolean isLocked() {
        try {
            return mService.isLocked();
        } catch (RemoteException e) {
            return true;
        }
    }

    /**
     * Start an activity while collapsing the panel.
     */
    public final void startActivityAndCollapse(Intent intent) {
        startActivity(intent);
        try {
            mService.onStartActivity(mTileToken);
        } catch (RemoteException e) {
        }
    }

    /**
     * Gets the {@link Tile} for this service.
     * <p/>
     * This tile may be used to get or set the current state for this
     * tile. This tile is only valid for updates between {@link #onStartListening()}
     * and {@link #onStopListening()}.
     */
    public final Tile getQsTile() {
        return mTile;
    }

    @Override
    public IBinder onBind(Intent intent) {
        mService = IQSService.Stub.asInterface(intent.getIBinderExtra(EXTRA_SERVICE));
        mTileToken = intent.getIBinderExtra(EXTRA_TOKEN);
        try {
            mTile = mService.getTile(mTileToken);
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to reach IQSService", e);
        }
        if (mTile != null) {
            mTile.setService(mService, mTileToken);
            mHandler.sendEmptyMessage(H.MSG_START_SUCCESS);
        }
        return new IQSTileService.Stub() {
            @Override
            public void onTileRemoved() throws RemoteException {
                mHandler.sendEmptyMessage(H.MSG_TILE_REMOVED);
            }

            @Override
            public void onTileAdded() throws RemoteException {
                mHandler.sendEmptyMessage(H.MSG_TILE_ADDED);
            }

            @Override
            public void onStopListening() throws RemoteException {
                mHandler.sendEmptyMessage(H.MSG_STOP_LISTENING);
            }

            @Override
            public void onStartListening() throws RemoteException {
                mHandler.sendEmptyMessage(H.MSG_START_LISTENING);
            }

            @Override
            public void onClick(IBinder wtoken) throws RemoteException {
                mHandler.obtainMessage(H.MSG_TILE_CLICKED, wtoken).sendToTarget();
            }

            @Override
            public void onUnlockComplete() throws RemoteException{
                mHandler.sendEmptyMessage(H.MSG_UNLOCK_COMPLETE);
            }
        };
    }

    private class H extends Handler {
        private static final int MSG_START_LISTENING = 1;
        private static final int MSG_STOP_LISTENING = 2;
        private static final int MSG_TILE_ADDED = 3;
        private static final int MSG_TILE_REMOVED = 4;
        private static final int MSG_TILE_CLICKED = 5;
        private static final int MSG_UNLOCK_COMPLETE = 6;
        private static final int MSG_START_SUCCESS = 7;
        private final String mTileServiceName;

        public H(Looper looper) {
            super(looper);
            mTileServiceName = TileService.this.getClass().getSimpleName();
        }

        private void logMessage(String message) {
            Log.d(TAG, mTileServiceName + " Handler - " + message);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TILE_ADDED:
                    if (DEBUG) logMessage("MSG_TILE_ADDED");
                    TileService.this.onTileAdded();
                    break;
                case MSG_TILE_REMOVED:
                    if (DEBUG) logMessage("MSG_TILE_REMOVED");
                    if (mListening) {
                        mListening = false;
                        TileService.this.onStopListening();
                    }
                    TileService.this.onTileRemoved();
                    break;
                case MSG_STOP_LISTENING:
                    if (DEBUG) logMessage("MSG_STOP_LISTENING");
                    if (mListening) {
                        mListening = false;
                        TileService.this.onStopListening();
                    }
                    break;
                case MSG_START_LISTENING:
                    if (DEBUG) logMessage("MSG_START_LISTENING");
                    if (!mListening) {
                        mListening = true;
                        TileService.this.onStartListening();
                    }
                    break;
                case MSG_TILE_CLICKED:
                    if (DEBUG) logMessage("MSG_TILE_CLICKED");
                    mToken = (IBinder) msg.obj;
                    TileService.this.onClick();
                    break;
                case MSG_UNLOCK_COMPLETE:
                    if (DEBUG) logMessage("MSG_UNLOCK_COMPLETE");
                    if (mUnlockRunnable != null) {
                        mUnlockRunnable.run();
                    }
                    break;
                case MSG_START_SUCCESS:
                    if (DEBUG) logMessage("MSG_START_SUCCESS");
                    try {
                        mService.onStartSuccessful(mTileToken);
                    } catch (RemoteException e) {
                    }
                    break;
            }
        }
    }

    /**
     * @return True if the device supports quick settings and its assocated APIs.
     * @hide
     */
    @TestApi
    public static boolean isQuickSettingsSupported() {
        return Resources.getSystem().getBoolean(R.bool.config_quickSettingsSupported);
    }

    /**
     * Requests that a tile be put in the listening state so it can send an update.
     *
     * This method is only applicable to tiles that have {@link #META_DATA_ACTIVE_TILE} defined
     * as true on their TileService Manifest declaration, and will do nothing otherwise.
     */
    public static final void requestListeningState(Context context, ComponentName component) {
        Intent intent = new Intent(ACTION_REQUEST_LISTENING);
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME, component);
        intent.setPackage("com.android.systemui");
        context.sendBroadcast(intent, Manifest.permission.BIND_QUICK_SETTINGS_TILE);
    }
}
