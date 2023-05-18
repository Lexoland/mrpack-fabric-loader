package dev.lexoland.updater.rendering;

import dev.lexoland.updater.Updater;

import javax.swing.*;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class UpdateWindow extends JFrame {

	private static UpdateWindow instance;

	static Updater updater;

	private UpdateWindow() {
		super("Updating..");
		setSize(600, 350);
		setResizable(false);
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setLocationRelativeTo(null);
		add(new UpdateRenderer());
		setVisible(true);
	}

	public static void setHeader(String header) {
		UpdateRenderer.setHeader(header);
	}

	public static void advanceToStage(UpdateRenderer.ProgressStage stage) {
		if(stage == UpdateRenderer.ProgressStage.CLOSE) {
			getInstance().dispose();
			return;
		}
		UpdateRenderer.updateStage(stage);
	}

	public static ProgressBar getBar(int index) {
		return UpdateRenderer.getBar(index);
	}

	public static void error(String s, int code) {
		UpdateRenderer.error(s);
		new Thread(() -> {
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			System.exit(code);
		}).start();
	}

	public static UpdateWindow getInstance() {
		if(instance == null) {
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
			return instance = new UpdateWindow();
		}
		return instance;
	}

	public static void setUpdater(Updater updater) {
		UpdateWindow.updater = updater;
	}
}
