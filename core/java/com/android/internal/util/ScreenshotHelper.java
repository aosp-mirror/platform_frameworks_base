package com.android.internal.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.WindowManager;

import java.util.function.Consumer;

public class ScreenshotHelper {
    private static final String TAG = "ScreenshotHelper";

    // Time until we give up on the screenshot & show an error instead.
    private final int SCREENSHOT_TIMEOUT_MS = 10000;

    private final Object mScreenshotLock = new Object();
    private ServiceConnection mScreenshotConnection = null;
    private final Context mContext;

    public ScreenshotHelper(Context context) {
        mContext = context;
    }

    /**
     * Request a screenshot be taken with a specific timeout.
     *
     * Added to support reducing unit test duration; the method variant without a timeout argument
     * is recommended for general use.
     *
     * @param screenshotType     The type of screenshot, for example either
     *                           {@link android.view.WindowManager#TAKE_SCREENSHOT_FULLSCREEN}
     *                           or
     *                           {@link android.view.WindowManager#TAKE_SCREENSHOT_SELECTED_REGION}
     * @param hasStatus          {@code true} if the status bar is currently showing. {@code false}
     *                           if
     *                           not.
     * @param hasNav             {@code true} if the navigation bar is currently showing. {@code
     *                           false}
     *                           if not.
     * @param handler            A handler used in case the screenshot times out
     * @param completionConsumer Consumes `false` if a screenshot was not taken, and `true` if the
     *                           screenshot was taken.
     */
    public void takeScreenshot(final int screenshotType, final boolean hasStatus,
            final boolean hasNav, @NonNull Handler handler,
            @Nullable Consumer<Uri> completionConsumer) {
        takeScreenshot(screenshotType, hasStatus, hasNav, SCREENSHOT_TIMEOUT_MS, handler,
                completionConsumer);
    }

    /**
     * Request a screenshot be taken.
     *
     * Added to support reducing unit test duration; the method variant without a timeout argument
     * is recommended for general use.
     *
     * @param screenshotType     The type of screenshot, for example either
     *                           {@link android.view.WindowManager#TAKE_SCREENSHOT_FULLSCREEN}
     *                           or
     *                           {@link android.view.WindowManager#TAKE_SCREENSHOT_SELECTED_REGION}
     * @param hasStatus          {@code true} if the status bar is currently showing. {@code false}
     *                           if
     *                           not.
     * @param hasNav             {@code true} if the navigation bar is currently showing. {@code
     *                           false}
     *                           if not.
     * @param timeoutMs          If the screenshot hasn't been completed within this time period,
     *                           the screenshot attempt will be cancelled and `completionConsumer`
     *                           will be run.
     * @param handler            A handler used in case the screenshot times out
     * @param completionConsumer Consumes `false` if a screenshot was not taken, and `true` if the
     *                           screenshot was taken.
     */
    public void takeScreenshot(final int screenshotType, final boolean hasStatus,
            final boolean hasNav, long timeoutMs, @NonNull Handler handler,
            @Nullable Consumer<Uri> completionConsumer) {
        takeScreenshot(screenshotType, hasStatus, hasNav, timeoutMs, handler, null,
                completionConsumer
        );
    }

    /**
     * Request that provided image be handled as if it was a screenshot.
     *
     * @param screenshot         The bitmap to treat as the screen shot.
     * @param boundsInScreen     The bounds in screen coordinates that the bitmap orginated from.
     * @param insets             The insets that the image was shown with, inside the screenbounds.
     * @param taskId             The taskId of the task that the screen shot was taken of.
     * @param handler            A handler used in case the screenshot times out
     * @param completionConsumer Consumes `false` if a screenshot was not taken, and `true` if the
     *                           screenshot was taken.
     */
    public void provideScreenshot(@NonNull Bitmap screenshot, @NonNull Rect boundsInScreen,
            @NonNull Insets insets, int taskId, @NonNull Handler handler,
            @Nullable Consumer<Uri> completionConsumer) {
        Bundle imageBundle = new Bundle();
        imageBundle.putParcelable(WindowManager.PARCEL_KEY_SCREENSHOT_BITMAP, screenshot);
        imageBundle.putParcelable(WindowManager.PARCEL_KEY_SCREENSHOT_BOUNDS, boundsInScreen);
        imageBundle.putParcelable(WindowManager.PARCEL_KEY_SCREENSHOT_INSETS, insets);
        imageBundle.putInt(WindowManager.PARCEL_KEY_SCREENSHOT_TASK_ID, taskId);

        takeScreenshot(
                WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE,
                false, false, // ignored when image bundle is set
                SCREENSHOT_TIMEOUT_MS, handler, imageBundle, completionConsumer);
    }

    private void takeScreenshot(final int screenshotType, final boolean hasStatus,
            final boolean hasNav, long timeoutMs, @NonNull Handler handler,
            @Nullable Bundle providedImage, @Nullable Consumer<Uri> completionConsumer) {
        synchronized (mScreenshotLock) {
            if (mScreenshotConnection != null) {
                return;
            }
            final ComponentName serviceComponent = ComponentName.unflattenFromString(
                    mContext.getResources().getString(
                            com.android.internal.R.string.config_screenshotServiceComponent));
            final Intent serviceIntent = new Intent();

            final Runnable mScreenshotTimeout = new Runnable() {
                @Override
                public void run() {
                    synchronized (mScreenshotLock) {
                        if (mScreenshotConnection != null) {
                            mContext.unbindService(mScreenshotConnection);
                            mScreenshotConnection = null;
                            notifyScreenshotError();
                        }
                    }
                    if (completionConsumer != null) {
                        completionConsumer.accept(null);
                    }
                }
            };

            serviceIntent.setComponent(serviceComponent);
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mScreenshotLock) {
                        if (mScreenshotConnection != this) {
                            return;
                        }
                        Messenger messenger = new Messenger(service);
                        Message msg = Message.obtain(null, screenshotType);
                        final ServiceConnection myConn = this;
                        Handler h = new Handler(handler.getLooper()) {
                            @Override
                            public void handleMessage(Message msg) {
                                synchronized (mScreenshotLock) {
                                    if (mScreenshotConnection == myConn) {
                                        mContext.unbindService(mScreenshotConnection);
                                        mScreenshotConnection = null;
                                        handler.removeCallbacks(mScreenshotTimeout);
                                    }
                                }
                                if (completionConsumer != null) {
                                    completionConsumer.accept((Uri) msg.obj);
                                }
                            }
                        };
                        msg.replyTo = new Messenger(h);
                        msg.arg1 = hasStatus ? 1 : 0;
                        msg.arg2 = hasNav ? 1 : 0;

                        if (screenshotType == WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE) {
                            msg.setData(providedImage);
                        }

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
                            mContext.unbindService(mScreenshotConnection);
                            mScreenshotConnection = null;
                            handler.removeCallbacks(mScreenshotTimeout);
                            notifyScreenshotError();
                        }
                    }
                }
            };
            if (mContext.bindServiceAsUser(serviceIntent, conn,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                    UserHandle.CURRENT)) {
                mScreenshotConnection = conn;
                handler.postDelayed(mScreenshotTimeout, timeoutMs);
            }
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
