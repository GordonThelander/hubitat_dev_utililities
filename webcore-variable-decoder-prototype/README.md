# webCoRE Variable Decoder Prototype

Experimental, test-backed reference code for discovering Hub Variable references in saved
Hubitat webCoRE piston configuration.

This package is a read-only proof of concept. It is not loaded by Automation Map and does
not modify a piston, webCoRE, or Hubitat state.

## Contents

| Path | Purpose |
| --- | --- |
| `webcore_variable_decoder_prototype.groovy` | Reconstructs and decodes saved `chunk:N` settings, then classifies typed variable operands. It includes nine executable checks. |

## What is established

- A webCoRE piston stores its JSON definition as Base64-encoded UTF-8 split across contiguous
  `chunk:N` settings.
- The matching Hubitat webCoRE source concatenates those chunks in numeric order, decodes
  Base64 and emoji escapes, and parses the result as JSON.
- Hub Variables are exposed to this Hubitat webCoRE port with an `@@` prefix.
- Ordinary webCoRE globals use `@`, piston-local variables are declared in the document's
  top-level `v` collection, and system variables use `$`.
- Pausing a piston does not remove its saved configuration, so configuration-based discovery
  is not dependent on the piston running.

## What is not established

- That webCoRE's private storage format will remain stable in future releases.
- A complete and reliable read-versus-write classification for every possible expression,
  statement, task, loop, and dynamic-variable construct.
- Complete compatibility with every historical webCoRE version.
- Safe mutation of saved piston chunks.

The conservative relationship supported by this prototype is therefore: a piston **uses** a
Hub Variable. Consumers should not infer read or write direction without additional structural
evidence.

## Verify the prototype

With Groovy installed, run this from the directory containing this README:

```text
groovy webcore_variable_decoder_prototype.groovy
```

Expected output:

```text
webCoRE decoder prototype: 9 checks passed
```

The checks cover one and multiple chunks, missing and duplicate chunks, invalid Base64,
invalid JSON, namespace separation, ordinary-text false positives, indexed variables, and
webCoRE emoji decoding.

## Safe use and privacy

Use this package only for read-only inspection, research, and conservative relationship
discovery. Do not write decoded or reconstructed data back to Hubitat.

The executable fixtures are entirely synthetic. They contain no hub addresses, tokens,
device IDs, application IDs, account information, or user variable names. A production
consumer should retain only the minimum extracted relationship data and must not log, export,
or persist decoded values.

## Evidence and provenance

- [webCoRE variable documentation](https://wiki.webcore.co/Variable)
- [Hubitat webCoRE maintainer release notes](https://community.hubitat.com/t/webcore-for-hubitat-updates/11967)
- [Hubitat webCoRE source, `hubitat-patches` branch](https://github.com/imnotbob/webCoRE/tree/hubitat-patches)
- [Maintainer discussion of the in-memory global-variable usage report](https://community.hubitat.com/t/webcore-list-global-variables-and-pistons-that-use-them/128312)

The source behavior was compared with the built-in webCoRE version installed on a Hubitat C-8
in September 2026. The usage report is useful corroboration, but it is populated by piston
analysis or execution and can be incomplete or stale. Saved configuration decoding is the
primary evidence used by this prototype.
