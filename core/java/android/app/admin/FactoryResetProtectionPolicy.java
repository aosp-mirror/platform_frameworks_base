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

package android.app.admin;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.TEXT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The factory reset protection policy determines which accounts can unlock a device that
 * has gone through untrusted factory reset.
 * <p>
 * Only a device owner or profile owner of an organization-owned device can set a factory
 * reset protection policy for the device by calling the {@code DevicePolicyManager} method
 * {@link DevicePolicyManager#setFactoryResetProtectionPolicy(ComponentName,
 * FactoryResetProtectionPolicy)}}.
 * <p>
 * Normally factory reset protection does not kick in if the device is factory reset via Settings.
 * This is also the case when a device owner sets factory reset protection policy. However,
 * when a profile owner of an organization-owned device sets factory reset protection policy that
 * locks the device to specific accounts, the policy will take effect even if factory reset is
 * performed from Settings.
 *
 * @see DevicePolicyManager#setFactoryResetProtectionPolicy
 * @see DevicePolicyManager#getFactoryResetProtectionPolicy
 */
public final class FactoryResetProtectionPolicy implements Parcelable {

    private static final String LOG_TAG = "FactoryResetProtectionPolicy";

    private static final String KEY_FACTORY_RESET_PROTECTION_ACCOUNT =
            "factory_reset_protection_account";
    private static final String KEY_FACTORY_RESET_PROTECTION_ENABLED =
            "factory_reset_protection_enabled";
    private static final String ATTR_VALUE = "value";

    private final List<String> mFactoryResetProtectionAccounts;
    private final boolean mFactoryResetProtectionEnabled;

    private FactoryResetProtectionPolicy(List<String> factoryResetProtectionAccounts,
            boolean factoryResetProtectionEnabled) {
        mFactoryResetProtectionAccounts = factoryResetProtectionAccounts;
        mFactoryResetProtectionEnabled = factoryResetProtectionEnabled;
    }

    /**
     * Get the list of accounts that can provision a device which has been factory reset.
     */
    public @NonNull List<String> getFactoryResetProtectionAccounts() {
        return mFactoryResetProtectionAccounts;
    }

    /**
     * Return whether factory reset protection for the device is enabled or not.
     */
    public boolean isFactoryResetProtectionEnabled() {
        return mFactoryResetProtectionEnabled;
    }

    /**
     * Builder class for {@link FactoryResetProtectionPolicy} objects.
     */
    public static class Builder {
        private List<String> mFactoryResetProtectionAccounts;
        private boolean mFactoryResetProtectionEnabled;

        /**
         * Initialize a new Builder to construct a {@link FactoryResetProtectionPolicy}.
         */
        public Builder() {
            mFactoryResetProtectionEnabled = true;
        };

        /**
         * Sets which accounts can unlock a device that has been factory reset.
         * <p>
         * Once set, the consumer unlock flow will be disabled and only accounts in this list
         * can unlock factory reset protection after untrusted factory reset.
         * <p>
         * It's up to the FRP management agent to interpret the {@code String} as account it
         * supports. Please consult their relevant documentation for details.
         *
         * @param factoryResetProtectionAccounts list of accounts.
         * @return the same Builder instance.
         */
        @NonNull
        public Builder setFactoryResetProtectionAccounts(
                @NonNull List<String> factoryResetProtectionAccounts) {
            mFactoryResetProtectionAccounts = new ArrayList<>(factoryResetProtectionAccounts);
            return this;
        }

        /**
         * Sets whether factory reset protection is enabled or not.
         * <p>
         * Once disabled, factory reset protection will not kick in all together when the device
         * goes through untrusted factory reset. This applies to both the consumer unlock flow and
         * the admin account overrides via {@link #setFactoryResetProtectionAccounts}. By default,
         * factory reset protection is enabled.
         *
         * @param factoryResetProtectionEnabled Whether the policy is enabled or not.
         * @return the same Builder instance.
         */
        @NonNull
        public Builder setFactoryResetProtectionEnabled(boolean factoryResetProtectionEnabled) {
            mFactoryResetProtectionEnabled = factoryResetProtectionEnabled;
            return this;
        }

        /**
         * Combines all of the attributes that have been set on this {@code Builder}
         *
         * @return a new {@link FactoryResetProtectionPolicy} object.
         */
        @NonNull
        public FactoryResetProtectionPolicy build() {
            return new FactoryResetProtectionPolicy(mFactoryResetProtectionAccounts,
                    mFactoryResetProtectionEnabled);
        }
    }

