/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.pm;

import android.annotation.NonNull;

import com.android.internal.util.Preconditions;

import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

abstract class ShortcutPackageItem {
    private final int mPackageUserId;
    private final String mPackageName;

    private ShortcutPackageInfo mPackageInfo;

    protected ShortcutPackageItem(int packageUserId, @NonNull String packageName,
            @NonNull ShortcutPackageInfo packageInfo) {
        mPackageUserId = packageUserId;
        mPackageName = Preconditions.checkStringNotEmpty(packageName);
        mPackageInfo = Preconditions.checkNotNull(packageInfo);
    }

    /**
     * ID of the user who actually has this package running on.  For {@link ShortcutPackage},
     * this is the same thing as {@link #getOwnerUserId}, but if it's a {@link ShortcutLauncher} and
     * {@link #getOwnerUserId} is of a work profile, then this ID could be the user who owns the
     * profile.
     */
    public int getPackageUserId() {
        return mPackageUserId;
    }

    /**
     * ID of the user who sees the shortcuts from this instance.
     */
    public abstract int getOwnerUserId();

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    public ShortcutPackageInfo getPackageInfo() {
        return mPackageInfo;
    }

    /**
     * Should be only used when loading from a file.o
     */
    protected void replacePackageInfo(@NonNull ShortcutPackageInfo packageInfo) {
        mPackageInfo = Preconditions.checkNotNull(packageInfo);
    }

    public void refreshPackageInfoAndSave(ShortcutService s) {
        mPackageInfo.refresh(s, this);
        s.scheduleSaveUser(getOwnerUserId());
    }

    public void ensureNotShadowAndSave(ShortcutService s) {
        if (mPackageInfo.isShadow()) {
            mPackageInfo.setShadow(false);
            s.scheduleSaveUser(getOwnerUserId());
        }
    }

    public abstract void saveToXml(@NonNull XmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException;
}
