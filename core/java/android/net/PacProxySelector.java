
package android.net;

import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.net.IProxyService;
import com.google.android.collect.Lists;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/**
 * @hide
 */
public class PacProxySelector extends ProxySelector {
    public static final String PROXY_SERVICE = "com.android.net.IProxyService";
    private IProxyService mProxyService;

    public PacProxySelector() {
        mProxyService = IProxyService.Stub.asInterface(
                ServiceManager.getService(PROXY_SERVICE));
    }

    @Override
    public List<Proxy> select(URI uri) {
        String response = null;
        String urlString;
        try {
            urlString = uri.toURL().toString();
        } catch (MalformedURLException e) {
            urlString = uri.getHost();
        }
        try {
            response = mProxyService.resolvePacFile(uri.getHost(), urlString);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        return parseResponse(response);
    }

    private static List<Proxy> parseResponse(String response) {
        String[] split = response.split(";");
        List<Proxy> ret = Lists.newArrayList();
        for (String s : split) {
            String trimmed = s.trim();
            if (trimmed.equals("DIRECT")) {
                ret.add(java.net.Proxy.NO_PROXY);
            } else if (trimmed.startsWith("PROXY ")) {
                String[] hostPort = trimmed.substring(6).split(":");
                String host = hostPort[0];
                int port;
                try {
                    port = Integer.parseInt(hostPort[1]);
                } catch (Exception e) {
                    port = 8080;
                }
                ret.add(new Proxy(Type.HTTP, new InetSocketAddress(host, port)));
            }
        }
        if (ret.size() == 0) {
            ret.add(java.net.Proxy.NO_PROXY);
        }
        return ret;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException failure) {

    }

}
