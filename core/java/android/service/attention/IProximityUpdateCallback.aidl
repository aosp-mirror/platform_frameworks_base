package android.service.attention;

/**
 * Callback for onStartProximityUpdates request.
 *
 * @hide
 */
oneway interface IProximityUpdateCallback {
    void onProximityUpdate(double distance);
}
