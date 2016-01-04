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

import android.app.Dialog;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.view.WindowManager;

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
 * <li>When the user removes a tile from Quick Settings {@link #onStopListening()}
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

    /**
     * Action that identifies a Service as being a TileService.
     */
    public static final String ACTION_QS_TILE = "android.service.quicksettings.action.QS_TILE";

    private final H mHandler = new H(Looper.getMainLooper());

    private boolean mListening = false;
    private Tile mTile;
    private IBinder mToken;

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
     * Used to show a dialog.
     *
     * This will collapse the Quick Settings panel and show the dialog.
     *
     * @param dialog Dialog to show.
     */
    public final void showDialog(Dialog dialog) {
        dialog.getWindow().getAttributes().token = mToken;
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_QS_DIALOG);
        dialog.show();
        getQsTile().onShowDialog();
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
        return new IQSTileService.Stub() {
            @Override
            public void setQSTile(Tile tile) throws RemoteException {
                mHandler.obtainMessage(H.MSG_SET_TILE, tile).sendToTarget();
            }

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
        };
    }

    private class H extends Handler {
        private static final int MSG_SET_TILE = 1;
        private static final int MSG_START_LISTENING = 2;
        private static final int MSG_STOP_LISTENING = 3;
        private static final int MSG_TILE_ADDED = 4;
        private static final int MSG_TILE_REMOVED = 5;
        private static final int MSG_TILE_CLICKED = 6;

        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_TILE:
                    mTile = (Tile) msg.obj;
                    break;
                case MSG_TILE_ADDED:
                    TileService.this.onTileAdded();
                    break;
                case MSG_TILE_REMOVED:
                    TileService.this.onTileRemoved();
                    break;
                case MSG_STOP_LISTENING:
                    if (mListening) {
                        mListening = false;
                        TileService.this.onStopListening();
                    }
                    break;
                case MSG_START_LISTENING:
                    if (!mListening) {
                        mListening = true;
                        TileService.this.onStartListening();
                    }
                    break;
                case MSG_TILE_CLICKED:
                    mToken = (IBinder) msg.obj;
                    TileService.this.onClick();
                    break;
            }
        }
    }
}
