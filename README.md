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

## Coming next
3. Joining nations and ranks, salaries, elections.
4. Letters and diplomacy.
5. War: divisions, battles, sieges.
6. AI nations acting on their own.
7. Revolts, coups and founding your own nation.
8. Portals: ruined portals waking up, invasions between the Nether and the Overworld.

Press **M** to open it. The top bar shows **Map Nations WARS** and three tabs: **Map**, **Nations** and **Alliances**.

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
