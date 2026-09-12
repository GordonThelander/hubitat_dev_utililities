# Function and virtual-command catalogue

Extracted from `func_*` and `vcmd_*` definitions in `webcore-piston.groovy` at the pinned revision,
and compared with the parent app's catalogues. Regenerate when the pin changes.

| Set | Count |
| --- | --- |
| Function handlers (`func_<name>`, lowercase) | 109 |
| Virtual-command handlers (`vcmd_<name>`, case-preserved) | 69 |
| Functions catalogued in the parent without a handler | 1: `dateAdd` |
| Functions with a handler but not catalogued | 5: `bool`, `boolean`, `decimal`, `substr`, `text` |
| Virtual commands with a handler but not catalogued | 2: `internal_fade`, `sendNotificationToContacts` |
| Virtual commands catalogued only when graphs are enabled | 3: `readFuelStream`, `writeFuelStream`, `clearFuelStream` (inside `if(graphsOn())` in `virtualCommands()`) |

`executeRoutine` is SmartThings-only. It has no Hubitat handler or catalogue entry and is not counted.

## Functions (109)

```text
abs
adddays
addhours
addminutes
addseconds
addweeks
age
arrayitem
asin
atan2
avg
bool
boolean
ceil
ceiling
celsius
coalesce
concat
contains
converttemperatureifneeded
cos
count
date
datetime
decimal
dewpoint
distance
encodeuricomponent
endswith
eq
exists
fahrenheit
float
floor
format
formatdatetime
formatduration
ge
gt
hsltohex
if
indexof
int
integer
isbetween
isempty
ispistonpaused
json
lastindexof
le
least
left
length
log
lower
lt
ltrim
matches
max
median
mid
min
monthname
most
newer
not
number
older
parsedatetime
pow
power
previousage
previousvalue
rainbowvalue
random
rangevalue
replace
right
round
roundtimetominutes
rtrim
settzid
setvariable
sin
size
sort
sprintf
sqr
sqrt
startswith
stdev
string
strlen
substr
substring
sum
tan
text
time
title
todegrees
toradians
trim
trimleft
trimright
upper
urlencode
variance
weekdayname
```

## Virtual commands (69)

```text
adjustColorTemperature
adjustHue
adjustInfraredLevel
adjustLevel
adjustSaturation
appendFile
cancelTasks
clearFuelStream
clearTile
deleteFile
emulatedFlash
executePiston
executeRule
fadeColorTemperature
fadeHue
fadeInfraredLevel
fadeLevel
fadeSaturation
flash
flashColor
flashLevel
httpRequest
iftttMaker
internal_fade
lifxBreathe
lifxPulse
lifxScene
lifxState
lifxToggle
loadStateGlobally
loadStateLocally
log
noop
parseJson
pausePiston
readFile
readFuelStream
resumePiston
saveStateGlobally
saveStateLocally
sendEmail
sendNotification
sendNotificationToContacts
sendPushNotification
sendSMSNotification
setAlarmSystemStatus
setHSLColor
setLocationMode
setState
setSwitch
setTile
setTileColor
setTileFooter
setTileOTitle
setTileText
setTileTitle
setVariable
storeMedia
toggle
toggleLevel
toggleRandom
wait
waitForDateTime
waitForTime
waitRandom
wolRequest
writeFile
writeFuelStream
writeToFuelStream
```

## What a name establishes

A handler name establishes vocabulary and implementation presence only. Parameter coercion, device
effects, state, timing, cancellation and runtime success require the handler source and the task's
saved context. Each parent catalogue entry carries a display template (`d`, for example
`Append to file {0}`) and parameter definitions (`p`) that describe the editor's view of the command.
