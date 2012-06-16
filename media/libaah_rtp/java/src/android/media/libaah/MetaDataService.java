/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media.libaah;

import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.ArrayList;

/**
 * The MetaDataService class enables application to retrieve metadata e.g.
 * beat information of current played track
 */
public class MetaDataService {

    protected final static String TAG = "AAHMetaData-JAVA";

    protected List<BeatListener> mBeatListeners;

    protected BeatListener.BeatInfo[] mCachedBeats;

    protected static final int TYPEID_BEAT = 1;

    public static final int BEAT_FIXED_LENGTH = 20;

    protected MetaDataService() {
        mBeatListeners = new ArrayList<BeatListener>();
        mCachedBeats = null;
    }

    public static MetaDataService create()
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        Class cls = Class.forName("android.media.libaah.MetaDataServiceRtp");
        return (MetaDataService) cls.newInstance();
    }

    /**
     * release native resources, it's suggested to call this instead of relying
     * on java garbage collection
     */
    public void release() {
    }

    public void enable() {
    }

    public void disable() {
    }

    public synchronized void addBeatListener(BeatListener aahBeatListener) {
        if (!mBeatListeners.contains(aahBeatListener)) {
            mBeatListeners.add(aahBeatListener);
        }
    }

    public synchronized void removeBeatListener(BeatListener aahBeatListener) {
        mBeatListeners.remove(aahBeatListener);
    }

    protected void processBeat(int item_len, byte[] buffer) {
        if (buffer == null) {
            return;
        }
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, item_len);
        // buffer is in network order (big endian)
        byteBuffer.order(ByteOrder.BIG_ENDIAN);

        short count = byteBuffer.getShort();
        if (count * BEAT_FIXED_LENGTH + 2 != item_len ) {
            return;
        }
        if (mCachedBeats == null || mCachedBeats.length < count) {
            BeatListener.BeatInfo[] beats = new BeatListener.BeatInfo[count];
            int i = 0;
            if (mCachedBeats != null) {
                for (; i < mCachedBeats.length; i++) {
                    beats[i] = mCachedBeats[i];
                }
            }
            for (; i < count; i++) {
                beats[i] = new BeatListener.BeatInfo();
            }
            mCachedBeats = beats;
        }
        for (int idx = 0; idx < count; idx++) {
            mCachedBeats[idx].timestamp = byteBuffer.getLong();
            mCachedBeats[idx].beatValue = byteBuffer.getFloat();
            mCachedBeats[idx].smoothedBeatValue = byteBuffer.getFloat();
            mCachedBeats[idx].sequenceNumber = byteBuffer.getInt();
        }
        synchronized (this) {
            for (int i = 0, c = mBeatListeners.size(); i < c; i++) {
                mBeatListeners.get(i).onBeat(count, mCachedBeats);
            }
        }
    }

    protected void flush() {
        synchronized (this) {
            for (int i = 0, c = mBeatListeners.size(); i < c; i++) {
                mBeatListeners.get(i).onFlush();
            }
        }
    }
}
