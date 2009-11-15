package android.content.pm;

import android.os.Parcelable;

/**
 * Listener for changes to the set of registered services managed by a RegisteredServicesCache.
 * @hide
 */
public interface RegisteredServicesCacheListener<V> {
    /**
     * Invoked when a service is registered or changed.
     * @param type the type of registered service
     * @param removed true if the service was removed
     */
    void onServiceChanged(V type, boolean removed);
}
