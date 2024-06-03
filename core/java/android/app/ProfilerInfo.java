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

package android.app;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import java.io.IOException;
import java.util.Objects;

/**
 * System private API for passing profiler settings.
 *
 * {@hide}
 */
public class ProfilerInfo implements Parcelable {
    // Version of the profiler output
    public static final int OUTPUT_VERSION_DEFAULT = 1;
    // CLOCK_TYPE_DEFAULT chooses the default used by ART. ART uses CLOCK_TYPE_DUAL by default (see
    // kDefaultTraceClockSource in art/runtime/runtime_globals.h).
    public static final int CLOCK_TYPE_DEFAULT = 0x000;
    // The values of these constants are chosen such that they correspond to the flags passed to
    // VMDebug.startMethodTracing to choose the corresponding clock type (see
    // core/java/android/app/ActivityThread.java).
    // The flag values are defined in ART (see TraceFlag in art/runtime/trace.h).
    public static final int CLOCK_TYPE_WALL = 0x010;
    public static final int CLOCK_TYPE_THREAD_CPU = 0x100;
    public static final int CLOCK_TYPE_DUAL = 0x110;
    // The second and third bits of the flags field specify the trace format version. This should
    // match with kTraceFormatVersionShift defined in art/runtime/trace.h.
    public static final int TRACE_FORMAT_VERSION_SHIFT = 1;

    private static final String TAG = "ProfilerInfo";

    /* Name of profile output file. */
    public final String profileFile;

    /* File descriptor for profile output file, can be null. */
    public ParcelFileDescriptor profileFd;

    /* Indicates sample profiling when nonzero, interval in microseconds. */
    public final int samplingInterval;

    /* Automatically stop the profiler when the app goes idle. */
    public final boolean autoStopProfiler;

    /*
     * Indicates whether to stream the profiling info to the out file continuously.
     */
    public final boolean streamingOutput;

    /**
     * Denotes an agent (and its parameters) to attach for profiling.
     */
    public final String agent;

    /**
     * Whether the {@link agent} should be attached early (before bind-application) or during
     * bind-application. Agents attached prior to binding cannot be loaded from the app's APK
     * directly and must be given as an absolute path (or available in the default LD_LIBRARY_PATH).
     * Agents attached during bind-application will miss early setup (e.g., resource initialization
     * and classloader generation), but are searched in the app's library search path.
     */
    public final boolean attachAgentDuringBind;

    /**
     * Indicates the clock source to be used for profiling. The source could be wallclock, thread
     * cpu or both
     */
    public final int clockType;

    /**
     * Indicates the version of profiler output.
     */
    public final int profilerOutputVersion;

    public ProfilerInfo(String filename, ParcelFileDescriptor fd, int interval, boolean autoStop,
            boolean streaming, String agent, boolean attachAgentDuringBind, int clockType,
            int profilerOutputVersion) {
        profileFile = filename;
        profileFd = fd;
        samplingInterval = interval;
        autoStopProfiler = autoStop;
        streamingOutput = streaming;
        this.clockType = clockType;
        this.agent = agent;
        this.attachAgentDuringBind = attachAgentDuringBind;
        this.profilerOutputVersion = profilerOutputVersion;
    }

    public ProfilerInfo(ProfilerInfo in) {
        profileFile = in.profileFile;
        profileFd = in.profileFd;
        samplingInterval = in.samplingInterval;
        autoStopProfiler = in.autoStopProfiler;
        streamingOutput = in.streamingOutput;
        agent = in.agent;
        attachAgentDuringBind = in.attachAgentDuringBind;
        clockType = in.clockType;
        profilerOutputVersion = in.profilerOutputVersion;
    }

    /**
     * Get the value for the clock type corresponding to the option string passed to the activity
     * manager. am profile start / am start-activity start-profiler commands accept clock-type
     * option to choose the source of timestamps when profiling. This function maps the option
     * string to the value of flags that is used when calling VMDebug.startMethodTracing
     */
    public static int getClockTypeFromString(String type) {
        if ("thread-cpu".equals(type)) {
            return CLOCK_TYPE_THREAD_CPU;
        } else if ("wall".equals(type)) {
            return CLOCK_TYPE_WALL;
        } else if ("dual".equals(type)) {
            return CLOCK_TYPE_DUAL;
        } else {
            return CLOCK_TYPE_DEFAULT;
        }
    }

