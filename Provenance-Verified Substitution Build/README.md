# Provenance-Verified Substitution Build

A pattern for generating a clean production artifact (stripped comments, a handful of identity/
channel substitutions) from an annotated development source, with a machine-checked guarantee that
the result is provably derived from one exact, unmodified commit and that *nothing except an
explicit, reviewed set of changes* actually changed. Generalized from Automation Map's own
production build tooling (backlog item 16 - `production_build_methodology.md`, implemented and
hardened across several review rounds), kept here as a reusable pattern for future projects, not
copied with real identifiers.

**The problem this solves:** a Dev-branch source file carries heavy internal commentary, Dev-only
build markers, and Dev-specific identity (an app name suffix, asset URLs pointing at a `dev` branch,
a build-channel flag) that a production release should not ship. Hand-editing a second copy for each
release invites exactly the class of mistake this exists to prevent: a stray leftover Dev marker, a
copy-paste that silently diverges from the real source, or no way to prove after the fact which
commit a shipped artifact actually came from. This pattern automates the transform and proves it
correct instead of trusting it.

## The two ideas doing the real work

1. **Provenance-verified** - nothing is read until it is proven to be the exact, untampered content
   of one specific, resolvable commit: tracked in the index, present in that commit's own tree,
   clean relative to it, and content-hash-matched directly against that commit's blob (not merely
   "git says nothing looks different," which an index flag can fool - see Pitfalls below).
2. **Substitution, not transformation** - a small, fixed, versioned list of exact before/after
   values, each checked to fire exactly once, never a general-purpose rewrite. After substitution,
   the whole output is walked structurally and every element that was *not* on the list is proven
   identical to the source; every element that *was* is proven to have changed in exactly the
   documented way. The tool never trusts its own bookkeeping - it re-derives the proof from the
   actual output every time.

Everything below is one or the other of these two ideas, applied consistently.

## When to use this pattern

Any time a maintained Dev/annotated source needs to become a distinct, cleaner production artifact
on a predictable, small set of axes (identity, environment/channel flags, internal-only commentary),
and you want a release process that fails loudly on anything unexpected rather than silently shipping
a wrong or half-updated artifact. Not a good fit for content that needs genuine editorial judgment
per release (see the release-notes note below) - that's a human input this pattern accepts and
verifies, not something it should try to generate.

## 1. Git-bound provenance

Resolve the target commit's SHA once (`git rev-parse --verify HEAD`, or a specific pinned commit).
For every file the build reads, in order:

1. **Tracked**: `git ls-files --error-unmatch -- <path>` must succeed. An *ignored and untracked*
   file at the expected path produces empty `git status --porcelain` output too - status alone
   cannot tell "genuinely clean" apart from "git isn't looking at this file at all."
2. **Present in the target commit's tree**: `git rev-parse --verify <sha>:<path>` must resolve to a
   real blob. Catches a file that's staged-but-never-committed, which `ls-files` alone would still
   accept.
