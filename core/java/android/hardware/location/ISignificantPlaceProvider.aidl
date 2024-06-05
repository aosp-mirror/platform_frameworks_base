package android.hardware.location;

import android.hardware.location.ISignificantPlaceProviderManager;

/**
 * @hide
 */
oneway interface ISignificantPlaceProvider {
    void setSignificantPlaceProviderManager(in ISignificantPlaceProviderManager manager);
}
