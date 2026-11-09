#!/usr/bin/env bash
# bench-crypto.sh <agent.jar> [seconds]: this host's crypto and TLS throughput, one thread, as the agent sets it up in each
# mode (java -jar agent.jar --crypto bench): standard (the JDK's providers) and FIPS approved-only (security_profile=
# production) with each module choice -- ACCP-FIPS first where it runs (Linux x86_64/aarch64), BC-FJA only, and BCJSSE.
# Measures AES-256-GCM, SHA-256, ECDSA P-384 and RSA-3072 signing (message signing), and TLS 1.3 AES-256-GCM over a pair
# of SSLEngines (each byte encrypted and decrypted on the one thread). Prints one BENCH {json} line per mode and a table.
# Repeat it on every node type of a deployment: the FIPS modes' numbers depend on the CPU (AES-NI/VAES) and platform.
set -uo pipefail
JAR="${1:?usage: bench-crypto.sh <agent.jar> [seconds]}"; SECS="${2:-5}"
JAVA="${JAVA:-java}"
out=$(mktemp); trap 'rm -f "$out"' EXIT
for mode in "" "--fips" "--fips --bcfips" "--fips --bcjsse" "--fips --bcfips --bcjsse"; do
    line=$("$JAVA" -jar "$JAR" --crypto bench $mode "$SECS" 2>&1 | grep '^BENCH ' | tail -1)
    if [ -z "$line" ]; then echo "mode '${mode:-standard}': no result (see: $JAVA -jar $JAR --crypto bench $mode)" >&2; continue; fi
    echo "$line" | sed "s/^BENCH {/BENCH {\"flags\": \"${mode:-standard}\", /"
    echo "${mode:-standard}|$line" >> "$out"
done
printf '\n%-26s %10s %10s %10s %10s %12s  %s\n' "mode" "AES-GCM" "SHA-256" "ECDSA/s" "RSA/s" "TLS1.3 MB/s" "providers"
while IFS='|' read -r mode line; do
    j="${line#BENCH }"
    val() { printf '%s' "$j" | sed -n "s/.*\"$1\": \"\{0,1\}\([^,\"}]*\).*/\1/p"; }
    printf '%-26s %10s %10s %10s %10s %12s  %s\n' "$mode" "$(val aes_gcm_mb_s)" "$(val sha256_mb_s)" "$(val ecdsa_p384_sign_per_s)" \
        "$(val rsa3072_sign_per_s)" "$(val tls13_aes256gcm_mb_s)" "$(val providers)"
done < "$out"
