/*
 * Copyright 2024 The Android Open Source Project
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

package android.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.ActivityOptions.LaunchCookie;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LaunchCookieTest {

    @Test
    public void parcelNonNullLaunchCookie() {
        LaunchCookie launchCookie = new LaunchCookie();
        Parcel parcel = Parcel.obtain();
        LaunchCookie.writeToParcel(launchCookie, parcel);
        parcel.setDataPosition(0);
        LaunchCookie unparceledLaunchCookie = LaunchCookie.readFromParcel(parcel);
        assertEquals(launchCookie, unparceledLaunchCookie);
    }

    @Test
    public void parcelNullLaunchCookie() {
        Parcel parcel = Parcel.obtain();
        LaunchCookie.writeToParcel(/*launchCookie*/null, parcel);
        LaunchCookie unparceledLaunchCookie = LaunchCookie.readFromParcel(parcel);
        assertNull(unparceledLaunchCookie);
    }

}
