package com.android.internal.util;

import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_OTHER;

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
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.WindowManager;

import java.util.function.Consumer;

public class ScreenshotHelper {

    /**
     * Describes a screenshot request (to make it easier to pass data through to the handler).
     */
    public static class ScreenshotRequest implements Parcelable {
        private int mSource;
        private boolean mHasStatusBar;
        private boolean mHasNavBar;
        private Bitmap mBitmap;
        private Rect mBoundsInScreen;
        private Insets mInsets;
        private int mTaskId;

        ScreenshotRequest(int source, boolean hasStatus, boolean hasNav) {
            mSource = source;
            mHasStatusBar = hasStatus;
            mHasNavBar = hasNav;
        }

        ScreenshotRequest(
                int source, Bitmap bitmap, Rect boundsInScreen, Insets insets, int taskId) {
            mSource = source;
            mBitmap = bitmap;
            mBoundsInScreen = boundsInScreen;
            mInsets = insets;
            mTaskId = taskId;
        }

        ScreenshotRequest(Parcel in) {
            mSource = in.readInt();
            mHasStatusBar = in.readBoolean();
            mHasNavBar = in.readBoolean();
            if (in.readInt() == 1) {
                mBitmap = in.readParcelable(Bitmap.class.getClassLoader());
                mBoundsInScreen = in.readParcelable(Rect.class.getClassLoader());
                mInsets = in.readParcelable(Insets.class.getClassLoader());
                mTaskId = in.readInt();
            }
        }

        public int getSource() {
            return mSource;
        }

        public boolean getHasStatusBar() {
            return mHasStatusBar;
        }

        public boolean getHasNavBar() {
            return mHasNavBar;
        }

        public Bitmap getBitmap() {
            return mBitmap;
        }

        public Rect getBoundsInScreen() {
            return mBoundsInScreen;
        }

        public Insets getInsets() {
            return mInsets;
        }

        public int getTaskId() {
            return mTaskId;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mSource);
            dest.writeBoolean(mHasStatusBar);
            dest.writeBoolean(mHasNavBar);
            if (mBitmap == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                dest.writeParcelable(mBitmap, 0);
                dest.writeParcelable(mBoundsInScreen, 0);
                dest.writeParcelable(mInsets, 0);
                dest.writeInt(mTaskId);
            }
        }

        public static final @NonNull Parcelable.Creator<ScreenshotRequest> CREATOR =
                new Parcelable.Creator<ScreenshotRequest>() {

                    @Override
                    public ScreenshotRequest createFromParcel(Parcel source) {
                        return new ScreenshotRequest(source);
                    }

                    @Override
                    public ScreenshotRequest[] newArray(int size) {
                        return new ScreenshotRequest[size];
                    }
                };
    }
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
     *                           if not.
     * @param hasNav             {@code true} if the navigation bar is currently showing. {@code
     *                           false} if not.
     * @param source             The source of the screenshot request. One of
     *                           {SCREENSHOT_GLOBAL_ACTIONS, SCREENSHOT_KEY_CHORD,
     *                           SCREENSHOT_OVERVIEW, SCREENSHOT_OTHER}
     * @param handler            A handler used in case the screenshot times out
     * @param completionConsumer Consumes `false` if a screenshot was not taken, and `true` if the
     *                           screenshot was taken.
     */
    public void takeScreenshot(final int screenshotType, final boolean hasStatus,
            final boolean hasNav, int source, @NonNull Handler handler,
            @Nullable Consumer<Uri> completionConsumer) {
        ScreenshotRequest screenshotRequest = new ScreenshotRequest(source, hasStatus, hasNav);
        takeScreenshot(screenshotType, SCREENSHOT_TIMEOUT_MS, handler, screenshotRequest,
                completionConsumer);
    }

    /**
     * Request a screenshot be taken, with provided reason.
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
        ScreenshotRequest screenshotRequest = new ScreenshotRequest(SCREENSHOT_OTHER, hasStatus,
                hasNav);
        takeScreenshot(screenshotType, timeoutMs, handler, screenshotRequest, completionConsumer);
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
            @NonNull Insets insets, int taskId, int source,
            @NonNull Handler handler, @Nullable Consumer<Uri> completionConsumer) {
        ScreenshotRequest screenshotRequest =
                new ScreenshotRequest(source, screenshot, boundsInScreen, insets, taskId);
        takeScreenshot(WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE, SCREENSHOT_TIMEOUT_MS,
                handler, screenshotRequest, completionConsumer);
    }

    private void takeScreenshot(final int screenshotType, long timeoutMs, @NonNull Handler handler,
            ScreenshotRequest screenshotRequest, @Nullable Consumer<Uri> completionConsumer) {
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
                        Message msg = Message.obtain(null, screenshotType, screenshotRequest);
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
