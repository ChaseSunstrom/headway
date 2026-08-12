# Third-party material

Headway is GPLv3. Everything vendored here is GPLv3-compatible, and the project
is a derivative work of the reverse-engineering effort that produced it.

## Protobuf schemas — `core-protocol/src/main/proto/aap_protobuf/`

254 `.proto` files copied verbatim from **opencardev/aasdk**, GPLv3.
See that directory's `PROVENANCE.md`. Not modified.

## TLS certificate material — `core-transport/src/main/resources/dev/headway/transport/tls/`

| File | Origin | Notes |
|---|---|---|
| `phone.crt` / `phone.key` | `AACS/AAServer/ssl/android_auto.*` (GPLv3) | The phone-side certificate. **Expired 2022-08-24** — see BLOCKERS.md B-003. |
| `headunit.crt` / `headunit.key` | `aasdk/cert/headunit.*` (GPLv3) | Used by the emulator to play the head unit. Valid to 2045. |

Both private keys were converted from PKCS#1 to PKCS#8 so the JDK can load them
without a third-party PEM parser. The key material itself is unchanged; only the
container encoding differs.

These certificates were extracted from Android Auto by the projects above. They
chain to a "Google Automotive Link" CA that no open-source project possesses,
which is why peer verification is disabled — see `AapTls`.

## Reference implementations — `references/` (not committed)

aasdk, AACS, WirelessAndroidAutoDongle, aa-proxy-rs and openauto are cloned
locally for study and cited throughout `docs/protocol-notes.md`. No code from
them is copied into Headway beyond the schemas and certificates listed above.
