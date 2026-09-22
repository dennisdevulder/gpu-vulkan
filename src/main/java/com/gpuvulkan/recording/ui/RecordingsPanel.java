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
import com.gpuvulkan.recording.RecordingHandle;
import com.gpuvulkan.recording.RecordingKind;
import com.gpuvulkan.recording.RecordingService;
import com.gpuvulkan.recording.RecordingStore;
import com.gpuvulkan.recording.SystemAudioSource;
import com.gpuvulkan.recording.events.RecordingDeleted;
import com.gpuvulkan.recording.events.RecordingProgress;
import com.gpuvulkan.recording.events.RecordingSaved;
import com.gpuvulkan.recording.events.RecordingStarted;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/** Sidebar browser for the library, built on the public service and events. */
@Slf4j
public final class RecordingsPanel extends PluginPanel
{
	private static final String ALL_KINDS = "All kinds";
	/** Off, as an entry in the device list rather than a separate toggle. */
	private static final String NO_AUDIO = "None";

	private final RecordingService service;
	private final Path root;
	private final AudioDeviceSetting audioSetting;

	/** How the panel persists the chosen device; the plugin supplies config access. */
	public interface AudioDeviceSetting
	{
		String get();

		void set(String device);

		boolean enabled();

		void setEnabled(boolean enabled);
	}

	private final JLabel summary = new JLabel();
	private final JLabel status = new JLabel();
	private final JComboBox<String> kindFilter = new JComboBox<>();
	private final JComboBox<String> audioDevice = new JComboBox<>();
	private final JLabel audioHeading = caption("Audio", true);
	private final JLabel audioDeviceLabel = caption("Record from", false);
	private final LevelMeter audioMeter = new LevelMeter();
	private final JLabel audioStatus = new JLabel();
	private final JTextField search = new JTextField();
	private final JPanel livePanel = new JPanel();
	private final JPanel listPanel = new JPanel();
	/** Rebuilding the kind combo fires its listener; ignore it until settled. */
	private boolean rebuildingFilters;
	/** Capture state changes without a config edit -- a restart completing, a
	 *  device being routed or unplugged -- so the status line has to re-ask. */
	private final javax.swing.Timer audioPoll = new javax.swing.Timer(1000, e -> refreshAudioStatus());
	private final javax.swing.Timer meterPoll = new javax.swing.Timer(50, e -> refreshMeters());

	private final RecordingCard.Actions actions = new RecordingCard.Actions()
	{
		@Override
		public void open(RecordingEntry entry)
		{
			openFile(pathOf(entry));
		}

		@Override
		public void reveal(RecordingEntry entry)
		{
			Path file = pathOf(entry);
			openFile(file.getParent() == null ? file : file.getParent());
		}

		@Override
		public void togglePin(RecordingEntry entry)
		{
			service.setPinned(entry.id(), !entry.pinned());
			refresh();
		}

		@Override
		public void delete(RecordingEntry entry)
		{
			int choice = JOptionPane.showConfirmDialog(RecordingsPanel.this,
				"Delete this recording?", "Delete", JOptionPane.YES_NO_OPTION);
			if (choice == JOptionPane.YES_OPTION)
			{
				service.delete(entry.id());
				refresh();
			}
		}
	};

	public RecordingsPanel(RecordingService service, Path root, AudioDeviceSetting audioSetting)
	{
		super(false);
		this.service = service;
		this.root = root;
		this.audioSetting = audioSetting;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(header(), BorderLayout.NORTH);
		add(scrollingList(), BorderLayout.CENTER);
		refresh();
	}

	/** Small left-aligned caption above a control. */
	private static JLabel caption(String text, boolean heading)
	{
		JLabel label = new JLabel(text);
		label.setFont(heading
			? FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD)
			: FontManager.getRunescapeSmallFont());
		label.setForeground(heading ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JPanel header()
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setOpaque(false);

		JLabel title = new JLabel("Recordings");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);

