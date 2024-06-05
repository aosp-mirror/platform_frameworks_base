/**
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.radio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Identifier that can uniquely identifies a program.
 *
 * This is a transport class used for internal communication between
 * Broadcast Radio Service and Radio Manager. Do not use it directly.
 *
 * @hide
 */
public final class UniqueProgramIdentifier implements Parcelable {

    @NonNull private final ProgramSelector.Identifier mPrimaryId;
    @NonNull private final ProgramSelector.Identifier[] mCriticalSecondaryIds;

    /**
     * Check whether some secondary identifier is needed to uniquely specify a program for
     * a given primary identifier type
     *
     * @param type primary identifier type {@link ProgramSelector.IdentifierType}
     * @return whether some secondary identifier is needed to uniquely specify a program.
     */
    public static boolean requireCriticalSecondaryIds(@ProgramSelector.IdentifierType int type) {
        return type == ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT || type
                == ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT;
    }

    public UniqueProgramIdentifier(ProgramSelector selector) {
        Objects.requireNonNull(selector, "Program selector can not be null");
        mPrimaryId = selector.getPrimaryId();
        switch (mPrimaryId.getType()) {
            case ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT:
            case ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT:
                ProgramSelector.Identifier ensembleId = null;
                ProgramSelector.Identifier frequencyId = null;
                ProgramSelector.Identifier[] secondaryIds = selector.getSecondaryIds();
                for (int i = 0; i < secondaryIds.length; i++) {
                    if (ensembleId == null && secondaryIds[i].getType()
                            == ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE) {
                        ensembleId = selector.getSecondaryIds()[i];
                    } else if (frequencyId == null && secondaryIds[i].getType()
                            == ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY) {
                        frequencyId = secondaryIds[i];
                    }
                    if (ensembleId != null && frequencyId != null) {
                        break;
                    }
                }
                if (ensembleId == null) {
                    if (frequencyId == null) {
                        mCriticalSecondaryIds = new ProgramSelector.Identifier[]{};
                    } else {
                        mCriticalSecondaryIds = new ProgramSelector.Identifier[]{frequencyId};
                    }
                } else if (frequencyId == null) {
                    mCriticalSecondaryIds = new ProgramSelector.Identifier[]{ensembleId};
                } else {
                    mCriticalSecondaryIds = new ProgramSelector.Identifier[]{ensembleId,
                            frequencyId};
                }
                break;
            default:
                mCriticalSecondaryIds = new ProgramSelector.Identifier[]{};
        }

    }

    public UniqueProgramIdentifier(ProgramSelector.Identifier primaryId) {
        mPrimaryId = primaryId;
        mCriticalSecondaryIds = new ProgramSelector.Identifier[]{};
    }

    @NonNull
    public ProgramSelector.Identifier getPrimaryId() {
        return mPrimaryId;
    }

    @NonNull
    public List<ProgramSelector.Identifier> getCriticalSecondaryIds() {
        return List.of(mCriticalSecondaryIds);
    }

    @NonNull
    @Override
    public String toString() {
        return new StringBuilder("UniqueProgramIdentifier(primary=").append(mPrimaryId)
                .append(", criticalSecondary=")
                .append(Arrays.toString(mCriticalSecondaryIds)).append(")")
                .toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPrimaryId, Arrays.hashCode(mCriticalSecondaryIds));
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof UniqueProgramIdentifier)) return false;
        UniqueProgramIdentifier other = (UniqueProgramIdentifier) obj;
        return other.mPrimaryId.equals(mPrimaryId)
                && Arrays.equals(other.mCriticalSecondaryIds, mCriticalSecondaryIds);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private UniqueProgramIdentifier(Parcel in) {
        mPrimaryId = in.readTypedObject(ProgramSelector.Identifier.CREATOR);
        mCriticalSecondaryIds = in.createTypedArray(ProgramSelector.Identifier.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mPrimaryId, 0);
        dest.writeTypedArray(mCriticalSecondaryIds, 0);
        if (Stream.of(mCriticalSecondaryIds).anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "criticalSecondaryIds list must not contain nulls");
        }
    }

    @NonNull
    public static final Parcelable.Creator<UniqueProgramIdentifier> CREATOR =
            new Parcelable.Creator<UniqueProgramIdentifier>() {
                public UniqueProgramIdentifier createFromParcel(Parcel in) {
                    return new UniqueProgramIdentifier(in);
                }

                public UniqueProgramIdentifier[] newArray(int size) {
                    return new UniqueProgramIdentifier[size];
                }
            };
}
