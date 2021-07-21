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

    public ProfilerInfo(String filename, ParcelFileDescriptor fd, int interval, boolean autoStop,
            boolean streaming, String agent, boolean attachAgentDuringBind) {
        profileFile = filename;
        profileFd = fd;
        samplingInterval = interval;
        autoStopProfiler = autoStop;
        streamingOutput = streaming;
        this.agent = agent;
        this.attachAgentDuringBind = attachAgentDuringBind;
    }

    public ProfilerInfo(ProfilerInfo in) {
        profileFile = in.profileFile;
        profileFd = in.profileFd;
        samplingInterval = in.samplingInterval;
        autoStopProfiler = in.autoStopProfiler;
        streamingOutput = in.streamingOutput;
        agent = in.agent;
        attachAgentDuringBind = in.attachAgentDuringBind;
    }

    /**
     * Return a new ProfilerInfo instance, with fields populated from this object,
     * and {@link agent} and {@link attachAgentDuringBind} as given.
     */
    public ProfilerInfo setAgent(String agent, boolean attachAgentDuringBind) {
        return new ProfilerInfo(this.profileFile, this.profileFd, this.samplingInterval,
                this.autoStopProfiler, this.streamingOutput, agent, attachAgentDuringBind);
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
                && streamingOutput == other.streamingOutput
                && Objects.equals(agent, other.agent);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(profileFile);
        result = 31 * result + samplingInterval;
        result = 31 * result + (autoStopProfiler ? 1 : 0);
        result = 31 * result + (streamingOutput ? 1 : 0);
        result = 31 * result + Objects.hashCode(agent);
        return result;
    }
}
