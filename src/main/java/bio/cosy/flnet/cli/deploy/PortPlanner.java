package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.base.deployment.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.helper.ConsoleHelper;
import bio.cosy.flnet.cli.helper.NetworkHelper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PortPlanner {

    private static final int FIRST_UNPRIVILEGED = 1024;

    private final Map<Integer, String> reserved = new HashMap<>();
    private final Set<Integer> own;

    public PortPlanner(BaseFLNetDeployableInstance self, List<? extends BaseFLNetDeployableInstance> others) {
        for (BaseFLNetDeployableInstance other : others) {
            other.getPorts().forEach(port -> reserved.putIfAbsent(port, other.getLabel()));
        }
        own = self.isInitialized() ? new HashSet<>(self.getPorts()) : Set.of();
    }

    public void claim(int port, String purpose) {
        reserved.put(port, purpose);
    }

    public int suggest(int preferred, int fallbackStart) {
        if (isUsable(preferred)) {
            return preferred;
        }
        for (int port = fallbackStart; port <= 65535; port++) {
            if (isUsable(port)) {
                return port;
            }
        }
        return preferred;
    }

    public String validate(String value) {
        String error = NetworkHelper.validatePort(value);
        if (error != null) {
            return error;
        }
        String owner = reserved.get(Integer.parseInt(value.strip()));
        return owner == null ? null : "Port " + value.strip() + " is already used by " + owner + ".";
    }

    public void warnIfBusy(int port) {
        if (!own.contains(port) && port >= FIRST_UNPRIVILEGED && !isFreeOnHost(port)) {
            ConsoleHelper.warn("Port " + port + " is currently in use on this machine. Starting will fail until it is free.");
        }
    }

    private boolean isUsable(int port) {
        if (reserved.containsKey(port)) {
            return false;
        }
        return own.contains(port) || port < FIRST_UNPRIVILEGED || isFreeOnHost(port);
    }

    static boolean isFreeOnHost(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
