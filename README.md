# Care For Them - Terrafirmacraft Integration
Integration of the features of Terrafirmacraft into Care For Them

This mod adds new Needs to Care For Them that use features from Terrafirmacraft. These needs rely on Xoonglins
living in certain climates, based on the temperature and rainfall; as well as proximity to heat sources.

It also adds [need conditions](#need-conditions) based on TFC's calendar, climate and geology, which can be used in the
`active_when` list of any need, including the ones from Care For Them itself.

All the needs below accept CFT's common need properties, such as `hidden`, `bonus`, `icon` and `active_when`;
see the Care For Them documentation for details.

## Rainfall Need

A simple need that checks what is the nominal rainfall in the location of the Xoonglin.

<details>
    <summary>Sample rainfall need file</summary>

```json
{
  "type": "cftfc:rainfall",
  "damage": 0.4,
  "damage_threshold": 0.5,
  "provided_happiness": 5,
  "satisfaction_threshold": 0.75,
  "frequency": 0.1,
  "min_rainfall": 300,
  "max_rainfall": 400
}
```

- `type`: Must be `"cftfc:rainfall"` to indicate this is a rainfall need.
- `damage`: Amount of damage per second if the need is unsatisfied.
- `damage_threshold`: The Xoonglin will receive damage if satisfaction falls below this level.
- `provided_happiness`: Happiness provided per Minecraft day (20 real minutes) if the need
  is satisfied.
- `satisfaction_threshold`: If satisfaction is above this level, the need is considered
  _satisfied_.
- `frequency`: In Minecraft days, how long it takes for the satisfaction of this need to
  go from 1 to 0.
- `hidden`: An optional boolean indicating if this need should be hidden from interfaces. Its value is `false`
  by default.
- `bonus`: _(Optional, default: false)_ If true, the need only adds happiness when satisfied and never
  subtracts it when unsatisfied — useful for festivals or luxuries.
- `min_rainfall`: The minimum value of the rainfall in the Xoonglin's location for the need to be satisfied.
- `max_rainfall`: The maximum value of the rainfall in the Xoonglin's location for the need to be satisfied.
</details>

## Temperature Need

This need checks the average temperature on the Xoonglin's location, which has to be in the given range.

It is also possible to specify ranges for each season, which will take into consideration the actual temperature.

<details>
    <summary>Sample temperature need file</summary>

```json
{
  "type": "cftfc:temperature",
  "damage": 0.4,
  "damage_threshold": 0.5,
  "provided_happiness": 5,
  "satisfaction_threshold": 0.75,
  "frequency": 0.1,
  "min_average_temperature": 20,
  "max_average_temperature": 50,
  "seasonal_limits": {
    "min_spring_temperature": 0.0,
    "max_spring_temperature": 15.0,
    "min_summer_temperature": 10.0,
    "max_summer_temperature": 30.0,
    "min_fall_temperature": 5.0,
    "max_fall_temperature": 20.0,
    "min_winter_temperature": -10.0,
    "max_winter_temperature": 5.0
  }
}
```
- `type`: Must be `"cftfc:temperature"` to indicate this is a temperature need.
- `min_average_temperature`: The minimum value of the average temperature in the Xoonglin's location for the need to be satisfied.
- `max_average_temperature`: The maximum value of the average temperature in the Xoonglin's location for the need to be satisfied.
- `min_season_temperature` and `max_season_temperature`: The range of temperature for each season.
</details>

## Heat Source Need

This need checks for nearby TFC heat-producing blocks (firepits, forges, crucibles, etc.) within a configurable
radius. It can optionally only activate when the ambient temperature drops below a threshold, making it ideal for
cold-climate social classes that need their Xoonglins to stay warm by a fire.

The need checks the actual temperature of the heat source, so an unlit or cold firepit won't satisfy it.

<details>
    <summary>Sample heat source need file</summary>

```json
{
  "type": "cftfc:heat_source",
  "damage": 0.3,
  "damage_threshold": 0.5,
  "provided_happiness": 4,
  "satisfaction_threshold": 0.75,
  "frequency": 0.1,
  "search_radius": 12,
  "temperature_threshold": 10.0,
  "min_source_temperature": 200.0
}
```
- `type`: Must be `"cftfc:heat_source"` to indicate this is a heat source need.
- `search_radius`: The radius (in blocks) around the Xoonglin to search for heat sources.
- `temperature_threshold`: Optional. If set, the need is automatically satisfied when the ambient temperature is
  at or above this value. If omitted, the need is always active regardless of ambient temperature.
- `min_source_temperature`: Optional (defaults to 0). The minimum temperature (in °C) that a heat source must have
  to count. A typical lit firepit reaches around 300°C.
- `max_source_temperature`: Optional (defaults to no limit). The maximum temperature a heat source can have
  to count. Can be used to prevent satisfaction near extremely hot sources like blast furnaces.
</details>

## Need Conditions

Conditions go in the `active_when` list of a need. While its conditions don't hold, a need is inactive: it is not
required, and shows as "Not needed right now". They can be combined with Care For Them's own conditions, such as
`cft:any_of`, `cft:not`, `cft:time` or `cft:weather`.

All conditions are evaluated at the Xoonglin's position. Calendar conditions take TFC's hemispheres into account, so
"winter" means the local winter, wherever the Xoonglin lives.

For instance, this makes Care For Them's built-in warmth need only apply when it is actually cold, or in winter:

```json
"active_when": [
  {
    "type": "cft:any_of",
    "conditions": [
      { "type": "cftfc:temperature", "max": 5.0 },
      { "type": "cftfc:season", "seasons": "winter" }
    ]
  }
]
```

### Season

```json
{ "type": "cftfc:season", "seasons": ["fall", "winter"] }
```
- `seasons`: A season or list of seasons: `spring`, `summer`, `fall` or `winter`.

### Month

```json
{ "type": "cftfc:month", "months": ["september", "october"] }
```
- `months`: A month or list of months, from `january` to `december`.

### Temperature

```json
{ "type": "cftfc:temperature", "min": 0.0, "max": 25.0, "mode": "instant" }
```
- `min`, `max`: The temperature range (in °C), both inclusive. At least one of them is required.
- `mode`: _(Optional, default: `instant`)_ `instant` checks the current temperature, which changes with the time of
  day and the season. `average` checks the yearly average temperature of the location.

### Climate

```json
{ "type": "cftfc:climate", "climates": ["af", "am", "aw"] }
```
- `climates`: A Köppen climate classification or list of them, as shown in TFC's debug screen (F3). For instance:
  `af` (tropical rainforest), `bwh` (hot desert), `cfb` (oceanic), `dfc` (subarctic) or `et` (tundra).

### Rock

```json
{ "type": "cftfc:rock", "categories": "metamorphic", "rocks": ["tfc:rock/raw/granite"] }
```
- `categories`: A TFC rock category or list of them: `igneous_extrusive`, `igneous_intrusive`, `metamorphic` or
  `sedimentary`.
- `rocks`: A raw rock block, block tag (prefixed with `#`), or a list of them.

At least one of `categories` or `rocks` is required. The condition holds if the rock layer under the Xoonglin matches
any of them.

## Jobs

### Firekeeper

The firekeeper keeps the TFC fires around its woodshed burning. It does rounds: it checks which fires within its
radius need tending, takes fuel from the woodshed's chests, walks to each of those fires (nearest first), fills them up
with fuel and lights them again if they've gone out. Then it brings back the fuel it has left and waits at the woodshed
until the next round.

A fire needs tending when it's lit but holds fewer fuel items than `min_fuel`, or when it has gone out (if `relight`
is on). This pairs well with the [Heat Source Need](#heat-source-need): a firekeeper keeps the firepits by the homes
burning through the winter.

The sample woodshed (`cftfc:woodshed`) is an open-air yard fenced with fences and fence gates, with a log pile as its
key block and at least one wooden chest to keep the fuel in.

<details>
    <summary>Sample firekeeper job file</summary>

```json
{
  "type": "cftfc:firekeeper",
  "hours_per_day": 8.0,
  "required_structure": "cftfc:woodshed",
  "radius": 32,
  "min_fuel": 2,
  "carry": 8
}
```
- `type`: Must be `"cftfc:firekeeper"`.
- `hours_per_day`: How many hours a day it works.
- `required_structure`: The structure it works from, and keeps its fuel in.
- `radius`: _(Optional, default: 32)_ How far from the structure's key block the fires it tends can be.
- `fires`: _(Optional, default: `["tfc:firepit", "tfc:grill", "tfc:pot"]`)_ The fire blocks it tends, as block ids or
  tags (prefixed with `#`). Firepits (with or without a grill or pot) and charcoal forges (`tfc:charcoal_forge`) are
  supported. Forges are left out by default, so the firekeeper doesn't keep a smith's forge burning charcoal while
  nobody uses it.
- `fuel`: _(Optional, default: any fuel)_ A list of item ingredients it may use as fuel, such as
  `[{"tag": "minecraft:logs"}]`. It only ever uses TFC firepit or forge fuel, and only what each fire accepts.
- `min_fuel`: _(Optional, default: 2)_ A lit fire is refueled when it holds fewer fuel items than this. A firepit holds
  4 fuel items, and a charcoal forge 5.
- `relight`: _(Optional, default: true)_ Whether it lights fires that have gone out. Turn it off if players put out
  their fires on purpose.
- `carry`: _(Optional, default: 8)_ How many fuel items it takes from the woodshed for a round.

It also accepts Care For Them's common job properties, such as `required_needs`, `min_happiness` or `schedule`.
</details>

### Charcoal Burner

The charcoal burner makes charcoal in a TFC charcoal pit. It fills the holes of the pit with log piles, covers them,
and lights the pit once every hole holds a full, covered log pile. When TFC has turned the logs into charcoal, it
uncovers each hole, digs the charcoal out into the yard's chests, fills the hole with a new log pile and covers it
again. It fetches the logs and cover blocks it needs from the yard's chests. Together with the
[firekeeper](#firekeeper), it keeps a settlement supplied with fuel.

The sample charcoal pit (`cftfc:charcoal_pit`) is an open-air yard fenced with fences and fence gates, with a firepit as
its key block and at least one wooden chest. To make the pit itself, dig holes **two blocks deep** in the yard's
ground: the log pile goes at the bottom of the hole, and its cover on top, level with the ground. Holes can be single
or side by side, as long as each of them is closed in by non-flammable blocks (such as dirt or stone) on every side and
below; holes that would let the fire out are ignored, and so is any hole that can't be reached from solid ground beside
it. The burner only lights the pit when every hole in it is ready, and waits for logs or cover blocks otherwise.

<details>
    <summary>Sample charcoal burner job file</summary>

```json
{
  "type": "cftfc:charcoal_burner",
  "hours_per_day": 8.0,
  "required_structure": "cftfc:charcoal_pit",
  "logs_per_pile": 16,
  "carry": 64
}
```
- `type`: Must be `"cftfc:charcoal_burner"`.
- `hours_per_day`: How many hours a day it works.
- `required_structure`: The structure whose ground holds the pit, and whose chests hold its logs, cover blocks and
  charcoal. Pits are holes below an open-air yard's ground, so this should be an open-air platform whose
  `surface_blocks` allow both air (open holes) and the cover blocks.
- `logs_per_pile`: _(Optional, default: 16)_ How many logs it puts in each log pile, up to 16. More logs make more
  charcoal: TFC turns each log into a quarter to half a piece of charcoal.
- `logs`: _(Optional, default: any log)_ A list of item ingredients it may stack in log piles. It only ever uses logs
  TFC accepts in log piles.
- `cover`: _(Optional, default: `[{"tag": "tfc:dirt"}]`)_ A list of item ingredients it may cover the log piles with.
  Only blocks that keep the fire in (non-flammable, with a full face) are used.
- `carry`: _(Optional, default: 64)_ How many logs it takes from the chests at once.

It also accepts Care For Them's common job properties.
</details>

### Miller

The miller grinds at the TFC querns of its mill: it loads them with grain (or anything else with a quern recipe) from
the mill's chests, turns them, and stores what comes out in the same chests. When a handstone wears out, it puts a new
one from the chests on the quern. With several querns, it works them one at a time, walking to whichever has work.
Querns turned by a water wheel or windmill grind on their own, so it only loads and empties those.

The sample mill (`cftfc:mill`) is an enclosed building of logs and planks, with a door, a quern as its key block, up
to 4 querns and at least one wooden chest inside. Keep grain and spare handstones in the chests.

<details>
    <summary>Sample miller job file</summary>

```json
{
  "type": "cftfc:miller",
  "hours_per_day": 8.0,
  "required_structure": "cftfc:mill",
  "load": 16
}
```
- `type`: Must be `"cftfc:miller"`.
- `hours_per_day`: How many hours a day it works.
- `required_structure`: The structure whose querns it works, and whose chests hold what it grinds, what comes out and
  spare handstones.
- `inputs`: _(Optional, default: anything with a quern recipe)_ A list of item ingredients it may grind, such as
  `[{"tag": "c:foods/grain"}]` (TFC's grains). Only items with a quern recipe are ever ground.
- `load`: _(Optional, default: 16)_ How many items it loads into a quern at once. A quern grinds one item every
  4.5 seconds.

It also accepts Care For Them's common job properties.
</details>

### Prospector

The prospector works the TFC sluices around its camp. It does rounds: it checks which sluices within its radius are
running low on ore deposits or have washed something out, takes deposits from the camp's chests, walks to each of those
sluices (nearest first), picks up what they washed out and loads them with deposits. Then it brings what it found back
to the camp's chests, and waits there until the next round.

Only sluices with water running through them count. What it brings back to the camp is only what it picked up at the
sluices, never the Xoonglin's own belongings.

The sample prospector's camp (`cftfc:prospectors_camp`) is an open-air yard fenced with fences and fence gates, with a
TFC workbench as its key block and at least one wooden chest to keep the deposits and what the sluices wash out. Build
the sluices on streams nearby.

<details>
    <summary>Sample prospector job file</summary>

```json
{
  "type": "cftfc:prospector",
  "hours_per_day": 8.0,
  "required_structure": "cftfc:prospectors_camp",
  "radius": 24,
  "min_load": 8,
  "carry": 32
}
```
- `type`: Must be `"cftfc:prospector"`.
- `hours_per_day`: How many hours a day it works.
- `required_structure`: The structure it works from, whose chests hold the deposits and what the sluices wash out.
- `radius`: _(Optional, default: 24)_ How far from the structure's key block the sluices it works can be.
- `inputs`: _(Optional, default: anything a sluice washes)_ A list of item ingredients it may load into sluices, such
  as `[{"tag": "tfc:ore_deposits"}]`.
- `min_load`: _(Optional, default: 8)_ A sluice is loaded again when it holds fewer deposits than this. A sluice holds
  up to 32.
- `carry`: _(Optional, default: 32)_ How many deposits it takes from the camp for a round.

It also accepts Care For Them's common job properties.
</details>