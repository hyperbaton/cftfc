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

## Nutrition Need

A balanced diet, based on TFC's five nutrients: grain, fruit, vegetables, protein and dairy. The Xoonglin remembers its
last meals, and the need is satisfied while those meals provide enough of each nutrient it needs, according to TFC's
nutrition values of each food (bread gives 1.5 grain, cooked beef 2.5 protein, cheese 3 dairy...).

When the need comes due, the Xoonglin eats the food it carries that best balances its diet, counting that its oldest
meal will be forgotten. It only eats food that keeps its diet balanced or makes it better, so it won't eat its way
through a pile of bread when it lacks fruit. While its diet lacks a nutrient, it fetches food rich in that nutrient from
its home, just like with a goods need. Rotten food never counts.

<details>
    <summary>Sample nutrition need file</summary>

```json
{
  "type": "cftfc:nutrition",
  "damage": 0.2,
  "damage_threshold": 0.25,
  "provided_happiness": 6,
  "satisfaction_threshold": 0.6,
  "frequency": 0.33,
  "nutrients": ["grain", "fruit", "vegetables", "protein"],
  "meals": 5,
  "min_amount": 0.5
}
```
- `type`: Must be `"cftfc:nutrition"` to indicate this is a nutrition need.
- `frequency`: As in other needs, how long its satisfaction lasts; it eats a meal each time the need comes due.
- `nutrients`: A nutrient or list of nutrients its diet has to provide: `grain`, `fruit`, `vegetables`, `protein` or
  `dairy`.
- `meals`: _(Optional, default: 5)_ How many of its last meals it remembers. With fewer meals than nutrients, it can't
  ever be satisfied.
- `min_amount`: _(Optional, default: 0.5)_ How much of each nutrient its remembered meals have to provide together.
</details>

### Nutrient ingredient

CFTFC also adds an ingredient type that matches food rich in a nutrient, which isn't rotten. It can be used wherever an
ingredient is, for instance in a goods need, to ask for any dairy food rather than for a specific item:

```json
"item": { "type": "cftfc:nutrient", "nutrient": "dairy", "min": 1.0 }
```
- `nutrient`: `grain`, `fruit`, `vegetables`, `protein` or `dairy`.
- `min`: _(Optional, default: 0.5)_ How much of the nutrient the food has to provide.

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

### Preserver

The preserver preserves food in its pantry the way TFC does, giving it traits that slow down its decay for good:

- **Salted**: it salts meat and fish from the pantry's chests, using one salt (`tfc:powder/salt`) for each piece.
- **Brined**: it seals fruit, vegetables, meat and fish in barrels of brine.
- **Pickled**: it seals brined food in barrels of vinegar.

It goes from barrel to barrel: it unseals the ones that are done, moves the preserved food to the chests, loads each
barrel with as much food as its liquid covers (TFC uses 125 mB per piece), and seals it again. Between barrels, it
salts food. It only works barrels that hold brine or vinegar, and **keeping them filled is up to you**. Barrels of
anything else in the pantry are left alone, but any barrel of brine or vinegar in it is the preserver's to use.

Food only keeps the traits TFC makes permanent: food kept sealed in vinegar is also "preserved" while it stays in the
barrel, but that trait goes away when the barrel is opened, as in TFC. To have Xoonglins ask for preserved food, use a
goods need whose ingredient checks for a trait, such as `{"type": "tfc:has_trait", "trait": "tfc:pickled"}`.

The sample pantry (`cftfc:pantry`) is an enclosed building of logs and planks, with a door, a barrel as its key block,
up to 8 barrels and at least one wooden chest inside. `#cftfc:barrels` is a block tag with all of TFC's barrels.

<details>
    <summary>Sample preserver job file</summary>

```json
{
  "type": "cftfc:preserver",
  "hours_per_day": 8.0,
  "required_structure": "cftfc:pantry",
  "load": 16
}
```
- `type`: Must be `"cftfc:preserver"`.
- `hours_per_day`: How many hours a day it works.
- `required_structure`: The structure whose barrels it works, and whose chests hold the food, the salt and what it
  has preserved.
- `foods`: _(Optional, default: any food)_ A list of item ingredients it may preserve, such as
  `[{"tag": "c:foods/meat"}]`. It only ever preserves what TFC can.
- `salt`, `brine`, `pickle`: _(Optional, default: true)_ Whether it salts food, brines it, and pickles it.
- `load`: _(Optional, default: 16)_ The most pieces of food it seals in a barrel at once.

It also accepts Care For Them's common job properties.
</details>
### Farmer

