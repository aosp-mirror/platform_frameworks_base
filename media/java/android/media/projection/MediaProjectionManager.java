/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.projection;

import android.annotation.NonNull;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.IMediaProjection;
import android.os.Binder;
import android.os.IBinder;

/**
 * Manages the retrieval of certain types of {@link MediaProjection} tokens.
 *
 * <p>
 * Get an instance of this class by calling {@link
 * android.content.Context#getSystemService(java.lang.String)
 * Context.getSystemService()} with the argument {@link
 * android.content.Context#MEDIA_PROJECTION_SERVICE}.
 * </p>
 */
public final class MediaProjectionManager {
    /** @hide */
    public static final String EXTRA_APP_TOKEN = "android.media.projection.extra.EXTRA_APP_TOKEN";
    /** @hide */
    public static final String EXTRA_MEDIA_PROJECTION =
            "android.media.projection.extra.EXTRA_MEDIA_PROJECTION";

    /** @hide */
    public static final int TYPE_SCREEN_CAPTURE = 0;
    /** @hide */
    public static final int TYPE_MIRRORING = 1;
    /** @hide */
    public static final int TYPE_PRESENTATION = 2;

    private Context mContext;

    /** @hide */
    public MediaProjectionManager(Context context) {
        mContext = context;
    }

    /**
     * Returns an Intent that <b>must</b> passed to startActivityForResult()
     * in order to start screen capture. The activity will prompt
     * the user whether to allow screen capture.  The result of this
     * activity should be passed to getMediaProjection.
     */
    public Intent getScreenCaptureIntent() {
        Intent i = new Intent();
        i.setClassName("com.android.systemui",
                "com.android.systemui.media.MediaProjectionPermissionActivity");
        return i;
    }

    /**
     * Retrieve the MediaProjection obtained from a succesful screen
     * capture request. Will be null if the result from the
     * startActivityForResult() is anything other than RESULT_OK.
     *
     * @param resultCode The result code from {@link android.app.Activity#onActivityResult(int,
     * int, android.content.Intent)}
     * @param resultData The resulting data from {@link android.app.Activity#onActivityResult(int,
     * int, android.content.Intent)}
     */
    public MediaProjection getMediaProjection(int resultCode, @NonNull Intent resultData) {
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            return null;
        }
        IBinder projection = resultData.getIBinderExtra(EXTRA_MEDIA_PROJECTION);
        if (projection == null) {
            return null;
        }
        return new MediaProjection(mContext, IMediaProjection.Stub.asInterface(projection));
    }
}
