package com.android.internal.util;

import static android.content.Intent.ACTION_USER_SWITCHED;
import static android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE;

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
import android.view.WindowManager.ScreenshotSource;
import android.view.WindowManager.ScreenshotType;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;
import java.util.function.Consumer;

public class ScreenshotHelper {

    public static final int SCREENSHOT_MSG_URI = 1;
    public static final int SCREENSHOT_MSG_PROCESS_COMPLETE = 2;

    /**
     * Describes a screenshot request.
     */
    public static class ScreenshotRequest implements Parcelable {
        @ScreenshotType
        private final int mType;

        @ScreenshotSource
        private final int mSource;

        private final Bundle mBitmapBundle;
        private final Rect mBoundsInScreen;
        private final Insets mInsets;
        private final int mTaskId;
        private final int mUserId;
        private final ComponentName mTopComponent;


        public ScreenshotRequest(@ScreenshotType int type, @ScreenshotSource int source) {
            this(type, source, /* topComponent */ null);
        }

        public ScreenshotRequest(@ScreenshotType int type, @ScreenshotSource int source,
                ComponentName topComponent) {
            this(type,
                source,
                /* bitmapBundle*/ null,
                /* boundsInScreen */ null,
                /* insets */ null,
                /* taskId */ -1,
                /* userId */ -1,
                topComponent);
        }

        public ScreenshotRequest(@ScreenshotType int type, @ScreenshotSource int source,
                Bundle bitmapBundle, Rect boundsInScreen, Insets insets, int taskId, int userId,
                ComponentName topComponent) {
            mType = type;
            mSource = source;
            mBitmapBundle = bitmapBundle;
            mBoundsInScreen = boundsInScreen;
            mInsets = insets;
            mTaskId = taskId;
            mUserId = userId;
            mTopComponent = topComponent;
        }

        ScreenshotRequest(Parcel in) {
            mType = in.readInt();
            mSource = in.readInt();
            if (in.readInt() == 1) {
                mBitmapBundle = in.readBundle(getClass().getClassLoader());
                mBoundsInScreen = in.readParcelable(Rect.class.getClassLoader(), Rect.class);
                mInsets = in.readParcelable(Insets.class.getClassLoader(), Insets.class);
                mTaskId = in.readInt();
                mUserId = in.readInt();
                mTopComponent = in.readParcelable(ComponentName.class.getClassLoader(),
                        ComponentName.class);
            } else {
                mBitmapBundle = null;
                mBoundsInScreen = null;
                mInsets = null;
                mTaskId = -1;
                mUserId = -1;
                mTopComponent = null;
            }
        }

        @ScreenshotType
        public int getType() {
            return mType;
        }

        @ScreenshotSource
        public int getSource() {
            return mSource;
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
            dest.writeInt(mType);
            dest.writeInt(mSource);
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

        @NonNull
        public static final Parcelable.Creator<ScreenshotRequest> CREATOR =
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
         * this Bitmap on to any other source.
         *
         * @param bundle containing the bitmap
         * @return a hardware Bitmap
         */
        public static Bitmap bundleToHardwareBitmap(Bundle bundle) {
            if (!bundle.containsKey(KEY_BUFFER) || !bundle.containsKey(KEY_COLOR_SPACE)) {
                throw new IllegalArgumentException("Bundle does not contain a hardware bitmap");
            }

            HardwareBuffer buffer = bundle.getParcelable(KEY_BUFFER, HardwareBuffer.class);
            ParcelableColorSpace colorSpace = bundle.getParcelable(KEY_COLOR_SPACE,
                    ParcelableColorSpace.class);

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
     * <p>
     * Added to support reducing unit test duration; the method variant without a timeout argument
     * is recommended for general use.
     *
     * @param type The type of screenshot, defined by {@link ScreenshotType}
     * @param source The source of the screenshot request, defined by {@link ScreenshotSource}
     * @param handler used to process messages received from the screenshot service
     * @param completionConsumer receives the URI of the captured screenshot, once saved or
     *         null if no screenshot was saved
     */
    public void takeScreenshot(@ScreenshotType int type, @ScreenshotSource int source,
            @NonNull Handler handler, @Nullable Consumer<Uri> completionConsumer) {
        ScreenshotRequest screenshotRequest = new ScreenshotRequest(type, source);
        takeScreenshot(handler, screenshotRequest, SCREENSHOT_TIMEOUT_MS,
                completionConsumer);
    }

    /**
     * Request a screenshot be taken.
     * <p>
     * Added to support reducing unit test duration; the method variant without a timeout argument
     * is recommended for general use.
     *
     * @param type The type of screenshot, defined by {@link ScreenshotType}
     * @param source The source of the screenshot request, defined by {@link ScreenshotSource}
     * @param handler used to process messages received from the screenshot service
     * @param timeoutMs time limit for processing, intended only for testing
     * @param completionConsumer receives the URI of the captured screenshot, once saved or
     *         null if no screenshot was saved
     */
    @VisibleForTesting
    public void takeScreenshot(@ScreenshotType int type, @ScreenshotSource int source,
            @NonNull Handler handler, long timeoutMs, @Nullable Consumer<Uri> completionConsumer) {
        ScreenshotRequest screenshotRequest = new ScreenshotRequest(type, source);
        takeScreenshot(handler, screenshotRequest, timeoutMs, completionConsumer);
    }

    /**
     * Request that provided image be handled as if it was a screenshot.
     *
     * @param screenshotBundle Bundle containing the buffer and color space of the screenshot.
     * @param boundsInScreen The bounds in screen coordinates that the bitmap originated from.
     * @param insets The insets that the image was shown with, inside the screen bounds.
     * @param taskId The taskId of the task that the screen shot was taken of.
     * @param userId The userId of user running the task provided in taskId.
     * @param topComponent The component name of the top component running in the task.
     * @param source The source of the screenshot request, defined by {@link ScreenshotSource}
     * @param handler A handler used in case the screenshot times out
     * @param completionConsumer receives the URI of the captured screenshot, once saved or
     *         null if no screenshot was saved
     */
    public void provideScreenshot(@NonNull Bundle screenshotBundle, @NonNull Rect boundsInScreen,
            @NonNull Insets insets, int taskId, int userId, ComponentName topComponent,
            @ScreenshotSource int source, @NonNull Handler handler,
            @Nullable Consumer<Uri> completionConsumer) {
        ScreenshotRequest screenshotRequest = new ScreenshotRequest(TAKE_SCREENSHOT_PROVIDED_IMAGE,
                source, screenshotBundle, boundsInScreen, insets, taskId, userId, topComponent);
        takeScreenshot(handler, screenshotRequest, SCREENSHOT_TIMEOUT_MS, completionConsumer);
    }

    private void takeScreenshot(@NonNull Handler handler,
            ScreenshotRequest screenshotRequest, long timeoutMs,
            @Nullable Consumer<Uri> completionConsumer) {
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

            Message msg = Message.obtain(null, 0, screenshotRequest);

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
