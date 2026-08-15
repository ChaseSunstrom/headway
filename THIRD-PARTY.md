# Third-party material

Headway is GPLv3. Everything it builds against is GPLv3-compatible, and the
project is a derivative work of the reverse-engineering effort that produced the
protocol description.

## Protobuf schemas — `core-protocol/src/main/proto/aap_protobuf/`

254 `.proto` files copied verbatim from **opencardev/aasdk**, GPLv3. Not
modified; no Headway licence headers are added to them, which is why
`tools/check-license-headers.sh` excludes that directory. See its
`PROVENANCE.md` for the file list and the commit they came from.

## TLS certificate material — fetched at build time, not vendored

**These files are not in this repository.** `core-transport/build.gradle.kts`
fetches them during the build from the upstream projects that already publish
them, each pinned to an upstream commit *and* verified against a SHA-256, cached
in `.gradle/cert-cache` and written to `build/generated/certs`. A cold build
therefore needs network access once; after that the cache serves it.

| Credential | Upstream source | Subject and validity |
|---|---|---|
| `phone` | `AACS/AAServer/ssl/android_auto.crt` / `.key` | The phone role every reference implementation sends. **Expired 2022-08-24.** |
| `internal` | `AACS/AAClient/ssl/headunit.crt` / `.key` | `O=Android-Auto-Internal`, valid to 2048. **The one a real 2021 Chevrolet Infotainment 3 unit accepted** — see BLOCKERS.md B-003. |
| `headunit` | `openDsh/aasdk`, `src/Messenger/Cryptor.cpp` | `O=JVC Kenwood`, valid to 2045. Also what the emulator presents when playing the car. |

The third pair is not a file upstream: it is two C++ string literals inside
`Cryptor.cpp`, so the build fetches that source file and cuts the PEMs out of it
(`extractCppLiteral`).

The fetched bytes are written **verbatim**. The `phone` and `headunit` keys are
PKCS#1, which the JDK's `KeyFactory` will not load, so `AapTls.wrapPkcs1AsPkcs8`
puts the PKCS#1 body inside a PKCS#8 envelope in memory at load time. Nothing is
re-encoded on disk and no key material is altered.

All three chain to a "Google Automotive Link" CA that no open-source project
possesses, which is why Headway does not verify the peer — see `AapTls`.

### Why they are fetched rather than committed

A key is not copyrightable, so copyright is not the concern. DMCA §1201 is:
distributing material that defeats an access control has been treated as
trafficking in a circumvention device regardless of intent. Headway is a
§1201(f) interoperability project and these files have been hosted openly by the
upstream projects for years, so this is caution rather than a settled legal
question — but moving the distribution back to the projects that already do it
costs nothing and takes this repository out of that chain.

A user can also supply their own certificate and key in the app, which is the
only path that involves no third-party key at all.

## Reference implementations — `references/` (not committed)

aasdk, AACS, WirelessAndroidAutoDongle, aa-proxy-rs and openauto are cloned
locally for study and cited throughout `docs/protocol-notes.md`. No code from
them is copied into Headway beyond the schemas above and the certificates
fetched at build time.
