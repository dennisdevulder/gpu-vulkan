/*
 * Ported from net.runelite.client.plugins.gpu.RegionManager
 * (BSD-2-Clause, Copyright (c) 2025, Adam <Adam@sigterm.info>).
 */
package com.gpuvulkan;

import java.io.IOException;
import java.io.InputStream;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import com.gpuvulkan.regions.Regions;

/**
 * Must be called before geometry capture so the removed tiles are absent
 * from the per-frame draw.
 */
final class RegionManager
{
	private static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;

	private final Regions regions;

	RegionManager()
	{
		try (InputStream in = RegionManager.class.getResourceAsStream("regions/regions.txt"))
		{
			if (in == null) throw new IOException("regions/regions.txt not on classpath");
			regions = new Regions(in, "regions.txt");
		}
		catch (IOException ex)
		{
			throw new RuntimeException("Failed to load gpuvulkan regions data", ex);
		}
	}

	/**
	 * Walks chunks within {@code EXTENDED_SCENE_SIZE / 16} of the player
	 * and {@code removeTile}s any whose region ID differs from the
	 * player's chunk. No-op on instances or when disabled.
	 */
	void prepare(Scene scene, boolean hideUnrelatedMaps)
	{
		if (scene.isInstance() || !hideUnrelatedMaps)
		{
			return;
		}

		int baseX = scene.getBaseX() / 8;
		int baseY = scene.getBaseY() / 8;
		int centerX = baseX + 6;
		int centerY = baseY + 6;
		int centerId = regions.getRegionId(centerX, centerY);

		int r = Constants.EXTENDED_SCENE_SIZE / 16;
		for (int offx = -r; offx <= r; ++offx)
		{
			for (int offy = -r; offy <= r; ++offy)
			{
				int cx = centerX + offx;
				int cy = centerY + offy;
				int id = regions.getRegionId(cx, cy);
				if (id != centerId)
				{
					removeZone(scene, cx, cy);
				}
			}
		}
	}

	private static void removeZone(Scene scene, int cx, int cy)
	{
		int wx = cx * 8;
		int wy = cy * 8;
		int sx = wx - scene.getBaseX();
		int sy = wy - scene.getBaseY();
		int cmsx = sx + SCENE_OFFSET;
		int cmsy = sy + SCENE_OFFSET;
		Tile[][][] tiles = scene.getExtendedTiles();
		for (int x = 0; x < 8; ++x)
		{
			for (int y = 0; y < 8; ++y)
			{
				int msx = cmsx + x;
				int msy = cmsy + y;
				if (msx >= 0 && msx < Constants.EXTENDED_SCENE_SIZE
					&& msy >= 0 && msy < Constants.EXTENDED_SCENE_SIZE)
				{
					for (int z = 0; z < Constants.MAX_Z; ++z)
					{
						Tile tile = tiles[z][msx][msy];
						if (tile != null)
						{
							scene.removeTile(tile);
						}
					}
				}
			}
		}
	}
}
