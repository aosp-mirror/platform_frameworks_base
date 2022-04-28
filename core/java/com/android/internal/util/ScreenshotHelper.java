package com.android.internal.util;

import static android.content.Intent.ACTION_USER_SWITCHED;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_OTHER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Insets;
import android.graphics.ParcelableColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Bundle;
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

import java.util.Objects;
import java.util.function.Consumer;

public class ScreenshotHelper {

    public static final int SCREENSHOT_MSG_URI = 1;
    public static final int SCREENSHOT_MSG_PROCESS_COMPLETE = 2;

    /**
     * Describes a screenshot request (to make it easier to pass data through to the handler).
     */
    public static class ScreenshotRequest implements Parcelable {
        private int mSource;
        private boolean mHasStatusBar;
        private boolean mHasNavBar;
        private Bundle mBitmapBundle;
        private Rect mBoundsInScreen;
        private Insets mInsets;
        private int mTaskId;
        private int mUserId;
        private ComponentName mTopComponent;

        ScreenshotRequest(int source, boolean hasStatus, boolean hasNav) {
            mSource = source;
            mHasStatusBar = hasStatus;
            mHasNavBar = hasNav;
        }

        ScreenshotRequest(int source, Bundle bitmapBundle, Rect boundsInScreen, Insets insets,
                int taskId, int userId, ComponentName topComponent) {
            mSource = source;
            mBitmapBundle = bitmapBundle;
            mBoundsInScreen = boundsInScreen;
            mInsets = insets;
            mTaskId = taskId;
            mUserId = userId;
            mTopComponent = topComponent;
        }

        ScreenshotRequest(Parcel in) {
            mSource = in.readInt();
            mHasStatusBar = in.readBoolean();
            mHasNavBar = in.readBoolean();

            if (in.readInt() == 1) {
                mBitmapBundle = in.readBundle(getClass().getClassLoader());
                mBoundsInScreen = in.readParcelable(Rect.class.getClassLoader(), android.graphics.Rect.class);
                mInsets = in.readParcelable(Insets.class.getClassLoader(), android.graphics.Insets.class);
                mTaskId = in.readInt();
                mUserId = in.readInt();
                mTopComponent = in.readParcelable(ComponentName.class.getClassLoader(), android.content.ComponentName.class);
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

        public Bundle getBitmapBundle() {
            return mBitmapBundle;
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


        public int getUserId() {
            return mUserId;
        }

        public ComponentName getTopComponent() {
            return mTopComponent;
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
            if (mBitmapBundle == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                dest.writeBundle(mBitmapBundle);
                dest.writeParcelable(mBoundsInScreen, 0);
                dest.writeParcelable(mInsets, 0);
                dest.writeInt(mTaskId);
                dest.writeInt(mUserId);
                dest.writeParcelable(mTopComponent, 0);
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

    /**
     * Bundler used to convert between a hardware bitmap and a bundle without copying the internal
     * content. This is expected to be used together with {@link #provideScreenshot} to handle a
     * hardware bitmap as a screenshot.
     */
    public static final class HardwareBitmapBundler {
        private static final String KEY_BUFFER = "bitmap_util_buffer";
        private static final String KEY_COLOR_SPACE = "bitmap_util_color_space";

        private HardwareBitmapBundler() {
        }

        /**
         * Creates a Bundle that represents the given Bitmap.
         * <p>The Bundle will contain a wrapped version of the Bitmaps HardwareBuffer, so will avoid
         * copies when passing across processes, only pass to processes you trust.
         *
         * <p>Returns a new Bundle rather than modifying an exiting one to avoid key collisions, the
         * returned Bundle should be treated as a standalone object.
         *
         * @param bitmap to convert to bundle
         * @return a Bundle representing the bitmap, should only be parsed by
         * {@link #bundleToHardwareBitmap(Bundle)}
         */
        public static Bundle hardwareBitmapToBundle(Bitmap bitmap) {
            if (bitmap.getConfig() != Bitmap.Config.HARDWARE) {
                throw new IllegalArgumentException(
                        "Passed bitmap must have hardware config, found: " + bitmap.getConfig());
            }

            // Bitmap assumes SRGB for null color space
            ParcelableColorSpace colorSpace =
                    bitmap.getColorSpace() == null
                            ? new ParcelableColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                            : new ParcelableColorSpace(bitmap.getColorSpace());

            Bundle bundle = new Bundle();
            bundle.putParcelable(KEY_BUFFER, bitmap.getHardwareBuffer());
            bundle.putParcelable(KEY_COLOR_SPACE, colorSpace);

            return bundle;
        }

        /**
         * Extracts the Bitmap added to a Bundle with {@link #hardwareBitmapToBundle(Bitmap)} .}
         *
         * <p>This Bitmap contains the HardwareBuffer from the original caller, be careful passing
         * this
         * Bitmap on to any other source.
         *
         * @param bundle containing the bitmap
         * @return a hardware Bitmap
         */
        public static Bitmap bundleToHardwareBitmap(Bundle bundle) {
            if (!bundle.containsKey(KEY_BUFFER) || !bundle.containsKey(KEY_COLOR_SPACE)) {
                throw new IllegalArgumentException("Bundle does not contain a hardware bitmap");
            }

            HardwareBuffer buffer = bundle.getParcelable(KEY_BUFFER);
            ParcelableColorSpace colorSpace = bundle.getParcelable(KEY_COLOR_SPACE);

            return Bitmap.wrapHardwareBuffer(Objects.requireNonNull(buffer),
                    colorSpace.getColorSpace());
        }
    }

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
        IntentFilter filter = new IntentFilter(ACTION_USER_SWITCHED);
        mContext.registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
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
     * @param screenshotBundle   Bundle containing the buffer and color space of the screenshot.
     * @param boundsInScreen     The bounds in screen coordinates that the bitmap orginated from.
     * @param insets             The insets that the image was shown with, inside the screenbounds.
     * @param taskId             The taskId of the task that the screen shot was taken of.
     * @param userId             The userId of user running the task provided in taskId.
     * @param topComponent       The component name of the top component running in the task.
     * @param handler            A handler used in case the screenshot times out
     * @param completionConsumer Consumes `false` if a screenshot was not taken, and `true` if the
     *                           screenshot was taken.
     */
    public void provideScreenshot(@NonNull Bundle screenshotBundle, @NonNull Rect boundsInScreen,
            @NonNull Insets insets, int taskId, int userId, ComponentName topComponent, int source,
            @NonNull Handler handler, @Nullable Consumer<Uri> completionConsumer) {
        ScreenshotRequest screenshotRequest =
                new ScreenshotRequest(source, screenshotBundle, boundsInScreen, insets, taskId,
                        userId, topComponent);
        takeScreenshot(WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE, SCREENSHOT_TIMEOUT_MS,
                handler, screenshotRequest, completionConsumer);
    }

    private void takeScreenshot(final int screenshotType, long timeoutMs, @NonNull Handler handler,
            ScreenshotRequest screenshotRequest, @Nullable Consumer<Uri> completionConsumer) {
        synchronized (mScreenshotLock) {

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

            Message msg = Message.obtain(null, screenshotType, screenshotRequest);

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
