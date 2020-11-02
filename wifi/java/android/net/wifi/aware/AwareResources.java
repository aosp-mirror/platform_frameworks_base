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

package android.net.wifi.aware;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The resources of the Aware service.
 */
public final class AwareResources implements Parcelable {
    /**
     * Number of the NDPs are available.
     */
    private int mNumOfAvailableNdps;

    /**
     * Number of the publish sessions are available.
     */
    private int mNumOfAvailablePublishSessions;

    /**
     * Number of the subscribe sessions are available.
     */
    private int mNumOfAvailableSubscribeSessions;

    /**
     * @hide : should not be created by apps
     */
    public AwareResources() {
    }

    /**
     * Return the number of Aware data-paths (also known as NDPs - NAN Data Paths) which an app
     * could create. Please refer to the {@link WifiAwareNetworkSpecifier} to create
     * a Network Specifier and request a data-path.
     * <p>
     * Note that these resources aren't reserved - other apps could use them by the time you
     * attempt to create a data-path.
     * </p>
     * @return A Non-negative integer, number of data-paths that could be created.
     */
    public int getNumOfAvailableDataPaths() {
        return mNumOfAvailableNdps;
    }

    /**
     * Return the number of Aware publish sessions which an app could create. Please refer to the
     * {@link WifiAwareSession#publish(PublishConfig, DiscoverySessionCallback, Handler)}
     * to create a publish session.
     * <p>
     * Note that these resources aren't reserved - other apps could use them by the time you
     * attempt to create a publish session.
     * </p>
     * @return A Non-negative integer, number of publish sessions that could be created.
     */
    public int getNumOfAvailablePublishSessions() {
        return mNumOfAvailablePublishSessions;
    }

    /**
     * Return the number of Aware subscribe sessions which an app could create. Please refer to the
     * {@link WifiAwareSession#subscribe(SubscribeConfig, DiscoverySessionCallback, Handler)}
     * to create a publish session.
     * <p>
     * Note that these resources aren't reserved - other apps could use them by the time you
     * attempt to create a subscribe session.
     * </p>
     * @return A Non-negative integer, number of subscribe sessions that could be created.
     */
    public int getNumOfAvailableSubscribeSessions() {
        return mNumOfAvailableSubscribeSessions;
    }

    /**
     * Set the number of the available NDPs.
     * @hide
     * @param numOfAvailableNdps Number of available NDPs.
     */
    public void setNumOfAvailableDataPaths(int numOfAvailableNdps) {
        mNumOfAvailableNdps = numOfAvailableNdps;
    }

    /**
     * Set the number of the available publish sessions.
     * @hide
     * @param numOfAvailablePublishSessions Number of available publish sessions.
     */
    public void setNumOfAvailablePublishSessions(int numOfAvailablePublishSessions) {
        mNumOfAvailablePublishSessions = numOfAvailablePublishSessions;
    }

    /**
     * Set the number of the available subscribe sessions.
     * @hide
     * @param numOfAvailableSubscribeSessions Number of available subscribe sessions.
     */
    public void setNumOfAvailableSubscribeSessions(int numOfAvailableSubscribeSessions) {
        mNumOfAvailableSubscribeSessions = numOfAvailableSubscribeSessions;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mNumOfAvailableNdps);
        dest.writeInt(mNumOfAvailablePublishSessions);
        dest.writeInt(mNumOfAvailableSubscribeSessions);
    }

    public static final @android.annotation.NonNull Creator<AwareResources> CREATOR =
            new Creator<AwareResources>() {
                @Override
                public AwareResources createFromParcel(Parcel in) {
                    AwareResources awareResources = new AwareResources();
                    awareResources.setNumOfAvailableDataPaths(in.readInt());
                    awareResources.setNumOfAvailablePublishSessions(in.readInt());
                    awareResources.setNumOfAvailableSubscribeSessions(in.readInt());
                    return awareResources;
                }

                @Override
                public AwareResources[] newArray(int size) {
                    return new AwareResources[size];
                }
            };
}
