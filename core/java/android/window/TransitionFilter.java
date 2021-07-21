/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.window;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A parcelable filter that can be used for rerouting transitions to a remote. This is a local
 * representation so that the transition system doesn't need to make blocking queries over
 * binder.
 *
 * @hide
 */
public final class TransitionFilter implements Parcelable {

    /**
     * When non-null: this is a list of transition types that this filter applies to. This filter
     * will fail for transitions that aren't one of these types.
     */
    @Nullable public int[] mTypeSet = null;

    /**
     * A list of required changes. To pass, a transition must meet all requirements.
     */
    @Nullable public Requirement[] mRequirements = null;

    public TransitionFilter() {
    }

    private TransitionFilter(Parcel in) {
        mTypeSet = in.createIntArray();
        mRequirements = in.createTypedArray(Requirement.CREATOR);
    }

    /** @return true if `info` meets all the requirements to pass this filter. */
    public boolean matches(@NonNull TransitionInfo info) {
        if (mTypeSet != null) {
            // non-null typeset, so make sure info is one of the types.
            boolean typePass = false;
            for (int i = 0; i < mTypeSet.length; ++i) {
                if (info.getType() == mTypeSet[i]) {
                    typePass = true;
                    break;
                }
            }
            if (!typePass) return false;
        }
        // Make sure info meets all of the requirements.
        if (mRequirements != null) {
            for (int i = 0; i < mRequirements.length; ++i) {
                if (!mRequirements[i].matches(info)) return false;
            }
        }
        return true;
    }

    @Override
    /** @hide */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeIntArray(mTypeSet);
        dest.writeTypedArray(mRequirements, flags);
    }

    @NonNull
    public static final Creator<TransitionFilter> CREATOR =
            new Creator<TransitionFilter>() {
                @Override
                public TransitionFilter createFromParcel(Parcel in) {
                    return new TransitionFilter(in);
                }

                @Override
                public TransitionFilter[] newArray(int size) {
                    return new TransitionFilter[size];
                }
            };

    @Override
    /** @hide */
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{types=[");
        if (mTypeSet != null) {
            for (int i = 0; i < mTypeSet.length; ++i) {
                sb.append((i == 0 ? "" : ",") + mTypeSet[i]);
            }
        }
        sb.append("] checks=[");
        if (mRequirements != null) {
            for (int i = 0; i < mRequirements.length; ++i) {
                sb.append((i == 0 ? "" : ",") + mRequirements[i]);
            }
        }
        return sb.append("]}").toString();
    }

    /**
     * Matches a change that a transition must contain to pass this filter. All requirements in a
     * filter must be met to pass the filter.
     */
    public static final class Requirement implements Parcelable {
        public int mActivityType = ACTIVITY_TYPE_UNDEFINED;
        public int[] mModes = null;

        public Requirement() {
        }

        private Requirement(Parcel in) {
            mActivityType = in.readInt();
            mModes = in.createIntArray();
        }

        /** Go through changes and find if at-least one change matches this filter */
        boolean matches(@NonNull TransitionInfo info) {
            for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                if (!TransitionInfo.isIndependent(change, info)) {
                    // Only look at independent animating windows.
                    continue;
                }
                if (mActivityType != ACTIVITY_TYPE_UNDEFINED) {
                    if (change.getTaskInfo() == null
                            || change.getTaskInfo().getActivityType() != mActivityType) {
                        continue;
                    }
                }
                if (mModes != null) {
                    boolean pass = false;
                    for (int m = 0; m < mModes.length; ++m) {
                        if (mModes[m] == change.getMode()) {
                            pass = true;
                            break;
                        }
                    }
                    if (!pass) continue;
                }
                return true;
            }
            return false;
        }

        /** Check if the request matches this filter. It may generate false positives */
        boolean matches(@NonNull TransitionRequestInfo request) {
            // Can't check modes since the transition hasn't been built at this point.
            if (mActivityType == ACTIVITY_TYPE_UNDEFINED) return true;
            return request.getTriggerTask() != null
                    && request.getTriggerTask().getActivityType() == mActivityType;
        }

        @Override
        /** @hide */
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mActivityType);
            dest.writeIntArray(mModes);
        }

        @NonNull
        public static final Creator<Requirement> CREATOR =
                new Creator<Requirement>() {
                    @Override
                    public Requirement createFromParcel(Parcel in) {
                        return new Requirement(in);
                    }

                    @Override
                    public Requirement[] newArray(int size) {
                        return new Requirement[size];
                    }
                };

        @Override
        /** @hide */
        public int describeContents() {
            return 0;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder();
            out.append("{atype=" + WindowConfiguration.activityTypeToString(mActivityType));
            out.append(" modes=[");
            if (mModes != null) {
                for (int i = 0; i < mModes.length; ++i) {
                    out.append((i == 0 ? "" : ",") + TransitionInfo.modeToString(mModes[i]));
                }
            }
            return out.append("]}").toString();
        }
    }
}
