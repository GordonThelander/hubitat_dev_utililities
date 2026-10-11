# How Rule Machine 5.1 stores each action

**What this is.** A reference for how Rule Machine 5.1 stores each action type in a rule's `settings` and
`state`: which keys an action writes, what values they hold, and which of them read the wrong way round.
It is the action-by-action companion to [the Rule Machine 5.1 storage format reference](rule_machine_5_1_storage_format.md), which
covers the format as a whole (the two stores, key families, conditional visibility, stale companions,
`state.eval`) and is not repeated here.

**Measured, not inferred.** Almost every row comes from throwaway probe rules built on the hub's virtual
test devices through the community MCP Rule Server, read back with `hub_get_app_config(includeSettings=true)` and
set against Rule Machine's own rendered paragraph for the same rule. Those measurements were made on 2026-10-10 and 2026-10-11 (firmware 2.5.2.135 for the negatives in
[section 4](#4-does-not-exist-or-not-storable)). The remaining rows are the shapes Automation Map's decoder
reads (`hamDetailActionOperands` in `apps/automation_map.groovy`), whose comments record earlier hub
measurements, mostly 2026-10-06 and 2026-10-07; those rows say "decoder" in their notes. Where the two
sources disagree, the row says so.

**Notation.** `<n>` is the action index, as in `actType.<n>`. `<m>` is a mode id (`2`, `5`). `<p>` is a
parameter slot. A key written `dimA.<n>` is the literal `dimA.` followed by the index.

**What it is not.** Not complete. A subtype appears here only if a probe wrote it or the decoder reads it.
Rule Machine's `actType` picker offers twelve families and this document names eleven; see
[Limits](#limits).

---

## 1. The general shape

### 1.1 Type and subtype

Every action writes two keys:

| Key | Holds |
|---|---|
| `actType.<n>` | The family, as picked from the first menu: `switchActs`, `dimmerActs`, … |
| `actSubType.<n>` | The subtype, a method name: `getOnOffSwitch`, `getFadeDimmer`, … |

`actSubType.<n>` is what decides the action; `actType.<n>` is redundant beside it
([the storage format reference](rule_machine_5_1_storage_format.md)).
The family is still worth knowing, because several subtypes live where their names do not suggest: colour
and colour temperature are `dimmerActs`, shades and fans are `sceneActs`, HSM, garage doors, valves and
thermostats are `lockActs`, and file actions are `modeActs`.

Every other key the action writes carries the same index as its last segment (`dimLA.<n>`), with two
exceptions in [1.4](#14-per-mode-keys).

**Device-valued settings are a map of device id to label**, for example `{"1234":"Test dimmer"}`, not a bare
id list. This holds for every device picker measured. Two lists of ids are not devices in that
sense: the scene keys `sceneAct.<n>` and `scene<m>.<n>` store a JSON list of activator device ids, and
mode lists are a JSON list of mode-id strings.

All other values are stored as strings, booleans included (`"true"` / `"false"`).

### 1.2 `state.actions`

`state.actionList` gives the actions in Rule Machine's order. The number is identity, not position:
reordering rewrites the list, and deleting an action leaves a gap. `state.disabledActions` lists the
indices Rule Machine renders as "Disabled:"; such an action stays in `actionList`.

`state.actions` holds one entry per index. Measured fields:

| Field | Meaning |
|---|---|
| `method` | The subtype, the same as `actSubType.<n>` |
| `indent` | `""` or `"\t"`, the block structure as RM keeps it. Not the rendered indentation: `getEndRepeat` and `getUntil` store `"\t"` and render at the margin, and every END-IF sits one level deeper than its IF. Derive nesting from the method names |
| `quick` | `true` or `false`; meaning not measured |
| `delay` | `"h:mm:ss"`. The interval on `getRepeat` and `getWhile` (a copy of the `repeat*` setting), and the **only** home of a Wait for Expression's or Wait for Events' time |
| `rule` | An **expression group id**, on IF, ELSE-IF, Repeat While (`getWhile`), Repeat Until (`getUntil`) and Wait for Expression |
| `cond` | A **condition slot**, on a Simple Conditional Action's THEN action |

`rule` and `cond` are different things. `rule` names an expression group, the key of an entry in
`state.eval` ([the storage format reference](rule_machine_5_1_storage_format.md)).
`cond` names one condition directly, by the slot number of the condition pool in settings
(`rCapab_<c>`, `rDev_<c>`, `state_<c>`, `not<c>`). Slot numbers are independent of action indices: a
condition added from action 6 was given slot 4.

**Neither binding exists in settings.** A reader of settings alone sees a While or Until with no
expression, and a Simple Conditional Action as an unconditional action.

### 1.3 Per-action delay

A delay attached to an action ("--> delayed: 0:00:04(cancelable)" under the action) is not a Delay step.
Decoder:

| Key | Holds |
|---|---|
| `delayAct.<n>` | The delay's format. Empty or `none` means no delay |
| `delaySec.<n>` | The delay in seconds |
| `cancelAct.<n>` | `true` when cancelable |

On Wait for Expression and Wait for Events, `delayAct.<n>` is the wait's own duration format, not a delay
before the action, and the time is in `state.actions[<n>].delay`, not in `delaySec`.

### 1.4 Per-mode keys

A per-mode action stores its modes as a JSON list of mode-id strings (`switchModes.<n> = ["2","5"]`) and
one value key per mode, with the mode id appended to the stem and the action index last:

```
switchModes.7 = ["2","5"]
switch2.7     = off
switch5.7     = on
```

Two keys break that rule by putting the index **inside** the key, with a suffix after it:

| Key | Subtype | Example |
|---|---|---|
| `butM<m>.<n>Op` | `getPushButtonPerMode`, the event per mode | `butM5.6Op = push` |
| `ctMode<m>.<n>Level` | `getColorTempPerMode`, the level per mode | `ctMode2.7Level = 30` |

A reader that takes the text after the last `.` as the index misparses both.

One per-mode action keys on the mode **name** instead of its id. Delay per mode stores
`delaySecond<ModeName>.<n>`, `delayMinute<ModeName>.<n>` or `delayHour<ModeName>.<n>`
(`delaySecondHome.<n>`), while its mode list is still ids. A reader must resolve id to name for this
subtype alone, and renaming a mode orphans the key.

### 1.5 Booleans that read the wrong way

Several flags store `false` for the **first** option in Rule Machine's menu and `true` for the second, so
the obvious reading of the name inverts the action. Checked against RM's rendering in every case.

| Key | `false` (or absent) | `true` | Reads as named? |
|---|---|---|---|
| `dimRL.<n>` | raise | lower | no |
| `shadeRL.<n>` | open | close | no |
| `lockRL.<n>` | lock | unlock | no |
| `valveRL.<n>` | open | close | no |
| `garageRL.<n>` | open (decoder: empty is open) | close | no |
| `disEn.<n>` | disable | enable | yes, once known |
| `ssZ.<n>` | start Z-Wave polling | stop | **no** |
| `mU.<n>` | **mute** | **unmute** | **no: `true` renders "Unmute"** |
| `pvTF.<n>` | absent: sets **True** | sets **False** | **no** (decoder) |
| `dimAdjR.<n>` | up | down | yes (decoder) |
| `pR.<n>` | empty: pause | resume | yes (decoder) |
| `onOff.<n>` | off | on | yes |
| `dimFadeUp.<n>`, `ctFadeUp.<n>` | down | up | yes |
| `stopRepeat.<n>` | not stoppable | stoppable | yes |
| `durChoice.<n>` | timeout | Use Duration | yes (decoder) |

The trap is the mix: the two `*FadeUp` flags read naturally while the `*RL` flags read as "second option".
Treating them alike inverts actions. `mU` is inverted against its own name. The MCP Rule Server's
`capability:'mute'` helper maps mute and unmute the wrong way round, so a rule written through it as
"mute" genuinely stores and renders Unmute. Its `zwavePoll` helper has the same fault: "start" stores `ssZ` true, which renders Stop.

---

## 2. Subtypes by family

Keys are the ones the subtype writes beyond `actType.<n>` and `actSubType.<n>`. "Devices" means a device
map as in [1.1](#11-type-and-subtype).

### 2.1 `switchActs`

The live picker offers eight subtypes, all listed here.

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getOnOffSwitch` | Turn switches on or off | `onOffSwitch.<n>` devices; `onOff.<n>` `true` on, `false` off | Options `optSwitch.<n>` ("command only switches that are on/off"), `trackSwitch.<n>` and `useLastDev.<n>` change the action; see [the storage format reference](rule_machine_5_1_storage_format.md). Also the stored form of a Simple Conditional Action and of scene or Room Lighting activation by activator device |
| `getToggleSwitch` | Toggle switches | `toggleSwitch.<n>` devices | Nothing else stored |
| `getFlashSwitch` | Flash switches | `flashSwitch.<n>` devices | Decoder; fields not measured in the probe pass |
| `getModeSwitch` | Switches per mode | `switchM.<n>` devices; `switchModes.<n>` mode ids; `switch<m>.<n>` `on` / `off` | |
| `getChooseSwitch` | Choose switches by mode | `chooseModes.<n>` mode ids; `chooseSwOn<m>.<n>` and `chooseSwOff<m>.<n>` devices, one pair per mode | |
| `getPushButton` | Push, hold, double tap or release a button | `pushButton.<n>` devices; `pushButNo.<n>` button number as a string (`"1"`); `pushButOp.<n>` `push` / `hold` / `doubleTap` / `release` | One subtype for all four events. Values are the command verbs, not the event names a device emits (`held`, `doubleTapped`, `released`). `varButNo.<n>` ("Variable button number?") appears beside it; unset in every probe |
| `getPushButtonPerMode` | Push a button per mode | `pushMBtn.<n>` devices, one picker for all modes; `pushModes.<n>` mode ids; `butM<m>.<n>` button number; `butM<m>.<n>Op` event | Irregular key, [1.4](#14-per-mode-keys). Same event vocabulary as `pushButOp` |
| `getChooseButton` | Choose button by mode | `chooseModes.<n>` mode ids; `chooseButton<m>.<n>` devices; `chooseButNo<m>.<n>` button number; `chooseButAct<m>.<n>` event | Device, number and event all per mode, index last as usual. Shares `chooseModes` with `getChooseSwitch` |

### 2.2 `dimmerActs`

Levels, colour and colour temperature. The live picker offers sixteen subtypes, all listed here.

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getSetDimmer` | Set level | `dimA.<n>` devices; `dimLA.<n>` level; `dimRA.<n>` fade in seconds | Renders "Dim: …: 60 --> fade: 5". Level from a variable: `uVar.<n>` `true`, `xVar.<n>` variable name, and **no** `dimLA.<n>` |
| `getToggleDimmer` | Toggle dimmers | `dimA.<n>` devices; `dimLA.<n>` level to turn on at | |
| `getAdjustDimmer` | Adjust level up or down | `dimA.<n>` devices; `dimAdj.<n>` amount, unsigned; `dimAdjR.<n>` `true` down | Decoder |
| `getDimmersPerMode` | Set level per mode | `dimM.<n>` devices; `dimmerModes.<n>` mode ids; `level<m>.<n>` level | |
| `getFadeDimmer` | Fade level to a target over time | `dimFade.<n>` devices; `dimFadeUp.<n>` `true` up, `false` down; `dimFadeTarget.<n>` level; `dimFadeTime.<n>` **minutes** | Renders "Raise … up to 90 over 2 minutes with 10 seconds interval". The direction only says which way the target lies |
| `getStopFade` | Stop dimmer fade | none | |
| `getRLDimmer` | Start raising or lowering | `dimRaiseLower.<n>` devices; `dimRL.<n>` `false` raise, `true` lower | Inverted, [1.5](#15-booleans-that-read-the-wrong-way). Needs `ChangeLevel` on the device; no built-in virtual driver has it |
| `getStopDimmer` | Stop raising or lowering | `dimStop.<n>` devices | |
| `getSetColor` | Set colour | `bulbs.<n>` devices; `color.<n>` the picker **mode** (a named colour, `Custom HSB color`, `Custom RGB color`, …); `colorHex.<n>` the numeric hue (0–100); `colorSat.<n>` saturation; `colorH.<n>` the hex string; `colorLevel.<n>` level | Decoder. `colorHex` and `colorH` are named the opposite way round from their contents. `color.<n>` decides which other keys are live. `uVar`, `uVar2`, `uVar3`, `uVar4` mark level, hue, saturation and RGB as taken from a variable |
| `getToggleColor` | Toggle colour | `bulbsTog.<n>` devices; `colorTog.<n>` capitalised colour name (`Green`); `colorTogLevel.<n>` level | |
| `getColorPerMode` | Set colour per mode | `bulbsM.<n>` devices; `colorModes.<n>` mode ids; `color<m>.<n>` colour name (`Blue`, `Red`); `colorLevel<m>.<n>` level | |
| `getSetColorTemp` | Set colour temperature | `ct.<n>` devices; `ctL.<n>` kelvin; `ctLevel.<n>` level | Decoder |
| `getToggleColorTemp` | Toggle colour temperature | `ctTog.<n>` devices; `ctLTog.<n>` kelvin; `ctTogLevel.<n>` level | Setting `ctTogLevel.<n>` reveals `ctFade.<n>`, a fade time. The same key name is `getFadeCT`'s device picker: read the subtype first |
| `getColorTempPerMode` | Set colour temperature per mode | `ctM.<n>` devices; `ctModes.<n>` mode ids; `ctMode<m>.<n>` kelvin; `ctMode<m>.<n>Level` level | Irregular key, [1.4](#14-per-mode-keys) |
| `getFadeCT` | Fade colour temperature over time | `ctFade.<n>` devices; `ctFadeUp.<n>` `true` up; `ctFadeTarget.<n>` kelvin; `ctFadeTime.<n>` minutes | Same shape as `getFadeDimmer` |
| `getStopCTFade` | Stop colour temperature fade | none | Renders "Stop temperature color changes" |

The named colours `color.<n>`, `colorTog.<n>` and `color<m>.<n>` hold are Rule Machine's own names. What it
sends for each was measured on a virtual RGBW light and is recorded in the decoder (`RM_NAMED_COLOURS`)
and in [the storage format reference](rule_machine_5_1_storage_format.md), section 7.5.

### 2.3 `sceneActs`

Shades, fans and scenes. The live picker offers seven subtypes, all listed here.

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getRLShade` | Open or close shades | `shadeOpenClose.<n>` devices; `shadeRL.<n>` `false` open, `true` close | Inverted, [1.5](#15-booleans-that-read-the-wrong-way) |
| `getStopShade` | Stop shades | `shadeStop.<n>` devices | A subtype of its own, not a value of `shadeRL` |
| `getShadePosition` | Set shade position | `shadePosition.<n>` devices; `shadeLevel.<n>` position | |
| `getFanSpeed` | Set fan speed | `fanDevice.<n>` devices; `fanSpeed.<n>` the driver's lowercase literal: `low`, `medium-low`, `medium`, `medium-high`, `high`, `on`, `off`, `auto` | |
| `getAdjustFan` | Cycle fan speed | `fanAdjust.<n>` devices | No speed field |
| `getActivateScene` | Activate scenes | `sceneAct.<n>` JSON list of activator device ids | A scene is a Scene Activator **device** (capabilities `PushableButton`, `Switch`) spawned by the scene app. Activating it is `on()` |
| `getActivateScenePerMode` | Activate scenes per mode | `sceneModes.<n>` mode ids; `scene<m>.<n>` JSON list of activator device ids | Ordinary per-mode key shape |

### 2.4 `lockActs`

Rule Machine labels the family "Control HSM, Garage Doors, Locks, Valves or Thermostats". The live picker
offers five subtypes, all listed here. The garage and valve subtypes appear only when the hub has a
matching device, and `getSetHSM` only when HSM is installed
([the storage format reference](rule_machine_5_1_storage_format.md)).

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getLULock` | Lock or unlock | `lockLockUnlock.<n>` devices; `lockRL.<n>` `false` lock, `true` unlock | One subtype for both directions |
| `getOCValve` | Open or close valves | `valveOpenClose.<n>` devices; `valveRL.<n>` `false` open, `true` close | |
| `getOCGarage` | Open or close garage doors | `garageOpenClose.<n>` devices; `garageRL.<n>` `false` (or empty) open, `true` close | Decoder: `onOff.<n>` is sometimes present beside it, also `true` for close; if the two disagree the decoder publishes no direction |
| `getSetHSM` | Control HSM | `alarm.<n>` one of `armAway`, `armHome`, `armNight`, `disarm`, `disarmAll`, `armRules`, `cancelAlerts` | No device field. Renders "HSM Arm Away", …, "HSM Arm All HSM Rules", "HSM Cancel All Alerts" |
| `getSetThermostat` | Set thermostats | `thermo.<n>` devices, then exactly one of: `thermoMode.<n>` (`heat`, `cool`, `auto`, `off`, the driver's lowercase literal); `thermoFan.<n>` fan mode (`circulate`); `thermoSetHeat.<n>` (`21.5`); `thermoSetCool.<n>` (`24.5`); `thermoAdjHeat.<n>` (`2`); `thermoAdjCool.<n>` (`-1.5`) | Adjustments are signed and keep the minus sign. The whole thermostat surface: there is no scheduler action |

### 2.5 `soundActs`

The live picker offers six subtypes: `getSetMusicPlayer`, `getSetVolume`, `getMuteUnmute`, `getTone`,
`getChime`, `getSiren`.

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getSetMusicPlayer` | Control a music player | `player.<n>` devices; `playerCommand.<n>` one of 17 words; then by command: `playerLevel.<n>` (set volume, with `uVar.<n>` / `xVar.<n>` for a variable), `playerTrack.<n>` (play, set, restore and resume track), `playerText.<n>` (play text) | The 17 words: play, pause, toggle play/pause, stop, mute, unmute, toggle mute, raise volume, lower volume, set volume, next track, previous track, play track, restore track, resume track, set track, play text. Play text uses `playerText`, not `playerTrack` |
| `getSetVolume` | Set volume | `volume.<n>` devices; `volumeVal.<n>` level | A subtype of its own, separate from the player's set volume |
| `getMuteUnmute` | Mute or unmute | `muteUnmute.<n>` devices; `mU.<n>` `false` mute, `true` **unmute** | Inverted against its name, [1.5](#15-booleans-that-read-the-wrong-way) |
| `getTone` | Sound a tone | not measured | Offered by the picker; no probe wrote one. A `toner.<n>` key can survive beside another subtype as residue |
| `getChime` | Play or stop a chime | `chime.<n>` devices; `chimePlayStop.<n>` `Play Sound` or a stop; `chimePlaySound.<n>` sound number | Decoder. `siren.<n>` at the same index can hold a different device left from an earlier siren action; only `chime.<n>` is live |
| `getSiren` | Control sirens | `siren.<n>` devices; `sirenAct.<n>` `siren` / `strobe` / `both` / `off` | Renders "Siren: … --> siren" |

### 2.6 `deviceActs`

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getRefreshSwitch` | Refresh devices | `refresh.<n>` devices | |
| `getCapture` | Capture device states | `capture.<n>` devices | |
| `getRestore` | Restore captured states | none | Restores whatever the rule's own Capture took |
| `getDisable` | Disable or enable devices | `devDisable.<n>` devices; `disEn.<n>` `false` disable, `true` enable | One subtype for both. Renders "Disable: …" / "Enable: …" |
| `getStartStopZPoll` | Start or stop Z-Wave polling | `ssZPoll.<n>` the Z-Wave devices; `ssZ.<n>` `false` **start**, `true` **stop**; `ssSD.<n>` meaning not measured (both values render the same) | Measured on 2026-10-11 against RM's rendered lines with a real Z-Wave device. A row with no device never commits: it stays an orphan in settings and does not render. The MCP Rule Server's `zwavePoll` helper writes `ssZ` the wrong way round |

### 2.7 `delayActs`

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getDelay` | Delay | `delayHour.<n>`, `delayMinute.<n>`, `delaySecond.<n>` (`delaySecond.<n> = 30`); `randomAct.<n>` random; `cancelAct.<n>` `true` cancelable | |
| `getDelay`, from a variable | Delay by a variable's value | `uVar.<n>` `true`; `xVar.<n>` variable name | The three unit fields disappear when `uVar` is set and none is written. The value is read as **seconds**: a variable holding 42 renders "(0:00:42)". A stale `xVar` left after `uVar` was switched off does not override a stored time (decoder) |
| `getDelayPerMode` | Delay per mode | `delayModes.<n>` mode ids; one of `delaySecond<ModeName>.<n>`, `delayMinute<ModeName>.<n>`, `delayHour<ModeName>.<n>` per mode (`delaySecondHome.<n> = 20`, `delayMinuteAway.<n> = 3`) | Keyed on the mode **name**, [1.4](#14-per-mode-keys). `cancelAct.<n>` read by the decoder |
| `getCancelDelay` | Cancel delayed actions | none | |
| `getExitRule` | Exit rule | none | |

### 2.8 `repeatActs`

A repeat block is positional, not nested in the settings: the opener, the body as ordinary actions, then
the closer. With a block open, the picker offers only `getEndRepeat`, `getUntil` and `getStopRepeat`, so
blocks cannot nest by accident.

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getRepeat` | Repeat every interval, optionally n times | One of `repeatSecond.<n>`, `repeatMinute.<n>`, `repeatHour.<n>`; `repeatN.<n>` times; `stopRepeat.<n>` `true` stoppable | Only the unit used is written. Renders "Repeat 3 times every 0:00:30 (stoppable)". Closed by `getEndRepeat` or `getUntil` |
| `getWhile` | Repeat While expression | One of `repeatSecond.<n>`, `repeatMinute.<n>`, `repeatHour.<n>` | No `repeatN`, no `stopRepeat`. The expression is `state.actions[<n>].rule` only. Closed by `getEndRepeat` |
| `getUntil` | Repeat Until expression | none | **Closes** the `getRepeat` above it; no END-REP follows. Its interval is the Repeat's. The expression is `state.actions[<n>].rule` only |
| `getEndRepeat` | END-REP | none | The block terminator. An action after it renders outside the block |
| `getStopRepeat` | Stop Repeating Actions | none | A command that halts a running repeat, not a terminator |

The MCP Rule Server's structural check looks for `stopRepeat` as the closer and reports a block closed by
`getEndRepeat` as unclosed. Rule Machine's rendering is the authority.

### 2.9 `condActs`

The family has two subtypes.

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getIfThen` | IF expression THEN | The expression group in `state.actions[<n>].rule` | ELSE-IF, ELSE and END-IF are `getElseIf`, `getElse`, `getEndIf`, created by dedicated buttons; their `actType` is not recorded in these sources |
| `getCondAct` | Simple Conditional Action | **Not stored.** A wizard path only | See below |

A Simple Conditional Action commits as its THEN action's ordinary keys, with `actType.<n>` and
`actSubType.<n>` overwritten by the THEN action's family and subtype. The condition goes in the pool
(`rCapab_<c>`, `rDev_<c>`, `state_<c>`, `not<c>`), and the binding is `state.actions[<n>].cond = <c>`.
Rule Machine renders it "IF (… is on FALSE) On: …" on one line. `useCond.<n>` appears while the editor is
open; a stray `useCond.<n>` left in settings is inert and is not the binding. The THEN picker offers eleven
families, excluding `condActs`, so a Simple Conditional cannot contain another conditional.

### 2.10 `messageActs`

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getMsg` | Send or speak a message | Decoder: `msg.<n>` the text; `note.<n>` notification devices; `speakDevice.<n>` speech devices; `speakVolume.<n>` volume | One step can carry both device lists. `speakVolume` is meaningful only beside speech devices. Confirmed on 2026-10-11 from three rules' raw settings and RM's rendered text: `msg` is the text, `note` and `speakDevice` are devices, and one row can carry both device lists sharing one text. `ranMsg.<n>` appears on every row, empty in every measurement. An early measurement table had `msg` and `note` swapped |
| `getHTTPGet` | HTTP GET | `httper.<n>` the address | |
| `getPingIP` | Ping an address | `pingIP.<n>` the address | |

### 2.11 `modeActs`

Mode, variables, custom actions and files.

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getSetMode` | Set mode | `mode.<n>` a mode **id** | |
| `getSetVariable` | Set a hub or local variable | `xVarV.<n>` the target. Then by type: boolean `valBool.<n>`; number `numOp.<n>` (`number` replaces, `add number` adds) with `valNumber.<n>`, or `variable math` with `xVar3.<n>` `valMathOp.<n>` `xVar4.<n>`; string `valStringOp.<n>` `Set string` with `valString.<n>`, or `Device attribute` with `tCustomAttr.<n>` and `customDev.<n>` | Decoder. `xVar4.<n>` holds the literal `(constant)` when the right operand is a number, which is then in `valConst2.<n>` |
| `getDefinedAction` | Run a custom action | `myCapab.<n>` capability; `devices.<n>` devices; `cCmd.<n>` command; `useLastDev.<n>`; per parameter `cpType<p>.<n>` and `cpVal<p>.<n>`; `meter.<n>` and `meterMillis.<n>` | Parameter slots are numbered independently of the index, need not start at 1 and can have gaps; their order is the argument order. Values stay strings even when they look numeric |
| `getWriteLocalFile` | Write a file | `localFile.<n>` name; `fileContents.<n>` text | |
| `getAppendLocalFile` | Append to a file | `localFile.<n>` name; `fileContents.<n>` text | |
| `getDeleteLocalFile` | Delete a file | `deleteFile.<n>` name | A different key from write and append |

---

## 3. Subtypes whose family is not recorded here

The decoder reads these, from rules on the hub. Neither source records their `actType`.

| Subtype | What it does | Keys and values | Notes |
|---|---|---|---|
| `getSetPrivateBoolean` | Set Private Boolean | `privateT.<n>` (or `privateF.<n>`) target rule ids, `*` for this rule; `pvTF.<n>` absent sets True, `true` sets False; `pvRuleType.<n>` engine | Inverted, [1.5](#15-booleans-that-read-the-wrong-way) |
| `getRuleActions` | Run rule actions | `ruleAct.<n>` or `ruleActMain.<n>` rule ids; `runRuleType.<n>` engine | Two spellings for the same target list |
| `getStopActions` | Cancel rule timers | `stopAct.<n>` rule ids; `stopRuleType.<n>` engine | |
| `getPauseResumeRules` | Pause or resume rules | `pauseRule.<n>` rule ids; `pR.<n>` `true` resume, empty pause; `pauseRuleType.<n>` engine | |
| `getWaitRule` | Wait for Expression | Expression in `state.actions[<n>].rule`; time in `state.actions[<n>].delay` (`"0:10:00"`); `durChoice.<n>` `true` Use Duration, otherwise timeout | No time means wait indefinitely |
| `getWaitEvents` | Wait for Events | Events in a rule-global dash namespace: `tCapab-<e>`, `tDev-<e>`, `tstate-<e>`, `stays-<e>`, `modesX-<e>`, `AlltDev-<e>`; time and `durChoice.<n>` as Wait for Expression | Only one per rule: a second overwrites the first's events |
| `getLogMsg` | Log a message | `logmsg.<n>` text | No level is stored |
| `getComment` | Comment | `comment.<n>` text | |
| `getHTTPPost` | HTTP POST | `httper.<n>` address; `httpPostBody.<n>` body; `httpPostType.<n>` content type | |
| `getPollSwitch` | Poll devices | `poll.<n>` devices | |
| `getElseIf`, `getElse`, `getEndIf` | ELSE-IF, ELSE, END-IF | ELSE-IF carries `state.actions[<n>].rule` | Created by buttons, in no picker |

---

## 4. Does not exist, or not storable

Measured on 2026-10-10 and 2026-10-11.

| Item | Finding |
|---|---|
| **Track event** (dimmer tracks dimmer, switch tracks switch) | Not an action on firmware 2.5.2.135. Absent from the `dimmerActs` picker (16 subtypes) and the `switchActs` picker (8). The only trace is the `trackSwitch.<n>` option on `getOnOffSwitch` |
| **Room Lights action** | No such action. No `actType` family is Room Lighting, and `sceneActs` covers shades, fans and scenes only. A Room Lighting instance, like a scene, is driven through its activator device as an ordinary switch action (`getOnOffSwitch`, `onOffSwitch.<n>`) |
| **Thermostat Scheduler** | No such action. `getSetThermostat` is the only thermostat subtype in `lockActs` |

---

## Limits

**Twelve families, eleven named.** The `actType` picker offers twelve families. This document names
`switchActs`, `dimmerActs`, `sceneActs`, `lockActs`, `soundActs`, `deviceActs`, `delayActs`, `repeatActs`,
`condActs`, `messageActs` and `modeActs`. The twelfth, and the family of each subtype in
[section 3](#3-subtypes-whose-family-is-not-recorded-here), are not recorded in these sources.

**Unmeasured values.** `getTone` has no measured shape; `quick` in `state.actions` has no measured meaning;
`ssSD.<n>` renders the same either way. Rows marked "decoder" were measured on real rules but not
re-probed in that pass.

**One hub.** Conditional visibility ([the storage format reference](rule_machine_5_1_storage_format.md))
applies here as it does to the whole format: a subtype or key that needs a device the hub does not have
cannot be seen.

Related: [the Rule Machine 5.1 storage format reference](rule_machine_5_1_storage_format.md) for the format as a whole,
and [execution and cross-rule causality](rule_machine_execution_and_cross_rule_causality.md) for behaviour rather than storage.
