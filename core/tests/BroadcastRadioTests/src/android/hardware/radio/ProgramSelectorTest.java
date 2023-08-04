/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.annotation.Nullable;
import android.os.Parcel;

import org.junit.Test;

public final class ProgramSelectorTest {

    private static final int CREATOR_ARRAY_SIZE = 2;
    private static final int FM_PROGRAM_TYPE = ProgramSelector.PROGRAM_TYPE_FM;
    private static final int DAB_PROGRAM_TYPE = ProgramSelector.PROGRAM_TYPE_DAB;
    private static final long FM_FREQUENCY = 88500;
    private static final long AM_FREQUENCY = 700;
    private static final ProgramSelector.Identifier FM_IDENTIFIER = new ProgramSelector.Identifier(
            ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, FM_FREQUENCY);
    private static final ProgramSelector.Identifier DAB_DMB_SID_EXT_IDENTIFIER_1 =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                    /* value= */ 0xA000000111L);
    private static final ProgramSelector.Identifier DAB_DMB_SID_EXT_IDENTIFIER_2 =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                    /* value= */ 0xA000000112L);
    private static final ProgramSelector.Identifier DAB_ENSEMBLE_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE,
                    /* value= */ 0x1001);
    private static final ProgramSelector.Identifier DAB_FREQUENCY_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 220352);

    @Test
    public void getType_forIdentifier() {
        assertWithMessage("Identifier type").that(FM_IDENTIFIER.getType())
                .isEqualTo(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY);
    }

    @Test
    public void isCategoryType_withCategoryTypeForIdentifier() {
        int typeChecked = ProgramSelector.IDENTIFIER_TYPE_VENDOR_START + 1;
        ProgramSelector.Identifier fmIdentifier = new ProgramSelector.Identifier(
                typeChecked, /* value= */ 99901);

        assertWithMessage("Whether %s is a category identifier type", typeChecked)
                .that(fmIdentifier.isCategoryType()).isTrue();
    }

    @Test
    public void isCategoryType_withNonCategoryTypeForIdentifier() {
        assertWithMessage("Is AMFM_FREQUENCY category identifier type")
                .that(FM_IDENTIFIER.isCategoryType()).isFalse();
    }

    @Test
    public void getValue_forIdentifier() {
        assertWithMessage("Identifier value")
                .that(FM_IDENTIFIER.getValue()).isEqualTo(FM_FREQUENCY);
    }

    @Test
    public void equals_withDifferentTypesForIdentifiers_returnsFalse() {
        assertWithMessage("Identifier with different identifier type")
                .that(FM_IDENTIFIER).isNotEqualTo(DAB_DMB_SID_EXT_IDENTIFIER_1);
    }

    @Test
    public void equals_withDifferentValuesForIdentifiers_returnsFalse() {
        assertWithMessage("Identifier with different identifier value")
                .that(DAB_DMB_SID_EXT_IDENTIFIER_2).isNotEqualTo(DAB_DMB_SID_EXT_IDENTIFIER_1);
    }

    @Test
    public void equals_withSameKeyAndValueForIdentifiers_returnsTrue() {
        ProgramSelector.Identifier fmIdentifierSame = new ProgramSelector.Identifier(
                ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, FM_FREQUENCY);

        assertWithMessage("Identifier of the same identifier")
                .that(FM_IDENTIFIER).isEqualTo(fmIdentifierSame);
    }

    @Test
    public void describeContents_forIdentifier() {
        assertWithMessage("FM identifier contents")
                .that(FM_IDENTIFIER.describeContents()).isEqualTo(0);
    }

    @Test
    public void newArray_forIdentifierCreator() {
        ProgramSelector.Identifier[] identifiers =
                ProgramSelector.Identifier.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        assertWithMessage("Identifiers").that(identifiers).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void writeToParcel_forIdentifier() {
        Parcel parcel = Parcel.obtain();

        FM_IDENTIFIER.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        ProgramSelector.Identifier identifierFromParcel =
                ProgramSelector.Identifier.CREATOR.createFromParcel(parcel);
        assertWithMessage("Identifier created from parcel")
                .that(identifierFromParcel).isEqualTo(FM_IDENTIFIER);
    }

    @Test
    public void getProgramType() {
        ProgramSelector selector = getFmSelector(/* secondaryIds= */ null, /* vendorIds= */ null);

        int programType = selector.getProgramType();

        assertWithMessage("Program type").that(programType).isEqualTo(FM_PROGRAM_TYPE);
    }

    @Test
    public void getPrimaryId() {
        ProgramSelector selector = getFmSelector(/* secondaryIds= */ null, /* vendorIds= */ null);

        ProgramSelector.Identifier programId = selector.getPrimaryId();

        assertWithMessage("Program Id").that(programId).isEqualTo(FM_IDENTIFIER);
    }

    @Test
    public void getSecondaryIds_withEmptySecondaryIds() {
        ProgramSelector selector = getFmSelector(/* secondaryIds= */ null, /* vendorIds= */ null);

        ProgramSelector.Identifier[] secondaryIds = selector.getSecondaryIds();

        assertWithMessage("Secondary ids of selector initialized with empty secondary ids")
                .that(secondaryIds).isEmpty();
    }

    @Test
    public void getSecondaryIds_withNonEmptySecondaryIds() {
        ProgramSelector.Identifier[] secondaryIdsExpected = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER};
        ProgramSelector selector = getDabSelector(secondaryIdsExpected, /* vendorIds= */ null);

        ProgramSelector.Identifier[] secondaryIds = selector.getSecondaryIds();

        assertWithMessage("Secondary identifier got")
                .that(secondaryIds).isEqualTo(secondaryIdsExpected);
    }

    @Test
    public void getFirstId_withIdInSelector() {
        ProgramSelector.Identifier[] secondaryIds = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_DMB_SID_EXT_IDENTIFIER_2, DAB_FREQUENCY_IDENTIFIER};
        ProgramSelector selector = getDabSelector(secondaryIds, /* vendorIds= */ null);

        long firstIdValue = selector.getFirstId(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT);

        assertWithMessage("Value of the first DAB_SID_EXT identifier")
                .that(firstIdValue).isEqualTo(DAB_DMB_SID_EXT_IDENTIFIER_1.getValue());
    }

    @Test
    public void getFirstId_withIdNotInSelector() {
        ProgramSelector.Identifier[] secondaryIds = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_DMB_SID_EXT_IDENTIFIER_2};
        ProgramSelector selector = getDabSelector(secondaryIds, /* vendorIds= */ null);

        int idType = ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY;
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            selector.getFirstId(idType);
        });

        assertWithMessage("Exception for getting first identifier %s", idType)
                .that(thrown).hasMessageThat().contains("Identifier " + idType + " not found");
    }

    @Test
    public void getAllIds_withIdInSelector() {
        ProgramSelector.Identifier[] secondaryIds = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_DMB_SID_EXT_IDENTIFIER_2, DAB_FREQUENCY_IDENTIFIER};
        ProgramSelector.Identifier[] allIdsExpected =
                {DAB_DMB_SID_EXT_IDENTIFIER_1, DAB_DMB_SID_EXT_IDENTIFIER_2};
        ProgramSelector selector = getDabSelector(secondaryIds, /* vendorIds= */ null);

        ProgramSelector.Identifier[] allIds =
                selector.getAllIds(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT);

        assertWithMessage("All DAB_SID_EXT identifiers in selector")
                .that(allIds).isEqualTo(allIdsExpected);
    }

    @Test
    public void getAllIds_withIdNotInSelector() {
        ProgramSelector.Identifier[] secondaryIds = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER};
        ProgramSelector selector = getDabSelector(secondaryIds, /* vendorIds= */ null);

        ProgramSelector.Identifier[] allIds =
                selector.getAllIds(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY);

        assertWithMessage("AMFM frequency identifiers found in selector")
                .that(allIds).isEmpty();
    }

    @Test
    public void getVendorIds_withEmptyVendorIds() {
        ProgramSelector selector = getFmSelector(/* secondaryIds= */ null, /* vendorIds= */ null);

        long[] vendorIds = selector.getVendorIds();

        assertWithMessage("Vendor Ids of selector initialized with empty vendor ids")
                .that(vendorIds).isEmpty();
    }

    @Test
    public void getVendorIds_withNonEmptyVendorIds() {
        long[] vendorIdsExpected = {12345, 678};
        ProgramSelector selector = getFmSelector(/* secondaryIds= */ null, vendorIdsExpected);

        long[] vendorIds = selector.getVendorIds();

        assertWithMessage("Vendor Ids of selector initialized with non-empty vendor ids")
                .that(vendorIds).isEqualTo(vendorIdsExpected);
    }

    @Test
    public void withSecondaryPreferred() {
        ProgramSelector.Identifier[] secondaryIds = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_DMB_SID_EXT_IDENTIFIER_2, DAB_FREQUENCY_IDENTIFIER};
        long[] vendorIdsExpected = {12345, 678};
        ProgramSelector selector = getDabSelector(secondaryIds, vendorIdsExpected);
        ProgramSelector.Identifier[] secondaryIdsExpected = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER, DAB_DMB_SID_EXT_IDENTIFIER_1};

        ProgramSelector selectorPreferred =
                selector.withSecondaryPreferred(DAB_DMB_SID_EXT_IDENTIFIER_1);

        assertWithMessage("Program type")
                .that(selectorPreferred.getProgramType()).isEqualTo(selector.getProgramType());
        assertWithMessage("Primary identifiers")
                .that(selectorPreferred.getPrimaryId()).isEqualTo(selector.getPrimaryId());
        assertWithMessage("Secondary identifiers")
                .that(selectorPreferred.getSecondaryIds()).isEqualTo(secondaryIdsExpected);
        assertWithMessage("Vendor Ids")
                .that(selectorPreferred.getVendorIds()).isEqualTo(vendorIdsExpected);
    }

    @Test
    public void createAmFmSelector_withValidFrequencyWithoutSubChannel() {
        int band = RadioManager.BAND_AM;
        ProgramSelector.Identifier primaryIdExpected = new ProgramSelector.Identifier(
                ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, AM_FREQUENCY);

        ProgramSelector selector = ProgramSelector.createAmFmSelector(band, (int) AM_FREQUENCY);

        assertWithMessage("Program type")
                .that(selector.getProgramType()).isEqualTo(ProgramSelector.PROGRAM_TYPE_AM);
        assertWithMessage("Primary identifiers")
                .that(selector.getPrimaryId()).isEqualTo(primaryIdExpected);
        assertWithMessage("Secondary identifiers")
                .that(selector.getSecondaryIds()).isEmpty();
    }

    @Test
    public void createAmFmSelector_withoutBandAndSubChannel() {
        ProgramSelector.Identifier primaryIdExpected = new ProgramSelector.Identifier(
                ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, FM_FREQUENCY);

        ProgramSelector selector = ProgramSelector.createAmFmSelector(
                RadioManager.BAND_INVALID, (int) FM_FREQUENCY);

        assertWithMessage("Program type")
                .that(selector.getProgramType()).isEqualTo(ProgramSelector.PROGRAM_TYPE_FM);
        assertWithMessage("Primary identifiers")
                .that(selector.getPrimaryId()).isEqualTo(primaryIdExpected);
        assertWithMessage("Secondary identifiers")
                .that(selector.getSecondaryIds()).isEmpty();
    }

    @Test
    public void createAmFmSelector_withValidFrequencyAndSubChannel() {
        int band = RadioManager.BAND_AM_HD;
        int subChannel = 2;
        ProgramSelector.Identifier primaryIdExpected = new ProgramSelector.Identifier(
                ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, AM_FREQUENCY);
        ProgramSelector.Identifier[] secondaryIdExpected = {
                new ProgramSelector.Identifier(
                        ProgramSelector.IDENTIFIER_TYPE_HD_SUBCHANNEL, subChannel - 1)
        };

        ProgramSelector selector = ProgramSelector.createAmFmSelector(band, (int) AM_FREQUENCY,
                subChannel);

        assertWithMessage("Program type")
                .that(selector.getProgramType()).isEqualTo(ProgramSelector.PROGRAM_TYPE_AM);
        assertWithMessage("Primary identifiers")
                .that(selector.getPrimaryId()).isEqualTo(primaryIdExpected);
        assertWithMessage("Secondary identifiers")
                .that(selector.getSecondaryIds()).isEqualTo(secondaryIdExpected);
    }

    @Test
    public void createAmFmSelector_withInvalidFrequency_throwsIllegalArgumentException() {
        int invalidFrequency = 50000;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            ProgramSelector.createAmFmSelector(RadioManager.BAND_AM, invalidFrequency);
        });

        assertWithMessage("Exception for using invalid frequency %s", invalidFrequency)
                .that(thrown).hasMessageThat().contains(
                "Provided value is not a valid AM/FM frequency: " + invalidFrequency);
    }

    @Test
    public void createAmFmSelector_withInvalidSubChannel_throwsIllegalArgumentException() {
        int invalidSubChannel = 9;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            ProgramSelector.createAmFmSelector(RadioManager.BAND_FM, (int) FM_FREQUENCY,
                    invalidSubChannel);
        });

        assertWithMessage("Exception for using invalid subchannel %s", invalidSubChannel)
                .that(thrown).hasMessageThat().contains(
                "Invalid subchannel: " + invalidSubChannel);
    }

    @Test
    public void createAmFmSelector_withSubChannelNotSupported_throwsIllegalArgumentException() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            ProgramSelector.createAmFmSelector(RadioManager.BAND_FM, (int) FM_FREQUENCY,
                    /* subChannel= */ 1);
        });

        assertWithMessage("Exception for using sub-channel on radio not supporting it")
                .that(thrown)
                .hasMessageThat().contains("Subchannels are not supported for non-HD radio");
    }

    @Test
    public void equals_withDifferentSecondaryIds_returnTrue() {
        ProgramSelector.Identifier[] secondaryIds1 = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER};
        ProgramSelector selector1 = getDabSelector(secondaryIds1, /* vendorIds= */ null);
        ProgramSelector selector2 = getDabSelector(
                /* secondaryIds= */ null, /* vendorIds= */ null);

        assertWithMessage("Selector with different secondary id")
                .that(selector1).isEqualTo(selector2);
    }

    @Test
    public void equals_withDifferentPrimaryIds_returnFalse() {
        ProgramSelector selector1 = getFmSelector(
                /* secondaryIds= */ null, /* vendorIds= */ null);
        ProgramSelector.Identifier fmIdentifier2 = new ProgramSelector.Identifier(
                ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, /* value= */ 88700);
        ProgramSelector selector2 = new ProgramSelector(FM_PROGRAM_TYPE, fmIdentifier2,
                /* secondaryIds= */ null, /* vendorIds= */ null);

        assertWithMessage("Selector with different primary id")
                .that(selector1).isNotEqualTo(selector2);
    }

    @Test
    public void strictEquals_withDifferentPrimaryIds_returnsFalse() {
        ProgramSelector selector1 = getFmSelector(
                /* secondaryIds= */ null, /* vendorIds= */ null);
        ProgramSelector.Identifier fmIdentifier2 = new ProgramSelector.Identifier(
                ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, /* value= */ 88700);
        ProgramSelector selector2 = new ProgramSelector(FM_PROGRAM_TYPE, fmIdentifier2,
                /* secondaryIds= */ null, /* vendorIds= */ null);

        assertWithMessage(
                "Whether two selectors with different primary ids are strictly equal")
                .that(selector1.strictEquals(selector2)).isFalse();
    }

    @Test
    public void strictEquals_withDifferentSecondaryIds_returnsFalse() {
        ProgramSelector.Identifier[] secondaryIds1 = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER};
        ProgramSelector selector1 = getDabSelector(secondaryIds1, /* vendorIds= */ null);
        ProgramSelector.Identifier[] secondaryIds2 = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER};
        ProgramSelector selector2 = getDabSelector(secondaryIds2, /* vendorIds= */ null);

        assertWithMessage(
                "Whether two selectors with different secondary ids are strictly equal")
                .that(selector1.strictEquals(selector2)).isFalse();
    }

    @Test
    public void strictEquals_withDifferentSecondaryIdsOrders_returnsTrue() {
        ProgramSelector.Identifier[] secondaryIds1 = new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER};
        ProgramSelector selector1 = getDabSelector(secondaryIds1, /* vendorIds= */ null);
        ProgramSelector.Identifier[] secondaryIds2 = new ProgramSelector.Identifier[]{
                DAB_FREQUENCY_IDENTIFIER, DAB_ENSEMBLE_IDENTIFIER};
        ProgramSelector selector2 = getDabSelector(secondaryIds2, /* vendorIds= */ null);

        assertWithMessage(
                "Whether two selectors with different secondary id orders are strictly equal")
                .that(selector1.strictEquals(selector2)).isTrue();
    }

    @Test
    public void describeContents_forProgramSelector() {
        assertWithMessage("FM selector contents")
                .that(getFmSelector(/* secondaryIds= */ null, /* vendorIds= */ null)
                        .describeContents()).isEqualTo(0);
    }

    @Test
    public void newArray_forProgramSelectorCreator() {
        ProgramSelector[] programSelectors = ProgramSelector.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        assertWithMessage("Program selectors").that(programSelectors).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void writeToParcel_forProgramSelector() {
        ProgramSelector selectorExpected = getDabSelector(new ProgramSelector.Identifier[]{
                DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER}, /* vendorIds= */ null);
        Parcel parcel = Parcel.obtain();

        selectorExpected.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        ProgramSelector selectorFromParcel = ProgramSelector.CREATOR.createFromParcel(parcel);
        assertWithMessage("Program selector created from parcel")
                .that(selectorFromParcel).isEqualTo(selectorExpected);
    }

    private ProgramSelector getFmSelector(@Nullable ProgramSelector.Identifier[] secondaryIds,
            @Nullable long[] vendorIds) {
        return new ProgramSelector(FM_PROGRAM_TYPE, FM_IDENTIFIER, secondaryIds, vendorIds);
    }

    private ProgramSelector getDabSelector(@Nullable ProgramSelector.Identifier[] secondaryIds,
            @Nullable long[] vendorIds) {
        return new ProgramSelector(DAB_PROGRAM_TYPE, DAB_DMB_SID_EXT_IDENTIFIER_1, secondaryIds,
                vendorIds);
    }
}
