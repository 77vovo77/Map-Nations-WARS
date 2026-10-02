# Map Nations WARS (Fabric mod for Minecraft 26.3)

The war version of [Map Nations RP](https://github.com/77vovo77/Map-Nations-RP). It is a separate mod: its own name, save files and settings, so it never mixes with Map Nations RP.

In RP you paint the map yourself. In **WARS** the world is already full of nations run by the game - a mix of Hearts of Iron, Manor Lords and Minecraft. It is built in stages; this is **stage 1: the world of nations**.

## The world of nations (stage 1)
- When a new world starts, the mod finds the villages, pillager outposts, woodland mansions and (in the Nether) bastions within about 2000 blocks of the middle of the world. This takes a few seconds; a message shows the progress.
- Every one of them becomes a **province** with a name, its own land around it and a **mayor** (villages) or commander (strongholds).
- Provinces are grouped into **AI nations**, each with a ruler, ideology and colour:
  - **Villager nations** - kingdoms, republics, empires... of several villages, plus some villages that are independent on their own.
  - **Illager nations** - every woodland mansion rules the outposts near it; other outposts form warbands.
  - **Piglin clans** - the bastions of the Nether.
  - **Undead hordes** - when a village has only zombie villagers left (an abandoned village), the undead take it over.
- The mayor is a real villager: when you come close, one villager of the village gets a name tag "Mayor ...". If the mayor dies, a new one is chosen. The number of villagers is counted while someone is near.
- On the map, villages show as village / city / capital icons with their borders; outposts as forts, mansions and bastions as castles. Hover one for its nation, mayor and population; right-click it to open its nation.
- **Land can't be claimed** in WARS, and cities / villages / castles can't be placed as markers - they are real places. Other markers (arrows, home, danger...) still work.
- Founding your own nation and joining nations come in later stages.

## The economy (stage 2)
- Every **Minecraft day** (20 minutes) each province works:
  - **Food:** farms grow food (5 each, plus a little from gardens) and the villagers eat it (1 each). Spare food is stored.
  - **Emeralds:** villagers earn emeralds (1 each, workshops +4). Unhappy villagers earn less. **Half goes to the nation as tax**, the rest stays in the village for building.
  - **Upkeep:** the nation pays for the buildings every day. If its treasury runs dry, its villages get unhappy.
  - **Growth:** with spare food, free beds (2 per house) and happy people the village grows; starving villages shrink. (While you are near a village, the real villagers are counted instead.)
- **Happiness** (0-100%) goes down with hunger, overcrowding and unpaid upkeep, and up with food, new buildings and gifts.
- **Buildings:** houses (12 emeralds, +2 beds), farms (8, +5 food a day) and workshops (20, +4 emeralds a day). They are paid by the village first, then by the nation's treasury, and are ready the next day. **AI nations decide by themselves** what their villages build.
- **Talk to the mayor** (right-click him) to open the village page: villagers, beds, happiness, food, emeralds, income, tax, upkeep and buildings. You can **give the village real emeralds** from your inventory - the villagers like that. You can also open the village page by right-clicking the village on the map.
- **Orders** can be given by the nation's leaders. Since players can't lead nations yet, a player in **creative mode** can give orders (for testing).
- Every nation has a **treasury** kept by the server (it can't be stolen). The nation page shows it and how much it changes per day.

## Nations and ranks (stage 3)
- **Joining:** you start without a nation. Open a **villager** nation in the Nations tab and click **Ask to join** - its ruler takes you in as a **Citizen**. (Illager, piglin and undead nations don't take in humans.)
- **Ranks:** Citizen → Soldier → Officer → Minister. In nations ruled by the game you are **promoted by merit**:
  - Soldier at 20 merit, Officer at 80, Minister at 250.
  - Merit: +1 every 5 minutes you spend in your nation's land, +2 for every monster you kill there, +1 for every emerald you give to your nation's villages.
- **Salaries** (from the nation's treasury, every day): Soldier 2, Officer 5, Minister 10, leader 15 emeralds. They are saved for you - **collect them at any mayor** of your nation (button "Salary" on the village page).
- **Ministers and the leader** can order buildings in the nation's villages, and **deposit or withdraw** emeralds from the treasury at the **capital's mayor**.
- **Elections:** nations with a democratic ideology (liberal, libertarian, socialist, syndicalist, agrarian, anarchist, technocratic) vote every **7 days**. Officers and higher can **Run for** leader on the nation page. Every village votes for the candidate it likes most - players win votes with merit, the game's ruler with happy villages. Win, and **you become the leader** of the nation (you can then promote and demote members with ▲ / ▼, and kick them). Lose the next election, or leave, and the game rules again.
- The nation page shows your rank, merit, the next promotion, your salary and what is waiting for you, plus the next election, the candidates and the last result.
- Nations without elections (empires, kingdoms, holy orders...) can only change rulers by force - coming in stage 7.

## Letters and diplomacy (stage 4)
- New **Letters** tab (press **M**). Leaders and **Ministers** can write letters to any other nation, player-led or AI.
- Letter types:
  - **Message**: just words.
  - **Gift**: emeralds from your treasury, held until they answer. If they refuse, the emeralds come back.
  - **Trade agreement**: both treasuries earn 3 emeralds a day while it lasts.
  - **Alliance**: only nations that like you a lot (opinion 40+) agree.
  - **Peace**: ends a war.
  - **Demand tribute**: a much weaker nation may pay, and everyone you threaten will hate you for it.
  - **Declaration of war**: takes effect right away. It ends trade and alliances between you. Battles come in stage 5.
- AI nations answer at once, in their own voice. Villagers are polite, illagers are rude, piglins want gold and the undead hiss. Player-led nations answer from their Letters tab with **Accept** or **Refuse**.
- **Opinion** (-100 to 100) is how one nation sees another. It starts from faction and ideology: villagers dislike illagers, everyone fears the undead, and similar governments get along. Gifts, trade, alliances and wars then move it, and grudges slowly fade day by day.
- The **Relations** view in the Letters tab shows how every nation sees yours. Each nation page shows its opinion of you, any war, alliance or trade deal, and a **Write a letter** button.

## War (stage 5)
- **Raising armies.** Leaders and Ministers raise armies at any province of their nation: right-click it on the map, or talk to its mayor. They are paid from the treasury (since 2.0 a division starts at half strength; hire the rest).
  - **Infantry** costs 30 emeralds: up to 24 soldiers, a solid defence.
  - **Cavalry** (iron golems for villagers) costs 50: fast and hits hard.
  - **Archers** cost 40.
  - **Siege** costs 60: slow and weak in battle, but takes provinces three times faster.
  - Every faction names its soldiers its own way: Militia and Lancers, Vindicators and Ravager Riders, Brutes and Hoglin Riders, the Horde and Bone Archers.
  - A nation can keep 2 armies plus 1 per province. Each army costs upkeep every day.
- **Commanding.** Officers, Ministers and the leader command armies. In the **Map** tab, left-click one of your armies to pick it, then right-click where it should march. Right-click an enemy province to besiege it, or one of your own to defend it.
- **The map shows the war.** Army counters show their nation's colour, their kind, soldiers (green bar) and morale (yellow bar). Battles flash with crossed swords, and sieges show a bar with their progress.
- **Battles.** Armies of nations at war fight when they meet. Morale falls as soldiers fall. Below 15% morale an army retreats to its nearest province, and an army with nowhere to run surrenders.
- **Sieges.** Each province has a garrison. Villages get villagers with pitchforks; outposts, mansions and bastions are tougher, and capitals are 50% tougher. At 100% the province changes hands, with its land, its villagers and half its emeralds. A nation that loses its last province falls.
- **You can be there.** Go near a battle or siege your nation is part of, and real enemy soldiers appear:
  - Villager armies send iron golems.
  - Illagers send vindicators and pillagers.
  - The undead send husks and skeletons.
  - Piglins send brutes, or zombified piglins in the Overworld.

  Every soldier you kill weakens that army (or the defenders of the besieged province) and earns merit. Your side also fights up to 90% harder while you are near.
- **Calls to arms.** When a nation is attacked, its AI allies who like it enough join the war. Player allies get a message asking for help.
- **Peace** (a Peace letter) ends the sieges between the two nations and sends their armies home.
- **Recovery.** Resting armies get their morale back in friendly land and refill their ranks every day at home.
- The **War** tab lists your armies, your wars and sieges, all battles and all wars in the world. Pick an army there to see it on the map, hold it, send it home or disband it.
- Wars, battles, captured provinces and fallen nations are announced in chat.

## AI nations (stage 6)
Nations ruled by the game now act on their own.

**Their rulers decide once a day:**
- **Peace.** When a war goes badly (they lost more provinces, or their army is much weaker) or drags on for 12 days, they ask for peace.
- **Answering peace.** A nation that is winning refuses peace. One that is losing accepts.
- **Each faction has its own temper:**
  - **Villager kingdoms** trade, make friends, send gifts to nations they like and propose alliances. They only go to war against nations they truly hate, or to take villages back from the dead.
  - **Illager dominions** raid any living neighbour weaker than them, especially one already busy with another war, and demand tribute: "pay, or we come for you".
  - **Piglin clans** demand gold and fight for pride.
  - **Undead hordes** never rest. When they are not at war, they attack the nearest living nation. Every two days the dead rise again and form new hordes for free, so take their villages back.
- **No wars at the start.** Rulers wait 3 days after a world starts, at least 5 days between two declarations, and never fight more than 2 wars at once.
- **They write to players too.** AI nations send real letters to player-led nations: trade offers, alliance proposals, tribute demands, peace offers and declarations of war. Answer them in the Letters tab. Refusing a strong neighbour's demand can lead to war.

**Their generals act every 10 seconds:**
- They raise armies when at war, at the province closest to the enemy, and keep a small army in peacetime when rich.
- They first relieve besieged provinces, then intercept enemy armies that come near their land, then march together on the closest, weakest enemy province.
- Tired armies go home to rest. In peacetime, armies outside their land come home. When money runs out, they disband soldiers.
- If an Officer or Minister of an AI-ruled nation gives an army an order, the generals leave that army alone for 10 minutes.

**Conquest changes villages.** Villages taken by the undead are left empty. Villages taken back from the dead get their refugees and a new mayor.

**The Chronicle.** The War tab keeps a chronicle of everything that happened in the world, day by day. It is also announced in chat.

## Revolts, coups and your own nation (stage 7)
- **Unrest.** Every province has unrest from 0 to 100%. You can see it on the village page, in the map tooltip, and as a ⚠ sign on the map once it passes 60%.
  - **It rises** with misery (happiness under 30%), hunger, new masters (10 days after a conquest), foreign rule (villages ruled by illagers or piglins), long wars, and nations that grew too big. More than 6 provinces is too many for a player-led nation, and more than 9 for an AI-ruled one: you can't just take all the land.
  - **It falls** in happy villages (60%+), and wherever one of the nation's own armies keeps order nearby.
- **Revolts.** At 100% a province rises up and proclaims itself free ("Free Oakvale"), with a rebel army. It goes to war with its old rulers, and angry provinces of the same nation nearby (70%+ unrest) join it.
  - Capitals never revolt.
  - If you have no nation and the people back you (60+ support), you are chosen to lead the revolt and rule the new nation.
- **Hated leaders fall.** If the villages of a player-led nation stay miserable (under 25% average happiness) for 3 days, the people overthrow the player. The player becomes a Citizen and the game rules again.
- **Coups.** Officers and Ministers build a conspiracy and launch it (since 2.1, see above). Click twice to be sure.
  - The page shows the chance. It is higher with more merit and an unhappy people, and lower against a player leader who is online.
  - **If it works,** you rule the nation, and unrest rises everywhere while things settle down.
  - **If it fails,** you are exiled from the nation.
- **Founding your own nation** is hard on purpose:
  1. Belong to no nation.
  2. Win the support of a village: give its mayor emeralds, kill monsters there and spend time there. The village page shows "They back you: x/100", and support slowly fades.
  3. You need 100 support, and the village must be restless (40%+ unrest) or unhappy (under 45%). Once you have 200 support, even a happy village follows you. It can't be a capital.
  4. Talk to the mayor and press **Found a nation here**. Pay the 200-emerald charter, then choose a name, colour, ideology and banner.
- **The new nation** gets that village, a free militia and a small treasury. Its old rulers declare war at once, so you will have to defend it, win allies or make peace.

## Portals and Nether invasions (stage 8)
- **Ruined portals wake up.** When a world starts, the server finds the ruined portals of the Overworld. Each one has an **awakening meter** from 0 to 100%.
  - **To see it,** hover over the portal on the map (or the minimap), or simply look at the portal in the world: the meter appears under your crosshair.
  - At 75% the whole server is warned.
- **Players make it worse.** Every portal players build (or relight) is noticed the first time someone travels through it.
  - Every such portal makes the ruined portals within 1500 blocks wake up faster.
  - It wakes up itself, three times as fast.
- **The world grows dangerous.** Portals also wake faster as the world gets older, and the stronger the piglin clans are.
- **Calming a portal:** camp an army at it (it barely wakes while guarded), or kill Nether creatures near it.
- **Invasion.** At 100% a portal **tears open for three days**. The strongest piglin clan (or the Legion of the Nether, if there is none) pours into the Overworld with 2 to 5 armies, and declares war on whoever owns the nearest land. That nation's allies are called to arms.
  - The piglin generals march on your villages like any other army. A village they take is ruled by piglins and hates it.
  - **Fight at the rift.** Near an open rift, Nether creatures come out for any player close by: zombified piglins, magma cubes and blazes. Every Nether creature you kill there closes the rift 2 minutes sooner.
- **Invasions both ways.** Armies can march through open rifts and through any portal players built. Pick an army in the War tab and press **Through a portal**: it marches to the nearest usable portal and comes out on the other side, at the same place divided by 8 in the Nether.
  - The AI uses portals too. Piglin clans at war with Overworld nations come through, and Overworld nations at war with piglins invade the Nether to besiege their bastions.

## 2.2.1 - Helmets you can see

- Villager and illager soldiers now visibly wear their leather helmet in the nation's colour. Vanilla Minecraft never draws armour on villagers or illagers, so the mod adds that layer (stretched a bit to fit their taller heads).
- Kings' golden crowns now show on villager and evoker kings too.
- Division captains' banners already showed on every kind of soldier.

## 2.2.0 - Better battles
- **Fixed:** a crash ("ConcurrentModificationException" in the server tick) when a villager soldier killed an enemy. Left-over soldiers from before a restart are now removed one tick after their chunk loads, not while it loads.
- **Friends can't hurt each other.** Soldiers, guards and kings are in their nation's team:
  - Soldiers of the same or allied nations never damage each other, and the game's own mob AI doesn't pick comrades as targets.
  - Soldiers only hurt players who are their enemies. You can't hurt your own nation's soldiers.
  - Armies don't harm the villagers and golems of nations they aren't at war with.
- **Outlines in battle.** While soldiers are fighting they glow, outlined in (about) their nation's team colour, so you can tell your soldiers from theirs, even through walls.
- **Captains.** Every division in the world is led by a captain: a foot soldier of the nation with a visible name ("♚ Captain of 2nd Militia"), double health, and the nation's banner on his head. If the nation has no banner, he wears a banner of the colour closest to the nation's. Killing a captain costs his army 15 morale.
- **Helmets in the nation's colour.** Every soldier wears a leather helmet dyed in its nation's colour (shown on zombies, skeletons and piglins). It also keeps the undead from burning in the sun.
- **Healing potions.** A soldier below 40% health drinks a healing potion (you see the bottle in its hand) and heals half its health. Each soldier has two.

## 2.1.0 - Boats, better politics, easier letters
- **Armies take boats.** Soldiers who have to cross water get into a boat, row across, step out on the other shore, and the boat is gone. Iron golems and ravagers are too big for boats; they wade or catch up.
- **No more stuck soldiers.** A soldier who doesn't move closer to its place for 6 seconds (a hole, a wall, a tree) goes straight to its place.
- **The undead wear helmets.** Undead soldiers, guards and the zombie villagers of abandoned villages wear helmets, so the sun doesn't burn them.
- **Elections:**
  - **Candidates campaign** for 10 emeralds a time (once a minute). Every 8 campaign points count like 10 merit with the voting villages.
  - **Members vote** for a candidate or for the crown. Each member's vote counts as 3 votes.
  - **Results:** after each election, every candidate's votes are shown as bars, and the result is in the Chronicle.
  - **Countdown:** the page shows how long until the next election, in days and minutes.
- **Coups are now conspiracies.** Officers build a plot step by step:
  - **Bribe an official:** 20 emeralds, +15 conspiracy, but 1 in 7 talk. That halves the plot, costs 30 merit and the ruler hears rumours.
  - **Win the army:** +20 once a day, if you commanded one of the nation's armies (or are a Minister).
  - **Unhappy villages** feed every plot by themselves each day.
  - **Launching:** from 30% conspiracy you can launch the coup. The bigger the plot, the likelier it works.
- **Revolts have a clear end.** With no nation, 60 support and 70% unrest, the mayor offers **⚑ LEAD THE REVOLT**. The village rises at once, you rule it, and its rebel army is bigger the more the people back you. The Revolts page tells you, village by village, what to do next.
- **Easier letters:**
  - While writing, the list on the left shows every nation, with what it thinks of you. Click one to write to it.
  - Preset amounts (10 / 25 / 50 / 100), and ready-made words for every kind of letter (one click).
  - A **Reply** button on letters.
- **One place for elections and coups:** the nation page's **Elections & coups** button opens that page in the You tab.

## 2.0.0 - Armies you can see, real kings, growing villages
- **Armies stand in the world.** Come within about 128 blocks of a division and it appears as real soldiers. Each one stands for 2-3 soldiers, up to 12 per division. They march in formation where the division marches and fight the soldiers of nations at war with them, and any player at war with them. Every soldier killed in the world is lost by the division, so you can see the battle and help win it. When nobody is near, they go back into the map.
  - **Villagers:** villager militia with iron swords, villager bowmen who shoot arrows, and iron golem divisions.
  - **Illagers:** vindicators, pillagers and ravagers.
  - **Piglins:** brutes, crossbow piglins and hoglins. They no longer turn into zombies in the Overworld.
  - **The undead:** husks, skeletons, wither-skeleton "Death Knights" and zombies.
  - Besieged provinces send their own defenders out to fight the besiegers.
- **Smaller divisions you build up.** A new division starts with half its soldiers:
  - Infantry: up to 24 soldiers.
  - Golems / cavalry: up to 12.
  - Archers: up to 20.
  - Siege: up to 8.

  In the War tab, **Hire** soldiers (+1, +5 or fill up) with treasury emeralds while the division is in your land. **Split** a division in two, or **Merge** it with another of the same kind nearby. Upkeep depends on how many soldiers it has.
- **Real kings.** Every nation ruled by the game has its ruler standing at its capital, with a crown, a sceptre and a royal name. The villager king wears purple; illagers have an evoker lord, piglins a warlord brute and the undead a wither-skeleton lich.
  - **Right-click the king** to open the **Royal Court**. There you can swear allegiance, ask for promotion or a duty, give 10 or 50 emeralds, write to the crown, beg for peace or declare your own war, and see the nation's state.
  - **Killing a king** is regicide: unrest everywhere, and a new ruler after 5 minutes. In war it earns you great merit; otherwise every guard of that nation hunts you.
- **Villages really grow.** Houses (with a bed), wheat farms (with water) and workshops (crafting table and furnace) a village builds now appear next to it in the world when someone is near. Villages with free beds, food and good mood have children.
- **Nations claim the land around them.** Every 2 minutes every province reaches a little further into the wild, until it meets its neighbours. You can watch the borders grow on the map.
- **A deeper treasury** (Nations page → Treasury, or the You tab):
  - **Yesterday's accounts,** line by line: taxes, building upkeep, salaries, army upkeep, hired soldiers, trade, tribute, new land, festivals.
  - **Tax level:** Low 30%, Normal 50%, High 65% or Harsh 80% of the villages' income. Higher taxes make villages less happy.
  - **Spending:** a **Festival** (+10 happiness, -12 unrest everywhere) or **Grain imports** (+20 food everywhere), once a day each.
- **Your nation's stats on the map:** the top-left corner shows your nation, your rank and merit, the treasury, taxes, provinces, villagers, armies, unrest and wars.
- **The minimap starts in the top-left corner**, so it no longer covers your arm.

## Politics for everyone (1.9.0)
- **The "You" tab** (second tab, press **M**) explains everything one player can do, with your progress and a button for every action:
  - **Start here:** your road, step by step, and the nations near you with **Join** buttons.
  - **Duties:** your tasks, their progress and rewards; abandon one, or ask for a new one.
  - **Rank & merit:** a merit bar to the next rank, every way to earn merit, and what each rank can do. **Ask for promotion** is here too.
  - **Elections & coups:** run or withdraw, and a coup checklist with your chance and the **Attempt a coup** button.
  - **Letters:** what you can write, with buttons to start.
  - **Revolts:** how to start one, the villages that know you, and the most restless villages, each with a map button.
  - **Your own nation:** the charter checklist (✔/✖) for the village you stand in, and **Found a nation here**.
  - **Personal wars:** what every nation thinks of you, and buttons to declare war or ask for peace.
  - **Land** and **Armies:** how they work, with buttons to the map and the War tab.
- **Duties** are the clear way to rise.
  - **Members** get up to 3 duties from their nation: hunt monsters in your land, bring emeralds to a village's mayor, visit a province, patrol, or fight the enemy in wartime. They pay merit (and some emeralds).
  - **Players without a nation** get duties from the nearest village, and doing them makes its people back you.
  - New duties come by themselves every few minutes.
- **Anyone can write letters**, as themselves:
  - **Message**, or **Gift** (emeralds you carry).
  - **Ask to join** and **Ask for promotion**.
  - A personal **Declaration of war**, and **Peace** (offer at least 30 emeralds).

  Leaders and Ministers choose "As yourself" or "As <nation>" when they write. Nations ruled by the game answer at once; player leaders answer in their Letters tab.
- **Opinion of you.** Every nation has an opinion of you, from -100 to +100.
  - **It rises** with gifts, emeralds for its villages and duties.
  - **It falls** when you are caught stirring trouble or kill its guards.
  - **Joining needs it:** villagers take almost anyone, illagers want +20, piglins want +30, and the undead take no one alive.
- **Village guards.** Come close to a village of a nation that you or your nation is at war with, or that thinks you are an outlaw (opinion -60 or lower), and its guards come out to kill you:
  - iron golems for villagers,
  - vindicators and pillagers for illagers,
  - brutes (zombified piglins in the Overworld) for piglins,
  - husks and skeletons for the dead.

  They go back when you leave or when there is peace.
- **Stir unrest.** At the mayor of another nation's village, once its people back you a little (20 support), press **Stir unrest**. It costs 10 emeralds and adds +12 unrest. At 100% the village rises up, and if you have no nation and 60+ support, you lead it. The guards may catch you.
- **Nations claim land.**
  - Every day, provinces of AI-ruled nations grow into the wild around them, faster when the village is happy, until they meet their neighbours.
  - Leaders and Ministers press **Claim** on the map to claim land next to their own, within 12 chunks of a province, for 2 emeralds a chunk. Right-click gives land up.
- **Only a village's own leaders decide for it.** Outsiders (even in creative mode) can no longer order buildings, raise or command armies there.
- **Tabs fit any screen.** They share the room and use short names on small screens.

## Polish (1.8.0)
- **War alert on screen.** In the world, a line at the top shows your nation's nearest battle or siege within 700 blocks, with its distance and direction ("⚔ Battle 140 blocks NE against ...").
- **The minimap** shows armies, battles and portals.
- **The map's View menu** can switch armies & battles and portals on or off.
- **The War tab** lists the most awake portals with their meters, and the help explains portals.
- **Narrow screens:** the title shortens to "WARS" so all five tabs fit.

Press **M** to open it. The top bar shows **Map Nations WARS** and six tabs: **Map**, **You**, **Nations**, **Alliances**, **Letters** and **War**.

## Map tab
- **The whole world is visible right away** - no exploring needed. The server works out what places you haven't visited look like (height, water, biomes, forests, snow, mountains) and fills them in, starting from the middle of the view. Places you really visit are then drawn exactly, block by block. The filled-in land is saved, so it shows instantly next time.
  - The filled-in land stops at the **world border**. Past the border you only see what you explored by walking there.
- A white arrow shows where you are and which way you're facing.
- Other players show up as their face. Hover over a face to see their name and nation.
- **Scroll** to zoom. **Drag** or use **WASD** to look around. **C** jumps back to you.
- The world border is drawn as a red line.
- A **compass** sits in the bottom-right corner (north is always up).
- **View menu** (top bar): map colours (**Nations**, **Alliances** or off), **settlement borders** (show the borders of every city / village / castle at once), which markers to show, and the minimap settings.
- **Minimap:** turn it on in the View menu (off at first). Pick its corner (bottom-left, top-left, top-right) and size (small, medium, large). It shows the land around you, nations' land, settlement borders, other players, the world border, N/E/S/W and your coordinates. In the bottom-left corner the **chat is drawn over it**, so it never hides messages.
- While claiming land or editing a settlement's borders, a box in the top-right corner shows the mouse controls (left click = claim / add, right click = remove).
- The political map shows nations in their colour, with country borders.
  - Each nation's name (and banner) is written across its land. The text grows with the size of the land and tilts to follow its shape.
  - When you zoom far out, every nation's name and banner stays readable over its main land.
  - Land that isn't connected gets its own label.
  - Hover over a nation to outline all of its land and see its info.
  - **Click a nation's land** to open its page in the Nations tab.
  - **Click a nation in the list** in the top-right corner to zoom to it.
  - The View menu or **P** switches the map colours: Nations → Alliances → off.
  - **Alliance map:** land is coloured by alliance, with the alliance name across its land. Nations without an alliance are grey. Click land to open that alliance.

## Markers
- Click the **◀ arrow** on the right edge of the map to open the marker panel. Pick a marker, then click on the map where it should go.
- A window asks "Place a City here?". Give it a name (optional) and pick who can see it.
- Icons are drawn in the style of vanilla map icons. There are arrows in all 8 directions, an X, a plus, a circle and a check mark too.
- Markers get a bit smaller when you zoom out, so they never cover the map.

**Who can see it**, and how you can tell:
- **Everyone:** just the icon.
- **My nation:** the icon is outlined in your nation's colour.
- **Only me:** your head peeks out behind the icon.
- Important places are **always visible to everyone** and can't be hidden: Capital City, City, Castle, Fort, Village, Port, Market, Temple and Landmark.

**Banners:**
- Settlements and important places show a small banner of the nation that owns the land, in the background.
- If another nation takes the land over, the banner changes and it's theirs now.

**Right-click a marker** to open its menu:
- **Your own marker:** rename it, **move** it (it follows your mouse until you click the new spot), or remove it.
- **Someone else's marker:** **vote to destroy** it. When **5 players** have voted, it's gone. You can take your vote back, the owner is told about every vote, and the hover shows the vote count.
- **Leaders** can also remove their members' markers and anything on their land straight away.

**Settlement borders:**
- Every Capital, City, Village, Castle and Fort has borders. They start as just the one chunk the marker is in; left-drag to make them bigger (up to Capital 64, City 49, Village 25, Castle 16, Fort 9 chunks).
- When you place a settlement, you **must choose its borders first**: "Choose borders →", adjust them on the map, then "✔ Place it" (or "✖ Cancel").
- Hover a settlement to see its borders on the map.
- If another nation takes a settlement's land, the settlement becomes theirs and **their leader manages it**. A captured Capital becomes a City.
- The owner (or the leader of the land) can click **Edit borders** in the marker menu, then left-drag to add chunks and right-drag to remove them.
  - A box above the info text shows **✔ Apply** (save), **↺ Reset** (back to how it was) and **✖ Cancel** (stop, keep the old borders). **Enter** = Apply, **Esc** = Cancel. Nothing changes until you press Apply.
- Borders can be up to 6 chunks from the marker and can't overlap another settlement or another nation's land.
- Maximum size: Capital 64 chunks, City 49, Village 25, Castle 16, Fort 9.
- **Population:** the server counts the villagers living inside the borders (and in each nation's land). You see it on hover, in the marker menu and on the nation page. Only nearby (loaded) areas can be counted; places nobody visits keep their last count.

**Markers button:** filters optional markers (All, Only me, Nation, Everyone, Important). Important places always stay visible, except with **Off**, which hides every marker. **H** hides or shows all markers quickly.

**Rules:**
- **Capital City:** only a nation's leader can place it, inside their own land, one per nation. Placing a new one moves it.
- **Settlements** (City, Castle, Fort, Village): anyone can found one, in the wilderness or in their own nation's land (not in someone else's). They need 16 blocks of space from each other.
- **Home:** one per player. Placing it again moves it.
- **Limits:**
  - Up to 30 "only me", 15 "my nation" and 12 "everyone" markers per player.
  - 3 seconds between placements.
  - Your own markers must be at least 3 blocks apart.
  - Only where the map shows land.

## Conflict zones
- In the Claims tab you can also claim land that another nation already owns. It becomes a **conflict zone**:
  - striped in the colours of all nations involved
  - outlined in sharp red
  - marked with a **!** sign
- Hover over it to see who holds it and who contests it.
- The first nation keeps the land (and its settlements) until it withdraws its claim. Then the next nation takes it over, and the banners change.
- You can't contest your allies' land.

## Where am I?
- The bottom-left corner of the map shows whose land you're standing in, or Wilderness, or a conflict zone.
- In the world, small text in the bottom-left corner of the screen always shows the nation you're in (or Wilderness / Conflict zone), plus the settlement if you're inside one.
- A message appears above the hotbar when you enter a nation's land or a conflict zone, and "Welcome to <city>" when you walk into a settlement's borders.

## Claims tab
- **Click** a chunk to claim it for your nation. Click your own land again to unclaim it.
- **Drag** with the left mouse button to claim a whole rectangle at once (up to 20 chunks per drag). The rectangle shows how many chunks it covers.
- **No land limit:** you can claim as much land as you like. Only short waits stop spam: 0.25 seconds between drags (up to 20 chunks each) and 0.05 seconds between clicks.
- **Who claims:** only the leader claims land directly.
- **Officers** propose land instead. It shows striped in the nation's colour and white as "Proposed territory".
  - The leader clicks it to annex it, or right-clicks to deny it.
  - The Nations tab also has **Annex all / Deny all** for the leader.
- Regular members can't claim.
- **Right-click** or **right-drag** to unclaim.
- **Middle-drag** or **WASD** moves the map in this tab.
- Drag claiming skips other nations' land. To contest land (start a conflict), click that chunk on its own.
- You have to be in a nation to claim land.

## Nations tab
- **Left side:** every nation. Click one to see its banner, ideology, leader and members.
- **Right side, if you're not in a nation:** "+ Create Your Nation", or "Ask to join" on a nation you clicked.
- When you create a nation you set:
  - a name
  - a banner (optional): click the slot and pick a banner from your inventory
  - a colour: each colour can only belong to one nation
  - an ideology, picked from a dropdown grouped by category
- **Leaders** can edit the nation, accept or deny join requests, kick members, and make another member the leader.
- **Ranks:** the leader can make members **Officers** (✦). Officers can propose new land. Their name tag gets a ✦ too.
- **Alliances:** a leader opens another nation and clicks **Propose alliance**. The other leader accepts it in their Requests list.
  - Each nation page has an **Allies** bar with their banners. Hover a banner for the nation's name, click it to open that nation.
  - **Break alliance** ends an alliance.
- **Anyone** can leave their nation. If the leader leaves, the member who joined first becomes the new leader. If the last member leaves, the nation is dissolved.
- The leader's title depends on the ideology: Emperor, President, King, General Secretary, High Priest, and so on.

## Alliances tab
- An **alliance** is a group of whole nations. It is different from "allies" (two friendly nations): an alliance has a name, colour and banner, and many nations.
- Only **nation leaders** found, join or leave alliances, and their whole nation comes with them. Normal members don't join alliances themselves.
- **Found an Alliance** works like creating a nation: name, banner from your inventory and a unique colour.
- Other leaders click **Ask to join**. The leader of the alliance's head nation accepts or denies, can remove nations and can make another nation the head.
- **Leave alliance** takes your nation out. When the last nation leaves, the alliance is dissolved.

## In the world
- Name tags **and the player list (hold Tab)** are coloured in your nation's colour; the player list also shows the nation's name after each player. The leader gets a symbol in front of their name, such as ♛, ★, ♔ or ☭.
- When you walk into a nation's land, "You just entered <nation>" appears above your hotbar, with its banner. When you leave, you see "You left <nation>".

## Banner patterns (loom)
The mod adds 96 new patterns to the **loom**. They need no pattern item, so they appear in the list right away. They also work on shields.
- **Emblems:** Crown, Star, Sun, Crescent Moon, Sword, Crossed Swords, Battle Axe, Hammer, Anchor, Tower, Pine Tree, Wheat, Fleur-de-lis, Lightning Bolt, Key, Heart, Eye, Cross Pattée, Gear, Small Shield, Laurel Wreath, Mountain, Ship.
- **Symbols & pictures:** Star of David, Crescent and Star, Latin Cross, Orthodox Cross, Celtic Cross, Ankh, Yin and Yang, Dharma Wheel, Hammer and Sickle, Peace Sign, Trident, Maple Leaf, Shamrock, Rose, Lotus, Snowflake, Flame, Water Drop, Oak Tree, Horseshoe, Bow and Arrow, Crossed Spears, Knight Helmet, Chalice, Bell, Open Book, Scales of Justice, Torch, Compass Rose, Eight-Pointed Star, Three Stars, Rising Sun, Swallow, Feather, Paw Print, Gem, Crossed Pickaxes, Pyramid, Fish, Eagle, Crossbones, Castle.
- **Field patterns:** Checkered, Field of Stars, Wavy Band, Ring, Double Chevron, Nordic Cross, Long Triangle, Pall, Sunburst, Vertical Stripes, Five Stripes, Thin Stripes, Diagonal Stripes, Diagonal Stripes Left, Diamonds, Small Checkers, Dots, Zigzag Band, Waves, Ermine, Vair, Lattice, Gyronny, Hourglass, Rising Triangle, Thick Frame, Double Border, Circle of Stars, Chevrons, Scales, Tartan.

## Small screens
- The Nations and Alliances tabs are a window in the middle of the screen (you still see the game around it) that shrinks to fit any GUI scale.
- The Nations page scrolls (mouse wheel) when it doesn't fit, with a scroll bar. Member and request buttons move under the names when the window is narrow.
- The ideology list and the nation list scroll too.
- The map's top buttons get shorter labels, the marker panel icons shrink to fit, and long help text wraps onto more lines.

## Notes
- The mod has to be on the **server** too (singleplayer is fine) for nations, claims, and far-away players.
- Claims only show on the map. They don't protect blocks.
- Nations are saved in the world folder as `mapnationswars_nations.json`, alliances as `mapnationswars_alliances.json`, markers as `mapnationswars_markers.json`.
