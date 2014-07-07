package android.hardware.hdmi;

import android.annotation.SystemApi;
import android.hardware.hdmi.HdmiControlManager.VendorCommandListener;
import android.hardware.hdmi.IHdmiVendorCommandListener;
import android.os.RemoteException;
import android.util.Log;

/**
 * Parent for classes of various HDMI-CEC device type used to access
 * {@link HdmiControlService}. Contains methods and data used in common.
 *
 * @hide
 */
public abstract class HdmiClient {
    private static final String TAG = "HdmiClient";

    protected final IHdmiControlService mService;

    protected abstract int getDeviceType();

    public HdmiClient(IHdmiControlService service) {
        mService = service;
    }

    public void sendVendorCommand(int targetAddress, byte[] params, boolean hasVendorId) {
        try {
            mService.sendVendorCommand(getDeviceType(), targetAddress, params, hasVendorId);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to send vendor command: ", e);
        }
    }

    public void addVendorCommandListener(VendorCommandListener listener) {
        try {
            mService.addVendorCommandListener(getListenerWrapper(listener), getDeviceType());
        } catch (RemoteException e) {
            Log.e(TAG, "failed to add vendor command listener: ", e);
        }
    }

    private static IHdmiVendorCommandListener getListenerWrapper(
            final VendorCommandListener listener) {
        return new IHdmiVendorCommandListener.Stub() {
            @Override
            public void onReceived(int srcAddress, byte[] params, boolean hasVendorId) {
                listener.onReceived(srcAddress, params, hasVendorId);
            }
        };
    }
}
