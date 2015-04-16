package android.security;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.gatekeeper.IGateKeeperService;

/**
 * Convenience class for accessing the gatekeeper service.
 *
 * @hide
 */
public abstract class GateKeeper {

    private GateKeeper() {}

    public static IGateKeeperService getService() {
        return IGateKeeperService.Stub.asInterface(
                ServiceManager.getService("android.service.gatekeeper.IGateKeeperService"));
    }

    public static long getSecureUserId() throws IllegalStateException {
        try {
            return GateKeeper.getService().getSecureUserId(UserHandle.myUserId());
        } catch (RemoteException e) {
            throw new IllegalStateException(
                    "Failed to obtain secure user ID from gatekeeper", e);
        }
    }
}
