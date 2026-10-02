#!/bin/bash
set +e
OUT=probe_out.txt
: > $OUT
JARS=$(find ~/.gradle/caches .gradle -name "*.jar" 2>/dev/null | grep -v sources)
CP=$(echo $JARS | tr ' ' ':')
p() { echo "== $1" >> $OUT; javap -public -cp "$CP" "$1" 2>&1 | grep -E "${2:-.}" | head -${3:-80} >> $OUT; }
pc() { echo "== CODE $1 $2" >> $OUT; javap -c -p -cp "$CP" "$1" 2>&1 | awk -v m="$2" 'index($0,m)&&/\(/{f=1} f{print} f&&/^$/{exit}' | grep -E "invoke|getfield|instanceof|checkcast|getstatic" | head -${3:-60} >> $OUT; }
p net.minecraft.client.renderer.entity.layers.RenderLayer "" 20
p net.minecraft.client.renderer.entity.layers.CustomHeadLayer "" 20
pc net.minecraft.client.renderer.entity.layers.CustomHeadLayer "public void submit" 60
p net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer "" 20
pc net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer "private void renderArmorPiece" 40
p net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer "renderLayers" 6
p net.minecraft.client.renderer.entity.VillagerRenderer "" 12
p net.minecraft.client.renderer.entity.IllagerRenderer "" 12
pc net.minecraft.client.renderer.entity.VillagerRenderer "VillagerRenderer(" 40
p net.minecraft.client.renderer.entity.LivingEntityRenderer "addLayer|getModel|extractRenderState" 8
p net.minecraft.client.renderer.entity.state.LivingEntityRenderState "headItem|headEquipment|wornHead|ItemStack" 10
p net.minecraft.client.renderer.entity.state.HumanoidRenderState "headEquipment|ItemStack" 6
p net.minecraft.client.renderer.entity.state.VillagerRenderState "" 20
p net.minecraft.client.renderer.entity.state.IllagerRenderState "" 20
p net.minecraft.client.model.npc.VillagerModel "" 20
p net.minecraft.client.model.VillagerModel "" 20
p net.minecraft.client.model.monster.illager.IllagerModel "" 20
p net.minecraft.client.model.IllagerModel "" 20
p net.minecraft.client.model.HeadedModel "" 10
p net.minecraft.client.model.HumanoidModel "head|ArmorModelSet|createArmor" 12
p net.minecraft.client.model.geom.ModelPart "translateAndRotate|getChild|visible|render\\(" 12
p net.minecraft.client.renderer.entity.EntityRendererProvider\$Context "" 20
p net.minecraft.client.model.geom.ModelLayers "ARMOR|PLAYER_ARMOR|ZOMBIE" 10
p net.minecraft.client.renderer.SubmitNodeCollector "submitModel" 10
p net.minecraft.client.renderer.entity.layers.WingsLayer "" 10
pc net.minecraft.client.renderer.entity.layers.WingsLayer "public void submit" 30
for j in $JARS; do unzip -l "$j" 2>/dev/null | awk '{print $4}' | grep -E "RenderLayerRegistrationCallback|FeatureRendererRegistrationCallback" | sed "s|^|$(basename $j): |" >> $OUT; done
for c in net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback\$RegistrationHelper; do p "$c" "" 10; done
p net.minecraft.client.renderer.entity.state.EntityRenderState "lightCoords|entityType" 5
wc -c $OUT
split -b 3500 $OUT chunk_
i=0
for f in chunk_*; do
  msg=$(cat $f | sed 's/%/%25/g' | tr '\n' '\r' | sed 's/\r/%0A/g')
  if [ $i -lt 10 ]; then echo "::warning title=probe$i::$msg"; elif [ $i -lt 20 ]; then echo "::notice title=probe$i::$msg"; else echo "::error title=probe$i::$msg"; fi
  i=$((i+1))
done
