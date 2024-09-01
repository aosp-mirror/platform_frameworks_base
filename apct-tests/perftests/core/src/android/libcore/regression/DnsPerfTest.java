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

package android.libcore.regression;

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.net.UnknownHostException;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class DnsPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    @Test
    public void timeDns() throws Exception {
        String[] hosts = new String[] {
                "www.amazon.com",
                "z-ecx.images-amazon.com",
                "g-ecx.images-amazon.com",
                "ecx.images-amazon.com",
                "ad.doubleclick.com",
                "bpx.a9.com",
                "d3dtik4dz1nej0.cloudfront.net",
                "uac.advertising.com",
                "servedby.advertising.com",
                "view.atdmt.com",
                "rmd.atdmt.com",
                "spe.atdmt.com",
                "www.google.com",
                "www.cnn.com",
                "bad.host.mtv.corp.google.com",
        };
        final BenchmarkState state = mBenchmarkRule.getState();
        int i = 0;
        while (state.keepRunning()) {
            try {
                InetAddress.getByName(hosts[++i % hosts.length]);
            } catch (UnknownHostException ex) {
            }
        }
    }
}
