/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner;

import android.annotation.Nullable;
import android.media.tv.tuner.TunerConstants.FilterType;

import java.util.List;

/**
 * Demux Filter configuration.
 *
 * @hide
 */
public abstract class FilterConfiguration {
    @Nullable
    protected final Settings mSettings;

    protected FilterConfiguration(Settings settings) {
        mSettings = settings;
    }

    /**
     * Gets filter configuration type
     */
    @FilterType
    public abstract int getType();

    public Settings getSettings() {
        return mSettings;
    }

    // TODO: more builders and getters

    /**
     *  Filter configuration for a TS filter.
     */
    public static class TsFilterConfiguration extends FilterConfiguration {
        private int mTpid;

        private TsFilterConfiguration(Settings settings, int tpid) {
            super(settings);
            mTpid = tpid;
        }

        @Override
        public int getType() {
            return TunerConstants.FILTER_TYPE_TS;
        }

        /**
         * Creates a new builder.
         */
        public static Builder newBuilder() {
            return new Builder();
        }

        /**
         * Builder for TsFilterConfiguration.
         */
        public static class Builder {
            private Settings mSettings;
            private int mTpid;

            /**
             * Sets settings.
             */
            public Builder setSettings(Settings settings) {
                mSettings = settings;
                return this;
            }

            /**
             * Sets TPID.
             */
            public Builder setTpid(int tpid) {
                mTpid = tpid;
                return this;
            }

            /**
             * Builds a TsFilterConfiguration instance.
             */
            public TsFilterConfiguration build() {
                return new TsFilterConfiguration(mSettings, mTpid);
            }
        }
    }

    /**
     *  Filter configuration for a MMTP filter.
     */
    public static class MmtpFilterConfiguration extends FilterConfiguration {
        private int mMmtpPid;

        public MmtpFilterConfiguration(Settings settings) {
            super(settings);
        }

        @Override
        public int getType() {
            return TunerConstants.FILTER_TYPE_MMTP;
        }
    }


    /**
     *  Filter configuration for a IP filter.
     */
    public static class IpFilterConfiguration extends FilterConfiguration {
        private byte[] mSrcIpAddress;
        private byte[] mDstIpAddress;
        private int mSrcPort;
        private int mDstPort;
        private boolean mPassthrough;

        public IpFilterConfiguration(Settings settings) {
            super(settings);
        }

        @Override
        public int getType() {
            return TunerConstants.FILTER_TYPE_IP;
        }
    }


    /**
     *  Filter configuration for a TLV filter.
     */
    public static class TlvFilterConfiguration extends FilterConfiguration {
        private int mPacketType;
        private boolean mIsCompressedIpPacket;
        private boolean mPassthrough;

        public TlvFilterConfiguration(Settings settings) {
            super(settings);
        }

        @Override
        public int getType() {
            return TunerConstants.FILTER_TYPE_TLV;
        }
    }


    /**
     *  Filter configuration for a ALP filter.
     */
    public static class AlpFilterConfiguration extends FilterConfiguration {
        private int mPacketType;
        private int mLengthType;

        public AlpFilterConfiguration(Settings settings) {
            super(settings);
        }

        @Override
        public int getType() {
            return TunerConstants.FILTER_TYPE_ALP;
        }
    }


    /**
     *  Settings for filters of different subtypes.
     */
    public abstract static class Settings {
        protected final int mType;

        protected Settings(int type) {
            mType = type;
        }

        /**
         * Gets filter settings type.
         * @return
         */
        int getType() {
            return mType;
        }
    }

    /**
     *  Filter Settings for Section data according to ISO/IEC 13818-1.
     */
    public static class SectionSettings extends Settings {

        private SectionSettings(int mainType) {
            super(TunerUtils.getFilterSubtype(mainType, TunerConstants.FILTER_SUBTYPE_SECTION));
        }
    }

    /**
     *  Bits Settings for Section Filter.
     */
    public static class SectionSettingsWithSectionBits extends SectionSettings {
        private List<Byte> mFilter;
        private List<Byte> mMask;
        private List<Byte> mMode;

        private SectionSettingsWithSectionBits(int mainType) {
            super(mainType);
        }
    }

    /**
     *  Table information for Section Filter.
     */
    public static class SectionSettingsWithTableInfo extends SectionSettings {
        private int mTableId;
        private int mVersion;

        private SectionSettingsWithTableInfo(int mainType) {
            super(mainType);
        }
    }

    /**
     *  Filter Settings for a PES Data.
     */
    public static class PesSettings extends Settings {
        private int mStreamId;
        private boolean mIsRaw;

        private PesSettings(int mainType, int streamId, boolean isRaw) {
            super(TunerUtils.getFilterSubtype(mainType, TunerConstants.FILTER_SUBTYPE_PES));
            mStreamId = streamId;
            mIsRaw = isRaw;
        }

        /**
         * Creates a builder for PesSettings.
         */
        public static Builder newBuilder(int mainType) {
            return new Builder(mainType);
        }

        /**
         * Builder for PesSettings.
         */
        public static class Builder {
            private final int mMainType;
            private int mStreamId;
            private boolean mIsRaw;

            public Builder(int mainType) {
                mMainType = mainType;
            }

            /**
             * Sets stream ID.
             */
            public Builder setStreamId(int streamId) {
                mStreamId = streamId;
                return this;
            }

            /**
             * Sets whether it's raw.
             * true if the filter send onFilterStatus instead of onFilterEvent.
             */
            public Builder setIsRaw(boolean isRaw) {
                mIsRaw = isRaw;
                return this;
            }

            /**
             * Builds a PesSettings instance.
             */
            public PesSettings build() {
                return new PesSettings(mMainType, mStreamId, mIsRaw);
            }
        }
    }

    /**
     *  Filter Settings for a Video and Audio.
     */
    public static class AvSettings extends Settings {
        private boolean mIsPassthrough;

        private AvSettings(int mainType, boolean isAudio) {
            super(TunerUtils.getFilterSubtype(
                    mainType,
                    isAudio
                            ? TunerConstants.FILTER_SUBTYPE_AUDIO
                            : TunerConstants.FILTER_SUBTYPE_VIDEO));
        }
    }

    /**
     *  Filter Settings for a Download.
     */
    public static class DownloadSettings extends Settings {
        private int mDownloadId;

        public DownloadSettings(int mainType) {
            super(TunerUtils.getFilterSubtype(mainType, TunerConstants.FILTER_SUBTYPE_DOWNLOAD));
        }
    }

    /**
     *  The Settings for the record in DVR.
     */
    public static class RecordSettings extends Settings {
        private int mIndexType;
        private int mIndexMask;

        public RecordSettings(int mainType) {
            super(TunerUtils.getFilterSubtype(mainType, TunerConstants.FILTER_SUBTYPE_RECORD));
        }
    }

}
