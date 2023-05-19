package dev.lexoland.updater.rendering;

import dev.lexoland.updater.Updater;

import javax.swing.*;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class UpdateWindow extends JFrame {

	private static UpdateWindow instance;

	private UpdateWindow() {
		super("Updating...");
		setSize(600, 350);
		setResizable(false);
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		setLocationRelativeTo(null);
		add(new UpdateRenderer());
	}

	public static void open() {
		List<String> fonts = new ArrayList<>();
		fonts.add("MinecraftRegular.otf");
		fonts.add("MinecraftBold.otf");
		fonts.add("MinecraftItalic.otf");
		fonts.add("MinecraftBoldItalic.otf");

		try {
			GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
			for (String font : fonts)
				if (!ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, Objects.requireNonNull(UpdateWindow.class.getResourceAsStream("/fonts/" + font)))))
					throw new Error("Failed to load fonts");
		} catch (IOException | FontFormatException | NullPointerException e) {
			throw new Error("Failed to load fonts", e);
		}

		instance = new UpdateWindow();
		instance.setVisible(true);

		new Thread(() -> {
			while (instance.isVisible()) {
				instance.repaint();
				Toolkit.getDefaultToolkit().sync();
			}
		}).start();
	}

	public static UpdateWindow getInstance() {
		return instance;
	}

	public static void close() {
		if (instance != null)
			instance.dispose();
	}
}
