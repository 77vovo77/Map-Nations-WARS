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
p net.minecraft.world.entity.npc.villager.Villager "Villager\(|makeBrain|brainProvider|createAttributes|setVillagerData|getVillagerData|registerBrainGoals|mobInteract|customServerAiStep|setAge" 40
p net.minecraft.world.entity.Mob "goalSelector|targetSelector|getNavigation|setTarget|setNoAi|setPersistenceRequired|setItemSlot|setDropChance|registerGoals|createMobAttributes|doHurtTarget|setLeftHanded" 40
p net.minecraft.world.entity.LivingEntity "setItemSlot|getAttribute|setHealth|hurtServer|kill|setYHeadRot|swing" 30
p net.minecraft.world.entity.Entity "teleportTo|discard|kill|setNoGravity|addTag|getTags|entityTags|setInvulnerable|startRiding|getType\(" 30
p net.minecraft.world.entity.ai.goal.MeleeAttackGoal "MeleeAttackGoal\(" 5
p net.minecraft.world.entity.ai.goal.Goal "." 30
p net.minecraft.world.entity.ai.goal.GoalSelector "addGoal|removeAllGoals|removeGoal" 10
p net.minecraft.world.entity.ai.goal.RandomStrollGoal "RandomStrollGoal\(" 5
p net.minecraft.world.entity.ai.goal.LookAtPlayerGoal "LookAtPlayerGoal\(" 5
p net.minecraft.world.entity.ai.goal.FloatGoal "FloatGoal\(" 5
p net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal "HurtByTargetGoal\(" 5
p net.minecraft.world.entity.ai.navigation.PathNavigation "moveTo|stop" 10
p net.minecraft.world.entity.ai.attributes.Attributes "MAX_HEALTH|ATTACK_DAMAGE|MOVEMENT_SPEED|SCALE|FOLLOW_RANGE|ARMOR " 10
p 'net.minecraft.world.entity.ai.attributes.AttributeSupplier$Builder' "." 15
p 'net.minecraft.world.entity.EntityType$Builder' "of\(|sized|build|clientTrackingRange|noSummon" 20
p net.minecraft.world.entity.EntityType "spawn\(|create\(|IRON_GOLEM|VILLAGER|byString|getDescriptionId" 20
p net.minecraft.core.registries.BuiltInRegistries "ENTITY_TYPE" 5
p net.minecraft.core.Registry "static.*register" 10
p net.minecraft.resources.ResourceKey "static" 10
p net.minecraft.world.entity.EquipmentSlot "MAINHAND|HEAD|OFFHAND" 5
p net.minecraft.world.entity.AgeableMob "setAge|setBaby" 5
p net.minecraft.client.renderer.entity.VillagerRenderer "." 20
p net.minecraft.client.renderer.entity.EntityRenderers "register" 5
p net.minecraft.world.level.block.BedBlock "PART|FACING|OCCUPIED" 5
p net.minecraft.world.level.block.state.properties.BedPart "HEAD|FOOT" 5
p net.minecraft.world.level.block.DoorBlock "HALF|FACING|HINGE|OPEN" 8
p net.minecraft.world.level.block.state.BlockState "setValue|is\(" 5
p net.minecraft.world.level.block.state.StateHolder "setValue" 5
p net.minecraft.world.level.Level "setBlock\(|getBlockState|isEmptyBlock|destroyBlock" 10
for c in $(grep -E "/(Arrow|AbstractArrow|AbstractPiglin|Blocks|Vindicator|Evoker|EntityRendererRegistry|FabricDefaultAttributeRegistry)\.class" classes.txt | sed 's|\.class||;s|/|.|g'); do p $c "Arrow\(|shoot|setImmuneToZombification|OAK_PLANKS |COBBLESTONE |WHITE_BED |OAK_DOOR |FARMLAND |WHEAT |CRAFTING_TABLE |TORCH |OAK_LOG |GLASS_PANE |HAY_BLOCK |FURNACE |OAK_STAIRS |WATER |register|Vindicator\(|Evoker\(" 30; done
for j in $JARS; do
  for c in $(unzip -l "$j" 2>/dev/null | awk '{print $4}' | grep -E "(EntityRendererRegistry|FabricDefaultAttributeRegistry)\.class$" | sed 's|\.class||;s|/|.|g'); do p $c "." 15; done
done
wc -c $OUT
# emit as annotations (chunks)
split -b 7000 $OUT chunk_
i=0
for f in chunk_*; do
  msg=$(cat $f | sed 's/%/%25/g' | tr '\n' '\r' | sed 's/\r/%0A/g')
  if [ $i -lt 10 ]; then echo "::warning title=probe$i::$msg"; elif [ $i -lt 20 ]; then echo "::notice title=probe$i::$msg"; else echo "::error title=probe$i::$msg"; fi
  i=$((i+1))
done
