/*
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.notification;

import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Flags;
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.proto.ProtoOutputStream;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * The current condition of an {@link android.app.AutomaticZenRule}, provided by the
 * app that owns the rule. Used to tell the system to enter Do Not
 * Disturb mode and request that the system exit Do Not Disturb mode.
 */
public final class Condition implements Parcelable {

    public static final String SCHEME = "condition";

    /** @hide */
    @IntDef(prefix = { "STATE_" }, value = {
            STATE_FALSE,
            STATE_TRUE,
            STATE_UNKNOWN,
            STATE_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    /**
     * Indicates that Do Not Disturb should be turned off. Note that all Conditions from all
     * {@link android.app.AutomaticZenRule} providers must be off for Do Not Disturb to be turned
     * off on the device.
     */
    public static final int STATE_FALSE = 0;
    /**
     * Indicates that Do Not Disturb should be turned on.
     */
    public static final int STATE_TRUE = 1;
    public static final int STATE_UNKNOWN = 2;
    public static final int STATE_ERROR = 3;

    public static final int FLAG_RELEVANT_NOW = 1 << 0;
    public static final int FLAG_RELEVANT_ALWAYS = 1 << 1;

    /**
     * The URI representing the rule being updated.
     * See {@link android.app.AutomaticZenRule#getConditionId()}.
     */
    public final Uri id;

    /**
     * A summary of what the rule encoded in {@link #id} means when it is enabled. User visible
     * if the state of the condition is {@link #STATE_TRUE}.
     */
    public final String summary;

    public final String line1;
    public final String line2;

    /**
     * The state of this condition. {@link #STATE_TRUE} will enable Do Not Disturb mode.
     * {@link #STATE_FALSE} will turn Do Not Disturb off for this rule. Note that Do Not Disturb
     * might still be enabled globally if other conditions are in a {@link #STATE_TRUE} state.
     */
    @State
    public final int state;

    public final int flags;
    public final int icon;

    /** @hide */
    @IntDef(prefix = { "SOURCE_" }, value = {
            SOURCE_UNKNOWN,
            SOURCE_USER_ACTION,
            SOURCE_SCHEDULE,
            SOURCE_CONTEXT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Source {}

    /** The state is changing due to an unknown reason. */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int SOURCE_UNKNOWN = 0;
    /** The state is changing due to an explicit user action. */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int SOURCE_USER_ACTION = 1;
    /** The state is changing due to an automatic schedule (alarm, set time, etc). */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int SOURCE_SCHEDULE = 2;
    /** The state is changing due to a change in context (such as detected driving or sleeping). */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final int SOURCE_CONTEXT = 3;

    /** The source of, or reason for, the state change represented by this Condition. **/
    @FlaggedApi(Flags.FLAG_MODES_API)
    public final @Source int source; // default = SOURCE_UNKNOWN

    /**
     * The maximum string length for any string contained in this condition.
     * @hide
     */
    public static final int MAX_STRING_LENGTH = 1000;

    /**
     * An object representing the current state of a {@link android.app.AutomaticZenRule}.
     * @param id the {@link android.app.AutomaticZenRule#getConditionId()} of the zen rule
     * @param summary a user visible description of the rule state
     * @param state whether the mode should be activated or deactivated
     */
    // TODO: b/310208502 - Deprecate this in favor of constructor which specifies source.
    public Condition(Uri id, String summary, int state) {
        this(id, summary, "", "", -1, state, SOURCE_UNKNOWN, FLAG_RELEVANT_ALWAYS);
    }

    /**
     * An object representing the current state of a {@link android.app.AutomaticZenRule}.
     * @param id the {@link android.app.AutomaticZenRule#getConditionId()} of the zen rule
     * @param summary a user visible description of the rule state
     * @param state whether the mode should be activated or deactivated
     * @param source the source of, or reason for, the state change represented by this Condition
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public Condition(@Nullable Uri id, @Nullable String summary, @State int state,
                     @Source int source) {
        this(id, summary, "", "", -1, state, source, FLAG_RELEVANT_ALWAYS);
    }

    // TODO: b/310208502 - Deprecate this in favor of constructor which specifies source.
    public Condition(Uri id, String summary, String line1, String line2, int icon,
            int state, int flags) {
        this(id, summary, line1, line2, icon, state, SOURCE_UNKNOWN, flags);
    }

    /**
     * An object representing the current state of a {@link android.app.AutomaticZenRule}.
     * @param id the {@link android.app.AutomaticZenRule#getConditionId()} of the zen rule
     * @param summary a user visible description of the rule state
     * @param line1 a user-visible description of when the rule will end
     * @param line2 a continuation of the user-visible description of when the rule will end
     * @param icon an icon representing this condition
     * @param state whether the mode should be activated or deactivated
     * @param source the source of, or reason for, the state change represented by this Condition
     * @param flags flags on this condition
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public Condition(@Nullable Uri id, @Nullable String summary, @Nullable String line1,
                     @Nullable String line2, int icon, @State int state, @Source int source,
                     int flags) {
        if (id == null) throw new IllegalArgumentException("id is required");
        if (summary == null) throw new IllegalArgumentException("summary is required");
        if (!isValidState(state)) throw new IllegalArgumentException("state is invalid: " + state);
        this.id = getTrimmedUri(id);
        this.summary = getTrimmedString(summary);
        this.line1 = getTrimmedString(line1);
        this.line2 = getTrimmedString(line2);
        this.icon = icon;
        this.state = state;
        this.source = checkValidSource(source);
        this.flags = flags;
    }

    public Condition(Parcel source) {
        // This constructor passes all fields directly into the constructor that takes all the
        // fields as arguments; that constructor will trim each of the input strings to
        // max length if necessary.
        this((Uri)source.readParcelable(Condition.class.getClassLoader(), android.net.Uri.class),
                source.readString(),
                source.readString(),
                source.readString(),
                source.readInt(),
                source.readInt(),
                Flags.modesApi() ? source.readInt() : SOURCE_UNKNOWN,
                source.readInt());
    }

    /** @hide */
    public void validate() {
        if (Flags.modesApi()) {
            checkValidSource(source);
        }
    }

    private static boolean isValidState(int state) {
        return state >= STATE_FALSE && state <= STATE_ERROR;
    }

    private static int checkValidSource(@Source int source) {
        if (Flags.modesApi()) {
            checkArgument(source >= SOURCE_UNKNOWN && source <= SOURCE_CONTEXT,
                    "Condition source must be one of SOURCE_UNKNOWN, SOURCE_USER_ACTION, "
                            + "SOURCE_SCHEDULE, or SOURCE_CONTEXT");
        }
        return source;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(id, 0);
        dest.writeString(summary);
        dest.writeString(line1);
        dest.writeString(line2);
        dest.writeInt(icon);
        dest.writeInt(state);
        if (Flags.modesApi()) {
            dest.writeInt(this.source);
        }
        dest.writeInt(this.flags);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(Condition.class.getSimpleName()).append('[')
                .append("state=").append(stateToString(state))
                .append(",id=").append(id)
                .append(",summary=").append(summary)
                .append(",line1=").append(line1)
                .append(",line2=").append(line2)
                .append(",icon=").append(icon);
        if (Flags.modesApi()) {
            sb.append(",source=").append(sourceToString(source));
        }
        return sb.append(",flags=").append(flags)
                .append(']').toString();

    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        // id is guaranteed not to be null.
        proto.write(ConditionProto.ID, id.toString());
        proto.write(ConditionProto.SUMMARY, summary);
        proto.write(ConditionProto.LINE_1, line1);
        proto.write(ConditionProto.LINE_2, line2);
        proto.write(ConditionProto.ICON, icon);
        proto.write(ConditionProto.STATE, state);
        // TODO: b/310644464 - Add source to dump.
        proto.write(ConditionProto.FLAGS, flags);

        proto.end(token);
    }

    public static String stateToString(int state) {
        if (state == STATE_FALSE) return "STATE_FALSE";
        if (state == STATE_TRUE) return "STATE_TRUE";
        if (state == STATE_UNKNOWN) return "STATE_UNKNOWN";
        if (state == STATE_ERROR) return "STATE_ERROR";
        throw new IllegalArgumentException("state is invalid: " + state);
    }

    /**
     * Provides a human-readable string version of the Source enum.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static @NonNull String sourceToString(@Source int source) {
        if (source == SOURCE_UNKNOWN) return "SOURCE_UNKNOWN";
        if (source == SOURCE_USER_ACTION) return "SOURCE_USER_ACTION";
        if (source == SOURCE_SCHEDULE) return "SOURCE_SCHEDULE";
        if (source == SOURCE_CONTEXT) return "SOURCE_CONTEXT";
        throw new IllegalArgumentException("source is invalid: " + source);
    }

    public static String relevanceToString(int flags) {
        final boolean now = (flags & FLAG_RELEVANT_NOW) != 0;
        final boolean always = (flags & FLAG_RELEVANT_ALWAYS) != 0;
        if (!now && !always) return "NONE";
        if (now && always) return "NOW, ALWAYS";
        return now ? "NOW" : "ALWAYS";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof Condition)) return false;
        if (o == this) return true;
        final Condition other = (Condition) o;
        boolean finalEquals = Objects.equals(other.id, id)
                && Objects.equals(other.summary, summary)
                && Objects.equals(other.line1, line1)
                && Objects.equals(other.line2, line2)
                && other.icon == icon
                && other.state == state
                && other.flags == flags;
        if (Flags.modesApi()) {
            return finalEquals && other.source == source;
        }
        return finalEquals;
    }

    @Override
    public int hashCode() {
        if (Flags.modesApi()) {
            return Objects.hash(id, summary, line1, line2, icon, state, source, flags);
        }
        return Objects.hash(id, summary, line1, line2, icon, state, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public Condition copy() {
        final Parcel parcel = Parcel.obtain();
        try {
            writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return new Condition(parcel);
        } finally {
            parcel.recycle();
        }
    }

    public static Uri.Builder newId(Context context) {
        return new Uri.Builder()
                .scheme(Condition.SCHEME)
                .authority(context.getPackageName());
    }

    public static boolean isValidId(Uri id, String pkg) {
        return id != null && SCHEME.equals(id.getScheme()) && pkg.equals(id.getAuthority());
    }

    public static final @android.annotation.NonNull Parcelable.Creator<Condition> CREATOR
            = new Parcelable.Creator<Condition>() {
        @Override
        public Condition createFromParcel(Parcel source) {
            return new Condition(source);
        }

        @Override
        public Condition[] newArray(int size) {
            return new Condition[size];
        }
    };

    /**
     * Returns a truncated copy of the string if the string is longer than MAX_STRING_LENGTH.
     */
    private static String getTrimmedString(String input) {
        if (input != null && input.length() > MAX_STRING_LENGTH) {
            return input.substring(0, MAX_STRING_LENGTH);
        }
        return input;
    }

    /**
     * Returns a truncated copy of the Uri by trimming the string representation to the maximum
     * string length.
     */
    private static Uri getTrimmedUri(Uri input) {
        if (input != null && input.toString().length() > MAX_STRING_LENGTH) {
            return Uri.parse(getTrimmedString(input.toString()));
        }
        return input;
    }
}
