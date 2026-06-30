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

This produces `target/agent-1.2-SNAPSHOT.jar` (main class `io.cresco.main.AgentEngine`).
`prebuild.sh` shows how the component jars are staged into `src/main/resources/` before the
assembly; it can pull published snapshots, or you can stage locally-built bundles.

## Run

```bash
java -Djava.net.preferIPv4Stack=true \
     -Dregionname=global-region -Dagentname=global-controller -Dis_global=true \
     -Denable_wsapi=true -Denable_console=true \
     -Ddiscovery_secret_global=sec -Ddiscovery_secret_region=sec -Ddiscovery_secret_agent=sec \
     -Dcresco_service_key=mykey \
     -jar agent-1.2-SNAPSHOT.jar
```

Configuration is via `-D` system properties, `CRESCO_*` environment variables, or an
`agent.ini` file. Common properties: `regionname`, `agentname`, `is_global`,
`is_region`/`global_controller_host`, `is_agent`/`regional_controller_host`,
`discovery_secret_{global,region,agent}`, `cresco_service_key`,
`enable_wsapi`/`enable_console`/`enable_dashboard`, `port`. Runtime state is written to
`cresco-data/` (Felix cache, Derby DB, ActiveMQ data, logs).

## Clients

External programs drive Cresco through the [wsapi](https://github.com/CrescoEdge/wsapi) WebSocket plugin
(`wss://host:8282`, authenticated with the `cresco_service_key` header):

- **Java** — [clientlib](https://github.com/CrescoEdge/clientlib)
- **Python** — [pycrescolib](https://github.com/CrescoEdge/pycrescolib)

## License

Apache License, Version 2.0.
