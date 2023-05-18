package dev.lexoland.updater;

import dev.lexoland.updater.config.Config;

import dev.lexoland.updater.config.ProjectSelectionWindow;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import javax.swing.*;

public class Test {

	public static void main(String[] args) {
		Log.finishBuiltinConfig();

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException |
				 UnsupportedLookAndFeelException e) {
			Log.error(LogCategory.UPDATER, "Failed to set system look and feel", e);
		}

		Config.load();

		if (Config.shouldAskForProject()) {
			ProjectSelectionWindow window = new ProjectSelectionWindow();
			window.setVisible(true);
			return;
		}
		Updater.start();
	}

}