The farmer farms the farmland of its farm the way TFC farming works, and never touches farmland outside it. Each round
it harvests the ripe crops and clears the dead ones, stores the harvest in the farm's chests, and sows the empty
farmland with seeds from those chests, spreading fertilizer first when the crop would lack something.

Farmland that touches makes a **plot**, and all of a plot's empty blocks get the same crop. To choose it, the farmer
looks at the seeds the farm has and, for each of them:

- **The weather to come.** It forecasts the temperature and the farmland's hydration over the crop's growing time, and
  only sows crops that would grow through most of it and never die of cold, heat, drought or flooding. Seasons,
  hemispheres and latitude come with the forecast: in autumn it may only find cold-hardy crops to sow, and in winter
  it waits for the right season.
- **The soil.** In TFC each crop takes some of the soil's nitrogen, phosphorus and potassium, and gives back some of
  the others: cereals take nitrogen, legumes give it back, cover crops like alfalfa give back all three. A crop yields
  fully only while the soil holds what it takes. The farmer scores each crop by the harvest it expects, times the
  crop's `weight`, plus how healthy the soil is left, looking a few crops ahead. So it **rotates crops** on its own:
  after wheat has used the nitrogen, it sows soybeans, which don't need it and put it back.
- **Fertilizer.** It counts the fertilizer in the chests as part of the soil, and when sowing it spreads what makes up
  most of what the crop lacks, wasting the least. Any fertilizer TFC knows works (compost, guano, saltpeter...).

It only sows crops one block tall that grow on dry farmland: cereals, roots, cabbage, onion, garlic, squash, legumes
like soybean and peanut, and cover crops. Climbing, double, spreading and pickable crops, and rice, are left to you;
it doesn't harvest those either.

The sample farm (`cftfc:farm`) is an open-air area fenced in, with a TFC composter in the fence as its key block and one
to four wooden chests in it too. Its ground is mostly TFC farmland, with some water and dirt allowed. How big a farm can
be is up to Care For Them's maximum structure size.

<details>
    <summary>Sample farmer job file</summary>

```json
{
  "type": "cftfc:farmer",
  "hours_per_day": 8.0,
  "required_structure": "cftfc:farm",
  "crops": [
    { "seed": "tfc:seeds/wheat", "weight": 1.5 },
    { "seed": "tfc:seeds/soybean" },
    { "seed": "tfc:seeds/alfalfa", "weight": 0.2 }
  ],
  "soil_weight": 0.5,
  "lookahead": 2,
  "carry": 32
}
```
- `type`: Must be `"cftfc:farmer"`.
- `hours_per_day`: How many hours a day it works.
- `required_structure`: The structure whose farmland it farms, and whose chests hold the seeds, the fertilizer and the
  harvest.
- `crops`: _(Optional, default: any crop it has seeds for, with weight 1)_ The crops it may sow, by their `seed`, and
  how much it values each crop's harvest (`weight`, default 1). A crop with weight 0 is only sown for the soil.
- `fertilize`: _(Optional, default: true)_ Whether it spreads fertilizer.
- `fertilizers`: _(Optional, default: any fertilizer)_ A list of item ingredients it may use as fertilizer.
- `soil_weight`: _(Optional, default: 0.5)_ How much it values healthy soil left behind, against the harvest. Higher
  values make it rotate crops sooner, and sow cover crops more.
- `lookahead`: _(Optional, default: 2, from 1 to 3)_ How many crops ahead it plans each plot.
- `carry`: _(Optional, default: 32)_ The most seeds of a kind, and the most fertilizer, it takes from the chests at once.

It also accepts Care For Them's common job properties.
</details>

### Composter

