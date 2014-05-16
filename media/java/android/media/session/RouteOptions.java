/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.media.session;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Specifies options that an application might use when connecting to a route.
 * This includes things like interfaces, connection parameters, and required
 * features.
 * <p>
 * An application may create several different route options that describe
 * alternative sets of capabilities that it can use and choose the most
 * appropriate route options when it is ready to connect to the route. Each
 * route options instance must specify a complete set of capabilities to request
 * when the connection is established.
 * @hide
 */
public final class RouteOptions implements Parcelable {
    private static final String TAG = "RouteOptions";

    private final ArrayList<String> mIfaces;
    private final Bundle mConnectionParams;

    private RouteOptions(List<String> ifaces, Bundle params) {
        mIfaces = new ArrayList<String>(ifaces);
        mConnectionParams = params;
    }

    private RouteOptions(Parcel in) {
        mIfaces = new ArrayList<String>();
        in.readStringList(mIfaces);
        mConnectionParams = in.readBundle();
    }

    /**
     * Get the interfaces this connection wants to use.
     *
     * @return The interfaces for this connection
     */
    public List<String> getInterfaceNames() {
        return mIfaces;
    }

    /**
     * Get the parameters that will be used for connecting.
     *
     * @return The set of connection parameters this connections uses
     */
    public Bundle getConnectionParams() {
        return mConnectionParams;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStringList(mIfaces);
        dest.writeBundle(mConnectionParams);
    }

    @Override
    public String toString() {
        StringBuilder bob = new StringBuilder();
        bob.append("Options: interfaces={");
        for (int i = 0; i < mIfaces.size(); i++) {
            if (i != 0) {
                bob.append(", ");
            }
            bob.append(mIfaces.get(i));
        }
        bob.append("}");
        bob.append(", parameters=");
        bob.append(mConnectionParams == null ? "null" : mConnectionParams.toString());
        return bob.toString();
    }

    public static final Parcelable.Creator<RouteOptions> CREATOR
            = new Parcelable.Creator<RouteOptions>() {
        @Override
        public RouteOptions createFromParcel(Parcel in) {
            return new RouteOptions(in);
        }

        @Override
        public RouteOptions[] newArray(int size) {
            return new RouteOptions[size];
        }
    };

    /**
     * Builder for creating {@link RouteOptions}.
     */
    public final static class Builder {
        private ArrayList<String> mIfaces = new ArrayList<String>();
        private Bundle mConnectionParams;

        public Builder() {
        }

        /**
         * Add a required interface to the options.
         *
         * @param interfaceName The name of the interface to add.
         * @return The builder to allow chaining commands.
         */
        public Builder addInterface(String interfaceName) {
            if (TextUtils.isEmpty(interfaceName)) {
                throw new IllegalArgumentException("interfaceName cannot be empty");
            }
            if (!mIfaces.contains(interfaceName)) {
                mIfaces.add(interfaceName);
            } else {
                Log.w(TAG, "Attempted to add interface that is already added");
            }
            return this;
        }

        /**
         * Set the connection parameters to use with the options. TODO replace
         * with more specific calls once we decide on the standard way to
         * express parameters.
         *
         * @param parameters The parameters to use.
         * @return The builder to allow chaining commands.
         */
        public Builder setParameters(Bundle parameters) {
            mConnectionParams = parameters;
            return this;
        }

        /**
         * Generate a set of options.
         *
         * @return The options with the specified components.
         */
        public RouteOptions build() {
            return new RouteOptions(mIfaces, mConnectionParams);
        }
    }
}
