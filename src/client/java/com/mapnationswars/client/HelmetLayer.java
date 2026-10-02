package com.mapnationswars.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Map Nations WARS 2.2.1: villagers and illagers have no armour layer in vanilla, so a helmet on their head was invisible.
 * This draws the helmet (dyed leather in the nation's colour, a king's gold crown...) on their head,
 * stretched a little because their heads are taller than a player's.
 */
public final class HelmetLayer extends RenderLayer<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> {
	private final HumanoidModel<HumanoidRenderState> helmet;
	private final EquipmentLayerRenderer equipment;
	/** a standing, unmoving pose for the helmet model: the real head pose comes from the villager's own head */
	private final HumanoidRenderState still = new HumanoidRenderState();

	public HelmetLayer(RenderLayerParent<LivingEntityRenderState, EntityModel<LivingEntityRenderState>> parent, EntityRendererProvider.Context context) {
		super(parent);
		this.helmet = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_ARMOR.head()));
		this.equipment = context.getEquipmentRenderer();
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, LivingEntityRenderState state, float yRot, float xRot) {
		if (!(state instanceof WornHelmet worn) || !(getParentModel() instanceof HeadedModel headed)) {
			return;
		}

		ItemStack stack = worn.mapnationswars$helmet();

		if (stack == null || stack.isEmpty() || !HumanoidArmorLayer.shouldRender(stack, EquipmentSlot.HEAD)) {
			return; // nothing, or a banner/skull - those the game already draws
		}

		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);

		if (equippable == null || equippable.assetId().isEmpty()) {
			return;
		}

		still.outlineColor = state.outlineColor;
		pose.pushPose();
		getParentModel().root().translateAndRotate(pose);
		headed.translateToHead(pose);
		pose.scale(1.0f, 1.25f, 1.0f); // villager and illager heads are 10 pixels tall, a player's 8
		equipment.renderLayers(EquipmentClientInfo.LayerType.HUMANOID, equippable.assetId().get(), helmet, still, stack, pose, collector, light, state.outlineColor);
		pose.popPose();
	}
}
