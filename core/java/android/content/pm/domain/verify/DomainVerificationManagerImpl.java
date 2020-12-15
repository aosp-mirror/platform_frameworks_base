/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm.domain.verify;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * @hide
 */
@SuppressWarnings("RedundantThrows")
public class DomainVerificationManagerImpl implements DomainVerificationManager {

    public static final int ERROR_INVALID_DOMAIN_SET = 1;
    public static final int ERROR_NAME_NOT_FOUND = 2;

    @IntDef(prefix = { "ERROR_" }, value = {
            ERROR_INVALID_DOMAIN_SET,
            ERROR_NAME_NOT_FOUND,
    })
    private @interface Error {
    }

    private final Context mContext;

    private final IDomainVerificationManager mDomainVerificationManager;

    public DomainVerificationManagerImpl(Context context,
            IDomainVerificationManager domainVerificationManager) {
        mContext = context;
        mDomainVerificationManager = domainVerificationManager;
    }

    @NonNull
    @Override
    public List<String> getValidVerificationPackageNames() {
        try {
            return mDomainVerificationManager.getValidVerificationPackageNames();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Nullable
    @Override
    public DomainVerificationSet getDomainVerificationSet(@NonNull String packageName)
            throws NameNotFoundException {
        try {
            return mDomainVerificationManager.getDomainVerificationSet(packageName);
        } catch (Exception e) {
            Exception converted = rethrow(e, packageName);
            if (converted instanceof NameNotFoundException) {
                throw (NameNotFoundException) converted;
            } else if (converted instanceof RuntimeException) {
                throw (RuntimeException) converted;
            } else {
                throw new RuntimeException(converted);
            }
        }
    }

    @Override
    public void setDomainVerificationStatus(@NonNull UUID domainSetId, @NonNull Set<String> domains,
            int state) throws InvalidDomainSetException, NameNotFoundException {
        try {
            mDomainVerificationManager.setDomainVerificationStatus(domainSetId.toString(),
                    new ArrayList<>(domains), state);
        } catch (Exception e) {
            Exception converted = rethrow(e, domainSetId);
            if (converted instanceof NameNotFoundException) {
                throw (NameNotFoundException) converted;
            } else if (converted instanceof RuntimeException) {
                throw (RuntimeException) converted;
            } else {
                throw new RuntimeException(converted);
            }
        }
    }

    @Override
    public void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName,
            boolean allowed) throws NameNotFoundException {
        try {
            mDomainVerificationManager.setDomainVerificationLinkHandlingAllowed(packageName,
                    allowed, mContext.getUserId());
        } catch (Exception e) {
            Exception converted = rethrow(e, packageName);
            if (converted instanceof NameNotFoundException) {
                throw (NameNotFoundException) converted;
            } else if (converted instanceof RuntimeException) {
                throw (RuntimeException) converted;
            } else {
                throw new RuntimeException(converted);
            }
        }
    }

    @Override
    public void setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled)
            throws InvalidDomainSetException, NameNotFoundException {
        try {
            mDomainVerificationManager.setDomainVerificationUserSelection(domainSetId.toString(),
                    new ArrayList<>(domains), enabled, mContext.getUserId());
        } catch (Exception e) {
            Exception converted = rethrow(e, domainSetId);
            if (converted instanceof NameNotFoundException) {
                throw (NameNotFoundException) converted;
            } else if (converted instanceof RuntimeException) {
                throw (RuntimeException) converted;
            } else {
                throw new RuntimeException(converted);
            }
        }
    }

    @Nullable
    @Override
    public DomainVerificationUserSelection getDomainVerificationUserSelection(
            @NonNull String packageName) throws NameNotFoundException {
        try {
            return mDomainVerificationManager.getDomainVerificationUserSelection(packageName,
                    mContext.getUserId());
        } catch (Exception e) {
            Exception converted = rethrow(e, packageName);
            if (converted instanceof NameNotFoundException) {
                throw (NameNotFoundException) converted;
            } else if (converted instanceof RuntimeException) {
                throw (RuntimeException) converted;
            } else {
                throw new RuntimeException(converted);
            }
        }
    }

    private Exception rethrow(Exception exception, @Nullable UUID domainSetId) {
        return rethrow(exception, domainSetId, null);
    }

    private Exception rethrow(Exception exception, @Nullable String packageName) {
        return rethrow(exception, null, packageName);
    }

    private Exception rethrow(Exception exception, @Nullable UUID domainSetId,
            @Nullable String packageName) {
        if (exception instanceof ServiceSpecificException) {
            int packedErrorCode = ((ServiceSpecificException) exception).errorCode;
            if (packageName == null) {
                packageName = exception.getMessage();
            }

            @Error int managerErrorCode = packedErrorCode & 0xFFFF;
            switch (managerErrorCode) {
                case ERROR_INVALID_DOMAIN_SET:
                    int errorSpecificCode = packedErrorCode >> 16;
                    return new InvalidDomainSetException(domainSetId, packageName,
                            errorSpecificCode);
                case ERROR_NAME_NOT_FOUND:
                    return new NameNotFoundException(packageName);
                default:
                    return exception;
            }
        } else if (exception instanceof RemoteException) {
            return ((RemoteException) exception).rethrowFromSystemServer();
        } else {
            return exception;
        }
    }
}
