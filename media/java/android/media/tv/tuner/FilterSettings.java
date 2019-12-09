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
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.TunerConstants.FilterSettingsType;

import java.util.List;

/**
 * Demux Filter settings.
 *
 * @hide
 */
public abstract class FilterSettings {
    @Nullable
    protected final Settings mSettings;

    protected FilterSettings(Settings settings) {
        mSettings = settings;
    }

    /**
     * Gets filter settings type
     */
    @FilterSettingsType public abstract int getType();

    // TODO: more builders and getters

    /**
     *  Filter Settings for a TS filter.
     */
    public static class TsFilterSettings extends FilterSettings {
        private int mTpid;

        private TsFilterSettings(Settings settings, int tpid) {
            super(settings);
            mTpid = tpid;
        }

        @Override
        public int getType() {
            return TunerConstants.FILTER_SETTINGS_TS;
        }

        /**
         * Creates a new builder.
         */
        public static Builder newBuilder() {
            return new Builder();
        }

        /**
         * Builder for TsFilterSettings.
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
             * Builds a TsFilterSettings instance.
             */
            public TsFilterSettings build() {
                return new TsFilterSettings(mSettings, mTpid);
            }
        }
    }

    /**
     *  Filter Settings for a MMTP filter.
     */
    public static class MmtpFilterSettings extends FilterSettings {
        private int mMmtpPid;

        public MmtpFilterSettings(Settings settings) {
            super(settings);
        }

        @Override
        public int getType() {
            return TunerConstants.FILTER_SETTINGS_MMTP;
        }
    }


    /**
     *  Filter Settings for a IP filter.
     */
    public static class IpFilterSettings extends FilterSettings {
        private byte[] mSrcIpAddress;
        private byte[] mDstIpAddress;
        private int mSrcPort;
        private int mDstPort;
        private boolean mPassthrough;

        public IpFilterSettings(Settings settings) {
            super(settings);
        }

        @Override
        public int getType() {
            return TunerConstants.FILTER_SETTINGS_IP;
        }
    }


    /**
     *  Filter Settings for a TLV filter.
     */
    public static class TlvFilterSettings extends FilterSettings {
        private int mPacketType;
        private boolean mIsCompressedIpPacket;
        private boolean mPassthrough;

        public TlvFilterSettings(Settings settings) {
            super(settings);
        }

        @Override
        public int getType() {
            return TunerConstants.FILTER_SETTINGS_TLV;
        }
    }


    /**
     *  Filter Settings for a ALP filter.
     */
    public static class AlpFilterSettings extends FilterSettings {
        private int mPacketType;
        private int mLengthType;

        public AlpFilterSettings(Settings settings) {
            super(settings);
        }

        @Override
        public int getType() {
            return TunerConstants.FILTER_SETTINGS_ALP;
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
            super(SectionSettings.findType(mainType));
        }

        private static int findType(int mainType) {
            switch (mainType) {
                case TunerConstants.FILTER_SETTINGS_TS:
                    return Constants.DemuxTsFilterType.SECTION;
                case TunerConstants.FILTER_SETTINGS_MMTP:
                    return Constants.DemuxMmtpFilterType.SECTION;
                case TunerConstants.FILTER_SETTINGS_IP:
                    return Constants.DemuxIpFilterType.SECTION;
                case TunerConstants.FILTER_SETTINGS_TLV:
                    return Constants.DemuxTlvFilterType.SECTION;
                case TunerConstants.FILTER_SETTINGS_ALP:
                    return Constants.DemuxAlpFilterType.SECTION;
            }
            // UNDEFINED
            return 0;
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
            super(PesSettings.findType(mainType));
            mStreamId = streamId;
            mIsRaw = isRaw;
        }

        private static int findType(int mainType) {
            switch (mainType) {
                case TunerConstants.FILTER_SETTINGS_TS:
                    return Constants.DemuxTsFilterType.PES;
                case TunerConstants.FILTER_SETTINGS_MMTP:
                    return Constants.DemuxMmtpFilterType.PES;
            }
            // UNDEFINED
            return 0;
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
            super(AvSettings.findType(mainType, isAudio));
        }

        private static int findType(int mainType, boolean isAudio) {
            switch (mainType) {
                case TunerConstants.FILTER_SETTINGS_TS:
                    return isAudio
                            ? Constants.DemuxTsFilterType.AUDIO
                            : Constants.DemuxTsFilterType.VIDEO;
                case TunerConstants.FILTER_SETTINGS_MMTP:
                    return isAudio
                            ? Constants.DemuxMmtpFilterType.AUDIO
                            : Constants.DemuxMmtpFilterType.VIDEO;
            }
            // UNDEFINED
            return 0;
        }
    }

    /**
     *  Filter Settings for a Download.
     */
    public static class DownloadSettings extends Settings {
        private int mDownloadId;

        public DownloadSettings(int mainType) {
            super(DownloadSettings.findType(mainType));
        }

        private static int findType(int mainType) {
            if (mainType == TunerConstants.FILTER_SETTINGS_MMTP) {
                return Constants.DemuxMmtpFilterType.DOWNLOAD;
            }
            // UNDEFINED
            return 0;
        }
    }

    /**
     *  The Settings for the record in DVR.
     */
    public static class RecordSettings extends Settings {
        private int mIndexType;
        private int mIndexMask;

        public RecordSettings(int mainType) {
            super(RecordSettings.findType(mainType));
        }

        private static int findType(int mainType) {
            switch (mainType) {
                case TunerConstants.FILTER_SETTINGS_TS:
                    return Constants.DemuxTsFilterType.RECORD;
                case TunerConstants.FILTER_SETTINGS_MMTP:
                    return Constants.DemuxMmtpFilterType.RECORD;
            }
            // UNDEFINED
            return 0;
        }
    }

}
