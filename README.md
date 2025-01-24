# Care For Them - Terrafirmacraft Integration
Integration of the features of Terrafirmacraft into Care For Them

This mod adds new Needs to Care For Them that use features from Terrafirmacraft. Two new needs relay on Xoonglins
living in certain climates, based on the temperature and rainfall.

## Rainfall Need

A simple need that checks what is the nominal rainfall in the location of the Xoonglin.

<details>
    <summary>Sample rainfall need file</summary>

```json
{
  "id": "cftfc:high_rainfall_need",
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

- `id`: Identifier of this need.
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
  "id": "cftfc:high_temperature_need",
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