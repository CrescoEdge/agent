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

### The embedded bundles are pinned: `bundles.lock`

The component jars are committed in `src/main/resources/`, and `bundles.lock` names each of them: file,
`Bundle-SymbolicName`, `Bundle-Version`, SHA-256 and where it came from (a snapshot on Maven Central, by its
timestamped version and, after `@`, the source commit the bundle's manifest names as `Cresco-Commit`; or `committed`
for a bundle built by hand). A build embeds exactly the bundles the lock names or
it fails, naming the jar (the same `EmbeddedBundleCheck`; the lock also travels in the agent jar). Nothing is fetched
at build time, so the same commit gives the same agent on any day.

```bash
./prebuild.sh                      # verify the jars against bundles.lock (no network)
./prebuild.sh --update controller  # take the latest published snapshot of a bundle (checked against Central's checksum)
./prebuild.sh --lock executor      # record a bundle you built and copied in yourself, as it is
```

`--update` downloads with curl into a temporary directory and never reads `~/.m2`, where a bundle installed by a
local build would be taken for the published one. Commit `bundles.lock` together with the jars it names: its diff is
the statement of what the next release contains.

### Releases have names

Every commit of `main` is the release `1.3.<n>`, n being the number of commits up to it. The CI workflow (or
`scripts/release.sh --build --alias` by hand, on any host with the GitHub CLI) publishes it as a GitHub release of its
own, tagged on that commit and never replaced, with `agent-1.3.<n>.jar`, its SHA-256, `bundles.lock` and the SBOM. The
release `1.3-SNAPSHOT` still carries the latest jar under its old name (`agent-1.3-SNAPSHOT.jar`) and is replaced each
time: use it to get "the latest 1.3", and a `1.3.<n>` release to pin what a deployment runs.

```bash
java -jar agent-1.3.374.jar --version          # the release, the build, every embedded bundle with its SHA-256
git checkout 1.3.374 && scripts/rebuild-check.sh agent-1.3.374.jar            # the jar embeds what the lock names
scripts/rebuild-check.sh agent-1.3.374.jar --rebuild   # and a build of this checkout has the same bytes in every entry
```

