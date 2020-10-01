/*
 * Copyright (C) 2015 The Android Open Source Project
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


package android.service.chooser;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A ChooserTarget represents a deep-link into an application as returned by a
 * {@link android.service.chooser.ChooserTargetService}.
 *
 * <p>A chooser target represents a specific deep link target into an application exposed
 * for selection by the user. This might be a frequently emailed contact, a recently active
 * group messaging conversation, a folder in a cloud storage app, a collection of related
 * items published on a social media service or any other contextually relevant grouping
 * of target app + relevant metadata.</p>
 *
 * <p>Creators of chooser targets should consult the relevant design guidelines for the type
 * of target they are presenting. For example, targets involving people should be presented
 * with a circular icon.</p>
 *
 * @deprecated For publishing direct share targets, please follow the instructions in
 * https://developer.android.com/training/sharing/receive.html#providing-direct-share-targets
 * instead.
 */
@Deprecated
public final class ChooserTarget implements Parcelable {
    private static final String TAG = "ChooserTarget";

    /**
     * The title of this target that will be shown to the user. The title may be truncated
     * if it is too long to display in the space provided.
     */
    private CharSequence mTitle;

    /**
     * The icon that will be shown to the user to represent this target.
     * The system may resize this icon as appropriate.
     */
    private Icon mIcon;

    /**
     * The ComponentName of the Activity to be invoked. Must be part of the target creator's
     * own package or an Activity exported by its package.
     */
    private ComponentName mComponentName;

    /**
     * A Bundle to merge with the extras of the intent sent to this target.
     * Any extras here will override the extras from the original intent.
     */
    private Bundle mIntentExtras;

    /**
     * The score given to this item. It can be normalized.
     */
    private float mScore;

    /**
     * Construct a deep link target for presentation by a chooser UI.
     *
     * <p>A target is composed of a title and an icon for presentation to the user.
     * The UI presenting this target may truncate the title if it is too long to be presented
     * in the available space, as well as crop, resize or overlay the supplied icon.</p>
     *
     * <p>The creator of a target may supply a ranking score. This score is assumed to be relative
     * to the other targets supplied by the same
     * {@link ChooserTargetService#onGetChooserTargets(ComponentName, IntentFilter) query}.
     * Scores should be in the range from 0.0f (unlikely match) to 1.0f (very relevant match).
     * Scores for a set of targets do not need to sum to 1.</p>
     *
     * <p>The ComponentName must be the name of an Activity component in the creator's own
     * package, or an exported component from any other package. You may provide an optional
     * Bundle of extras that will be merged into the final intent before it is sent to the
     * target Activity; use this to add any additional data about the deep link that the target
     * activity will read. e.g. conversation IDs, email addresses, etc.</p>
     *
     * <p>Take care not to place custom {@link android.os.Parcelable} types into
     * the extras bundle, as the system will not be able to unparcel them to merge them.</p>
     *
     * @param title title of this target that will be shown to a user
     * @param icon icon to represent this target
     * @param score ranking score for this target between 0.0f and 1.0f, inclusive
     * @param componentName Name of the component to be launched if this target is chosen
     * @param intentExtras Bundle of extras to merge with the extras of the launched intent
     */
    public ChooserTarget(CharSequence title, Icon icon, float score,
            ComponentName componentName, @Nullable Bundle intentExtras) {
        mTitle = title;
        mIcon = icon;
        if (score > 1.f || score < 0.f) {
            throw new IllegalArgumentException("Score " + score + " out of range; "
                    + "must be between 0.0f and 1.0f");
        }
        mScore = score;
        mComponentName = componentName;
        mIntentExtras = intentExtras;
    }

    ChooserTarget(Parcel in) {
        mTitle = in.readCharSequence();
        if (in.readInt() != 0) {
            mIcon = Icon.CREATOR.createFromParcel(in);
        } else {
            mIcon = null;
        }
        mScore = in.readFloat();
        mComponentName = ComponentName.readFromParcel(in);
        mIntentExtras = in.readBundle();
    }

    /**
     * Returns the title of this target for display to a user. The UI displaying the title
     * may truncate this title if it is too long to be displayed in full.
     *
     * @return the title of this target, intended to be shown to a user
     */
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Returns the icon representing this target for display to a user. The UI displaying the icon
     * may crop, resize or overlay this icon.
     *
     * @return the icon representing this target, intended to be shown to a user
     */
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * Returns the ranking score supplied by the creator of this ChooserTarget.
     * Values are between 0.0f and 1.0f. The UI displaying the target may
     * take this score into account when sorting and merging targets from multiple sources.
     *
     * @return the ranking score for this target between 0.0f and 1.0f, inclusive
     */
    public float getScore() {
        return mScore;
    }

    /**
     * Returns the ComponentName of the Activity that should be launched for this ChooserTarget.
     *
     * @return the name of the target Activity to launch
     */
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Returns the Bundle of extras to be added to an intent launched to this target.
     *
     * @return the extras to merge with the extras of the intent being launched
     */
    public Bundle getIntentExtras() {
        return mIntentExtras;
    }

    @Override
    public String toString() {
        return "ChooserTarget{"
                + mComponentName
                + ", " + mIntentExtras
                + ", '" + mTitle
                + "', " + mScore + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeCharSequence(mTitle);
        if (mIcon != null) {
            dest.writeInt(1);
            mIcon.writeToParcel(dest, 0);
        } else {
            dest.writeInt(0);
        }
        dest.writeFloat(mScore);
        ComponentName.writeToParcel(mComponentName, dest);
        dest.writeBundle(mIntentExtras);
    }

    public static final @android.annotation.NonNull Creator<ChooserTarget> CREATOR
            = new Creator<ChooserTarget>() {
        @Override
        public ChooserTarget createFromParcel(Parcel source) {
            return new ChooserTarget(source);
        }

        @Override
        public ChooserTarget[] newArray(int size) {
            return new ChooserTarget[size];
        }
    };
}
