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

## Release: SBOM and signatures

Every `mvn package` writes a CycloneDX 1.6 software bill of materials next to the jar,
`target/agent-<version>.cdx.json`. The build writes it with its own code (`MavenSbom`, `EmbeddedSbom`; no extra Maven
plugin), so an offline build (`mvn -o`) writes it too, and a build that cannot write it fails, saying why. It lists:

- the agent and the Maven dependencies the jar bundles: coordinates and purl, SHA-256/384/512, licenses and dependency
  edges from the POMs Maven resolved;
- every embedded bundle in `src/main/resources` (controller, library, wsapi, the Felix bundles, ...) with its Maven
  coordinates and purl (or OSGi name and version) and SHA-256/384/512;
- under each bundle, the libraries it carries: nested jars (for example wsapi's Netty and BouncyCastle), libraries named
  by a `pom.properties`, and libraries inlined as bare classes without either (library.jar's LMAX Disruptor, Dropwizard
  Metrics, ANTLR 4, ...). Inlined libraries are found by package and named from a table of known libraries
  (`InlinedLibraries.KNOWN`), with the version from the bundle's `Embedded-Artifacts` or `Export-Package` header or a
  version constant in the library; each carries `cresco:inlined-package-root` / `cresco:inlined-packages` properties.
  Inlined classes nobody names are still listed, by package root, with `cresco:identified=false`, and the build log
  prints a WARNING for each.

A nested jar without a `pom.properties` takes its coordinates from its bundle's `Embedded-Artifacts` header, and a
bundle's other `pom.properties` account for their own artifact's packages only, so sibling artifacts of one group
(library.jar's siddhi-query-api, siddhi-query-compiler and siddhi-annotations beside siddhi-core) are listed too.

Feed it to a vulnerability scanner (`grype sbom:target/agent-1.3-SNAPSHOT.cdx.json`, Dependency-Track, ...). The build
itself refuses known-vulnerable components: `EmbeddedKnownVulnerabilitiesTest` walks every embedded jar's tree against
an offline list (`src/test/resources/known-vulnerable-components.txt`, from OSV) and fails unless a dated waiver
(`known-vulnerable-waivers.txt`: coordinates, jar, end date, reason) covers the finding; an expired waiver, or one that
matches nothing, fails it too. Today's waivers: the stale embedded library.jar (guava 33.6.0-jre, c3p0 0.9.1.1) and
logger.jar (log4j-core 2.24.3), until 2026-12-04, replaced at the final re-embed.

A release is signed with an operator-held key, on any build host (a workstation, any Linux host or the DGX; no GitHub
Actions):

```bash
# once, offline: an EC P-384 key (RSA-3072+ also accepted), passphrase-protected, never in this repository
umask 077
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384 -aes-256-cbc -out ~/keys/cresco-release.key
openssl pkey -in ~/keys/cresco-release.key -pubout -out cresco-release.pub      # publish this and its fingerprint

# each release
mvn -B clean package
scripts/sign-release.sh --key ~/keys/cresco-release.key            # target/agent-*.jar and target/agent-*.cdx.json
```

The files signed together are one release. The script writes a release manifest (the release name and version, taken
from the file names `agent-<version>.jar` / `.cdx.json` or given with `--name` / `--version`; the build id, `--build`
or else the jar's `Implementation-Version`, which names the version, git revision and build time; the signing key's
fingerprint; the time; and the SHA-384 of every file), signs the manifest, and writes next to each file `<file>.manifest`,
`<file>.sig` (a detached SHA-384 ECDSA or RSA signature of the manifest) and `<file>.sha384` (`sha384sum -c` format, a
convenience; not signed). It prints the SHA-256 fingerprint of the signing public key, the build id and the manifest's SHA-384: publish all
three with the release. Because the signature covers the
file names and the version, an older signed release cannot be served under a newer release's name. It refuses a key
that is weaker than EC P-384 / RSA-3072, not owner-only (0600 or 0400, no ACL), not yours, or inside the repository
(`.gitignore` also keeps `*.key`, `*.p12` and `release-signing*` out); the mode check reads `ls -ln`, so it works the
same on macOS, BSD and GNU/Linux. `--passin env:VAR` or `--passin file:PATH` supplies the passphrase
non-interactively; `--out DIR` puts the outputs elsewhere.

Anyone can check a release with the published public key (or an X.509 certificate for it). The fingerprint pin is
required, and so is a build check: `--build <published build id>`, or `--manifest-sha384 <published hash>`, or at
least `--not-before <YYYY-MM-DD>` (the manifest was signed then or later); without one, an older signed build of the
same version, a SNAPSHOT above all, would verify as this one. `--version` refuses any other release:

```bash
scripts/verify-release.sh --pub cresco-release.pub --fingerprint sha256:<published fingerprint> \
    --build <published build id> --version 1.3-SNAPSHOT agent-1.3-SNAPSHOT.jar agent-1.3-SNAPSHOT.cdx.json
```

It exits non-zero unless, for every file, `<file>.sig` is the pinned key's signature of `<file>.manifest` and the
manifest lists the file under its own name with its SHA-384 (and, with `--version` / `--name`, names that release).
Releases signed by the earlier per-file scheme (a `.sig` over the file itself, no `.manifest`) do not verify with this
script; sign them again.

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

`enable_console=true` starts the Felix WebConsole (`/system/console`, WebConsole 5.1.0) only when it can be secured;
otherwise the agent logs `WebConsole console NOT started: <why>` at SEVERE and runs without it. It is served by Felix
HTTP Jetty 12 (`org.apache.felix.http.jetty12` 2.0.10, Jetty 12.1.14), the one HTTP service the agent ships and
installed only with the console; wsapi serves its WebSockets with Netty and needs none. (Before 1.3 hardening the
console ran on Felix HTTP Jetty 5 / Jetty 11 beside an unused Jetty 12 bundle, a second Felix HTTP base and its
commons-io / commons-fileupload / UserAdmin bundles; those bundles and their inlined copies carried known CVEs, see
the SBOM.)

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
