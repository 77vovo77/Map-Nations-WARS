#!/bin/bash
# finds the Minecraft and Fabric API jars Loom downloaded and prints class signatures as annotations
set +e
OUT=probe_out.txt
: > $OUT
JARS=$(find ~/.gradle/caches .gradle -name "*.jar" 2>/dev/null | grep -v sources)
MC=""
for j in $JARS; do
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/world/entity/EntityType.class"; then MC="$MC $j"; fi
done
echo "MCJARS:$MC" >> $OUT
CLIENTJ=""
for j in $JARS; do
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/client/renderer/entity/VillagerRenderer.class"; then CLIENTJ="$CLIENTJ $j"; fi
done
echo "CLIENTJARS:$CLIENTJ" >> $OUT
CP=$(echo $JARS | tr ' ' ':')
for j in $MC $CLIENTJ; do unzip -l "$j" | awk '{print $4}' | grep "\.class$" ; done | sort -u > classes.txt
echo "== grep classes" >> $OUT
grep -E "/(Vindicator|Evoker|Pillager|Ravager|PiglinBrute|AbstractPiglin|Piglin|WitherSkeleton|Arrow|AbstractArrow|IronGolem|Husk|Zombie|Skeleton|Hoglin|EntityTypes|Villager|AbstractVillager|VillagerRenderer|EntityRenderers|IllagerRenderer|VindicatorRenderer|BedBlock|BedPart|DoorBlock|Blocks|MeleeAttackGoal|NearestAttackableTargetGoal|RangedAttackMob|RangedBowAttackGoal|TargetingConditions|BuiltInRegistries|Attributes|DefaultAttributes)\.class" classes.txt >> $OUT
for j in $JARS; do
  unzip -l "$j" 2>/dev/null | awk '{print $4}' | grep -E "(EntityRendererRegistry|FabricDefaultAttributeRegistry|FabricEntityType)\.class$" | sed "s|^|$(basename $j): |" >> $OUT
done
p() { echo "== $1" >> $OUT; javap -public -cp "$CP" "$1" 2>&1 | grep -E "${2:-.}" | head -${3:-80} >> $OUT; }
p net.minecraft.world.entity.npc.villager.Villager "makeBrain|brainProvider|registerGoals|customServerAiStep|getBrain|Brain" 20
p net.minecraft.world.entity.Mob "goalSelector|targetSelector|getNavigation\(|void setTarget|setNoAi|setPersistenceRequired|void setItemSlot|setDropChance|registerGoals|createMobAttributes|doHurtTarget" 20
p net.minecraft.world.entity.LivingEntity "hurtServer|void kill|setHealth|getMaxHealth" 10
p net.minecraft.world.entity.Entity "teleportTo\(double|void discard|void kill|setInvulnerable|setYRot|setGlowingTag|setSilent" 12
p net.minecraft.world.entity.ai.goal.MeleeAttackGoal "MeleeAttackGoal\(" 3
p 'net.minecraft.world.entity.EntityType$Builder' "of\(|sized|build\(|clientTrackingRange" 10
p net.minecraft.world.entity.EntityTypes " VILLAGER;| IRON_GOLEM;| EVOKER;" 5
p net.minecraft.world.entity.MobCategory "MONSTER|CREATURE|MISC" 5
p net.minecraft.core.registries.BuiltInRegistries " ENTITY_TYPE;" 3
p net.minecraft.core.Registry "static.*register\(" 6
echo "== BedBlock" >> $OUT; javap -p -cp "$CP" net.minecraft.world.level.block.BedBlock 2>&1 | grep -E "PART|FACING" | head -5 >> $OUT
echo "== HorizontalDirectionalBlock" >> $OUT; javap -p -cp "$CP" net.minecraft.world.level.block.HorizontalDirectionalBlock 2>&1 | grep -E "FACING" | head -3 >> $OUT
p net.minecraft.world.level.block.Blocks " OAK_PLANKS;| COBBLESTONE;| WHITE_BED;| OAK_DOOR;| FARMLAND;| WHEAT;| CRAFTING_TABLE;| TORCH;| OAK_LOG;| GLASS_PANE;| FURNACE;| WATER;| AIR;| DIRT_PATH;| OAK_FENCE;| RED_WOOL;" 20
p net.minecraft.world.entity.projectile.arrow.Arrow "Arrow\(" 6
p net.minecraft.world.entity.projectile.Projectile "void shoot\(" 3
p net.minecraft.world.entity.monster.piglin.AbstractPiglin "setImmuneToZombification" 2
p net.minecraft.world.level.Level "boolean setBlock\(" 3
p net.minecraft.world.level.block.state.StateHolder "setValue" 3
p net.minecraft.world.level.block.state.properties.DoubleBlockHalf "UPPER|LOWER" 3
p net.minecraft.world.level.block.state.BlockBehaviour\$BlockStateBase "isAir\(\)|canBeReplaced\(\)|liquid\(\)|isSolid\(\)|blocksMotion" 6
p net.minecraft.world.level.block.CropBlock "AGE|getStateForAge|getMaxAge" 4
p net.minecraft.world.entity.npc.villager.VillagerData "with|profession" 8
wc -c $OUT
# emit as annotations (chunks)
split -b 3500 $OUT chunk_
i=0
for f in chunk_*; do
  msg=$(cat $f | sed 's/%/%25/g' | tr '\n' '\r' | sed 's/\r/%0A/g')
  if [ $i -lt 10 ]; then echo "::warning title=probe$i::$msg"; elif [ $i -lt 20 ]; then echo "::notice title=probe$i::$msg"; else echo "::error title=probe$i::$msg"; fi
  i=$((i+1))
done
