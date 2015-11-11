/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.net.config;

import android.util.Pair;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.Set;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManagerFactorySpi;

import com.android.internal.annotations.VisibleForTesting;

/** @hide */
public class RootTrustManagerFactorySpi extends TrustManagerFactorySpi {
    private ApplicationConfig mApplicationConfig;
    private NetworkSecurityConfig mConfig;

    @Override
    public void engineInit(ManagerFactoryParameters spec)
            throws InvalidAlgorithmParameterException {
        if (!(spec instanceof ApplicationConfigParameters)) {
            throw new InvalidAlgorithmParameterException("Unsupported spec: " +  spec + ". Only "
                    + ApplicationConfigParameters.class.getName() + " supported");

        }
        mApplicationConfig = ((ApplicationConfigParameters) spec).config;
    }

    @Override
    public void engineInit(KeyStore ks) throws KeyStoreException {
        if (ks != null) {
            mApplicationConfig = new ApplicationConfig(new KeyStoreConfigSource(ks));
        } else {
            mApplicationConfig = ApplicationConfig.getDefaultInstance();
        }
    }

    @Override
    public TrustManager[] engineGetTrustManagers() {
        if (mApplicationConfig == null) {
            throw new IllegalStateException("TrustManagerFactory not initialized");
        }
        return new TrustManager[] { mApplicationConfig.getTrustManager() };
    }

    @VisibleForTesting
    public static final class ApplicationConfigParameters implements ManagerFactoryParameters {
        public final ApplicationConfig config;
        public ApplicationConfigParameters(ApplicationConfig config) {
            this.config = config;
        }
    }
}