		summary.setFont(FontManager.getRunescapeSmallFont());
		summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		summary.setAlignmentX(Component.LEFT_ALIGNMENT);

		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		status.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel filters = new JPanel(new GridLayout(2, 1, 0, 4));
		filters.setOpaque(false);
		filters.setAlignmentX(Component.LEFT_ALIGNMENT);
		filters.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
		kindFilter.addActionListener(e ->
		{
			if (!rebuildingFilters)
			{
				refreshList();
			}
		});
		search.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyReleased(KeyEvent e)
			{
				refreshList();
			}
		});
		search.setToolTipText("Filter by name");
		filters.add(kindFilter);
		filters.add(search);

		livePanel.setLayout(new BoxLayout(livePanel, BoxLayout.Y_AXIS));
		livePanel.setOpaque(false);
		livePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		header.add(title);
		header.add(Box.createVerticalStrut(2));
		header.add(summary);
		header.add(status);
		audioStatus.setFont(FontManager.getRunescapeSmallFont());
		audioStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		audioDevice.setAlignmentX(Component.LEFT_ALIGNMENT);
		audioDevice.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		audioDevice.setToolTipText("The device recordings take their sound from");
		audioDevice.addActionListener(e ->
		{
			Object selected = audioDevice.getSelectedItem();
			if (rebuildingFilters || selected == null)
			{
				return;
			}
			if (NO_AUDIO.equals(selected))
			{
				audioSetting.setEnabled(false);
			}
			else
			{
				audioSetting.set(String.valueOf(selected));
				audioSetting.setEnabled(true);
			}
			refreshAudio();
		});


		header.add(Box.createVerticalStrut(6));
		header.add(filters);
		header.add(Box.createVerticalStrut(8));
		header.add(audioHeading);
		header.add(Box.createVerticalStrut(2));
		header.add(audioDeviceLabel);
		header.add(audioDevice);
		header.add(Box.createVerticalStrut(3));
		header.add(audioMeter);
		header.add(Box.createVerticalStrut(2));
		header.add(audioStatus);
		header.add(Box.createVerticalStrut(4));
		header.add(livePanel);
		header.add(Box.createVerticalStrut(4));
		return header;
	}

	private JScrollPane scrollingList()
	{
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setOpaque(false);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setOpaque(false);
		wrapper.add(listPanel, BorderLayout.NORTH);

		JScrollPane scroll = new JScrollPane(wrapper,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	public void refresh()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refresh);
			return;
		}
		refreshKindFilter();
		refreshAudio();
		refreshSummary();
		refreshLive();
		refreshList();
	}

	private void refreshKindFilter()
	{
		Object selected = kindFilter.getSelectedItem();
		rebuildingFilters = true;
		try
		{
			kindFilter.removeAllItems();
			kindFilter.addItem(ALL_KINDS);
			List<String> present = new ArrayList<>();
			for (RecordingEntry entry : service.index())
			{
				String name = service.kinds().resolve(entry.kindId()).displayName();
				if (!present.contains(name))
				{
					present.add(name);
				}
			}
			present.sort(String::compareToIgnoreCase);
			for (String name : present)
			{
				kindFilter.addItem(name);
			}
			if (selected != null && present.contains(selected))
			{
				kindFilter.setSelectedItem(selected);
			}
		}
		finally
		{
			rebuildingFilters = false;
		}
	}

	/** Devices are discovered, and can appear or vanish while the client runs. */
	private void refreshAudio()
	{
		boolean on = audioSetting.enabled();
		// The picker stays visible when off: choosing a device is how audio is
		// turned back on, so hiding it would strand the user on "None".
		audioMeter.setVisible(on);
		audioStatus.setVisible(on);

		rebuildingFilters = true;
		try
		{
			List<String> devices = SystemAudioSource.captureDevices();
			String chosen = audioSetting.get();
			audioDevice.removeAllItems();
			audioDevice.addItem(NO_AUDIO);
			audioDevice.addItem("default");
			for (String name : devices)
			{
				audioDevice.addItem(name);
			}
			audioDevice.setSelectedItem(!on ? NO_AUDIO
				: chosen == null || chosen.isEmpty() ? "default" : chosen);

		}
		finally
		{
			rebuildingFilters = false;
		}

		refreshAudioStatus();
	}

	private void refreshAudioStatus()
	{
		if (!audioSetting.enabled())
		{
			return;
		}
		if (!service.audioCapturing())
		{
			audioStatus.setText("Audio: starting");
			audioStatus.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
		}
		else if (service.audioSilent())
		{
			audioStatus.setText("Audio: no signal");
			audioStatus.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
		}
		else
		{
			audioStatus.setText("Audio: capturing");
			audioStatus.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		}
	}

	private void refreshSummary()
	{
		List<RecordingEntry> all = service.index();
		long bytes = 0;
		for (RecordingEntry entry : all)
		{
			bytes += entry.sizeBytes();
		}
		summary.setText(all.size() + (all.size() == 1 ? " recording  " : " recordings  ")
			+ RecordingFormat.size(bytes));

		String reason = service.unavailableReason();
		status.setText(reason == null ? "" : "Recording unavailable: " + reason);
		status.setVisible(reason != null);
	}

	private void refreshList()
	{
		listPanel.removeAll();
		String kindWanted = String.valueOf(kindFilter.getSelectedItem());
		String term = search.getText().trim().toLowerCase(Locale.ROOT);

		int shown = 0;
		for (RecordingEntry entry : service.index())
		{
			RecordingKind kind = service.kinds().resolve(entry.kindId());
			if (!ALL_KINDS.equals(kindWanted) && !kind.displayName().equals(kindWanted))
			{
				continue;
			}
			if (!term.isEmpty()
				&& !entry.description().toLowerCase(Locale.ROOT).contains(term)
				&& !kind.displayName().toLowerCase(Locale.ROOT).contains(term))
			{
				continue;
			}
			listPanel.add(new RecordingCard(entry, kind, thumbnailOf(entry, kind), actions));
			listPanel.add(Box.createVerticalStrut(4));
			shown++;
		}

		if (shown == 0)
		{
			JLabel empty = new JLabel(service.index().isEmpty()
				? "No recordings yet."
				: "Nothing matches that filter.");
			empty.setFont(FontManager.getRunescapeSmallFont());
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
			listPanel.add(empty);
		}
		listPanel.revalidate();
		listPanel.repaint();
	}

	private void refreshLive()
	{
		livePanel.removeAll();
		for (RecordingHandle handle : service.activeSessions())
		{
			JLabel label = new JLabel("REC  " + handle.kind().displayName()
				+ "  " + RecordingFormat.size(handle.bytesWritten()));
			label.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
			label.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
			livePanel.add(label);
		}
		livePanel.revalidate();
		livePanel.repaint();
	}

	private Path pathOf(RecordingEntry entry)
	{
		return root.resolve(service.kinds().resolve(entry.kindId()).folder()).resolve(entry.fileName());
	}

	private Path thumbnailOf(RecordingEntry entry, RecordingKind kind)
	{
		String name = entry.thumbnailName();
		if (name == null || name.isEmpty())
		{
			return null;
		}
		Path path = root.resolve(kind.folder()).resolve(name);
		return Files.isRegularFile(path) ? path : null;
	}

	private void openFile(Path path)
	{
		if (!Files.exists(path))
		{
			refresh();
			return;
		}
		// Desktop.open blocks while the handler starts; keep it off the EDT.
		new Thread(() ->
		{
			try
			{
				if (Desktop.isDesktopSupported())
				{
					Desktop.getDesktop().open(path.toFile());
				}
			}
			catch (IOException | UnsupportedOperationException e)
			{
				log.warn("Could not open {}", path, e);
			}
		}, "vkgpu-recordings-open").start();
	}

	/** Availability is a toggle away, so re-ask after one. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (com.gpuvulkan.GpuVulkanPluginConfig.GROUP.equals(event.getGroup()))
		{
			refresh();
		}
	}

	@Override
	public void onActivate()
	{
		refresh();
		audioPoll.start();
		meterPoll.start();
	}

	@Override
	public void onDeactivate()
	{
		audioPoll.stop();
		meterPoll.stop();
	}

	private void refreshMeters()
	{
		if (!audioSetting.enabled())
		{
			return;
		}
		audioMeter.setLevel(service.audioLevel());
	}

	@Subscribe
	public void onRecordingSaved(RecordingSaved event)
	{
		refresh();
	}

	@Subscribe
	public void onRecordingDeleted(RecordingDeleted event)
	{
		refresh();
	}

	@Subscribe
	public void onRecordingStarted(RecordingStarted event)
	{
		SwingUtilities.invokeLater(this::refreshLive);
	}

	@Subscribe
	public void onRecordingProgress(RecordingProgress event)
	{
		SwingUtilities.invokeLater(this::refreshLive);
	}

	public static Path defaultRoot()
	{
		return RecordingStore.defaultRoot();
	}
}
