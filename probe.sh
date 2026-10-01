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
p net.minecraft.world.entity.ai.Brain "removeAllBehaviors|stopAll|clearMemories|eraseMemory|setActiveActivity" 8
p net.minecraft.world.entity.npc.villager.VillagerProfession " ARMORER;| FLETCHER;| CLERIC;| WEAPONSMITH;| NONE;" 6
p net.minecraft.world.entity.LivingEntity "getAttribute\\(|void swing\\(|setYHeadRot|setYBodyRot" 6
p net.minecraft.world.entity.ai.attributes.AttributeInstance "setBaseValue" 3
p net.minecraft.world.level.Level "damageSources\\(" 3
p net.minecraft.world.damagesource.DamageSources "mobAttack|playerAttack" 4
p net.minecraft.world.level.block.state.properties.BlockStateProperties " BED_PART;| HORIZONTAL_FACING;| DOUBLE_BLOCK_HALF;| DOOR_HINGE;| AGE_7;" 6
p net.minecraft.core.registries.BuiltInRegistries " BLOCK;" 3
p net.minecraft.core.DefaultedRegistry "getValue|get\\(" 6
p net.minecraft.core.Registry "getValue\\(|getOptional\\(" 6
p net.minecraft.world.level.block.Blocks "BED" 6
p net.minecraft.world.item.Items " IRON_SWORD;| BOW;| CROSSBOW;| GOLDEN_SWORD;| ARROW;| BREAD;| GOLDEN_HELMET;" 8
p net.minecraft.world.item.ItemStack "ItemStack\\(net.minecraft.world.level.ItemLike\\)|ItemStack\\(net.minecraft.world.level.ItemLike, int\\)" 3
p net.minecraft.world.entity.Entity "distanceTo\\(|getBoundingBox\\(\\)|setPos\\(double" 4
p net.minecraft.world.entity.npc.villager.Villager "setVillagerData|getVillagerData" 3
echo "== Villager protected" >> $OUT; javap -protected -cp "$CP" net.minecraft.world.entity.npc.villager.Villager 2>&1 | grep -E "Brain|registerGoals" | head -6 >> $OUT
echo "== Mob protected" >> $OUT; javap -protected -cp "$CP" net.minecraft.world.entity.Mob 2>&1 | grep -E "goalSelector|targetSelector|registerGoals" | head -5 >> $OUT
wc -c $OUT
# emit as annotations (chunks)
split -b 3500 $OUT chunk_
i=0
for f in chunk_*; do
  msg=$(cat $f | sed 's/%/%25/g' | tr '\n' '\r' | sed 's/\r/%0A/g')
  if [ $i -lt 10 ]; then echo "::warning title=probe$i::$msg"; elif [ $i -lt 20 ]; then echo "::notice title=probe$i::$msg"; else echo "::error title=probe$i::$msg"; fi
  i=$((i+1))
done
