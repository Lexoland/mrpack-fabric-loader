package dev.lexoland.updater.rendering;

import java.awt.*;

import javax.swing.*;

import dev.lexoland.updater.rendering.stages.UpdateStage;

public class UpdateRenderer extends JLabel {

	private static UpdateStage stage;

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
	}

}
