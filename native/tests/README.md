# DenyList mount cleanup tests

Run from the repository root with the configured toolchain:

```sh
scripts/env.py rustc --test --edition=2024 native/tests/denylist.rs -o /tmp/magisk-denylist-tests
/tmp/magisk-denylist-tests
```

These host tests check the production cleanup order, including stacked mounts
and nested targets. They do not perform mount system calls. Device testing is
still needed to verify namespace cleanup and root-checker behavior on Android.