3. **Clean**: `git status --porcelain -- <path>` must be empty.
4. **Content-identity, independent of git's own change-detection**: `git hash-object --path <path>
   <path>` (the worktree file's own object ID, computed with the same path-aware clean filters git
   would apply) must equal the blob ID resolved in step 2. Steps 1-3 all ultimately trust git's
   *change-detection heuristics*, which index flags like `assume-unchanged`/`skip-worktree` can
   suppress even though the actual bytes differ from what's tracked. Step 4 doesn't ask git whether
   anything changed; it independently proves the content is identical.

Building a package from more than one file (e.g. an app source plus its manifest)? Resolve the
commit SHA **once** and verify every file against that *same* resolved SHA, in one function call -
not once per file. Two separate resolutions can each individually succeed while still describing two
different moments if something commits in between; there is no way to detect that after the fact
unless the SHA was shared to begin with.

## 2. The allowlist

Each entry names an exact path/location, the exact value expected there, and the exact value it
becomes. Two entry shapes cover most cases:

- **Fixed value** (`expectedFrom` → `expectedTo`): the location must hold `expectedFrom` exactly, or
  the whole build fails closed, naming the entry and what was actually found. This catches a value
  that's missing, already-transformed, or - just as importantly - a value that looks plausible but
  isn't the one specific identity you expected (a real incident this pattern was built to prevent:
  an early draft asserted only the *output* value for one entry, meaning literally any input,
  including an already-correct production value or a value from an unrelated third identity, would
  be silently accepted and overwritten).
- **Transform** (a function of the original value): for a substitution that isn't a flat constant -
  e.g. stripping a known prefix - the transform function itself must fail closed (return nothing
  usable) on any input that doesn't match the exact expected shape, not just on a completely absent
  one.

A third shape is worth naming explicitly because it's easy to miss: **external** - a value supplied
from *outside* the Dev source entirely, not derived from it at all. Not everything that differs
between Dev and production can be mechanically derived. A curated release-notes summary is the
concrete example this pattern was built against: the Dev source accumulates a long history across
many interim builds, but a production release needs a short, human-judged "what's new since the last
release" - text nobody would want machine-generated from a commit log. The right design is not to
try harder at deriving it, but to accept it as a separately verified input (its own provenance chain,
its own explicit binding to the release it's for, its own check that it isn't accidentally still in
Dev shape) and simply carry it through untouched.

## 3. Prove the structure, not just the substitution

Whatever the underlying format, walk the *real* structure and compare original against candidate
element-by-element:

- **Code**: lex both with the language's own parser/lexer (never regex - comment-like text
  legitimately appears inside string literals, URLs, and embedded markup that must survive
  untouched) and build an ordered stream of records - one per real token, one per line-ending -
  covering both source texts. Comment removal and substitution are then two separately provable
  claims: "the non-comment token stream is identical modulo comment removal" and "the resulting
  stream is identical except at exactly the allowlisted positions, each of which changed in exactly
  the expected way." A bare token *count* is not enough - it cannot distinguish a token that moved
  position from one that didn't, or a line-ending silently swapped for a different kind at the same
  position.
- **Structured data (JSON, similar)**: parse both, then recursively walk keys/array elements in
  lockstep. Require identical key sets and array lengths everywhere (a silently added, removed, or
  reordered field is a structural bug no allowlist entry is meant to paper over), and for every leaf
  value: unchanged unless it's on the allowlist, and if it is, changed in exactly the way that entry
  describes.

Then a final independent backstop, run against the literal generated output text: confirm none of
the allowlist's *pre-substitution* values still appear anywhere in the result. Be careful how this
is implemented - see the false-positive pitfall below.

## 4. Fail-closed everywhere; deterministic, atomic output

- Every check above returns a clear reason on failure and stops the whole build; nothing partial is
  ever produced.
- The final artifact must be a pure function of the source commit: no wall-clock timestamp, no
  machine-specific data, anywhere in the generated content (including any generated header or
  sidecar metadata). Verify this directly: run the build twice from the same commit and diff the
  outputs - they must be byte-identical.
- **Two runs in the same working copy is not a sufficient test of that**, and believing it is will
  hide a whole class of defect. Anything the build reads off disk that the *checkout* controls
  rather than the *commit* - line endings being the usual culprit - stays constant between two runs
  in one directory and varies between two independently materialized checkouts of the identical
  commit. Diff two separate fresh checkouts, ideally created under different client settings, not
  two runs in the same folder. Text a generator reads must be canonicalized on the way in (and the
  finished artifact asserted to hold only the canonical form), because the version-control system's
  own content hashing may normalize exactly what the generator preserves, letting provenance pass
  while the bytes differ.
- Writing the destination: remove any pre-existing file/directory at the target path *before* any
  real work begins (not just before the final write) - so any later failure, for any reason, leaves
  the target absent rather than a stale prior success that could be mistaken for the new build's
  output. Then write to a unique temporary sibling, verify it read back correctly, and move it into
  place as the last step - atomically where the filesystem supports it, with a same-directory
  rename/replace fallback otherwise.

## Composing multiple verified builds into one package

When a release needs more than one generated artifact together (an app plus its manifest, say), add
one more layer rather than trusting "two commands run close together":

1. Resolve one shared commit and verify every bound input against it (section 1).
2. Generate every artifact into one isolated temporary directory - nothing touches the real output
   location yet.
3. Run every gate - including anything slow or external, like a full downstream validation script -
   **before** the final recheck, not after it. This ordering is easy to get backwards and easy to
   miss in review: a recheck placed before the slow gates leaves the exact window it exists to close
   open for the entire duration of the slowest step. The recheck must be the *last* thing before
   publication, full stop.
4. Recheck the same shared commit one final time, immediately before publishing. If anything moved,
   fail closed; nothing is published.
5. Write a small, deterministic, machine-readable sidecar recording the source commit and each
   artifact's own hash - no wall-clock data, so it participates in the same reproducibility guarantee
   as everything else.
6. Publish the whole temporary directory atomically (rename into place), only now.
7. Provide a separate, independent verifier: given a published package, recompute every artifact's
   real hash and confirm it matches the sidecar - and cross-check at least one artifact's own
   self-description (an embedded commit reference in a header, say) against the sidecar too, as a
   second independent signal. This catches a directory hand-assembled from two different builds'
   outputs, which the build process's own atomic publish never produces but a careless later copy
   easily could.

## Pitfalls actually hit building this

- **Line endings quietly break commit-purity, and the provenance check cannot see it.** The most
  expensive defect in this whole pattern. Git normalizes line endings when it hashes content, so a
  worktree holding CRLF still verifies clean against an LF blob - while the generator, reading raw
  bytes off disk, produces a materially different artifact. Provenance passes, the output differs,
  and nothing reports a problem. It surfaced when a routine branch switch on a client with
  `core.autocrlf` enabled rewrote the working copy, and the next build produced a *mixed* CRLF/LF
  artifact. Three separate paths carried it, and finding one is not finding them all: the source
  file read, the generated header (a literal in the generator's own source file, so it inherited
  *that* file's checkout), and a human-authored text input embedded verbatim into a JSON string
  value, where CRLF escaped as `\r\n` and changed the bytes. Fix at every boundary text enters:
  canonicalize on read, canonicalize any literal the tool itself contributes, and assert the
  finished artifact contains only the canonical form rather than trusting that canonicalization
  held. Then declare the policy in version control too (`.gitattributes` with an explicit `eol`
  for the build inputs), which both stops the rewrite happening and - a useful side effect - makes
  the version-control system itself flag a corrupted working copy as dirty, so provenance refuses
  the build instead of proceeding silently.
- **A loose "does this forbidden value appear anywhere" leak-check false-positives on legitimate
  content.** Twice, independently, in two different generators for this exact pattern: a runtime
  string or a curated text field that happens to *mention* a Dev-only identity as ordinary prose (a
  help string, a changelog line) tripped a leak-check built as a blunt substring scan. The fix both
  times was the same: check the *specific location* an allowlist entry targets for its exact
  pre-substitution value, not "does this substring appear anywhere in the whole output." For
  structured data this is nearly free (walk the real path); for source text, derive the check from
  each allowlist entry's own exact *declaration* text (the whole assignment line, not just the bare
  value), which is precise enough not to collide with unrelated prose containing the same bare word.
- **Git's own change-detection can be deliberately fooled.** `git status`/`git ls-files` alone are
  not sufficient proof of content identity - see step 4 of section 1. This was caught only by
  deliberately setting `assume-unchanged` on a tracked file, modifying it, and confirming the naive
  checks stayed silent while the direct hash comparison still caught it.
- **A pre-publish "nothing changed" recheck is only as good as its position in the gate sequence.**
  Placing it before a slow external gate (rather than after) leaves the exact race window it exists
  to close open for that gate's entire duration - a real finding from review, not a hypothetical.
  Test this with a deterministic seam (an injectable hook that mutates the source at a known point
  in the sequence), not a timing-dependent race that may or may not land in the window on any given
  run.
- **Shelling out from a JVM language needs two Windows-specific fixes**, easy to miss until a test
  suite actually exercises a subprocess call on Windows: a bare interpreter name (`groovy`, `node`,
  etc.) is often a shell script `ProcessBuilder` cannot launch directly - use the platform's `.bat`
  wrapper explicitly. And the *inherited* environment's `JAVA_HOME` can be stale or wrong even though
  the currently-running JVM is perfectly fine - set the child process's `JAVA_HOME` explicitly from
  `System.getProperty('java.home')` rather than trusting whatever was inherited.
- **A dynamically-typed language's string types aren't always interchangeable where a strict API is
  called.** Groovy's `GString` (from `"text ${var}"` interpolation) is not a `java.lang.String`, and
  `ProcessBuilder`'s internal array handling can throw on a `GString` where a real `String` is
  required, with no automatic coercion. Concatenate with `+` or call `.toString()` explicitly for any
  value that crosses into a strict Java API boundary, not just string interpolation.

## Reference implementation

Automation Map's `tools/production-builder/` (private production-manifest/production-profile/
production-package scripts, plus their own test suites) is the concrete, load-bearing implementation
this document generalizes from. Not reproduced here verbatim - the exact allowlist entries, file
layout, and validation gates are specific to that project's own source shape. Read this document for
the pattern; read that project's own tooling and its test suites for a complete worked example of
every principle above actually implemented and independently reviewed.
