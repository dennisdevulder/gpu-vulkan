/*
 * Copyright (c) 2026, Dennis de Vulder
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gpuvulkan.recording.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import net.runelite.client.ui.ColorScheme;

/**
 * Horizontal signal meter. Shows whether a device is actually carrying sound,
 * which is otherwise only discoverable by playing a recording back.
 */
final class LevelMeter extends JComponent
{
	private static final int HEIGHT = 6;
	/** Peak decays rather than snapping, so a transient stays visible. */
	private static final float DECAY = 0.12f;

	private float level;
	private float shown;

	LevelMeter()
	{
		setPreferredSize(new Dimension(0, HEIGHT));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
	}

	void setLevel(float value)
	{
		this.level = Math.max(0f, Math.min(1f, value));
		if (this.level >= shown)
		{
			shown = this.level;
		}
		else
		{
			shown = Math.max(this.level, shown - DECAY);
		}
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int w = getWidth();

		g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
		g2.fillRoundRect(0, 0, w, HEIGHT, HEIGHT, HEIGHT);

		int filled = Math.round(w * shown);
		if (filled > 0)
		{
			// Amber near the top of the scale: past this a mixed signal clips.
			g2.setColor(shown > 0.9f ? ColorScheme.PROGRESS_ERROR_COLOR
				: shown > 0.7f ? ColorScheme.PROGRESS_INPROGRESS_COLOR
				: ColorScheme.PROGRESS_COMPLETE_COLOR);
			g2.fillRoundRect(0, 0, Math.max(filled, HEIGHT), HEIGHT, HEIGHT, HEIGHT);
		}
		g2.setColor(new Color(0, 0, 0, 40));
		g2.drawRoundRect(0, 0, w - 1, HEIGHT - 1, HEIGHT, HEIGHT);
		g2.dispose();
	}
}
