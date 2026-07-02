package io.cresco.main;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Graceful-shutdown entrypoint for a running Cresco agent.
 *
 * <p>Run as a short-lived companion process:
 * <pre>java -cp agent-1.3-SNAPSHOT.jar io.cresco.main.AgentEngineShutdown [pid | data-dir] [timeoutMs]</pre>
 *
 * <p>It resolves the running agent's PID (a numeric first argument, or from
 * {@code <data-dir>/agent.pid} written by {@link HostApplication} on boot), then sends it
 * <b>SIGTERM</b>. That triggers the agent's JVM shutdown hook, which stops the OSGi bundles in
 * dependency order so the controller runs a <b>graceful teardown</b>: it unregisters from its parent
 * (an agent sends {@code agent_disable} to its region; a region sends {@code region_disable} to its
 * global) <em>while the broker is still alive</em>, so the parent records a clean departure instead
 * of aging the node to STALE/LOST. This process then waits for the agent to exit, escalating to
 * <b>SIGKILL</b> only if it overruns the timeout.
 *
 * <p>Exit codes: {@code 0} = graceful stop (or already gone); {@code 2} = forced after timeout;
 * {@code 1}/{@code 3} = argument/error.
 */
public class AgentEngineShutdown {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    public static void main(String[] argv) {
        try {
            long pid = resolvePid(argv);
            if (pid < 0) {
                System.exit(1);
                return;
            }
            long timeoutMs = resolveTimeout(argv);

            Optional<ProcessHandle> ho = ProcessHandle.of(pid);
            if (ho.isEmpty() || !ho.get().isAlive()) {
                System.out.println("Agent pid " + pid + " is not running (nothing to stop).");
                System.exit(0);
                return;
            }
            ProcessHandle handle = ho.get();

            System.out.println("Requesting graceful shutdown of agent pid " + pid + " (timeout " + timeoutMs + " ms)...");
            handle.destroy(); // SIGTERM -> HostApplication shutdown hook -> ordered stop + parent unregister

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (handle.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }

            if (handle.isAlive()) {
                System.err.println("Agent pid " + pid + " did not stop within " + timeoutMs + " ms; forcing (SIGKILL).");
                handle.destroyForcibly();
                try { handle.onExit().get(); } catch (Exception ignore) { /* proceed */ }
                System.exit(2);
                return;
            }

            System.out.println("Agent pid " + pid + " stopped gracefully.");
            System.exit(0);
        } catch (Exception ex) {
            System.err.println("AgentEngineShutdown error: " + ex);
            System.exit(3);
        }
    }

    /**
     * PID from a numeric first arg, otherwise read from {@code <dataDir>/agent.pid}. The data dir is
     * the first arg (if it isn't numeric), else {@code -Dcresco_data_location}, else {@code cresco-data}.
     */
    private static long resolvePid(String[] argv) {
        try {
            String arg = (argv.length > 0 && argv[0] != null) ? argv[0].trim() : null;
            if (arg != null && arg.matches("\\d+")) {
                return Long.parseLong(arg);
            }
            String dataDir = (arg != null && !arg.isEmpty())
                    ? arg
                    : System.getProperty("cresco_data_location", "cresco-data");
            Path pidFile = Paths.get(dataDir);
            if (!pidFile.getFileName().toString().equals("agent.pid")) {
                pidFile = pidFile.resolve("agent.pid");
            }
            if (!Files.exists(pidFile)) {
                System.err.println("No agent pidfile at " + pidFile.toAbsolutePath()
                        + " (agent not running, or pass the PID / data-dir as arg 1).");
                return -1;
            }
            return Long.parseLong(Files.readString(pidFile).trim());
        } catch (Exception ex) {
            System.err.println("AgentEngineShutdown: could not resolve pid: " + ex);
            return -1;
        }
    }

    private static long resolveTimeout(String[] argv) {
        try {
            if (argv.length > 1 && argv[1] != null && argv[1].trim().matches("\\d+")) {
                return Long.parseLong(argv[1].trim());
            }
        } catch (Exception ignore) { /* fall through to default */ }
        try {
            return Long.parseLong(System.getProperty("shutdown_timeout_ms", Long.toString(DEFAULT_TIMEOUT_MS)));
        } catch (Exception ignore) {
            return DEFAULT_TIMEOUT_MS;
        }
    }
}