The composter makes compost, TFC's fertilizer, in the composters of its compost yard. On each round it takes out the
compost of the composters that are done, and fills the others with green and brown items from the yard's chests. It
then stores the compost in the chests, ready for a [farmer](#farmer) or for you.

It fills composters the way TFC does when you add items by hand, so TFC's rules hold. Each composter takes 16 worth of
greens (fruit, vegetables, grain, plants...) and 16 of browns (leaves, wood ash, jute, humus, pinecones...). High-value
items count for 4 and low-value ones for 1. The composter is done about 12 days after its last addition. It picks the
items that make up what each composter needs while wasting the least, and **never adds anything that rots the compost**:
meat, bones or rotten food. If a composter rots anyway, because someone else spoiled it, it empties out the rotten
compost and stores it too.

Composters work slower in very dry or very wet regions (below 150 mm or above 350 mm of rainfall), and when they touch
each other, so leave a gap between them.

The sample compost yard (`cftfc:compost_yard`) is an open-air area fenced in, with composters in the fence (one of them
is its key block) or standing on its ground, and one to four wooden chests in the fence. Its ground is dirt or gravel,
which tells it apart from a [farm](#farmer), whose key block is also a composter.

<details>
    <summary>Sample composter job file</summary>

```json
{
  "type": "cftfc:composter",
  "hours_per_day": 6.0,
  "required_structure": "cftfc:compost_yard",
  "carry": 32
}
```
- `type`: Must be `"cftfc:composter"`.
- `hours_per_day`: How many hours a day it works.
- `required_structure`: The structure whose composters it tends, and whose chests hold the green and brown items and
  the compost.
- `materials`: _(Optional, default: anything the composter takes)_ A list of item ingredients it may compost, such as
  `[{"tag": "minecraft:leaves"}, {"tag": "c:foods/vegetable"}]`. It never composts what would rot the compost.
- `carry`: _(Optional, default: 32)_ The most items it takes from the chests at once.

It also accepts Care For Them's common job properties.
</details>

### Barrel Keeper

The barrel keeper makes liquids in the barrels of its barrel yard. You don't tell it how: it looks at the liquids the
yard has (in its barrels, wells, aqueducts...) and the items in its chests, and works out what it can make from them
with **TFC's own barrel recipes**, including those added by other mods or datapacks. Then it makes **as much as it
can**, starting a new batch whenever it has an empty barrel and the ingredients for it, and sharing its barrels among
the liquids it can make. With water, apples and salt, for instance, it makes vinegar and brine:

1. it fills a barrel with water, one bucket at a time, and seals it with apples to brew cider;
2. it seals the cider with more fruit to make vinegar;
3. in another barrel, it salts water into salt water, and pours in some of that vinegar to make brine.

By default it makes the liquids that recipes end in, the ones no barrel turns into another liquid: brine, vinegar,
tannin, limewater, curdled milk... Liquids on the way, like cider or salt water, it only makes to go on with them. A
`products` list in the job limits it to the liquids listed, and lets you list one that's on the way to others, like
beer. Either way, it makes the liquids a recipe needs poured in, like vinegar for brine, as they're needed.

The liquids stay in their barrels for someone else to take. It never moves them to other workplaces.

It plans each barrel so that nothing goes to waste. In TFC a barrel converts all of its liquid at once, and any liquid
that doesn't make up a whole recipe is lost. So the keeper fills each barrel with only as much as every step can
convert with the items it has: no more than the barrel holds, no more items than fit in its slot, and no more of a
second liquid than one bucket carries. A barrel that holds more than that, such as one you filled yourself, gets split
into another barrel first. It doesn't start a batch whose second liquid it neither has nor can make.

What it needs:
- **Liquid sources in the yard**: water source blocks in its ground, an aqueduct, or any other block holding liquid
  (not its barrels). It draws from source blocks without using them up, as from a well. Water in its own barrels,
  such as rain, is used where it is.
- **A bucket** (or any other liquid container) in the yard's chests. A TFC wooden bucket is best, because it can carry
  part of a bucket, which some recipes need.
- **The items its recipes take** in the yard's chests: salt, flour, fruit... Each barrel's slot only takes one kind of
  item at a time, so it counts each kind on its own.
- **Empty barrels** to work with. It leaves alone barrels holding liquids it doesn't make, and those holding what it
  made, until they're emptied.

The sample barrel yard (`cftfc:barrel_yard`) is an open-air area fenced in, with a cauldron in the fence as its key
block and one to four wooden chests in the fence too. Its ground is paved with stone: cobblestone, smooth stone or stone
bricks. The barrels stand on that ground, inside the yard. TFC aqueducts may run through the fence and along the ground
to bring water in, and the ground may also have some water as a well. A cauldron of water also serves as a source.

<details>
    <summary>Sample barrel keeper job file</summary>

```json
{
  "type": "cftfc:barrel_keeper",
  "hours_per_day": 8.0,
  "required_structure": "cftfc:barrel_yard",
  "products": ["tfc:brine", "tfc:vinegar"]
}
```
- `type`: Must be `"cftfc:barrel_keeper"`.
- `hours_per_day`: How many hours a day it works.
- `required_structure`: The structure whose barrels it works, and whose chests hold the bucket and the items.
- `products`: _(Optional, default: every liquid recipes end in)_ The only liquids it makes, besides those on the way
  to them and those their recipes need poured in.

It also accepts Care For Them's common job properties.
</details>
