/*
 * Copyright (C) 2010 The Android Open Source Project
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
 * limitations under the License
 */

package android.location;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class wraps the country information.
 *
 * @hide
 */
public class Country implements Parcelable {
    /**
     * The country code came from the mobile network
     */
    public static final int COUNTRY_SOURCE_NETWORK = 0;

    /**
     * The country code came from the location service
     */
    public static final int COUNTRY_SOURCE_LOCATION = 1;

    /**
     * The country code was read from the SIM card
     */
    public static final int COUNTRY_SOURCE_SIM = 2;

    /**
     * The country code came from the system locale setting
     */
    public static final int COUNTRY_SOURCE_LOCALE = 3;

    /**
     * The ISO 3166-1 two letters country code.
     */
    private final String mCountryIso;

    /**
     * Where the country code came from.
     */
    private final int mSource;

    private int mHashCode;
    /**
     *
     * @param countryIso the ISO 3166-1 two letters country code.
     * @param source where the countryIso came from, could be one of below
     *        values
     *        <p>
     *        <ul>
     *        <li>{@link #COUNTRY_SOURCE_NETWORK}</li>
     *        <li>{@link #COUNTRY_SOURCE_LOCATION}</li>
     *        <li>{@link #COUNTRY_SOURCE_SIM}</li>
     *        <li>{@link #COUNTRY_SOURCE_LOCALE}</li>
     *        </ul>
     */
    public Country(final String countryIso, final int source) {
        if (countryIso == null || source < COUNTRY_SOURCE_NETWORK
                || source > COUNTRY_SOURCE_LOCALE) {
            throw new IllegalArgumentException();
        }
        mCountryIso = countryIso.toLowerCase();
        mSource = source;
    }

    public Country(Country country) {
        mCountryIso = country.mCountryIso;
        mSource = country.mSource;
    }

    /**
     * @return the ISO 3166-1 two letters country code
     */
    public final String getCountryIso() {
        return mCountryIso;
    }

    /**
     * @return where the country code came from, could be one of below values
     *         <p>
     *         <ul>
     *         <li>{@link #COUNTRY_SOURCE_NETWORK}</li>
     *         <li>{@link #COUNTRY_SOURCE_LOCATION}</li>
     *         <li>{@link #COUNTRY_SOURCE_SIM}</li>
     *         <li>{@link #COUNTRY_SOURCE_LOCALE}</li>
     *         </ul>
     */
    public final int getSource() {
        return mSource;
    }

    public static final Parcelable.Creator<Country> CREATOR = new Parcelable.Creator<Country>() {
        public Country createFromParcel(Parcel in) {
            return new Country(in.readString(), in.readInt());
        }

        public Country[] newArray(int size) {
            return new Country[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mCountryIso);
        parcel.writeInt(mSource);
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) {
            return true;
        }
        if (object instanceof Country) {
            Country c = (Country) object;
            return mCountryIso.equals(c.getCountryIso()) && mSource == c.getSource();
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = mHashCode;
        if (hash == 0) {
            hash = 17;
            hash = hash * 13 + mCountryIso.hashCode();
            hash = hash * 13 + mSource;
            mHashCode = hash;
        }
        return mHashCode;
    }

    /**
     * Compare the specified country to this country object ignoring the mSource
     * field, return true if the countryIso fields are equal
     *
     * @param country the country to compare
     * @return true if the specified country's countryIso field is equal to this
     *         country's, false otherwise.
     */
    public boolean equalsIgnoreSource(Country country) {
        return country != null && mCountryIso.equals(country.getCountryIso());
    }
}
