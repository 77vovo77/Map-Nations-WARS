package com.mapnationswars.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joml.Matrix3x2fStack;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLScancode;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;

import com.mapnationswars.MapNationsMod;
import com.mapnationswars.nation.AllianceData;
import com.mapnationswars.nation.MarkerData;
import com.mapnationswars.nation.MarkerType;
import com.mapnationswars.nation.NationData;
import com.mapnationswars.network.ClaimAreaPayload;
import com.mapnationswars.network.MarkerActionPayload;
import com.mapnationswars.network.MarkerAreaPayload;
import com.mapnationswars.network.PlayersPayload;
import com.mapnationswars.network.ToggleClaimPayload;

/** The full-screen map (Map tab) and the claiming map (Claims tab). */
public class MapScreen extends MapNationsBaseScreen {
	private static final double MIN_ZOOM = 0.125; // 1 pixel = 8 blocks
	private static final double MAX_ZOOM = 16.0;  // 1 block = 16 pixels
	/** How far (in chunks) from the middle of the view unexplored land is filled in. */
	private static final int PREVIEW_RADIUS = 160;
	private static final int HEAD_SIZE = 10;
	private static final int PANEL_W = 132;
	private static final int CONFLICT_RED = 0xFFFF1A1A;

	// Remembered while the game runs, so switching tabs keeps your view.
	private static double savedZoom = 2.0;
	/** What the land is coloured by: 0 = nothing, 1 = nations, 2 = alliances. */
	private static int mapMode = 1;
	private static final String[] MODE_NAMES = {"Off", "Nations", "Alliances"};
	/** Show the borders of every city / village / castle, not only the one under the mouse. */
	private static boolean showSettlementBorders = false;
	private static boolean savedFollow = true;
	private static double savedCenterX;
	private static double savedCenterZ;
	private static boolean panelOpen = false;
	/** 0 all, 1 only mine (private), 2 my nation, 3 everyone, 4 only important ones */
	private static int markerFilter = 0;
	private static final String[] FILTER_NAMES = {"All", "Only me", "Nation", "Everyone", "Important", "Off"};
	private static final int FILTER_OFF = 5;
	private static int filterBeforeHiding = 0;

	private final boolean claimMode;
	private double centerX;
	private double centerZ;
	private double zoom = savedZoom;
	private long lastPreviewRequest = 0;
	/** Bottom of the mouse-controls box in the top-right corner (the nation list goes under it). */
	private int hintBottom = 0;
	private boolean followPlayer = savedFollow;

	private boolean mouseDown = false;
	private boolean dragged = false;
	private double pressX;
	private double pressY;

	/** The marker type picked in the side panel, waiting for a click on the map. */
	private MarkerType placingType = null;
	/** A marker of yours being moved to a new spot. */
	private MarkerData movingMarker = null;

	// the nation list in the top-right corner (for clicking)
	private final List<UUID> legendRows = new ArrayList<>();
	private int legendX;
	private int legendY;
	private int legendW;
	private int mouseXNow;
	private int mouseYNow;

	// editing a settlement's borders
	private MarkerData editingArea = null;
	/** True while choosing the borders of a settlement that isn't placed yet. */
	private boolean pendingPlace = false;
	/** The borders as they were when editing started (for Reset, and to see if anything changed). */
	private final java.util.Set<Long> originalArea = new java.util.HashSet<>();
	private Button applyButton;
	private Button resetButton;
	private Button cancelButton;
	private int editBarLeft, editBarRight, editBarTop, editBarBottom;
	private final java.util.LinkedHashSet<Long> workingArea = new java.util.LinkedHashSet<>();

	// drag claiming in the Claims tab
	private int pressButton;
	private boolean selecting = false;
	private int selFromX;
	private int selFromZ;
	private int selToX;
	private int selToZ;

	/** The "View" drop-down with the map options. */
	private boolean viewOpen = false;
	private int viewX;
	private int viewMenuTop;
	private int viewMenuBottom;
	private static final int VIEW_W = 168;

	/** A player to draw on the map. */
	private record Dot(UUID id, String name, double x, double z) {
	}

	public MapScreen(boolean claimMode) {
		super(Tab.MAP); // WARS has no Claims tab: land belongs to provinces
		this.claimMode = claimMode;

		if (!savedFollow) {
			this.centerX = savedCenterX;
			this.centerZ = savedCenterZ;
		}
	}

	// ---------------------------------------------------------------- setup

	private boolean panelVisible() {
		return panelOpen && !this.claimMode;
	}

	/** Left edge of the marker panel (the screen width when it's closed). */
	private int panelLeft() {
		return this.panelVisible() ? this.width - PANEL_W : this.width;
	}

