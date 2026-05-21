package net.runelite.client.plugins.gpuvulkan;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

final class GpuVulkanDebugOverlay extends OverlayPanel
{
	private final GpuVulkanPlugin plugin;
	private final GpuVulkanPluginConfig config;

	GpuVulkanDebugOverlay(GpuVulkanPlugin plugin, GpuVulkanPluginConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_LOW);
		panelComponent.setPreferredSize(new Dimension(220, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.debugOverlay())
		{
			return null;
		}

		for (String line : plugin.debugOverlayLines())
		{
			int split = line.indexOf(':');
			if (split > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(line.substring(0, split))
					.right(line.substring(split + 1).trim())
					.build());
			}
			else
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(line)
					.build());
			}
		}

		return super.render(graphics);
	}
}
