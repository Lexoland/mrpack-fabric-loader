package dev.lexoland.updater.rendering;

import java.awt.*;

import javax.swing.*;

import dev.lexoland.updater.rendering.stages.ProjectSelectionStage;
import dev.lexoland.updater.rendering.stages.UpdateStage;

import net.fabricmc.api.EnvType;

public class UpdateRenderer extends JPanel {

	private static UpdateStage stage;

	public UpdateRenderer(boolean shouldAskForProject, EnvType envType, String gameVersion, Runnable onFinish, Runnable onSelected) {
		if (shouldAskForProject)
			stage = new ProjectSelectionStage(this, envType, gameVersion, onFinish, onSelected);
	}

    public static void setStage(UpdateStage stage) {
		UpdateRenderer.stage = stage;
	}

	@Override
	public void paint(Graphics g) {
		Graphics2D graphics = (Graphics2D) g;

		if (stage == null)
			return;

		stage.setScreenSize(getWidth(), getHeight());
		stage.render(graphics);
		paintComponents(g);
	}
}
