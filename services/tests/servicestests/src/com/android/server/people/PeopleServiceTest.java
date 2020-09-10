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

package com.android.server.people;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTarget;
import android.app.prediction.IPredictionCallback;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@RunWith(JUnit4.class)
public final class PeopleServiceTest {

    private static final String APP_PREDICTION_SHARE_UI_SURFACE = "share";
    private static final int APP_PREDICTION_TARGET_COUNT = 4;
    private static final String TEST_PACKAGE_NAME = "com.example";
    private static final int USER_ID = 0;

    private PeopleServiceInternal mServiceInternal;
    private PeopleService.LocalService mLocalService;
    private AppPredictionSessionId mSessionId;
    private AppPredictionContext mPredictionContext;

    @Mock private Context mContext;
    @Mock private IPredictionCallback mCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(mCallback.asBinder()).thenReturn(new Binder());

        PeopleService service = new PeopleService(mContext);
        service.onStart();

        mServiceInternal = LocalServices.getService(PeopleServiceInternal.class);
        mLocalService = (PeopleService.LocalService) mServiceInternal;

        mSessionId = new AppPredictionSessionId("abc", USER_ID);
        mPredictionContext = new AppPredictionContext.Builder(mContext)
                .setUiSurface(APP_PREDICTION_SHARE_UI_SURFACE)
                .setPredictedTargetCount(APP_PREDICTION_TARGET_COUNT)
                .setExtras(new Bundle())
                .build();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(PeopleServiceInternal.class);
    }

    @Test
    public void testRegisterCallbacks() throws RemoteException {
        mServiceInternal.onCreatePredictionSession(mPredictionContext, mSessionId);

        SessionInfo sessionInfo = mLocalService.getSessionInfo(mSessionId);

        mServiceInternal.registerPredictionUpdates(mSessionId, mCallback);

        Consumer<List<AppTarget>> updatePredictionMethod =
                sessionInfo.getPredictor().getUpdatePredictionsMethod();
        updatePredictionMethod.accept(new ArrayList<>());
        updatePredictionMethod.accept(new ArrayList<>());

        verify(mCallback, times(2)).onResult(any(ParceledListSlice.class));

        mServiceInternal.unregisterPredictionUpdates(mSessionId, mCallback);

        updatePredictionMethod.accept(new ArrayList<>());

        // After the un-registration, the callback should no longer be called.
        verify(mCallback, times(2)).onResult(any(ParceledListSlice.class));

        mServiceInternal.onDestroyPredictionSession(mSessionId);
    }
}
