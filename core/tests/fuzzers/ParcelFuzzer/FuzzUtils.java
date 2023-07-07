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
package parcelfuzzer;

import android.os.Parcel;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

import randomparcel.FuzzBinder;

public class FuzzUtils {
    public static FuzzOperation[] FUZZ_OPERATIONS =
            new FuzzOperation[] {
                new FuzzOperation() {
                    @java.lang.Override
                    public void doFuzz(FuzzedDataProvider provider) {
                        // Fuzz Append
                        int start = provider.consumeInt();
                        int len = provider.consumeInt();
                        Parcel p1 = null;
                        Parcel p2 = null;

                        try {
                            p1 = Parcel.obtain();
                            p2 = Parcel.obtain();

                            byte[] data =
                                    provider.consumeBytes(
                                            provider.consumeInt(0, provider.remainingBytes()));
                            FuzzBinder.fillRandomParcel(p1, data);
                            FuzzBinder.fillRandomParcel(p2, provider.consumeRemainingAsBytes());

                            p1.appendFrom(p2, start, len);

                        } catch (Exception e) {
                            // Rethrow exception as runtime exceptions are catched
                            // at highest level.
                            throw e;
                        } finally {
                            p1.recycle();
                            p2.recycle();
                        }
                    }
                },
                new FuzzOperation() {
                    @java.lang.Override
                    public void doFuzz(FuzzedDataProvider provider) {
                        // Fuzz Read
                        // Use maximum bytes to generate read instructions and remaining for parcel
                        // creation
                        int maxParcelBytes = provider.remainingBytes() / 3;
                        byte[] data = provider.consumeBytes(maxParcelBytes);
                        Parcel randomParcel = null;

                        try {
                            randomParcel = Parcel.obtain();
                            FuzzBinder.fillRandomParcel(randomParcel, data);

                            while (provider.remainingBytes() > 0) {
                                provider.pickValue(ReadUtils.READ_OPERATIONS)
                                        .readParcel(randomParcel, provider);
                            }

                        } catch (Exception e) {
                            // Rethrow exception as runtime exceptions are catched
                            // at highest level.
                            throw e;
                        } finally {
                            randomParcel.recycle();
                        }
                    }
                },
            };
}
