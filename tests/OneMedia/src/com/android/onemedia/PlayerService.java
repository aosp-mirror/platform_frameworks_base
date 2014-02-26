package com.android.onemedia;

import android.app.Service;
import android.content.Intent;
import android.media.session.MediaSessionToken;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.onemedia.playback.IRequestCallback;
import com.android.onemedia.playback.RequestUtils;

import java.util.ArrayList;

public class PlayerService extends Service {
    private static final String TAG = "PlayerService";

    private PlayerBinder mBinder;
    private PlayerSession mSession;
    private Intent mIntent;

    private ArrayList<IPlayerCallback> mCbs = new ArrayList<IPlayerCallback>();

    @Override
    public void onCreate() {
        mIntent = onCreateServiceIntent();
        mSession = onCreatePlayerController();
        mSession.createSession();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (mBinder == null) {
            mBinder = new PlayerBinder();
        }
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mSession.onDestroy();
    }

    protected Intent onCreateServiceIntent() {
        return new Intent(this, PlayerService.class).setPackage(getBasePackageName());
    }

    protected PlayerSession onCreatePlayerController() {
        return new PlayerSession(this);
    }

    protected ArrayList<String> getAllowedPackages() {
        return null;
    }

    public class PlayerBinder extends IPlayerService.Stub {
        @Override
        public void sendRequest(String action, Bundle params, IRequestCallback cb) {
            if (RequestUtils.ACTION_SET_CONTENT.equals(action)) {
                mSession.setContent(params);
            } else if (RequestUtils.ACTION_SET_NEXT_CONTENT.equals(action)) {
                mSession.setNextContent(params);
            }
        }

        @Override
        public void registerCallback(final IPlayerCallback cb) throws RemoteException {
            if (!mCbs.contains(cb)) {
                mCbs.add(cb);
                cb.asBinder().linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        mCbs.remove(cb);
                    }
                }, 0);
            }
            try {
                cb.onSessionChanged(getSessionToken());
            } catch (RemoteException e) {
                mCbs.remove(cb);
                throw e;
            }
        }

        @Override
        public void unregisterCallback(IPlayerCallback cb) throws RemoteException {
            mCbs.remove(cb);
        }

        @Override
        public MediaSessionToken getSessionToken() throws RemoteException {
            // TODO(epastern): Auto-generated method stub
            return mSession.getSessionToken();
        }
    }

}
