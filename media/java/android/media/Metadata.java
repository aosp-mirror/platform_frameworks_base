/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.graphics.Bitmap;
import android.util.Log;

import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;

/**
   Class to hold the media's metadata.  Metadata are used
   for human consumption and can be embedded in the media (e.g
   shoutcast) or available from an external source. The source can be
   local (e.g thumbnail stored in the DB) or remote (e.g caption
   server).

   Metadata is like a Bundle. It is sparse and each key can occur at
   most once. The key is an integer and the value is the actual metadata.

   The caller is expected to know the type of the metadata and call
   the right get* method to fetch its value.

   // FIXME: unhide.
   {@hide}
 */
public class Metadata
{
    // The metadata are keyed using integers rather than more heavy
    // weight strings. We considered using Bundle to ship the metadata
    // between the native layer and the java layer but dropped that
    // option since keeping in sync a native implementation of Bundle
    // and the java one would be too burdensome. Besides Bundle uses
    // String for its keys.
    // The key range [0 8192) is reserved for the system.
    //
    // We manually serialize the data in Parcels. For large memory
    // blob (bitmaps, raw pictures) we use MemoryFile which allow the
    // client to make the data purgeable once it is done with it.
    //

    public static final int ANY = 0;  // Never used for metadata returned, only for filtering.
                                      // Keep in sync with kAny in MediaPlayerService.cpp

    // TODO: Should we use numbers compatible with the metadata retriever?
    public static final int TITLE = 1;           // String
    public static final int COMMENT = 2;         // String
    public static final int COPYRIGHT = 3;       // String
    public static final int ALBUM = 4;           // String
    public static final int ARTIST = 5;          // String
    public static final int AUTHOR = 6;          // String
    public static final int COMPOSER = 7;        // String
    public static final int GENRE = 8;           // String
    public static final int DATE = 9;            // Date
    public static final int DURATION = 10;       // Integer(millisec)
    public static final int CD_TRACK_NUM = 11;   // Integer 1-based
    public static final int CD_TRACK_MAX = 12;   // Integer
    public static final int RATING = 13;         // String
    public static final int ALBUM_ART = 14;      // byte[]
    public static final int VIDEO_FRAME = 15;    // Bitmap
    public static final int CAPTION = 16;        // TimedText

    public static final int BIT_RATE = 17;       // Integer, Aggregate rate of
                                                 // all the streams in bps.

    public static final int AUDIO_BIT_RATE = 18; // Integer, bps
    public static final int VIDEO_BIT_RATE = 19; // Integer, bps
    public static final int AUDIO_SAMPLE_RATE = 20; // Integer, Hz
    public static final int VIDEO_FRAME_RATE = 21;  // Integer, Hz

    // See RFC2046 and RFC4281.
    public static final int MIME_TYPE = 22;      // String
    public static final int AUDIO_CODEC = 23;    // String
    public static final int VIDEO_CODEC = 24;    // String

    public static final int VIDEO_HEIGHT = 25;   // Integer
    public static final int VIDEO_WIDTH = 26;    // Integer
    public static final int NUM_TRACKS = 27;     // Integer
    public static final int DRM_CRIPPLED = 28;   // Boolean
    public static final int LAST_SYSTEM = 29;
    public static final int FIRST_CUSTOM = 8092;

    // Shorthands to set the MediaPlayer's metadata filter.
    public static final Set<Integer> MATCH_NONE = Collections.EMPTY_SET;
    public static final Set<Integer> MATCH_ALL = Collections.singleton(ANY);

    /**
     * Helper class to hold a pair (time, text). Can be used to implement caption.
     */
    public class TimedText {
        private Date mTime;
        private String mText;
        public TimedText(final Date time, final String text) {
            mTime = time;
            mText = text;
        }
        public String toString() {
            StringBuilder res = new StringBuilder(80);
            res.append(mTime).append(":").append(mText);
            return res.toString();
        }
    }

    /* package */ Metadata() {}

    /**
     * @return the number of element in this metadata set.
     */
    public int size() {
        // FIXME: Implement.
        return 0;
    }

    /**
     * @return an iterator over the keys.
     */
    public Iterator<Integer> iterator() {
        // FIXME: Implement.
        return new java.util.HashSet<Integer>().iterator();
    }

    /**
     * @return true if a value is present for the given key.
     */
    public boolean has(final int key) {
        if (key <= ANY) {
            throw new IllegalArgumentException("Invalid key: " + key);
        }
        if (LAST_SYSTEM <= key && key < FIRST_CUSTOM) {
            throw new IllegalArgumentException("Key in reserved range: " + key);
        }
        // FIXME: Implement.
        return true;
    }

    // Accessors
    public String getString(final int key) {
        // FIXME: Implement.
        return new String();
    }

    public int getInt(final int key) {
        // FIXME: Implement.
        return 0;
    }

    public long getLong(final int key) {
        // FIXME: Implement.
        return 0;
    }

    public double getDouble(final int key) {
        // FIXME: Implement.
        return 0.0;
    }

    public byte[] getByteArray(final int key) {
        return new byte[0];
    }

    public Bitmap getBitmap(final int key) {
        // FIXME: Implement.
        return null;
    }

    public Date getDate(final int key) {
        // FIXME: Implement.
        return new Date();
    }

    public TimedText getTimedText(final int key) {
        // FIXME: Implement.
        return new TimedText(new Date(0), "<missing>");
    }
}
