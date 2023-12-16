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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Parcel;
import android.os.RemoteException;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArraySet;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.verification.VerificationWithTimeout;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

@RunWith(MockitoJUnitRunner.class)
public final class ProgramListTest {

    private static final int TEST_TARGET_SDK_VERSION = Build.VERSION_CODES.CUR_DEVELOPMENT;

    private static final int CREATOR_ARRAY_SIZE = 3;
    private static final int TIMEOUT_MS = 500;
    private static final VerificationWithTimeout CALLBACK_TIMEOUT = timeout(TIMEOUT_MS);

    private static final boolean IS_PURGE = false;
    private static final boolean IS_COMPLETE = true;

    private static final boolean INCLUDE_CATEGORIES = true;
    private static final boolean EXCLUDE_MODIFICATIONS = false;

    private static final ProgramSelector.Identifier FM_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY,
                    /* value= */ 94300);
    private static final ProgramSelector.Identifier RDS_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_RDS_PI, 15019);
    private static final ProgramSelector.Identifier DAB_DMB_SID_EXT_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                    /* value= */ 0xA000000111L);
    private static final ProgramSelector.Identifier DAB_ENSEMBLE_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE,
                    /* value= */ 0x1013);
    private static final ProgramSelector.Identifier DAB_FREQUENCY_IDENTIFIER_1 =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 222_064);
    private static final ProgramSelector.Identifier DAB_FREQUENCY_IDENTIFIER_2 =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 220_352);

    private static final ProgramSelector DAB_SELECTOR_1 = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_DAB, DAB_DMB_SID_EXT_IDENTIFIER,
            new ProgramSelector.Identifier[]{DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER_1},
            /* vendorIds= */ null);
    private static final ProgramSelector DAB_SELECTOR_2 = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_DAB, DAB_DMB_SID_EXT_IDENTIFIER,
            new ProgramSelector.Identifier[]{DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER_2},
            /* vendorIds= */ null);

    private static final UniqueProgramIdentifier RDS_UNIQUE_IDENTIFIER =
            new UniqueProgramIdentifier(RDS_IDENTIFIER);
    private static final UniqueProgramIdentifier DAB_UNIQUE_IDENTIFIER_1 =
            new UniqueProgramIdentifier(DAB_SELECTOR_1);
    private static final UniqueProgramIdentifier DAB_UNIQUE_IDENTIFIER_2 =
            new UniqueProgramIdentifier(DAB_SELECTOR_2);

    private static final RadioManager.ProgramInfo FM_PROGRAM_INFO = createFmProgramInfo(
            createProgramSelector(ProgramSelector.PROGRAM_TYPE_FM, FM_IDENTIFIER));
    private static final RadioManager.ProgramInfo DAB_PROGRAM_INFO_1 = createDabProgramInfo(
            DAB_SELECTOR_1);
    private static final RadioManager.ProgramInfo DAB_PROGRAM_INFO_2 = createDabProgramInfo(
            DAB_SELECTOR_2);

    private static final Set<Integer> FILTER_IDENTIFIER_TYPES = Set.of(
            ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY,
            ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT);
    private static final Set<ProgramSelector.Identifier> FILTER_IDENTIFIERS = Set.of(
            FM_IDENTIFIER, DAB_DMB_SID_EXT_IDENTIFIER);

    private static final ProgramList.Chunk FM_DAB_ADD_CHUNK = new ProgramList.Chunk(IS_PURGE,
            IS_COMPLETE, Set.of(FM_PROGRAM_INFO, DAB_PROGRAM_INFO_1, DAB_PROGRAM_INFO_2),
            Set.of(RDS_UNIQUE_IDENTIFIER));
    private static final ProgramList.Chunk FM_ADD_INCOMPLETE_CHUNK = new ProgramList.Chunk(IS_PURGE,
            /* complete= */ false, Set.of(FM_PROGRAM_INFO), new ArraySet<>());
    private static final ProgramList.Filter TEST_FILTER = new ProgramList.Filter(
            FILTER_IDENTIFIER_TYPES, FILTER_IDENTIFIERS, INCLUDE_CATEGORIES, EXCLUDE_MODIFICATIONS);
    private static final Map<String, String> VENDOR_FILTER = Map.of("testVendorKey1",
            "testVendorValue1", "testVendorKey2", "testVendorValue2");

    private final Executor mExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };
    private final ApplicationInfo mApplicationInfo = new ApplicationInfo();

    private RadioTuner mRadioTuner;
    private ITunerCallback mTunerCallback;
    private ProgramList mProgramList;

    private ProgramList.ListCallback[] mListCallbackMocks;
    private ProgramList.OnCompleteListener[] mOnCompleteListenerMocks;
    @Mock
    private Context mContextMock;
    @Mock
    private IRadioService mRadioServiceMock;
    @Mock
    private ITuner mTunerMock;
    @Mock
    private RadioTuner.Callback mTunerCallbackMock;

    @Rule
    public final Expect mExpect = Expect.create();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void getIdentifierTypes_forFilter() {
        ProgramList.Filter filter = new ProgramList.Filter(FILTER_IDENTIFIER_TYPES,
                FILTER_IDENTIFIERS, INCLUDE_CATEGORIES, EXCLUDE_MODIFICATIONS);

        mExpect.withMessage("Filtered identifier types").that(filter.getIdentifierTypes())
                .containsExactlyElementsIn(FILTER_IDENTIFIER_TYPES);
    }

    @Test
    public void getIdentifiers_forFilter() {
        ProgramList.Filter filter = new ProgramList.Filter(FILTER_IDENTIFIER_TYPES,
                FILTER_IDENTIFIERS, INCLUDE_CATEGORIES, EXCLUDE_MODIFICATIONS);

        mExpect.withMessage("Filtered identifiers").that(filter.getIdentifiers())
                .containsExactlyElementsIn(FILTER_IDENTIFIERS);
    }

    @Test
    public void areCategoriesIncluded_forFilter() {
        ProgramList.Filter filter = new ProgramList.Filter(FILTER_IDENTIFIER_TYPES,
                FILTER_IDENTIFIERS, INCLUDE_CATEGORIES, EXCLUDE_MODIFICATIONS);

        mExpect.withMessage("Filter including categories")
                .that(filter.areCategoriesIncluded()).isEqualTo(INCLUDE_CATEGORIES);
    }

    @Test
    public void areModificationsExcluded_forFilter() {
        ProgramList.Filter filter = new ProgramList.Filter(FILTER_IDENTIFIER_TYPES,
                FILTER_IDENTIFIERS, INCLUDE_CATEGORIES, EXCLUDE_MODIFICATIONS);

        mExpect.withMessage("Filter excluding modifications")
                .that(filter.areModificationsExcluded()).isEqualTo(EXCLUDE_MODIFICATIONS);
    }

    @Test
    public void getVendorFilter_forFilterWithoutVendorFilter_returnsNull() {
        ProgramList.Filter filter = new ProgramList.Filter(FILTER_IDENTIFIER_TYPES,
                FILTER_IDENTIFIERS, INCLUDE_CATEGORIES, EXCLUDE_MODIFICATIONS);

        mExpect.withMessage("Filter vendor obtained from filter without vendor filter")
                .that(filter.getVendorFilter()).isNull();
    }

    @Test
    public void getVendorFilter_forFilterWithVendorFilter() {
        ProgramList.Filter vendorFilter = new ProgramList.Filter(VENDOR_FILTER);

        mExpect.withMessage("Filter vendor obtained from filter with vendor filter")
                .that(vendorFilter.getVendorFilter()).isEqualTo(VENDOR_FILTER);
    }

    @Test
    public void describeContents_forFilter() {
        mExpect.withMessage("Filter contents").that(TEST_FILTER.describeContents()).isEqualTo(0);
    }

    @Test
    public void hashCode_withTheSameFilters_equals() {
        ProgramList.Filter filterCompared = new ProgramList.Filter(FILTER_IDENTIFIER_TYPES,
                FILTER_IDENTIFIERS, INCLUDE_CATEGORIES, EXCLUDE_MODIFICATIONS);

        mExpect.withMessage("Hash code of the same filter")
                .that(filterCompared.hashCode()).isEqualTo(TEST_FILTER.hashCode());
    }

    @Test
    public void hashCode_withDifferentFilters_notEquals() {
        ProgramList.Filter filterCompared = new ProgramList.Filter();

        mExpect.withMessage("Hash code of the different filter")
                .that(filterCompared.hashCode()).isNotEqualTo(TEST_FILTER.hashCode());
    }

    @Test
    public void writeToParcel_forFilter() {
        Parcel parcel = Parcel.obtain();

        TEST_FILTER.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        ProgramList.Filter filterFromParcel =
                ProgramList.Filter.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Filter created from parcel")
                .that(filterFromParcel).isEqualTo(TEST_FILTER);
    }

    @Test
    public void newArray_forFilterCreator() {
        ProgramList.Filter[] filters = ProgramList.Filter.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Program filters").that(filters).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void isPurge_forChunk() {
        mExpect.withMessage("Puring chunk").that(FM_DAB_ADD_CHUNK.isPurge()).isEqualTo(IS_PURGE);
    }

    @Test
    public void isComplete_forChunk() {
        mExpect.withMessage("Complete chunk").that(FM_DAB_ADD_CHUNK.isComplete())
                .isEqualTo(IS_COMPLETE);
    }

    @Test
    public void getModified_forChunk() {
        mExpect.withMessage("Modified program info in chunk")
                .that(FM_DAB_ADD_CHUNK.getModified())
                .containsExactly(FM_PROGRAM_INFO, DAB_PROGRAM_INFO_1, DAB_PROGRAM_INFO_2);
    }

    @Test
    public void getRemoved_forChunk() {
        mExpect.withMessage("Removed program identifiers in chunk")
                .that(FM_DAB_ADD_CHUNK.getRemoved()).containsExactly(RDS_UNIQUE_IDENTIFIER);
    }

    @Test
    public void describeContents_forChunk() {
        mExpect.withMessage("Chunk contents").that(FM_DAB_ADD_CHUNK.describeContents())
                .isEqualTo(0);
    }

    @Test
    public void writeToParcel_forChunk() {
        Parcel parcel = Parcel.obtain();

        FM_DAB_ADD_CHUNK.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        ProgramList.Chunk chunkFromParcel =
                ProgramList.Chunk.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Chunk created from parcel")
                .that(chunkFromParcel).isEqualTo(FM_DAB_ADD_CHUNK);
    }

    @Test
    public void newArray_forChunkCreator() {
        ProgramList.Chunk[] chunks = ProgramList.Chunk.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Chunks").that(chunks).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void getProgramList_forTunerAdapterWhenListNotReady_fails() throws Exception {
        Map<String, String> parameters = Map.of("ParameterKeyMock", "ParameterValueMock");
        createRadioTuner();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mRadioTuner.getProgramList(parameters));

        mExpect.withMessage("Exception for getting program list when not ready")
                .that(thrown).hasMessageThat().contains("Program list is not ready yet");
    }

    @Test
    public void getProgramList_forTunerAdapterWhenServiceDied_fails() throws Exception {
        Map<String, String> parameters = Map.of("ParameterKeyMock", "ParameterValueMock");
        createRadioTuner();
        doThrow(new RemoteException()).when(mTunerMock).startProgramListUpdates(any());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> mRadioTuner.getProgramList(parameters));

        mExpect.withMessage("Exception for getting program list when service is dead")
                .that(thrown).hasMessageThat().contains("Service died");
    }

    @Test
    public void getDynamicProgramList_forTunerAdapter() throws Exception {
        createRadioTuner();

        mRadioTuner.getDynamicProgramList(TEST_FILTER);

        verify(mTunerMock).startProgramListUpdates(TEST_FILTER);
    }

    @Test
    public void getDynamicProgramList_forTunerNotSupportingProgramList_returnsNull()
            throws Exception {
        createRadioTuner();
        doThrow(new UnsupportedOperationException())
                .when(mTunerMock).startProgramListUpdates(any());

        ProgramList nullProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);

        mExpect.withMessage("Exception for radio HAL client not supporting program list")
                .that(nullProgramList).isNull();
    }

    @Test
    public void getDynamicProgramList_forTunerAdapterWithServiceDied_throwsException()
            throws Exception {
        createRadioTuner();
        doThrow(new RemoteException()).when(mTunerMock).startProgramListUpdates(any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            mRadioTuner.getDynamicProgramList(TEST_FILTER);
        });

        mExpect.withMessage("Exception for radio HAL client service died")
                .that(thrown).hasMessageThat().contains("Service died");
    }

    @Test
    public void onProgramListUpdated_withNewIdsAdded_invokesCallbacks() throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(/* numCallbacks= */ 1);
        addOnCompleteListeners(/* numListeners= */ 1);

        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);

        verify(mListCallbackMocks[0], CALLBACK_TIMEOUT).onItemChanged(FM_IDENTIFIER);
        verify(mListCallbackMocks[0], CALLBACK_TIMEOUT).onItemChanged(DAB_DMB_SID_EXT_IDENTIFIER);
        verify(mOnCompleteListenerMocks[0], CALLBACK_TIMEOUT).onComplete();
        mExpect.withMessage("Program info in program list after adding FM and DAB info")
                .that(mProgramList.toList()).containsExactly(FM_PROGRAM_INFO, DAB_PROGRAM_INFO_1,
                        DAB_PROGRAM_INFO_2);
    }

    @Test
    public void onProgramListUpdated_withFmIdsRemoved_invokesCallbacks() throws Exception {
        UniqueProgramIdentifier fmUniqueId = new UniqueProgramIdentifier(FM_IDENTIFIER);
        ProgramList.Chunk fmRemovedChunk = new ProgramList.Chunk(/* purge= */ false,
                /* complete= */ false, new ArraySet<>(), Set.of(fmUniqueId));
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(/* numCallbacks= */ 1);
        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);

        mTunerCallback.onProgramListUpdated(fmRemovedChunk);

        verify(mListCallbackMocks[0], CALLBACK_TIMEOUT).onItemRemoved(FM_IDENTIFIER);
        mExpect.withMessage("Program info in program list after removing FM id")
                .that(mProgramList.toList()).containsExactly(DAB_PROGRAM_INFO_1,
                        DAB_PROGRAM_INFO_2);
    }

    @Test
    public void onProgramListUpdated_withPartOfDabIdsRemoved_doesNotInvokeCallbacks()
            throws Exception {
        ProgramList.Chunk dabRemovedChunk1 = new ProgramList.Chunk(/* purge= */ false,
                /* complete= */ false, new ArraySet<>(), Set.of(DAB_UNIQUE_IDENTIFIER_1));
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(/* numCallbacks= */ 1);
        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);

        mTunerCallback.onProgramListUpdated(dabRemovedChunk1);

        verify(mListCallbackMocks[0], after(TIMEOUT_MS).never()).onItemRemoved(
                DAB_DMB_SID_EXT_IDENTIFIER);
        mExpect.withMessage("Program info in program list after removing part of DAB ids")
                .that(mProgramList.toList()).containsExactly(FM_PROGRAM_INFO, DAB_PROGRAM_INFO_2);
    }

    @Test
    public void onProgramListUpdated_withAllDabIdsRemoved_invokesCallbacks()
            throws Exception {
        ProgramList.Chunk dabRemovedChunk1 = new ProgramList.Chunk(/* purge= */ false,
                /* complete= */ false, new ArraySet<>(), Set.of(DAB_UNIQUE_IDENTIFIER_1));
        ProgramList.Chunk dabRemovedChunk2 = new ProgramList.Chunk(/* purge= */ false,
                /* complete= */ false, new ArraySet<>(), Set.of(DAB_UNIQUE_IDENTIFIER_2));
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(/* numCallbacks= */ 1);
        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);
        mTunerCallback.onProgramListUpdated(dabRemovedChunk1);
        verify(mListCallbackMocks[0], after(TIMEOUT_MS).never()).onItemRemoved(
                DAB_DMB_SID_EXT_IDENTIFIER);

        mTunerCallback.onProgramListUpdated(dabRemovedChunk2);

        verify(mListCallbackMocks[0], CALLBACK_TIMEOUT).onItemRemoved(DAB_DMB_SID_EXT_IDENTIFIER);
        mExpect.withMessage("Program info in program list after removing all DAB ids")
                .that(mProgramList.toList()).containsExactly(FM_PROGRAM_INFO);
    }

    @Test
    public void onProgramListUpdated_withIncompleteChunk_notInvokesOnCompleteListener()
            throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        addOnCompleteListeners(/* numListeners= */ 1);

        mTunerCallback.onProgramListUpdated(FM_ADD_INCOMPLETE_CHUNK);

        verify(mOnCompleteListenerMocks[0], after(TIMEOUT_MS).never()).onComplete();
    }

    @Test
    public void onProgramListUpdated_withPurgeChunk() throws Exception {
        ProgramList.Chunk purgeChunk = new ProgramList.Chunk(/* purge= */ true,
                /* complete= */ true, new ArraySet<>(), new ArraySet<>());
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(/* numCallbacks= */ 1);
        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);

        mTunerCallback.onProgramListUpdated(purgeChunk);

        verify(mListCallbackMocks[0], CALLBACK_TIMEOUT).onItemRemoved(FM_IDENTIFIER);
        verify(mListCallbackMocks[0], CALLBACK_TIMEOUT).onItemRemoved(DAB_DMB_SID_EXT_IDENTIFIER);
        mExpect.withMessage("Program list after purge chunk applied")
                .that(mProgramList.toList()).isEmpty();
    }

    @Test
    public void onProgramListUpdated_afterProgramListClosed_notInvokeCallbacks()
            throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(/* numCallbacks= */ 1);
        addOnCompleteListeners(/* numListeners= */ 1);
        mProgramList.close();

        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);

        verify(mListCallbackMocks[0], after(TIMEOUT_MS).never()).onItemChanged(any());
        verify(mListCallbackMocks[0], never()).onItemChanged(any());
        verify(mOnCompleteListenerMocks[0], never()).onComplete();
    }

    @Test
    public void onItemChanged_forListCallbackRegisteredWithExecutor_invokesWhenIdAdded()
            throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        ProgramList.ListCallback listCallbackMock = mock(ProgramList.ListCallback.class);
        mProgramList.registerListCallback(mExecutor, listCallbackMock);

        mTunerCallback.onProgramListUpdated(FM_ADD_INCOMPLETE_CHUNK);

        verify(listCallbackMock, CALLBACK_TIMEOUT).onItemChanged(FM_IDENTIFIER);
    }

    @Test
    public void onItemRemoved_forListCallbackRegisteredWithExecutor_invokesWhenIdRemoved()
            throws Exception {
        ProgramList.Chunk purgeChunk = new ProgramList.Chunk(/* purge= */ true,
                /* complete= */ true, new ArraySet<>(), new ArraySet<>());
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        ProgramList.ListCallback listCallbackMock = mock(ProgramList.ListCallback.class);
        mProgramList.registerListCallback(mExecutor, listCallbackMock);
        mTunerCallback.onProgramListUpdated(FM_ADD_INCOMPLETE_CHUNK);

        mTunerCallback.onProgramListUpdated(purgeChunk);

        verify(listCallbackMock, CALLBACK_TIMEOUT).onItemRemoved(FM_IDENTIFIER);
    }

    @Test
    public void onProgramListUpdated_withMultipleListCallBacks() throws Exception {
        int numCallbacks = 3;
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(numCallbacks);

        mTunerCallback.onProgramListUpdated(FM_ADD_INCOMPLETE_CHUNK);

        for (int index = 0; index < numCallbacks; index++) {
            verify(mListCallbackMocks[index], CALLBACK_TIMEOUT).onItemChanged(FM_IDENTIFIER);
        }
    }

    @Test
    public void onBackgroundScanComplete_whenProgramListReady_invokesMockedCallback()
            throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);

        mTunerCallback.onBackgroundScanComplete();

        verify(mTunerCallbackMock, CALLBACK_TIMEOUT).onBackgroundScanComplete();
    }

    @Test
    public void onBackgroundScanComplete_whenProgramListNotReady_notInvokeMockedCallback()
            throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);

        mTunerCallback.onBackgroundScanComplete();

        verify(mTunerCallbackMock, after(TIMEOUT_MS).never()).onBackgroundScanComplete();
    }

    @Test
    public void onBackgroundScanComplete_afterProgramListReady_invokesMockedCallback()
            throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);

        mTunerCallback.onBackgroundScanComplete();
        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);

        verify(mTunerCallbackMock, CALLBACK_TIMEOUT).onBackgroundScanComplete();
    }

    @Test
    public void unregisterListCallback_withProgramUpdated_notInvokesCallback() throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(/* numCallbacks= */ 1);

        mProgramList.unregisterListCallback(mListCallbackMocks[0]);
        mTunerCallback.onProgramListUpdated(FM_ADD_INCOMPLETE_CHUNK);

        verify(mListCallbackMocks[0], after(TIMEOUT_MS).never()).onItemChanged(any());
    }

    @Test
    public void addOnCompleteListener_withExecutor() throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        ProgramList.OnCompleteListener onCompleteListenerMock =
                mock(ProgramList.OnCompleteListener.class);

        mProgramList.addOnCompleteListener(mExecutor, onCompleteListenerMock);
        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);

        verify(onCompleteListenerMock, CALLBACK_TIMEOUT).onComplete();
    }

    @Test
    public void onProgramListUpdated_withMultipleOnCompleteListeners() throws Exception {
        int numListeners = 3;
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        addOnCompleteListeners(numListeners);

        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);

        for (int index = 0; index < numListeners; index++) {
            verify(mOnCompleteListenerMocks[index], CALLBACK_TIMEOUT).onComplete();
        }
    }

    @Test
    public void removeOnCompleteListener_withProgramUpdated_notInvokesListener() throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        addOnCompleteListeners(/* numListeners= */ 1);

        mProgramList.removeOnCompleteListener(mOnCompleteListenerMocks[0]);
        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);

        verify(mOnCompleteListenerMocks[0], after(TIMEOUT_MS).never()).onComplete();
    }

    @Test
    public void close_forProgramList_invokesStopProgramListUpdates() throws Exception {
        createRadioTuner();
        ProgramList programList = mRadioTuner.getDynamicProgramList(TEST_FILTER);

        programList.close();

        verify(mTunerMock, CALLBACK_TIMEOUT).stopProgramListUpdates();
    }

    @Test
    public void get() throws Exception {
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(/* numCallbacks= */ 1);
        mTunerCallback.onProgramListUpdated(FM_ADD_INCOMPLETE_CHUNK);
        verify(mListCallbackMocks[0], CALLBACK_TIMEOUT).onItemChanged(FM_IDENTIFIER);

        mExpect.withMessage(
                "FM program info in program list after updating with chunk of FM program")
                .that(mProgramList.get(FM_IDENTIFIER)).isEqualTo(FM_PROGRAM_INFO);
    }

    @Test
    public void getProgramInfos() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(/* numCallbacks= */ 1);
        mTunerCallback.onProgramListUpdated(FM_DAB_ADD_CHUNK);
        verify(mListCallbackMocks[0], CALLBACK_TIMEOUT).onItemChanged(FM_IDENTIFIER);
        verify(mListCallbackMocks[0], CALLBACK_TIMEOUT).onItemChanged(DAB_DMB_SID_EXT_IDENTIFIER);

        mExpect.withMessage("FM program info in program list")
                .that(mProgramList.getProgramInfos(FM_IDENTIFIER)).containsExactly(FM_PROGRAM_INFO);
        mExpect.withMessage("All DAB program info in program list")
                .that(mProgramList.getProgramInfos(DAB_DMB_SID_EXT_IDENTIFIER))
                .containsExactly(DAB_PROGRAM_INFO_1, DAB_PROGRAM_INFO_2);
    }

    @Test
    public void getProgramInfos_withIdNotFound() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        createRadioTuner();
        mProgramList = mRadioTuner.getDynamicProgramList(TEST_FILTER);
        registerListCallbacks(/* numCallbacks= */ 1);
        mTunerCallback.onProgramListUpdated(FM_ADD_INCOMPLETE_CHUNK);
        verify(mListCallbackMocks[0], CALLBACK_TIMEOUT).onItemChanged(FM_IDENTIFIER);

        mExpect.withMessage("DAB program info in program list")
                .that(mProgramList.getProgramInfos(DAB_DMB_SID_EXT_IDENTIFIER)).isEmpty();
    }

    private static ProgramSelector createProgramSelector(int programType,
            ProgramSelector.Identifier identifier) {
        return new ProgramSelector(programType, identifier, /* secondaryIds= */ null,
                /* vendorIds= */ null);
    }

    private static RadioManager.ProgramInfo createFmProgramInfo(ProgramSelector selector) {
        return new RadioManager.ProgramInfo(selector, selector.getPrimaryId(),
                selector.getPrimaryId(), /* relatedContents= */ null, /* infoFlags= */ 0,
                /* signalQuality= */ 1, new RadioMetadata.Builder().build(),
                /* vendorInfo= */ null);
    }

    private static RadioManager.ProgramInfo createDabProgramInfo(ProgramSelector selector) {
        return new RadioManager.ProgramInfo(selector, selector.getPrimaryId(),
                DAB_ENSEMBLE_IDENTIFIER, /* relatedContents= */ null, /* infoFlags= */ 0,
                /* signalQuality= */ 1, new RadioMetadata.Builder().build(),
                /* vendorInfo= */ null);
    }

    private void createRadioTuner() throws Exception {
        mApplicationInfo.targetSdkVersion = TEST_TARGET_SDK_VERSION;
        when(mContextMock.getApplicationInfo()).thenReturn(mApplicationInfo);
        RadioManager radioManager = new RadioManager(mContextMock, mRadioServiceMock);

        RadioManager.BandConfig band = new RadioManager.FmBandConfig(
                new RadioManager.FmBandDescriptor(RadioManager.REGION_ITU_1, RadioManager.BAND_FM,
                        /* lowerLimit= */ 87500, /* upperLimit= */ 108000, /* spacing= */ 200,
                        /* stereo= */ true, /* rds= */ false, /* ta= */ false, /* af= */ false,
                        /* es= */ false));

        doAnswer(invocation -> {
            mTunerCallback = (ITunerCallback) invocation.getArguments()[3];
            return mTunerMock;
        }).when(mRadioServiceMock).openTuner(anyInt(), any(), anyBoolean(), any());

        mRadioTuner = radioManager.openTuner(/* moduleId= */ 0, band,
                /* withAudio= */ true, mTunerCallbackMock, /* handler= */ null);
    }

    private void registerListCallbacks(int numCallbacks) {
        mListCallbackMocks = new ProgramList.ListCallback[numCallbacks];
        for (int index = 0; index < numCallbacks; index++) {
            mListCallbackMocks[index] = mock(ProgramList.ListCallback.class);
            mProgramList.registerListCallback(mListCallbackMocks[index]);
        }
    }

    private void addOnCompleteListeners(int numListeners) {
        mOnCompleteListenerMocks = new ProgramList.OnCompleteListener[numListeners];
        for (int index = 0; index < numListeners; index++) {
            mOnCompleteListenerMocks[index] = mock(ProgramList.OnCompleteListener.class);
            mProgramList.addOnCompleteListener(mOnCompleteListenerMocks[index]);
        }
    }
}