	@Override
	protected void init() {
		int x = this.addTabs();

		boolean compact = this.compact();

		if (!this.claimMode) {
			int viewW = compact ? 48 : 60;
			this.viewX = x;
			this.addRenderableWidget(Button.builder(Component.literal(this.viewOpen ? "View \u25B2" : "View \u25BC"), b -> {
				this.viewOpen = !this.viewOpen;
				this.rebuildWidgets();
			}).pos(x, 6).size(viewW, 20).build());
			x += viewW + 4;
		}

		int centerW = compact ? 44 : 52;
		this.addRenderableWidget(Button.builder(Component.literal("Center"), b -> this.followPlayer = true)
				.pos(x, 6).size(centerW, 20).build());
		x += centerW + 4;

		if (!this.claimMode) {
			// the little arrow on the right edge that opens / closes the marker panel
			this.addRenderableWidget(Button.builder(Component.literal(panelOpen ? "▶" : "◀"), b -> {
				panelOpen = !panelOpen;

				if (!panelOpen) {
					this.placingType = null;
				}

				this.rebuildWidgets();
			}).pos(this.panelLeft() - 16, this.height / 2 - 12).size(14, 24).build());
		}

		// zoom buttons (only when there is room; the mouse wheel zooms too)
		if (x + 50 <= this.width - 50) {
			this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.zoomAt(this.width / 2.0, this.height / 2.0, 2.0))
					.pos(this.width - 50, 6).size(20, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("-"), b -> this.zoomAt(this.width / 2.0, this.height / 2.0, 0.5))
					.pos(this.width - 26, 6).size(20, 20).build());
		}

		// the View drop-down: map colours, settlement borders, markers, minimap
		if (this.viewOpen && !this.claimMode) {
			int vx = Math.min(this.viewX, this.width - VIEW_W - 4);
			int vy = TOP_BAR + 4;
			this.viewMenuTop = vy - 4;
			List<Component> labels = List.of(
					Component.literal("Map colours: " + MODE_NAMES[mapMode]),
					Component.literal("Settlement borders: " + (showSettlementBorders ? "ON" : "OFF")),
					Component.literal("Markers: " + FILTER_NAMES[markerFilter]),
					Component.literal("Minimap: " + (ClientConfig.minimap() ? "ON" : "OFF")),
					Component.literal("Minimap place: " + ClientConfig.CORNERS[ClientConfig.minimapCorner()]),
					Component.literal("Minimap size: " + ClientConfig.SIZES[ClientConfig.minimapSize()]));
			List<Runnable> actions = List.of(
					() -> mapMode = (mapMode + 1) % 3,
					() -> showSettlementBorders = !showSettlementBorders,
					() -> markerFilter = (markerFilter + 1) % FILTER_NAMES.length,
					() -> ClientConfig.setMinimap(!ClientConfig.minimap()),
					ClientConfig::cycleMinimapCorner,
					ClientConfig::cycleMinimapSize);

			for (int i = 0; i < labels.size(); i++) {
				Runnable action = actions.get(i);
				this.addRenderableWidget(Button.builder(labels.get(i), b -> {
					action.run();
					this.rebuildWidgets();
				}).pos(vx + 4, vy + 16 + i * 22).size(VIEW_W - 8, 20).build());
			}

			this.viewMenuBottom = vy + 16 + labels.size() * 22 + 2;
		}

		this.applyButton = null;
		this.resetButton = null;
		this.cancelButton = null;

		if (this.editingArea != null) {
			// Apply / Reset / Cancel bar for the borders being edited (moved above the info text every frame)
			int bw = 96;
			int bx = this.width / 2 - (bw * 3 + 8) / 2;
			this.applyButton = this.addRenderableWidget(Button.builder(Component.literal(this.pendingPlace ? "\u2714 Place it" : "\u2714 Apply")
					.withColor(0x7CFF7C), b -> this.saveArea()).pos(bx, this.height - 80).size(bw, 20).build());
			this.resetButton = this.addRenderableWidget(Button.builder(Component.literal("\u21BA Reset"), b -> this.resetArea())
					.pos(bx + bw + 4, this.height - 80).size(bw, 20).build());
			this.cancelButton = this.addRenderableWidget(Button.builder(Component.literal("\u2716 Cancel").withColor(0xFF7C7C),
					b -> this.cancelArea()).pos(bx + (bw + 4) * 2, this.height - 80).size(bw, 20).build());
		}

		if (this.minecraft.player != null && this.followPlayer) {
			this.centerX = this.minecraft.player.getX();
			this.centerZ = this.minecraft.player.getZ();
		}
	}

	private boolean showPolitical() {
		return mapMode == 1 || this.claimMode;
	}

	private boolean showAlliances() {
		return mapMode == 2 && !this.claimMode;
	}

	/** P: nations -> alliances -> off -> nations. */
	private void togglePolitical() {
		mapMode = (mapMode + 1) % 3;
		this.rebuildWidgets();
	}

	@Override
	public void removed() {
		savedZoom = this.zoom;
		savedFollow = this.followPlayer;
		savedCenterX = this.centerX;
		savedCenterZ = this.centerZ;
		super.removed();
	}

	// ---------------------------------------------------------------- coordinates

	private double toScreenX(double worldX) {
		return (worldX - this.centerX) * this.zoom + this.width / 2.0;
	}

	private double toScreenY(double worldZ) {
		return (worldZ - this.centerZ) * this.zoom + this.height / 2.0;
	}

	private double toWorldX(double screenX) {
		return this.centerX + (screenX - this.width / 2.0) / this.zoom;
	}

	private double toWorldZ(double screenY) {
		return this.centerZ + (screenY - this.height / 2.0) / this.zoom;
	}

	/** Turns a screen position into a pixel we can pass to fill(), without overflowing on huge numbers. */
	private static int clampPx(double v) {
		return (int) Math.floor(Mth.clamp(v, -10000.0, 10000.0));
	}

	private String dimensionId() {
		ClientLevel level = this.minecraft.level;
		return level == null ? "" : level.dimension().identifier().toString();
	}

	private boolean overPanel(double mouseX, double mouseY) {
		return this.panelVisible() && mouseX >= this.panelLeft() && mouseY > TOP_BAR;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, 0xFF15181C);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		LocalPlayer player = this.minecraft.player;
		ClientLevel level = this.minecraft.level;

		if (player == null || level == null) {
			this.onClose();
			return;
		}

		if (this.followPlayer) {
			this.centerX = player.getX();
			this.centerZ = player.getZ();
		}

		String dim = this.dimensionId();
		this.mouseXNow = mouseX;
		this.mouseYNow = mouseY;
		boolean overMap = mouseY > TOP_BAR && !this.overPanel(mouseX, mouseY);
		int hoverCX = Math.floorDiv(Mth.floor(this.toWorldX(mouseX)), 16);
		int hoverCZ = Math.floorDiv(Mth.floor(this.toWorldZ(mouseY)), 16);

		graphics.fill(0, 0, this.width, this.height, 0xFF15181C);
		this.drawTerrain(graphics);

		NationData hoveredNation = null;
		List<UUID> hoveredClaimants = List.of();

		if (this.showPolitical()) {
			hoveredClaimants = overMap ? ClientNations.claimants(dim, hoverCX, hoverCZ) : List.of();
			hoveredNation = hoveredClaimants.size() == 1 ? ClientNations.get(hoveredClaimants.get(0)) : null;
			this.drawClaims(graphics, dim, hoveredNation);
			this.drawProposals(graphics, dim);
		}

		// alliance map: land coloured by the alliance its nation belongs to
		AllianceData hoveredAlliance = null;
		NationData hoveredAllianceNation = null;

		if (this.showAlliances()) {
			UUID owner = overMap ? ClientNations.claims(dim).get(MapNationsMod.chunkKey(hoverCX, hoverCZ)) : null;
			hoveredAllianceNation = ClientNations.get(owner);
			hoveredAlliance = owner != null ? ClientNations.allianceOf(owner) : null;
			this.drawAllianceClaims(graphics, dim, hoveredAlliance, hoveredAllianceNation);
		}

		if (this.claimMode) {
			this.drawChunkGrid(graphics, mouseY, hoverCX, hoverCZ);
		}

		this.drawWorldBorder(graphics, level, player);

		if (this.showPolitical()) {
			this.drawLabels(graphics, dim);
			this.drawConflictSigns(graphics, dim);
		}

		if (this.showAlliances()) {
			this.drawAllianceLabels(graphics, dim);
		}

		// every settlement's borders (View menu option)
		if (showSettlementBorders && this.editingArea == null) {
			for (MarkerData m : ClientMarkers.all()) {
				if (m.dimension.equals(dim) && (m.type.settlement || m.province != null) && !m.area.isEmpty()) {
					this.drawArea(graphics, m.area, 0xFFD54F, false);
				}
			}
		}

		MarkerData hoveredMarker = this.drawMarkers(graphics, dim, mouseX, mouseY, overMap);

		if (this.editingArea != null) {
			this.drawArea(graphics, this.workingArea, 0xFFD54F, true);
		} else if (hoveredMarker != null && (hoveredMarker.type.settlement || hoveredMarker.province != null) && !hoveredMarker.area.isEmpty()) {
			this.drawArea(graphics, hoveredMarker.area, 0xFFD54F, false); // show the settlement's borders
		}
		Dot hoveredPlayer = this.drawPlayers(graphics, player, dim, mouseX, mouseY, overMap);
		this.drawPlayerArrow(graphics, player);

		graphics.nextStratum(); // screen overlays above the map
		this.drawTopBar(graphics);
		this.drawCompass(graphics);

		this.hintBottom = this.drawControlsHint(graphics);

		if (this.showPolitical()) {
			this.drawLegend(graphics, dim);
		} else if (this.showAlliances()) {
			this.drawAllianceLegend(graphics, dim);
		} else {
			this.legendRows.clear();
		}

		MarkerType hoveredPanelType = this.panelVisible() ? this.drawPanel(graphics, mouseX, mouseY) : null;
		int infoTop = this.drawInfo(graphics, mouseX, mouseY, hoverCX, hoverCZ, overMap);
		this.drawLocation(graphics, player, dim, infoTop);
		this.drawStatus(graphics, TOP_BAR + 18);
		this.drawEditBar(graphics, infoTop);

		if (this.viewOpen && !this.claimMode) {
			int vx = Math.min(this.viewX, this.width - VIEW_W - 4);
			graphics.fill(vx - 1, this.viewMenuTop - 1, vx + VIEW_W + 1, this.viewMenuBottom + 1, C_EDGE);
			graphics.fill(vx, this.viewMenuTop, vx + VIEW_W, this.viewMenuBottom, 0xF0161A1F);
			graphics.text(this.font, "Map view", vx + 6, this.viewMenuTop + 5, 0xFFFFD060);
		}

		super.extractRenderState(graphics, mouseX, mouseY, delta);

		if (this.placingType != null && overMap) {
			this.drawPlacingGhost(graphics, dim, mouseX, mouseY);
		}

		if (this.movingMarker != null && overMap) {
			this.drawMovingGhost(graphics, mouseX, mouseY);
		}

		if (this.selecting && this.dragged) {
			graphics.nextStratum();
			this.drawSelection(graphics, mouseX, mouseY);
		}

		// tooltips last, so they are on top
		if (hoveredPanelType != null) {
			this.panelTooltip(graphics, hoveredPanelType, mouseX, mouseY);
		} else if (this.placingType != null || this.movingMarker != null || this.selecting) {
			// no tooltips while placing / moving / selecting, they would cover the spot
		} else if (hoveredMarker != null) {
			this.markerTooltip(graphics, hoveredMarker, mouseX, mouseY);
		} else if (hoveredPlayer != null) {
			this.playerTooltip(graphics, hoveredPlayer, mouseX, mouseY);
		} else if (hoveredClaimants.size() > 1) {
			this.conflictTooltip(graphics, hoveredClaimants, hoverCX, hoverCZ, mouseX, mouseY);
		} else if (this.showPolitical() && overMap && ClientNations.proposalAt(dim, hoverCX, hoverCZ) != null) {
			this.proposalTooltip(graphics, ClientNations.proposalAt(dim, hoverCX, hoverCZ), mouseX, mouseY);
		} else if (hoveredNation != null) {
			this.nationTooltip(graphics, hoveredNation, mouseX, mouseY);
		} else if (hoveredAllianceNation != null) {
			this.allianceTooltip(graphics, hoveredAlliance, hoveredAllianceNation, mouseX, mouseY);
		}
	}

	private void drawTerrain(GuiGraphicsExtractor graphics) {
		MapData.beginFrame();
		int size = MapRegion.SIZE;

		int minRX = Math.floorDiv(Mth.floor(this.toWorldX(0)), size);
		int maxRX = Math.floorDiv(Mth.floor(this.toWorldX(this.width)), size);
		int minRZ = Math.floorDiv(Mth.floor(this.toWorldZ(0)), size);
		int maxRZ = Math.floorDiv(Mth.floor(this.toWorldZ(this.height)), size);

		Matrix3x2fStack pose = graphics.pose();

		// the land painted by the server only goes up to the world border; past it you only see what you explored
		int[] border = this.minecraft.level != null && this.minecraft.player != null
				? MapData.borderBox(this.minecraft.level, this.minecraft.player.getX(), this.minecraft.player.getZ()) : null;

		// first the land painted by the server (places you haven't been), then what you really explored on top
		for (int layer = 0; layer < 2; layer++) {
			boolean clipped = false;

			if (layer == 0) {
				if (border == null) {
					continue;
				}

				int bl = clampPx(this.toScreenX(border[0]));
				int bt = clampPx(this.toScreenY(border[1]));
				int br = clampPx(this.toScreenX(border[2]));
				int bb = clampPx(this.toScreenY(border[3]));

				if (br <= bl || bb <= bt) {
					continue;
				}

				graphics.enableScissor(Math.max(bl, 0), Math.max(bt, 0), Math.min(br, this.width), Math.min(bb, this.height));
				clipped = true;
			}

			for (int rz = minRZ; rz <= maxRZ; rz++) {
				for (int rx = minRX; rx <= maxRX; rx++) {
					MapRegion region = layer == 0 ? MapData.getPreviewRegion(rx, rz) : MapData.getRegion(rx, rz);

					if (region == null) {
						continue;
					}

					boolean upload = region.textureDirty && MapData.tryUseUpload();
					Identifier texture = region.prepareTexture(this.minecraft, upload);

					if (texture == null) {
						continue;
					}

					float sx = (float) this.toScreenX((double) rx * size);
					float sy = (float) this.toScreenY((double) rz * size);

					pose.pushMatrix();
					pose.translate(sx, sy);
					pose.scale((float) this.zoom);
					graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0F, 0.0F, size, size, size, size);
					pose.popMatrix();
				}
			}

			if (clipped) {
				graphics.disableScissor();
			}
		}

		// ask the server for the parts of the view that are still empty (a few times a second)
		long now = System.currentTimeMillis();

		if (now - this.lastPreviewRequest > 200) {
			this.lastPreviewRequest = now;
			int minCX = Math.floorDiv(Mth.floor(this.toWorldX(0)), 16);
			int maxCX = Math.floorDiv(Mth.floor(this.toWorldX(this.width)), 16);
			int minCZ = Math.floorDiv(Mth.floor(this.toWorldZ(TOP_BAR)), 16);
			int maxCZ = Math.floorDiv(Mth.floor(this.toWorldZ(this.height)), 16);
			int centerCX = Math.floorDiv(Mth.floor(this.centerX), 16);
			int centerCZ = Math.floorDiv(Mth.floor(this.centerZ), 16);

			if (border != null) {
				// never ask for land outside the world border
				minCX = Math.max(minCX, Math.floorDiv(border[0], 16));
				minCZ = Math.max(minCZ, Math.floorDiv(border[1], 16));
				maxCX = Math.min(maxCX, Math.floorDiv(border[2] - 1, 16));
				maxCZ = Math.min(maxCZ, Math.floorDiv(border[3] - 1, 16));

				if (minCX <= maxCX && minCZ <= maxCZ) {
					MapData.requestPreview(centerCX, centerCZ, minCX, minCZ, maxCX, maxCZ, PREVIEW_RADIUS);
				}
			}
		}
	}

	/** True if the chunk is claimed by exactly this one nation (no conflict). */
	private static boolean solelyOwnedBy(Map<Long, List<UUID>> claims, long key, UUID nation) {
		List<UUID> list = claims.get(key);
		return list != null && list.size() == 1 && list.get(0).equals(nation);
	}

	private static boolean isContested(Map<Long, List<UUID>> claims, long key) {
		List<UUID> list = claims.get(key);
		return list != null && list.size() > 1;
	}

	private void drawClaims(GuiGraphicsExtractor graphics, String dim, NationData hovered) {
		Map<Long, List<UUID>> claims = ClientNations.claimantsMap(dim);

		if (claims.isEmpty()) {
			return;
		}

		double chunkPx = 16 * this.zoom;
		boolean borders = chunkPx >= 3;
		int line = chunkPx >= 24 ? 2 : 1;

		for (Map.Entry<Long, List<UUID>> entry : claims.entrySet()) {
			List<UUID> nations = entry.getValue();

			if (nations.isEmpty()) {
				continue;
			}

			int cx = MapNationsMod.keyX(entry.getKey());
			int cz = MapNationsMod.keyZ(entry.getKey());
			double sx1 = this.toScreenX(cx * 16.0);
			double sy1 = this.toScreenY(cz * 16.0);

			if (sx1 > this.width || sy1 > this.height || sx1 + chunkPx < 0 || sy1 + chunkPx < 0) {
				continue;
			}

			int x1 = clampPx(sx1);
			int y1 = clampPx(sy1);
			int x2 = Math.max(x1 + 1, clampPx(sx1 + chunkPx));
			int y2 = Math.max(y1 + 1, clampPx(sy1 + chunkPx));
			long north = MapNationsMod.chunkKey(cx, cz - 1);
			long south = MapNationsMod.chunkKey(cx, cz + 1);
			long west = MapNationsMod.chunkKey(cx - 1, cz);
			long east = MapNationsMod.chunkKey(cx + 1, cz);

			if (nations.size() > 1) {
				// conflict zone: stripes in every claiming nation's colour, sharp red outline
				this.drawStripes(graphics, nations, cx, cz, x1, y1, x2, y2);
				int edge = Math.max(2, line);

				if (!isContested(claims, north)) {
					graphics.fill(x1, y1, x2, y1 + edge, CONFLICT_RED);
				}
				if (!isContested(claims, south)) {
					graphics.fill(x1, y2 - edge, x2, y2, CONFLICT_RED);
				}
				if (!isContested(claims, west)) {
					graphics.fill(x1, y1, x1 + edge, y2, CONFLICT_RED);
				}
				if (!isContested(claims, east)) {
					graphics.fill(x2 - edge, y1, x2, y2, CONFLICT_RED);
				}

				continue;
			}

			NationData nation = ClientNations.get(nations.get(0));

			if (nation == null) {
				continue;
			}

			boolean isHovered = nation == hovered;
			graphics.fill(x1, y1, x2, y2, (isHovered ? 0xB8000000 : 0x9A000000) | nation.color);

			if (!borders) {
				continue;
			}

			// country borders: only where the neighbour chunk is not the same nation
			int edge = isHovered ? line + 1 : line;
			int edgeColor = isHovered ? 0xFFFFFFFF : 0xFF000000 | nation.color;
			UUID id = nation.id;

			if (!solelyOwnedBy(claims, north, id)) {
				graphics.fill(x1, y1, x2, y1 + edge, edgeColor);
			}
			if (!solelyOwnedBy(claims, south, id)) {
				graphics.fill(x1, y2 - edge, x2, y2, edgeColor);
			}
			if (!solelyOwnedBy(claims, west, id)) {
				graphics.fill(x1, y1, x1 + edge, y2, edgeColor);
			}
			if (!solelyOwnedBy(claims, east, id)) {
				graphics.fill(x2 - edge, y1, x2, y2, edgeColor);
			}
		}
	}

	/**
	 * Diagonal stripes in the colours of all nations fighting over a chunk.
	 * The stripes follow world coordinates, so they line up across neighbouring chunks.
	 */
	private void drawStripes(GuiGraphicsExtractor graphics, List<UUID> nations, int cx, int cz, int x1, int y1, int x2, int y2) {
		int[] colors = new int[nations.size()];

		for (int i = 0; i < colors.length; i++) {
			NationData nation = ClientNations.get(nations.get(i));
			colors[i] = nation != null ? nation.color : 0x777777;
		}

		this.drawStripeColors(graphics, colors, 0xC0, cx, cz, x1, y1, x2, y2);
	}

	/** Diagonal stripes in the given colours over one chunk (they line up across chunks). */
	private void drawStripeColors(GuiGraphicsExtractor graphics, int[] colors, int alpha, int cx, int cz, int x1, int y1, int x2, int y2) {
		int n = colors.length;

		int sx1 = Math.max(x1, 0);
		int sy1 = Math.max(y1, 0);
		int sx2 = Math.min(x2, this.width);
		int sy2 = Math.min(y2, this.height);

		if (sx2 <= sx1 || sy2 <= sy1) {
			return;
		}

		graphics.enableScissor(sx1, sy1, sx2, sy2);
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate((float) this.toScreenX(cx * 16.0), (float) this.toScreenY(cz * 16.0));
		pose.rotate((float) (Math.PI / 4));
		pose.scale((float) (this.zoom / 4)); // local units are quarter blocks

		double stripe = 3.0;                           // stripe width in blocks
		double u0 = (cx + cz) * 16 / Math.sqrt(2);     // where this chunk starts along the stripe axis
		double span = 32 / Math.sqrt(2);               // how far the chunk reaches along that axis
		int vHalf = (int) Math.ceil(16 / Math.sqrt(2) * 4) + 4;
		int kStart = (int) Math.floor(u0 / stripe) - 1;
		int kEnd = (int) Math.ceil((u0 + span) / stripe) + 1;

		for (int k = kStart; k <= kEnd; k++) {
			int a = (int) Math.round((k * stripe - u0) * 4);
			int b = (int) Math.round(((k + 1) * stripe - u0) * 4);
			graphics.fill(a, -vHalf, b, vHalf, (alpha << 24) | colors[Math.floorMod(k, n)]);
		}

		pose.popMatrix();
		graphics.disableScissor();
	}

	/** A "!" warning sign in the middle of every conflict zone. */
	private void drawConflictSigns(GuiGraphicsExtractor graphics, String dim) {
		for (ClientNations.ConflictZone zone : ClientNations.conflicts(dim)) {
			float sx = (float) this.toScreenX(zone.centerX());
			float sy = (float) this.toScreenY(zone.centerZ());

			if (sx < -20 || sy < -20 || sx > this.width + 20 || sy > this.height + 20) {
				continue;
			}

			int size = zone.chunks() >= 4 && this.zoom >= 0.5 ? 20 : 14;
			MarkerIcons.sprite(graphics, MarkerIcons.CONFLICT, sx, sy, size);
		}
	}

	/**
	 * Nation names (and banners) written across their land, sized to fit.
	 * The biggest piece of every nation keeps a readable name and banner even when zoomed far out.
	 */
	private void drawLabels(GuiGraphicsExtractor graphics, String dim) {
		Matrix3x2fStack pose = graphics.pose();
		List<ClientNations.TerritoryLabel> labels = ClientNations.labels(dim);
		Map<UUID, ClientNations.TerritoryLabel> mainPiece = new HashMap<>();

		for (ClientNations.TerritoryLabel label : labels) {
			ClientNations.TerritoryLabel best = mainPiece.get(label.nation());

			if (best == null || label.chunks() > best.chunks()) {
				mainPiece.put(label.nation(), label);
			}
		}

		for (ClientNations.TerritoryLabel label : labels) {
			NationData nation = ClientNations.get(label.nation());

			if (nation == null) {
				continue;
			}

			double sx = this.toScreenX(label.centerX());
			double sy = this.toScreenY(label.centerZ());

			if (sx < -300 || sy < -300 || sx > this.width + 300 || sy > this.height + 300) {
				continue;
			}

			boolean main = mainPiece.get(label.nation()) == label;
			int textW = Math.max(1, this.font.width(nation.name));
			double lengthPx = label.lengthBlocks() * this.zoom;
			double thicknessPx = label.thicknessBlocks() * this.zoom;
			boolean hasBanner = !nation.banner.isEmpty();

			// how big the name can be and still fit inside the land
			double fit = Math.min(lengthPx * 0.8 / textW, thicknessPx * 0.7 / (hasBanner ? 28 : 9));
			double scale;
			float angle;
			boolean withBanner;

			if (fit >= 0.7) {
				scale = Math.min(fit, 4.0);
				angle = label.angle();
				withBanner = hasBanner;
			} else if (main) {
				// zoomed far out: still show the name (and banner) above the land, flat and readable
				scale = 0.8;
				angle = 0;
				withBanner = hasBanner;
			} else {
				continue; // small extra pieces only show their name when there's room
			}

			pose.pushMatrix();
			pose.translate((float) sx, (float) sy);
			pose.rotate(angle);
			pose.scale((float) scale);

			if (withBanner) {
				graphics.item(nation.banner, -8, -15);
				graphics.text(this.font, nation.name, -textW / 2, 4, 0xFFFFFFFF, true);
			} else {
				graphics.text(this.font, nation.name, -textW / 2, -4, 0xFFFFFFFF, true);
			}

			pose.popMatrix();
		}
	}

	private void drawChunkGrid(GuiGraphicsExtractor graphics, int mouseY, int hoverCX, int hoverCZ) {
		double chunkPx = 16 * this.zoom;

		if (chunkPx >= 4) {
			int firstCX = Math.floorDiv(Mth.floor(this.toWorldX(0)), 16);
			int lastCX = Math.floorDiv(Mth.floor(this.toWorldX(this.width)), 16) + 1;
			int firstCZ = Math.floorDiv(Mth.floor(this.toWorldZ(0)), 16);
			int lastCZ = Math.floorDiv(Mth.floor(this.toWorldZ(this.height)), 16) + 1;

			for (int cx = firstCX; cx <= lastCX; cx++) {
				int x = clampPx(this.toScreenX(cx * 16.0));
				graphics.fill(x, TOP_BAR, x + 1, this.height, 0x40FFFFFF);
			}

			for (int cz = firstCZ; cz <= lastCZ; cz++) {
				int y = clampPx(this.toScreenY(cz * 16.0));
				graphics.fill(0, y, this.width, y + 1, 0x40FFFFFF);
			}
		}

		if (mouseY > TOP_BAR) {
			int x1 = clampPx(this.toScreenX(hoverCX * 16.0));
			int y1 = clampPx(this.toScreenY(hoverCZ * 16.0));
			int x2 = Math.max(x1 + 2, clampPx(this.toScreenX(hoverCX * 16.0 + 16)));
			int y2 = Math.max(y1 + 2, clampPx(this.toScreenY(hoverCZ * 16.0 + 16)));
			int c = 0xFFFFFFFF;
			graphics.fill(x1, y1, x2, y1 + 1, c);
			graphics.fill(x1, y2 - 1, x2, y2, c);
			graphics.fill(x1, y1, x1 + 1, y2, c);
			graphics.fill(x2 - 1, y1, x2, y2, c);
		}
	}

	private void drawWorldBorder(GuiGraphicsExtractor graphics, ClientLevel level, LocalPlayer player) {
		WorldBorder border = level.getWorldBorder();
		int ax = Mth.floor(player.getX());
		int az = Mth.floor(player.getZ());

		if (!border.isWithinBounds(new BlockPos(ax, 0, az))) {
			ax = 0;
			az = 0;

			if (!border.isWithinBounds(new BlockPos(0, 0, 0))) {
				return;
			}
		}

		int minX = MapData.findEdge(border, ax, az, -1, 0);
		int maxX = MapData.findEdge(border, ax, az, 1, 0) + 1;
		int minZ = MapData.findEdge(border, ax, az, 0, -1);
		int maxZ = MapData.findEdge(border, ax, az, 0, 1) + 1;

		double left = this.toScreenX(minX);
		double right = this.toScreenX(maxX);
		double top = this.toScreenY(minZ);
		double bottom = this.toScreenY(maxZ);

		int l = clampPx(left);
		int r = clampPx(right);
		int t = clampPx(top);
		int b = clampPx(bottom);
		int shade = 0x50FF0000;
		int red = 0xFFFF3030;

		if (l > 0) {
			graphics.fill(0, 0, Math.min(l, this.width), this.height, shade);
		}
		if (r < this.width) {
			graphics.fill(Math.max(r, 0), 0, this.width, this.height, shade);
		}
		int innerL = Math.max(l, 0);
		int innerR = Math.min(r, this.width);
		if (innerR > innerL) {
			if (t > 0) {
				graphics.fill(innerL, 0, innerR, Math.min(t, this.height), shade);
			}
			if (b < this.height) {
				graphics.fill(innerL, Math.max(b, 0), innerR, this.height, shade);
			}
		}

		int lineTop = Math.max(t, 0);
		int lineBottom = Math.min(b, this.height);
		int lineLeft = Math.max(l, 0);
		int lineRight = Math.min(r, this.width);

		if (left >= -2 && left <= this.width + 2) {
			graphics.fill(l - 1, lineTop, l + 1, lineBottom, red);
		}
		if (right >= -2 && right <= this.width + 2) {
			graphics.fill(r - 1, lineTop, r + 1, lineBottom, red);
		}
		if (top >= -2 && top <= this.height + 2) {
			graphics.fill(lineLeft, t - 1, lineRight, t + 1, red);
		}
		if (bottom >= -2 && bottom <= this.height + 2) {
			graphics.fill(lineLeft, b - 1, lineRight, b + 1, red);
		}
	}


	// ---------------------------------------------------------------- markers

	private static boolean passesFilter(MarkerData m) {
		if (markerFilter == FILTER_OFF) {
			return false; // all markers hidden
		}

		if (m.type.alwaysPublic) {
			return true; // important places are always shown
		}

		return switch (markerFilter) {
			case 0 -> true;
			case 1 -> m.visibility == MarkerType.PRIVATE;
			case 2 -> m.visibility == MarkerType.NATION;
			case 3 -> m.visibility == MarkerType.PUBLIC;
			default -> false;
		};
	}

	/** Markers shrink a bit when you zoom out, so they never get too big for the map. */
	private int markerSize(MarkerType type) {
		double factor = this.zoom >= 2 ? 1.0 : this.zoom >= 1 ? 0.9 : this.zoom >= 0.5 ? 0.78 : this.zoom >= 0.25 ? 0.66 : 0.58;
		return Math.max(7, (int) Math.round(MarkerIcons.size(type) * factor));
	}

	/** The markers of this dimension that pass the filter, big ones last (so they are drawn on top). */
	private List<MarkerData> visibleMarkers(String dim) {
		List<MarkerData> list = new ArrayList<>();

		for (MarkerData m : ClientMarkers.all()) {
			if (m.dimension.equals(dim) && passesFilter(m)) {
				list.add(m);
			}
		}

		list.sort((a, b) -> Integer.compare(MarkerIcons.size(a.type), MarkerIcons.size(b.type)));
		return list;
	}

	private MarkerData markerAt(String dim, double mouseX, double mouseY) {
		List<MarkerData> list = this.visibleMarkers(dim);

		for (int i = list.size() - 1; i >= 0; i--) {
			MarkerData m = list.get(i);
			double half = this.markerSize(m.type) / 2.0 + 1;

			if (Math.abs(mouseX - this.toScreenX(m.x + 0.5)) <= half && Math.abs(mouseY - this.toScreenY(m.z + 0.5)) <= half) {
				return m;
			}
		}

		return null;
	}

	/** Marker icons (with the land owner's banner) and names. Returns the one under the mouse. */
	private MarkerData drawMarkers(GuiGraphicsExtractor graphics, String dim, int mouseX, int mouseY, boolean overMap) {
		List<MarkerData> list = this.visibleMarkers(dim);

		if (list.isEmpty()) {
			return null;
		}

		Matrix3x2fStack pose = graphics.pose();

		for (MarkerData m : list) {
			float sx = (float) this.toScreenX(m.x + 0.5);
			float sy = (float) this.toScreenY(m.z + 0.5);

			if (sx < -60 || sy < -30 || sx > this.width + 60 || sy > this.height + 30) {
				continue;
			}

			// settlements and other important places keep their names when zoomed out
			int size = this.markerSize(m.type);
			MarkerIcons.draw(graphics, m, sx, sy, size);

			boolean showName = (m.type.settlement || m.province != null) ? this.zoom >= 0.18 : (m.type.alwaysPublic ? this.zoom >= 0.5 : this.zoom >= 1.5);

			if (showName) {
				float scale = switch (m.type) {
					case CAPITAL -> 0.9f;
					case CITY, CASTLE -> 0.78f;
					case FORT, VILLAGE -> 0.7f;
					default -> 0.62f;
				};
				String name = m.title();
				int color = m.type == MarkerType.CAPITAL ? 0xFFFFD54F : 0xFFFFFFFF;

				pose.pushMatrix();
				pose.translate(sx, sy + size / 2f + 1);
				pose.scale(scale);
				graphics.text(this.font, name, -this.font.width(name) / 2, 0, color, true);
				pose.popMatrix();
			}
		}

		return overMap ? this.markerAt(dim, mouseX, mouseY) : null;
	}

	/** Can I place this type at all? (only the Capital has a special rule here) */
	private static String panelProblem(MarkerType type) {
		if (type.settlement || type == MarkerType.PORT || type == MarkerType.MARKET || type == MarkerType.TEMPLE) {
			return "In Map Nations WARS the villages and strongholds are already on the map.";
		}

		if (type.leaderOnly) {
			NationData mine = ClientNations.myNation();
			LocalPlayer me = net.minecraft.client.Minecraft.getInstance().player;

			if (mine == null || me == null || !mine.leader.equals(me.getUUID())) {
				return "Only a nation's leader can place the " + type.displayName + ".";
			}
		}

		return null;
	}

	private static final int PANEL_COLUMNS = 4;
	private static final int PANEL_CELL_MAX = 28;

	/** Size of one icon square in the marker panel: smaller on short screens so they all fit. */
	private int panelCell() {
		int rows = (MarkerType.values().length + PANEL_COLUMNS - 1) / PANEL_COLUMNS;
		return Math.max(18, Math.min(PANEL_CELL_MAX, (this.height - this.panelGridTop() - 6) / rows));
	}

	private int panelGridTop() {
		return TOP_BAR + 32;
	}

	private int panelGridLeft() {
		return this.panelLeft() + (PANEL_W - PANEL_COLUMNS * this.panelCell()) / 2;
	}

	/** Which marker type is at this spot in the panel grid, or null. */
	private MarkerType panelTypeAt(double mouseX, double mouseY) {
		if (!this.overPanel(mouseX, mouseY) || mouseY < this.panelGridTop() || mouseX < this.panelGridLeft()) {
			return null;
		}

		int col = (int) ((mouseX - this.panelGridLeft()) / this.panelCell());
		int row = (int) ((mouseY - this.panelGridTop()) / this.panelCell());
		int index = row * PANEL_COLUMNS + col;
		MarkerType[] types = MarkerType.values();
		return col < PANEL_COLUMNS && index >= 0 && index < types.length ? types[index] : null;
	}

	/** The marker side panel: a grid of icons. Returns the type under the mouse. */
	private MarkerType drawPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = this.panelLeft();
		graphics.fill(left, TOP_BAR, this.width, this.height, 0xF0161A1F);
		graphics.fill(left, TOP_BAR, left + 1, this.height, C_EDGE);
		graphics.text(this.font, "Markers", left + 8, TOP_BAR + 6, 0xFFFFD54F);

		MarkerType hovered = this.panelTypeAt(mouseX, mouseY);
		MarkerType shown = hovered != null ? hovered : this.placingType;
		graphics.text(this.font, shown != null ? shown.displayName : "Pick one, click the map", left + 8, TOP_BAR + 18,
				shown != null ? 0xFF000000 | shown.color : 0xFF888888);

		MarkerType[] types = MarkerType.values();
		int gx = this.panelGridLeft();
		int gy = this.panelGridTop();

		for (int i = 0; i < types.length; i++) {
			MarkerType t = types[i];
			int x = gx + (i % PANEL_COLUMNS) * this.panelCell();
			int y = gy + (i / PANEL_COLUMNS) * this.panelCell();

			if (t == this.placingType) {
				graphics.fill(x, y, x + this.panelCell() - 2, y + this.panelCell() - 2, 0xFFFFD54F);
				graphics.fill(x + 1, y + 1, x + this.panelCell() - 3, y + this.panelCell() - 3, 0xFF3A3420);
			} else {
				graphics.fill(x, y, x + this.panelCell() - 2, y + this.panelCell() - 2, t == hovered ? 0xFF4A5058 : 0xFF2A2F36);
			}

			MarkerIcons.sprite(graphics, t.ordinal(), x + (this.panelCell() - 2) / 2f, y + (this.panelCell() - 2) / 2f, 16);

			if (panelProblem(t) != null) {
				graphics.fill(x, y, x + this.panelCell() - 2, y + this.panelCell() - 2, 0xA0101010);
			}
		}

		// small key for the three kinds of visibility
		int ky = gy + ((types.length + PANEL_COLUMNS - 1) / PANEL_COLUMNS) * this.panelCell() + 8;

		if (ky + 34 < this.height) {
			graphics.text(this.font, "No outline: everyone", left + 8, ky, 0xFF888888);
			graphics.text(this.font, "Nation colour: nation", left + 8, ky + 11, 0xFF888888);
			graphics.text(this.font, "Your head: only you", left + 8, ky + 22, 0xFF888888);
		}

		return hovered;
	}

	private void drawPlacingGhost(GuiGraphicsExtractor graphics, String dim, int mouseX, int mouseY) {
		int bx = Mth.floor(this.toWorldX(mouseX));
		int bz = Mth.floor(this.toWorldZ(mouseY));
		NationData land = ClientNations.nationAt(dim, Math.floorDiv(bx, 16), Math.floorDiv(bz, 16));
		int size = this.markerSize(this.placingType);

		graphics.nextStratum();
		MarkerIcons.draw(graphics, this.placingType, land, MarkerType.PUBLIC, null, null, mouseX, mouseY, size);
		String hint = "Click to place " + this.placingType.displayName + "  -  right-click to cancel";
		int w = this.font.width(hint);
		int x = Math.min(mouseX + 12, this.width - w - 6);
		graphics.fill(x - 3, mouseY + 12, x + w + 3, mouseY + 24, 0xC0000000);
		graphics.text(this.font, hint, x, mouseY + 14, 0xFFFFFFFF);
	}

	// ---------------------------------------------------------------- players

	/** Other players as their face. Returns the one under the mouse, if any. */
	private Dot drawPlayers(GuiGraphicsExtractor graphics, LocalPlayer me, String dim, int mouseX, int mouseY, boolean overMap) {
		List<Dot> dots = new ArrayList<>();
		List<PlayersPayload.Entry> fromServer = ClientNations.players();

		if (!fromServer.isEmpty()) {
			for (PlayersPayload.Entry e : fromServer) {
				if (e.dimension().equals(dim) && !e.id().equals(me.getUUID())) {
					dots.add(new Dot(e.id(), e.name(), e.x(), e.z()));
				}
			}
		} else if (this.minecraft.level != null) {
			// server without the mod: we can still show players that are close enough to be loaded
			for (AbstractClientPlayer p : this.minecraft.level.players()) {
				if (!p.getUUID().equals(me.getUUID())) {
					dots.add(new Dot(p.getUUID(), p.getName().getString(), p.getX(), p.getZ()));
				}
			}
		}

		Dot hovered = null;
		int half = HEAD_SIZE / 2;

		for (Dot d : dots) {
			int x = clampPx(this.toScreenX(d.x()));
			int y = clampPx(this.toScreenY(d.z()));

			if (x < -20 || y < -20 || x > this.width + 20 || y > this.height + 20) {
				continue;
			}

			graphics.fill(x - half - 2, y - half - 2, x + half + 2, y + half + 2, 0xFF000000);
			Faces.draw(graphics, d.id(), x - half, y - half, HEAD_SIZE);

			if (overMap && Math.abs(mouseX - x) <= half + 2 && Math.abs(mouseY - y) <= half + 2) {
				hovered = d;
			}
		}

		return hovered;
	}

	private void drawPlayerArrow(GuiGraphicsExtractor graphics, LocalPlayer player) {
		float px = (float) this.toScreenX(player.getX());
		float py = (float) this.toScreenY(player.getZ());

		if (px < -20 || py < -20 || px > this.width + 20 || py > this.height + 20) {
			return;
		}

		// Minecraft yaw: 0 = south, 90 = west, 180 = north, 270 = east. The arrow texture points north.
		float angle = (float) Math.toRadians(player.getYRot() + 180.0F);
		int size = ArrowTexture.SIZE;

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(px, py);
		pose.rotate(angle);
		pose.scale(20f / size);
		graphics.blit(RenderPipelines.GUI_TEXTURED, ArrowTexture.get(this.minecraft), -size / 2, -size / 2,
				0.0F, 0.0F, size, size, size, size);
		pose.popMatrix();
	}

	// ---------------------------------------------------------------- overlays

	/**
	 * "Left click: claim / Right click: remove" box in the top-right corner,
	 * shown while claiming land or editing a settlement's borders. Returns its bottom edge (0 if not shown).
	 */
	private int drawControlsHint(GuiGraphicsExtractor graphics) {
		String left;
		String right;

		if (this.editingArea != null) {
			left = "add chunks";
			right = "remove chunks";
		} else if (this.claimMode) {
			NationData mine = ClientNations.myNation();
			UUID me = this.minecraft.player != null ? this.minecraft.player.getUUID() : null;

			if (mine != null && me != null && mine.leader.equals(me)) {
				left = "claim land";
				right = "remove claim";
			} else if (mine != null && me != null && mine.isOfficer(me)) {
				left = "propose land";
				right = "take proposal back";
			} else {
				left = null;
				right = null;
			}
		} else {
			return 0;
		}

		List<Component> lines = new ArrayList<>();

		if (left == null) {
			lines.add(Component.literal("Only a nation's leader (or officer) can claim").withColor(0xFFAAAAAA));
		} else {
			lines.add(Component.literal("Left click / drag: ").withColor(0x7CFF7C).append(Component.literal(left).withColor(0xFFFFFF)));
			lines.add(Component.literal("Right click / drag: ").withColor(0xFF7C7C).append(Component.literal(right).withColor(0xFFFFFF)));
		}

		lines.add(Component.literal("Middle drag: move map").withColor(0xAAAAAA));

		int w = 0;

		for (Component c : lines) {
			w = Math.max(w, this.font.width(c.getString()));
		}

		int x2 = this.panelLeft() - (this.panelVisible() ? 22 : 6);
		int x1 = x2 - w - 10;
		int y1 = TOP_BAR + 4;
		int y2 = y1 + 6 + lines.size() * 11;

		graphics.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xFFFFD54F);
		graphics.fill(x1, y1, x2, y2, 0xE01B1F24);

		for (int i = 0; i < lines.size(); i++) {
			graphics.text(this.font, lines.get(i), x1 + 5, y1 + 4 + i * 11, 0xFFFFFFFF, true);
		}

		return y2;
	}


	private void drawLegend(GuiGraphicsExtractor graphics, String dim) {
		Map<Long, UUID> claims = ClientNations.claims(dim);
		this.legendRows.clear();

		if (claims.isEmpty()) {
			return;
		}

		Map<UUID, Integer> counts = new HashMap<>();

		for (UUID id : claims.values()) {
			counts.merge(id, 1, Integer::sum);
		}

		List<Map.Entry<UUID, Integer>> list = new ArrayList<>(counts.entrySet());
		list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

		int shown = Math.min(list.size(), 10);
		int boxW = 140;
		int x = this.panelLeft() - boxW - (this.panelVisible() ? 22 : 6);
		int y = Math.max(TOP_BAR + 6, this.hintBottom + 10);
		this.legendRows.clear();
		this.legendX = x;
		this.legendY = y + 12;
		this.legendW = boxW;

		graphics.fill(x - 5, y - 5, x + boxW + 1, y + 13 + shown * 11, 0x50FFFFFF);
		graphics.fill(x - 4, y - 4, x + boxW, y + 12 + shown * 11, 0xD0101317);
		graphics.text(this.font, "Nations (click to zoom)", x, y, 0xFFFFD060);

		for (int i = 0; i < shown; i++) {
			NationData n = ClientNations.get(list.get(i).getKey());
			this.legendRows.add(n != null ? n.id : null);

			if (n == null) {
				continue;
			}

			int rowY = y + 12 + i * 11;

			if (this.mouseXNow >= x - 4 && this.mouseXNow < x + boxW && this.mouseYNow >= rowY - 1 && this.mouseYNow < rowY + 10) {
				graphics.fill(x - 4, rowY - 1, x + boxW, rowY + 10, 0x30FFFFFF);
			}

			graphics.fill(x, rowY, x + 8, rowY + 8, 0xFF000000 | n.color);
			graphics.text(this.font, n.name + " (" + list.get(i).getValue() + ")", x + 12, rowY, 0xFFFFFFFF);
		}
	}

	/** Help lines in the bottom-left. Returns the y where the box starts. */
	private int drawInfo(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int cx, int cz, boolean overMap) {
		List<String> lines = new ArrayList<>();

		if (overMap) {
			int bx = Mth.floor(this.toWorldX(mouseX));
			int bz = Mth.floor(this.toWorldZ(mouseY));
			lines.add("X " + bx + "   Z " + bz + "   (chunk " + cx + ", " + cz + ")   zoom " + formatZoom(this.zoom));
		}

		if (this.claimMode) {
			NationData mine = ClientNations.myNation();

			if (mine == null) {
				lines.add("Join or create a nation in the Nations tab to claim land.");
			} else {
				LocalPlayer self = this.minecraft.player;
				UUID me = self != null ? self.getUUID() : new UUID(0, 0);
				lines.add("Land: " + ClientNations.chunkCount(mine.id) + " chunks (no limit)");

				if (mine.leader.equals(me)) {
					int waiting = ClientNations.proposalCount(mine.id);
					lines.add("Click or drag (max " + ClaimAreaPayload.MAX_CHUNKS + "): claim   Right-click / right-drag: unclaim   Middle-drag: move map");

					if (waiting > 0) {
						lines.add(waiting + " chunks proposed by officers: click to annex, right-click to deny (or use the Nations tab).");
					} else {
						lines.add("Drag claiming skips other nations' land - click a single chunk to contest it (starts a conflict).");
					}
				} else if (mine.isOfficer(me)) {
					lines.add("You are an Officer: click or drag to PROPOSE land. The " + mine.ideology.leaderTitle + " decides.");
					lines.add("Right-click / right-drag: take your nation's proposals back   Middle-drag: move map");
				} else {
					lines.add("Only the " + mine.ideology.leaderTitle + " claims land. Officers can propose land.");
				}
			}
		}

		if (this.editingArea != null) {
			lines.add((this.pendingPlace ? "Choose the borders of your new " + this.editingArea.type.displayName : "Borders of " + this.editingArea.title()) + ": " + this.workingArea.size() + " / " + this.editingArea.type.maxArea()
					+ " chunks   Left-drag: add   Right-drag: remove   Middle-drag: move map   Enter: apply   Esc: cancel");
		}

		lines.add(this.claimMode ? "WASD: move   Scroll: zoom   C: center   Right-click a marker: marker menu   M: close"
				: "Drag / WASD: move   Scroll: zoom   Right-click a marker: marker menu   C: center   P: map colours   H: hide markers   M: close");

		// long lines are split so they never run under the compass or off the screen
		int room = Math.max(150, this.panelLeft() - (this.panelVisible() ? 22 : 8) - 96 - 10); // leaves room for the compass and minimap button
		List<String> wrapped = new ArrayList<>();

		for (String line : lines) {
			wrapped.addAll(this.wrapText(line, room));
		}

		lines = wrapped;
		int lineH = 11;
		int y = this.height - 6 - lines.size() * lineH;
		int maxW = 0;

		for (String s : lines) {
			maxW = Math.max(maxW, this.font.width(s));
		}

		graphics.fill(0, y - 5, maxW + 13, this.height, 0x50FFFFFF);
		graphics.fill(0, y - 4, maxW + 12, this.height, 0xD0101317);

		for (int i = 0; i < lines.size(); i++) {
			graphics.text(this.font, lines.get(i), 6, y + i * lineH, 0xFFFFFFFF);
		}

		return y - 4;
	}

	/** "You are in: <nation>" box in the bottom-left corner. */
	private void drawLocation(GuiGraphicsExtractor graphics, LocalPlayer player, String dim, int bottom) {
		int cx = Mth.floor(player.getX()) >> 4;
		int cz = Mth.floor(player.getZ()) >> 4;
		List<UUID> here = ClientNations.claimants(dim, cx, cz);

		MutableComponent text;
		NationData single = here.size() == 1 ? ClientNations.get(here.get(0)) : null;

		if (here.isEmpty()) {
			text = Component.literal("Wilderness").withColor(0x9AA0A6);
		} else if (here.size() == 1) {
			int color = single != null ? single.color : 0xFFFFFF;
			text = Component.literal(single != null ? single.name : "?").withStyle(style -> style.withColor(color).withBold(true));
		} else {
			text = Component.literal("Conflict zone").withColor(0xFF5555);

			for (int i = 0; i < here.size(); i++) {
				NationData n = ClientNations.get(here.get(i));

				if (n != null) {
					text = text.append(Component.literal(i == 0 ? ": " : " vs ").withColor(0xAAAAAA))
							.append(Component.literal(n.name).withColor(n.color));
				}
			}
		}

		String label = "You are in";
		int iconW = here.isEmpty() ? 0 : 20;
		int w = Math.max(this.font.width(label), iconW + this.font.width(text.getString())) + 12;
		int top = bottom - 30;

		graphics.fill(0, top - 1, w + 1, bottom - 2, 0x50FFFFFF);
		graphics.fill(0, top, w, bottom - 2, 0xD0101317);
		graphics.text(this.font, label, 6, top + 3, 0xFF888888);

		if (here.size() > 1) {
			MarkerIcons.sprite(graphics, MarkerIcons.CONFLICT, 14, top + 20, 14);
		} else if (single != null) {
			if (!single.banner.isEmpty()) {
				graphics.item(single.banner, 6, top + 12);
			} else {
				graphics.fill(8, top + 14, 20, top + 26, 0xFF000000 | single.color);
			}
		}

		graphics.text(this.font, text, 6 + iconW, top + 16, 0xFFFFFFFF, true);
	}

	// ---------------------------------------------------------------- tooltips

	private void playerTooltip(GuiGraphicsExtractor graphics, Dot d, int mouseX, int mouseY) {
		RichTooltip tip = new RichTooltip().player("", d.id(), d.name(), "");
		NationData nation = ClientNations.nationOfPlayer(d.id());

		if (nation != null) {
			if (d.id().equals(nation.leader)) {
				tip.text(Component.literal(nation.ideology.leaderTitle + " of ").withColor(0xFFD54F)
						.append(Component.literal(nation.name).withColor(nation.color)));
			} else {
				tip.text(Component.literal("Citizen of ").withColor(0xBBBBBB)
						.append(Component.literal(nation.name).withColor(nation.color)));
			}
		} else {
			tip.text(Component.literal("No nation").withColor(0x999999));
		}

		tip.text(Component.literal("X " + Mth.floor(d.x()) + "  Z " + Mth.floor(d.z())).withColor(0x888888));
		tip.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
	}

	private static MutableComponent nationList(List<UUID> ids) {
		MutableComponent out = Component.literal("");
		boolean first = true;

		for (UUID id : ids) {
			NationData n = ClientNations.get(id);

			if (n == null) {
				continue;
			}

			if (!first) {
				out = out.append(Component.literal(", ").withColor(0xAAAAAA));
			}

			out = out.append(Component.literal(n.name).withColor(n.color));
			first = false;
		}

		return out;
	}

	private void nationTooltip(GuiGraphicsExtractor graphics, NationData n, int mouseX, int mouseY) {
		RichTooltip tip = new RichTooltip();
		tip.text(Component.literal(n.name).withStyle(style -> style.withColor(n.color).withBold(true)));
		tip.text(Component.literal(n.ideology.displayName).withColor(n.ideology.category.color));
		tip.player(n.ideology.leaderTitle + ": ", n.leader, n.leaderName(), "");
		tip.text(Component.literal("Members: " + n.members.size() + "   Territory: " + ClientNations.chunkCount(n.id) + " chunks").withColor(0xDDDDDD));
		tip.text(Component.literal("Villagers: " + n.population).withColor(0x9CE0A0));

		if (!n.allies.isEmpty()) {
			tip.text(Component.literal("Allies: ").withColor(0xAAAAAA).append(nationList(n.allies)));
		}

		if (this.claimMode) {
			NationData mine = ClientNations.myNation();

			if (mine != null && mine.id.equals(n.id)) {
				tip.text(Component.literal("Click to unclaim this chunk").withColor(0x9CFF9C));
			} else if (mine != null && mine.allies.contains(n.id)) {
				tip.text(Component.literal("Your ally's land").withColor(0x9CFF9C));
			} else if (mine != null) {
				tip.text(Component.literal("Click to contest this land (starts a conflict)").withColor(0xFF8080));
			}
		}

		tip.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
	}

	private void conflictTooltip(GuiGraphicsExtractor graphics, List<UUID> claimants, int cx, int cz, int mouseX, int mouseY) {
		RichTooltip tip = new RichTooltip();
		tip.text(Component.literal("⚠ Conflict zone").withStyle(style -> style.withColor(0xFF4040).withBold(true)));

		NationData holder = ClientNations.get(claimants.get(0));

		if (holder != null) {
			tip.text(Component.literal("Held by ").withColor(0xAAAAAA).append(Component.literal(holder.name).withColor(holder.color)));
		}

		tip.text(Component.literal("Contested by ").withColor(0xAAAAAA).append(nationList(claimants.subList(1, claimants.size()))));
		tip.text(Component.literal("Chunk " + cx + ", " + cz).withColor(0x777777));

		if (this.claimMode) {
			NationData mine = ClientNations.myNation();

			if (mine != null && claimants.contains(mine.id)) {
				tip.text(Component.literal("Click to withdraw your claim").withColor(0x9CFF9C));
			}
		}

		tip.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
	}

	private void markerTooltip(GuiGraphicsExtractor graphics, MarkerData m, int mouseX, int mouseY) {
		if (m.province != null) {
			this.provinceTooltip(graphics, m.province, mouseX, mouseY);
			return;
		}

		RichTooltip tip = new RichTooltip();
		NationData land = MarkerIcons.owner(m);
		int titleColor = m.type == MarkerType.CAPITAL ? 0xFFD54F : m.type.color;
		tip.text(Component.literal(m.title()).withStyle(style -> style.withColor(titleColor).withBold(true)));

		// whose settlement is it? (whoever owns the land now)
		if (m.type == MarkerType.CAPITAL && land != null && !land.id.equals(m.nation)) {
			NationData old = ClientNations.get(m.nation);
			tip.text(Component.literal("Former capital" + (old != null ? " of " + old.name : "")).withColor(0xAAAAAA));
			tip.text(Component.literal("Now held by ").withColor(0xAAAAAA).append(Component.literal(land.name).withColor(land.color)));
		} else if (m.type.settlement || m.type.showsBanner) {
			if (land != null) {
				tip.text(Component.literal(m.type.displayName + " of ").withColor(0xAAAAAA).append(Component.literal(land.name).withColor(land.color)));
			} else {
				tip.text(Component.literal(m.type.displayName + " - independent").withColor(0xAAAAAA));
			}
		} else if (!m.label.isBlank()) {
			tip.text(Component.literal(m.type.displayName).withColor(0xAAAAAA));
		}

		if (m.type.settlement) {
			tip.text(Component.literal("Population: " + m.population + " villagers   Area: " + m.area.size() + " chunks").withColor(0x9CE0A0));
		}

		tip.player(m.type.settlement ? "Managed by " : "Placed by ", m.owner, m.ownerName, "");

		List<UUID> claimants = ClientNations.claimants(m.dimension, Math.floorDiv(m.x, 16), Math.floorDiv(m.z, 16));

		if (claimants.size() > 1) {
			tip.text(Component.literal("⚠ In a conflict zone").withColor(0xFF5555));
		} else if (land != null) {
			tip.text(Component.literal("In ").withColor(0xAAAAAA)
					.append(Component.literal(land.name).withColor(land.color))
					.append(Component.literal(" territory").withColor(0xAAAAAA)));
		} else {
			tip.text(Component.literal("In the Wilderness").withColor(0x9AA0A6));
		}

		String visibility = switch (m.visibility) {
			case MarkerType.PUBLIC -> "Visible to everyone";
			case MarkerType.NATION -> "Visible to the nation";
			default -> "Only visible to you";
		};
		tip.text(Component.literal(visibility + "   X " + m.x + "  Z " + m.z).withColor(0x888888));

		if (!m.votes.isEmpty()) {
			tip.text(Component.literal("Destroy votes: " + m.votes.size() + "/" + MarkerType.DESTROY_VOTES).withColor(0xFF8080));
		}

		LocalPlayer self = this.minecraft.player;
		boolean own = self != null && m.owner.equals(self.getUUID());
		tip.text(Component.literal(own ? "Right-click to rename, move or remove" : "Right-click to vote to destroy it").withColor(0x777777));

		tip.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
	}

	private void panelTooltip(GuiGraphicsExtractor graphics, MarkerType t, int mouseX, int mouseY) {
		RichTooltip tip = new RichTooltip();
		tip.text(Component.literal(t.displayName).withStyle(style -> style.withColor(t.color).withBold(true)));

		if (t.leaderOnly) {
			tip.text(Component.literal("Leader only - inside your own land, one per nation").withColor(0xAAAAAA));
		} else if (t.settlement) {
			tip.text(Component.literal("Settlement - belongs to whoever owns the land").withColor(0xAAAAAA));
			tip.text(Component.literal("Needs " + MarkerType.SETTLEMENT_SPACING + " blocks of space").withColor(0xAAAAAA));
		} else if (t.playerLimit > 0) {
			tip.text(Component.literal("One per player - placing it again moves it").withColor(0xAAAAAA));
		}

		if (t.showsBanner) {
			tip.text(Component.literal("Shows the banner of the land's nation").withColor(0xAAAAAA));
		}

		if (t.alwaysPublic) {
			tip.text(Component.literal("Always visible to everyone").withColor(0x9CFF9C));
		}

		String problem = panelProblem(t);

		if (problem != null) {
			tip.text(Component.literal(problem).withColor(0xFF7070));
		}

		tip.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
	}

	private static String formatZoom(double z) {
		return z >= 1 ? ((int) Math.round(z)) + "x" : "1/" + ((int) Math.round(1 / z)) + "x";
	}

	// ---------------------------------------------------------------- input

	private void zoomAt(double screenX, double screenY, double factor) {
		if (this.followPlayer) {
			screenX = this.width / 2.0;
			screenY = this.height / 2.0;
		}

		double wx = this.toWorldX(screenX);
		double wz = this.toWorldZ(screenY);
		this.zoom = Mth.clamp(this.zoom * factor, MIN_ZOOM, MAX_ZOOM);
		this.centerX = wx - (screenX - this.width / 2.0) / this.zoom;
		this.centerZ = wz - (screenY - this.height / 2.0) / this.zoom;
		savedZoom = this.zoom;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (this.overPanel(mouseX, mouseY)) {
			return true;
		}

		if (verticalAmount > 0) {
			this.zoomAt(mouseX, mouseY, 1.25);
		} else if (verticalAmount < 0) {
			this.zoomAt(mouseX, mouseY, 0.8);
		}

		return true;
	}

	private int chunkXAt(double screenX) {
		return Math.floorDiv(Mth.floor(this.toWorldX(screenX)), 16);
	}

	private int chunkZAt(double screenY) {
		return Math.floorDiv(Mth.floor(this.toWorldZ(screenY)), 16);
	}

	/** Called from the marker menu: the marker follows the mouse until you click where it should go. */
	public void startMoving(MarkerData marker) {
		this.movingMarker = marker;
		this.placingType = null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
		if (super.mouseClicked(click, doubleClick)) {
			return true;
		}

		if (this.viewOpen) {
			this.viewOpen = false; // a click anywhere else closes the View menu
			this.rebuildWidgets();
			return true;
		}

		if (click.y() <= TOP_BAR) {
			return false;
		}

		if (this.editingArea != null && click.x() >= this.editBarLeft && click.x() < this.editBarRight
				&& click.y() >= this.editBarTop && click.y() < this.editBarBottom) {
			return true; // clicked the Apply / Cancel box itself, not the map under it
		}

		UUID legendNation = this.legendNationAt(click.x(), click.y());

		if (legendNation != null && this.showPolitical()) {
			this.zoomToNation(legendNation);
			return true;
		}

		UUID legendAlliance = this.showAlliances() ? this.legendAllianceAt(click.x(), click.y()) : null;

		if (legendAlliance != null) {
			this.zoomToAlliance(legendAlliance);
			return true;
		}

		if (this.overPanel(click.x(), click.y())) {
			MarkerType t = this.panelTypeAt(click.x(), click.y());

			if (t != null) {
				String problem = panelProblem(t);

				if (problem != null) {
					ClientNations.setStatus(problem, false);
				} else {
					this.placingType = this.placingType == t ? null : t;
					this.movingMarker = null;
				}
			}

			return true;
		}

		this.mouseDown = true;
		this.dragged = false;
		this.pressX = click.x();
		this.pressY = click.y();
		this.pressButton = click.input();

		// Claims tab: left / right button starts a claim / unclaim rectangle (middle button moves the map)
		this.selecting = (this.claimMode || this.editingArea != null) && this.placingType == null && this.movingMarker == null
				&& (click.input() == SDLMouse.SDL_BUTTON_LEFT || click.input() == SDLMouse.SDL_BUTTON_RIGHT);

		if (this.selecting) {
			this.selFromX = this.chunkXAt(click.x());
			this.selFromZ = this.chunkZAt(click.y());
			this.selToX = this.selFromX;
			this.selToZ = this.selFromZ;
		}

		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent click, double dragX, double dragY) {
		if (this.mouseDown) {
			if (Math.abs(click.x() - this.pressX) + Math.abs(click.y() - this.pressY) > 3) {
				this.dragged = true;
			}

			if (this.selecting) {
				this.selToX = this.chunkXAt(click.x());
				this.selToZ = this.chunkZAt(click.y());
			} else if (this.dragged) {
				this.centerX -= dragX / this.zoom;
				this.centerZ -= dragY / this.zoom;
				this.followPlayer = false;
			}

			return true;
		}

		return super.mouseDragged(click, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		if (!this.mouseDown) {
			return super.mouseReleased(click);
		}

		this.mouseDown = false;
		boolean wasSelecting = this.selecting;
		this.selecting = false;

		if (wasSelecting && this.editingArea != null) {
			// editing borders: add (left) or remove (right) the chunks in the rectangle
			this.editArea(this.selFromX, this.selFromZ, this.selToX, this.selToZ, this.pressButton == SDLMouse.SDL_BUTTON_LEFT);
			return true;
		}

		if (wasSelecting && this.dragged) {
			// drag claiming: the whole rectangle at once
			this.requestClaimArea(this.selFromX, this.selFromZ, this.selToX, this.selToZ, this.pressButton == SDLMouse.SDL_BUTTON_LEFT);
			return true;
		}

		if (this.dragged) {
			return true;
		}

		String dim = this.dimensionId();

		if (click.input() == SDLMouse.SDL_BUTTON_RIGHT) {
			if (this.placingType != null || this.movingMarker != null) {
				this.placingType = null; // cancel placing / moving
				this.movingMarker = null;
				return true;
			}

			MarkerData existing = this.markerAt(dim, click.x(), click.y());

			if (existing != null && existing.province != null) {
				if (existing.province.nation != null && ClientNations.get(existing.province.nation) != null) {
					this.minecraft.gui.setScreen(new NationsScreen(existing.province.nation));
				}
			} else if (existing != null) {
				this.minecraft.gui.setScreen(new MarkerManageScreen(this, existing));
			} else if (this.claimMode) {
				// right-click a single chunk in the Claims tab: unclaim it
				this.requestClaimArea(this.chunkXAt(click.x()), this.chunkZAt(click.y()),
						this.chunkXAt(click.x()), this.chunkZAt(click.y()), false);
			}

			return true;
		}

		if (click.input() == SDLMouse.SDL_BUTTON_LEFT) {
			int bx = Mth.floor(this.toWorldX(click.x()));
			int bz = Mth.floor(this.toWorldZ(click.y()));

			if (this.movingMarker != null) {
				MarkerData moving = this.movingMarker;
				this.movingMarker = null;

				if (!MapData.isKnown(bx, bz)) {
					ClientNations.setStatus("You can only put markers where the map shows land.", false);
				} else if (ClientPlayNetworking.canSend(MarkerActionPayload.TYPE)) {
					ClientPlayNetworking.send(MarkerActionPayload.move(moving.id.toString(), bx, bz));
				}
			} else if (this.placingType != null) {
				MarkerType type = this.placingType;
				this.placingType = null;
				this.minecraft.gui.setScreen(new MarkerPlaceScreen(this, dim, bx, bz, type));
			} else if (this.claimMode) {
				this.requestClaimToggle(this.chunkXAt(click.x()), this.chunkZAt(click.y()));
			} else if (this.editingArea == null && this.showPolitical() && this.markerAt(dim, click.x(), click.y()) == null) {
				// clicking a nation's land opens its page in the Nations tab
				NationData nation = ClientNations.nationAt(dim, this.chunkXAt(click.x()), this.chunkZAt(click.y()));

				if (nation != null) {
					this.minecraft.gui.setScreen(new NationsScreen(nation.id));
				}
			} else if (this.editingArea == null && this.showAlliances() && this.markerAt(dim, click.x(), click.y()) == null) {
				// clicking an alliance's land opens its page in the Alliances tab
				NationData nation = ClientNations.nationAt(dim, this.chunkXAt(click.x()), this.chunkZAt(click.y()));
				AllianceData alliance = nation != null ? ClientNations.allianceOf(nation.id) : null;

				if (alliance != null) {
					this.minecraft.gui.setScreen(new AlliancesScreen(alliance.id));
				} else if (nation != null) {
					this.minecraft.gui.setScreen(new NationsScreen(nation.id));
				}
			}
		}

		return true;
	}

	// ---------------------------------------------------------------- alliance map

	/** Land coloured by alliance. Nations without an alliance are shown faintly. */
	private void drawAllianceClaims(GuiGraphicsExtractor graphics, String dim, AllianceData hovered, NationData hoveredNation) {
		Map<Long, UUID> owners = ClientNations.claims(dim);
		double chunkPx = 16 * this.zoom;
		boolean borders = chunkPx >= 3;
		int line = chunkPx >= 24 ? 2 : 1;

		for (Map.Entry<Long, UUID> e : owners.entrySet()) {
			int cx = MapNationsMod.keyX(e.getKey());
			int cz = MapNationsMod.keyZ(e.getKey());
			double sx1 = this.toScreenX(cx * 16.0);
			double sy1 = this.toScreenY(cz * 16.0);

			if (sx1 > this.width || sy1 > this.height || sx1 + chunkPx < 0 || sy1 + chunkPx < 0) {
				continue;
			}

			int x1 = clampPx(sx1);
			int y1 = clampPx(sy1);
			int x2 = Math.max(x1 + 1, clampPx(sx1 + chunkPx));
			int y2 = Math.max(y1 + 1, clampPx(sy1 + chunkPx));
			AllianceData a = ClientNations.allianceOf(e.getValue());
			UUID group = a != null ? a.id : e.getValue();

			if (a == null) {
				graphics.fill(x1, y1, x2, y2, 0x40000000 | 0x9AA0A6);
			} else {
				boolean hot = a == hovered;
				graphics.fill(x1, y1, x2, y2, (hot ? 0xB8000000 : 0x9A000000) | a.color);
			}

			if (!borders) {
				continue;
			}

			int edgeColor = a == null ? 0x909AA0A6 : (a == hovered ? 0xFFFFFFFF : 0xFF000000 | a.color);
			int[][] sides = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

			for (int[] d : sides) {
				UUID other = owners.get(MapNationsMod.chunkKey(cx + d[0], cz + d[1]));
				AllianceData oa = other != null ? ClientNations.allianceOf(other) : null;
				UUID otherGroup = oa != null ? oa.id : other;
				boolean outer = otherGroup == null || !otherGroup.equals(group);
				boolean innerNationEdge = !outer && a != null && !other.equals(e.getValue()) && chunkPx >= 8;

				if (!outer && !innerNationEdge) {
					continue;
				}

				int color = outer ? edgeColor : 0x70000000; // thin dark line between nations of one alliance
				int w = outer ? line : 1;

				if (d[1] == -1) {
					graphics.fill(x1, y1, x2, y1 + w, color);
				} else if (d[1] == 1) {
					graphics.fill(x1, y2 - w, x2, y2, color);
				} else if (d[0] == -1) {
					graphics.fill(x1, y1, x1 + w, y2, color);
				} else {
					graphics.fill(x2 - w, y1, x2, y2, color);
				}
			}
		}
	}

	private record AllianceLabel(UUID alliance, double centerX, double centerZ, int chunks, double spanBlocks) {
	}

	private static List<AllianceLabel> allianceLabels = List.of();
	private static String allianceLabelKey = "";

	/** One label per connected piece of an alliance's land (worked out again only when the data changes). */
	private static List<AllianceLabel> allianceLabels(String dim) {
		String key = dim + "#" + ClientNations.version();

		if (key.equals(allianceLabelKey)) {
			return allianceLabels;
		}

		Map<Long, UUID> groupOf = new HashMap<>();

		for (Map.Entry<Long, UUID> e : ClientNations.claims(dim).entrySet()) {
			AllianceData a = ClientNations.allianceOf(e.getValue());

			if (a != null) {
				groupOf.put(e.getKey(), a.id);
			}
		}

		List<AllianceLabel> out = new ArrayList<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();

		for (Map.Entry<Long, UUID> start : groupOf.entrySet()) {
			if (!seen.add(start.getKey())) {
				continue;
			}

			java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
			queue.add(start.getKey());
			double sumX = 0;
			double sumZ = 0;
			int count = 0;
			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

			while (!queue.isEmpty()) {
				long k = queue.poll();
				int cx = MapNationsMod.keyX(k);
				int cz = MapNationsMod.keyZ(k);
				sumX += cx * 16 + 8;
				sumZ += cz * 16 + 8;
				count++;
				minX = Math.min(minX, cx);
				maxX = Math.max(maxX, cx);
				minZ = Math.min(minZ, cz);
				maxZ = Math.max(maxZ, cz);

				for (long n : new long[] {MapNationsMod.chunkKey(cx + 1, cz), MapNationsMod.chunkKey(cx - 1, cz),
						MapNationsMod.chunkKey(cx, cz + 1), MapNationsMod.chunkKey(cx, cz - 1)}) {
					if (start.getValue().equals(groupOf.get(n)) && seen.add(n)) {
						queue.add(n);
					}
				}
			}

			out.add(new AllianceLabel(start.getValue(), sumX / count, sumZ / count, count, Math.max(maxX - minX + 1, maxZ - minZ + 1) * 16.0));
		}

		allianceLabels = out;
		allianceLabelKey = key;
		return out;
	}

	private void drawAllianceLabels(GuiGraphicsExtractor graphics, String dim) {
		for (AllianceLabel label : allianceLabels(dim)) {
			AllianceData a = ClientNations.alliance(label.alliance());
			double spanPx = label.spanBlocks() * this.zoom;

			if (a == null || spanPx < 36) {
				continue;
			}

			String text = a.name.toUpperCase(java.util.Locale.ROOT);
			float scale = (float) Mth.clamp(spanPx * 0.75 / Math.max(1, this.font.width(text)), 0.7, 2.6);
			double sx = this.toScreenX(label.centerX());
			double sy = this.toScreenY(label.centerZ());

			if (sx < -200 || sy < -50 || sx > this.width + 200 || sy > this.height + 50) {
				continue;
			}

			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate((float) sx, (float) sy);
			pose.scale(scale);
			graphics.centeredText(this.font, Component.literal(text).withStyle(st -> st.withColor(0xFFFFFF).withBold(true)), 0, -4, 0xFFFFFFFF);
			pose.popMatrix();
		}
	}

	private final List<UUID> allianceLegendRows = new ArrayList<>();

	/** "Alliances" list in the top-right corner (click one to zoom to it). */
	private void drawAllianceLegend(GuiGraphicsExtractor graphics, String dim) {
		this.legendRows.clear();
		this.allianceLegendRows.clear();
		List<AllianceData> list = new ArrayList<>(ClientNations.alliances());

		if (list.isEmpty()) {
			return;
		}

		list.sort((a, b) -> Integer.compare(ClientNations.allianceChunks(b), ClientNations.allianceChunks(a)));
		int shown = Math.min(list.size(), 10);
		int boxW = 140;
		int x = this.panelLeft() - boxW - (this.panelVisible() ? 22 : 6);
		int y = Math.max(TOP_BAR + 6, this.hintBottom + 10);
		this.legendX = x;
		this.legendY = y + 12;
		this.legendW = boxW;
		graphics.fill(x - 5, y - 5, x + boxW + 1, y + 13 + shown * 11, 0x50FFFFFF);
		graphics.fill(x - 4, y - 4, x + boxW, y + 12 + shown * 11, 0xD0101317);
		graphics.text(this.font, "Alliances (click to zoom)", x, y, 0xFFFFD060);

		for (int i = 0; i < shown; i++) {
			AllianceData a = list.get(i);
			this.allianceLegendRows.add(a.id);
			int rowY = y + 12 + i * 11;

			if (this.mouseXNow >= x - 4 && this.mouseXNow < x + boxW && this.mouseYNow >= rowY - 1 && this.mouseYNow < rowY + 10) {
				graphics.fill(x - 4, rowY - 1, x + boxW, rowY + 10, 0x30FFFFFF);
			}

			graphics.fill(x, rowY, x + 8, rowY + 8, 0xFF000000 | a.color);
			String text = a.name + " (" + a.nations.size() + ")";

			while (this.font.width(text) > boxW - 14 && text.length() > 4) {
				text = text.substring(0, text.length() - 2);
			}

			graphics.text(this.font, text, x + 12, rowY, 0xFFFFFFFF);
		}
	}

	private UUID legendAllianceAt(double mouseX, double mouseY) {
		if (mouseX < this.legendX - 4 || mouseX >= this.legendX + this.legendW || mouseY < this.legendY - 1) {
			return null;
		}

		int index = (int) ((mouseY - this.legendY + 1) / 11);
		return index >= 0 && index < this.allianceLegendRows.size() ? this.allianceLegendRows.get(index) : null;
	}

	/** Centres the map on all the land of an alliance. */
	private void zoomToAlliance(UUID allianceId) {
		AllianceData a = ClientNations.alliance(allianceId);

		if (a == null) {
			return;
		}

		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

		for (Map.Entry<Long, UUID> e : ClientNations.claims(this.dimensionId()).entrySet()) {
			if (a.nations.contains(e.getValue())) {
				int cx = MapNationsMod.keyX(e.getKey());
				int cz = MapNationsMod.keyZ(e.getKey());
				minX = Math.min(minX, cx);
				maxX = Math.max(maxX, cx);
				minZ = Math.min(minZ, cz);
				maxZ = Math.max(maxZ, cz);
			}
		}

		if (minX == Integer.MAX_VALUE) {
			ClientNations.setStatus("The " + a.name + " has no land in this dimension.", false);
			return;
		}

		this.followPlayer = false;
		this.centerX = (minX + maxX + 1) * 8.0;
		this.centerZ = (minZ + maxZ + 1) * 8.0;
		double widthBlocks = (maxX - minX + 1) * 16.0;
		double heightBlocks = (maxZ - minZ + 1) * 16.0;
		this.zoom = Mth.clamp(Math.min(this.width * 0.7 / widthBlocks, (this.height - TOP_BAR) * 0.7 / heightBlocks), MIN_ZOOM, MAX_ZOOM);
		savedZoom = this.zoom;
	}

	/** A village / outpost / mansion / bastion of the world. */
	private void provinceTooltip(GuiGraphicsExtractor graphics, com.mapnationswars.nation.ProvinceData p, int mouseX, int mouseY) {
		RichTooltip tip = new RichTooltip();
		NationData n = ClientNations.get(p.nation);
		int color = n != null ? n.color : 0xFFFFFF;
		tip.text(Component.literal(p.title()).withStyle(style -> style.withColor(color).withBold(true)));
		tip.text(Component.literal((p.capital ? "Capital - " : "") + p.type.displayName).withColor(p.capital ? 0xFFD54F : 0xAAAAAA));

		if (n != null) {
			tip.text(Component.literal("Part of ").withColor(0xAAAAAA).append(Component.literal(n.name).withColor(n.color)));
			tip.text(Component.literal(n.faction.displayName + "  \u00B7  " + n.ideology.displayName).withColor(n.faction.color));
		}

		if (p.type == com.mapnationswars.nation.ProvinceData.Type.VILLAGE && !p.abandoned) {
			tip.text(Component.literal("Mayor: ").withColor(0xAAAAAA).append(Component.literal(p.mayorName).withColor(0xFFE0A0)));
			tip.text(Component.literal("Villagers: " + p.population + "   Land: " + p.chunks + " chunks").withColor(0x9CE0A0));
		} else if (p.abandoned) {
			tip.text(Component.literal("Only zombies live here now").withColor(0x88AA66));
		} else {
			tip.text(Component.literal("Commander: ").withColor(0xAAAAAA).append(Component.literal(p.mayorName).withColor(0xFFB0A0)));
			tip.text(Component.literal("Garrison: ~" + p.population + "   Land: " + p.chunks + " chunks").withColor(0xE0B0A0));
		}

		tip.text(Component.literal("X " + p.x + "  Z " + p.z + "   Right-click: nation page").withColor(0x777777));
		tip.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
	}

	private void allianceTooltip(GuiGraphicsExtractor graphics, AllianceData a, NationData n, int mouseX, int mouseY) {
		RichTooltip tip = new RichTooltip();

		if (a == null) {
			tip.text(Component.literal(n.name).withStyle(style -> style.withColor(n.color).withBold(true)));
			tip.text(Component.literal("Not in any alliance").withColor(0x9AA0A6));
		} else {
			tip.text(Component.literal(a.name).withStyle(style -> style.withColor(a.color).withBold(true)));
			NationData head = ClientNations.get(a.head());
			tip.text(Component.literal(a.nations.size() + (a.nations.size() == 1 ? " nation" : " nations") + "   "
					+ ClientNations.allianceChunks(a) + " chunks").withColor(0xDDDDDD));

			if (head != null) {
				tip.text(Component.literal("Led by ").withColor(0xAAAAAA).append(Component.literal(head.name).withColor(head.color)));
			}

			tip.text(Component.literal("This land: ").withColor(0xAAAAAA).append(Component.literal(n.name).withColor(n.color)));
			tip.text(Component.literal("Click for details").withColor(0x777777));
		}

		tip.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
	}

	private UUID legendNationAt(double mouseX, double mouseY) {
		if (mouseX < this.legendX - 4 || mouseX >= this.legendX + this.legendW || mouseY < this.legendY - 1) {
			return null;
		}

		int index = (int) ((mouseY - this.legendY + 1) / 11);
		return index >= 0 && index < this.legendRows.size() ? this.legendRows.get(index) : null;
	}

	/** Centres the map on a nation's land and zooms so all of it fits. */
	private void zoomToNation(UUID nationId) {
		String dim = this.dimensionId();
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (Map.Entry<Long, UUID> e : ClientNations.claims(dim).entrySet()) {
			if (e.getValue().equals(nationId)) {
				int cx = MapNationsMod.keyX(e.getKey());
				int cz = MapNationsMod.keyZ(e.getKey());
				minX = Math.min(minX, cx);
				maxX = Math.max(maxX, cx);
				minZ = Math.min(minZ, cz);
				maxZ = Math.max(maxZ, cz);
			}
		}

		if (minX == Integer.MAX_VALUE) {
			return;
		}

		double widthBlocks = (maxX - minX + 1) * 16.0;
		double heightBlocks = (maxZ - minZ + 1) * 16.0;
		this.followPlayer = false;
		this.centerX = (minX + maxX + 1) * 8.0;
		this.centerZ = (minZ + maxZ + 1) * 8.0;
		this.zoom = Mth.clamp(Math.min(this.width * 0.7 / widthBlocks, (this.height - TOP_BAR) * 0.7 / heightBlocks), MIN_ZOOM, MAX_ZOOM);
		savedZoom = this.zoom;
	}

	/** Proposed land: striped in the nation's colour and white, waiting for the leader. */
	private void drawProposals(GuiGraphicsExtractor graphics, String dim) {
		double chunkPx = 16 * this.zoom;

		for (Map.Entry<Long, com.mapnationswars.network.NationsSyncPayload.Proposal> e : ClientNations.proposals(dim).entrySet()) {
			NationData nation = ClientNations.get(e.getValue().nation());

			if (nation == null) {
				continue;
			}

			int cx = MapNationsMod.keyX(e.getKey());
			int cz = MapNationsMod.keyZ(e.getKey());
			double sx1 = this.toScreenX(cx * 16.0);
			double sy1 = this.toScreenY(cz * 16.0);

			if (sx1 > this.width || sy1 > this.height || sx1 + chunkPx < 0 || sy1 + chunkPx < 0) {
				continue;
			}

			int x1 = clampPx(sx1);
			int y1 = clampPx(sy1);
			int x2 = Math.max(x1 + 1, clampPx(sx1 + chunkPx));
			int y2 = Math.max(y1 + 1, clampPx(sy1 + chunkPx));
			this.drawStripeColors(graphics, new int[] {nation.color, 0xFFFFFF}, 0x98, cx, cz, x1, y1, x2, y2);

			int c = 0xFF000000 | nation.color;
			graphics.fill(x1, y1, x2, y1 + 1, c);
			graphics.fill(x1, y2 - 1, x2, y2, c);
			graphics.fill(x1, y1, x1 + 1, y2, c);
			graphics.fill(x2 - 1, y1, x2, y2, c);
		}
	}

	private void proposalTooltip(GuiGraphicsExtractor graphics, com.mapnationswars.network.NationsSyncPayload.Proposal p, int mouseX, int mouseY) {
		NationData nation = ClientNations.get(p.nation());

		if (nation == null) {
			return;
		}

		RichTooltip tip = new RichTooltip();
		tip.text(Component.literal("Proposed territory").withStyle(style -> style.withColor(0xFFFFFF).withBold(true)));
		tip.text(Component.literal("for ").withColor(0xAAAAAA).append(Component.literal(nation.name).withColor(nation.color)));
		tip.text(Component.literal("Proposed by " + p.proposer()).withColor(0xAAAAAA));
		tip.text(Component.literal("Waiting for the " + nation.ideology.leaderTitle + " to annex it").withColor(0x888888));

		LocalPlayer self = this.minecraft.player;

		if (this.claimMode && self != null && nation.leader.equals(self.getUUID())) {
			tip.text(Component.literal("Click: annex   Right-click: deny").withColor(0x9CFF9C));
		}

		tip.draw(graphics, this.font, mouseX, mouseY, this.width, this.height);
	}

	/** Called from the place window: a settlement has to get its borders before it's placed. */
	public void startNewSettlement(MarkerType type, String label, int visibility, int x, int z) {
		MarkerData temp = new MarkerData(new UUID(0, 0));
		LocalPlayer self = this.minecraft.player;
		NationData mine = ClientNations.myNation();
		temp.owner = self != null ? self.getUUID() : new UUID(0, 0);
		temp.ownerName = self != null ? self.getName().getString() : "";
		temp.nation = mine != null ? mine.id : null;
		temp.dimension = this.dimensionId();
		temp.x = x;
		temp.z = z;
		temp.type = type;
		temp.label = label;
		temp.visibility = visibility;

		this.editingArea = temp;
		this.pendingPlace = true;
		this.placingType = null;
		this.movingMarker = null;
		this.workingArea.clear();

		// start with just the chunk the marker stands in; the player drags to make it bigger
		int cx = Math.floorDiv(x, 16);
		int cz = Math.floorDiv(z, 16);
		this.workingArea.add(MapNationsMod.chunkKey(cx, cz));
		this.originalArea.clear();
		this.originalArea.addAll(this.workingArea);
		ClientNations.setStatus("Choose the borders of your " + type.displayName + ", then click \"Place it\" (or Cancel).", true);
		this.rebuildWidgets();
	}

	/** Called from the marker menu: edit a settlement's borders on the map. */
	public void startEditingArea(MarkerData marker) {
		this.editingArea = marker;
		this.pendingPlace = false;
		this.workingArea.clear();
		this.workingArea.addAll(marker.area);
		this.originalArea.clear();
		this.originalArea.addAll(marker.area);
		this.placingType = null;
		this.movingMarker = null;
		ClientNations.setStatus("Editing the borders of " + marker.title() + ". Click \"Apply\" to save or \"Cancel\" to keep the old ones.", true);
		this.rebuildWidgets(); // show the Apply / Reset / Cancel buttons
	}

	/** Puts the borders back to how they were when editing started. */
	private void resetArea() {
		this.workingArea.clear();
		this.workingArea.addAll(this.originalArea);
	}

	/** Stops editing without saving anything. */
	private void cancelArea() {
		if (this.editingArea != null) {
			ClientNations.setStatus(this.pendingPlace ? "Cancelled - the " + this.editingArea.type.displayName + " was not placed."
					: "Cancelled - the borders of " + this.editingArea.title() + " were not changed.", false);
		}

		this.editingArea = null;
		this.pendingPlace = false;
		this.selecting = false;
		this.rebuildWidgets();
	}

	private boolean areaChanged() {
		return !this.workingArea.equals(this.originalArea);
	}

	/** Adds or removes the chunks of a rectangle to the borders being edited (only allowed chunks). */
	private void editArea(int fromX, int fromZ, int toX, int toZ, boolean add) {
		MarkerData m = this.editingArea;
		int mx = Math.floorDiv(m.x, 16);
		int mz = Math.floorDiv(m.z, 16);
		long own = MapNationsMod.chunkKey(mx, mz);
		NationData land = ClientNations.nationAt(m.dimension, mx, mz);
		int skipped = 0;

		for (int cx = Math.min(fromX, toX); cx <= Math.max(fromX, toX); cx++) {
			for (int cz = Math.min(fromZ, toZ); cz <= Math.max(fromZ, toZ); cz++) {
				long key = MapNationsMod.chunkKey(cx, cz);

				if (!add) {
					if (key != own) {
						this.workingArea.remove(key);
					}

					continue;
				}

				if (this.workingArea.contains(key)) {
					continue;
				}

				NationData owner = ClientNations.nationAt(m.dimension, cx, cz);
				boolean tooFar = Math.max(Math.abs(cx - mx), Math.abs(cz - mz)) > MarkerType.AREA_REACH;
				boolean foreign = owner != null && owner != land;
				boolean taken = false;

				for (MarkerData other : ClientMarkers.all()) {
					if (other != m && other.dimension.equals(m.dimension) && other.area.contains(key)) {
						taken = true;
					}
				}

				if (tooFar || foreign || taken || this.workingArea.size() >= m.type.maxArea()) {
					skipped++;
				} else {
					this.workingArea.add(key);
				}
			}
		}

		if (skipped > 0) {
			ClientNations.setStatus(skipped + " chunks left out (too far, too many, another settlement's or another nation's land).", false);
		}
	}

	/** Dark box behind the Apply / Reset / Cancel buttons, placed just above the info text. */
	private void drawEditBar(GuiGraphicsExtractor graphics, int infoTop) {
		if (this.editingArea == null || this.applyButton == null) {
			return;
		}

		int y = infoTop - 26;
		int left = this.resetButton.getX() - 96 - 4 - 6;
		int right = this.resetButton.getX() + 96 + 4 + 96 + 6;
		String title = (this.pendingPlace ? "New " + this.editingArea.type.displayName : this.editingArea.title())
				+ " - " + this.workingArea.size() + " / " + this.editingArea.type.maxArea() + " chunks"
				+ (!this.pendingPlace && this.areaChanged() ? "  (not saved yet)" : "");
		right = Math.max(right, this.width / 2 + this.font.width(title) / 2 + 6);
		left = Math.min(left, this.width / 2 - this.font.width(title) / 2 - 6);

		this.editBarLeft = left - 1;
		this.editBarRight = right + 1;
		this.editBarTop = y - 15;
		this.editBarBottom = y + 25;
		graphics.fill(left - 1, y - 15, right + 1, y + 25, 0xFFFFD54F);
		graphics.fill(left, y - 14, right, y + 24, 0xE01B1F24);
		graphics.centeredText(this.font, title, this.width / 2, y - 11, 0xFFFFD54F);

		this.applyButton.setY(y + 2);
		this.resetButton.setY(y + 2);
		this.cancelButton.setY(y + 2);
		this.applyButton.active = this.pendingPlace || this.areaChanged();
		this.resetButton.active = this.areaChanged();
	}

	private void saveArea() {
		if (this.editingArea != null && this.pendingPlace) {
			MarkerData m = this.editingArea;

			if (ClientPlayNetworking.canSend(MarkerActionPayload.TYPE)) {
				ClientPlayNetworking.send(new MarkerActionPayload(MarkerActionPayload.PLACE, "", m.x, m.z, m.type.name(), m.label,
						m.visibility, new ArrayList<>(this.workingArea)));
			}
		} else if (this.editingArea != null && ClientPlayNetworking.canSend(MarkerAreaPayload.TYPE)) {
			if (this.areaChanged()) {
				ClientPlayNetworking.send(new MarkerAreaPayload(this.editingArea.id.toString(), new ArrayList<>(this.workingArea)));
			} else {
				ClientNations.setStatus("No changes - the borders stay the same.", true);
			}
		}

		this.pendingPlace = false;
		this.editingArea = null;
		this.rebuildWidgets();
	}

	/** Outline (and light fill) of a set of chunks - used for settlement borders. */
	private void drawArea(GuiGraphicsExtractor graphics, java.util.Collection<Long> chunks, int rgb, boolean editing) {
		java.util.Set<Long> set = chunks instanceof java.util.Set<Long> s ? s : new java.util.HashSet<>(chunks);
		int line = 16 * this.zoom >= 12 ? 2 : 1;
		int solid = 0xFF000000 | rgb;

		for (long key : set) {
			int cx = MapNationsMod.keyX(key);
			int cz = MapNationsMod.keyZ(key);
			int x1 = clampPx(this.toScreenX(cx * 16.0));
			int y1 = clampPx(this.toScreenY(cz * 16.0));
			int x2 = Math.max(x1 + 1, clampPx(this.toScreenX(cx * 16.0 + 16)));
			int y2 = Math.max(y1 + 1, clampPx(this.toScreenY(cz * 16.0 + 16)));

			if (x2 < 0 || y2 < 0 || x1 > this.width || y1 > this.height) {
				continue;
			}

			graphics.fill(x1, y1, x2, y2, (editing ? 0x60000000 : 0x38000000) | rgb);

			if (!set.contains(MapNationsMod.chunkKey(cx, cz - 1))) {
				graphics.fill(x1, y1, x2, y1 + line, solid);
			}
			if (!set.contains(MapNationsMod.chunkKey(cx, cz + 1))) {
				graphics.fill(x1, y2 - line, x2, y2, solid);
			}
			if (!set.contains(MapNationsMod.chunkKey(cx - 1, cz))) {
				graphics.fill(x1, y1, x1 + line, y2, solid);
			}
			if (!set.contains(MapNationsMod.chunkKey(cx + 1, cz))) {
				graphics.fill(x2 - line, y1, x2, y2, solid);
			}
		}
	}

	/** A little compass in the bottom-right corner (north is always up on this map). */
	private void drawCompass(GuiGraphicsExtractor graphics) {
		int r = 18;
		int cx = this.panelLeft() - r - (this.panelVisible() ? 22 : 8);
		int cy = this.height - r - 8;

		graphics.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, 0xB0101418);
		graphics.fill(cx - r, cy - r, cx + r + 1, cy - r + 1, 0xFF3A4048);
		graphics.fill(cx - r, cy + r, cx + r + 1, cy + r + 1, 0xFF3A4048);
		graphics.fill(cx - r, cy - r, cx - r + 1, cy + r + 1, 0xFF3A4048);
		graphics.fill(cx + r, cy - r, cx + r + 1, cy + r + 1, 0xFF3A4048);

		// needle: red half points north, white half south
		for (int i = 0; i < 9; i++) {
			int half = (9 - i) / 3;
			graphics.fill(cx - half, cy - 10 + i, cx + half + 1, cy - 9 + i, 0xFFE53935);
			graphics.fill(cx - half, cy + 9 - i, cx + half + 1, cy + 10 - i, 0xFFEEEEEE);
		}

		graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFF1B1B1B);
		graphics.centeredText(this.font, "N", cx + 1, cy - r + 2, 0xFFFF5555);
		graphics.centeredText(this.font, "S", cx + 1, cy + r - 9, 0xFFDDDDDD);
		graphics.text(this.font, "W", cx - r + 3, cy - 4, 0xFFDDDDDD);
		graphics.text(this.font, "E", cx + r - 7, cy - 4, 0xFFDDDDDD);
	}

	private void requestClaimArea(int fromX, int fromZ, int toX, int toZ, boolean claim) {
		if (!ClientPlayNetworking.canSend(ClaimAreaPayload.TYPE)) {
			ClientNations.setStatus("This server doesn't have Map Nations WARS installed, so claiming won't work here.", false);
			return;
		}

		NationData myNation = ClientNations.myNation();

		if (myNation == null) {
			ClientNations.setStatus("Join or create a nation first (Nations tab).", false);
			return;
		}

		LocalPlayer self = this.minecraft.player;

		if (self != null && !myNation.leader.equals(self.getUUID()) && !myNation.isOfficer(self.getUUID())) {
			ClientNations.setStatus("Only the " + myNation.ideology.leaderTitle + " claims land. Officers can propose land.", false);
			return;
		}

		long area = (long) (Math.abs(toX - fromX) + 1) * (Math.abs(toZ - fromZ) + 1);

		if (area > ClaimAreaPayload.MAX_CHUNKS) {
			ClientNations.setStatus("Too big - at most " + ClaimAreaPayload.MAX_CHUNKS + " chunks at once.", false);
			return;
		}

		ClientPlayNetworking.send(new ClaimAreaPayload(fromX, fromZ, toX, toZ, claim));
	}

	/** The rectangle being dragged in the Claims tab. */
	private void drawSelection(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int minX = Math.min(this.selFromX, this.selToX);
		int maxX = Math.max(this.selFromX, this.selToX);
		int minZ = Math.min(this.selFromZ, this.selToZ);
		int maxZ = Math.max(this.selFromZ, this.selToZ);
		boolean claim = this.pressButton == SDLMouse.SDL_BUTTON_LEFT;
		long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
		boolean tooBig = area > ClaimAreaPayload.MAX_CHUNKS;
		int color = tooBig ? 0xFF7070 : (claim ? 0x6CFF6C : 0xFF9C40);

		int x1 = clampPx(this.toScreenX(minX * 16.0));
		int y1 = clampPx(this.toScreenY(minZ * 16.0));
		int x2 = clampPx(this.toScreenX((maxX + 1) * 16.0));
		int y2 = clampPx(this.toScreenY((maxZ + 1) * 16.0));

		graphics.fill(x1, y1, x2, y2, 0x40000000 | color);
		graphics.fill(x1, y1, x2, y1 + 2, 0xFF000000 | color);
		graphics.fill(x1, y2 - 2, x2, y2, 0xFF000000 | color);
		graphics.fill(x1, y1, x1 + 2, y2, 0xFF000000 | color);
		graphics.fill(x2 - 2, y1, x2, y2, 0xFF000000 | color);

		String text;

		if (this.editingArea != null) {
			tooBig = false;
			color = claim ? 0xFFD54F : 0xFF9C40;
			text = (claim ? "Add " : "Remove ") + area + (area == 1 ? " chunk" : " chunks");
		} else {
			NationData mine = ClientNations.myNation();
			LocalPlayer self = this.minecraft.player;
			boolean officer = mine != null && self != null && !mine.leader.equals(self.getUUID());
			String verb = claim ? (officer ? "Propose " : "Claim ") : (officer ? "Take back " : "Unclaim ");
			text = tooBig ? "Too big: " + area + " chunks (max " + ClaimAreaPayload.MAX_CHUNKS + ")"
					: verb + area + (area == 1 ? " chunk" : " chunks");
		}
		int w = this.font.width(text);
		int tx = Math.min(mouseX + 12, this.width - w - 6);
		graphics.fill(tx - 3, mouseY + 12, tx + w + 3, mouseY + 24, 0xC0000000);
		graphics.text(this.font, text, tx, mouseY + 14, 0xFF000000 | color);
	}

	/** The marker being moved follows the mouse. */
	private void drawMovingGhost(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		MarkerData m = this.movingMarker;
		int bx = Mth.floor(this.toWorldX(mouseX));
		int bz = Mth.floor(this.toWorldZ(mouseY));
		NationData land = ClientNations.nationAt(m.dimension, Math.floorDiv(bx, 16), Math.floorDiv(bz, 16));

		graphics.nextStratum();
		MarkerIcons.draw(graphics, m.type, land, m.visibility, m.owner, m.nation, mouseX, mouseY, this.markerSize(m.type));
		String hint = "Click to move " + m.title() + " here  -  right-click to cancel";
		int w = this.font.width(hint);
		int x = Math.min(mouseX + 12, this.width - w - 6);
		graphics.fill(x - 3, mouseY + 12, x + w + 3, mouseY + 24, 0xC0000000);
		graphics.text(this.font, hint, x, mouseY + 14, 0xFFFFFFFF);
	}

	private void requestClaimToggle(int cx, int cz) {
		if (!ClientPlayNetworking.canSend(ToggleClaimPayload.TYPE)) {
			ClientNations.setStatus("This server doesn't have Map Nations WARS installed, so claiming won't work here.", false);
			return;
		}

		NationData mine = ClientNations.myNation();

		if (mine == null) {
			ClientNations.setStatus("Join or create a nation first (Nations tab).", false);
			return;
		}

		LocalPlayer self = this.minecraft.player;

		if (self != null && !mine.leader.equals(self.getUUID()) && !mine.isOfficer(self.getUUID())) {
			ClientNations.setStatus("Only the " + mine.ideology.leaderTitle + " claims land. Officers can propose land.", false);
			return;
		}

		for (UUID id : ClientNations.claimants(this.dimensionId(), cx, cz)) {
			if (mine.allies.contains(id)) {
				NationData ally = ClientNations.get(id);
				ClientNations.setStatus("That land belongs to your ally " + (ally != null ? ally.name : "") + ".", false);
				return;
			}
		}

		ClientPlayNetworking.send(new ToggleClaimPayload(cx, cz));
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		int key = input.key();

		if (this.editingArea != null && key == SDLScancode.SDL_SCANCODE_ESCAPE) {
			this.cancelArea(); // Esc = Cancel (keeps the old borders)
			return true;
		}

		if (this.editingArea != null && (key == SDLScancode.SDL_SCANCODE_RETURN || key == SDLScancode.SDL_SCANCODE_KP_ENTER)
				&& (this.pendingPlace || this.areaChanged())) {
			this.saveArea(); // Enter = Apply
			return true;
		}

		if (key == SDLScancode.SDL_SCANCODE_ESCAPE && (this.placingType != null || this.movingMarker != null)) {
			this.placingType = null;
			this.movingMarker = null;
			return true;
		}

		double step = 64.0 / this.zoom;

		if (key == SDLScancode.SDL_SCANCODE_W || key == SDLScancode.SDL_SCANCODE_UP) {
			this.centerZ -= step;
			this.followPlayer = false;
			return true;
		}
		if (key == SDLScancode.SDL_SCANCODE_S || key == SDLScancode.SDL_SCANCODE_DOWN) {
			this.centerZ += step;
			this.followPlayer = false;
			return true;
		}
		if (key == SDLScancode.SDL_SCANCODE_A || key == SDLScancode.SDL_SCANCODE_LEFT) {
			this.centerX -= step;
			this.followPlayer = false;
			return true;
		}
		if (key == SDLScancode.SDL_SCANCODE_D || key == SDLScancode.SDL_SCANCODE_RIGHT) {
			this.centerX += step;
			this.followPlayer = false;
			return true;
		}
		if (key == SDLScancode.SDL_SCANCODE_H) {
			// H hides / shows all markers
			if (markerFilter == FILTER_OFF) {
				markerFilter = filterBeforeHiding;
			} else {
				filterBeforeHiding = markerFilter;
				markerFilter = FILTER_OFF;
			}

			this.rebuildWidgets();
			return true;
		}

		if (key == SDLScancode.SDL_SCANCODE_C) {
			this.followPlayer = true;
			return true;
		}
		if (key == SDLScancode.SDL_SCANCODE_P && !this.claimMode) {
			this.togglePolitical();
			return true;
		}
		if (key == SDLScancode.SDL_SCANCODE_EQUALS || key == SDLScancode.SDL_SCANCODE_KP_PLUS) {
			this.zoomAt(this.width / 2.0, this.height / 2.0, 2.0);
			return true;
		}
		if (key == SDLScancode.SDL_SCANCODE_MINUS || key == SDLScancode.SDL_SCANCODE_KP_MINUS) {
			this.zoomAt(this.width / 2.0, this.height / 2.0, 0.5);
			return true;
		}

		return super.keyPressed(input);
	}
}
