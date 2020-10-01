/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.integrity;

import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represent rules to be used in the rule evaluation engine to match against app installs.
 *
 * <p>Instances of this class are immutable.
 *
 * @hide
 */
@TestApi
@SystemApi
@VisibleForTesting
public final class Rule implements Parcelable {

    /** @hide */
    @IntDef(
            value = {
                DENY,
                FORCE_ALLOW,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Effect {}

    /** If this rule matches the install, the install should be denied. */
    public static final int DENY = 0;

    /**
     * If this rule matches the install, the install will be allowed regardless of other matched
     * rules.
     */
    public static final int FORCE_ALLOW = 1;

    private final @NonNull IntegrityFormula mFormula;
    private final @Effect int mEffect;

    public Rule(@NonNull IntegrityFormula formula, @Effect int effect) {
        checkArgument(isValidEffect(effect), String.format("Unknown effect: %d", effect));
        this.mFormula = Objects.requireNonNull(formula);
        this.mEffect = effect;
    }

    Rule(Parcel in) {
        mFormula = IntegrityFormula.readFromParcel(in);
        mEffect = in.readInt();
    }

    @NonNull
    public static final Creator<Rule> CREATOR =
            new Creator<Rule>() {
                @Override
                public Rule createFromParcel(Parcel in) {
                    return new Rule(in);
                }

                @Override
                public Rule[] newArray(int size) {
                    return new Rule[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        IntegrityFormula.writeToParcel(mFormula, dest, flags);
        dest.writeInt(mEffect);
    }

    @NonNull
    public IntegrityFormula getFormula() {
        return mFormula;
    }

    public @Effect int getEffect() {
        return mEffect;
    }

    @Override
    public String toString() {
        return String.format("Rule: %s, %s", mFormula, effectToString(mEffect));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Rule that = (Rule) o;
        return mEffect == that.mEffect && Objects.equals(mFormula, that.mFormula);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFormula, mEffect);
    }

    private static String effectToString(int effect) {
        switch (effect) {
            case DENY:
                return "DENY";
            case FORCE_ALLOW:
                return "FORCE_ALLOW";
            default:
                throw new IllegalArgumentException("Unknown effect " + effect);
        }
    }

    private static boolean isValidEffect(int effect) {
        return effect == DENY || effect == FORCE_ALLOW;
    }
}
