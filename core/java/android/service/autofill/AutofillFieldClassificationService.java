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

    /**
     * Manifest metadata key for the resource string containing the name of the default field
     * classification algorithm.
     */
    public static final String SERVICE_META_DATA_KEY_DEFAULT_ALGORITHM =
            "android.autofill.field_classification.default_algorithm";
    /**
     * Manifest metadata key for the resource string array containing the names of all field
     * classification algorithms provided by the service.
     */
    public static final String SERVICE_META_DATA_KEY_AVAILABLE_ALGORITHMS =
            "android.autofill.field_classification.available_algorithms";


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
                final float[][] scores = onGetScores(algorithmName, algorithmArgs, actualValues,
                        Arrays.asList(userDataValues));
                if (scores != null) {
                    data.putParcelable(EXTRA_SCORES, new Scores(scores));
                }
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
     * @return the calculated scores, with the first dimension representing actual values and the
     * second dimension values from {@link UserData}.
     *
     * {@hide}
     */
    @Nullable
    @SystemApi
    public float[][] onGetScores(@Nullable String algorithm,
            @Nullable Bundle args, @NonNull List<AutofillValue> actualValues,
            @NonNull List<String> userDataValues) {
        Log.e(TAG, "service implementation (" + getClass() + " does not implement onGetScore()");
        return null;
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

    /**
     * Helper class used to encapsulate a float[][] in a Parcelable.
     *
     * {@hide}
     */
    public static final class Scores implements Parcelable {
        public final float[][] scores;

        private Scores(Parcel parcel) {
            final int size1 = parcel.readInt();
            final int size2 = parcel.readInt();
            scores = new float[size1][size2];
            for (int i = 0; i < size1; i++) {
                for (int j = 0; j < size2; j++) {
                    scores[i][j] = parcel.readFloat();
                }
            }
        }

        private Scores(float[][] scores) {
            this.scores = scores;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            int size1 = scores.length;
            int size2 = scores[0].length;
            parcel.writeInt(size1);
            parcel.writeInt(size2);
            for (int i = 0; i < size1; i++) {
                for (int j = 0; j < size2; j++) {
                    parcel.writeFloat(scores[i][j]);
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
