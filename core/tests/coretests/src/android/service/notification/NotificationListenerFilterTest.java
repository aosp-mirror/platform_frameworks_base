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

package android.service.notification;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ALERTING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_CONVERSATIONS;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_SILENT;

import static com.google.common.truth.Truth.assertThat;

import android.app.NotificationChannel;
import android.os.Parcel;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NotificationListenerFilterTest {

    @Test
    public void testEmptyConstructor() {
        NotificationListenerFilter nlf = new NotificationListenerFilter();
        assertThat(nlf.isTypeAllowed(FLAG_FILTER_TYPE_CONVERSATIONS)).isTrue();
        assertThat(nlf.isTypeAllowed(FLAG_FILTER_TYPE_ALERTING)).isTrue();
        assertThat(nlf.isTypeAllowed(FLAG_FILTER_TYPE_SILENT)).isTrue();
        assertThat(nlf.getTypes()).isEqualTo(FLAG_FILTER_TYPE_CONVERSATIONS
                | FLAG_FILTER_TYPE_ALERTING
                | FLAG_FILTER_TYPE_SILENT);

        assertThat(nlf.getDisallowedPackages()).isEmpty();
        assertThat(nlf.isPackageAllowed("pkg1")).isTrue();
    }


    @Test
    public void testConstructor() {
        ArraySet<String> pkgs = new ArraySet<>(new String[] {"pkg1", "pkg2"});
        NotificationListenerFilter nlf =
                new NotificationListenerFilter(FLAG_FILTER_TYPE_ALERTING, pkgs);
        assertThat(nlf.isTypeAllowed(FLAG_FILTER_TYPE_CONVERSATIONS)).isFalse();
        assertThat(nlf.isTypeAllowed(FLAG_FILTER_TYPE_ALERTING)).isTrue();
        assertThat(nlf.isTypeAllowed(FLAG_FILTER_TYPE_SILENT)).isFalse();
        assertThat(nlf.getTypes()).isEqualTo(FLAG_FILTER_TYPE_ALERTING);

        assertThat(nlf.getDisallowedPackages()).contains("pkg1");
        assertThat(nlf.getDisallowedPackages()).contains("pkg2");
        assertThat(nlf.isPackageAllowed("pkg1")).isFalse();
        assertThat(nlf.isPackageAllowed("pkg2")).isFalse();
    }

    @Test
    public void testSetDisallowedPackages() {
        NotificationListenerFilter nlf = new NotificationListenerFilter();

        ArraySet<String> pkgs = new ArraySet<>(new String[] {"pkg1"});
        nlf.setDisallowedPackages(pkgs);

        assertThat(nlf.isPackageAllowed("pkg1")).isFalse();
    }

    @Test
    public void testSetTypes() {
        NotificationListenerFilter nlf = new NotificationListenerFilter();

        nlf.setTypes(FLAG_FILTER_TYPE_ALERTING | FLAG_FILTER_TYPE_SILENT);

        assertThat(nlf.isTypeAllowed(FLAG_FILTER_TYPE_CONVERSATIONS)).isFalse();
        assertThat(nlf.isTypeAllowed(FLAG_FILTER_TYPE_ALERTING)).isTrue();
        assertThat(nlf.isTypeAllowed(FLAG_FILTER_TYPE_SILENT)).isTrue();
        assertThat(nlf.getTypes()).isEqualTo(FLAG_FILTER_TYPE_ALERTING
                | FLAG_FILTER_TYPE_SILENT);
    }

    @Test
    public void testDescribeContents() {
        final int expected = 0;
        ArraySet<String> pkgs = new ArraySet<>(new String[] {"pkg1", "pkg2"});
        NotificationListenerFilter nlf =
                new NotificationListenerFilter(FLAG_FILTER_TYPE_ALERTING, pkgs);
        assertThat(nlf.describeContents()).isEqualTo(expected);
    }

    @Test
    public void testParceling() {
        ArraySet<String> pkgs = new ArraySet<>(new String[] {"pkg1", "pkg2"});
        NotificationListenerFilter nlf =
                new NotificationListenerFilter(FLAG_FILTER_TYPE_ALERTING, pkgs);

        Parcel parcel = Parcel.obtain();
        nlf.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        NotificationListenerFilter nlf1 =
                NotificationListenerFilter.CREATOR.createFromParcel(parcel);
        assertThat(nlf1.isTypeAllowed(FLAG_FILTER_TYPE_CONVERSATIONS)).isFalse();
        assertThat(nlf1.isTypeAllowed(FLAG_FILTER_TYPE_ALERTING)).isTrue();
        assertThat(nlf1.isTypeAllowed(FLAG_FILTER_TYPE_SILENT)).isFalse();
        assertThat(nlf1.getTypes()).isEqualTo(FLAG_FILTER_TYPE_ALERTING);

        assertThat(nlf1.getDisallowedPackages()).contains("pkg1");
        assertThat(nlf1.getDisallowedPackages()).contains("pkg2");
        assertThat(nlf1.isPackageAllowed("pkg1")).isFalse();
        assertThat(nlf1.isPackageAllowed("pkg2")).isFalse();
    }
}
