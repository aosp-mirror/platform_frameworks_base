package com.android.systemui.qs;

import android.os.IBinder;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.Tile;
import android.util.Log;


public class QSTileServiceWrapper implements IQSTileService {
    private static final String TAG = "IQSTileServiceWrapper";

    private final IQSTileService mService;
    
    public QSTileServiceWrapper(IQSTileService service) {
        mService = service;
    }

    @Override
    public IBinder asBinder() {
        return mService.asBinder();
    }

    @Override
    public void setQSTile(Tile tile) {
        try {
            mService.setQSTile(tile);
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from QSTileService", e);
        }
    }

    @Override
    public void onTileAdded() {
        try {
            mService.onTileAdded();
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from QSTileService", e);
        }
    }

    @Override
    public void onTileRemoved() {
        try {
            mService.onTileRemoved();
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from QSTileService", e);
        }
    }

    @Override
    public void onStartListening() {
        try {
            mService.onStartListening();
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from QSTileService", e);
        }
    }

    @Override
    public void onStopListening() {
        try {
            mService.onStopListening();
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from QSTileService", e);
        }
    }

    @Override
    public void onClick() {
        try {
            mService.onClick();
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from QSTileService", e);
        }
    }
}