A rebuild with the same JDK build is identical but for the four entries that record the build (the manifest, Maven's
`pom.properties`, the SBOM and the gate's list). Another 21.x patch release of the JDK can change a debug table: the
release 1.3.374, built by CI on Linux, differs from a rebuild with Homebrew's 21.0.5 on macOS in one line-number entry
of one class and nothing else. Each release's notes name the JDK it was built with.

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
itself refuses known-vulnerable components: `KnownVulnerabilityGate` is a build step in the package phase, right after
the SBOM. It is not a unit test, so `-DskipTests` and `-Dmaven.test.skip=true` do not skip it, and the SBOM and gate
executions set `<skip>false</skip>` literally (a parameter set in the POM is not read from its `-D` property), so
`-Dmaven.antrun.skip=true` does not skip them either; it used to skip both, and the build passed with an unwaived
known-vulnerable component. (The embedded-bundle check is skipped only by its own `-Dcresco.skipBundleCheck=true`.) It
checks every component of the SBOM (the agent's Maven dependencies, every embedded jar, the jars nested in them, the
libraries they inline or declare) and fails the build when:

- a component is on the offline known-vulnerable list, `sbom/known-vulnerable-components.txt` (from OSV; extend it
  when OSV reports more), and no waiver in `sbom/waivers.txt` covers it. A waiver names the coordinates and version, the
  embedded jar, `since=` and `until=` dates at most 30 days apart, `reviewer=` and a reason; a malformed, expired,
  future-dated or unused waiver fails the build too;
- a component's version is unknown (nothing in the jar records it, so no list can judge it) and it is not on the
  reviewed allowlist `sbom/unknown-version-allowlist.txt` (jar, coordinates, `since=` and `until=` dates at most 30 days
  apart, `reviewer=`, reason). An allowance expires like a waiver: one without dates (the earlier format), lasting more
  than 30 days, expired, future-dated or matching nothing any more fails the build.

**Inlined libraries are named by content.** A bundle that inlines a library as bare classes, with no `pom.properties`
and no version constant, records no version for it. The build resolves reference jars from the Maven repository
(`pom.xml`, execution `sbom-reference`, copied to `target/sbom-reference/` in repository layout, never packaged), and a
library whose every class is byte-identical to the class at the same path in one of them is listed as that artifact at
that version (`cresco:version-source` `content: every class byte-identical to <group:artifact:version> (<jar>,
SHA-256 ...)`). So OSHI's platform classes that executor.jar and sysinfo.jar inline are
`com.github.oshi:oshi-common:7.3.2` (they were listed as oshi-core of unknown version), and the classes library.jar
inlines from micrometer's pause detection are `org.latencyutils:LatencyUtils:2.0.3`. OSV (api.osv.dev, 2026-11-08) has
no advisory for any version of oshi-common, oshi-core or LatencyUtils. One class that differs leaves the library of
unknown version, and the gate fails the build: when a bundle inlines another version (an OSHI or micrometer upgrade),
add that version to `sbom-reference`.

Today there is no waiver and no unknown-version allowance: both files are empty, and every component's version is known.

**Who reviews an exception.** `reviewer=` is free text, so it cannot stop someone from approving their own waiver.
`CODEOWNERS` names the owner (`@codybum`) for `sbom/**`, the gate and SBOM code, `pom.xml` (which wires the gate) and the
release scripts, so a pull request that changes them asks for the owner's approval. **GitHub enforces that only where
branch protection (or a ruleset) on the target branch has "Require review from Code Owners" turned on**, and only for
changes made through pull requests; without it the owner is asked but a merge is not blocked, and a direct push is not
reviewed at all. Turning that protection on for the release branches is a repository setting for the owner.

**The gate's marker in the jar.** When the gate passes it also checks that the SBOM is this build's (every embedded jar
in it with its SHA-384, and no other), and writes its marker, which the assembly puts into the agent jar: the manifest's
`Cresco-Gate: passed sha384:<the SBOM's SHA-384>`, `Cresco-Gate-Files: sha384:<the list's SHA-384>` and
`Cresco-Gate-Date`, the SBOM itself as `META-INF/cresco/sbom.cdx.json`, and `META-INF/cresco/embedded.sha384`, the SHA-384
of every embedded jar. The assembly takes the SBOM and the list from the gate's output, so after `mvn clean`, `mvn
compile assembly:single` fails; with an older build's output left in `target/`, the jar it assembles has an empty
`Cresco-Gate`. Either way, `scripts/sign-release.sh` signs a jar only with a valid marker whose hashes match the jar's
own entries (and a `.cdx.json` signed with it only when it is that SBOM), and `scripts/verify-release.sh` checks the same
(with unzip and openssl, never by running the jar). Before, `mvn compile assembly:single` built a fat jar with no gate and
no SBOM, and the signing script signed it.

A release is signed with an operator-held key, on any build host (a workstation, any Linux host or the DGX; no GitHub
Actions):

```bash
# once, offline: an EC P-384 key (RSA-3072+ also accepted), passphrase-protected, never in this repository
umask 077
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384 -aes-256-cbc -out ~/keys/cresco-release.key
openssl pkey -in ~/keys/cresco-release.key -pubout -out cresco-release.pub      # publish this and its fingerprint

# each release
mvn -B clean package
scripts/sign-release.sh --key ~/keys/cresco-release.key            # target/agent-*.jar and target/agent-*.cdx.json (needs unzip)
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
required, and so is a build check: `--build <published build id>` or `--manifest-sha384 <published hash>` (use one of
these), or at least `--not-before <YYYY-MM-DD>`; without one, an older signed build of the same version, a SNAPSHOT
above all, would verify as this one. `--not-before` is the weak one: it only refuses builds signed before that date,
so any other build signed since (yesterday's SNAPSHOT, say) still passes; use it only when the release named no build
id or manifest hash. `--version` refuses any other release:

```bash
scripts/verify-release.sh --pub cresco-release.pub --fingerprint sha256:<published fingerprint> \
    --build <published build id> --version 1.3-SNAPSHOT agent-1.3-SNAPSHOT.jar agent-1.3-SNAPSHOT.cdx.json
```

It exits non-zero unless, for every file, `<file>.sig` is the pinned key's signature of `<file>.manifest` and the
manifest lists the file under its own name with its SHA-384 (and, with `--version` / `--name`, names that release), and
every jar carries a valid known-vulnerability gate marker (above). Releases signed by the earlier per-file scheme (a `.sig` over the file itself, no `.manifest`) do not verify with this
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

### Crypto modules and FIPS 140-3 mode

The agent jar carries the FIPS 140-3 validated crypto modules, **unmodified**, under `crypto/`: Bouncy Castle FIPS
(BC-FJA `bc-fips` 2.1.3, CMVP certificate #4943, with `bcpkix-fips`, `bcutil-fips`, `bctls-fips`) and the Amazon Corretto
Crypto Provider FIPS build (ACCP-FIPS 2.5.0, a JCA provider over AWS-LC FIPS 3.0.0, CMVP #4816) for Linux x86_64 and
aarch64. A FIPS module checks its own jar, so none of them is repackaged into the agent or a bundle:

- **Loading.** `java -jar` runs the jar's launcher agent (`Launcher-Agent-Class: io.cresco.main.CryptoBoot`) before
  `main`. At start each module jar is written to a new owner-only temporary directory, checked against the SHA-384 the
  known-vulnerability gate recorded for it (`META-INF/cresco/embedded.sha384`, covered by the release signature) and
  appended, unmodified, to the system class path. The OSGi framework exports the Bouncy Castle packages from there
  (`org.osgi.framework.system.packages.extra`, from the jars' own `Export-Package`), and the controller and wsapi import
  them instead of embedding Bouncy Castle. A deployment that puts the jars on the class path itself is used as is.
- **Standard profile** (the default): the JDK's providers serve crypto and TLS, as before; Bouncy Castle FIPS is only
  the X.509 library the controller and wsapi make certificates with.
- **`security_profile=production`** (or `crypto_fips_mode=true` in the standard profile): **FIPS approved-only mode**.
  Every JCA provider that is not a FIPS module is removed (SUN, SunJCE, SunEC, SunRsaSign, ...). The list is ACCP-FIPS
  (its approved algorithms only: AES-GCM, AES-KWP, SHA-2, HMAC-SHA-2, HKDF, ECDH, EC/RSA keys, ECDSA/RSA/RSA-PSS
  signatures with SHA-2), then BC-FJA in approved-only mode (everything else: X.509, PKIX, BCFKS keystores, PBKDF2,
  the DRBG), then SunJSSE as the TLS protocol engine, whose every cryptographic operation is then a module's; TLS is
  TLS 1.3 (SunJSSE's TLS 1.2 key derivation is SunJCE's, which is gone). The OS entropy (the platform's NativePRNG) is the
  DRBG's seed source only. A non-approved algorithm is refused: at start the agent checks that MD5, ChaCha20, RC4,
  Blowfish, X25519 and MD5withRSA are not available and that the modules work, and does not start otherwise. The log
  line `crypto: FIPS 140-3 approved-only mode: ...` names the modules and which serves AES-GCM, SHA-256, random numbers
  and TLS. Where ACCP-FIPS has no build (macOS, Windows), BC-FJA serves everything, with a WARNING (its pure-Java TLS is
  far slower, see below).
- **Keystores** in FIPS mode are BCFKS (a PKCS#12 file's integrity MAC derives its key with the PKCS#12 KDF, which FIPS
  does not approve): the `PKCS12` keystore name is served by BCFKS, so code keeps asking for PKCS12; a PKCS#12 file is
  refused with how to convert it: `java -jar agent.jar --crypto convert-keystore <in.p12> <password-file> <out.bcfks>`
  (same password, owner-only output). PEM trust anchors, CRLs and PEM plugin-signing bundles need nothing.
- **Refused in FIPS mode**: `crypto_fips_mode=false` with the production profile, `cresco_ssl_provider=CONSCRYPT`
  (BoringSSL is not a FIPS module), wsapi's `wsapi_ssl_provider=OPENSSL`.

| Setting | Default | Meaning |
|---|---|---|
| `crypto_fips_mode` | production: true, else false | FIPS approved-only mode |
| `crypto_fips_provider` | `auto` | `auto`: ACCP-FIPS first where it runs; `accp`: required (refuse to start without it); `bcfips`: BC-FJA only |
| `crypto_fips_tls` | `sunjsse` | `bcjsse`: BCJSSE over BC-FJA (TLS 1.3 and 1.2, the TLS key derivation inside the module; slower) |

`java -jar agent.jar --crypto status [--fips]` shows what a node would run with; `scripts/bench-crypto.sh <agent.jar>`
measures this host in every mode (one thread; TLS is a pair of SSLEngines, each byte encrypted and decrypted), and
`scripts/bench/dataplane_perf.py` measures the dataplane through running agents (one node, or across the broker bridge
with `--rx-host/--rx-port`). Measured 2026-11-08:

| one thread, MB/s or ops/s | AES-256-GCM | TLS 1.3 | RSA-3072 sign/s | ECDSA P-384 sign/s |
|---|---|---|---|---|
| Xeon 8480CL (DGX), standard (JDK) | 8717 | 2793 | 425 | 1064 |
| Xeon 8480CL, FIPS: ACCP-FIPS + BC-FJA, SunJSSE (the default) | 11857 | 3623 | 1403 | 12662 |
| Xeon 8480CL, FIPS: BC-FJA only (native VAES), SunJSSE | 4547 | 916 | 333 | 2433 |
| Xeon 8480CL, FIPS: BC-FJA + BCJSSE | 4547 | 818 | 349 | 2515 |
| Apple M3 Max, standard (JDK) | 4942 | 1890 | 519 | 1577 |
| Apple M3 Max, FIPS: BC-FJA only (no native code for this platform) | 116 | 56 | 422 | 3570 |

So on Linux x86_64 FIPS mode is faster than the JDK (ACCP-FIPS); BC-FJA alone costs 3-4x of TLS throughput there and 30x
on Apple silicon, which is why ACCP-FIPS comes first wherever it runs. End to end on one DGX node (Xeon 8480CL, 8 cores,
`scripts/bench/dataplane_perf.py`, 2000 x 256 KiB): a production (FIPS) mesh 299 MiB/s through one agent and 280 MiB/s
across the broker bridge to another; a standard mesh 246 and 219 MiB/s.

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
