package android.service.attention;

/**
 * Callback for onStartProximityUpdates request.
 *
 * @hide
 */
oneway interface IProximityCallback {
    void onProximityUpdate(double distance);
}
