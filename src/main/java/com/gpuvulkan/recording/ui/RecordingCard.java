/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.gpuvulkan.recording.ui;

import com.gpuvulkan.recording.RecordingEntry;
import com.gpuvulkan.recording.RecordingKind;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/** One row in the recordings list. */
final class RecordingCard extends JPanel
{
	private static final int THUMB_WIDTH = 64;
	private static final int THUMB_HEIGHT = 36;

	interface Actions
	{
		void open(RecordingEntry entry);

		void reveal(RecordingEntry entry);

		void togglePin(RecordingEntry entry);

		void delete(RecordingEntry entry);
	}

	RecordingCard(RecordingEntry entry, RecordingKind kind, Path thumbnail, Actions actions)
	{
		setLayout(new BorderLayout(6, 0));
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		add(thumbnailLabel(kind, thumbnail), BorderLayout.WEST);
		add(details(entry, kind), BorderLayout.CENTER);

		String tooltip = entry.fileName() + (entry.session() ? " (long recording)" : "");
		setToolTipText(tooltip);

		JPopupMenu menu = buildMenu(entry, actions);
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1)
				{
					actions.open(entry);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		setComponentPopupMenu(menu);
	}

	private JPopupMenu buildMenu(RecordingEntry entry, Actions actions)
	{
		JPopupMenu menu = new JPopupMenu();
		menu.add(item("Play", () -> actions.open(entry)));
		menu.add(item("Show in folder", () -> actions.reveal(entry)));
		menu.add(item(entry.pinned() ? "Unpin" : "Pin (keep forever)", () -> actions.togglePin(entry)));
		menu.addSeparator();
		menu.add(item("Delete", () -> actions.delete(entry)));
		return menu;
	}

	private static JMenuItem item(String label, Runnable action)
	{
		JMenuItem menuItem = new JMenuItem(label);
		menuItem.addActionListener(e -> action.run());
		return menuItem;
	}

	private JPanel details(RecordingEntry entry, RecordingKind kind)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);

		String title = entry.description().isEmpty() ? kind.displayName() : entry.description();
		JLabel titleLabel = new JLabel((entry.pinned() ? "* " : "") + title);
		titleLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		titleLabel.setForeground(Color.WHITE);

		JLabel kindLabel = new JLabel(kind.displayName());
		kindLabel.setFont(FontManager.getRunescapeSmallFont());
		kindLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		StringBuilder meta = new StringBuilder(RecordingFormat.relativeTime(entry.triggeredAt()));
		if (entry.durationMs() > 0)
		{
			meta.append("  ").append(RecordingFormat.duration(entry.durationMs()));
		}
		meta.append("  ").append(RecordingFormat.size(entry.sizeBytes()));
		JLabel metaLabel = new JLabel(meta.toString());
		metaLabel.setFont(FontManager.getRunescapeSmallFont());
		metaLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		panel.add(titleLabel);
		panel.add(kindLabel);
		panel.add(metaLabel);
		return panel;
	}

	private JLabel thumbnailLabel(RecordingKind kind, Path thumbnail)
	{
		JLabel label = new JLabel();
		label.setPreferredSize(new Dimension(THUMB_WIDTH, THUMB_HEIGHT));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		BufferedImage shot = thumbnail == null ? null : read(thumbnail);
		if (shot != null)
		{
			label.setIcon(new ImageIcon(fit(shot)));
			// A 64px row is too small to recognise a moment; hovering shows the
			// frame at the size it was stored.
			label.setToolTipText("<html><img src=\"" + thumbnail.toUri() + "\"></html>");
			return label;
		}
		label.setIcon(new ImageIcon(placeholder(kind)));
		return label;
	}

	private static BufferedImage read(Path path)
	{
		try
		{
			return javax.imageio.ImageIO.read(path.toFile());
		}
		catch (java.io.IOException | RuntimeException e)
		{
			return null;
		}
	}

	/** Centre-crops to the card's aspect, then scales; letterboxing a 64px
	 *  wide row wastes most of it. */
	private static BufferedImage fit(BufferedImage source)
	{
		double want = (double) THUMB_WIDTH / THUMB_HEIGHT;
		int cropW = source.getWidth();
		int cropH = (int) Math.round(cropW / want);
		if (cropH > source.getHeight())
		{
			cropH = source.getHeight();
			cropW = (int) Math.round(cropH * want);
		}
		BufferedImage cropped = source.getSubimage(
			(source.getWidth() - cropW) / 2, (source.getHeight() - cropH) / 2, cropW, cropH);

		BufferedImage out = new BufferedImage(THUMB_WIDTH, THUMB_HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(cropped, 0, 0, THUMB_WIDTH, THUMB_HEIGHT, null);
		g.dispose();
		return out;
	}

	/** No thumbnail yet: a tinted initial keeps rows scannable by kind. */
	private static BufferedImage placeholder(RecordingKind kind)
	{
		BufferedImage image = new BufferedImage(THUMB_WIDTH, THUMB_HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int hash = kind.id().hashCode();
		g.setColor(new Color(60 + Math.abs(hash % 60), 60 + Math.abs((hash >> 8) % 60), 90));
		g.fillRoundRect(0, 0, THUMB_WIDTH, THUMB_HEIGHT, 6, 6);
		g.setColor(Color.WHITE);
		g.setFont(FontManager.getRunescapeBoldFont());
		String initial = kind.displayName().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
		int w = g.getFontMetrics().stringWidth(initial);
		g.drawString(initial, (THUMB_WIDTH - w) / 2, THUMB_HEIGHT / 2 + 5);
		g.dispose();
		return image;
	}
}
