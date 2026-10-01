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