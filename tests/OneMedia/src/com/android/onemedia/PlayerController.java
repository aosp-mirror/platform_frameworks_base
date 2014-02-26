
package com.android.onemedia;

import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.util.Log;
import android.view.KeyEvent;

import com.android.onemedia.playback.RequestUtils;

public class PlayerController {
    private static final String TAG = "PlayerSession";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTED = 1;

    protected MediaController mController;
    protected IPlayerService mBinder;

    private final Intent mServiceIntent;
    private Context mContext;
    private Listener mListener;
    private SessionCallback mControllerCb;
    private MediaSessionManager mManager;
    private Handler mHandler = new Handler();

    private boolean mResumed;

    public PlayerController(Context context, Intent serviceIntent) {
        mContext = context;
        if (serviceIntent == null) {
            mServiceIntent = new Intent(mContext, PlayerService.class);
        } else {
            mServiceIntent = serviceIntent;
        }
        mControllerCb = new SessionCallback();
        mManager = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);

        mResumed = false;
    }

    public void setListener(Listener listener) {
        mListener = listener;
        Log.d(TAG, "Listener set to " + listener + " session is " + mController);
        if (mListener != null) {
            mHandler = new Handler();
            mListener.onPlayerStateChange(
                    mController == null ? STATE_DISCONNECTED : STATE_CONNECTED);
        }
    }

    public void onResume() {
        mResumed = true;
        Log.d(TAG, "onResume. Binding to service with intent " + mServiceIntent.toString());
        bindToService();
    }

    public void onPause() {
        mResumed = false;
        Log.d(TAG, "onPause, unbinding from service");
        unbindFromService();
    }

    public void play() {
        mController.sendMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    public void pause() {
        mController.sendMediaButton(KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    public void setContent(String source) {
        RequestUtils.ContentBuilder bob = new RequestUtils.ContentBuilder();
        bob.setSource(source);
        try {
            mBinder.sendRequest(RequestUtils.ACTION_SET_CONTENT, bob.build(), null);
        } catch (RemoteException e) {
            Log.d(TAG, "setContent failed, service may have died.", e);
        }
    }

    public void setNextContent(String source) {
        RequestUtils.ContentBuilder bob = new RequestUtils.ContentBuilder();
        bob.setSource(source);
        try {
            mBinder.sendRequest(RequestUtils.ACTION_SET_NEXT_CONTENT, bob.build(), null);
        } catch (RemoteException e) {
            Log.d(TAG, "setNexctContent failed, service may have died.", e);
        }
    }

    private void unbindFromService() {
        mContext.unbindService(mServiceConnection);
    }

    private void bindToService() {
        mContext.bindService(mServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (mController != null) {
                mController.removeCallback(mControllerCb);
            }
            mBinder = null;
            mController = null;
            Log.d(TAG, "Disconnected from PlayerService");

            if (mListener != null) {
                mListener.onPlayerStateChange(STATE_DISCONNECTED);
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = IPlayerService.Stub.asInterface(service);
            Log.d(TAG, "service is " + service + " binder is " + mBinder);
            try {
                mController = new MediaController(mBinder.getSessionToken());
            } catch (RemoteException e) {
                Log.e(TAG, "Error getting session", e);
                return;
            }
            mController.addCallback(mControllerCb, mHandler);
            Log.d(TAG, "Ready to use PlayerService");

            if (mListener != null) {
                mListener.onPlayerStateChange(STATE_CONNECTED);
            }
        }
    };

    private class SessionCallback extends MediaController.Callback {
        @Override
        public void onPlaybackStateChange(int state) {
            if (mListener != null) {
                mListener.onSessionStateChange(state);
            }
        }
    }

    public interface Listener {
        public void onSessionStateChange(int state);

        public void onPlayerStateChange(int state);
    }

}