    /**
     * Get the flags that need to be passed to VMDebug.startMethodTracing to specify the desired
     * output format.
     */
    public static int getFlagsForOutputVersion(int version) {
        // Only two version 1 and version 2 are supported. Just use the default if we see an unknown
        // version.
        if (version != 1 || version != 2) {
            version = OUTPUT_VERSION_DEFAULT;
        }

        // The encoded version in the flags starts from 0, where as the version that we read from
        // user starts from 1. So, subtract one before encoding it in the flags.
        return (version - 1) << TRACE_FORMAT_VERSION_SHIFT;
    }

    /**
     * Return a new ProfilerInfo instance, with fields populated from this object,
     * and {@link agent} and {@link attachAgentDuringBind} as given.
     */
    public ProfilerInfo setAgent(String agent, boolean attachAgentDuringBind) {
        return new ProfilerInfo(this.profileFile, this.profileFd, this.samplingInterval,
                this.autoStopProfiler, this.streamingOutput, agent, attachAgentDuringBind,
                this.clockType, this.profilerOutputVersion);
    }

    /**
     * Close profileFd, if it is open. The field will be null after a call to this function.
     */
    public void closeFd() {
        if (profileFd != null) {
            try {
                profileFd.close();
            } catch (IOException e) {
                Slog.w(TAG, "Failure closing profile fd", e);
            }
            profileFd = null;
        }
    }

    @Override
    public int describeContents() {
        if (profileFd != null) {
            return profileFd.describeContents();
        } else {
            return 0;
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(profileFile);
        if (profileFd != null) {
            out.writeInt(1);
            profileFd.writeToParcel(out, flags);
        } else {
            out.writeInt(0);
        }
        out.writeInt(samplingInterval);
        out.writeInt(autoStopProfiler ? 1 : 0);
        out.writeInt(streamingOutput ? 1 : 0);
        out.writeString(agent);
        out.writeBoolean(attachAgentDuringBind);
        out.writeInt(clockType);
        out.writeInt(profilerOutputVersion);
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(ProfilerInfoProto.PROFILE_FILE, profileFile);
        if (profileFd != null) {
            proto.write(ProfilerInfoProto.PROFILE_FD, profileFd.getFd());
        }
        proto.write(ProfilerInfoProto.SAMPLING_INTERVAL, samplingInterval);
        proto.write(ProfilerInfoProto.AUTO_STOP_PROFILER, autoStopProfiler);
        proto.write(ProfilerInfoProto.STREAMING_OUTPUT, streamingOutput);
        proto.write(ProfilerInfoProto.AGENT, agent);
        proto.write(ProfilerInfoProto.CLOCK_TYPE, clockType);
        proto.write(ProfilerInfoProto.PROFILER_OUTPUT_VERSION, profilerOutputVersion);
        proto.end(token);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ProfilerInfo> CREATOR =
            new Parcelable.Creator<ProfilerInfo>() {
                @Override
                public ProfilerInfo createFromParcel(Parcel in) {
                    return new ProfilerInfo(in);
                }

                @Override
                public ProfilerInfo[] newArray(int size) {
                    return new ProfilerInfo[size];
                }
            };

    private ProfilerInfo(Parcel in) {
        profileFile = in.readString();
        profileFd = in.readInt() != 0 ? ParcelFileDescriptor.CREATOR.createFromParcel(in) : null;
        samplingInterval = in.readInt();
        autoStopProfiler = in.readInt() != 0;
        streamingOutput = in.readInt() != 0;
        agent = in.readString();
        attachAgentDuringBind = in.readBoolean();
        clockType = in.readInt();
        profilerOutputVersion = in.readInt();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ProfilerInfo other = (ProfilerInfo) o;
        // TODO: Also check #profileFd for equality.
        return Objects.equals(profileFile, other.profileFile)
                && autoStopProfiler == other.autoStopProfiler
                && samplingInterval == other.samplingInterval
                && streamingOutput == other.streamingOutput && Objects.equals(agent, other.agent)
                && clockType == other.clockType
                && profilerOutputVersion == other.profilerOutputVersion;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(profileFile);
        result = 31 * result + samplingInterval;
        result = 31 * result + (autoStopProfiler ? 1 : 0);
        result = 31 * result + (streamingOutput ? 1 : 0);
        result = 31 * result + Objects.hashCode(agent);
        result = 31 * result + clockType;
        result = 31 * result + profilerOutputVersion;
        return result;
    }
}
