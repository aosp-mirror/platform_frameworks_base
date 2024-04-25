package com.android.internal.util;

import static android.content.Intent.ACTION_USER_SWITCHED;
import static android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.WindowManager.ScreenshotSource;

import com.android.internal.annotations.VisibleForTesting;

import java.util.function.Consumer;

public class ScreenshotHelper {

    public static final int SCREENSHOT_MSG_URI = 1;
    public static final int SCREENSHOT_MSG_PROCESS_COMPLETE = 2;

    private static final String TAG = "ScreenshotHelper";

    // Time until we give up on the screenshot & show an error instead.
    private final int SCREENSHOT_TIMEOUT_MS = 10000;

    private final Object mScreenshotLock = new Object();
    private IBinder mScreenshotService = null;
    private ServiceConnection mScreenshotConnection = null;
    private final Context mContext;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mScreenshotLock) {
                if (ACTION_USER_SWITCHED.equals(intent.getAction())) {
                    resetConnection();
                }
            }
        }
    };

    public ScreenshotHelper(Context context) {
        mContext = context;
    }

    /**
     * Request a screenshot be taken.
     * <p>
     * Convenience method for taking a full screenshot with provided source.
     *
     * @param source             source of the screenshot request, defined by {@link
     *                           ScreenshotSource}
     * @param handler            used to process messages received from the screenshot service
     * @param completionConsumer receives the URI of the captured screenshot, once saved or
     *                           null if no screenshot was saved
     */
    public void takeScreenshot(@ScreenshotSource int source, @NonNull Handler handler,
            @Nullable Consumer<Uri> completionConsumer) {
        ScreenshotRequest request =
                new ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, source).build();
        takeScreenshot(request, handler, completionConsumer);
    }

    /**
     * Request a screenshot be taken.
     * <p>
     *
     * @param request            description of the screenshot request, either for taking a
     *                           screenshot or
     *                           providing a bitmap
     * @param handler            used to process messages received from the screenshot service
     * @param completionConsumer receives the URI of the captured screenshot, once saved or
     *                           null if no screenshot was saved
     */
    public void takeScreenshot(ScreenshotRequest request, @NonNull Handler handler,
            @Nullable Consumer<Uri> completionConsumer) {
        takeScreenshotInternal(request, handler, completionConsumer, SCREENSHOT_TIMEOUT_MS);
    }

    /**
     * Request a screenshot be taken.
     * <p>
     * Added to support reducing unit test duration; the method variant without a timeout argument
     * is recommended for general use.
     *
     * @param request            description of the screenshot request, either for taking a
     *                           screenshot or providing a bitmap
     * @param handler            used to process messages received from the screenshot service
     * @param timeoutMs          time limit for processing, intended only for testing
     * @param completionConsumer receives the URI of the captured screenshot, once saved or
     *                           null if no screenshot was saved
     */
    @VisibleForTesting
    public void takeScreenshotInternal(ScreenshotRequest request, @NonNull Handler handler,
            @Nullable Consumer<Uri> completionConsumer, long timeoutMs) {
        synchronized (mScreenshotLock) {
            mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(ACTION_USER_SWITCHED), Context.RECEIVER_EXPORTED);

            final Runnable mScreenshotTimeout = () -> {
                synchronized (mScreenshotLock) {
                    if (mScreenshotConnection != null) {
                        Log.e(TAG, "Timed out before getting screenshot capture response");
                        resetConnection();
                        notifyScreenshotError();
                    }
                }
                if (completionConsumer != null) {
                    completionConsumer.accept(null);
                }
            };

            Message msg = Message.obtain(null, 0, request);

            Handler h = new Handler(handler.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case SCREENSHOT_MSG_URI:
                            if (completionConsumer != null) {
                                completionConsumer.accept((Uri) msg.obj);
                            }
                            handler.removeCallbacks(mScreenshotTimeout);
                            break;
                        case SCREENSHOT_MSG_PROCESS_COMPLETE:
                            synchronized (mScreenshotLock) {
                                resetConnection();
                            }
                            break;
                    }
                }
            };
            msg.replyTo = new Messenger(h);

            if (mScreenshotConnection == null || mScreenshotService == null) {
                if (mScreenshotConnection != null) {
                    resetConnection();
                }
                final ComponentName serviceComponent = ComponentName.unflattenFromString(
                        mContext.getResources().getString(
                                com.android.internal.R.string.config_screenshotServiceComponent));
                final Intent serviceIntent = new Intent();

                serviceIntent.setComponent(serviceComponent);
                ServiceConnection conn = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        synchronized (mScreenshotLock) {
                            if (mScreenshotConnection != this) {
                                return;
                            }
                            mScreenshotService = service;
                            Messenger messenger = new Messenger(mScreenshotService);

                            try {
                                messenger.send(msg);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Couldn't take screenshot: " + e);
                                if (completionConsumer != null) {
                                    completionConsumer.accept(null);
                                }
                            }
                        }
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        synchronized (mScreenshotLock) {
                            if (mScreenshotConnection != null) {
                                resetConnection();
                                // only log an error if we're still within the timeout period
                                if (handler.hasCallbacks(mScreenshotTimeout)) {
                                    Log.e(TAG, "Screenshot service disconnected");
                                    handler.removeCallbacks(mScreenshotTimeout);
                                    notifyScreenshotError();
                                }
                            }
                        }
                    }
                };
                if (mContext.bindServiceAsUser(serviceIntent, conn,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        UserHandle.CURRENT)) {
                    mScreenshotConnection = conn;
                    handler.postDelayed(mScreenshotTimeout, timeoutMs);
                } else {
                    mContext.unbindService(conn);
                }
            } else {
                Messenger messenger = new Messenger(mScreenshotService);

                try {
                    messenger.send(msg);
                } catch (RemoteException e) {
                    Log.e(TAG, "Couldn't take screenshot: " + e);
                    if (completionConsumer != null) {
                        completionConsumer.accept(null);
                    }
                }
                handler.postDelayed(mScreenshotTimeout, timeoutMs);
            }
        }
    }

    /**
     * Unbinds the current screenshot connection (if any).
     */
    private void resetConnection() {
        if (mScreenshotConnection != null) {
            mContext.unbindService(mScreenshotConnection);
            mScreenshotConnection = null;
            mScreenshotService = null;
        }
        try {
            mContext.unregisterReceiver(mBroadcastReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Attempted to remove broadcast receiver twice");
        }
    }

    /**
     * Notifies the screenshot service to show an error.
     */
    private void notifyScreenshotError() {
        // If the service process is killed, then ask it to clean up after itself
        final ComponentName errorComponent = ComponentName.unflattenFromString(
                mContext.getResources().getString(
                        com.android.internal.R.string.config_screenshotErrorReceiverComponent));
        // Broadcast needs to have a valid action.  We'll just pick
        // a generic one, since the receiver here doesn't care.
        Intent errorIntent = new Intent(Intent.ACTION_USER_PRESENT);
        errorIntent.setComponent(errorComponent);
        errorIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT |
                Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(errorIntent, UserHandle.CURRENT);
    }
}