    @Override
    public String toString() {
        return "FactoryResetProtectionPolicy{"
                + "mFactoryResetProtectionAccounts=" + mFactoryResetProtectionAccounts
                + ", mFactoryResetProtectionEnabled=" + mFactoryResetProtectionEnabled
                + '}';
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, @Nullable int flags) {
        int accountsCount = mFactoryResetProtectionAccounts.size();
        dest.writeInt(accountsCount);
        for (String account: mFactoryResetProtectionAccounts) {
            dest.writeString(account);
        }
        dest.writeBoolean(mFactoryResetProtectionEnabled);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<FactoryResetProtectionPolicy> CREATOR =
            new Creator<FactoryResetProtectionPolicy>() {

                @Override
                public FactoryResetProtectionPolicy createFromParcel(Parcel in) {
                    List<String> factoryResetProtectionAccounts = new ArrayList<>();
                    int accountsCount = in.readInt();
                    for (int i = 0; i < accountsCount; i++) {
                        factoryResetProtectionAccounts.add(in.readString());
                    }
                    boolean factoryResetProtectionEnabled = in.readBoolean();

                    return new FactoryResetProtectionPolicy(factoryResetProtectionAccounts,
                            factoryResetProtectionEnabled);
                }

                @Override
                public FactoryResetProtectionPolicy[] newArray(int size) {
                    return new FactoryResetProtectionPolicy[size];
                }
    };

    /**
     * Restore a previously saved FactoryResetProtectionPolicy from XML.
     * <p>
     * No validation is required on the reconstructed policy since the XML was previously
     * created by the system server from a validated policy.
     * @hide
     */
    @Nullable
    public static FactoryResetProtectionPolicy readFromXml(@NonNull TypedXmlPullParser parser) {
        try {
            boolean factoryResetProtectionEnabled = parser.getAttributeBoolean(null,
                    KEY_FACTORY_RESET_PROTECTION_ENABLED, false);

            List<String> factoryResetProtectionAccounts = new ArrayList<>();
            int outerDepth = parser.getDepth();
            int type;
            while ((type = parser.next()) != END_DOCUMENT
                    && (type != END_TAG || parser.getDepth() > outerDepth)) {
                if (type == END_TAG || type == TEXT) {
                    continue;
                }
                if (!parser.getName().equals(KEY_FACTORY_RESET_PROTECTION_ACCOUNT)) {
                    continue;
                }
                factoryResetProtectionAccounts.add(
                        parser.getAttributeValue(null, ATTR_VALUE));
            }

            return new FactoryResetProtectionPolicy(factoryResetProtectionAccounts,
                    factoryResetProtectionEnabled);
        } catch (XmlPullParserException | IOException e) {
            Log.w(LOG_TAG, "Reading from xml failed", e);
        }
        return null;
    }

    /**
     * @hide
     */
    public void writeToXml(@NonNull TypedXmlSerializer out) throws IOException {
        out.attributeBoolean(null, KEY_FACTORY_RESET_PROTECTION_ENABLED,
                mFactoryResetProtectionEnabled);
        for (String account : mFactoryResetProtectionAccounts) {
            out.startTag(null, KEY_FACTORY_RESET_PROTECTION_ACCOUNT);
            out.attribute(null, ATTR_VALUE, account);
            out.endTag(null, KEY_FACTORY_RESET_PROTECTION_ACCOUNT);
        }
    }

    /**
     * Returns if the policy will result in factory reset protection being locked to
     * admin-specified accounts.
     * <p>
     * When a device has a non-empty factory reset protection policy, trusted factory reset
     * via Settings will no longer remove factory reset protection from the device.
     * @hide
     */
    public boolean isNotEmpty() {
        return !mFactoryResetProtectionAccounts.isEmpty() && mFactoryResetProtectionEnabled;
    }

    /**
     * @hide
     */
    public void dump(IndentingPrintWriter pw) {
        pw.print("factoryResetProtectionEnabled=");
        pw.println(mFactoryResetProtectionEnabled);

        pw.print("factoryResetProtectionAccounts=");
        pw.increaseIndent();
        for (int i = 0; i < mFactoryResetProtectionAccounts.size(); i++) {
            pw.println(mFactoryResetProtectionAccounts.get(i));
        }
        pw.decreaseIndent();
    }
}
