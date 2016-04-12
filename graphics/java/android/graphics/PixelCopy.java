package android.graphics;

import android.annotation.NonNull;
import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.ThreadedRenderer;

/**
 * Provides a mechanisms to issue pixel copy requests to allow for copy
 * operations from {@link Surface} to {@link Bitmap}
 *
 * @hide
 */
public final class PixelCopy {
    /**
     * Contains the result of a pixel copy request
     */
    public static final class Response {
        /**
         * Indicates whether or not the copy request completed successfully.
         * If this is true, then {@link #bitmap} contains the result of the copy.
         * If this is false, {@link #bitmap} is unmodified from the originally
         * passed destination.
         *
         * For example a request might fail if the source is protected content
         * so copies are not allowed. Similarly if the source has nothing to
         * copy from, because either no frames have been produced yet or because
         * it has already been destroyed, then this will be false.
         */
        public boolean success;

        /**
         * The output bitmap. This is always the same object that was passed
         * to request() as the 'dest' bitmap. If {@link #success} is true this
         * contains a copy of the pixels of the source object. If {@link #success}
         * is false then this is unmodified.
         */
        @NonNull
        public Bitmap bitmap;
    }

    public interface OnPixelCopyFinished {
        /**
         * Callback for when a pixel copy request has completed. This will be called
         * regardless of whether the copy succeeded or failed.
         *
         * @param response Contains the result of the copy request which includes
         * whether or not the copy was successful.
         */
        void onPixelCopyFinished(PixelCopy.Response response);
    }

    /**
     * Requests for the display content of a {@link SurfaceView} to be copied
     * into a provided {@link Bitmap}.
     *
     * The contents of the source will be scaled to fit exactly inside the bitmap.
     * The pixel format of the source buffer will be converted, as part of the copy,
     * to fit the the bitmap's {@link Bitmap.Config}. The most recently queued buffer
     * in the SurfaceView's Surface will be used as the source of the copy.
     *
     * @param source The source from which to copy
     * @param dest The destination of the copy. The source will be scaled to
     * match the width, height, and format of this bitmap.
     * @param listener Callback for when the pixel copy request completes
     * @param listenerThread The callback will be invoked on this Handler when
     * the copy is finished.
     */
    public static void request(@NonNull SurfaceView source, @NonNull Bitmap dest,
            @NonNull OnPixelCopyFinished listener, @NonNull Handler listenerThread) {
        request(source.getHolder().getSurface(), dest, listener, listenerThread);
    }

    /**
     * Requests a copy of the pixels from a {@link Surface} to be copied into
     * a provided {@link Bitmap}.
     *
     * The contents of the source will be scaled to fit exactly inside the bitmap.
     * The pixel format of the source buffer will be converted, as part of the copy,
     * to fit the the bitmap's {@link Bitmap.Config}. The most recently queued buffer
     * in the Surface will be used as the source of the copy.
     *
     * @param source The source from which to copy
     * @param dest The destination of the copy. The source will be scaled to
     * match the width, height, and format of this bitmap.
     * @param listener Callback for when the pixel copy request completes
     * @param listenerThread The callback will be invoked on this Handler when
     * the copy is finished.
     */
    public static void request(@NonNull Surface source, @NonNull Bitmap dest,
            @NonNull OnPixelCopyFinished listener, @NonNull Handler listenerThread) {
        // TODO: Make this actually async and fast and cool and stuff
        final PixelCopy.Response response = new PixelCopy.Response();
        response.success = ThreadedRenderer.copySurfaceInto(source, dest);
        response.bitmap = dest;
        listenerThread.post(new Runnable() {
            @Override
            public void run() {
                listener.onPixelCopyFinished(response);
            }
        });
    }
}
