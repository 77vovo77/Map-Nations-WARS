#!/bin/bash
set +e
OUT=probe_out.txt
: > $OUT
JARS=$(find ~/.gradle/caches .gradle -name "*.jar" 2>/dev/null | grep -v sources)
CP=$(echo $JARS | tr ' ' ':')
p() { echo "== $1" >> $OUT; javap -public -cp "$CP" "$1" 2>&1 | grep -E "${2:-.}" | head -${3:-80} >> $OUT; }
pc() { echo "== CODE $1 $2" >> $OUT; javap -c -p -cp "$CP" "$1" 2>&1 | awk -v m="$2" 'index($0,m)&&/\(/{f=1} f{print} f&&/^$/{exit}' | grep -E "invoke|getfield|instanceof|checkcast|getstatic" | head -${3:-60} >> $OUT; }
p net.minecraft.client.renderer.entity.ArmorModelSet "" 20
echo "== ModelLayers ARMOR names" >> $OUT; javap -public -cp "$CP" net.minecraft.client.model.geom.ModelLayers | grep -E "ArmorModelSet" | grep -oE "[A-Z_]+;" | tr '\n' ' ' >> $OUT
p net.minecraft.client.model.HumanoidModel "HumanoidModel\\(|class " 6
p net.minecraft.client.renderer.entity.state.HumanoidRenderState "HumanoidRenderState\\(|class " 4
p net.minecraft.client.renderer.entity.state.UndeadRenderState "class " 3
p net.minecraft.client.renderer.entity.state.ZombieRenderState "class " 3
p net.minecraft.client.renderer.entity.state.EntityRenderState "outlineColor" 3
p net.minecraft.client.renderer.entity.LivingEntityRenderer "class |addLayer" 4
p net.minecraft.client.resources.model.EquipmentClientInfo\$LayerType " HUMANOID;" 2
p net.minecraft.world.item.equipment.Equippable "assetId" 2
p net.minecraft.client.model.EntityModel "class |root\\(" 4
p net.minecraft.client.model.Model "class |root\\(" 4
p com.mojang.blaze3d.vertex.PoseStack "pushPose|popPose|scale|translate" 8
pc net.minecraft.client.renderer.entity.LivingEntityRenderer "public void extractRenderState(T" 60
pc net.minecraft.client.renderer.entity.IllagerRenderer "public void extractRenderState(T" 20
wc -c $OUT
split -b 3500 $OUT chunk_
i=0
for f in chunk_*; do
  msg=$(cat $f | sed 's/%/%25/g' | tr '\n' '\r' | sed 's/\r/%0A/g')
  if [ $i -lt 10 ]; then echo "::warning title=probe$i::$msg"; elif [ $i -lt 20 ]; then echo "::notice title=probe$i::$msg"; else echo "::error title=probe$i::$msg"; fi
  i=$((i+1))
done
