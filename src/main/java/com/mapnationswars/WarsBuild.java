package com.mapnationswars;

import java.util.List;
import java.util.Random;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import com.mapnationswars.nation.ProvinceData;

/**
 * Map Nations WARS 2.0: villages really grow.
 * Houses, farms and workshops a village builds appear in the world next to it (when someone is near enough for the land
 * to be loaded): a house with a bed, a wheat field with water, a workshop with a crafting table and a furnace.
 * And when a village has free beds and food, a child is born.
 */
public final class WarsBuild {
	private static final Random RANDOM = new Random();
	private static int ticks = 0;

	private WarsBuild() {
	}

	static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ticks++;

			if (ticks % 200 == 0 && WarsWorld.isGenerated()) {
				build(server);
			}

			if (ticks % 3000 == 0 && WarsWorld.isGenerated()) {
				births(server);
			}
		});
	}

	private static boolean loadedAndWatched(MinecraftServer server, ProvinceData p, ServerLevel level, double range) {
		if (!level.getChunkSource().hasChunk(p.x >> 4, p.z >> 4)) {
			return false;
		}

		for (ServerPlayer pl : PlayerLookup.all(server)) {
			if (pl.level() == level && Math.hypot(pl.getX() - p.x, pl.getZ() - p.z) < range) {
				return true;
			}
		}

		return false;
	}

	// ---------------------------------------------------------------- buildings

	private static void build(MinecraftServer server) {
		boolean changed = false;

		for (ProvinceData p : WarsWorld.provinces()) {
			if (p.type != ProvinceData.Type.VILLAGE || p.abandoned || p.pendingHouses + p.pendingFarms + p.pendingWorkshops <= 0) {
				continue;
			}

			ServerLevel level = WarsWorld.levelOf(server, p.dimension);

			if (level == null || !loadedAndWatched(server, p, level, 160)) {
				continue;
			}

			String what = p.pendingHouses > 0 ? "house" : p.pendingFarms > 0 ? "farm" : "workshop";
			int size = what.equals("farm") ? 7 : 5;
			BlockPos at = findSpot(level, p, size);

			if (at == null) {
				continue; // no room yet (maybe the land around isn't loaded)
			}

			switch (what) {
				case "house" -> {
					house(level, at, false);
					p.pendingHouses--;
				}
				case "farm" -> {
					farm(level, at);
					p.pendingFarms--;
				}
				default -> {
					house(level, at, true);
					p.pendingWorkshops--;
				}
			}

			changed = true;

			for (ServerPlayer pl : level.players()) {
				if (Math.hypot(pl.getX() - p.x, pl.getZ() - p.z) < 160) {
					pl.sendSystemMessage(Component.literal("⚒ " + p.name + " built a new " + what + " (" + at.getX() + ", " + at.getZ() + ").").withColor(0xC8E0A0));
				}
			}
		}

		if (changed) {
			WarsWorld.saveNow();
		}
	}

	/** A flat, free square of the given size near the village (rings outwards from its centre). */
	private static BlockPos findSpot(ServerLevel level, ProvinceData p, int size) {
		for (int r = 14; r <= 46; r += 4) {
			int steps = 8 + r / 3;
			int start = RANDOM.nextInt(steps);

			for (int i = 0; i < steps; i++) {
				double a = (start + i) * Math.PI * 2 / steps;
				int x = p.x + (int) Math.round(Math.cos(a) * r);
				int z = p.z + (int) Math.round(Math.sin(a) * r);

				if (fits(level, x, z, size)) {
					return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
				}
			}
		}

		return null;
	}

	private static boolean fits(ServerLevel level, int x0, int z0, int size) {
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;

		for (int dx = -1; dx <= size; dx++) {
			for (int dz = -1; dz <= size; dz++) {
				int x = x0 + dx;
				int z = z0 + dz;

				if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
					return false;
				}

				int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockState ground = level.getBlockState(new BlockPos(x, h - 1, z));
				BlockState above = level.getBlockState(new BlockPos(x, h, z));

				if (ground.liquid() || !ground.isSolid() || !(above.isAir() || above.canBeReplaced())) {
					return false;
				}

				// don't build on other buildings (planks, cobblestone, paths...)
				if (ground.is(Blocks.OAK_PLANKS) || ground.is(Blocks.COBBLESTONE) || ground.is(Blocks.DIRT_PATH) || ground.is(Blocks.FARMLAND)) {
					return false;
				}

				min = Math.min(min, h);
				max = Math.max(max, h);
			}
		}

		return max - min <= 1;
	}

	private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
		level.setBlock(new BlockPos(x, y, z), state, 3);
	}

	private static Block bed() {
		try {
			Block b = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("minecraft", "red_bed"));
			return b == null || b == Blocks.AIR ? null : b;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** A small house (5x5) with a bed - or a workshop with a crafting table and a furnace (villagers take jobs there). */
	private static void house(ServerLevel level, BlockPos at, boolean workshop) {
		int x0 = at.getX();
		int z0 = at.getZ();
		int y = at.getY();

		for (int dx = 0; dx < 5; dx++) {
			for (int dz = 0; dz < 5; dz++) {
				int x = x0 + dx;
				int z = z0 + dz;
				set(level, x, y - 2, z, Blocks.COBBLESTONE.defaultBlockState()); // foundation
				set(level, x, y - 1, z, Blocks.OAK_PLANKS.defaultBlockState()); // floor
				boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
				boolean corner = (dx == 0 || dx == 4) && (dz == 0 || dz == 4);

				for (int h = 0; h < 3; h++) {
					BlockState s = !edge ? Blocks.AIR.defaultBlockState() : corner ? Blocks.OAK_LOG.defaultBlockState()
							: (h == 1 && (dx == 2 || dz == 2) ? Blocks.GLASS_PANE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
					set(level, x, y + h, z, s);
				}

				set(level, x, y + 3, z, Blocks.OAK_PLANKS.defaultBlockState()); // roof
				set(level, x, y + 4, z, Blocks.AIR.defaultBlockState());
			}
		}

		// the door, in the middle of the front wall
		BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
		set(level, x0 + 2, y, z0, door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
		set(level, x0 + 2, y + 1, z0, door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
		set(level, x0 + 2, y, z0 - 1, Blocks.AIR.defaultBlockState()); // room to walk in
		set(level, x0 + 2, y + 1, z0 - 1, Blocks.AIR.defaultBlockState());
		set(level, x0 + 3, y, z0 + 1, Blocks.TORCH.defaultBlockState());

		if (workshop) {
			set(level, x0 + 1, y, z0 + 3, Blocks.CRAFTING_TABLE.defaultBlockState());
			set(level, x0 + 3, y, z0 + 3, Blocks.FURNACE.defaultBlockState());
		} else {
			Block bed = bed();

			if (bed != null) {
				// foot at (1,3), head at (2,3): the bed faces east
				BlockState b = bed.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
				set(level, x0 + 1, y, z0 + 3, b.setValue(BlockStateProperties.BED_PART, BedPart.FOOT));
				set(level, x0 + 2, y, z0 + 3, b.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
			}
		}
	}

	/** A 7x7 wheat field around a water block. */
	private static void farm(ServerLevel level, BlockPos at) {
		int x0 = at.getX();
		int z0 = at.getZ();
		int y = at.getY() - 1;

		for (int dx = 0; dx < 7; dx++) {
			for (int dz = 0; dz < 7; dz++) {
				int x = x0 + dx;
				int z = z0 + dz;
				set(level, x, y + 1, z, Blocks.AIR.defaultBlockState());
				set(level, x, y + 2, z, Blocks.AIR.defaultBlockState());

				if (dx == 3 && dz == 3) {
					set(level, x, y, z, Blocks.WATER.defaultBlockState());
				} else {
					set(level, x, y, z, Blocks.FARMLAND.defaultBlockState());
					set(level, x, y + 1, z, Blocks.WHEAT.defaultBlockState());
				}
			}
		}
	}

	// ---------------------------------------------------------------- children

	/** Villages with free beds and food get a child (counted as a villager once it's there). */
	private static void births(MinecraftServer server) {
		for (ProvinceData p : WarsWorld.provinces()) {
			if (p.type != ProvinceData.Type.VILLAGE || p.abandoned) {
				continue;
			}

			ServerLevel level = WarsWorld.levelOf(server, p.dimension);

			if (level == null || !loadedAndWatched(server, p, level, 128)) {
				continue;
			}

			AABB box = new AABB(p.x - 56, level.getMinY(), p.z - 56, p.x + 56, level.getMinY() + level.getHeight(), p.z + 56);
			List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box);
			villagers.removeIf(WarsBuild::isWarMob);
			int people = villagers.size();

			if (people == 0 || people >= p.beds() || p.food < people || p.happiness < 40) {
				continue;
			}

			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.x, p.z);
			Mob child = WarsWar.spawnMob(level, "VILLAGER", p.x, y, p.z);

			if (child instanceof AgeableMob baby) {
				WarsWar.untrack(child.getUUID()); // a real villager: it stays
				baby.setAge(-24000);
				p.food = Math.max(0, p.food - 3);

				for (ServerPlayer pl : level.players()) {
					if (Math.hypot(pl.getX() - p.x, pl.getZ() - p.z) < 96) {
						pl.sendSystemMessage(Component.literal("❤ A child was born in " + p.name + ".").withColor(0xF0B0C0));
					}
				}
			}
		}
	}

	/** Soldiers, kings and guards of the war are not villagers of the village. */
	static boolean isWarMob(net.minecraft.world.entity.Entity e) {
		if (!e.hasCustomName() || e.getCustomName() == null) {
			return false;
		}

		String name = e.getCustomName().getString();
		return name.startsWith(WarsWar.soldierPrefix()) || name.startsWith("♛ ");
	}
}
