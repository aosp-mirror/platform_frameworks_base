/*
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
package com.android.internal.app;

import static com.google.common.truth.Truth.assertThat;

import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetId;
import android.os.UserHandle;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
public class ResolverAppPredictorCallbackTest {
    private class Callback implements Consumer<List<AppTarget>> {
        public int count = 0;
        public List<AppTarget> latest = null;
        @Override
        public void accept(List<AppTarget> appTargets) {
            count++;
            latest = appTargets;
        }
    };

    @Test
    public void testAsConsumer() {
        Callback callback = new Callback();
        ResolverAppPredictorCallback wrapped = new ResolverAppPredictorCallback(callback);
        assertThat(callback.count).isEqualTo(0);

        List<AppTarget> targets = createAppTargetList();
        wrapped.asConsumer().accept(targets);

        assertThat(callback.count).isEqualTo(1);
        assertThat(callback.latest).isEqualTo(targets);

        wrapped.destroy();

        // Shouldn't do anything:
        wrapped.asConsumer().accept(targets);

        assertThat(callback.count).isEqualTo(1);
    }

    @Test
    public void testAsCallback() {
        Callback callback = new Callback();
        ResolverAppPredictorCallback wrapped = new ResolverAppPredictorCallback(callback);
        assertThat(callback.count).isEqualTo(0);

        List<AppTarget> targets = createAppTargetList();
        wrapped.asCallback().onTargetsAvailable(targets);

        assertThat(callback.count).isEqualTo(1);
        assertThat(callback.latest).isEqualTo(targets);

        wrapped.destroy();

        // Shouldn't do anything:
        wrapped.asConsumer().accept(targets);

        assertThat(callback.count).isEqualTo(1);
    }

    @Test
    public void testAsConsumer_null() {
        Callback callback = new Callback();
        ResolverAppPredictorCallback wrapped = new ResolverAppPredictorCallback(callback);
        assertThat(callback.count).isEqualTo(0);

        wrapped.asConsumer().accept(null);

        assertThat(callback.count).isEqualTo(1);
        assertThat(callback.latest).isEmpty();

        wrapped.destroy();

        // Shouldn't do anything:
        wrapped.asConsumer().accept(null);

        assertThat(callback.count).isEqualTo(1);
    }

    private List<AppTarget> createAppTargetList() {
        AppTarget.Builder builder = new AppTarget.Builder(
                new AppTargetId("ID"), "package", UserHandle.CURRENT);
        return List.of(builder.build());
    }
}
