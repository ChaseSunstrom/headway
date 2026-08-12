# Vendored protobuf schemas — provenance

Everything under this directory is copied **verbatim** from
[opencardev/aasdk](https://github.com/opencardev/aasdk), path
`protobuf/aap_protobuf/`. Not written by the Headway authors; not modified.

- **Upstream:** https://github.com/opencardev/aasdk
- **Upstream licence:** GNU General Public License v3
- **Vendored:** 2026-08-12, 254 files

## Why vendored rather than transcribed

These schemas are the single most error-prone thing to retype: a wrong field
number or a `required` turned `optional` produces a wire format that parses
locally and is rejected by a real head unit. Copying them removes that entire
class of mistake, and keeps the diff against a future aasdk update mechanical.

## Why they carry no Headway copyright header

They are someone else's files. `tools/check-license-headers.sh` skips this
directory for that reason. Headway is GPLv3, the same licence as aasdk, so
vendoring is compatible — see `THIRD-PARTY.md`.

## Updating

Re-copy the whole directory from a fresh aasdk checkout and re-run the test
suite. The byte fixtures in `core-protocol`'s tests will catch a schema change
that alters the wire format.
