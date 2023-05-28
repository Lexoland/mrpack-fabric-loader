package dev.lexoland.updater.rendering;

import dev.lexoland.updater.config.Config;

import net.fabricmc.api.EnvType;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.swing.*;

public class UpdateWindow extends JFrame {

	private static UpdateWindow instance;

	private UpdateWindow(EnvType envType, String gameVersion, Runnable onFinish) {
		super("Updating...");
		setSize(600, 350);
		setResizable(false);
		setLocationRelativeTo(null);
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		boolean shouldAskForProject = Config.shouldAskForProject();
		if(shouldAskForProject)
			setTitle("Project Selection");
		
		add(new UpdateRenderer(shouldAskForProject, envType, gameVersion, onFinish, () -> setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE)));
	}

	public static void open(EnvType envType, String gameVersion, Runnable onFinish) {
		if(instance != null)
			return;
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

		instance = new UpdateWindow(envType, gameVersion, onFinish);
		instance.setVisible(true);

		new Thread(() -> {
			while (instance.isVisible()) {
				instance.repaint();
				Toolkit.getDefaultToolkit().sync();
			}
		}, "Render-Thread").start();
	}

	public static UpdateWindow getInstance() {
		return instance;
	}

	public static void close() {
		if (instance != null)
			instance.dispose();
	}
}
