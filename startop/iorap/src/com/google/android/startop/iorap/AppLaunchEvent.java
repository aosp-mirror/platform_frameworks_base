/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.startop.iorap;

import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.proto.ProtoOutputStream;

import com.android.server.wm.ActivityMetricsLaunchObserver;
import com.android.server.wm.ActivityMetricsLaunchObserver.ActivityRecordProto;
import com.android.server.wm.ActivityMetricsLaunchObserver.Temperature;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/**
 * Provide a hint to iorapd that an app launch sequence has transitioned state.<br /><br />
 *
 * Knowledge of when an activity starts/stops can be used by iorapd to increase system
 * performance (e.g. by launching perfetto tracing to record an io profile, or by
 * playing back an ioprofile via readahead) over the long run.<br /><br />
 *
 * /@see com.google.android.startop.iorap.IIorap#onAppLaunchEvent <br /><br />
 * @see com.android.server.wm.ActivityMetricsLaunchObserver
 *      ActivityMetricsLaunchObserver for the possible event states.
 * @hide
 */
public abstract class AppLaunchEvent implements Parcelable {
    @LongDef
    @Retention(RetentionPolicy.SOURCE)
    public @interface SequenceId {}

    public final @SequenceId
    long sequenceId;

    protected AppLaunchEvent(@SequenceId long sequenceId) {
        this.sequenceId = sequenceId;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof AppLaunchEvent) {
            return equals((AppLaunchEvent) other);
        }
        return false;
    }

    protected boolean equals(AppLaunchEvent other) {
        return sequenceId == other.sequenceId;
    }


    @Override
    public String toString() {
        return getClass().getSimpleName() +
                "{" + "sequenceId=" + Long.toString(sequenceId) +
                toStringBody() + "}";
    }

    protected String toStringBody() { return ""; };

    // List of possible variants:

    public static final class IntentStarted extends AppLaunchEvent {
        @NonNull
        public final Intent intent;

        public IntentStarted(@SequenceId long sequenceId, Intent intent) {
            super(sequenceId);
            this.intent = intent;

            Objects.requireNonNull(intent, "intent");
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof IntentStarted) {
                return intent.equals(((IntentStarted)other).intent) &&
                        super.equals(other);
            }
            return false;
        }

        @Override
        protected String toStringBody() {
            return ", intent=" + intent.toString();
        }


        @Override
        protected void writeToParcelImpl(Parcel p, int flags) {
            super.writeToParcelImpl(p, flags);
            IntentProtoParcelable.write(p, intent, flags);
        }

        IntentStarted(Parcel p) {
            super(p);
            intent = IntentProtoParcelable.create(p);
        }
    }

    public static final class IntentFailed extends AppLaunchEvent {
        public IntentFailed(@SequenceId long sequenceId) {
            super(sequenceId);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof IntentFailed) {
                return super.equals(other);
            }
            return false;
        }

        IntentFailed(Parcel p) {
            super(p);
        }
    }

    public static abstract class BaseWithActivityRecordData extends AppLaunchEvent {
        public final @NonNull
        @ActivityRecordProto byte[] activityRecordSnapshot;

        protected BaseWithActivityRecordData(@SequenceId long sequenceId,
                @NonNull @ActivityRecordProto byte[] snapshot) {
            super(sequenceId);
            activityRecordSnapshot = snapshot;

            Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof BaseWithActivityRecordData) {
                return activityRecordSnapshot.equals(
                        ((BaseWithActivityRecordData)other).activityRecordSnapshot) &&
                        super.equals(other);
            }
            return false;
        }

        @Override
        protected String toStringBody() {
            return ", " + activityRecordSnapshot.toString();
        }

        @Override
        protected void writeToParcelImpl(Parcel p, int flags) {
           super.writeToParcelImpl(p, flags);
           ActivityRecordProtoParcelable.write(p, activityRecordSnapshot, flags);
        }

        BaseWithActivityRecordData(Parcel p) {
            super(p);
            activityRecordSnapshot = ActivityRecordProtoParcelable.create(p);
        }
    }

    public static final class ActivityLaunched extends BaseWithActivityRecordData {
        public final @ActivityMetricsLaunchObserver.Temperature
        int temperature;

        public ActivityLaunched(@SequenceId long sequenceId,
                @NonNull @ActivityRecordProto byte[] snapshot, 
                @ActivityMetricsLaunchObserver.Temperature int temperature) {
            super(sequenceId, snapshot);
            this.temperature = temperature;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof ActivityLaunched) {
                return temperature == ((ActivityLaunched)other).temperature &&
                        super.equals(other);
            }
            return false;
        }

        @Override
        protected String toStringBody() {
            return ", temperature=" + Integer.toString(temperature);
        }

        @Override
        protected void writeToParcelImpl(Parcel p, int flags) {
           super.writeToParcelImpl(p, flags);
           p.writeInt(temperature);
        }

        ActivityLaunched(Parcel p) {
            super(p);
            temperature = p.readInt();
        }
    }

    public static final class ActivityLaunchFinished extends BaseWithActivityRecordData {
        public ActivityLaunchFinished(@SequenceId long sequenceId,
                @NonNull @ActivityRecordProto byte[] snapshot) {
            super(sequenceId, snapshot);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof ActivityLaunched) {
                return super.equals(other);
            }
            return false;
        }
    }

     public static class ActivityLaunchCancelled extends AppLaunchEvent {
        public final @Nullable @ActivityRecordProto byte[] activityRecordSnapshot;

        public ActivityLaunchCancelled(@SequenceId long sequenceId,
                @Nullable @ActivityRecordProto byte[] snapshot) {
            super(sequenceId);
            activityRecordSnapshot = snapshot;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof ActivityLaunchCancelled) {
                return Objects.equals(activityRecordSnapshot,
                        ((ActivityLaunchCancelled)other).activityRecordSnapshot) &&
                        super.equals(other);
            }
            return false;
        }

        @Override
        protected String toStringBody() {
            return ", " + activityRecordSnapshot.toString();
        }

        @Override
        protected void writeToParcelImpl(Parcel p, int flags) {
           super.writeToParcelImpl(p, flags);
           if (activityRecordSnapshot != null) {
               p.writeBoolean(true);
               ActivityRecordProtoParcelable.write(p, activityRecordSnapshot, flags);
           } else {
               p.writeBoolean(false);
           }
        }

        ActivityLaunchCancelled(Parcel p) {
            super(p);
            if (p.readBoolean()) {
                activityRecordSnapshot = ActivityRecordProtoParcelable.create(p);
            } else {
                activityRecordSnapshot = null;
            }
        }
    }

    @Override
    public @ContentsFlags int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel p, @WriteFlags int flags) {
        p.writeInt(getTypeIndex());

        writeToParcelImpl(p, flags);
    }


    public static Creator<AppLaunchEvent> CREATOR =
            new Creator<AppLaunchEvent>() {
        @Override
        public AppLaunchEvent createFromParcel(Parcel source) {
            int typeIndex = source.readInt();

            Class<?> kls = getClassFromTypeIndex(typeIndex);
            if (kls == null) {
                throw new IllegalArgumentException("Invalid type index: " + typeIndex);
            }

            try {
                return (AppLaunchEvent) kls.getConstructor(Parcel.class).newInstance(source);
            } catch (InstantiationException e) {
                throw new AssertionError(e);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            } catch (InvocationTargetException e) {
                throw new AssertionError(e);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public AppLaunchEvent[] newArray(int size) {
            return new AppLaunchEvent[0];
        }
    };

    protected void writeToParcelImpl(Parcel p, int flags) {
        p.writeLong(sequenceId);
    }

    protected AppLaunchEvent(Parcel p) {
        sequenceId = p.readLong();
    }

    private int getTypeIndex() {
        for (int i = 0; i < sTypes.length; ++i) {
            if (sTypes[i].equals(this.getClass())) {
                return i;
            }
        }
        throw new AssertionError("sTypes did not include this type: " + this.getClass());
    }

    private static @Nullable Class<?> getClassFromTypeIndex(int typeIndex) {
        if (typeIndex >= 0 && typeIndex < sTypes.length) {
            return sTypes[typeIndex];
        }
        return null;
    }

    // Index position matters: It is used to encode the specific type in parceling.
    // Keep up-to-date with C++ side.
    private static Class<?>[] sTypes = new Class[] {
            IntentStarted.class,
            IntentFailed.class,
            ActivityLaunched.class,
            ActivityLaunchFinished.class,
            ActivityLaunchCancelled.class,
    };

    public static class ActivityRecordProtoParcelable {
        public static void write(Parcel p, @ActivityRecordProto byte[] activityRecordSnapshot,
                int flags) {
            p.writeByteArray(activityRecordSnapshot);
        }

        public static @ActivityRecordProto byte[] create(Parcel p) {
            byte[] data = p.createByteArray();

            return data;
        }
    }

    public static class IntentProtoParcelable {
        private static final int INTENT_PROTO_CHUNK_SIZE = 1024;

        public static void write(Parcel p, @NonNull Intent intent, int flags) {
            // There does not appear to be a way to 'reset' a ProtoOutputBuffer stream,
            // so create a new one every time.
            final ProtoOutputStream protoOutputStream =
                    new ProtoOutputStream(INTENT_PROTO_CHUNK_SIZE);
            // Write this data out as the top-most IntentProto (i.e. it is not a sub-object).
            intent.writeToProto(protoOutputStream);
            final byte[] bytes = protoOutputStream.getBytes();

            p.writeByteArray(bytes);
        }

        // TODO: Should be mockable for testing?
        // We cannot deserialize in the platform because we don't have a 'readFromProto'
        // code.
        public static @NonNull Intent create(Parcel p) {
            // This will "read" the correct amount of data, but then we discard it.
            byte[] data = p.createByteArray();

            // Never called by real code in a platform, this binder API is implemented only in C++.
            return new Intent("<cannot deserialize IntentProto>");
        }
    }
}
