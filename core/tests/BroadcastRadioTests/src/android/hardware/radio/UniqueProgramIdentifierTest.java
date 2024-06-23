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

import static org.junit.Assert.assertThrows;

import android.annotation.Nullable;
import android.os.Parcel;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;

public final class UniqueProgramIdentifierTest {
    private static final ProgramSelector.Identifier FM_IDENTIFIER = new ProgramSelector.Identifier(
            ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, /* value= */ 88_500);

    private static final ProgramSelector.Identifier DAB_DMB_SID_EXT_IDENTIFIER_1 =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                    /* value= */ 0xA000000111L);
    private static final ProgramSelector.Identifier DAB_ENSEMBLE_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE,
                    /* value= */ 0x1001);
    private static final ProgramSelector.Identifier DAB_FREQUENCY_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 220352);
    private static final ProgramSelector.Identifier DAB_SCID_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_SCID,
                    /* value= */ 0x101);

    @Rule
    public final Expect expect = Expect.create();

    @Test
    public void requireCriticalSecondaryIds_forDab() {
        expect.withMessage("Critical secondary Id required for DAB")
                .that(UniqueProgramIdentifier.requireCriticalSecondaryIds(
                        ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT)).isTrue();
    }

    @Test
    public void constructor_withNullSelector() {
        ProgramSelector nullSelector = null;

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new UniqueProgramIdentifier(nullSelector));

        expect.withMessage("Null pointer exception for unique program identifier")
                .that(thrown).hasMessageThat().contains("can not be null");
    }

    @Test
    public void getPrimaryId_forUniqueProgramIdentifier() {
        ProgramSelector dabSelector = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER}, /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier = new UniqueProgramIdentifier(dabSelector);

        expect.withMessage("Primary id of DAB unique identifier")
                .that(dabIdentifier.getPrimaryId()).isEqualTo(DAB_DMB_SID_EXT_IDENTIFIER_1);
    }

    @Test
    public void getCriticalSecondaryIds_forDabUniqueProgramIdentifier() {
        ProgramSelector dabSelector = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER, DAB_SCID_IDENTIFIER},
                /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier = new UniqueProgramIdentifier(dabSelector);

        expect.withMessage("Critical secondary ids of DAB unique identifier")
                .that(dabIdentifier.getCriticalSecondaryIds()).containsExactly(
                        DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER);
    }

    @Test
    public void getCriticalSecondaryIds_forDabUniqueProgramIdentifierWithoutEnsemble() {
        ProgramSelector dabSelector = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_FREQUENCY_IDENTIFIER}, /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier = new UniqueProgramIdentifier(dabSelector);

        expect.withMessage("Critical secondary ids of DAB unique identifier without ensemble")
                .that(dabIdentifier.getCriticalSecondaryIds())
                .containsExactly(DAB_FREQUENCY_IDENTIFIER);
    }

    @Test
    public void getCriticalSecondaryIds_forDabUniqueProgramIdentifierWithoutSecondaryIds() {
        ProgramSelector dabSelector = getDabSelector(new ProgramSelector.Identifier[]{},
                /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier = new UniqueProgramIdentifier(dabSelector);

        expect.withMessage("Critical secondary ids of DAB unique identifier")
                .that(dabIdentifier.getCriticalSecondaryIds()).isEmpty();
    }

    @Test
    public void getCriticalSecondaryIds_forFmUniqueProgramIdentifier() {
        UniqueProgramIdentifier fmUniqueIdentifier = new UniqueProgramIdentifier(
                new ProgramSelector(ProgramSelector.PROGRAM_TYPE_FM, FM_IDENTIFIER,
                        new ProgramSelector.Identifier[]{new ProgramSelector.Identifier(
                                ProgramSelector.IDENTIFIER_TYPE_RDS_PI, /* value= */ 0x1003)},
                        /* vendorIds= */ null));

        expect.withMessage("Empty critical secondary id list of FM unique identifier")
                .that(fmUniqueIdentifier.getCriticalSecondaryIds()).isEmpty();
    }

    @Test
    public void toString_forUniqueProgramIdentifier() {
        ProgramSelector dabSelector = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER}, /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier = new UniqueProgramIdentifier(dabSelector);

        String identifierString = dabIdentifier.toString();

        expect.withMessage("Primary id in DAB unique identifier")
                .that(identifierString).contains(DAB_DMB_SID_EXT_IDENTIFIER_1.toString());
        expect.withMessage("Ensemble id in DAB unique identifier")
                .that(identifierString).contains(DAB_ENSEMBLE_IDENTIFIER.toString());
        expect.withMessage("Frequency id in DAB unique identifier")
                .that(identifierString).contains(DAB_FREQUENCY_IDENTIFIER.toString());
    }

    @Test
    public void hashCode_withTheSameUniqueProgramIdentifier_equals() {
        ProgramSelector dabSelector1 = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER}, /* vendorIds= */ null);
        ProgramSelector dabSelector2 = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_FREQUENCY_IDENTIFIER, DAB_ENSEMBLE_IDENTIFIER}, /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier1 = new UniqueProgramIdentifier(dabSelector1);
        UniqueProgramIdentifier dabIdentifier2 = new UniqueProgramIdentifier(dabSelector2);

        expect.withMessage("Hash code of the same DAB unique identifiers")
                .that(dabIdentifier1.hashCode()).isEqualTo(dabIdentifier2.hashCode());
    }

    @Test
    public void equals_withIdsForUniqueProgramIdentifier_returnsTrue() {
        ProgramSelector dabSelector1 = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER}, /* vendorIds= */ null);
        ProgramSelector dabSelector2 = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_FREQUENCY_IDENTIFIER, DAB_ENSEMBLE_IDENTIFIER}, /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier1 = new UniqueProgramIdentifier(dabSelector1);
        UniqueProgramIdentifier dabIdentifier2 = new UniqueProgramIdentifier(dabSelector2);

        expect.withMessage("The same DAB unique identifiers")
                .that(dabIdentifier1).isEqualTo(dabIdentifier2);
    }

    @Test
    public void equals_withDifferentPrimaryIdsForUniqueProgramIdentifier_returnsFalse() {
        ProgramSelector dabSelector1 = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER}, /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier1 = new UniqueProgramIdentifier(dabSelector1);
        UniqueProgramIdentifier fmUniqueIdentifier = new UniqueProgramIdentifier(FM_IDENTIFIER);

        expect.withMessage("Unique identifier with different primary ids")
                .that(dabIdentifier1).isNotEqualTo(fmUniqueIdentifier);
    }

    @Test
    public void equals_withDifferentSecondaryIdsForUniqueProgramIdentifier_returnsFalse() {
        ProgramSelector dabSelector1 = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER}, /* vendorIds= */ null);
        ProgramSelector.Identifier dabFreqIdentifier2 = new ProgramSelector.Identifier(
                ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY, /* value= */ 222064);
        ProgramSelector dabSelector2 = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, dabFreqIdentifier2}, /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier1 = new UniqueProgramIdentifier(dabSelector1);
        UniqueProgramIdentifier dabIdentifier2 = new UniqueProgramIdentifier(dabSelector2);

        expect.withMessage("DAB unique identifier with different secondary ids")
                .that(dabIdentifier1).isNotEqualTo(dabIdentifier2);
    }

    @Test
    public void equals_withMissingSecondaryIdsForUniqueProgramIdentifier() {
        ProgramSelector dabSelector1 = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER}, /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier1 = new UniqueProgramIdentifier(dabSelector1);
        ProgramSelector dabSelector2 = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER}, /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier2 = new UniqueProgramIdentifier(dabSelector2);

        expect.withMessage("DAB unique identifier with missing secondary ids")
                .that(dabIdentifier1).isNotEqualTo(dabIdentifier2);
    }

    @Test
    public void describeContents_forUniqueProgramIdentifier() {
        UniqueProgramIdentifier fmUniqueIdentifier = new UniqueProgramIdentifier(FM_IDENTIFIER);

        expect.withMessage("FM unique identifier contents")
                .that(fmUniqueIdentifier.describeContents()).isEqualTo(0);
    }

    @Test
    public void newArray_forUniqueProgramIdentifier() {
        int createArraySize = 3;
        UniqueProgramIdentifier[] identifiers = UniqueProgramIdentifier.CREATOR.newArray(
                createArraySize);

        expect.withMessage("Unique identifiers").that(identifiers).hasLength(createArraySize);
    }

    @Test
    public void writeToParcel_forUniqueProgramIdentifier() {
        ProgramSelector dabSelector = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER}, /* vendorIds= */ null);
        UniqueProgramIdentifier dabIdentifier = new UniqueProgramIdentifier(dabSelector);
        Parcel parcel = Parcel.obtain();

        dabIdentifier.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        UniqueProgramIdentifier identifierFromParcel = UniqueProgramIdentifier.CREATOR
                .createFromParcel(parcel);
        expect.withMessage("Unique identifier created from parcel")
                .that(identifierFromParcel).isEqualTo(dabIdentifier);
    }

    private ProgramSelector getDabSelector(@Nullable ProgramSelector.Identifier[] secondaryIds,
            @Nullable long[] vendorIds) {
        return new ProgramSelector(ProgramSelector.PROGRAM_TYPE_DAB, DAB_DMB_SID_EXT_IDENTIFIER_1,
                secondaryIds, vendorIds);
    }
}
