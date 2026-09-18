package bio.cosy.flnet.cli.deploy;

import bio.cosy.flnet.cli.base.BaseFLNetDeployableInstance;
import bio.cosy.flnet.cli.support.Net;
import bio.cosy.flnet.cli.support.Ui;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps host ports of deployments on one machine apart. Ports of every other client and platform
 * are reserved (choosing one is an error); ports busy on the host for other reasons are skipped when
 * suggesting and produce a warning when chosen explicitly.
 */
public class PortPlanner {

    /** Ports below this need root to bind, so they cannot be probed without privileges. */
    private static final int FIRST_UNPRIVILEGED = 1024;

    private final Map<Integer, String> reserved = new HashMap<>();
    private final Set<Integer> own;

    /**
     * @param self   the deployment being configured; its current ports count as free for it
     * @param others all other deployments on this machine
     */
    public PortPlanner(BaseFLNetDeployableInstance self, List<? extends BaseFLNetDeployableInstance> others) {
        for (BaseFLNetDeployableInstance other : others) {
            other.getPorts().forEach(port -> reserved.putIfAbsent(port, other.getLabel()));
        }
        own = self.isInitialized() ? new HashSet<>(self.getPorts()) : Set.of();
    }

    /** Reserves a port chosen earlier in the same run (e.g. nginx before relay). */
    public void claim(int port, String purpose) {
        reserved.put(port, purpose);
    }

    /** {@code preferred} if usable, otherwise the next usable port from {@code fallbackStart}. */
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

    /** Validator for {@link bio.cosy.flnet.cli.support.Prompter}: invalid or reserved by another deployment. */
    public String validate(String value) {
        String error = Net.validatePort(value);
        if (error != null) {
            return error;
        }
        String owner = reserved.get(Integer.parseInt(value.strip()));
        return owner == null ? null : "Port " + value.strip() + " is already used by " + owner + ".";
    }

    /** Warns when a chosen port is busy on this machine (and not by this deployment itself). */
    public void warnIfBusy(int port) {
        if (!own.contains(port) && port >= FIRST_UNPRIVILEGED && !isFreeOnHost(port)) {
            Ui.warn("Port " + port + " is currently in use on this machine. Starting will fail until it is free.");
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
