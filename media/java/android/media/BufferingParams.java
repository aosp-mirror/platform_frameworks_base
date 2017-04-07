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
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Structure for source buffering management params.
 *
 * Used by {@link MediaPlayer#getDefaultBufferingParams()},
 * {@link MediaPlayer#getBufferingParams()} and
 * {@link MediaPlayer#setBufferingParams(BufferingParams)}
 * to control source buffering behavior.
 *
 * <p>There are two stages of source buffering in {@link MediaPlayer}: initial buffering
 * (when {@link MediaPlayer} is being prepared) and rebuffering (when {@link MediaPlayer}
 * is playing back source). {@link BufferingParams} includes mode and corresponding
 * watermarks for each stage of source buffering. The watermarks could be either size
 * based (in milliseconds), or time based (in kilobytes) or both, depending on the mode.
 *
 * <p>There are 4 buffering modes: {@link #BUFFERING_MODE_NONE},
 * {@link #BUFFERING_MODE_TIME_ONLY}, {@link #BUFFERING_MODE_SIZE_ONLY} and
 * {@link #BUFFERING_MODE_TIME_THEN_SIZE}.
 * {@link MediaPlayer} source component has default buffering modes which can be queried
 * by calling {@link MediaPlayer#getDefaultBufferingParams()}.
 * Users should always use those default modes or their downsized version when trying to
 * change buffering params. For example, {@link #BUFFERING_MODE_TIME_THEN_SIZE} can be
 * downsized to {@link #BUFFERING_MODE_NONE}, {@link #BUFFERING_MODE_TIME_ONLY} or
 * {@link #BUFFERING_MODE_SIZE_ONLY}. But {@link #BUFFERING_MODE_TIME_ONLY} can not be
 * downsized to {@link #BUFFERING_MODE_SIZE_ONLY}.
 * <ul>
 * <li><strong>initial buffering stage:</strong> has one watermark which is used when
 * {@link MediaPlayer} is being prepared. When cached data amount exceeds this watermark,
 * {@link MediaPlayer} is prepared.</li>
 * <li><strong>rebuffering stage:</strong> has two watermarks, low and high, which are
 * used when {@link MediaPlayer} is playing back content.
 * <ul>
 * <li> When cached data amount exceeds high watermark, {@link MediaPlayer} will pause
 * buffering. Buffering will resume when cache runs below some limit which could be low
 * watermark or some intermediate value decided by the source component.</li>
 * <li> When cached data amount runs below low watermark, {@link MediaPlayer} will paused
 * playback. Playback will resume when cached data amount exceeds high watermark
 * or reaches end of stream.</li>
 * </ul>
 * </ul>
 * <p>Users should use {@link Builder} to change {@link BufferingParams}.
 * @hide
 */
public final class BufferingParams implements Parcelable {
    /**
     * This mode indicates that source buffering is not supported.
     */
    public static final int BUFFERING_MODE_NONE = 0;
    /**
     * This mode indicates that only time based source buffering is supported. This means
     * the watermark(s) are time based.
     */
    public static final int BUFFERING_MODE_TIME_ONLY = 1;
    /**
     * This mode indicates that only size based source buffering is supported. This means
     * the watermark(s) are size based.
     */
    public static final int BUFFERING_MODE_SIZE_ONLY = 2;
    /**
     * This mode indicates that both time and size based source buffering are supported,
     * and time based calculation precedes size based. Size based calculation will be used
     * only when time information is not available from the source.
     */
    public static final int BUFFERING_MODE_TIME_THEN_SIZE = 3;

    /** @hide */
    @IntDef(
        value = {
                BUFFERING_MODE_NONE,
                BUFFERING_MODE_TIME_ONLY,
                BUFFERING_MODE_SIZE_ONLY,
                BUFFERING_MODE_TIME_THEN_SIZE,
        }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface BufferingMode {}

    private static final int BUFFERING_NO_WATERMARK = -1;

    // params
    private int mInitialBufferingMode = BUFFERING_MODE_NONE;
    private int mRebufferingMode = BUFFERING_MODE_NONE;

    private int mInitialWatermarkMs = BUFFERING_NO_WATERMARK;
    private int mInitialWatermarkKB = BUFFERING_NO_WATERMARK;

    private int mRebufferingWatermarkLowMs = BUFFERING_NO_WATERMARK;
    private int mRebufferingWatermarkHighMs = BUFFERING_NO_WATERMARK;
    private int mRebufferingWatermarkLowKB = BUFFERING_NO_WATERMARK;
    private int mRebufferingWatermarkHighKB = BUFFERING_NO_WATERMARK;

    private BufferingParams() {
    }

    /**
     * Return the initial buffering mode used when {@link MediaPlayer} is being prepared.
     * @return one of the values that can be set in {@link Builder#setInitialBufferingMode(int)}
     */
    public int getInitialBufferingMode() {
        return mInitialBufferingMode;
    }

    /**
     * Return the rebuffering mode used when {@link MediaPlayer} is playing back source.
     * @return one of the values that can be set in {@link Builder#setRebufferingMode(int)}
     */
    public int getRebufferingMode() {
        return mRebufferingMode;
    }

    /**
     * Return the time based initial buffering watermark in milliseconds.
     * It is meaningful only when initial buffering mode obatined from
     * {@link #getInitialBufferingMode()} is time based.
     * @return time based initial buffering watermark in milliseconds
     */
    public int getInitialBufferingWatermarkMs() {
        return mInitialWatermarkMs;
    }

    /**
     * Return the size based initial buffering watermark in kilobytes.
     * It is meaningful only when initial buffering mode obatined from
     * {@link #getInitialBufferingMode()} is size based.
     * @return size based initial buffering watermark in kilobytes
     */
    public int getInitialBufferingWatermarkKB() {
        return mInitialWatermarkKB;
    }

    /**
     * Return the time based low watermark in milliseconds for rebuffering.
     * It is meaningful only when rebuffering mode obatined from
     * {@link #getRebufferingMode()} is time based.
     * @return time based low watermark for rebuffering in milliseconds
     */
    public int getRebufferingWatermarkLowMs() {
        return mRebufferingWatermarkLowMs;
    }

    /**
     * Return the time based high watermark in milliseconds for rebuffering.
     * It is meaningful only when rebuffering mode obatined from
     * {@link #getRebufferingMode()} is time based.
     * @return time based high watermark for rebuffering in milliseconds
     */
    public int getRebufferingWatermarkHighMs() {
        return mRebufferingWatermarkHighMs;
    }

    /**
     * Return the size based low watermark in kilobytes for rebuffering.
     * It is meaningful only when rebuffering mode obatined from
     * {@link #getRebufferingMode()} is size based.
     * @return size based low watermark for rebuffering in kilobytes
     */
    public int getRebufferingWatermarkLowKB() {
        return mRebufferingWatermarkLowKB;
    }

    /**
     * Return the size based high watermark in kilobytes for rebuffering.
     * It is meaningful only when rebuffering mode obatined from
     * {@link #getRebufferingMode()} is size based.
     * @return size based high watermark for rebuffering in kilobytes
     */
    public int getRebufferingWatermarkHighKB() {
        return mRebufferingWatermarkHighKB;
    }

    /**
     * Builder class for {@link BufferingParams} objects.
     * <p> Here is an example where <code>Builder</code> is used to define the
     * {@link BufferingParams} to be used by a {@link MediaPlayer} instance:
     *
     * <pre class="prettyprint">
     * BufferingParams myParams = mediaplayer.getDefaultBufferingParams();
     * myParams = new BufferingParams.Builder(myParams)
     *             .setInitialBufferingWatermarkMs(10000)
     *             .build();
     * mediaplayer.setBufferingParams(myParams);
     * </pre>
     */
    public static class Builder {
        private int mInitialBufferingMode = BUFFERING_MODE_NONE;
        private int mRebufferingMode = BUFFERING_MODE_NONE;

        private int mInitialWatermarkMs = BUFFERING_NO_WATERMARK;
        private int mInitialWatermarkKB = BUFFERING_NO_WATERMARK;

        private int mRebufferingWatermarkLowMs = BUFFERING_NO_WATERMARK;
        private int mRebufferingWatermarkHighMs = BUFFERING_NO_WATERMARK;
        private int mRebufferingWatermarkLowKB = BUFFERING_NO_WATERMARK;
        private int mRebufferingWatermarkHighKB = BUFFERING_NO_WATERMARK;

        /**
         * Constructs a new Builder with the defaults.
         * By default, both initial buffering mode and rebuffering mode are
         * {@link BufferingParams#BUFFERING_MODE_NONE}, and all watermarks are -1.
         */
        public Builder() {
        }

        /**
         * Constructs a new Builder from a given {@link BufferingParams} instance
         * @param bp the {@link BufferingParams} object whose data will be reused
         * in the new Builder.
         */
        public Builder(BufferingParams bp) {
            mInitialBufferingMode = bp.mInitialBufferingMode;
            mRebufferingMode = bp.mRebufferingMode;

            mInitialWatermarkMs = bp.mInitialWatermarkMs;
            mInitialWatermarkKB = bp.mInitialWatermarkKB;

            mRebufferingWatermarkLowMs = bp.mRebufferingWatermarkLowMs;
            mRebufferingWatermarkHighMs = bp.mRebufferingWatermarkHighMs;
            mRebufferingWatermarkLowKB = bp.mRebufferingWatermarkLowKB;
            mRebufferingWatermarkHighKB = bp.mRebufferingWatermarkHighKB;
        }

        /**
         * Combines all of the fields that have been set and return a new
         * {@link BufferingParams} object. <code>IllegalStateException</code> will be
         * thrown if there is conflict between fields.
         * @return a new {@link BufferingParams} object
         */
        public BufferingParams build() {
            if (isTimeBasedMode(mRebufferingMode)
                    && mRebufferingWatermarkLowMs > mRebufferingWatermarkHighMs) {
                throw new IllegalStateException("Illegal watermark:"
                        + mRebufferingWatermarkLowMs + " : " + mRebufferingWatermarkHighMs);
            }
            if (isSizeBasedMode(mRebufferingMode)
                    && mRebufferingWatermarkLowKB > mRebufferingWatermarkHighKB) {
                throw new IllegalStateException("Illegal watermark:"
                        + mRebufferingWatermarkLowKB + " : " + mRebufferingWatermarkHighKB);
            }

            BufferingParams bp = new BufferingParams();
            bp.mInitialBufferingMode = mInitialBufferingMode;
            bp.mRebufferingMode = mRebufferingMode;

            bp.mInitialWatermarkMs = mInitialWatermarkMs;
            bp.mInitialWatermarkKB = mInitialWatermarkKB;

            bp.mRebufferingWatermarkLowMs = mRebufferingWatermarkLowMs;
            bp.mRebufferingWatermarkHighMs = mRebufferingWatermarkHighMs;
            bp.mRebufferingWatermarkLowKB = mRebufferingWatermarkLowKB;
            bp.mRebufferingWatermarkHighKB = mRebufferingWatermarkHighKB;
            return bp;
        }

        private boolean isTimeBasedMode(int mode) {
            return (mode == BUFFERING_MODE_TIME_ONLY || mode == BUFFERING_MODE_TIME_THEN_SIZE);
        }

        private boolean isSizeBasedMode(int mode) {
            return (mode == BUFFERING_MODE_SIZE_ONLY || mode == BUFFERING_MODE_TIME_THEN_SIZE);
        }

        /**
         * Sets the initial buffering mode.
         * @param mode one of {@link BufferingParams#BUFFERING_MODE_NONE},
         *     {@link BufferingParams#BUFFERING_MODE_TIME_ONLY},
         *     {@link BufferingParams#BUFFERING_MODE_SIZE_ONLY},
         *     {@link BufferingParams#BUFFERING_MODE_TIME_THEN_SIZE},
         * @return the same Builder instance.
         */
        public Builder setInitialBufferingMode(@BufferingMode int mode) {
            switch (mode) {
                case BUFFERING_MODE_NONE:
                case BUFFERING_MODE_TIME_ONLY:
                case BUFFERING_MODE_SIZE_ONLY:
                case BUFFERING_MODE_TIME_THEN_SIZE:
                     mInitialBufferingMode = mode;
                     break;
                default:
                     throw new IllegalArgumentException("Illegal buffering mode " + mode);
            }
            return this;
        }

        /**
         * Sets the rebuffering mode.
         * @param mode one of {@link BufferingParams#BUFFERING_MODE_NONE},
         *     {@link BufferingParams#BUFFERING_MODE_TIME_ONLY},
         *     {@link BufferingParams#BUFFERING_MODE_SIZE_ONLY},
         *     {@link BufferingParams#BUFFERING_MODE_TIME_THEN_SIZE},
         * @return the same Builder instance.
         */
        public Builder setRebufferingMode(@BufferingMode int mode) {
            switch (mode) {
                case BUFFERING_MODE_NONE:
                case BUFFERING_MODE_TIME_ONLY:
                case BUFFERING_MODE_SIZE_ONLY:
                case BUFFERING_MODE_TIME_THEN_SIZE:
                     mRebufferingMode = mode;
                     break;
                default:
                     throw new IllegalArgumentException("Illegal buffering mode " + mode);
            }
            return this;
        }

        /**
         * Sets the time based watermark in milliseconds for initial buffering.
         * @param watermarkMs time based watermark in milliseconds
         * @return the same Builder instance.
         */
        public Builder setInitialBufferingWatermarkMs(int watermarkMs) {
            mInitialWatermarkMs = watermarkMs;
            return this;
        }

        /**
         * Sets the size based watermark in kilobytes for initial buffering.
         * @param watermarkKB size based watermark in kilobytes
         * @return the same Builder instance.
         */
        public Builder setInitialBufferingWatermarkKB(int watermarkKB) {
            mInitialWatermarkKB = watermarkKB;
            return this;
        }

        /**
         * Sets the time based low watermark in milliseconds for rebuffering.
         * @param watermarkMs time based low watermark in milliseconds
         * @return the same Builder instance.
         */
        public Builder setRebufferingWatermarkLowMs(int watermarkMs) {
            mRebufferingWatermarkLowMs = watermarkMs;
            return this;
        }

        /**
         * Sets the time based high watermark in milliseconds for rebuffering.
         * @param watermarkMs time based high watermark in milliseconds
         * @return the same Builder instance.
         */
        public Builder setRebufferingWatermarkHighMs(int watermarkMs) {
            mRebufferingWatermarkHighMs = watermarkMs;
            return this;
        }

        /**
         * Sets the size based low watermark in milliseconds for rebuffering.
         * @param watermarkKB size based low watermark in milliseconds
         * @return the same Builder instance.
         */
        public Builder setRebufferingWatermarkLowKB(int watermarkKB) {
            mRebufferingWatermarkLowKB = watermarkKB;
            return this;
        }

        /**
         * Sets the size based high watermark in milliseconds for rebuffering.
         * @param watermarkKB size based high watermark in milliseconds
         * @return the same Builder instance.
         */
        public Builder setRebufferingWatermarkHighKB(int watermarkKB) {
            mRebufferingWatermarkHighKB = watermarkKB;
            return this;
        }

        /**
         * Sets the time based low and high watermarks in milliseconds for rebuffering.
         * @param lowWatermarkMs time based low watermark in milliseconds
         * @param highWatermarkMs time based high watermark in milliseconds
         * @return the same Builder instance.
         */
        public Builder setRebufferingWatermarksMs(int lowWatermarkMs, int highWatermarkMs) {
            mRebufferingWatermarkLowMs = lowWatermarkMs;
            mRebufferingWatermarkHighMs = highWatermarkMs;
            return this;
        }

        /**
         * Sets the size based low and high watermarks in kilobytes for rebuffering.
         * @param lowWatermarkKB size based low watermark in kilobytes
         * @param highWatermarkKB size based high watermark in kilobytes
         * @return the same Builder instance.
         */
        public Builder setRebufferingWatermarksKB(int lowWatermarkKB, int highWatermarkKB) {
            mRebufferingWatermarkLowKB = lowWatermarkKB;
            mRebufferingWatermarkHighKB = highWatermarkKB;
            return this;
        }
    }

    private BufferingParams(Parcel in) {
        mInitialBufferingMode = in.readInt();
        mRebufferingMode = in.readInt();

        mInitialWatermarkMs = in.readInt();
        mInitialWatermarkKB = in.readInt();

        mRebufferingWatermarkLowMs = in.readInt();
        mRebufferingWatermarkHighMs = in.readInt();
        mRebufferingWatermarkLowKB = in.readInt();
        mRebufferingWatermarkHighKB = in.readInt();
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
        dest.writeInt(mInitialBufferingMode);
        dest.writeInt(mRebufferingMode);

        dest.writeInt(mInitialWatermarkMs);
        dest.writeInt(mInitialWatermarkKB);

        dest.writeInt(mRebufferingWatermarkLowMs);
        dest.writeInt(mRebufferingWatermarkHighMs);
        dest.writeInt(mRebufferingWatermarkLowKB);
        dest.writeInt(mRebufferingWatermarkHighKB);
    }
}
