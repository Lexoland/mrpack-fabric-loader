package dev.lexoland.updater.rendering.stages;

import dev.lexoland.updater.Updater;

import java.awt.*;

public class DownloadPackMetaStage extends UpdateStage {

	public DownloadPackMetaStage(Updater updater) {
		super(updater, "Downloading...");
	}

	@Override
	public void render(Graphics2D graphics) {
		super.render(graphics);

		renderProgressBar(
				graphics, middleY() - PROGRESS_BAR_HEIGHT / 2,
				(double) downloaded() / downloadSize(),
				"Downloading Pack Meta...", downloadSpeedFormatted(), downloadProgressFormatted()
		);
	}
}
