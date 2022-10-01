---
title: Verifying Binaries
id: verifying-binaries
sidebar_position: 3
---

Releases are signed
with [benthecarman's signing key](https://keybase.io/benthecarman/pgp_keys.asc?fingerprint=0ad83877c1f0cd1ee9bd660ad7cc770b81fd22a8)
with fingerprint `0AD83877C1F0CD1EE9BD660AD7CC770B81FD22A8`

To do the verification, first hash the executable using `sha256sum`. You should check that the result is listed in
the `SHA256SUMS.asc` file next to its file name. After doing that you can use `gpg --verify` to authenticate the
signature.

Example:

```
$ gpg -d SHA256SUMS.asc > SHA256SUMS.stripped
gpg: Signature made Wed 14 Sep 2022 10:21:24 AM CDT
gpg:                using RSA key 0AD83877C1F0CD1EE9BD660AD7CC770B81FD22A8
gpg: Good signature from "Ben Carman <benthecarman@live.com>"

$ sha256sum -c SHA256SUMS.stripped
vortexd-linux-1.0.0.zip: OK
vortexd-macosx-1.0.0.zip: OK
vortexd-win-1.0.0.zip: OK
```
