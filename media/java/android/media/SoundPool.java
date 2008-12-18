/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.media;

import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import android.os.ParcelFileDescriptor;
import java.lang.ref.WeakReference;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import java.io.IOException;

/*
 * The SoundPool class manages and plays audio resources for applications.
 */
public class SoundPool
{
    static { System.loadLibrary("soundpool"); }

    private final static String TAG = "SoundPool";

    private int mNativeContext; // accessed by native methods

    public SoundPool(int maxStreams, int streamType, int srcQuality) {
        native_setup(new WeakReference<SoundPool>(this), maxStreams, streamType, srcQuality);
    }

    public int load(String path, int priority)
    {
        // pass network streams to player
        if (path.startsWith("http:"))
            return _load(path, priority);

        // try local path
        int id = 0;
        try {
            File f = new File(path);
            if (f != null) {
                ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                if (fd != null) {
                    id = _load(fd.getFileDescriptor(), 0, f.length(), priority);
                    //Log.v(TAG, "close fd");
                    fd.close();
                }
            }
        } catch (java.io.IOException e) {}
        return id;
    }

    public int load(Context context, int resId, int priority) {
        AssetFileDescriptor afd = context.getResources().openRawResourceFd(resId);
        int id = 0;
        if (afd != null) {
            id = _load(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength(), priority);
            try {
                //Log.v(TAG, "close fd");
                afd.close();
            } catch (java.io.IOException ex) {
                //Log.d(TAG, "close failed:", ex);
            }
        }
        return id;
    }

    public int load(AssetFileDescriptor afd, int priority) {
        if (afd != null) {
            return _load(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength(), priority);
        } else {
            return 0;
        }
    }

    public int load(FileDescriptor fd, long offset, long length, int priority) {
        return _load(fd, offset, length, priority);
    }

    private native final int _load(String uri, int priority);

    private native final int _load(FileDescriptor fd, long offset, long length, int priority);

    public native final boolean unload(int soundID);

    public native final int play(int soundID, float leftVolume, float rightVolume,
            int priority, int loop, float rate);

    public native final void pause(int streamID);

    public native final void resume(int streamID);

    public native final void stop(int streamID);

    public native final void setVolume(int streamID,
            float leftVolume, float rightVolume);

    public native final void setPriority(int streamID, int priority);

    public native final void setLoop(int streamID, int loop);

    public native final void setRate(int streamID, float rate);

    public native final void release();

    private native final void native_setup(Object mediaplayer_this,
            int maxStreams, int streamType, int srcQuality);

    protected void finalize() { release(); }
}
