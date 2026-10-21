# FturnRaw Android source snapshot

This is the complete source dependency closure for `cmd/raw-client` targeting
Android/arm64, taken from the sibling fturn-raw-experimental checkout. It includes
the RAW 1.0.0 multi-device client and the authenticated IPv4 TCP/UDP SOCKS bridge
used by Fturn Android. The RAW wire protocol is unchanged by the bridge.

`SOURCE.json` records the source base commit, every included source file and a
snapshot SHA-256. Local changes were present in the source checkout; the base
commit alone is not sufficient to reproduce this snapshot. This directory is an
Android build snapshot, not a replacement for the full Windows/server checkout.

The root and locally replaced modules keep their original license files. The
Android scripts pin Go 1.26.6 and stamp the client core version 1.0.0. Rebuild with
`python scripts/build-raw-core.py` from the Android repository root. The normal
Gradle preBuild task also builds and verifies the RAW binary automatically.
