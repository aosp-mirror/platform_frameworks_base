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
package android.service.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.Log;
import android.view.autofill.AutofillValue;

import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

import java.util.Arrays;
import java.util.List;

/**
 * A service that calculates field classification scores.
 *
 * <p>A field classification score is a {@code float} representing how well an
 * {@link AutofillValue} filled matches a expected value predicted by an autofill service
 * &mdash;a full-match is {@code 1.0} (representing 100%), while a full mismatch is {@code 0.0}.
 *
 * <p>The exact score depends on the algorithm used to calculate it&mdash; the service must provide
 * at least one default algorithm (which is used when the algorithm is not specified or is invalid),
 * but it could provide more (in which case the algorithm name should be specifiied by the caller
 * when calculating the scores).
 *
 * {@hide}
 */
@SystemApi
public abstract class AutofillFieldClassificationService extends Service {

    private static final String TAG = "AutofillFieldClassificationService";

    private static final int MSG_GET_SCORES = 1;

    /**
     * The {@link Intent} action that must be declared as handled by a service
     * in its manifest for the system to recognize it as a quota providing service.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.autofill.AutofillFieldClassificationService";

    /** {@hide} **/
    public static final String EXTRA_SCORES = "scores";

    private AutofillFieldClassificationServiceWrapper mWrapper;

    private final HandlerCaller.Callback mHandlerCallback = (msg) -> {
        final int action = msg.what;
        final Bundle data = new Bundle();
        final RemoteCallback callback;
        switch (action) {
            case MSG_GET_SCORES:
                final SomeArgs args = (SomeArgs) msg.obj;
                callback = (RemoteCallback) args.arg1;
                final String algorithmName = (String) args.arg2;
                final Bundle algorithmArgs = (Bundle) args.arg3;
                @SuppressWarnings("unchecked")
                final List<AutofillValue> actualValues = ((List<AutofillValue>) args.arg4);
                @SuppressWarnings("unchecked")
                final String[] userDataValues = (String[]) args.arg5;
                final Scores scores = onGetScores(algorithmName, algorithmArgs, actualValues,
                        Arrays.asList(userDataValues));
                data.putParcelable(EXTRA_SCORES, scores);
                break;
            default:
                Log.w(TAG, "Handling unknown message: " + action);
                return;
        }
        callback.sendResult(data);
    };

    private final HandlerCaller mHandlerCaller = new HandlerCaller(null, Looper.getMainLooper(),
            mHandlerCallback, true);

    /** @hide */
    public AutofillFieldClassificationService() {

    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWrapper = new AutofillFieldClassificationServiceWrapper();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mWrapper;
    }

    /**
     * Calculates field classification scores in a batch.
     *
     * <p>See {@link AutofillFieldClassificationService} for more info about field classification
     * scores.
     *
     * @param algorithm name of the algorithm to be used to calculate the scores. If invalid, the
     * default algorithm will be used instead.
     * @param args optional arguments to be passed to the algorithm.
     * @param actualValues values entered by the user.
     * @param userDataValues values predicted from the user data.
     * @return the calculated scores and the algorithm used.
     *
     * {@hide}
     */
    @Nullable
    @SystemApi
    public Scores onGetScores(@Nullable String algorithm,
            @Nullable Bundle args, @NonNull List<AutofillValue> actualValues,
            @NonNull List<String> userDataValues) {
        throw new UnsupportedOperationException("Must be implemented by external service");
    }

    private final class AutofillFieldClassificationServiceWrapper
            extends IAutofillFieldClassificationService.Stub {
        @Override
        public void getScores(RemoteCallback callback, String algorithmName, Bundle algorithmArgs,
                List<AutofillValue> actualValues, String[] userDataValues)
                        throws RemoteException {
            // TODO(b/70939974): refactor to use PooledLambda
            mHandlerCaller.obtainMessageOOOOO(MSG_GET_SCORES, callback, algorithmName,
                    algorithmArgs, actualValues, userDataValues).sendToTarget();
        }
    }


    // TODO(b/70939974): it might be simpler to remove this class and return the float[][] directly,
    // ignoring the request if the algorithm name is invalid.
    /**
     * Represents field classification scores used in a batch calculation.
     *
     * {@hide}
     */
    @SystemApi
    public static final class Scores implements Parcelable {
        private final String mAlgorithmName;
        private final float[][] mScores;

        /* @hide */
        public Scores(String algorithmName, int size1, int size2) {
            mAlgorithmName = algorithmName;
            mScores = new float[size1][size2];
        }

        public Scores(Parcel parcel) {
            mAlgorithmName = parcel.readString();
            final int size1 = parcel.readInt();
            final int size2 = parcel.readInt();
            mScores = new float[size1][size2];
            for (int i = 0; i < size1; i++) {
                for (int j = 0; j < size2; j++) {
                    mScores[i][j] = parcel.readFloat();
                }
            }
        }

        /**
         * Gets the name of algorithm used to calculate the score.
         */
        @NonNull
        public String getAlgorithm() {
            return mAlgorithmName;
        }

        /**
         * Gets the resulting scores, with the 1st dimension representing actual values and the 2nd
         * dimension values from {@link UserData}.
         */
        @NonNull
        public float[][] getScores() {
            return mScores;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeString(mAlgorithmName);
            int size1 = mScores.length;
            int size2 = mScores[0].length;
            parcel.writeInt(size1);
            parcel.writeInt(size2);
            for (int i = 0; i < size1; i++) {
                for (int j = 0; j < size2; j++) {
                    parcel.writeFloat(mScores[i][j]);
                }
            }
        }

        public static final Creator<Scores> CREATOR = new Creator<Scores>() {

            @Override
            public Scores createFromParcel(Parcel parcel) {
                return new Scores(parcel);
            }

            @Override
            public Scores[] newArray(int size) {
                return new Scores[size];
            }

        };
    }
}
