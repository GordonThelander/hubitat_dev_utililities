# Evidence model and fixture method

## 1. Evidence levels

Evidence attaches to individual constructs and claims, never to a whole piston.

| Level | Meaning | Permitted claim |
| --- | --- | --- |
| L0 | Not present in the fixture corpus | None |
| L1 | Present in the pinned source vocabulary | Name is source-known |
| L2 | Identified by a source-pinned registry entry | Construct identity |
| L3 | Shape proven by source and editor round trip | Structural decoding |
| L4 | Runtime meaning proven by source and a discriminating fixture | A narrow semantic statement |

## 2. Accounting and coverage

Visit every object, list, field and scalar in the decoded document within depth and count budgets.
Report accounting (was everything visited) separately from recognition (was each position known).

Report an unrecognised field as a structural placeholder that shows location and nothing else:

```text
$.s[0].k[0].p[1].exp.<unknown-key#0>
```

Known editor data (`zc`, `data`) is reported as opaque and still traversed for budgets.

Report recognised and unidentified counts. Do not show a completeness percentage while the walk is
truncated or unidentified positions remain.

Implementation: [`snippets/05_bounded_structure_walker.groovy`](snippets/05_bounded_structure_walker.groovy).

## 3. Fixture capture

For each construct, capture the smallest piston that isolates it:

1. Record hub model, platform build, webCoRE version and editor version.
2. Create the piston in the Dashboard editor, paused, using virtual devices or synthetic values.
3. Capture the first save.
4. Reopen without editing, save, capture the round trip.
5. Make one targeted edit, save, capture.
6. Reopen and save the edited piston, capture its round trip.
7. Compare the changes with the editor display and the pinned executor source.
8. Sanitise identifiers and text, keeping types and topology.
9. Store expected classifications beside the fixture.
10. Add a near-neighbour fixture wherever a misclassification is plausible.

First save and round trip differ (architecture section 4.2). Keep both.

## 4. Promotion to L3

All of:

- owner path and discriminator are unambiguous;
- the pinned source contains the producer or consumer path;
- an editor-created fixture saves the shape;
- the shape survives the unchanged round trip;
- a verifier checks the exact fixture against the source assertion;
- sibling shapes do not match the same predicate.

Hand-written JSON tests a decoder. It does not prove the editor produces the shape.

## 5. Promotion to L4

A claim narrow enough to disprove, with:

- a claim identifier;
- the source path that consumes the structure;
- a fixture that exercises the branch;
- a named misreading the evidence rules out;
- a live observation where source and fixture cannot separate interpretations;
- stated limits (fast-forward, policy, restriction, dynamic values).

Example: `statement.switch.default.v1`: the default runs only when no case matched, or in fall-through
when no `break` intervened. Ruled out: the default always runs after the case list.

## 6. Source assertions

Each registry entry carries assertions against the pinned source. Regeneration fails or withdraws the
claim when an assertion stops matching. Generate the runtime registry from reviewed evidence files;
never hand-maintain it separately.

## 7. Fixture sanitisation

Remove device and app identifiers, device, room, person and location names, notification text, URLs,
tokens, variable values, hub addresses and credentials. Replace each with a synthetic value of the
same type (`<string#N>`, `:000...N:`), keep list lengths, branch topology and empty or missing
states, then scan for leftover identifiers and rerun the expected classification.

## 8. Corpus

The corpus is small and constructed. It proves the constructs it exercises. Claims about historical
SmartThings migrations, unusual community patterns or older editor versions need a broader,
sanitised, opt-in corpus.
