/*
 * Copyright 2017 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Structure for source buffering management params.
 *
 * Used by {@link MediaPlayer#getBufferingParams()} and
 * {@link MediaPlayer#setBufferingParams(BufferingParams)}
 * to control source buffering behavior.
 *
 * <p>There are two stages of source buffering in {@link MediaPlayer}: initial buffering
 * (when {@link MediaPlayer} is being prepared) and rebuffering (when {@link MediaPlayer}
 * is playing back source). {@link BufferingParams} includes corresponding marks for each
 * stage of source buffering. The marks are time based (in milliseconds).
 *
 * <p>{@link MediaPlayer} source component has default marks which can be queried by
 * calling {@link MediaPlayer#getBufferingParams()} before any change is made by
 * {@link MediaPlayer#setBufferingParams()}.
 * <ul>
 * <li><strong>initial buffering:</strong> initialMarkMs is used when
 * {@link MediaPlayer} is being prepared. When cached data amount exceeds this mark
 * {@link MediaPlayer} is prepared. </li>
 * <li><strong>rebuffering during playback:</strong> resumePlaybackMarkMs is used when
 * {@link MediaPlayer} is playing back content.
 * <ul>
 * <li> {@link MediaPlayer} has internal mark, namely pausePlaybackMarkMs, to decide when
 * to pause playback if cached data amount runs low. This internal mark varies based on
 * type of data source. </li>
 * <li> When cached data amount exceeds resumePlaybackMarkMs, {@link MediaPlayer} will
 * resume playback if it has been paused due to low cached data amount. The internal mark
 * pausePlaybackMarkMs shall be less than resumePlaybackMarkMs. </li>
 * <li> {@link MediaPlayer} has internal mark, namely pauseRebufferingMarkMs, to decide
 * when to pause rebuffering. Apparently, this internal mark shall be no less than
 * resumePlaybackMarkMs. </li>
 * <li> {@link MediaPlayer} has internal mark, namely resumeRebufferingMarkMs, to decide
 * when to resume buffering. This internal mark varies based on type of data source. This
 * mark shall be larger than pausePlaybackMarkMs, and less than pauseRebufferingMarkMs.
 * </li>
 * </ul> </li>
 * </ul>
 * <p>Users should use {@link Builder} to change {@link BufferingParams}.
 * @hide
 */
@TestApi
public final class BufferingParams implements Parcelable {
    private static final int BUFFERING_NO_MARK = -1;

    // params
    private int mInitialMarkMs = BUFFERING_NO_MARK;

    private int mResumePlaybackMarkMs = BUFFERING_NO_MARK;

    private BufferingParams() {
    }

    /**
     * Return initial buffering mark in milliseconds.
     * @return initial buffering mark in milliseconds
     */
    public int getInitialMarkMs() {
        return mInitialMarkMs;
    }

    /**
     * Return the mark in milliseconds for resuming playback.
     * @return the mark for resuming playback in milliseconds
     */
    public int getResumePlaybackMarkMs() {
        return mResumePlaybackMarkMs;
    }

    /**
     * Builder class for {@link BufferingParams} objects.
     * <p> Here is an example where <code>Builder</code> is used to define the
     * {@link BufferingParams} to be used by a {@link MediaPlayer} instance:
     *
     * <pre class="prettyprint">
     * BufferingParams myParams = mediaplayer.getDefaultBufferingParams();
     * myParams = new BufferingParams.Builder(myParams)
     *         .setInitialMarkMs(10000)
     *         .setResumePlaybackMarkMs(15000)
     *         .build();
     * mediaplayer.setBufferingParams(myParams);
     * </pre>
     */
    public static class Builder {
        private int mInitialMarkMs = BUFFERING_NO_MARK;
        private int mResumePlaybackMarkMs = BUFFERING_NO_MARK;

        /**
         * Constructs a new Builder with the defaults.
         * By default, all marks are -1.
         */
        public Builder() {
        }

        /**
         * Constructs a new Builder from a given {@link BufferingParams} instance
         * @param bp the {@link BufferingParams} object whose data will be reused
         * in the new Builder.
         */
        public Builder(BufferingParams bp) {
            mInitialMarkMs = bp.mInitialMarkMs;
            mResumePlaybackMarkMs = bp.mResumePlaybackMarkMs;
        }

        /**
         * Combines all of the fields that have been set and return a new
         * {@link BufferingParams} object. <code>IllegalStateException</code> will be
         * thrown if there is conflict between fields.
         * @return a new {@link BufferingParams} object
         */
        public BufferingParams build() {
            BufferingParams bp = new BufferingParams();
            bp.mInitialMarkMs = mInitialMarkMs;
            bp.mResumePlaybackMarkMs = mResumePlaybackMarkMs;

            return bp;
        }

        /**
         * Sets the time based mark in milliseconds for initial buffering.
         * @param markMs time based mark in milliseconds
         * @return the same Builder instance.
         */
        public Builder setInitialMarkMs(int markMs) {
            mInitialMarkMs = markMs;
            return this;
        }

        /**
         * Sets the time based mark in milliseconds for resuming playback.
         * @param markMs time based mark in milliseconds for resuming playback
         * @return the same Builder instance.
         */
        public Builder setResumePlaybackMarkMs(int markMs) {
            mResumePlaybackMarkMs = markMs;
            return this;
        }
    }

    private BufferingParams(Parcel in) {
        mInitialMarkMs = in.readInt();
        mResumePlaybackMarkMs = in.readInt();
    }

    public static final Parcelable.Creator<BufferingParams> CREATOR =
            new Parcelable.Creator<BufferingParams>() {
                @Override
                public BufferingParams createFromParcel(Parcel in) {
                    return new BufferingParams(in);
                }

                @Override
                public BufferingParams[] newArray(int size) {
                    return new BufferingParams[size];
                }
            };


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mInitialMarkMs);
        dest.writeInt(mResumePlaybackMarkMs);
    }
}
