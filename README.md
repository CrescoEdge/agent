# Cresco

**Cresco** is a free and open-source edge-computing framework. It arranges distributed
agents into a hierarchy — a single **global** controller over **regions**, each with a
regional controller over a set of **agents** — giving distributed, hierarchical control
of plugins and workloads across edge infrastructure.

This repository is the **agent**: the runtime that boots the framework and brings all of
the components together into a single executable jar.

## Architecture

The agent is an [Apache Felix](https://felix.apache.org/) OSGi container.
`io.cresco.main.AgentEngine` starts a Felix framework and installs/starts a fixed bundle
sequence:

```
configadmin → logger → (metatype, scr, gogo) → library → core → controller
                                     (+ optional Jetty web console when enable_console=true)
```

The component jars are bundled inside the agent uber-jar (`src/main/resources/`) and
loaded by the classloader. The **controller** then brings up the fabric: an embedded
ActiveMQ broker, an embedded Derby state database, TCP/UDP discovery, the data plane, and
the system plugins (`wsapi`, `stunnel`, `repo`, `sysinfo`, …).

Agents form a **global → region → agent** hierarchy; an agent started with
`-Dis_global=true` is the root of a fabric.

## Components

| Component | Role |
|-----------|------|
| **[Agent](https://github.com/CrescoEdge/agent)** — _this repo_ | OSGi runtime that boots the framework and bundles every component into one executable jar. |
| [Logger](https://github.com/CrescoEdge/logger) | Logging bundle (pax-logging) — the first service the agent starts. |
| [Library](https://github.com/CrescoEdge/library) | Shared `io.cresco.library` API + embedded dependencies (JMS, Siddhi, Jackson, …) used by every component and plugin. |
| [Core](https://github.com/CrescoEdge/core) | Core agent services (logging control, update management), loaded above the library. |
| [Controller](https://github.com/CrescoEdge/controller) | Control plane — manages agents, regions and the global hierarchy; embeds the ActiveMQ broker, Derby state store, discovery, and loads system plugins. |
| [Repo](https://github.com/CrescoEdge/repo) | Plugin repository — stores, reports and deploys Cresco plugins. |
| [SysInfo](https://github.com/CrescoEdge/sysinfo) | Collects operating-environment and system metrics for an agent. |
| [WSAPI](https://github.com/CrescoEdge/wsapi) | WebSocket API plugin — the external client entrypoint (control, data plane, log streaming) over `wss://…:8282`. |
| [STunnel](https://github.com/CrescoEdge/stunnel) | Secure TCP tunnel plugin (Netty) — tunnels TCP across the fabric. |
| [Java Client (clientlib)](https://github.com/CrescoEdge/clientlib) | Java client library for driving Cresco through the wsapi. |
| [Python Client (pycrescolib)](https://github.com/CrescoEdge/pycrescolib) | Python client library for driving Cresco through the wsapi. |

## Build from source

Each component is an OSGi bundle built with `mvn package bundle:bundle` (JDK 21) and
installed to your local Maven repository, in dependency order:

```
logger, library            # no Cresco dependencies
core, repo, sysinfo, wsapi, stunnel, controller   # depend on library
agent                      # bundles all of the above as resources
```

Then build the agent uber-jar:

```bash
mvn package
```

This produces `target/agent-1.3-SNAPSHOT.jar` (main class `io.cresco.main.AgentEngine`).
The build fails (process-classes, `EmbeddedBundleCheck`) if any embedded jar in
`src/main/resources/` is not an OSGi bundle (no `Bundle-SymbolicName`, e.g. a controller built
without `bundle:bundle`), naming the jar; `-Dcresco.skipBundleCheck=true` bypasses it for testing only.
`prebuild.sh` shows how the component jars are staged into `src/main/resources/` before the
assembly; it can pull published snapshots, or you can stage locally-built bundles.

## Run

```bash
# secrets from the environment or owner-only files, not -D (every local user can read -D values with ps)
export CRESCO_discovery_secret_global=... CRESCO_discovery_secret_region=... CRESCO_discovery_secret_agent=...
export CRESCO_cresco_service_key=...
java -Djava.net.preferIPv4Stack=true \
     -Dregionname=global-region -Dagentname=global-controller -Dis_global=true \
     -Denable_wsapi=true \
     -jar agent-1.3-SNAPSHOT.jar
```

Configuration is via `-D` system properties, `CRESCO_*` environment variables, or an
`agent.ini` file. Common properties: `regionname`, `agentname`, `is_global`,
`is_region`/`global_controller_host`, `is_agent`/`regional_controller_host`,
`discovery_secret_{global,region,agent}`, `cresco_service_key`,
`enable_wsapi`/`enable_console`/`enable_dashboard`, `port`. Runtime state is written to
`cresco-data/` (Felix cache, Derby DB, ActiveMQ data, logs).

### Felix WebConsole

`enable_console=true` starts the Felix WebConsole (`/system/console`) only when it can be secured; otherwise the agent
logs `WebConsole console NOT started: <why>` at SEVERE and runs without it:

- **Credentials are required**: `console_username` and `console_password_file` (an owner-only file: mode 0600 or
  0400, owned by the agent's user, in a directory no one else can write) or `console_password`. The password needs at
  least 12 characters and may not be a well-known default (the console's built-in `admin`/`admin` is never used) or
  the user name. The console receives only a salted, iterated SHA-384 hash of it.
- **Loopback by default**: it binds `console_host` (default `127.0.0.1`) on `console_port` (default `port`, else
  8080). Reach it remotely through an SSH tunnel, or:
- **HTTPS off loopback**: with `console_keystore` (PKCS12) and `console_keystore_password_file` (or
  `console_keystore_password`) it serves HTTPS only (TLS 1.3/1.2) on `console_https_port` (default 8443). Binding a
  non-loopback `console_host` without a keystore is refused, since the password would cross the network in clear.

```bash
umask 077; printf '%s' 'a-long-console-password' > console.pw
java -Denable_console=true -Dconsole_username=ops -Dconsole_password_file=console.pw ... -jar agent-1.3-SNAPSHOT.jar
```

If an embedded bundle cannot be installed or started (for example a `controller.jar` that is not
an OSGi bundle), the agent logs one `FATAL:` line naming the jar and exits with status 1 instead
of hanging. As a backstop, if the controller bundle starts but has not registered its
`AgentService` within `controller_activation_timeout_sec` (default 300; `0` disables) the agent
exits the same way.

## Clients

External programs drive Cresco through the [wsapi](https://github.com/CrescoEdge/wsapi) WebSocket plugin
(`wss://host:8282`; the clients verify its certificate, pinned from the `ws.fingerprint` file wsapi writes in its plugin
data directory, and authenticate with a per-user credential or the `cresco_service_key`; see the wsapi README):

- **Java** — [clientlib](https://github.com/CrescoEdge/clientlib)
- **Python** — [pycrescolib](https://github.com/CrescoEdge/pycrescolib)

## License

Apache License, Version 2.0.
